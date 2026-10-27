package com.example.generador.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.generador.model.CostoCombustible;
import com.example.generador.model.Generador;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Galones de diésel de cada arranque sacados de la planilla MF13 de los operadores (un .xlsx con una hoja por
 * generador). Los controladores no informan el combustible por Modbus (los dos leen 0), así que la planilla es la
 * única fuente. Cada generador dice en generador-config.json ("combustible") cómo se anota el suyo:
 * <ul>
 *   <li>CONTADOR (Caterpillar): el contador de galones consumidos del motor. Galones = contador al parar - al arrancar.</li>
 *   <li>NIVEL_CM (GenPower): el nivel del tanque en cm; el tanque es un cilindro vertical, así que cada cm son
 *       π·r²·0,01 m³ (r = radioTanqueM) x 264,172 gal/m³. Galones = cm que bajó entre lecturas. Un tramo en que el
 *       nivel sube (cargaron diésel en marcha) o sin nivel (FULL) no se puede medir y se estima con el rendimiento
 *       (gal/kWh) del resto del mismo arranque.</li>
 * </ul>
 * Las lecturas se ubican por el contador de energía del controlador (columna PROD KWH, la misma que guarda la app al
 * abrir y cerrar cada arranque), no por la fecha y hora anotadas, que traen errores (24:05, 00:00 del día anterior).
 * Solo calcula: guardar lo hace {@link CostoCombustibleService} después de que el administrador confirma.
 */
@Service
public class PlanillaCombustibleService {

