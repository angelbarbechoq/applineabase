package com.example.generador.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.generador.model.EventoGenerador;
import com.example.generador.model.LecturaGenerador;
import com.example.generador.model.TipoEventoGenerador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.generador.model.TipoEventoGenerador.*;

/**
 * Historial propio de eventos del generador, a semejanza del historial de ComAp (que no se puede
 * leer por Modbus sin escribirle al controlador). Se detectan comparando cada lectura (1 por minuto)
 * con la anterior: arranque/parada (y arranques de menos de 1 minuto por el contador del
 * controlador), toma de carga / vacío, falla y retorno de red, alarmas que aparecen y se resuelven
 * en la lista de alarmas del controlador, y comunicación perdida/recuperada. Cada evento guarda la
 * foto de los valores del momento.
 *
 * Archivo {@code C:\LineaBaseX\generador\eventos}, una tabla por generador (como los arranques).
 * El estado anterior vive en memoria: al iniciar la app se registra "Inicio de lectura" (marca el
 * hueco) y las alarmas que ya estaban activas.
 */
@Service
public class HistorialEventosService {

    private static final Logger logger = LoggerFactory.getLogger(HistorialEventosService.class);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);

    /** Último estado visto de cada generador. */
    private static final class Estado {
        Boolean comunicando;
        boolean marcha;
        Boolean carga, red;
        Integer arranques;
        Set<String> alarmas;
    }

    private final Map<String, Estado> estados = new ConcurrentHashMap<>();

    public static String ruta() {
        return RutaArchivosEnergia.BASE_PATH + "\\generador\\eventos";
    }

    /** Compara la lectura con el estado anterior y guarda los eventos que correspondan. */
    public void registrarLectura(String generador, LecturaGenerador l) {
        List<EventoGenerador> nuevos = new ArrayList<>();
        LocalDateTime t = l.fecha();
        boolean marcha = l.enMarcha();
        Boolean carga = !marcha ? Boolean.FALSE : l.kw() == null ? null : l.kw() >= GeneradorAnalisisService.KW_VACIO;
        Boolean red = redPresente(l);
        Estado e = estados.get(generador);
        if (e == null) {
            e = new Estado();
            estados.put(generador, e);
            nuevos.add(EventoGenerador.de(t, INICIO_LECTURA, "La app empezo a leer el controlador; el generador estaba "
                    + (marcha ? "en marcha" + (Boolean.TRUE.equals(carga) ? " con carga" : " en vacio") : "parado")
                    + (red == null ? "" : red ? ", con red" : ", sin red")
                    + (l.desfaseReloj() == null ? "" : "; reloj del controlador " + LecturaGenerador.textoDesfase(l.desfaseReloj())), l));
            if (l.alarmas() != null) {
                for (String a : l.alarmas()) {
                    nuevos.add(EventoGenerador.de(t, ALARMA, AlarmaComap.nombre(a) + ": " + AlarmaComap.estado(a) + " (ya estaba al iniciar la lectura)", l));
                }
            }
        } else {
            if (Boolean.FALSE.equals(e.comunicando)) nuevos.add(EventoGenerador.de(t, COMUNICACION_RECUPERADA, "El controlador volvio a responder", l));
            if (marcha && !e.marcha) {
                nuevos.add(EventoGenerador.de(t, ARRANQUE, "Motor en marcha" + (l.arranques() == null ? "" : " (arranque Nro " + l.arranques() + ")"), l));
            } else if (!marcha && e.marcha) {
                nuevos.add(EventoGenerador.de(t, PARADA, "Motor parado", l));
            } else if (!marcha && e.arranques != null && l.arranques() != null && l.arranques() > e.arranques) {
                nuevos.add(EventoGenerador.de(t, ARRANQUE_BREVE, "El contador de arranques del controlador subio de " + e.arranques
                        + " a " + l.arranques() + " sin que se viera el motor en marcha: arranque de menos de 1 minuto", l));
            }
            // Carga: solo con el motor en marcha (al arrancar se compara contra "sin carga").
            if (marcha && carga != null) {
                boolean antes = e.marcha && Boolean.TRUE.equals(e.carga);
                if (carga && !antes) {
                    nuevos.add(EventoGenerador.de(t, TOMA_CARGA, String.format(java.util.Locale.ROOT, "El generador toma carga (%.0f kW)", l.kw()), l));
                } else if (!carga && antes) {
                    nuevos.add(EventoGenerador.de(t, SIN_CARGA, String.format(java.util.Locale.ROOT, "El generador queda en vacio (%.0f kW)", l.kw()), l));
                }
            }
            if (red != null && e.red != null && !red.equals(e.red)) {
                nuevos.add(EventoGenerador.de(t, red ? RETORNO_RED : FALLA_RED, red ? "Volvio la tension de red" : "Sin tension de red", l));
            }
            if (l.alarmas() != null && e.alarmas != null) {
                nuevos.addAll(cambiosAlarmas(t, e.alarmas, l.alarmas(), l));
            }
        }
        e.comunicando = true;
        e.marcha = marcha;
        e.carga = carga;
        if (red != null) e.red = red;
        if (l.arranques() != null) e.arranques = l.arranques();
        if (l.alarmas() != null) e.alarmas = new LinkedHashSet<>(l.alarmas());
        guardar(generador, nuevos);
    }

    // ================= Alarmas de la lista del controlador =================
    // Cada alarma se sigue por su nombre sin los signos "!" (activa) y "*" (sin reset) de ComAp
    // (ver AlarmaComap): un cambio de signo es un cambio de estado de la misma alarma, no una alarma nueva.

    /** Eventos por los cambios de la lista de alarmas entre dos lecturas, siguiendo cada alarma por su nombre. */
    private static List<EventoGenerador> cambiosAlarmas(LocalDateTime t, Set<String> antes, List<String> ahora, LecturaGenerador l) {
        Map<String, String> previas = new java.util.LinkedHashMap<>(), actuales = new java.util.LinkedHashMap<>();
        for (String a : antes) previas.put(AlarmaComap.nombre(a), a);
        for (String a : ahora) actuales.put(AlarmaComap.nombre(a), a);
        List<EventoGenerador> eventos = new ArrayList<>();
        for (Map.Entry<String, String> x : actuales.entrySet()) {
            String nombre = x.getKey(), texto = x.getValue(), previo = previas.get(nombre);
            if (previo == null) {
                eventos.add(EventoGenerador.de(t, ALARMA, nombre + ": " + AlarmaComap.estado(texto), l));
            } else if (AlarmaComap.activa(texto) && !AlarmaComap.activa(previo)) {
                eventos.add(EventoGenerador.de(t, ALARMA, nombre + ": activa de nuevo", l));
            } else if (!AlarmaComap.activa(texto) && AlarmaComap.activa(previo)) {
                eventos.add(EventoGenerador.de(t, ALARMA_INACTIVA, nombre + ": " + AlarmaComap.estado(texto), l));
            } else if (!AlarmaComap.sinConfirmar(texto) && AlarmaComap.sinConfirmar(previo)) {
                eventos.add(EventoGenerador.de(t, ALARMA_CONFIRMADA, nombre + ": confirmada en el controlador"
                        + (AlarmaComap.activa(texto) ? ", sigue activa" : ""), l));
            }
        }
        for (String nombre : previas.keySet()) {
            if (!actuales.containsKey(nombre)) {
                eventos.add(EventoGenerador.de(t, ALARMA_RESUELTA, nombre + ": salio de la lista del controlador (reseteada)", l));
            }
        }
        return eventos;
    }

    /**
     * Alarmas de la lista del controlador según la última lectura de fondo (cada minuto), con sus
     * signos de estado. Vacía si no tiene alarmas o todavía no se leyó; sirve para avisar de un
     * generador que no está en pantalla.
     */
    public List<String> alarmasActivas(String generador) {
        Estado e = estados.get(generador);
        return e == null || e.alarmas == null ? List.of() : List.copyOf(e.alarmas);
    }

    /**
     * Desde cuándo está la alarma en su estado actual: el último evento ALARMA (aparece o se activa de
     * nuevo) o ALARMA_INACTIVA con ese nombre. {@code inicioLectura} = true si ya estaba cuando arrancó la
     * app (apareció antes de esa hora). Null si no está en el historial. Acepta también los eventos
     * guardados antes de separar el nombre de los signos ("*Emergency Stop").
     */
    public Aparicion aparicion(String generador, String alarma) {
        String nombre = AlarmaComap.nombre(alarma);
        LocalDateTime ahora = LocalDateTime.now();
        Aparicion alIniciar = null;
        LocalDateTime resueltaEn = null;
        for (EventoGenerador ev : listar(generador, ahora.minusDays(365), ahora, null)) {
            String d = AlarmaComap.nombre(ev.descripcion());
            if (!(d.equals(nombre) || d.startsWith(nombre + ":") || d.startsWith(nombre + " ("))) continue;
            if (ev.tipo() == ALARMA_RESUELTA) {
                if (resueltaEn == null) resueltaEn = ev.fecha();
                continue;
            }
            if (ev.tipo() != ALARMA && ev.tipo() != ALARMA_INACTIVA) continue;
            // Una "resuelta" en el mismo segundo que la alarma fue solo un cambio de signo ("*" -> "!*"), que antes
            // del 2026-10-12 se anotaba como alarma nueva + resuelta; una resuelta posterior corta la búsqueda.
            if (resueltaEn != null && !resueltaEn.equals(ev.fecha())) break;
            if (!d.contains("al iniciar la lectura")) return new Aparicion(ev.fecha(), false);
            // Ya estaba al reiniciar la app: se busca hacia atrás cuándo apareció de verdad, si no se resolvió antes.
            if (alIniciar == null) alIniciar = new Aparicion(ev.fecha(), true);
        }
        return alIniciar;
    }

    /** Id del último evento guardado del generador (0 si no hay), para saber si hay eventos nuevos. */
    public long ultimoId(String generador) {
        if (!new File(ruta()).exists()) return 0;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta().replace('\\', '/') + "?mode=ro");
             ResultSet r = c.createStatement().executeQuery("SELECT max(id) FROM \"" + generador + "\"")) {
            return r.next() ? r.getLong(1) : 0;
        } catch (Exception ex) {
            return 0;
        }
    }

    public record Aparicion(LocalDateTime fecha, boolean inicioLectura) {
    }

    /** El controlador no respondió: un evento cuando se pierde la comunicación (no uno por minuto). */
    public void registrarSinComunicacion(String generador, String motivo) {
        Estado e = estados.get(generador);
        if (e == null) {
            e = new Estado();
            estados.put(generador, e);
            guardar(generador, List.of(EventoGenerador.de(LocalDateTime.now().withNano(0), SIN_COMUNICACION,
                    "Sin comunicacion al iniciar la lectura: " + motivo, null)));
        } else if (!Boolean.FALSE.equals(e.comunicando)) {
            guardar(generador, List.of(EventoGenerador.de(LocalDateTime.now().withNano(0), SIN_COMUNICACION, motivo, null)));
        }
        e.comunicando = false;
    }

    private static Boolean redPresente(LecturaGenerador l) {
        Double a = l.redVL1L2(), b = l.redVL2L3(), c = l.redVL3L1();
        if (a == null || b == null || c == null) return null;
        return (a + b + c) / 3.0 >= GeneradorAnalisisService.V_RED_PRESENTE;
    }

    // ================= Almacenamiento =================

    /** Guarda eventos detectados fuera de esta clase (señales de red del controlador, RedControladorService). */
    public void registrar(String generador, List<EventoGenerador> eventos) {
        guardar(generador, eventos);
    }

    private void guardar(String generador, List<EventoGenerador> eventos) {
        if (eventos.isEmpty()) return;
        try {
            Files.createDirectories(Paths.get(ruta()).getParent());
        } catch (Exception ex) {
            logger.error("No se pudo crear la carpeta del historial de eventos: {}", ex.getMessage());
            return;
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ruta())) {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA busy_timeout = 10000;");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS \"" + generador + "\" (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "fecha TEXT NOT NULL, tipo TEXT NOT NULL, descripcion TEXT, rpm REAL, kw REAL, pf REAL, frecuencia REAL, "
                        + "v_gen REAL, i_gen REAL, red_frecuencia REAL, v_red REAL, bateria REAL, temp_refrigerante REAL, "
                        + "horas_marcha REAL, kwh INTEGER, arranques INTEGER)");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO \"" + generador + "\" (fecha, tipo, descripcion, rpm, kw, pf, "
                    + "frecuencia, v_gen, i_gen, red_frecuencia, v_red, bateria, temp_refrigerante, horas_marcha, kwh, arranques) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (EventoGenerador ev : eventos) {
                    ps.setString(1, ev.fecha().format(FECHA));
                    ps.setString(2, ev.tipo().name());
                    ps.setString(3, ev.descripcion());
                    Object[] v = {ev.rpm(), ev.kw(), ev.pf(), ev.frecuencia(), ev.vGen(), ev.iGen(), ev.redFrecuencia(), ev.vRed(),
                            ev.bateria(), ev.tempRefrigerante(), ev.horasMarcha(), ev.kwh(), ev.arranques()};
                    for (int i = 0; i < v.length; i++) {
                        if (v[i] == null) ps.setNull(i + 4, Types.REAL);
                        else ps.setObject(i + 4, v[i]);
                    }
                    ps.addBatch();
                    logger.info("Generador {}: evento {} - {}", generador, ev.tipo().etiqueta(), ev.descripcion());
                }
                ps.executeBatch();
            }
        } catch (SQLException ex) {
            logger.error("Error guardando eventos de {}: {}", generador, ex.getMessage());
        }
    }

    // ================= Eventos copiados del historial del controlador =================
    // Se cargan a mano (no se leen por Modbus) y viajan con "Pasar configuracion" a otro equipo.

    /** Eventos de tipo CONTROLADOR_* de cada generador (nombre de la tabla), del más viejo al más nuevo. */
    public Map<String, List<EventoGenerador>> eventosDelControlador() {
        Map<String, List<EventoGenerador>> resultado = new java.util.TreeMap<>();
        if (!new File(ruta()).exists()) return resultado;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta().replace('\\', '/') + "?mode=ro")) {
            List<String> tablas = new ArrayList<>();
            try (ResultSet r = c.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
                while (r.next()) tablas.add(r.getString(1));
            }
            for (String generador : tablas) {
                List<EventoGenerador> lista = new ArrayList<>();
                try (ResultSet r = c.createStatement().executeQuery("SELECT id, fecha, tipo, descripcion FROM \"" + generador
                        + "\" WHERE tipo LIKE 'CONTROLADOR_%' ORDER BY id")) {
                    while (r.next()) {
                        LocalDateTime f = GeneradorAnalisisService.fecha(r.getString(2));
                        TipoEventoGenerador t;
                        try {
                            t = TipoEventoGenerador.valueOf(r.getString(3));
                        } catch (IllegalArgumentException ex) {
                            continue;
                        }
                        if (f != null) lista.add(EventoGenerador.de(f, t, r.getString(4), null));
                    }
                }
                if (!lista.isEmpty()) resultado.put(generador, lista);
            }
        } catch (Exception ex) {
            logger.warn("No se pudieron leer los eventos del controlador: {}", ex.getMessage());
        }
        return resultado;
    }

    /**
     * Agrega los eventos del controlador que falten (misma fecha, tipo y descripción = ya estaba).
     * Con {@code aplicar} false solo cuenta cuántos se agregarían. Devuelve esa cantidad.
     */
    public int agregarDelControlador(String generador, List<EventoGenerador> eventos, boolean aplicar) {
        Set<String> existentes = new java.util.HashSet<>();
        if (new File(ruta()).exists()) {
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta().replace('\\', '/') + "?mode=ro");
                 ResultSet r = c.createStatement().executeQuery("SELECT fecha, tipo, descripcion FROM \"" + generador
                         + "\" WHERE tipo LIKE 'CONTROLADOR_%'")) {
                while (r.next()) existentes.add(r.getString(1) + "|" + r.getString(2) + "|" + r.getString(3));
            } catch (Exception ex) {
                if (!String.valueOf(ex.getMessage()).contains("no such table")) {
                    logger.warn("No se pudo leer el historial de eventos de {}: {}", generador, ex.getMessage());
                }
            }
        }
        List<EventoGenerador> nuevos = new ArrayList<>();
        for (EventoGenerador ev : eventos) {
            if (existentes.add(ev.fecha().format(FECHA) + "|" + ev.tipo().name() + "|" + ev.descripcion())) nuevos.add(ev);
        }
        if (aplicar) guardar(generador, nuevos);
        return nuevos.size();
    }

    /**
     * Guarda los eventos que todavía no estén (misma fecha y tipo = ya estaba). Para los que se detectan revisando lo
     * guardado (islas), que se vuelven a encontrar en cada revisión. Devuelve cuántos se agregaron.
     */
    public int agregarSiFaltan(String generador, List<EventoGenerador> eventos) {
        if (eventos.isEmpty()) return 0;
        Set<String> existentes = new java.util.HashSet<>();
        if (new File(ruta()).exists()) {
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta().replace('\\', '/') + "?mode=ro");
                 ResultSet r = c.createStatement().executeQuery("SELECT fecha, tipo FROM \"" + generador + "\"")) {
                while (r.next()) existentes.add(r.getString(1) + "|" + r.getString(2));
            } catch (Exception ex) {
                if (!String.valueOf(ex.getMessage()).contains("no such table")) {
                    logger.warn("No se pudo leer el historial de eventos de {}: {}", generador, ex.getMessage());
                    return 0;
                }
            }
        }
        List<EventoGenerador> nuevos = new ArrayList<>();
        for (EventoGenerador ev : eventos) {
            if (existentes.add(ev.fecha().format(FECHA) + "|" + ev.tipo().name())) nuevos.add(ev);
        }
        guardar(generador, nuevos);
        return nuevos.size();
    }

    /** Eventos entre dos fechas, del más reciente al más viejo (tipo null = todos). */
    public List<EventoGenerador> listar(String generador, LocalDateTime desde, LocalDateTime hasta, TipoEventoGenerador tipo) {
        List<EventoGenerador> lista = new ArrayList<>();
        if (!new File(ruta()).exists()) return lista;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta().replace('\\', '/') + "?mode=ro");
             ResultSet r = c.createStatement().executeQuery("SELECT id, fecha, tipo, descripcion, rpm, kw, pf, frecuencia, v_gen, "
                     + "i_gen, red_frecuencia, v_red, bateria, temp_refrigerante, horas_marcha, kwh, arranques FROM \"" + generador
                     + "\" ORDER BY id DESC LIMIT 20000")) {
            while (r.next()) {
                LocalDateTime f = GeneradorAnalisisService.fecha(r.getString(2));
                if (f == null || f.isBefore(desde) || f.isAfter(hasta)) continue;
                TipoEventoGenerador t;
                try {
                    t = TipoEventoGenerador.valueOf(r.getString(3));
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                if (tipo != null && t != tipo) continue;
                lista.add(new EventoGenerador(r.getLong(1), f, t, r.getString(4), num(r, 5), num(r, 6), num(r, 7), num(r, 8),
                        num(r, 9), num(r, 10), num(r, 11), num(r, 12), num(r, 13), num(r, 14), num(r, 15),
                        r.getObject(16) == null ? null : r.getLong(16), r.getObject(17) == null ? null : r.getInt(17)));
            }
        } catch (Exception ex) {
            if (!String.valueOf(ex.getMessage()).contains("no such table")) {
                logger.warn("No se pudo leer el historial de eventos de {}: {}", generador, ex.getMessage());
            }
        }
        // Por fecha y no por id: los eventos copiados del historial del controlador se cargan después de ocurridos.
        lista.sort(java.util.Comparator.comparing(EventoGenerador::fecha).thenComparing(EventoGenerador::id).reversed());
        return lista;
    }

    private static Double num(ResultSet r, int i) throws SQLException {
        Object o = r.getObject(i);
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
