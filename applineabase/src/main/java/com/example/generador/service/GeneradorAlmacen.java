package com.example.generador.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.generador.model.LecturaGenerador;
import com.example.generador.model.ParametroGenerador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guardado del generador en SQLite:
 * - Lecturas: {@code C:\LineaBaseX\{año}\{mes}\{mes}Generador}, una tabla por generador, una fila
 *   por lectura guardada (cada minuto en marcha, cada 15 min parado; lo decide el lector).
 * - Arranques: {@code C:\LineaBaseX\generador\arranques}, una tabla por generador, una fila por
 *   arranque (inicio, fin, duración, kWh y horas del controlador al inicio y al fin). Archivo único
 *   (pocas filas) para que un arranque que cruza fin de mes no quede partido.
 */
@Service
public class GeneradorAlmacen {

    private static final Logger logger = LoggerFactory.getLogger(GeneradorAlmacen.class);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);

    /** Archivo|tabla cuyas columnas ya se verificaron en esta ejecución (evita consultar el esquema cada minuto). */
    private final Set<String> esquemaVerificado = ConcurrentHashMap.newKeySet();

    private static void agregarColumnasSiFaltan(Connection c, String tabla, String[] columnas) throws SQLException {
        agregarColumnasSiFaltan(c, tabla, columnas, new boolean[columnas.length]);
    }

    private static void agregarColumnasSiFaltan(Connection c, String tabla, String[] columnas, boolean[] enteras) throws SQLException {
        Set<String> existentes = new HashSet<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, null, tabla, null)) {
            while (rs.next()) existentes.add(rs.getString("COLUMN_NAME").toLowerCase());
        }
        try (Statement st = c.createStatement()) {
            for (int i = 0; i < columnas.length; i++) {
                if (!existentes.contains(columnas[i])) {
                    st.executeUpdate("ALTER TABLE \"" + tabla + "\" ADD COLUMN " + columnas[i] + (enteras[i] ? " INTEGER" : " REAL"));
                }
            }
        }
    }

    /** Carga máxima del arranque en curso (se actualiza en cada lectura en marcha). */
    public void actualizarKwMax(String generador, Double kw) {
        if (kw == null) {
            return;
        }
        try (Connection c = abrirArranques(generador);
             PreparedStatement ps = c.prepareStatement("UPDATE \"" + generador + "\" SET kw_max = max(coalesce(kw_max, 0), ?) "
                     + "WHERE fin IS NULL")) {
            ps.setDouble(1, kw);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error actualizando carga maxima de {}: {}", generador, e.getMessage());
        }
    }

    public static String rutaLecturas(YearMonth mes) {
        return RutaArchivosEnergia.construirCarpetaMes(mes.getYear(), mes.getMonthValue())
                + "\\" + RutaArchivosEnergia.getNombreMes(mes.getMonthValue()) + "Generador";
    }

    public static String rutaArranques() {
        return RutaArchivosEnergia.BASE_PATH + "\\generador\\arranques";
    }

    public void guardarLectura(String generador, LecturaGenerador l) {
        String ruta = rutaLecturas(YearMonth.from(l.fecha()));
        try {
            Files.createDirectories(Paths.get(ruta).getParent());
        } catch (Exception e) {
            logger.error("No se pudo crear la carpeta de {}: {}", ruta, e.getMessage());
            return;
        }
        ParametroGenerador[] params = ParametroGenerador.values();
        try (Connection c = abrir(ruta)) {
            if (esquemaVerificado.add(ruta + "|" + generador)) {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS \"" + generador + "\" (fecha TEXT PRIMARY KEY NOT NULL, estado TEXT)");
                }
                // Una columna por parámetro de la lista cerrada; las que falten se agregan (tablas de
                // antes del mapa configurable, o parámetros nuevos).
                String[] columnas = new String[params.length];
                boolean[] enteras = new boolean[params.length];
                for (int i = 0; i < params.length; i++) {
                    columnas[i] = params[i].columna();
                    enteras[i] = params[i].entero();
                }
                agregarColumnasSiFaltan(c, generador, columnas, enteras);
            }
            StringBuilder cols = new StringBuilder("fecha, estado");
            StringBuilder marcas = new StringBuilder("?, ?");
            for (ParametroGenerador p : params) {
                cols.append(", ").append(p.columna());
                marcas.append(", ?");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO \"" + generador
                    + "\" (" + cols + ") VALUES (" + marcas + ")")) {
                ps.setString(1, l.fecha().format(FECHA));
                ps.setString(2, l.enMarcha() ? "MARCHA" : "PARADO");
                for (int i = 0; i < params.length; i++) {
                    Double v = l.valor(params[i]);
                    if (v == null) ps.setNull(i + 3, params[i].entero() ? Types.INTEGER : Types.REAL);
                    else if (params[i].entero()) ps.setLong(i + 3, Math.round(v));
                    else ps.setDouble(i + 3, v);
                }
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            esquemaVerificado.remove(ruta + "|" + generador);
            logger.error("Error guardando lectura de {} en {}: {}", generador, ruta, e.getMessage());
        }
    }

    /** Abre un arranque (fin vacío). inicioEstimado = la app arrancó con el generador ya en marcha. */
    public void abrirArranque(String generador, LecturaGenerador l, boolean inicioEstimado) {
        try (Connection c = abrirArranques(generador);
             PreparedStatement ps = c.prepareStatement("INSERT INTO \"" + generador + "\" (inicio, kwh_inicio, "
                     + "horas_inicio, arranques_contador, inicio_estimado) VALUES (?,?,?,?,?)")) {
            ps.setString(1, l.fecha().format(FECHA));
            ps.setObject(2, l.kwh());
            ps.setObject(3, l.horasMarcha());
            ps.setObject(4, l.arranques());
            ps.setInt(5, inicioEstimado ? 1 : 0);
            ps.executeUpdate();
            logger.info("Generador {}: arranque registrado ({}{})", generador, l.fecha().format(FECHA),
                    inicioEstimado ? ", inicio estimado" : "");
        } catch (SQLException e) {
            logger.error("Error registrando arranque de {}: {}", generador, e.getMessage());
        }
    }

    /** Cierra el arranque abierto (si hay) con la lectura de parada. */
    public void cerrarArranque(String generador, LecturaGenerador l) {
        try (Connection c = abrirArranques(generador)) {
            Long id = null;
            LocalDateTime inicio = null;
            Long kwhInicio = null;
            try (ResultSet r = c.createStatement().executeQuery("SELECT id, inicio, kwh_inicio FROM \"" + generador
                    + "\" WHERE fin IS NULL ORDER BY id DESC LIMIT 1")) {
                if (r.next()) {
                    id = r.getLong(1);
                    inicio = LocalDateTime.parse(r.getString(2), FECHA);
                    kwhInicio = (Long) (r.getObject(3) == null ? null : r.getLong(3));
                }
            }
            if (id == null) {
                return;
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE \"" + generador + "\" SET fin = ?, duracion_min = ?, "
                    + "kwh_fin = ?, kwh_generados = ?, horas_fin = ? WHERE id = ?")) {
                ps.setString(1, l.fecha().format(FECHA));
                ps.setDouble(2, Duration.between(inicio, l.fecha()).toSeconds() / 60.0);
                ps.setObject(3, l.kwh());
                if (l.kwh() != null && kwhInicio != null) ps.setLong(4, l.kwh() - kwhInicio);
                else ps.setNull(4, Types.INTEGER);
                ps.setObject(5, l.horasMarcha());
                ps.setLong(6, id);
                ps.executeUpdate();
            }
            logger.info("Generador {}: parada registrada ({})", generador, l.fecha().format(FECHA));
        } catch (SQLException e) {
            logger.error("Error cerrando arranque de {}: {}", generador, e.getMessage());
        }
    }

    /** true si hay un arranque abierto (para no duplicarlo si la app se reinicia en marcha). */
    public boolean hayArranqueAbierto(String generador) {
        try (Connection c = abrirArranques(generador);
             ResultSet r = c.createStatement().executeQuery("SELECT count(*) FROM \"" + generador + "\" WHERE fin IS NULL")) {
            return r.next() && r.getInt(1) > 0;
        } catch (SQLException e) {
            logger.error("Error consultando arranques de {}: {}", generador, e.getMessage());
            return false;
        }
    }

    private Connection abrirArranques(String generador) throws SQLException {
        String ruta = rutaArranques();
        try {
            Files.createDirectories(Paths.get(ruta).getParent());
        } catch (Exception e) {
            throw new SQLException("No se pudo crear la carpeta de " + ruta, e);
        }
        Connection c = abrir(ruta);
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS \"" + generador + "\" (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "inicio TEXT NOT NULL, fin TEXT, duracion_min REAL, kwh_inicio INTEGER, kwh_fin INTEGER, "
                    + "kwh_generados INTEGER, horas_inicio REAL, horas_fin REAL, arranques_contador INTEGER, "
                    + "inicio_estimado INTEGER, kw_max REAL)");
        }
        agregarColumnasSiFaltan(c, generador, new String[]{"kw_max"});
        return c;
    }

    private static Connection abrir(String ruta) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + ruta);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout = 10000;");
        }
        return c;
    }
}
