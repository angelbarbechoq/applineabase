package com.example.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Herramienta de mantenimiento (NO forma parte de la aplicación en ejecución, no es un
 * bean de Spring): reconstruye el VIP mensual a partir de los VIP diarios, que suelen
 * quedar completos aunque el mensual no. Corre esto cada vez que quieras verificar que
 * el mensual está al día antes de recalcular el horómetro.
 *
 * Cómo correrlo desde IntelliJ: clic derecho sobre este archivo -> Run
 * 'MergeVipMensualTool.main()'. Sin argumentos usa el mes actual; con un argumento
 * "yyyy-MM" (ej. "2026-06") procesa ese mes en particular.
 *
 * Detecta solo los archivos diarios que realmente existen en el disco (no asume un
 * rango de días fijo), así que sirve igual hoy que dentro de tres semanas sin tocarlo.
 * Es idempotente: INSERT OR REPLACE por 'fecha', se puede correr las veces que haga falta.
 */
public class MergeVipMensualTool {

    private static final String BASE_PATH = "C:\\LineaBaseX";
    private static final String[] NOMBRES_MES = {
            "enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
    };

    public static void main(String[] args) throws Exception {
        YearMonth mes = args.length > 0 ? YearMonth.parse(args[0]) : YearMonth.now();
        String nombreMes = NOMBRES_MES[mes.getMonthValue() - 1];
        String carpetaMes = BASE_PATH + "\\" + mes.getYear() + "\\" + nombreMes;
        String mensualPath = carpetaMes + "\\" + nombreMes + "VIP";

        System.out.println("Mes: " + mes + " (" + nombreMes + ")");
        System.out.println("Mensual VIP: " + mensualPath);

        if (!new File(mensualPath).exists()) {
            System.out.println("No existe el archivo mensual VIP, nada que hacer: " + mensualPath);
            return;
        }

        List<File> diarios = listarArchivosDiariosVip(carpetaMes, nombreMes);
        if (diarios.isEmpty()) {
            System.out.println("No se encontraron archivos VIP diarios en " + carpetaMes);
            return;
        }
        System.out.println("Archivos diarios encontrados: " + diarios.size());

        List<String> lineas = leerLineasDesdeConfig();
        System.out.println("Lineas a procesar: " + lineas.size());

        try (Connection mensualConn = DriverManager.getConnection("jdbc:sqlite:" + mensualPath)) {
            for (File diario : diarios) {
                procesarDia(mensualConn, diario, lineas);
            }
        }
        System.out.println("Listo.");
    }

    private static void procesarDia(Connection mensualConn, File diario, List<String> lineas) {
        System.out.println("Procesando " + diario.getName() + "...");
        String rutaEscapada = diario.getAbsolutePath().replace("'", "''");
        try (Statement st = mensualConn.createStatement()) {
            st.execute("ATTACH DATABASE '" + rutaEscapada + "' AS diaAdjunto");
            try {
                for (String linea : lineas) {
                    try {
                        st.execute("INSERT OR REPLACE INTO " + linea + " SELECT * FROM diaAdjunto." + linea);
                    } catch (SQLException e) {
                        // tabla inexistente en ese diario (linea sin datos ese dia), o similar: se salta y sigue
                        System.out.println("  " + linea + ": " + e.getMessage());
                    }
                }
            } finally {
                st.execute("DETACH DATABASE diaAdjunto");
            }
        } catch (SQLException e) {
            System.out.println("  Error con " + diario.getName() + ": " + e.getMessage());
        }
    }

    private static List<File> listarArchivosDiariosVip(String carpetaMes, String nombreMes) {
        Pattern patron = Pattern.compile("\\d{2}" + Pattern.quote(nombreMes) + "VIP");
        File[] archivos = new File(carpetaMes).listFiles(
                (dir, name) -> patron.matcher(name).matches());
        List<File> resultado = new ArrayList<>();
        if (archivos != null) {
            resultado.addAll(Arrays.asList(archivos));
        }
        resultado.sort((a, b) -> a.getName().compareTo(b.getName()));
        return resultado;
    }

    @SuppressWarnings("unchecked")
    private static List<String> leerLineasDesdeConfig() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = MergeVipMensualTool.class.getResourceAsStream("/linea-id-config.json")) {
            Map<String, Object> config = mapper.readValue(is, Map.class);
            List<Map<String, Object>> lineasConfig = (List<Map<String, Object>>) config.get("lineas");
            List<String> nombres = new ArrayList<>();
            for (Map<String, Object> linea : lineasConfig) {
                Object nombre = linea.get("lineaMaquina");
                if (nombre != null) {
                    nombres.add(nombre.toString());
                }
            }
            return nombres;
        }
    }
}
