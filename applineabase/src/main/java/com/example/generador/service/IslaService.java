package com.example.generador.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.generador.model.EventoGenerador;
import com.example.generador.model.Generador;
import com.example.generador.model.TipoEventoGenerador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Isla: se abrió el interruptor de la planta (MCB) por una protección de red (ROCOF, VectorShift...) y el generador
 * quedó alimentando solo su parte de la planta hasta que el controlador vuelve a sincronizar (retorno de red, 900 s en
 * el Caterpillar). El controlador sigue viendo tensión de red, así que no sale de sus lecturas ni de su lista de
 * alarmas; las protecciones duran menos de 1 s y quedan solo en su historial interno, que no se lee por Modbus.
 *
 * Firma (comprobada con todo octubre 2026: solo la cumplen las islas del 11-10, 61 min, y del 21-10, 16 min):
 * 1. el generador en marcha con carga;
 * 2. el medidor de su transformador en 0 (|PW| &lt; 1 kW) en {@link #LECTURAS_MINIMAS} lecturas seguidas o más
 *    (los ceros sueltos del medidor, 171 en 13 días, nunca pasaron de 2 lecturas);
 * 3. y el propio generador lo confirma: su carga cambia (en paralelo trabaja a potencia fija), sus kVAr cambian (en
 *    paralelo los regula; en isla da los de la planta) o la frecuencia sale de 59,9-60,1 Hz.
 * Del medidor del transformador solo se usa si marca 0 o no (sus valores no entran en ninguna cifra).
 *
 * Se revisa lo guardado: cada minuto las últimas horas (eventos "Paso a isla" / "Vuelve a paralelo" y el estado en
 * vivo) y, la primera vez, los últimos {@link #DIAS_PRIMERA_REVISION} días (islas anteriores). Análisis usa las mismas
 * islas para separar el tiempo en paralelo y en isla. Solo lectura de los archivos de energía.
 */
@Service
public class IslaService {

    private static final Logger logger = LoggerFactory.getLogger(IslaService.class);

    public static final int LECTURAS_MINIMAS = 3;
    static final double KW_TRAFO_CERO = 1.0;
    /** Confirmación del generador: cambio de carga (kW o %), de kVAr, o frecuencia fuera de este rango. */
    static final double KW_CAMBIO = 30, PCT_CAMBIO = 0.05, KVAR_CAMBIO = 100, HZ_BAJA = 59.8, HZ_ALTA = 60.2;
    /** Lecturas en paralelo antes de la isla que se toman como referencia de carga y kVAr. */
    private static final int REFERENCIA = 10;
    private static final long SEGUNDOS_MEDIDOR = 90;
    /** Sin lecturas por más de esto, el tramo se corta (la app estuvo detenida). */
    private static final long MINUTOS_HUECO = 10;
    static final int HORAS_REVISION = 3, DIAS_PRIMERA_REVISION = 62;

    /** Una lectura guardada del generador con lo que marcaba en ese momento el medidor de su transformador. */
    public record Muestra(LocalDateTime fecha, boolean conCarga, Double kw, Double kvar, Double hz, Double kwTrafo) {
        boolean trafoEnCero() {
            return conCarga && kwTrafo != null && Math.abs(kwTrafo) < KW_TRAFO_CERO;
        }
    }

    /**
     * Una isla. hasta = primera lectura de vuelta en paralelo o con el generador sin carga (null = sigue en isla).
     * volvioAParalelo = terminó porque el transformador volvió a marcar (no porque el generador paró o quedó sin carga).
     */
    public record Isla(String generador, LocalDateTime desde, LocalDateTime hasta, boolean volvioAParalelo, int lecturas,
                       String evidencia) {
        public boolean enCurso() {
            return hasta == null;
        }

        public boolean contiene(LocalDateTime t) {
            return !t.isBefore(desde) && (hasta == null || t.isBefore(hasta));
        }
    }

    private final GeneradoresConfigService config;
    private final HistorialEventosService historial;
    /** Generador -> comienzo de la isla en curso (estado en vivo). */
    private final Map<String, LocalDateTime> enCurso = new ConcurrentHashMap<>();
    private volatile boolean primeraRevision = true;

    public IslaService(GeneradoresConfigService config, HistorialEventosService historial) {
        this.config = config;
        this.historial = historial;
    }

    /** Comienzo de la isla en curso del generador, o null si no está en isla (según la última revisión). */
    public LocalDateTime enIslaDesde(String generador) {
        return enCurso.get(generador);
    }

    /**
     * Revisa lo guardado y registra en el historial de eventos las islas que falten (inicio y fin). Lo llama la
     * lectura de los generadores después de cada ciclo; la primera vez revisa los últimos días.
     */
    public void revisar() {
        LocalDateTime ahora = LocalDateTime.now().withNano(0);
        LocalDateTime desde = primeraRevision ? ahora.minusDays(DIAS_PRIMERA_REVISION) : ahora.minusHours(HORAS_REVISION);
        for (Generador g : config.generadores()) {
            if (g.redAsociada() == null) continue;
            List<Isla> islas = islas(g, desde, ahora);
            List<EventoGenerador> eventos = new ArrayList<>();
            LocalDateTime abierta = null;
            for (Isla i : islas) {
                eventos.add(EventoGenerador.de(i.desde(), TipoEventoGenerador.ISLA, "Se abrio el interruptor de la planta: el medidor del "
                        + nombreRed(g.redAsociada()) + " marca 0 con el generador con carga, que alimenta solo su parte de la planta; "
                        + i.evidencia(), null));
                if (i.enCurso()) {
                    abierta = i.desde();
                } else {
                    String dur = duracion(i.desde(), i.hasta());
                    eventos.add(EventoGenerador.de(i.hasta(), TipoEventoGenerador.FIN_ISLA, i.volvioAParalelo()
                            ? "Vuelve a paralelo con la red tras " + dur + " en isla"
                            : "Fin de la isla tras " + dur + ": el generador quedo sin carga o se detuvo", null));
                }
            }
            if (abierta != null) enCurso.put(g.nombre(), abierta);
            else enCurso.remove(g.nombre());
            int agregados = historial.agregarSiFaltan(g.nombre(), eventos);
            if (agregados > 0) logger.info("Generador {}: {} eventos de isla registrados", g.nombre(), agregados);
        }
        primeraRevision = false;
    }

    /** Islas del generador que se pisan con [desde, hasta], de la más vieja a la más nueva. */
    public List<Isla> islas(Generador g, LocalDateTime desde, LocalDateTime hasta) {
        if (g.redAsociada() == null || !PLCDataQueryService.esNombreMaquinaValido(g.redAsociada())) return List.of();
        // Dos horas antes, para tener la referencia en paralelo y no cortar una isla que empezó antes.
        LocalDateTime ini = desde.minusHours(2);
        TreeMap<LocalDateTime, Double> trafo = potenciaTrafo(g.redAsociada(), ini, hasta);
        List<Muestra> muestras = new ArrayList<>();
        for (Object[] x : lecturasGenerador(g.nombre(), ini, hasta)) {
            LocalDateTime t = (LocalDateTime) x[0];
            Double kw = (Double) x[2];
            boolean conCarga = (Boolean) x[1] && kw != null && kw >= GeneradorAnalisisService.KW_VACIO;
            Map.Entry<LocalDateTime, Double> m = trafo.floorEntry(t);
            Double kwTrafo = m != null && Duration.between(m.getKey(), t).toSeconds() <= SEGUNDOS_MEDIDOR ? m.getValue() : null;
            muestras.add(new Muestra(t, conCarga, kw, (Double) x[3], (Double) x[4], kwTrafo));
        }
        List<Isla> lista = new ArrayList<>();
        for (Isla i : detectar(g.nombre(), muestras)) {
            if (i.desde().isAfter(hasta) || (i.hasta() != null && i.hasta().isBefore(desde))) continue;
            lista.add(i);
        }
        return lista;
    }

    /** Islas en una serie de lecturas ordenada por fecha (ver la firma en la descripción de la clase). */
    public static List<Isla> detectar(String generador, List<Muestra> muestras) {
        List<Isla> islas = new ArrayList<>();
        int i = 0;
        while (i < muestras.size()) {
            if (!muestras.get(i).trafoEnCero()) {
                i++;
                continue;
            }
            // Tramo con el transformador en 0; una lectura sin dato del medidor no lo corta ni lo alarga. Un hueco de más
            // de MINUTOS_HUECO sin lecturas (app detenida) lo cierra en la última lectura en 0: no se sabe qué pasó después.
            int inicio = i, ceros = 0, j = i;
            boolean cortadoPorHueco = false;
            while (j < muestras.size()) {
                Muestra m = muestras.get(j);
                if (j > inicio && Duration.between(muestras.get(j - 1).fecha(), m.fecha()).toMinutes() > MINUTOS_HUECO) {
                    cortadoPorHueco = true;
                    break;
                }
                if (m.trafoEnCero()) ceros++;
                else if (m.conCarga() && m.kwTrafo() == null) { /* sin dato del medidor: sigue */ }
                else break;
                j++;
            }
            String evidencia = ceros >= LECTURAS_MINIMAS ? evidencia(muestras, inicio, j) : null;
            if (evidencia != null) {
                Muestra fin = j < muestras.size() ? muestras.get(j) : null;
                LocalDateTime hasta = cortadoPorHueco ? muestras.get(j - 1).fecha() : fin == null ? null : fin.fecha();
                islas.add(new Isla(generador, muestras.get(inicio).fecha(), hasta,
                        !cortadoPorHueco && fin != null && fin.conCarga(), ceros, evidencia));
            }
            i = j;
        }
        return islas;
    }

    /**
     * Confirmación del propio generador entre las lecturas [desde, hasta) del tramo, contra las últimas lecturas en
     * paralelo antes del tramo. null = no hay confirmación (no se toma como isla).
     */
    private static String evidencia(List<Muestra> muestras, int desde, int hasta) {
        List<Double> kws = new ArrayList<>(), kvars = new ArrayList<>();
        for (int k = desde - 1; k >= 0 && kws.size() < REFERENCIA; k--) {
            Muestra m = muestras.get(k);
            if (!m.conCarga()) break; // antes del arranque o en vacío: sin referencia
            if (m.kwTrafo() == null || m.trafoEnCero()) continue;
            if (m.kw() != null) kws.add(m.kw());
            if (m.kvar() != null) kvars.add(m.kvar());
        }
        Double kwRef = mediana(kws), kvarRef = mediana(kvars);
        double kwMin = Double.MAX_VALUE, kwMax = -Double.MAX_VALUE, kvarMax = -Double.MAX_VALUE, kvarMin = Double.MAX_VALUE;
        double hzMin = Double.MAX_VALUE, hzMax = -Double.MAX_VALUE;
        boolean carga = false, reactiva = false, frecuencia = false;
        for (int k = desde; k < hasta; k++) {
            Muestra m = muestras.get(k);
            if (m.kw() != null) {
                kwMin = Math.min(kwMin, m.kw());
                kwMax = Math.max(kwMax, m.kw());
                if (kwRef != null && Math.abs(m.kw() - kwRef) >= Math.max(KW_CAMBIO, PCT_CAMBIO * kwRef)) carga = true;
            }
            if (m.kvar() != null) {
                kvarMin = Math.min(kvarMin, m.kvar());
                kvarMax = Math.max(kvarMax, m.kvar());
                if (kvarRef != null && Math.abs(m.kvar() - kvarRef) >= KVAR_CAMBIO) reactiva = true;
            }
            if (m.hz() != null) {
                hzMin = Math.min(hzMin, m.hz());
                hzMax = Math.max(hzMax, m.hz());
                if (m.hz() <= HZ_BAJA || m.hz() >= HZ_ALTA) frecuencia = true;
            }
        }
        if (!carga && !reactiva && !frecuencia) return null;
        StringBuilder sb = new StringBuilder();
        if (kwRef != null && kwMax >= kwMin) sb.append(String.format(Locale.ROOT, "carga %.0f kW antes, %.0f a %.0f kW en isla", kwRef, kwMin, kwMax));
        if (kvarRef != null && kvarMax >= kvarMin) {
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(String.format(Locale.ROOT, "%.0f kVAr antes, %.0f a %.0f kVAr en isla", kvarRef, kvarMin, kvarMax));
        }
        if (hzMax >= hzMin) {
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(String.format(Locale.ROOT, "frecuencia %.1f a %.1f Hz", hzMin, hzMax));
        }
        return sb.toString();
    }

    private static Double mediana(List<Double> v) {
        if (v.isEmpty()) return null;
        List<Double> o = new ArrayList<>(v);
        Collections.sort(o);
        return o.get(o.size() / 2);
    }

    /** Lecturas guardadas del generador: fecha, en marcha, kW, kVAr, frecuencia. */
    private List<Object[]> lecturasGenerador(String generador, LocalDateTime desde, LocalDateTime hasta) {
        TreeMap<LocalDateTime, Object[]> filas = new TreeMap<>();
        for (YearMonth m = YearMonth.from(desde); !m.isAfter(YearMonth.from(hasta)); m = m.plusMonths(1)) {
            String ruta = GeneradorAlmacen.rutaLecturas(m);
            if (!new File(ruta).exists()) continue;
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta.replace('\\', '/') + "?mode=ro");
                 ResultSet r = c.createStatement().executeQuery("SELECT fecha, estado, kw, kvar, frecuencia FROM \"" + generador + "\""
                         + soloDias(desde, hasta))) {
                while (r.next()) {
                    LocalDateTime t = GeneradorAnalisisService.fecha(r.getString(1));
                    if (t == null || t.isBefore(desde) || t.isAfter(hasta)) continue;
                    filas.put(t, new Object[]{t, "MARCHA".equals(r.getString(2)), num(r.getObject(3)), num(r.getObject(4)), num(r.getObject(5))});
                }
            } catch (Exception e) {
                if (!String.valueOf(e.getMessage()).contains("no such table")) {
                    logger.warn("Islas: no se pudieron leer las lecturas de {} en {}: {}", generador, ruta, e.getMessage());
                }
            }
        }
        return new ArrayList<>(filas.values());
    }

    /** Potencia del medidor del transformador (archivo VIP de cada mes). */
    private TreeMap<LocalDateTime, Double> potenciaTrafo(String trafo, LocalDateTime desde, LocalDateTime hasta) {
        TreeMap<LocalDateTime, Double> serie = new TreeMap<>();
        for (YearMonth m = YearMonth.from(desde); !m.isAfter(YearMonth.from(hasta)); m = m.plusMonths(1)) {
            String ruta = RutaArchivosEnergia.construirRutaMensual(m.getYear(), m.getMonthValue(), true);
            if (!new File(ruta).exists()) continue;
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta.replace('\\', '/') + "?mode=ro");
                 ResultSet r = c.createStatement().executeQuery("SELECT fecha, PW FROM \"" + trafo + "\"" + soloDias(desde, hasta))) {
                while (r.next()) {
                    LocalDateTime t = GeneradorAnalisisService.fecha(r.getString(1));
                    if (t != null && !t.isBefore(desde) && !t.isAfter(hasta) && r.getObject(2) != null) serie.put(t, r.getDouble(2));
                }
            } catch (Exception e) {
                if (!String.valueOf(e.getMessage()).contains("no such table")) {
                    logger.warn("Islas: no se pudo leer PW de {} en {}: {}", trafo, ruta, e.getMessage());
                }
            }
        }
        return serie;
    }

    /**
     * Para la revisión de cada minuto (pocas horas): solo las filas de esos días ("dd-MM-yyyy" al comienzo de la fecha),
     * en vez de todo el mes. Para períodos largos, vacío (se lee todo y se filtra al leer).
     */
    private static String soloDias(LocalDateTime desde, LocalDateTime hasta) {
        if (Duration.between(desde, hasta).toDays() > 4) return "";
        List<String> dias = new ArrayList<>();
        for (var d = desde.toLocalDate(); !d.isAfter(hasta.toLocalDate()); d = d.plusDays(1)) {
            dias.add("'" + d.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")) + "'");
        }
        return " WHERE substr(fecha, 1, 10) IN (" + String.join(", ", dias) + ")";
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    static String nombreRed(String red) {
        return red == null ? "transformador" : red.replaceFirst("^Trafo(\\d+)$", "Transformador $1");
    }

    private static String duracion(LocalDateTime desde, LocalDateTime hasta) {
        long min = Math.round(Duration.between(desde, hasta).toSeconds() / 60.0);
        return min < 60 ? min + " min" : (min / 60) + " h " + (min % 60) + " min";
    }
}