    /** 1 m³ = 264,172 galones US. */
    private static final double GALONES_POR_M3 = 264.172;
    private static final int MAXIMO_BYTES = 10 * 1024 * 1024;
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);
    /** "116.7", "70,1", "119.1 cm", "41 gl"; no "1310,,6", "FULL", "√" ni "191gl(18,1g/h)". */
    private static final Pattern NUMERO = Pattern.compile("^\\s*(-?\\d+(?:[.,]\\d+)?)\\s*([a-zA-Z]{0,3})\\s*$");

    public enum Medida { CONTADOR, NIVEL_CM }

    /** Cómo se anota el combustible de un generador en la planilla (generador-config.json, "combustible"). */
    public record Planilla(String hoja, Medida medida, String columnaValor, String columnaKwh, Double radioTanqueM) {
        public double galonesPorCm() {
            return radioTanqueM == null ? 0 : Math.PI * radioTanqueM * radioTanqueM * 0.01 * GALONES_POR_M3;
        }
    }

    /**
     * Un arranque con lo calculado. galones null = la planilla no alcanza para calcularlo (detalle dice por qué);
     * actual = lo que ya está cargado en Costos (null si nada).
     */
    public record Calculo(String generador, long arranqueId, LocalDateTime inicio, LocalDateTime fin, double kwh,
                          Double galones, String detalle, CostoCombustible actual) {
        public Double kwhPorGalon() {
            return galones == null || galones <= 0 ? null : kwh / galones;
        }
    }

    /** Lo calculado de toda la planilla, y lo que no se pudo usar (hojas faltantes, generadores sin configurar). */
    public record Resultado(List<Calculo> arranques, List<String> avisos) {
    }

    private record Lectura(int fila, double kwh, double valor) {
    }

    private record ArranqueGuardado(long id, LocalDateTime inicio, LocalDateTime fin, double kwhInicio, double kwhFin) {
    }

    private final ConfigLoaderService configLoaderService;
    private final CostoCombustibleService costos;

    public PlanillaCombustibleService(ConfigLoaderService configLoaderService, CostoCombustibleService costos) {
        this.configLoaderService = configLoaderService;
        this.costos = costos;
    }

    /** Calcula los galones de cada arranque terminado que cubre la planilla, para todos los generadores configurados. */
    public Resultado calcular(byte[] xlsx, List<Generador> generadores) throws IOException {
        if (xlsx.length > MAXIMO_BYTES) throw new IOException("El archivo pasa de 10 MB");
        Map<String, List<Map<String, String>>> hojas = leerLibro(xlsx);
        List<Calculo> calculos = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        for (Generador g : generadores) {
            Planilla p = planilla(g.nombre());
            if (p == null) {
                avisos.add(g.nombre() + ": falta \"combustible\" en generador-config.json");
                continue;
            }
            List<Map<String, String>> filas = hojas.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(p.hoja())).map(Map.Entry::getValue).findFirst().orElse(null);
            if (filas == null) {
                avisos.add(g.nombre() + ": la planilla no tiene la hoja \"" + p.hoja() + "\"");
                continue;
            }
            List<Lectura> lecturas = lecturas(filas, p);
            List<Lectura> descartadas = new ArrayList<>();
            if (p.medida() == Medida.CONTADOR) lecturas = sinSaltos(lecturas, descartadas);
            if (lecturas.size() < 2) {
                avisos.add(g.nombre() + ": la hoja \"" + p.hoja() + "\" no tiene lecturas con energía y "
                        + (p.medida() == Medida.CONTADOR ? "contador" : "nivel"));
                continue;
            }
            Map<Long, CostoCombustible> cargados = new HashMap<>();
            costos.delGenerador(g.nombre()).forEach(c -> cargados.put(c.getArranqueId(), c));
            double desde = lecturas.getFirst().kwh(), hasta = lecturas.getLast().kwh();
            for (ArranqueGuardado a : arranques(g.nombre())) {
                double kwh = a.kwhFin() - a.kwhInicio();
                if (kwh < 1 || a.kwhFin() <= desde || a.kwhInicio() >= hasta) continue; // sin carga o fuera de la planilla
                Calculo c = p.medida() == Medida.CONTADOR
                        ? porContador(g.nombre(), a, lecturas, cargados.get(a.id()))
                        : porNivel(g.nombre(), a, lecturas, p.galonesPorCm(), cargados.get(a.id()));
                calculos.add(c);
                // Solo se avisan las lecturas mal anotadas que caen en un arranque: las de meses viejos no importan.
                for (Lectura x : List.copyOf(descartadas)) {
                    if (x.kwh() >= a.kwhInicio() && x.kwh() <= a.kwhFin()) {
                        descartadas.remove(x);
                        avisos.add(String.format(Locale.ROOT, "%s: no se usó la fila %d de la hoja \"%s\" (contador %.0f, "
                                + "no sigue la secuencia de las filas vecinas)", g.nombre(), x.fila(), p.hoja(), x.valor()));
                    }
                }
            }
        }
        calculos.sort(Comparator.comparing(Calculo::inicio).reversed());
        return new Resultado(calculos, avisos);
    }

    /** Lo de generador-config.json, o null si el generador no lo tiene o está incompleto. */
    @SuppressWarnings("unchecked")
    public Planilla planilla(String generador) {
        for (Map<String, Object> g : configLoaderService.loadGeneradoresConfig()) {
            if (!generador.equals(String.valueOf(g.get("nombre"))) || !(g.get("combustible") instanceof Map<?, ?> m)) continue;
            Map<String, Object> c = (Map<String, Object>) m;
            try {
                Medida medida = Medida.valueOf(String.valueOf(c.get("medida")).toUpperCase(Locale.ROOT));
                Double radio = c.get("radioTanqueM") instanceof Number n ? n.doubleValue() : null;
                if (c.get("hoja") == null || c.get("columnaValor") == null || (medida == Medida.NIVEL_CM && (radio == null || radio <= 0))) {
                    return null;
                }
                return new Planilla(String.valueOf(c.get("hoja")), medida, columna(c.get("columnaValor")),
                        c.get("columnaKwh") == null ? "P" : columna(c.get("columnaKwh")), radio);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private static String columna(Object o) {
        return String.valueOf(o).trim().toUpperCase(Locale.ROOT);
    }

    // ---------------- Cálculo ----------------

    private static Calculo porContador(String gen, ArranqueGuardado a, List<Lectura> l, CostoCombustible actual) {
        Double c1 = interpolar(l, a.kwhInicio()), c2 = interpolar(l, a.kwhFin());
        if (c1 == null || c2 == null) {
            return new Calculo(gen, a.id(), a.inicio(), a.fin(), a.kwhFin() - a.kwhInicio(), null,
                    "la planilla no cubre " + (c1 == null ? "el arranque" : "la parada"), actual);
        }
        double gal = redondo(c2 - c1);
        return new Calculo(gen, a.id(), a.inicio(), a.fin(), a.kwhFin() - a.kwhInicio(), gal,
                String.format(Locale.ROOT, "contador %.0f a %.0f", c1, c2), actual);
    }

    /**
     * Suma lo que bajó el nivel en cada tramo entre lecturas (recortado al arranque, en proporción a la energía). Los
     * tramos en que sube y la energía sin lecturas se estiman con el rendimiento de los tramos medidos.
     */
    private static Calculo porNivel(String gen, ArranqueGuardado a, List<Lectura> l, double galCm, CostoCombustible actual) {
        double kwh = a.kwhFin() - a.kwhInicio();
        double cm = 0, kwhMedido = 0, kwhSube = 0;
        int recargas = 0;
        for (int i = 1; i < l.size(); i++) {
            Lectura x = l.get(i - 1), y = l.get(i);
            double lo = Math.max(x.kwh(), a.kwhInicio()), hi = Math.min(y.kwh(), a.kwhFin());
            if (hi <= lo) continue;
            double parte = (hi - lo) / (y.kwh() - x.kwh());
            if (y.valor() <= x.valor()) {
                cm += (x.valor() - y.valor()) * parte;
                kwhMedido += hi - lo;
            } else {
                kwhSube += hi - lo;
                recargas++;
            }
        }
        if (kwhMedido < 1) {
            return new Calculo(gen, a.id(), a.inicio(), a.fin(), kwh, null, "sin lecturas del nivel que bajen", actual);
        }
        double medido = cm * galCm;
        double estimadoKwh = kwh - kwhMedido;
        double gal = redondo(medido + estimadoKwh * medido / kwhMedido);
        StringBuilder d = new StringBuilder(String.format(Locale.ROOT, "bajó %.1f cm x %.3f gal/cm", cm, galCm));
        if (recargas > 0) d.append(String.format(Locale.ROOT, "; %d tramo(s) con carga de diésel", recargas));
        if (estimadoKwh >= 1) d.append(String.format(Locale.ROOT, "; %.0f kWh estimados con el rendimiento", estimadoKwh));
        return new Calculo(gen, a.id(), a.inicio(), a.fin(), kwh, gal, d.toString(), actual);
    }

    /** Valor de la planilla a una energía dada, entre las dos lecturas que la rodean; null fuera de la planilla. */
    private static Double interpolar(List<Lectura> l, double kwh) {
        if (kwh < l.getFirst().kwh() || kwh > l.getLast().kwh()) return null;
        for (int i = 1; i < l.size(); i++) {
            Lectura x = l.get(i - 1), y = l.get(i);
            if (kwh > y.kwh()) continue;
            return y.kwh() == x.kwh() ? y.valor() : x.valor() + (y.valor() - x.valor()) * (kwh - x.kwh()) / (y.kwh() - x.kwh());
        }
        return l.getLast().valor();
    }

    /**
     * Entre dos lecturas el contador nunca baja ni sube más de 0,1 gal por kWh (10 kWh/gal, peor que cualquier
     * rendimiento real). Una lectura que no cuadra con ninguna de sus vecinas, mientras las vecinas sí cuadran entre
     * sí, está mal anotada (ej. 4808 entre 4469 y 4541) y se descarta.
     */
    private static List<Lectura> sinSaltos(List<Lectura> l, List<Lectura> descartadas) {
        List<Lectura> ok = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            Lectura x = l.get(i);
            Lectura antes = i > 0 ? l.get(i - 1) : null, despues = i + 1 < l.size() ? l.get(i + 1) : null;
            Lectura otra = antes == null ? (i + 2 < l.size() ? l.get(i + 2) : null) : despues;
            boolean mala;
            if (antes != null && despues != null) {
                mala = !cuadra(antes, x) && !cuadra(x, despues) && cuadra(antes, despues);
            } else if (antes == null && despues != null && otra != null) { // la primera
                mala = !cuadra(x, despues) && cuadra(despues, otra);
            } else {
                mala = antes != null && !cuadra(antes, x) && i >= 2 && cuadra(l.get(i - 2), antes); // la última
            }
            if (mala) descartadas.add(x);
            else ok.add(x);
        }
        return ok;
    }

    private static boolean cuadra(Lectura a, Lectura b) {
        return b.valor() >= a.valor() && b.valor() - a.valor() <= (b.kwh() - a.kwh()) / 10 + 20;
    }

    /** Filas con energía y valor numéricos, ordenadas por energía (y por fila si se repite). */
    private static List<Lectura> lecturas(List<Map<String, String>> filas, Planilla p) {
        List<Lectura> l = new ArrayList<>();
        for (Map<String, String> f : filas) {
            Double kwh = numero(f.get(p.columnaKwh()), false);
            Double valor = numero(f.get(p.columnaValor()), p.medida() == Medida.CONTADOR);
            if (kwh == null || valor == null || kwh <= 0 || valor < 0) continue;
            l.add(new Lectura(Integer.parseInt(f.get("#")), kwh, valor));
        }
        l.sort(Comparator.comparingDouble(Lectura::kwh).thenComparingInt(Lectura::fila));
        return l;
    }

    /** Número de una celda; para el contador no se acepta "cm" (en filas viejas esa columna era el nivel). */
    private static Double numero(String s, boolean contador) {
        if (s == null) return null;
        Matcher m = NUMERO.matcher(s);
        if (!m.matches()) return null;
        String unidad = m.group(2).toLowerCase(Locale.ROOT);
        if (!unidad.isEmpty() && !unidad.equals("cm") && !unidad.equals("gl") && !unidad.equals("gal")) return null;
        if (contador && unidad.equals("cm")) return null;
        return Double.parseDouble(m.group(1).replace(',', '.'));
    }

    private static double redondo(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private List<ArranqueGuardado> arranques(String generador) {
        List<ArranqueGuardado> lista = new ArrayList<>();
        String ruta = GeneradorAlmacen.rutaArranques();
        if (!new File(ruta).exists()) return lista;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta.replace('\\', '/') + "?mode=ro");
             ResultSet r = c.createStatement().executeQuery("SELECT id, inicio, fin, kwh_inicio, kwh_fin FROM \"" + generador
                     + "\" WHERE fin IS NOT NULL AND kwh_inicio IS NOT NULL AND kwh_fin IS NOT NULL ORDER BY id")) {
            while (r.next()) {
                lista.add(new ArranqueGuardado(r.getLong(1), LocalDateTime.parse(r.getString(2), FECHA),
                        LocalDateTime.parse(r.getString(3), FECHA), r.getDouble(4), r.getDouble(5)));
            }
        } catch (Exception e) {
            // Tabla inexistente = el generador todavía no tuvo arranques.
        }
        return lista;
    }

    // ---------------- Lectura del .xlsx (sin librerías: es un ZIP con XML) ----------------

    /** Hoja -> filas; cada fila = columna ("A", "B"...) -> texto o número tal como está guardado, y "#" = nro de fila. */
    private static Map<String, List<Map<String, String>>> leerLibro(byte[] xlsx) throws IOException {
        Map<String, byte[]> partes = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("xl/workbook.xml") || n.equals("xl/_rels/workbook.xml.rels") || n.equals("xl/sharedStrings.xml")
                        || n.startsWith("xl/worksheets/sheet")) {
                    partes.put(n, zip.readAllBytes());
                }
            }
        }
        if (!partes.containsKey("xl/workbook.xml")) throw new IOException("El archivo no es una planilla de Excel (.xlsx)");
        try {
            DocumentBuilder b = constructor();
            List<String> textos = new ArrayList<>();
            if (partes.containsKey("xl/sharedStrings.xml")) {
                NodeList si = b.parse(new ByteArrayInputStream(partes.get("xl/sharedStrings.xml"))).getElementsByTagNameNS("*", "si");
                for (int i = 0; i < si.getLength(); i++) textos.add(textoDe((Element) si.item(i)));
            }
            Map<String, String> destinos = new HashMap<>();
            if (partes.containsKey("xl/_rels/workbook.xml.rels")) {
                NodeList rel = b.parse(new ByteArrayInputStream(partes.get("xl/_rels/workbook.xml.rels"))).getElementsByTagNameNS("*", "Relationship");
                for (int i = 0; i < rel.getLength(); i++) {
                    Element r = (Element) rel.item(i);
                    String t = r.getAttribute("Target");
                    destinos.put(r.getAttribute("Id"), t.startsWith("/") ? t.substring(1) : "xl/" + t);
                }
            }
            Map<String, List<Map<String, String>>> hojas = new HashMap<>();
            NodeList sheets = b.parse(new ByteArrayInputStream(partes.get("xl/workbook.xml"))).getElementsByTagNameNS("*", "sheet");
            for (int i = 0; i < sheets.getLength(); i++) {
                Element s = (Element) sheets.item(i);
                String id = s.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                byte[] xml = partes.get(destinos.get(id));
                if (xml != null) hojas.put(s.getAttribute("name"), filas(b.parse(new ByteArrayInputStream(xml)), textos));
            }
            return hojas;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("No se pudo leer la planilla: " + e.getMessage(), e);
        }
    }

    private static List<Map<String, String>> filas(Document hoja, List<String> textos) {
        List<Map<String, String>> filas = new ArrayList<>();
        NodeList rows = hoja.getElementsByTagNameNS("*", "row");
        for (int i = 0; i < rows.getLength(); i++) {
            Element row = (Element) rows.item(i);
            Map<String, String> f = new HashMap<>();
            f.put("#", row.getAttribute("r"));
            NodeList cs = row.getElementsByTagNameNS("*", "c");
            for (int j = 0; j < cs.getLength(); j++) {
                Element c = (Element) cs.item(j);
                String ref = c.getAttribute("r").replaceAll("\\d", "");
                String tipo = c.getAttribute("t");
                NodeList v = c.getElementsByTagNameNS("*", "v");
                String valor;
                if (tipo.equals("inlineStr")) {
                    valor = textoDe(c);
                } else if (v.getLength() == 0) {
                    continue;
                } else if (tipo.equals("s")) {
                    int k = Integer.parseInt(v.item(0).getTextContent().trim());
                    valor = k < textos.size() ? textos.get(k) : null;
                } else {
                    valor = v.item(0).getTextContent();
                }
                if (valor != null) f.put(ref, valor.trim());
            }
            if (!f.get("#").isEmpty()) filas.add(f);
        }
        return filas;
    }

    private static String textoDe(Element e) {
        StringBuilder s = new StringBuilder();
        NodeList t = e.getElementsByTagNameNS("*", "t");
        for (int i = 0; i < t.getLength(); i++) s.append(t.item(i).getTextContent());
        return s.toString();
    }

    private static DocumentBuilder constructor() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }
}
