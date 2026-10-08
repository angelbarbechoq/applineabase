package com.example.calidad.service;

import com.example.calidad.model.LecturaCalidad;
import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.medidores.model.ParametroMedidor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Archivo SQLite mensual de calidad de energía: {@code C:\LineaBaseX\{año}\{mes}\{mes}Calidad},
 * una tabla por máquina, una fila por minuto (misma marca de tiempo que el VIP). Columnas: todos
 * los parámetros del catálogo salvo las energías (que ya están en los archivos normal y VIP), en
 * unidades estándar, más dos marcas de desbalance calculado. Columnas **nulables**: lo que el
 * medidor no tiene o no se pudo leer queda vacío, nunca 0.
 *
 * Solo archivo mensual (sin copia diaria), decidido con el usuario el 2026-10-07: ~640 MB/mes con
 * ~45 medidores. Archivo aparte de los VIP para no tocar lo que lee el programa NetBeans.
 * Los resúmenes (promedios de 10 min, máximos, % del tiempo en límites) se calculan al leer.
 */
@Service
public class CalidadEnergiaAlmacen {

    private static final Logger logger = LoggerFactory.getLogger(CalidadEnergiaAlmacen.class);

    /** Parámetros que van al archivo de calidad, en orden de columnas. */
    public static final List<ParametroMedidor> PARAMETROS = Arrays.stream(ParametroMedidor.values())
            .filter(p -> p != ParametroMedidor.KWH && p != ParametroMedidor.KWH_RETORNO)
            .toList();
    public static final String COL_DESB_I_CALC = "DESBALANCE_I_CALCULADO";
    public static final String COL_DESB_V_CALC = "DESBALANCE_V_CALCULADO";

    /** Tablas ya verificadas por archivo (evita el CREATE/ALTER en cada ciclo). */
    private final Map<String, Set<String>> tablasListas = new ConcurrentHashMap<>();

    public static String rutaArchivo(YearMonth mes) {
        return RutaArchivosEnergia.construirCarpetaMes(mes.getYear(), mes.getMonthValue())
                + "\\" + RutaArchivosEnergia.getNombreMes(mes.getMonthValue()) + "Calidad";
    }

    /**
     * Guarda las lecturas de un ciclo (una fila por máquina). Un error de escritura se registra y
     * no afecta al guardado de kWh/VIP, que ya se hizo antes.
     */
    public void guardar(YearMonth mes, String timestamp, Map<String, LecturaCalidad> porMaquina) {
        if (porMaquina.isEmpty()) {
            return;
        }
        String ruta = rutaArchivo(mes);
        try {
            Files.createDirectories(Paths.get(ruta).getParent());
        } catch (Exception e) {
            logger.error("No se pudo crear la carpeta de {}: {}", ruta, e.getMessage());
            return;
        }
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + ruta)) {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA busy_timeout = 10000;");
            }
            conn.setAutoCommit(false);
            for (Map.Entry<String, LecturaCalidad> e : porMaquina.entrySet()) {
                asegurarTabla(conn, ruta, e.getKey());
                insertar(conn, e.getKey(), timestamp, e.getValue());
            }
            conn.commit();
        } catch (SQLException e) {
            logger.error("Error guardando calidad de energia en {}: {}", ruta, e.getMessage());
        }
    }

    private void asegurarTabla(Connection conn, String ruta, String tabla) throws SQLException {
        Set<String> listas = tablasListas.computeIfAbsent(ruta, k -> ConcurrentHashMap.newKeySet());
        if (listas.contains(tabla)) {
            return;
        }
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS \"").append(tabla)
                .append("\" (fecha TEXT PRIMARY KEY NOT NULL");
        for (ParametroMedidor p : PARAMETROS) {
            sql.append(", \"").append(p.name()).append("\" REAL"); // entre comillas: IN es palabra reservada
        }
        sql.append(", ").append(COL_DESB_I_CALC).append(" INTEGER, ").append(COL_DESB_V_CALC).append(" INTEGER)");
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(sql.toString());
            // Parámetros agregados al catálogo después de crear la tabla: se suman como columnas nuevas.
            Set<String> existentes = new HashSet<>();
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, tabla, null)) {
                while (rs.next()) {
                    existentes.add(rs.getString("COLUMN_NAME").toUpperCase());
                }
            }
            for (ParametroMedidor p : PARAMETROS) {
                if (!existentes.contains(p.name())) {
                    st.executeUpdate("ALTER TABLE \"" + tabla + "\" ADD COLUMN \"" + p.name() + "\" REAL");
                }
            }
        }
        listas.add(tabla);
    }

    private static void insertar(Connection conn, String tabla, String timestamp, LecturaCalidad l) throws SQLException {
        StringBuilder cols = new StringBuilder("fecha");
        StringBuilder marcas = new StringBuilder("?");
        for (ParametroMedidor p : PARAMETROS) {
            cols.append(", \"").append(p.name()).append("\"");
            marcas.append(", ?");
        }
        cols.append(", ").append(COL_DESB_I_CALC).append(", ").append(COL_DESB_V_CALC);
        marcas.append(", ?, ?");
        String sql = "INSERT OR REPLACE INTO \"" + tabla + "\" (" + cols + ") VALUES (" + marcas + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, timestamp);
            for (ParametroMedidor p : PARAMETROS) {
                Double v = l.valores().get(p);
                if (v == null || v.isNaN() || v.isInfinite()) {
                    ps.setNull(i++, Types.REAL);
                } else {
                    ps.setDouble(i++, v);
                }
            }
            ps.setInt(i++, l.desbalanceICalculado() ? 1 : 0);
            ps.setInt(i, l.desbalanceVCalculado() ? 1 : 0);
            ps.executeUpdate();
        }
    }
}
