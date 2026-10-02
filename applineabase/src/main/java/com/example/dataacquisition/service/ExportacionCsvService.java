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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exportación masiva a CSV de las tablas de energía (todas las máquinas) de un rango de meses:
 * un solo CSV por tabla con todo el rango ("{tabla}_{mes}_{año}[_a_{mes}_{año}].csv"),
 * empaquetados en un ZIP que se escribe al vuelo sobre la respuesta HTTP (todas las máquinas
 * de un mes son cientos de MB, no se arma en memoria: cada tabla se lee y escribe mes por mes).
 * Cada CSV junta por fecha las columnas pedidas del archivo mensual normal (kwh) y del VIP
 * (PW, voltajes, corrientes, PF).
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

    /** Conexiones abiertas a los archivos de un mes (null si ese archivo no existe o no hace falta). */
    private record MesAbierto(YearMonth mes, Connection normal, Connection vip) {
    }

    /** Lo que pasó con cada tabla, separado por categoría para "resumen.txt". */
    private static final class Resumen {
        final List<String> generados = new ArrayList<>();
        final List<String> enCero = new ArrayList<>();
        final List<String> sinDatos = new ArrayList<>();
        final List<String> sinVariables = new ArrayList<>();
        final List<String> errores = new ArrayList<>();

        String armar(String rango) {
            StringBuilder sb = new StringBuilder("Rango exportado: ").append(rango).append("\r\n\r\n");
            seccion(sb, "Archivos generados con datos", generados);
            seccion(sb, "Archivos generados con todos los valores en cero", enCero);
            seccion(sb, "Sin datos en el rango (no se genero archivo)", sinDatos);
            seccion(sb, "Sin las variables elegidas (no se genero archivo)", sinVariables);
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
     * Escribe el ZIP completo en out. El detalle por tabla (con datos, en cero, sin datos, sin las
     * variables pedidas) queda en "resumen.txt" dentro del mismo ZIP, porque la descarga no tiene
     * otra forma de devolverle un mensaje a la pantalla.
     */
    public void exportarZip(YearMonth desde, YearMonth hasta, Set<Variable> variables, OutputStream out) throws IOException {
        Resumen resumen = new Resumen();
        String sufijo = sufijoRango(desde, hasta);
        boolean pideNormal = variables.stream().anyMatch(v -> !v.vip);
        boolean pideVip = variables.stream().anyMatch(v -> v.vip);
        List<MesAbierto> meses = new ArrayList<>();

        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            try {
                for (YearMonth mes = desde; !mes.isAfter(hasta); mes = mes.plusMonths(1)) {
                    File normal = new File(RutaArchivosEnergia.construirRutaMensual(mes.getYear(), mes.getMonthValue(), false));
                    File vip = new File(RutaArchivosEnergia.construirRutaMensual(mes.getYear(), mes.getMonthValue(), true));
                    Connection connNormal = pideNormal && normal.exists() ? abrir(normal) : null;
                    Connection connVip = pideVip && vip.exists() ? abrir(vip) : null;
                    if (connNormal == null && connVip == null) {
                        resumen.errores.add(etiqueta(mes) + ": sin archivos de datos para las variables elegidas");
                        continue;
                    }
                    meses.add(new MesAbierto(mes, connNormal, connVip));
                }

                Set<String> tablas = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (MesAbierto m : meses) {
                    if (m.normal() != null) tablas.addAll(listarTablas(m.normal()));
                    if (m.vip() != null) tablas.addAll(listarTablas(m.vip()));
                }
                for (String tabla : tablas) {
                    exportarTabla(tabla, meses, variables, sufijo, zip, resumen);
                }
            } catch (SQLException e) {
                logger.error("Error exportando {}: {}", sufijo, e.getMessage());
                resumen.errores.add("Error leyendo la base: " + e.getMessage());
            } finally {
                for (MesAbierto m : meses) {
                    cerrar(m.normal());
                    cerrar(m.vip());
                }
            }
            zip.putNextEntry(new ZipEntry("resumen.txt"));
            zip.write(resumen.armar(sufijo.replace('_', ' ')).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    /**
     * Un CSV con todo el rango para una tabla. Las columnas son la unión de las que la tabla tiene
     * en cualquier mes del rango (un medidor VIP agregado a mitad de rango deja vacías esas
     * columnas en los meses anteriores). Se lee y escribe mes por mes para no cargar el rango
     * entero en memoria; como los meses van en orden, el archivo queda ordenado por fecha.
     */
    private void exportarTabla(String tabla, List<MesAbierto> meses, Set<Variable> variables, String sufijo,
                               ZipOutputStream zip, Resumen resumen) throws IOException {
        String nombreArchivo = tabla + "_" + sufijo + ".csv";
        try {
            List<List<String>> columnasNormalPorMes = new ArrayList<>();
            List<List<String>> columnasVipPorMes = new ArrayList<>();
            Set<String> presentes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (MesAbierto m : meses) {
                List<String> cn = m.normal() != null ? columnasPedidas(m.normal(), tabla, variables, false) : List.of();
                List<String> cv = m.vip() != null ? columnasPedidas(m.vip(), tabla, variables, true) : List.of();
                columnasNormalPorMes.add(cn);
                columnasVipPorMes.add(cv);
                presentes.addAll(cn);
                presentes.addAll(cv);
            }

            List<String> columnas = new ArrayList<>();
            for (Variable variable : Variable.values()) {
                if (!variables.contains(variable)) continue;
                for (String columna : variable.columnas) {
                    if (presentes.contains(columna)) columnas.add(columna);
                }
            }
            if (columnas.isEmpty()) {
                resumen.sinVariables.add(tabla);
                return;
            }
            Map<String, Integer> indices = new HashMap<>();
            for (int i = 0; i < columnas.size(); i++) {
                indices.put(columnas.get(i), i);
            }

            // No se cierra el writer: cerraría el ZipOutputStream entero, solo se vacía.
            Writer w = null;
            boolean algunValorDistintoDeCero = false;
            for (int i = 0; i < meses.size(); i++) {
                MesAbierto m = meses.get(i);
                TreeMap<LocalDateTime, Double[]> filas = new TreeMap<>();
                leer(m.normal(), tabla, columnasNormalPorMes.get(i), indices, columnas.size(), filas);
                leer(m.vip(), tabla, columnasVipPorMes.get(i), indices, columnas.size(), filas);
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
                resumen.sinDatos.add(tabla);
                return;
            }
            w.flush();
            zip.closeEntry();
            (algunValorDistintoDeCero ? resumen.generados : resumen.enCero).add(nombreArchivo);
        } catch (SQLException e) {
            logger.error("Error exportando tabla {}: {}", tabla, e.getMessage());
            resumen.errores.add(tabla + ": error leyendo la base (" + e.getMessage() + ")");
        }
    }

    private static Connection abrir(File archivo) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + archivo.getAbsolutePath());
    }

    private static void cerrar(Connection conn) {
        if (conn == null) return;
        try {
            conn.close();
        } catch (SQLException e) {
            logger.warn("No se pudo cerrar conexion SQLite: {}", e.getMessage());
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

    /** Columnas de las variables pedidas (del archivo normal o VIP según vip) que la tabla tiene en ese archivo. */
    private static List<String> columnasPedidas(Connection conn, String tabla, Set<Variable> variables, boolean vip) throws SQLException {
        Set<String> existentes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + tabla + ")")) {
            while (rs.next()) {
                existentes.add(rs.getString("name"));
            }
        }
        List<String> columnas = new ArrayList<>();
        if (existentes.isEmpty()) {
            return columnas;
        }
        for (Variable variable : Variable.values()) {
            if (variable.vip == vip && variables.contains(variable)) {
                for (String columna : variable.columnas) {
                    if (existentes.contains(columna)) {
                        columnas.add(columna);
                    }
                }
            }
        }
        return columnas;
    }

    private static void leer(Connection conn, String tabla, List<String> columnas, Map<String, Integer> indices,
                             int total, Map<LocalDateTime, Double[]> filas) throws SQLException {
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
                    valores[indices.get(columnas.get(i))] = rs.wasNull() ? null : valor;
                }
            }
        }
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
