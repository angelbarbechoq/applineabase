package com.example.dataacquisition.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PLCDataQueryService {

    private static final Logger logger = LoggerFactory.getLogger(PLCDataQueryService.class);

    private final DatabaseInitializationService databaseInitializationService;

    public PLCDataQueryService(DatabaseInitializationService databaseInitializationService) {
        this.databaseInitializationService = databaseInitializationService;
    }

    /**
     * Mapea una fila de una tabla VIP (fecha + VAB, VAC, VBC, IA, IB, IC, PW, PF, KWhR) a un
     * Map. Única función para este mapeo: la usan getLatestVIPDataByMaquina,
     * getTodayDataByMaquina y getHistoricoVIPByRango, para que las tres lean siempre las
     * mismas columnas.
     */
    private Map<String, Object> mapVipRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        row.put("fecha", rs.getString("fecha"));
        for (String campo : RutaArchivosEnergia.CAMPOS_VIP) {
            row.put(campo, rs.getDouble(campo));
        }
        return row;
    }

    public Map<String, Object> getLatestVIPDataByMaquina(String nombreMaquina) {
        Map<String, Object> result = new HashMap<>();

        try {
            String dbPath = databaseInitializationService.getDailyVIPPath();
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM " + nombreMaquina + " ORDER BY fecha DESC LIMIT 1");
                 ResultSet rs = pstmt.executeQuery()) {

                if (rs.next()) {
                    result = mapVipRow(rs);
                    result.put("nombreMaquina", nombreMaquina);
                    logger.debug("Retrieved latest data for {}: {}", nombreMaquina, result);
                } else {
                    result.put("error", "No data found for " + nombreMaquina);
                    logger.warn("No data found for machine: {}", nombreMaquina);
                }
            }
        } catch (SQLException e) {
            result.put("error", "Database error: " + e.getMessage());
            logger.error("Error retrieving data for {}: {}", nombreMaquina, e.getMessage());
        }

        return result;
    }

    public Map<String, Object> getLatestKWhDataByMaquina(String nombreMaquina) {
        Map<String, Object> result = new HashMap<>();

        try {
            String dbPath = databaseInitializationService.getDailyPath();
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM " + nombreMaquina + " ORDER BY fecha DESC LIMIT 1");
                 ResultSet rs = pstmt.executeQuery()) {

                if (rs.next()) {
                    result.put("fecha", rs.getString("fecha"));
                    result.put("kwh", rs.getDouble("kwh"));
                    result.put("nombreMaquina", nombreMaquina);
                    logger.debug("Retrieved latest KWh data for {}: {}", nombreMaquina, result);
                } else {
                    result.put("error", "No data found for " + nombreMaquina);
                    logger.warn("No data found for machine: {}", nombreMaquina);
                }
            }
        } catch (SQLException e) {
            result.put("error", "Database error: " + e.getMessage());
            logger.error("Error retrieving KWh data for {}: {}", nombreMaquina, e.getMessage());
        }

        return result;
    }

    public java.util.List<Map<String, Object>> getTodayDataByMaquina(String nombreMaquina) {
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();

        try {
            String dbPath = databaseInitializationService.getDailyVIPPath();
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM " + nombreMaquina + " ORDER BY fecha ASC");
                 ResultSet rs = pstmt.executeQuery()) {

                while (rs.next()) {
                    result.add(mapVipRow(rs));
                }
                logger.debug("Retrieved {} records for {} today", result.size(), nombreMaquina);
            }
        } catch (SQLException e) {
            logger.error("Error retrieving today data for {}: {}", nombreMaquina, e.getMessage());
        }

        return result;
    }

    public java.util.List<Map<String, Object>> getTodayKWhDataByMaquina(String nombreMaquina) {
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();

        try {
            String dbPath = databaseInitializationService.getDailyPath();
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 PreparedStatement pstmt = conn.prepareStatement("SELECT fecha, kwh FROM " + nombreMaquina + " ORDER BY fecha ASC");
                 ResultSet rs = pstmt.executeQuery()) {

                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("fecha", rs.getString("fecha"));
                    row.put("kwh", rs.getDouble("kwh"));
                    result.add(row);
                }
                logger.debug("Retrieved {} KWh records for {} today", result.size(), nombreMaquina);
            }
        } catch (SQLException e) {
            logger.error("Error retrieving KWh data for {}: {}", nombreMaquina, e.getMessage());
        }

        return result;
    }

    private String buildMonthlyPath(YearMonth ym, boolean vip) {
        int year = ym.getYear();
        int month = ym.getMonthValue();
        String monthName = RutaArchivosEnergia.getNombreMes(month);
        //String monthFolder = String.format("%02d", month) + "_" + monthName;
        String monthFolder = monthName;
        String fileName =  monthName + (vip ? "VIP" : "");
        return RutaArchivosEnergia.BASE_PATH + "\\" + year + "\\" + monthFolder + "\\" + fileName;
    }

    public List<Map<String, Object>> getHistoricoVIPByRango(String maquina, LocalDate desde, LocalDate hasta) {
        List<Map<String, Object>> result = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");

        YearMonth ymDesde = YearMonth.from(desde);
        YearMonth ymHasta = YearMonth.from(hasta);

        YearMonth cursor = ymDesde;
        while (!cursor.isAfter(ymHasta)) {
            String dbPath = buildMonthlyPath(cursor, true);
            java.io.File dbFile = new java.io.File(dbPath);

            if (dbFile.exists()) {
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                     PreparedStatement ps = conn.prepareStatement(
                             "SELECT * FROM " + maquina + " ORDER BY fecha ASC");
                     ResultSet rs = ps.executeQuery()) {

                    while (rs.next()) {
                        String fechaStr = rs.getString("fecha");
                        try {
                            Date fechaParsed = sdf.parse(fechaStr);
                            LocalDate fechaLocal = fechaParsed.toInstant()
                                    .atZone(ZoneId.systemDefault()).toLocalDate();

                            if (!fechaLocal.isBefore(desde) && !fechaLocal.isAfter(hasta)) {
                                result.add(mapVipRow(rs));
                            }
                        } catch (java.text.ParseException ignored) {}
                    }
                } catch (SQLException e) {
                    logger.error("Error leyendo historico VIP {}: {}", dbPath, e.getMessage());
                }
            } else {
                logger.warn("BD mensual VIP no encontrada: {}", dbPath);
            }
            cursor = cursor.plusMonths(1);
        }
        return result;
    }

    public List<Map<String, Object>> getHistoricoKWhByRango(String maquina, LocalDate desde, LocalDate hasta) {
        List<Map<String, Object>> result = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");

        YearMonth ymDesde = YearMonth.from(desde);
        YearMonth ymHasta = YearMonth.from(hasta);

        YearMonth cursor = ymDesde;
        while (!cursor.isAfter(ymHasta)) {
            String dbPath = buildMonthlyPath(cursor, false);
            java.io.File dbFile = new java.io.File(dbPath);

            if (dbFile.exists()) {
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                     PreparedStatement ps = conn.prepareStatement(
                             "SELECT fecha, kwh FROM " + maquina + " ORDER BY fecha ASC");
                     ResultSet rs = ps.executeQuery()) {

                    while (rs.next()) {
                        String fechaStr = rs.getString("fecha");
                        try {
                            Date fechaParsed = sdf.parse(fechaStr);
                            LocalDate fechaLocal = fechaParsed.toInstant()
                                    .atZone(ZoneId.systemDefault()).toLocalDate();

                            if (!fechaLocal.isBefore(desde) && !fechaLocal.isAfter(hasta)) {
                                Map<String, Object> row = new HashMap<>();
                                row.put("fecha", fechaStr);
                                row.put("kwh", rs.getDouble("kwh"));
                                result.add(row);
                            }
                        } catch (java.text.ParseException ignored) {}
                    }
                } catch (SQLException e) {
                    logger.error("Error leyendo historico KWh {}: {}", dbPath, e.getMessage());
                }
            } else {
                logger.warn("BD mensual KWh no encontrada: {}", dbPath);
            }
            cursor = cursor.plusMonths(1);
        }
        return result;
    }
    public Map<String, Object> getKWhByFechaExacta(String maquina, String fechaStr) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Buscar sin segundos: "dd-MM-yyyy HH:mm"
            String fechaBusqueda = fechaStr.substring(0, 16);

            logger.info("Buscando KWh para {} con fecha: {}", maquina, fechaBusqueda);

            String dbPath = databaseInitializationService.getDailyPath();
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 PreparedStatement pstmt = conn.prepareStatement(
                         "SELECT kwh FROM " + maquina + " WHERE fecha LIKE ? LIMIT 1");
            ) {
                pstmt.setString(1, fechaBusqueda + "%");
                ResultSet rs = pstmt.executeQuery();

                if (rs.next()) {
                    double kwh = rs.getDouble("kwh");
                    logger.info("✅ ENCONTRADO KWh: {}", kwh);
                    result.put("kwh", kwh);
                } else {
                    logger.warn("❌ NO ENCONTRADO para fecha: {}", fechaBusqueda);
                    result.put("kwh", 0.0);
                }
            }
        } catch (Exception e) {
            logger.error("❌ ERROR: {}", e.getMessage());
            result.put("kwh", 0.0);
        }

        return result;
    }
}
