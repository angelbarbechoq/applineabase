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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
     * El nombre de máquina se concatena directo en el SQL en todos los métodos de esta clase (el
     * nombre de tabla no se puede parametrizar con PreparedStatement) — se valida acá como
     * identificador simple antes de usarlo en cualquiera de ellos. No alcanza con que la UI
     * (ComboBox) solo ofrezca valores fijos: PLCDataController expone estos mismos métodos por
     * REST con "maquina" como @PathVariable libre, y tieneAccesoAMaquina() deja pasar cualquier
     * valor para un usuario ADMIN (no valida contra la lista de máquinas reales), así que esta es
     * la única barrera real contra inyección SQL vía el nombre de tabla.
     */
    public static boolean esNombreMaquinaValido(String maquina) {
        return maquina != null && maquina.matches("[A-Za-z0-9_]+");
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
        if (!esNombreMaquinaValido(nombreMaquina)) {
            result.put("error", "Nombre de máquina inválido: " + nombreMaquina);
            return result;
        }

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
        if (!esNombreMaquinaValido(nombreMaquina)) {
            result.put("error", "Nombre de máquina inválido: " + nombreMaquina);
            return result;
        }

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
        if (!esNombreMaquinaValido(nombreMaquina)) {
            logger.warn("Nombre de máquina inválido: {}", nombreMaquina);
            return result;
        }

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
        if (!esNombreMaquinaValido(nombreMaquina)) {
            logger.warn("Nombre de máquina inválido: {}", nombreMaquina);
            return result;
        }

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

    private static final DateTimeFormatter FECHA_FORMATTER = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);

    /** Minutos hacia atrás usados como base para la derivada — ver calcularDerivadaPorHora.
     * Independiente de la unidad en la que se exprese el resultado (°C/h): esto solo define qué
     * tan lejos se busca el punto de comparación, no cómo se escala la pendiente resultante. */
    private static final int LOOKBACK_MINUTOS_DERIVADA = 5;

    /**
     * Derivada por hora de un sensor tipo KWh (TemperaturaAgua/Ambiente, que en realidad guardan
     * temperatura en la columna "kwh") entre valorActual/momentoActual y un punto de hace
     * ~{@value #LOOKBACK_MINUTOS_DERIVADA} minutos dentro de las lecturas de HOY.
     *
     * Se usa una ventana de varios minutos en vez del punto inmediatamente anterior (un solo
     * ciclo de 60s) porque comparar solo dos lecturas consecutivas hace que la derivada oscile
     * entre un valor real y ~0 apenas el sensor se queda quieto un ciclo (ruido/cuantización de
     * una sola lectura) — promediar sobre varios minutos filtra ese ruido.
     *
     * Se apoya en la misma tabla DIARY que ya usa getTodayKWhDataByMaquina (para el gráfico y la
     * carga inicial de la franja), así que el valor coincide entre la carga inicial (HTML) y las
     * actualizaciones en vivo por SSE, sin depender de un estado en memoria que se pierda al
     * reiniciar la aplicación.
     *
     * @return null si hoy no hay ningún punto anterior a momentoActual (primera lectura del día)
     */
    public Double calcularDerivadaPorHora(String maquina, double valorActual, LocalDateTime momentoActual) {
        List<Map<String, Object>> puntosHoy = getTodayKWhDataByMaquina(maquina);
        if (puntosHoy.isEmpty()) {
            return null;
        }

        LocalDateTime umbral = momentoActual.minusMinutes(LOOKBACK_MINUTOS_DERIVADA);
        Map<String, Object> puntoBase = null;
        for (Map<String, Object> punto : puntosHoy) {
            LocalDateTime fecha = LocalDateTime.parse((String) punto.get("fecha"), FECHA_FORMATTER);
            if (!fecha.isBefore(momentoActual)) {
                break; // ignora el propio punto actual (si ya está persistido) y cualquier lectura futura
            }
            if (puntoBase == null || !fecha.isAfter(umbral)) {
                puntoBase = punto; // se sigue reemplazando: al salir del loop queda el punto más cercano a "hace 5 min" sin pasarse
            } else {
                break;
            }
        }
        if (puntoBase == null) {
            return null;
        }

        LocalDateTime fechaBase = LocalDateTime.parse((String) puntoBase.get("fecha"), FECHA_FORMATTER);
        double valorBase = ((Number) puntoBase.get("kwh")).doubleValue();
        double horasTranscurridas = Duration.between(fechaBase, momentoActual).toMillis() / 3600000.0;
        if (horasTranscurridas <= 0) {
            return null;
        }
        return (valorActual - valorBase) / horasTranscurridas;
    }

    private String buildMonthlyPath(YearMonth ym, boolean vip) {
        return RutaArchivosEnergia.construirRutaMensual(ym.getYear(), ym.getMonthValue(), vip);
    }

    public List<Map<String, Object>> getHistoricoVIPByRango(String maquina, LocalDate desde, LocalDate hasta) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!esNombreMaquinaValido(maquina)) {
            logger.warn("Nombre de máquina inválido: {}", maquina);
            return result;
        }
        SimpleDateFormat sdf = new SimpleDateFormat(RutaArchivosEnergia.FORMATO_FECHA_HORA);

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
        if (!esNombreMaquinaValido(maquina)) {
            logger.warn("Nombre de máquina inválido: {}", maquina);
            return result;
        }
        SimpleDateFormat sdf = new SimpleDateFormat(RutaArchivosEnergia.FORMATO_FECHA_HORA);

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
        if (!esNombreMaquinaValido(maquina)) {
            logger.warn("Nombre de máquina inválido: {}", maquina);
            result.put("kwh", 0.0);
            return result;
        }

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

    /**
     * Igual que getKWhByFechaExacta, pero para Histórico: busca en el archivo MENSUAL que
     * corresponde a la fecha pedida (no en el diario, que solo tiene el día de hoy). El punto
     * clickeado en un gráfico de Histórico puede ser de cualquier día del rango consultado, a
     * diferencia de ChartsView que siempre clickea sobre "hoy".
     */
    public Map<String, Object> getKWhByFechaExactaHistorico(String maquina, String fechaHoraStr) {
        return buscarPorFechaExactaEnMensual(maquina, fechaHoraStr, false, new String[]{"kwh"});
    }

    /** Variante VIP (VAB/VAC/VBC/IA/IB/IC/PW/PF) de getKWhByFechaExactaHistorico. */
    public Map<String, Object> getVIPByFechaExactaHistorico(String maquina, String fechaHoraStr) {
        return buscarPorFechaExactaEnMensual(maquina, fechaHoraStr, true, RutaArchivosEnergia.CAMPOS_VIP);
    }

    private Map<String, Object> buscarPorFechaExactaEnMensual(String maquina, String fechaHoraStr, boolean vip, String[] campos) {
        Map<String, Object> result = new HashMap<>();
        if (!esNombreMaquinaValido(maquina)) {
            result.put("error", "Nombre de máquina inválido: " + maquina);
            return result;
        }
        try {
            // Buscar sin segundos: "dd-MM-yyyy HH:mm", igual que getKWhByFechaExacta (la
            // posición del click en el eje X no siempre cae justo en el segundo exacto).
            String fechaBusqueda = fechaHoraStr.substring(0, 16);

            SimpleDateFormat sdfSoloFecha = new SimpleDateFormat("dd-MM-yyyy");
            Date fechaDia = sdfSoloFecha.parse(fechaHoraStr.substring(0, 10));
            YearMonth ym = YearMonth.from(fechaDia.toInstant().atZone(ZoneId.systemDefault()).toLocalDate());

            String dbPath = buildMonthlyPath(ym, vip);
            java.io.File dbFile = new java.io.File(dbPath);
            if (!dbFile.exists()) {
                logger.warn("BD mensual no encontrada para fecha exacta {}: {}", fechaBusqueda, dbPath);
                result.put("error", "BD mensual no encontrada: " + dbPath);
                return result;
            }

            String columnas = String.join(", ", campos);
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                 PreparedStatement pstmt = conn.prepareStatement(
                         "SELECT fecha, " + columnas + " FROM " + maquina + " WHERE fecha LIKE ? LIMIT 1")) {
                pstmt.setString(1, fechaBusqueda + "%");
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        result.put("fecha", rs.getString("fecha"));
                        for (String campo : campos) {
                            result.put(campo, rs.getDouble(campo));
                        }
                    } else {
                        result.put("error", "No se encontró " + maquina + " en " + fechaBusqueda);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error buscando {} por fecha exacta histórica: {}", maquina, e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }
}
