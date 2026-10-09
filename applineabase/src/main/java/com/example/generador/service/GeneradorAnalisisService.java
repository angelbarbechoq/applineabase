package com.example.generador.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.generador.model.Generador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Análisis del grupo electrógeno por día, semana o mes: energía generada (contador del
 * controlador), energía importada de la red y exportada a la red (contadores kWh y KWhR del
 * medidor del transformador asociado, leído por PLC), consumo del tablero, horas de marcha en
 * paralelo con la red / en isla / en vacío y arranques.
 *
 * Toda energía sale de diferencias de contadores (no de integrar potencias): las lecturas
 * faltantes no pierden energía, solo la corren a la lectura siguiente. Se descartan valores en
 * cero o basura (ceros de F-10, 1e-42) y saltos imposibles. Las filas repetidas entre archivos
 * mensuales (el de septiembre tiene las primeras horas del 1 de octubre) se cuentan una vez.
 */
@Service
public class GeneradorAnalisisService {

    private static final Logger logger = LoggerFactory.getLogger(GeneradorAnalisisService.class);

    /** En marcha con menos de esto = en vacío (sin carga). */
    public static final double KW_VACIO = 10.0;
    /** Tensión de red (promedio fase-fase, medida por el controlador) a partir de la cual hay red. */
    public static final double V_RED_PRESENTE = 100.0;
    /** Minutos que cuenta como máximo cada lectura en marcha (se guarda una por minuto). */
    private static final double MAX_MIN_POR_LECTURA = 2.0;
    /** Plausibilidad de los contadores: potencia máxima creíble (kW). */
    private static final double KW_MAX_RED = 20000, KW_MAX_GENERADOR = 5000;

