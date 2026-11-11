package com.example.lotes.service;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.File;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lee los lotes de la hoja MEDICION_XTR (o MEDICION_XTRaaaa) de un Excel de medición: inicio (B, C), fin (E, F),
 * LÍNEA (I), ITEM (L), total de material procesado en kg (T, SUMIFS de PRODUCCIÓN) y kWh (U). Usa los valores que
 * Excel dejó calculados en el archivo. Descarta las piezas rojas de corte de mes (relleno FFFFC7CE en la fecha de
 * inicio) y las filas contenidas en otra más larga del mismo producto (se queda el lote completo, la fila verde).
 */
public final class LotesExcelReader {

    /** Lote leído del Excel. clave = ITEM en mayúsculas con un espacio (igual que el catálogo). */
    public record LoteExcel(int anio, int fila, String linea, String clave, String item, LocalDateTime inicio,
                            LocalDateTime fin, double kwh, double kg) {
        public double minutos() { return Duration.between(inicio, fin).toSeconds() / 60.0; }
        public double horas() { return minutos() / 60.0; }
    }

    private static final String ROJO = "FFFFC7CE";
    private static final LocalDate BASE_EXCEL = LocalDate.of(1899, 12, 30);

    private LotesExcelReader() {
    }

    /** Hoja de lotes del libro: MEDICION_XTR + año si existe (libros de 2026), si no MEDICION_XTR. */
    static List<LoteExcel> leer(File archivo, int anio) throws Exception {
        try (ZipFile zip = new ZipFile(archivo)) {
            String ruta = CatalogoProductosService.rutaHoja(zip, "MEDICION_XTR" + anio);
            if (ruta == null) ruta = CatalogoProductosService.rutaHoja(zip, "MEDICION_XTR");
            if (ruta == null) throw new IllegalStateException("no tiene la hoja MEDICION_XTR");
            List<String> textos = CatalogoProductosService.textosCompartidos(zip);
            Set<Integer> estilosRojos = estilosRojos(zip);
            List<LoteExcel> salida = new ArrayList<>();
            XMLInputFactory xf = CatalogoProductosService.fabrica();
            try (InputStream in = zip.getInputStream(zip.getEntry(ruta))) {
                XMLStreamReader r = xf.createXMLStreamReader(in);
                Map<String, String> fila = new HashMap<>();
                int nFila = 0;
                boolean roja = false;
                String col = null, tipo = null;
                StringBuilder valor = null;
                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        switch (r.getLocalName()) {
                            case "row" -> {
                                fila.clear();
                                roja = false;
                                String rr = r.getAttributeValue(null, "r");
                                nFila = rr == null ? nFila + 1 : Integer.parseInt(rr);
                            }
                            case "c" -> {
                                col = r.getAttributeValue(null, "r").replaceAll("\\d", "");
                                tipo = r.getAttributeValue(null, "t");
                                String s = r.getAttributeValue(null, "s");
                                if ("B".equals(col) && s != null && estilosRojos.contains(Integer.parseInt(s))) roja = true;
                            }
                            case "v", "t" -> valor = new StringBuilder();
                            default -> { }
                        }
                    } else if (ev == XMLStreamConstants.CHARACTERS && valor != null) {
                        valor.append(r.getText());
                    } else if (ev == XMLStreamConstants.END_ELEMENT) {
                        switch (r.getLocalName()) {
                            case "v", "t" -> {
                                if (valor != null && col != null) {
                                    String v = valor.toString();
                                    if ("s".equals(tipo)) {
                                        int k = Integer.parseInt(v.trim());
                                        v = k < textos.size() ? textos.get(k) : null;
                                    }
                                    if (v != null) fila.merge(col, v, String::concat);
                                }
                                valor = null;
                            }
                            case "row" -> {
                                if (!roja) {
                                    LoteExcel l = lote(fila, nFila, anio);
                                    if (l != null) salida.add(l);
                                }
                            }
                            default -> { }
                        }
                    }
                }
                r.close();
            }
            return sinPiezas(salida);
        }
    }

    private static LoteExcel lote(Map<String, String> f, int nFila, int anio) {
        String linea = CatalogoProductosService.linea(f.get("I"));
        String item = limpiar(f.get("L"));
        Double b = num(f.get("B")), c = num(f.get("C")), e = num(f.get("E")), h = num(f.get("F"));
        Double kg = num(f.get("T")), kwh = num(f.get("U"));
        if (linea == null || item.isEmpty() || b == null || c == null || e == null || h == null || kg == null || kwh == null) return null;
        if (kg <= 0 || kwh <= 0 || b < 1 || e < 1) return null;
        LocalDateTime ini = fecha(b, c), fin = fecha(e, h);
        if (!fin.isAfter(ini)) return null;
        return new LoteExcel(anio, nFila, linea, item.toUpperCase(Locale.ROOT), item, ini, fin, kwh, kg);
    }

    /** Fecha (serie de Excel, se usa la parte entera) + hora (fracción del día). */
    private static LocalDateTime fecha(double dia, double hora) {
        double fr = hora - Math.floor(hora);
        long seg = Math.round(fr * 86400 / 60.0) * 60;
        return LocalDateTime.of(BASE_EXCEL.plusDays((long) Math.floor(dia)), LocalTime.MIDNIGHT).plusSeconds(seg);
    }

    /** Fila repetida o contenida en otra más larga del mismo producto: se queda la completa. */
    private static List<LoteExcel> sinPiezas(List<LoteExcel> l) {
        List<LoteExcel> out = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        for (LoteExcel x : l) {
            if (!vistos.add(x.linea() + "|" + x.clave() + "|" + x.inicio() + "|" + x.fin() + "|" + Math.round(x.kwh() * 10))) continue;
            boolean contenida = false;
            for (LoteExcel y : l) {
                if (y != x && y.linea().equals(x.linea()) && y.clave().equals(x.clave()) && !y.inicio().isAfter(x.inicio())
                        && !x.fin().isAfter(y.fin()) && y.minutos() > x.minutos()) {
                    contenida = true;
                    break;
                }
            }
            if (!contenida) out.add(x);
        }
        return out;
    }

    /** Índices de cellXfs cuyo relleno es el rojo de las piezas de corte de mes. */
    private static Set<Integer> estilosRojos(ZipFile zip) throws Exception {
        Set<Integer> rojos = new HashSet<>();
        ZipEntry e = zip.getEntry("xl/styles.xml");
        if (e == null) return rojos;
        Set<Integer> fillsRojos = new HashSet<>();
        try (InputStream in = zip.getInputStream(e)) {
            XMLStreamReader r = CatalogoProductosService.fabrica().createXMLStreamReader(in);
            String seccion = "";
            int nFill = -1, nXf = -1;
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) continue;
                switch (r.getLocalName()) {
                    case "fills", "cellXfs", "cellStyleXfs", "dxfs" -> seccion = r.getLocalName();
                    case "fill" -> { if (seccion.equals("fills")) nFill++; }
                    case "fgColor" -> {
                        if (seccion.equals("fills") && ROJO.equalsIgnoreCase(r.getAttributeValue(null, "rgb"))) fillsRojos.add(nFill);
                    }
                    case "xf" -> {
                        if (seccion.equals("cellXfs")) {
                            nXf++;
                            String fid = r.getAttributeValue(null, "fillId");
                            if (fid != null && fillsRojos.contains(Integer.parseInt(fid))) rojos.add(nXf);
                        }
                    }
                    default -> { }
                }
            }
        }
        return rojos;
    }

    private static Double num(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String limpiar(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }
}
