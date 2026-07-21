package com.example.dataacquisition.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class DatabaseInitializationService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseInitializationService.class);

    private final ConfigLoaderService configLoaderService;
    private Connection conexion;

    private LocalDate lastDate;
    private YearMonth lastMonth;

    private String dailyPath;
    private String dailyVIPPath;
    private String monthlyPath;
    private String monthlyVIPPath;

    private Connection connDaily;
    private Connection connDailyVIP;
    private Connection connMonthly;
    private Connection connMonthlyVIP;

    public DatabaseInitializationService(ConfigLoaderService configLoaderService) {
        this.configLoaderService = configLoaderService;
        logger.info("DatabaseInitializationService iniciando...");
        createDatabases();
        this.lastDate = LocalDate.now();
        this.lastMonth = YearMonth.now();
        logger.info("DatabaseInitializationService iniciado.");
    }

    private void createDatabases() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        int month = today.getMonthValue();
        int day = today.getDayOfMonth();

        String monthName = RutaArchivosEnergia.getNombreMes(month);
        //String monthFormatted = String.format("%02d", month);
        String dayFormatted = String.format("%02d", day);

        String monthFolder =  monthName;
        String monthPath = RutaArchivosEnergia.BASE_PATH + "\\" + year + "\\" + monthFolder;

        try {
            Files.createDirectories(Paths.get(monthPath));

            this.dailyPath = monthPath + "\\" + dayFormatted  + monthName;
            this.dailyVIPPath = monthPath + "\\" + dayFormatted + monthName + "VIP";
            this.monthlyPath = monthPath + "\\" +monthName;
            this.monthlyVIPPath = monthPath + "\\"+ monthName + "VIP";

            String[] lineas = obtenerLineas();

            for (String linea : lineas) {
                creaTabla(dailyPath, linea, RutaArchivosEnergia.CAMPOS_NORMAL);
                creaTabla(dailyVIPPath, linea, RutaArchivosEnergia.CAMPOS_VIP);
                creaTabla(monthlyPath, linea, RutaArchivosEnergia.CAMPOS_NORMAL);
                creaTabla(monthlyVIPPath, linea, RutaArchivosEnergia.CAMPOS_VIP);
            }

        } catch (Exception e) {
            logger.error("Error creando directorios", e);
        }
    }

    public void creaTabla(String dbPath, String nombreTabla, String[] datos) {
        try {
            String sqlCrearTabla = RutaArchivosEnergia.construirSqlCrearTabla(nombreTabla, datos);

            conectar(dbPath);

            Statement stmt = conexion.createStatement();
            stmt.executeUpdate(sqlCrearTabla);

            DatabaseMetaData meta = conexion.getMetaData();
            ResultSet rs = meta.getColumns(null, null, nombreTabla, null);
            List<String> columnasExistentes = new ArrayList<>();
            while (rs.next()) {
                columnasExistentes.add(rs.getString("COLUMN_NAME"));
            }

            for (String columna : datos) {
                if (!columnasExistentes.contains(columna)) {
                    String sqlAgregarColumna = "ALTER TABLE " + nombreTabla + " ADD COLUMN " + columna + " FLOAT NOT NULL DEFAULT 0";
                    stmt.executeUpdate(sqlAgregarColumna);
                }
            }

            rs.close();
            stmt.close();
            desconectar();
        } catch (SQLException ex) {
            logger.error("Error creando tabla {} en {}: {}", nombreTabla, dbPath, ex.getMessage());
        }
    }

    public void guardarDato(Object[] datos, String nombreTabla, String dbPath) {
        try {
            conectar(dbPath);

            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < datos.length; i++) {
                if (i == datos.length - 1) placeholders.append("?");
                else placeholders.append("?,");
            }

            String sqlInsert = "INSERT OR REPLACE INTO " + nombreTabla + " VALUES (" + placeholders + ")";
            PreparedStatement pstmt = conexion.prepareStatement(sqlInsert);

            for (int i = 0; i < datos.length; i++) {
                pstmt.setObject(i + 1, datos[i]);
            }

            pstmt.executeUpdate();
            pstmt.close();
            desconectar();
        } catch (SQLException ex) {
            logger.error("Error guardando datos en tabla {} en {}: {}", nombreTabla, dbPath, ex.getMessage());
        }
    }

    private void conectar(String dbPath) throws SQLException {
        String url = "jdbc:sqlite:" + dbPath;
        conexion = DriverManager.getConnection(url);
    }

    private void desconectar() {
        try {
            if (conexion != null && !conexion.isClosed()) {
                conexion.close();
            }
        } catch (SQLException e) {
            logger.error("Error desconectando", e);
        }
    }

    private String[] obtenerLineas() {
        List<Map<String, Object>> lineasConfig = configLoaderService.loadLineaIDConfig();
        String[] lineas = new String[lineasConfig.size()];
        for (int i = 0; i < lineasConfig.size(); i++) {
            lineas[i] = (String) lineasConfig.get(i).get("lineaMaquina");
        }
        return lineas;
    }

    public void verifyAndCreate() {
        LocalDate today = LocalDate.now();
        YearMonth currentMonth = YearMonth.now();

        if (!today.isEqual(lastDate) || !currentMonth.equals(lastMonth)) {
            createDatabases();
            lastDate = today;
            lastMonth = currentMonth;
        }
    }

    /**
     * Lista los meses (año+mes) para los que existe una carpeta de datos en BASE_PATH,
     * ordenados de más antiguo a más reciente. Usado por el backfill del horómetro para
     * saber desde cuándo hay histórico disponible, sin asumir una fecha de inicio fija.
     */
    public List<YearMonth> listarMesesDisponibles() {
        List<YearMonth> meses = new ArrayList<>();
        File[] carpetasAnio = new File(RutaArchivosEnergia.BASE_PATH).listFiles(File::isDirectory);
        if (carpetasAnio == null) {
            return meses;
        }
        for (File carpetaAnio : carpetasAnio) {
            int anio;
            try {
                anio = Integer.parseInt(carpetaAnio.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            File[] carpetasMes = carpetaAnio.listFiles(File::isDirectory);
            if (carpetasMes == null) {
                continue;
            }
            for (File carpetaMes : carpetasMes) {
                int numeroMes = RutaArchivosEnergia.getMesNumeroDesdeNombre(carpetaMes.getName());
                if (numeroMes > 0) {
                    meses.add(YearMonth.of(anio, numeroMes));
                }
            }
        }
        Collections.sort(meses);
        return meses;
    }

    public String getDailyPath() { return dailyPath; }
    public void setDailyPath(String dailyPath) { this.dailyPath = dailyPath; }
    public String getDailyVIPPath() { return dailyVIPPath; }
    public void setDailyVIPPath(String dailyVIPPath) { this.dailyVIPPath = dailyVIPPath; }
    public String getMonthlyPath() { return monthlyPath; }
    public void setMonthlyPath(String monthlyPath) { this.monthlyPath = monthlyPath; }
    public String getMonthlyVIPPath() { return monthlyVIPPath; }
    public void setMonthlyVIPPath(String monthlyVIPPath) { this.monthlyVIPPath = monthlyVIPPath; }

    public void beginBatch() {
        connDaily = null;
        connDailyVIP = null;
        connMonthly = null;
        connMonthlyVIP = null;

        try {
            connDaily = DriverManager.getConnection("jdbc:sqlite:" + dailyPath);
            enableWAL(connDaily);
            connDaily.setAutoCommit(false);

            connDailyVIP = DriverManager.getConnection("jdbc:sqlite:" + dailyVIPPath);
            enableWAL(connDailyVIP);
            connDailyVIP.setAutoCommit(false);

            connMonthly = DriverManager.getConnection("jdbc:sqlite:" + monthlyPath);
            enableWAL(connMonthly);
            connMonthly.setAutoCommit(false);

            connMonthlyVIP = DriverManager.getConnection("jdbc:sqlite:" + monthlyVIPPath);
            enableWAL(connMonthlyVIP);
            connMonthlyVIP.setAutoCommit(false);

            logger.debug("Batch connections opened with WAL enabled and transactions started");
        } catch (SQLException e) {
            logger.error("Error opening batch connections: {}", e.getMessage());
            rollbackBatch();
        }
    }

    private void enableWAL(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            logger.debug("WAL mode enabled for connection");
        }
    }

    public void guardarDatoBatch(Object[] datos, String nombreTabla, String tipo) {
        try {
            Connection conn = getConnectionByType(tipo);
            if (conn == null || conn.isClosed()) {
                logger.error("Connection is null or closed for tipo: {}", tipo);
                rollbackBatch();
                return;
            }

            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < datos.length; i++) {
                if (i == datos.length - 1) placeholders.append("?");
                else placeholders.append("?,");
            }

            String sqlInsert = "INSERT OR REPLACE INTO " + nombreTabla + " VALUES (" + placeholders + ")";
            PreparedStatement pstmt = conn.prepareStatement(sqlInsert);

            for (int i = 0; i < datos.length; i++) {
                pstmt.setObject(i + 1, datos[i]);
            }

            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException ex) {
            logger.error("Error guardando datos en tabla {} (tipo {}): {}", nombreTabla, tipo, ex.getMessage());
            rollbackBatch();
        }
    }

    public void endBatch() {
        boolean hasError = false;

        hasError |= !commitConnection("DAILY", connDaily);
        hasError |= !commitConnection("DAILY_VIP", connDailyVIP);
        hasError |= !commitConnection("MONTHLY", connMonthly);
        hasError |= !commitConnection("MONTHLY_VIP", connMonthlyVIP);

        if (hasError) {
            logger.error("Error committing batch transactions, rolling back...");
            rollbackBatch();
        } else {
            logger.debug("Batch transactions committed and connections closed");
        }
    }

    private boolean commitConnection(String tipo, Connection conn) {
        if (conn == null) return true;

        try {
            if (!conn.isClosed()) {
                conn.commit();
                conn.close();
                return true;
            }
        } catch (SQLException e) {
            logger.error("Error committing/closing connection {}: {}", tipo, e.getMessage());
            return false;
        }
        return true;
    }

    private void rollbackBatch() {
        rollbackConnection("DAILY", connDaily);
        rollbackConnection("DAILY_VIP", connDailyVIP);
        rollbackConnection("MONTHLY", connMonthly);
        rollbackConnection("MONTHLY_VIP", connMonthlyVIP);

        logger.debug("Batch transactions rolled back and connections closed");
    }

    private void rollbackConnection(String tipo, Connection conn) {
        if (conn == null) return;

        try {
            if (!conn.isClosed()) {
                conn.rollback();
            }
        } catch (SQLException e) {
            logger.warn("Error rolling back transaction for {}: {}", tipo, e.getMessage());
        } finally {
            try {
                conn.close();
            } catch (SQLException e) {
                logger.warn("Error closing connection {}: {}", tipo, e.getMessage());
            }
        }
    }

    private Connection getConnectionByType(String tipo) {
        return switch (tipo) {
            case "DAILY" -> connDaily;
            case "DAILY_VIP" -> connDailyVIP;
            case "MONTHLY" -> connMonthly;
            case "MONTHLY_VIP" -> connMonthlyVIP;
            default -> null;
        };
    }
}
