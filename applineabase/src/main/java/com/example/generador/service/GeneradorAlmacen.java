package com.example.generador.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.generador.model.LecturaGenerador;
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

    static final String[] COLUMNAS_CARGA = {"kw", "kw_l1", "kw_l2", "kw_l3", "kvar", "kva", "pf", "i_l1", "i_l2", "i_l3"};
    /** Columnas de valores en el orden en que se insertan (después de fecha y estado). */
    static final String[] COLUMNAS_VALOR = {"rpm", "frecuencia", "v_l1n", "v_l2n", "v_l3n", "v_l1l2", "v_l2l3", "v_l3l1",
            "bateria", "presion_aceite", "temp_refrigerante", "kwh", "kvarh", "horas_marcha", "arranques",
            "kw", "kw_l1", "kw_l2", "kw_l3", "kvar", "kva", "pf", "i_l1", "i_l2", "i_l3"};

    private static void agregarColumnasSiFaltan(Connection c, String tabla, String[] columnas) throws SQLException {
        java.util.Set<String> existentes = new java.util.HashSet<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, null, tabla, null)) {
            while (rs.next()) existentes.add(rs.getString("COLUMN_NAME").toLowerCase());
        }
        try (Statement st = c.createStatement()) {
            for (String col : columnas) {
                if (!existentes.contains(col)) {
                    st.executeUpdate("ALTER TABLE \"" + tabla + "\" ADD COLUMN " + col + " REAL");
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
        try (Connection c = abrir(ruta)) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS \"" + generador + "\" (fecha TEXT PRIMARY KEY NOT NULL, "
                        + "estado TEXT, rpm REAL, frecuencia REAL, v_l1n REAL, v_l2n REAL, v_l3n REAL, "
                        + "v_l1l2 REAL, v_l2l3 REAL, v_l3l1 REAL, bateria REAL, presion_aceite REAL, "
                        + "temp_refrigerante REAL, kwh INTEGER, kvarh INTEGER, horas_marcha REAL, arranques INTEGER)");
                // Columnas de carga agregadas el 2026-10-08 (confirmadas con carga contra TR2).
                agregarColumnasSiFaltan(c, generador, COLUMNAS_CARGA);
            }
            StringBuilder cols = new StringBuilder("fecha, estado");
            StringBuilder marcas = new StringBuilder("?, ?");
            for (String col : COLUMNAS_VALOR) {
                cols.append(", ").append(col);
                marcas.append(", ?");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO \"" + generador
                    + "\" (" + cols + ") VALUES (" + marcas + ")")) {
                ps.setString(1, l.fecha().format(FECHA));
                ps.setString(2, l.enMarcha() ? "MARCHA" : "PARADO");
                Object[] v = {l.rpm(), l.frecuencia(), l.vL1N(), l.vL2N(), l.vL3N(), l.vL1L2(), l.vL2L3(), l.vL3L1(),
                        l.bateria(), l.presionAceite(), l.tempRefrigerante(), l.kwh(), l.kvarh(), l.horasMarcha(), l.arranques(),
                        l.kw(), l.kwL1(), l.kwL2(), l.kwL3(), l.kvar(), l.kva(), l.pf(), l.iL1(), l.iL2(), l.iL3()};
                for (int i = 0; i < v.length; i++) {
                    if (v[i] == null) ps.setNull(i + 3, Types.REAL);
                    else ps.setObject(i + 3, v[i]);
                }
                ps.executeUpdate();
            }
        } catch (SQLException e) {
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
