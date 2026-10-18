package com.example.franjahoraria.service;

import com.example.dataacquisition.MaquinasVirtuales;
import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.franjahoraria.model.Franja;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Consumo de la planta por franja horaria, por día y por mes, a partir del contador de energía del
 * medidor general (KWhPlanta1, ION8600): el mismo punto que factura la empresa eléctrica (contrastado con
 * las facturas de marzo, abril y junio de 2026: menos de 0,3 % de diferencia por franja).
 *
 * Cada tramo entre dos lecturas seguidas se reparte entre las franjas que cruza, en proporción al tiempo.
 * Un tramo de más de {@link #MINUTOS_HUECO} minutos (faltan lecturas) se reparte igual, pero se cuenta como
 * horas estimadas. Un tramo con el contador hacia atrás o con un salto imposible no se cuenta (horas sin
 * datos). Mes calendario, como la factura.
 */
@Service
public class ConsumoFranjaService {

    private static final Logger logger = LoggerFactory.getLogger(ConsumoFranjaService.class);

    public static final String MEDIDOR = MaquinasVirtuales.KWH_PLANTA_1;
    public static final int MINUTOS_HUECO = 5;
    private static final double KW_MAXIMO = 20000;
    private static final int FRANJAS = Franja.values().length;
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final FeriadoService feriadoService;
    /** Meses cerrados ya calculados (no cambian, salvo que se carguen feriados de ese mes). */
    private final Map<YearMonth, Mes> cache = new ConcurrentHashMap<>();

    public ConsumoFranjaService(FeriadoService feriadoService) {
        this.feriadoService = feriadoService;
    }

    /** Un día. feriado = nombre si está cargado como feriado, si no null. */
    public record Dia(LocalDate fecha, String feriado, double[] kwh, double horasEstimadas, double horasSinDatos,
                      boolean conDatos) {
        public boolean especial() {
            return feriado != null || Franja.finDeSemana(fecha);
        }

        public double kwh(Franja f) {
            return kwh[f.ordinal()];
        }

        public double total() {
            return Arrays.stream(kwh).sum();
        }
    }

    /** Un mes: sus días (hasta hoy si está en curso) y los totales. */
    public record Mes(YearMonth mes, List<Dia> dias, Map<LocalDate, String> feriados, double[] kwh,
                      double horasEstimadas, double horasSinDatos, boolean enCurso) {
        public double kwh(Franja f) {
            return kwh[f.ordinal()];
        }

        public double total() {
            return Arrays.stream(kwh).sum();
        }

        public boolean conDatos() {
            return dias.stream().anyMatch(Dia::conDatos);
        }
    }

    public Mes calcular(YearMonth mes) {
        Map<LocalDate, String> feriados = feriadoService.delMes(mes);
        Mes guardado = cache.get(mes);
        if (guardado != null && guardado.feriados().equals(feriados)) return guardado;
        Mes m = calcular(mes, feriados, LocalDateTime.now());
        if (!m.enCurso()) cache.put(mes, m);
        return m;
    }

    /**
     * Descarta lo guardado en memoria de un mes cerrado, para releer sus archivos (por ejemplo, después de copiar
     * archivos históricos con la app abierta).
     */
    public void olvidar(YearMonth mes) {
        cache.remove(mes);
    }

    /** Los meses del año hasta el actual. */
    public List<Mes> anio(int anio) {
        List<Mes> lista = new ArrayList<>();
        YearMonth hoy = YearMonth.now();
        for (int i = 1; i <= 12; i++) {
            YearMonth m = YearMonth.of(anio, i);
            if (m.isAfter(hoy)) break;
            lista.add(calcular(m));
        }
        return lista;
    }

    private static final class Acum {
        final double[] kwh = new double[FRANJAS];
        double cubierto, estimado;
    }

    private Mes calcular(YearMonth mes, Map<LocalDate, String> feriados, LocalDateTime ahora) {
        LocalDateTime inicio = mes.atDay(1).atStartOfDay();
        LocalDateTime finMes = mes.plusMonths(1).atDay(1).atStartOfDay();
        LocalDateTime limite = ahora.isBefore(finMes) ? ahora : finMes;
        // Una hora de margen para que el último tramo del mes tenga la primera lectura del mes siguiente.
        boolean enCurso = ahora.isBefore(finMes.plusHours(1));

        Map<LocalDate, Acum> acum = new TreeMap<>();
        for (LocalDate d = inicio.toLocalDate(); d.atStartOfDay().isBefore(limite); d = d.plusDays(1)) acum.put(d, new Acum());
        if (!acum.isEmpty()) {
            Map.Entry<LocalDateTime, Double> a = null;
            for (Map.Entry<LocalDateTime, Double> b : leerSerie(mes).entrySet()) {
                if (a != null && b.getKey().isAfter(inicio) && a.getKey().isBefore(limite)) {
                    double seg = Duration.between(a.getKey(), b.getKey()).toMillis() / 1000.0;
                    double d = b.getValue() - a.getValue();
                    if (seg > 0 && d >= 0 && d <= KW_MAXIMO * seg / 3600 + 1) {
                        repartir(a.getKey(), b.getKey(), d, seg, inicio, limite, seg > MINUTOS_HUECO * 60, acum, feriados);
                    }
                }
                a = b;
            }
        }

        List<Dia> dias = new ArrayList<>();
        double[] total = new double[FRANJAS];
        double estimadas = 0, sinDatos = 0;
        for (Map.Entry<LocalDate, Acum> e : acum.entrySet()) {
            LocalDateTime desde = e.getKey().atStartOfDay();
            LocalDateTime hasta = desde.plusDays(1).isBefore(limite) ? desde.plusDays(1) : limite;
            Acum ac = e.getValue();
            double faltan = Math.max(0, Duration.between(desde, hasta).toMillis() / 1000.0 - ac.cubierto) / 3600;
            dias.add(new Dia(e.getKey(), feriados.get(e.getKey()), ac.kwh, ac.estimado / 3600, faltan, ac.cubierto > 0));
            for (int i = 0; i < FRANJAS; i++) total[i] += ac.kwh[i];
            estimadas += ac.estimado / 3600;
            sinDatos += faltan;
        }
        return new Mes(mes, dias, feriados, total, estimadas, sinDatos, enCurso);
    }

    /** Reparte el consumo del tramo [a, b] entre los días y franjas que cruza, dentro de [inicio, limite). */
    private static void repartir(LocalDateTime a, LocalDateTime b, double kwh, double seg, LocalDateTime inicio,
                                 LocalDateTime limite, boolean estimado, Map<LocalDate, Acum> acum,
                                 Map<LocalDate, String> feriados) {
        LocalDateTime t = a.isBefore(inicio) ? inicio : a;
        LocalDateTime fin = b.isAfter(limite) ? limite : b;
        while (t.isBefore(fin)) {
            LocalDateTime corte = Franja.siguienteCorte(t);
            LocalDateTime hasta = corte.isBefore(fin) ? corte : fin;
            double s = Duration.between(t, hasta).toMillis() / 1000.0;
            LocalDate dia = t.toLocalDate();
            Acum ac = acum.get(dia);
            if (ac != null) {
                Franja f = Franja.de(t, feriados.containsKey(dia) || Franja.finDeSemana(dia));
                ac.kwh[f.ordinal()] += kwh * s / seg;
                ac.cubierto += s;
                if (estimado) ac.estimado += s;
            }
            t = hasta;
        }
    }

    /**
     * Contador kWh del medidor general alrededor del mes. Los archivos mensuales se pisan en el borde (el de
     * un mes puede traer las primeras horas del siguiente), así que de los archivos vecinos se leen también
     * los días del borde; las lecturas repetidas quedan una sola vez.
     */
    private TreeMap<LocalDateTime, Double> leerSerie(YearMonth mes) {
        TreeMap<LocalDateTime, Double> serie = new TreeMap<>();
        LocalDate ini = mes.atDay(1), fin = mes.atEndOfMonth();
        leer(mes.minusMonths(1), List.of(ini.minusDays(1), ini), serie);
        leer(mes, null, serie);
        leer(mes.plusMonths(1), List.of(fin, fin.plusDays(1)), serie);
        return serie;
    }

    /** dias == null: toda la tabla; si no, solo las lecturas de esos días. */
    private void leer(YearMonth m, List<LocalDate> dias, TreeMap<LocalDateTime, Double> serie) {
        String ruta = RutaArchivosEnergia.construirRutaMensual(m.getYear(), m.getMonthValue(), false);
        if (!new File(ruta).exists()) return;
        String sql = "SELECT fecha, kwh FROM " + MEDIDOR
                + (dias == null ? "" : " WHERE " + dias.stream().map(d -> "fecha LIKE ?").collect(Collectors.joining(" OR ")));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta.replace('\\', '/') + "?mode=ro");
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (dias != null) {
                for (int i = 0; i < dias.size(); i++) ps.setString(i + 1, dias.get(i).format(DIA) + "%");
            }
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    LocalDateTime f = fecha(r.getString(1));
                    double v = r.getDouble(2);
                    if (f != null && v > 1) serie.put(f, v); // 0 y basura (1e-42) no son lecturas del contador
                }
            }
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("no such table")) {
                logger.warn("Franja horaria: no se pudo leer {} en {}: {}", MEDIDOR, ruta, e.getMessage());
            }
        }
    }

    /** "dd-MM-yyyy HH:mm:ss" (RutaArchivosEnergia.FORMATO_FECHA_HORA), sin excepciones por filas mal formadas. */
    private static LocalDateTime fecha(String s) {
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