    private static final String[] MESES = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
            "agosto", "septiembre", "octubre", "noviembre", "diciembre"};
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter DIA_CORTO = DateTimeFormatter.ofPattern("dd-MM");

    public enum Agrupacion {
        DIA("Dia"), SEMANA("Semana"), MES("Mes");

        private final String etiqueta;

        Agrupacion(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        public String etiqueta() {
            return etiqueta;
        }
    }

    /** Un período. null = sin datos (distinto de 0). */
    public record Fila(String periodo, LocalDate desde, LocalDate hasta,
                       Double redImportada, Double redExportada, Double generado,
                       Double generadoParalelo, Double generadoIsla,
                       Double horasMarcha, Double horasParalelo, Double horasIsla, Double horasVacio,
                       Integer arranques, Double kwMax) {

        /** Lo que consumió el tablero: lo que entró de la red + lo generado - lo que volvió a la red. */
        public Double consumo() {
            if (redImportada == null && generado == null) return null;
            return Math.max(0, nz(redImportada) + nz(generado) - nz(redExportada));
        }

        /** Parte del consumo cubierta por el generador (%), descontando lo que se exportó. */
        public Double aporteGenerador() {
            Double c = consumo();
            if (c == null || c <= 0 || generado == null) return null;
            return Math.max(0, Math.min(100, (generado - nz(redExportada)) / c * 100));
        }
    }

    /** Acumulado de un día (campos null hasta que llega un dato). */
    private static final class Acum {
        Double redImportada, redExportada, generado, generadoParalelo, generadoIsla;
        Double horasMarcha, horasParalelo, horasIsla, horasVacio, kwMax;
        Integer arranques;

        void sumar(Acum o) {
            redImportada = suma(redImportada, o.redImportada);
            redExportada = suma(redExportada, o.redExportada);
            generado = suma(generado, o.generado);
            generadoParalelo = suma(generadoParalelo, o.generadoParalelo);
            generadoIsla = suma(generadoIsla, o.generadoIsla);
            horasMarcha = suma(horasMarcha, o.horasMarcha);
            horasParalelo = suma(horasParalelo, o.horasParalelo);
            horasIsla = suma(horasIsla, o.horasIsla);
            horasVacio = suma(horasVacio, o.horasVacio);
            // Con if: un ternario Integer/int o Double/double desempaqueta el null y falla.
            if (o.arranques != null) arranques = arranques == null ? o.arranques : Integer.valueOf(arranques + o.arranques);
            if (o.kwMax != null) kwMax = kwMax == null ? o.kwMax : Double.valueOf(Math.max(kwMax, o.kwMax));
        }
    }

    private record LecturaGuardada(LocalDateTime fecha, boolean marcha, Double kw, Double kwh, Double horas,
                                   Double arranques, Double redV) {
    }

    private final GeneradorService generadorService;

    public GeneradorAnalisisService(GeneradorService generadorService) {
        this.generadorService = generadorService;
    }

    /**
     * Filas por período entre dos fechas (inclusive) para uno o varios generadores. Con varios, la
     * red es la suma de sus transformadores asociados (cada uno una vez).
     */
    public List<Fila> analizar(List<Generador> generadores, Agrupacion agrupacion, LocalDate desde, LocalDate hasta) {
        TreeMap<LocalDate, Acum> dias = new TreeMap<>();
        for (LocalDate d = desde; !d.isAfter(hasta); d = d.plusDays(1)) dias.put(d, new Acum());

        Set<String> redes = new LinkedHashSet<>();
        for (Generador g : generadores) {
            if (g.redAsociada() != null) redes.add(g.redAsociada());
            acumularGenerador(g, desde, hasta, dias);
        }
        for (String red : redes) {
            acumularRed(red, desde, hasta, dias);
        }

        List<Fila> filas = new ArrayList<>();
        LocalDate inicio = desde;
        while (!inicio.isAfter(hasta)) {
            LocalDate fin = switch (agrupacion) {
                case DIA -> inicio;
                case SEMANA -> inicio.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                case MES -> inicio.with(TemporalAdjusters.lastDayOfMonth());
            };
            if (fin.isAfter(hasta)) fin = hasta;
            Acum a = new Acum();
            for (Acum d : dias.subMap(inicio, true, fin, true).values()) a.sumar(d);
            filas.add(new Fila(etiqueta(agrupacion, inicio, fin), inicio, fin, a.redImportada, a.redExportada, a.generado,
                    a.generadoParalelo, a.generadoIsla, a.horasMarcha, a.horasParalelo, a.horasIsla, a.horasVacio,
                    a.arranques, a.kwMax));
            inicio = fin.plusDays(1);
        }
        return filas;
    }

    /** Totales de una lista de filas (para las tarjetas de resumen). */
    public Fila total(List<Fila> filas) {
        Acum a = new Acum();
        for (Fila f : filas) {
            Acum x = new Acum();
            x.redImportada = f.redImportada();
            x.redExportada = f.redExportada();
            x.generado = f.generado();
            x.generadoParalelo = f.generadoParalelo();
            x.generadoIsla = f.generadoIsla();
            x.horasMarcha = f.horasMarcha();
            x.horasParalelo = f.horasParalelo();
            x.horasIsla = f.horasIsla();
            x.horasVacio = f.horasVacio();
            x.arranques = f.arranques();
            x.kwMax = f.kwMax();
            a.sumar(x);
        }
        LocalDate desde = filas.isEmpty() ? null : filas.get(0).desde();
        LocalDate hasta = filas.isEmpty() ? null : filas.get(filas.size() - 1).hasta();
        return new Fila("Total", desde, hasta, a.redImportada, a.redExportada, a.generado, a.generadoParalelo,
                a.generadoIsla, a.horasMarcha, a.horasParalelo, a.horasIsla, a.horasVacio, a.arranques, a.kwMax);
    }

    private static String etiqueta(Agrupacion agrupacion, LocalDate desde, LocalDate hasta) {
        return switch (agrupacion) {
            case DIA -> desde.format(DIA);
            case SEMANA -> "Semana " + desde.format(DIA_CORTO) + " al " + hasta.format(DIA_CORTO);
            case MES -> MESES[desde.getMonthValue() - 1] + " " + desde.getYear();
        };
    }

    // ================= Períodos en marcha =================

    /**
     * Un período en marcha (un arranque): lo que trabajó el generador y lo que pasó con la red en ese
     * mismo intervalo. fin = ahora si sigue en marcha.
     */
    public record PeriodoMarcha(String generador, String red, long arranqueId, Integer nro, LocalDateTime inicio,
                                LocalDateTime fin, boolean enCurso, boolean inicioEstimado,
                                Double kwhGenerado, Double kwhRedImportada, Double kwhRedExportada, Double kwMax,
                                double minParalelo, double minIsla, double minVacio) {

        public double horas() {
            return Duration.between(inicio, fin).toSeconds() / 3600.0;
        }

        public Double kwMedio() {
            return kwhGenerado == null || horas() <= 0 ? null : kwhGenerado / horas();
        }

        public Double consumo() {
            if (kwhRedImportada == null && kwhGenerado == null) return null;
            return Math.max(0, nz(kwhRedImportada) + nz(kwhGenerado) - nz(kwhRedExportada));
        }

        public Double aporteGenerador() {
            Double c = consumo();
            if (c == null || c <= 0 || kwhGenerado == null) return null;
            return Math.max(0, Math.min(100, (kwhGenerado - nz(kwhRedExportada)) / c * 100));
        }
    }

    /** Arranques que se pisan con el período elegido (completos, no recortados), del más reciente al más viejo. */
    public List<PeriodoMarcha> periodosEnMarcha(List<Generador> generadores, LocalDate desde, LocalDate hasta) {
        LocalDateTime ini = desde.atStartOfDay();
        LocalDateTime fin = hasta.atTime(23, 59, 59);
        List<PeriodoMarcha> lista = new ArrayList<>();
        for (Generador g : generadores) {
            List<GeneradorService.Arranque> arranques = generadorService.arranques(g).stream()
                    .filter(a -> !a.inicio().isAfter(fin) && (a.fin() == null || !a.fin().isBefore(ini))).toList();
            if (arranques.isEmpty()) continue;
            LocalDate primero = arranques.stream().map(a -> a.inicio().toLocalDate()).min(LocalDate::compareTo).orElse(desde);
            LocalDate ultimo = arranques.stream().map(a -> a.fin() == null ? LocalDate.now() : a.fin().toLocalDate())
                    .max(LocalDate::compareTo).orElse(hasta);
            TreeMap<LocalDateTime, LecturaGuardada> lecturas = lecturasGenerador(g, primero, ultimo);
            TreeMap<LocalDateTime, Double> kwhGen = new TreeMap<>();
            lecturas.values().forEach(l -> { if (l.kwh() != null && l.kwh() > 0) kwhGen.put(l.fecha(), l.kwh()); });
            boolean redValida = g.redAsociada() != null && PLCDataQueryService.esNombreMaquinaValido(g.redAsociada());
            TreeMap<LocalDateTime, Double> importada = redValida ? serieContador(g.redAsociada(), false, primero, ultimo) : new TreeMap<>();
            TreeMap<LocalDateTime, Double> exportada = redValida ? serieContador(g.redAsociada(), true, primero, ultimo) : new TreeMap<>();

            for (GeneradorService.Arranque a : arranques) {
                LocalDateTime f = a.fin() == null ? LocalDateTime.now().withNano(0) : a.fin();
                Double generado = a.kwhGenerados() != null ? Double.valueOf(a.kwhGenerados())
                        : deltaEnVentana(kwhGen, a.inicio(), f, KW_MAX_GENERADOR);
                double[] min = new double[3]; // paralelo, isla, vacío
                Double kwMax = a.kwMax();
                LecturaGuardada previa = null;
                for (LecturaGuardada l : lecturas.subMap(a.inicio(), true, f, true).values()) {
                    if (previa != null && previa.marcha()) {
                        double m = Math.min(Duration.between(previa.fecha(), l.fecha()).toSeconds() / 60.0, MAX_MIN_POR_LECTURA);
                        if (previa.kw() != null && previa.kw() < KW_VACIO) min[2] += m;
                        else if (previa.redV() != null && previa.redV() >= V_RED_PRESENTE) min[0] += m;
                        else if (previa.redV() != null) min[1] += m;
                    }
                    if (l.marcha() && l.kw() != null && (kwMax == null || l.kw() > kwMax)) kwMax = l.kw();
                    previa = l;
                }
                lista.add(new PeriodoMarcha(g.nombre(), g.redAsociada(), a.id(), a.arranqueNro(), a.inicio(), f, a.fin() == null,
                        a.inicioEstimado(), generado, deltaEnVentana(importada, a.inicio(), f, KW_MAX_RED),
                        deltaEnVentana(exportada, a.inicio(), f, KW_MAX_RED), kwMax, min[0], min[1], min[2]));
            }
        }
        lista.sort((x, y) -> y.inicio().compareTo(x.inicio()));
        return lista;
    }

    /**
     * Aumento de un contador dentro de [ini, fin]. Las lecturas son cada ~1 min y no coinciden con el
     * inicio/fin del arranque: el intervalo que cruza un borde aporta en proporción al tiempo que cae
     * adentro. null = sin lecturas que cubran el intervalo.
     */
    static Double deltaEnVentana(TreeMap<LocalDateTime, Double> serie, LocalDateTime ini, LocalDateTime fin, double kwMax) {
        Map.Entry<LocalDateTime, Double> a = serie.floorEntry(ini);
        if (a == null) a = serie.ceilingEntry(ini);
        if (a == null || a.getKey().isAfter(fin)) return null;
        double total = 0;
        boolean cubierto = false;
        for (Map.Entry<LocalDateTime, Double> b : serie.tailMap(a.getKey(), false).entrySet()) {
            long tramo = Duration.between(a.getKey(), b.getKey()).toSeconds();
            LocalDateTime desdeAdentro = a.getKey().isAfter(ini) ? a.getKey() : ini;
            LocalDateTime hastaAdentro = b.getKey().isBefore(fin) ? b.getKey() : fin;
            long adentro = Duration.between(desdeAdentro, hastaAdentro).toSeconds();
            Double d = tramo <= 0 ? null : delta(a.getValue(), b.getValue(), kwMax * tramo / 3600.0 + 1);
            if (d != null && adentro > 0) {
                total += d * adentro / tramo;
                cubierto = true;
            }
            if (!b.getKey().isBefore(fin)) break;
            a = b;
        }
        return cubierto ? total : null;
    }

    // ================= Generador =================

    private void acumularGenerador(Generador g, LocalDate desde, LocalDate hasta, TreeMap<LocalDate, Acum> dias) {
        TreeMap<LocalDateTime, LecturaGuardada> lecturas = lecturasGenerador(g, desde, hasta);
        LecturaGuardada a = null;
        for (LecturaGuardada b : lecturas.values()) {
            LocalDate dia = b.fecha().toLocalDate();
            Acum acum = dias.get(dia);
            if (acum != null) {
                // Día con lecturas del generador: energía/horas/arranques en 0 (no "sin datos").
                acum.generado = suma(acum.generado, 0.0);
                acum.horasMarcha = suma(acum.horasMarcha, 0.0);
                acum.arranques = acum.arranques == null ? 0 : acum.arranques;
                if (b.marcha() && b.kw() != null && (acum.kwMax == null || b.kw() > acum.kwMax)) acum.kwMax = b.kw();
            }
            if (a != null && acum != null) {
                double horas = Duration.between(a.fecha(), b.fecha()).toSeconds() / 3600.0;
                // Energía: del contador; se clasifica con la lectura en marcha del intervalo.
                LecturaGuardada enMarcha = b.marcha() ? b : a.marcha() ? a : null;
                Double dkwh = delta(a.kwh(), b.kwh(), KW_MAX_GENERADOR * horas + 1);
                if (dkwh != null) {
                    acum.generado += dkwh;
                    if (enMarcha != null && enMarcha.redV() != null) {
                        if (enMarcha.redV() >= V_RED_PRESENTE) acum.generadoParalelo = suma(acum.generadoParalelo, dkwh);
                        else acum.generadoIsla = suma(acum.generadoIsla, dkwh);
                    }
                }
                // Horas: del horómetro del controlador; si no lo da, de las lecturas en marcha.
                Double dh = delta(a.horas(), b.horas(), horas + 0.15);
                double minutos = a.marcha() ? Math.min(horas * 60, MAX_MIN_POR_LECTURA) : 0;
                acum.horasMarcha += dh != null ? dh : minutos / 60.0;
                if (minutos > 0) {
                    if (a.kw() != null && a.kw() < KW_VACIO) acum.horasVacio = suma(acum.horasVacio, minutos / 60.0);
                    else if (a.redV() != null && a.redV() >= V_RED_PRESENTE) acum.horasParalelo = suma(acum.horasParalelo, minutos / 60.0);
                    else if (a.redV() != null) acum.horasIsla = suma(acum.horasIsla, minutos / 60.0);
                }
                Double darr = delta(a.arranques(), b.arranques(), 50);
                if (darr != null) acum.arranques += (int) Math.round(darr);
            }
            a = b;
        }
    }

    /** Lecturas guardadas del generador entre el día anterior a {@code desde} y {@code hasta} (sin repetidas). */
    private TreeMap<LocalDateTime, LecturaGuardada> lecturasGenerador(Generador g, LocalDate desde, LocalDate hasta) {
        TreeMap<LocalDateTime, LecturaGuardada> lecturas = new TreeMap<>();
        for (YearMonth m = YearMonth.from(desde.minusDays(1)); !m.isAfter(YearMonth.from(hasta)); m = m.plusMonths(1)) {
            String ruta = GeneradorAlmacen.rutaLecturas(m);
            if (!new File(ruta).exists()) continue;
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta.replace('\\', '/') + "?mode=ro");
                 ResultSet r = c.createStatement().executeQuery("SELECT * FROM \"" + g.nombre() + "\"")) {
                Set<String> cols = columnas(r.getMetaData());
                while (r.next()) {
                    LocalDateTime f = fecha(r.getString("fecha"));
                    if (f == null || f.toLocalDate().isBefore(desde.minusDays(1)) || f.toLocalDate().isAfter(hasta)) continue;
                    Double v1 = num(r, cols, "red_v_l1l2"), v2 = num(r, cols, "red_v_l2l3"), v3 = num(r, cols, "red_v_l3l1");
                    Double redV = v1 == null || v2 == null || v3 == null ? null : (v1 + v2 + v3) / 3.0;
                    lecturas.put(f, new LecturaGuardada(f, "MARCHA".equals(r.getString("estado")), num(r, cols, "kw"),
                            num(r, cols, "kwh"), num(r, cols, "horas_marcha"), num(r, cols, "arranques"), redV));
                }
            } catch (Exception e) {
                if (!String.valueOf(e.getMessage()).contains("no such table")) {
                    logger.warn("Analisis: no se pudieron leer las lecturas de {} en {}: {}", g.nombre(), ruta, e.getMessage());
                }
            }
        }
        return lecturas;
    }

    // ================= Red (medidor del transformador, leído por PLC) =================

    private void acumularRed(String maquina, LocalDate desde, LocalDate hasta, TreeMap<LocalDate, Acum> dias) {
        if (!PLCDataQueryService.esNombreMaquinaValido(maquina)) {
            logger.warn("Analisis: nombre de red invalido {}", maquina);
            return;
        }
        sumarDeltas(serieContador(maquina, false, desde, hasta), dias, true);
        sumarDeltas(serieContador(maquina, true, desde, hasta), dias, false);
    }

    /** Contador del medidor de la red: kWh importado (archivo normal) o KWhR exportado (archivo VIP). */
    private TreeMap<LocalDateTime, Double> serieContador(String maquina, boolean exportada, LocalDate desde, LocalDate hasta) {
        TreeMap<LocalDateTime, Double> serie = new TreeMap<>();
        for (YearMonth m = YearMonth.from(desde.minusDays(1)); !m.isAfter(YearMonth.from(hasta)); m = m.plusMonths(1)) {
            leerContador(RutaArchivosEnergia.construirRutaMensual(m.getYear(), m.getMonthValue(), exportada), maquina,
                    exportada ? "KWhR" : "kwh", desde, hasta, serie);
        }
        return serie;
    }

    private void leerContador(String ruta, String maquina, String columna, LocalDate desde, LocalDate hasta,
                              TreeMap<LocalDateTime, Double> serie) {
        if (!new File(ruta).exists()) return;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta.replace('\\', '/') + "?mode=ro");
             ResultSet r = c.createStatement().executeQuery("SELECT fecha, " + columna + " FROM " + maquina)) {
            LocalDate antes = desde.minusDays(1);
            while (r.next()) {
                LocalDateTime f = fecha(r.getString(1));
                if (f == null || f.toLocalDate().isBefore(antes) || f.toLocalDate().isAfter(hasta)) continue;
                double v = r.getDouble(2);
                if (v > 1) serie.put(f, v); // 0 y basura (1e-42) no son lecturas del contador
            }
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("no such table")) {
                logger.warn("Analisis: no se pudo leer {} de {} en {}: {}", columna, maquina, ruta, e.getMessage());
            }
        }
    }

    private static void sumarDeltas(TreeMap<LocalDateTime, Double> serie, TreeMap<LocalDate, Acum> dias, boolean importada) {
        Map.Entry<LocalDateTime, Double> a = null;
        for (Map.Entry<LocalDateTime, Double> b : serie.entrySet()) {
            Acum acum = dias.get(b.getKey().toLocalDate());
            if (acum != null) {
                if (importada) acum.redImportada = suma(acum.redImportada, 0.0);
                else acum.redExportada = suma(acum.redExportada, 0.0);
            }
            if (a != null && acum != null) {
                double horas = Duration.between(a.getKey(), b.getKey()).toSeconds() / 3600.0;
                Double d = delta(a.getValue(), b.getValue(), KW_MAX_RED * horas + 1);
                if (d != null) {
                    if (importada) acum.redImportada += d;
                    else acum.redExportada += d;
                }
            }
            a = b;
        }
    }

    /** Diferencia de un contador, o null si falta un dato, bajó (reinicio) o el salto es imposible. */
    private static Double delta(Double a, Double b, double maximo) {
        if (a == null || b == null) return null;
        double d = b - a;
        return d < 0 || d > maximo ? null : d;
    }

    private static Double suma(Double a, Double b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + b;
    }

    private static double nz(Double v) {
        return v == null ? 0 : v;
    }

    private static Set<String> columnas(ResultSetMetaData md) throws java.sql.SQLException {
        Set<String> s = new HashSet<>();
        for (int i = 1; i <= md.getColumnCount(); i++) s.add(md.getColumnName(i).toLowerCase(Locale.ROOT));
        return s;
    }

    private static Double num(ResultSet r, Set<String> cols, String col) throws java.sql.SQLException {
        if (!cols.contains(col)) return null;
        Object o = r.getObject(col);
        return o instanceof Number n ? n.doubleValue() : null;
    }

    /** "dd-MM-yyyy HH:mm:ss" sin DateTimeFormatter (se leen cientos de miles de filas). */
    static LocalDateTime fecha(String s) {
        if (s == null || s.length() < 19) return null;
        try {
            return LocalDateTime.of(Integer.parseInt(s.substring(6, 10)), Integer.parseInt(s.substring(3, 5)),
                    Integer.parseInt(s.substring(0, 2)), Integer.parseInt(s.substring(11, 13)),
                    Integer.parseInt(s.substring(14, 16)), Integer.parseInt(s.substring(17, 19)));
        } catch (Exception e) {
            return null;
        }
    }
}
