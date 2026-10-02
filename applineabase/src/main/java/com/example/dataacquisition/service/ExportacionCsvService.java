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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exportación masiva a CSV de las tablas de energía (todas las máquinas) de un rango de meses,
 * en un ZIP que se escribe al vuelo sobre la respuesta HTTP (todas las máquinas de un mes son
 * cientos de MB, no se arma en memoria: cada tabla se lee y escribe mes por mes).
 *
 * Por cada tabla salen dos CSV, uno por base: "{tabla}_{rango}.csv" con la energía (archivo
 * mensual normal, kwh) y "{tabla}VIP_{rango}.csv" con voltajes, corrientes, potencia y PF
 * (archivo mensual VIP). No se juntan en un solo CSV porque las dos bases se graban en ciclos
 * distintos y sus fechas nunca coinciden al segundo.
 */
@Service
public class ExportacionCsvService {

    private static final Logger logger = LoggerFactory.getLogger(ExportacionCsvService.class);

    private static final DateTimeFormatter FECHA_FORMATTER = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);

    private static final String[] COLUMNAS_NORMAL = {"kwh"};
    private static final String[] COLUMNAS_VIP = {"VAB", "VAC", "VBC", "IA", "IB", "IC", "PW", "PF"};

    /** Encabezado legible (con unidad) de cada columna de la base en el CSV. */
    private static final Map<String, String> ENCABEZADOS = Map.of(
            "kwh", "Energia (kWh)", "PW", "Potencia (kW)",
            "VAB", "Voltaje VAB (V)", "VAC", "Voltaje VAC (V)", "VBC", "Voltaje VBC (V)",
            "IA", "Corriente IA (A)", "IB", "Corriente IB (A)", "IC", "Corriente IC (A)",
            "PF", "PF");

    /** Lo que pasó con cada archivo, separado por categoría para "resumen.txt". */
    private static final class Resumen {
        final List<String> generados = new ArrayList<>();
        final List<String> enCero = new ArrayList<>();
        final List<String> sinDatos = new ArrayList<>();
        final List<String> errores = new ArrayList<>();

        String armar(String rango) {
            StringBuilder sb = new StringBuilder("Rango exportado: ").append(rango).append("\r\n\r\n");
            seccion(sb, "Archivos generados con datos", generados);
            seccion(sb, "Archivos generados con todos los valores en cero", enCero);
            seccion(sb, "Sin datos en el rango (no se genero archivo)", sinDatos);
            seccion(sb, "Meses sin archivo o con error de lectura", errores);
            return sb.toString();
        }

        private static void seccion(StringBuilder sb, String titulo, List<String> items) {
            sb.append(titulo).append(": ").append(items.size()).append("\r\n");
            for (String item : items) {
                sb.append("  - ").append(item).append("\r\n");
            }
            sb.append("\r\n");
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

    /** "septiembre_2026" o "julio_2026_a_septiembre_2026": sufijo de los CSV y del ZIP. */
    public static String sufijoRango(YearMonth desde, YearMonth hasta) {
        return etiqueta(desde) + (desde.equals(hasta) ? "" : "_a_" + etiqueta(hasta));
    }

    private static String etiqueta(YearMonth ym) {
        return RutaArchivosEnergia.getNombreMes(ym.getMonthValue()) + "_" + ym.getYear();
    }

    /**
     * Escribe el ZIP completo en out: los CSV de energía y luego los VIP. El detalle por archivo
     * (con datos, en cero, sin datos) queda en "resumen.txt" dentro del mismo ZIP, porque la
     * descarga no tiene otra forma de devolverle un mensaje a la pantalla.
     */
    public void exportarZip(YearMonth desde, YearMonth hasta, OutputStream out) throws IOException {
        Resumen resumen = new Resumen();
        String sufijo = sufijoRango(desde, hasta);
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            exportarBase(desde, hasta, false, sufijo, zip, resumen);
            exportarBase(desde, hasta, true, sufijo, zip, resumen);
            zip.putNextEntry(new ZipEntry("resumen.txt"));
            zip.write(resumen.armar(sufijo.replace('_', ' ')).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    /** Todas las tablas de una de las dos bases (normal o VIP) para el rango. */
    private void exportarBase(YearMonth desde, YearMonth hasta, boolean vip, String sufijo,
                              ZipOutputStream zip, Resumen resumen) throws IOException {
        String nombreBase = vip ? "VIP" : "energia";
        List<Connection> meses = new ArrayList<>();
        try {
            for (YearMonth mes = desde; !mes.isAfter(hasta); mes = mes.plusMonths(1)) {
                File archivo = new File(RutaArchivosEnergia.construirRutaMensual(mes.getYear(), mes.getMonthValue(), vip));
                if (!archivo.exists()) {
                    resumen.errores.add(etiqueta(mes).replace('_', ' ') + ": no existe el archivo de " + nombreBase);
                    continue;
                }
                meses.add(DriverManager.getConnection("jdbc:sqlite:" + archivo.getAbsolutePath()));
            }

            Set<String> tablas = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Connection conn : meses) {
                tablas.addAll(listarTablas(conn));
            }
            for (String tabla : tablas) {
                exportarTabla(tabla, meses, vip ? COLUMNAS_VIP : COLUMNAS_NORMAL,
                        tabla + (vip ? "VIP" : "") + "_" + sufijo + ".csv", zip, resumen);
            }
        } catch (SQLException e) {
            logger.error("Error exportando {} {}: {}", nombreBase, sufijo, e.getMessage());
            resumen.errores.add("Error leyendo la base de " + nombreBase + ": " + e.getMessage());
        } finally {
            for (Connection conn : meses) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    logger.warn("No se pudo cerrar conexion SQLite: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Un CSV con todo el rango para una tabla. Las columnas son las que la tabla tiene en
     * cualquier mes del rango (si en algún mes le falta una, esa celda queda vacía). Se lee y
     * escribe mes por mes; como los meses van en orden, el archivo queda ordenado por fecha.
     */
    private void exportarTabla(String tabla, List<Connection> meses, String[] columnasBase, String nombreArchivo,
                               ZipOutputStream zip, Resumen resumen) throws IOException {
        try {
            List<Set<String>> existentesPorMes = new ArrayList<>();
            Set<String> presentes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Connection conn : meses) {
                Set<String> existentes = columnasExistentes(conn, tabla);
                existentesPorMes.add(existentes);
                presentes.addAll(existentes);
            }
            List<String> columnas = new ArrayList<>();
            for (String columna : columnasBase) {
                if (presentes.contains(columna)) columnas.add(columna);
            }
            if (columnas.isEmpty()) {
                resumen.sinDatos.add(nombreArchivo);
                return;
            }

            // No se cierra el writer: cerraría el ZipOutputStream entero, solo se vacía.
            Writer w = null;
            boolean algunValorDistintoDeCero = false;
            for (int i = 0; i < meses.size(); i++) {
                List<String> columnasMes = new ArrayList<>();
                for (String columna : columnas) {
                    if (existentesPorMes.get(i).contains(columna)) columnasMes.add(columna);
                }
                TreeMap<LocalDateTime, Double[]> filas = leer(meses.get(i), tabla, columnas, columnasMes);
                if (filas.isEmpty()) continue;
                if (w == null) {
                    zip.putNextEntry(new ZipEntry(nombreArchivo));
                    w = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
                    escribirEncabezado(w, columnas);
                }
                escribirFilas(w, filas);
                algunValorDistintoDeCero |= !todosEnCero(filas);
            }

            if (w == null) {
                resumen.sinDatos.add(nombreArchivo);
                return;
            }
            w.flush();
            zip.closeEntry();
            (algunValorDistintoDeCero ? resumen.generados : resumen.enCero).add(nombreArchivo);
        } catch (SQLException e) {
            logger.error("Error exportando {}: {}", nombreArchivo, e.getMessage());
            resumen.errores.add(nombreArchivo + ": error leyendo la base (" + e.getMessage() + ")");
        }
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

    /** Columnas que la tabla tiene en ese archivo (vacío si la tabla no existe en ese mes). */
    private static Set<String> columnasExistentes(Connection conn, String tabla) throws SQLException {
        Set<String> existentes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + tabla + ")")) {
            while (rs.next()) {
                existentes.add(rs.getString("name"));
            }
        }
        return existentes;
    }

    /** Filas de un mes, ordenadas por fecha; cada arreglo sigue el orden de columnas (null = sin dato). */
    private static TreeMap<LocalDateTime, Double[]> leer(Connection conn, String tabla, List<String> columnas,
                                                         List<String> columnasMes) throws SQLException {
        TreeMap<LocalDateTime, Double[]> filas = new TreeMap<>();
        if (columnasMes.isEmpty()) {
            return filas;
        }
        int[] indices = new int[columnasMes.size()];
        for (int i = 0; i < columnasMes.size(); i++) {
            indices[i] = columnas.indexOf(columnasMes.get(i));
        }
        String sql = "SELECT fecha, " + String.join(", ", columnasMes) + " FROM " + tabla;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                LocalDateTime fecha;
                try {
                    fecha = LocalDateTime.parse(rs.getString(1), FECHA_FORMATTER);
                } catch (DateTimeParseException | NullPointerException e) {
                    continue;
                }
                Double[] valores = new Double[columnas.size()];
                for (int i = 0; i < indices.length; i++) {
                    double valor = rs.getDouble(i + 2);
                    valores[indices[i]] = rs.wasNull() ? null : valor;
                }
                filas.put(fecha, valores);
            }
        }
        return filas;
    }

    /** true si ningún valor de ninguna columna es distinto de 0 (medidor que grabó solo ceros). */
    private static boolean todosEnCero(TreeMap<LocalDateTime, Double[]> filas) {
        for (Double[] valores : filas.values()) {
            for (Double valor : valores) {
                if (valor != null && valor != 0.0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Mismo formato que el CSV de Histórico: BOM UTF-8, separador ";" y coma decimal (Excel en español). */
    private static void escribirEncabezado(Writer w, List<String> columnas) throws IOException {
        w.write('﻿');
        w.write("Fecha");
        for (String columna : columnas) {
            w.write(';');
            w.write(ENCABEZADOS.getOrDefault(columna, columna));
        }
        w.write("\r\n");
    }

    private static void escribirFilas(Writer w, TreeMap<LocalDateTime, Double[]> filas) throws IOException {
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
    }
}
