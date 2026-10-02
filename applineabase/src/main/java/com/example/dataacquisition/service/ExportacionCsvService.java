package com.example.dataacquisition.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exportación masiva a CSV de las tablas de energía (todas las máquinas) de uno o varios meses:
 * un CSV por tabla y por mes ("{tabla}_{mes}_{año}.csv"), empaquetados en un ZIP que se escribe
 * al vuelo sobre la respuesta HTTP (un mes completo de todas las máquinas son cientos de MB, no
 * se arma en memoria). Cada CSV junta por fecha las columnas pedidas del archivo mensual normal
 * (kwh) y del VIP (PW, voltajes, corrientes, PF).
 */
@Service
public class ExportacionCsvService {

    private static final Logger logger = LoggerFactory.getLogger(ExportacionCsvService.class);

    private static final DateTimeFormatter FECHA_FORMATTER = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);

    /** Encabezado legible (con unidad) de cada columna de la base en el CSV. */
    private static final Map<String, String> ENCABEZADOS = Map.of(
            "kwh", "Energia (kWh)", "PW", "Potencia (kW)",
            "VAB", "Voltaje VAB (V)", "VAC", "Voltaje VAC (V)", "VBC", "Voltaje VBC (V)",
            "IA", "Corriente IA (A)", "IB", "Corriente IB (A)", "IC", "Corriente IC (A)",
            "PF", "PF");

    /** Variables que se pueden elegir en la exportación y de qué archivo/columnas salen. */
    public enum Variable {
        ENERGIA("Energia kWh", false, "kwh"),
        POTENCIA("Potencia kW", true, "PW"),
        VOLTAJE("Voltaje", true, "VAB", "VAC", "VBC"),
        CORRIENTE("Corriente", true, "IA", "IB", "IC"),
        PF("PF", true, "PF");

        private final String etiqueta;
        private final boolean vip;
        private final String[] columnas;

        Variable(String etiqueta, boolean vip, String... columnas) {
            this.etiqueta = etiqueta;
            this.vip = vip;
            this.columnas = columnas;
        }

        public String getEtiqueta() {
            return etiqueta;
        }
    }

    /** Meses que tienen al menos un archivo mensual (normal o VIP) en disco, del más reciente al más viejo. */
    public List<YearMonth> mesesDisponibles() {
        TreeSet<YearMonth> meses = new TreeSet<>();
        File[] anios = new File(RutaArchivosEnergia.BASE_PATH).listFiles(File::isDirectory);
        if (anios == null) {
            return List.of();
        }
        for (File carpetaAnio : anios) {
            int anio;
            try {
                anio = Integer.parseInt(carpetaAnio.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            for (int mes = 1; mes <= 12; mes++) {
                if (new File(RutaArchivosEnergia.construirRutaMensual(anio, mes, false)).exists()
                        || new File(RutaArchivosEnergia.construirRutaMensual(anio, mes, true)).exists()) {
                    meses.add(YearMonth.of(anio, mes));
                }
            }
        }
        return new ArrayList<>(meses.descendingSet());
    }

    /**
     * Escribe el ZIP completo en out. Lo que no se pudo exportar (tabla sin las columnas pedidas,
     * mes sin archivo) queda listado en "resumen.txt" dentro del mismo ZIP, porque la descarga no
     * tiene otra forma de devolverle un mensaje a la pantalla.
     */
    public void exportarZip(YearMonth desde, YearMonth hasta, Set<Variable> variables, OutputStream out) throws IOException {
        List<String> resumen = new ArrayList<>();
        int archivos = 0;
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (YearMonth mes = desde; !mes.isAfter(hasta); mes = mes.plusMonths(1)) {
                archivos += exportarMes(mes, variables, zip, resumen);
            }
            resumen.add(0, "Archivos generados: " + archivos);
            zip.putNextEntry(new ZipEntry("resumen.txt"));
            zip.write(String.join("\r\n", resumen).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private int exportarMes(YearMonth mes, Set<Variable> variables, ZipOutputStream zip, List<String> resumen) throws IOException {
        String nombreMes = RutaArchivosEnergia.getNombreMes(mes.getMonthValue());
        File normal = new File(RutaArchivosEnergia.construirRutaMensual(mes.getYear(), mes.getMonthValue(), false));
        File vip = new File(RutaArchivosEnergia.construirRutaMensual(mes.getYear(), mes.getMonthValue(), true));
        boolean pideNormal = variables.stream().anyMatch(v -> !v.vip);
        boolean pideVip = variables.stream().anyMatch(v -> v.vip);

        if ((!pideNormal || !normal.exists()) && (!pideVip || !vip.exists())) {
            resumen.add(nombreMes + " " + mes.getYear() + ": sin archivos de datos para las variables elegidas");
            return 0;
        }

        int generados = 0;
        try (Connection connNormal = pideNormal && normal.exists() ? abrir(normal) : null;
             Connection connVip = pideVip && vip.exists() ? abrir(vip) : null) {

            Set<String> tablas = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            if (connNormal != null) tablas.addAll(listarTablas(connNormal));
            if (connVip != null) tablas.addAll(listarTablas(connVip));

            for (String tabla : tablas) {
                List<String> columnasNormal = connNormal != null
                        ? columnasPedidas(connNormal, tabla, variables, false) : List.of();
                List<String> columnasVip = connVip != null
                        ? columnasPedidas(connVip, tabla, variables, true) : List.of();
                if (columnasNormal.isEmpty() && columnasVip.isEmpty()) {
                    resumen.add(tabla + " " + nombreMes + " " + mes.getYear() + ": no tiene las variables elegidas");
                    continue;
                }

                List<String> columnas = new ArrayList<>(columnasNormal);
                columnas.addAll(columnasVip);
                TreeMap<LocalDateTime, Double[]> filas = new TreeMap<>();
                leer(connNormal, tabla, columnasNormal, 0, columnas.size(), filas);
                leer(connVip, tabla, columnasVip, columnasNormal.size(), columnas.size(), filas);
                if (filas.isEmpty()) {
                    resumen.add(tabla + " " + nombreMes + " " + mes.getYear() + ": sin datos");
                    continue;
                }

                zip.putNextEntry(new ZipEntry(tabla + "_" + nombreMes + "_" + mes.getYear() + ".csv"));
                escribirCsv(columnas, filas, zip);
                zip.closeEntry();
                generados++;
            }
        } catch (SQLException e) {
            logger.error("Error exportando {}: {}", mes, e.getMessage());
            resumen.add(nombreMes + " " + mes.getYear() + ": error leyendo la base (" + e.getMessage() + ")");
        }
        return generados;
    }

    private static Connection abrir(File archivo) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + archivo.getAbsolutePath());
    }

    private static List<String> listarTablas(Connection conn) throws SQLException {
        List<String> tablas = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            while (rs.next()) {
                String nombre = rs.getString(1);
                if (PLCDataQueryService.esNombreMaquinaValido(nombre)) {
                    tablas.add(nombre);
                }
            }
        }
        return tablas;
    }

    /** Columnas de las variables pedidas (del archivo normal o VIP según vip) que la tabla realmente tiene. */
    private static List<String> columnasPedidas(Connection conn, String tabla, Set<Variable> variables, boolean vip) throws SQLException {
        Set<String> existentes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + tabla + ")")) {
            while (rs.next()) {
                existentes.add(rs.getString("name"));
            }
        }
        if (existentes.isEmpty()) {
            return List.of();
        }
        Set<String> columnas = new LinkedHashSet<>();
        for (Variable variable : Variable.values()) {
            if (variable.vip == vip && variables.contains(variable)) {
                for (String columna : variable.columnas) {
                    if (existentes.contains(columna)) {
                        columnas.add(columna);
                    }
                }
            }
        }
        return new ArrayList<>(columnas);
    }

    private static void leer(Connection conn, String tabla, List<String> columnas, int offset, int total,
                             Map<LocalDateTime, Double[]> filas) throws SQLException {
        if (conn == null || columnas.isEmpty()) {
            return;
        }
        String sql = "SELECT fecha, " + String.join(", ", columnas) + " FROM " + tabla;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                LocalDateTime fecha;
                try {
                    fecha = LocalDateTime.parse(rs.getString(1), FECHA_FORMATTER);
                } catch (DateTimeParseException | NullPointerException e) {
                    continue;
                }
                Double[] valores = filas.computeIfAbsent(fecha, f -> new Double[total]);
                for (int i = 0; i < columnas.size(); i++) {
                    double valor = rs.getDouble(i + 2);
                    valores[offset + i] = rs.wasNull() ? null : valor;
                }
            }
        }
    }

    /** Mismo formato que el CSV de Histórico: BOM UTF-8, separador ";" y coma decimal (Excel en español). */
    private static void escribirCsv(List<String> columnas, TreeMap<LocalDateTime, Double[]> filas, OutputStream out) throws IOException {
        // No se cierra el writer: cerraría el ZipOutputStream entero, solo se vacía al final.
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write('﻿');
        w.write("Fecha");
        for (String columna : columnas) {
            w.write(';');
            w.write(ENCABEZADOS.getOrDefault(columna, columna));
        }
        w.write("\r\n");
        StringBuilder linea = new StringBuilder();
        for (Map.Entry<LocalDateTime, Double[]> fila : filas.entrySet()) {
            linea.setLength(0);
            linea.append(FECHA_FORMATTER.format(fila.getKey()));
            for (Double valor : fila.getValue()) {
                linea.append(';');
                if (valor != null) {
                    linea.append(String.format(java.util.Locale.US, "%.2f", valor).replace('.', ','));
                }
            }
            linea.append("\r\n");
            w.write(linea.toString());
        }
        w.flush();
    }
}
