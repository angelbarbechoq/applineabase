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
                    + (red == null ? "" : red ? ", con red" : ", sin red"), l));
            if (l.alarmas() != null) {
                for (String a : l.alarmas()) nuevos.add(EventoGenerador.de(t, ALARMA, a + " (activa al iniciar la lectura)", l));
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
                for (String a : l.alarmas()) if (!e.alarmas.contains(a)) nuevos.add(EventoGenerador.de(t, ALARMA, a, l));
                for (String a : e.alarmas) if (!l.alarmas().contains(a)) nuevos.add(EventoGenerador.de(t, ALARMA_RESUELTA, a, l));
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
        return lista;
    }

    private static Double num(ResultSet r, int i) throws SQLException {
        Object o = r.getObject(i);
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
