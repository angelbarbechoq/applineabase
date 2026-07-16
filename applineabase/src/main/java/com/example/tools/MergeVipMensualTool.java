package com.example.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

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
 * Reconstruye el mensual a partir de los diarios, que suelen quedar completos aunque
 * el mensual no. Es un bean de Spring (@Service) para poder invocarse desde la UI
 * (ver com.example.tools.ui.ReparacionVipView, solo ADMIN); también se puede seguir
 * corriendo suelto desde IntelliJ con el método main().
 *
 * Sirve tanto para los archivos VIP ({@link #reparar}) como para los normales de KWh
 * ({@link #repararNormal}) — la única diferencia entre ambos es el sufijo "VIP" en el
 * nombre de archivo; la fusión en sí (SELECT * / INSERT OR REPLACE) no depende de las
 * columnas de cada tabla.
 *
 * Detecta solo los archivos diarios que realmente existen en el disco (no asume un
 * rango de días fijo), así que sirve igual hoy que dentro de tres semanas sin tocarlo.
 * Es idempotente: INSERT OR REPLACE por 'fecha', se puede correr las veces que haga falta.
 */
@Service
public class MergeVipMensualTool {

    private static final String BASE_PATH = "C:\\LineaBaseX";
    private static final String[] NOMBRES_MES = {
            "enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
    };

    /** Uso desde IntelliJ (clic derecho -> Run): sin argumentos usa el mes actual; con "yyyy-MM" procesa ese mes. */
    public static void main(String[] args) throws Exception {
        YearMonth mes = args.length > 0 ? YearMonth.parse(args[0]) : YearMonth.now();
        ResultadoReparacion resultado = new MergeVipMensualTool().reparar(mes);
        resultado.log().forEach(System.out::println);
    }

    /** Repara el mensual VIP (VAB, VAC, VBC, IA, IB, IC, PW, PF, KWhR) a partir de los VIP diarios. */
    public ResultadoReparacion reparar(YearMonth mes) {
        return reparar(mes, "VIP");
    }

    /** Repara el mensual normal (columna kwh) a partir de los diarios normales, sin sufijo VIP. */
    public ResultadoReparacion repararNormal(YearMonth mes) {
        return reparar(mes, "");
    }

    private ResultadoReparacion reparar(YearMonth mes, String sufijo) {
        List<String> log = new ArrayList<>();
        String nombreMes = NOMBRES_MES[mes.getMonthValue() - 1];
        String carpetaMes = BASE_PATH + "\\" + mes.getYear() + "\\" + nombreMes;
        String mensualPath = carpetaMes + "\\" + nombreMes + sufijo;
        String etiqueta = sufijo.isEmpty() ? "normal (KWh)" : sufijo;

        log.add("Mes: " + mes + " (" + nombreMes + ")");
        log.add("Mensual " + etiqueta + ": " + mensualPath);

        if (!new File(mensualPath).exists()) {
            log.add("No existe el archivo mensual " + etiqueta + ", nada que hacer: " + mensualPath);
            return new ResultadoReparacion(log, 0, false);
        }

        List<File> diarios = listarArchivosDiarios(carpetaMes, nombreMes, sufijo);
        if (diarios.isEmpty()) {
            log.add("No se encontraron archivos " + etiqueta + " diarios en " + carpetaMes);
            return new ResultadoReparacion(log, 0, false);
        }
        log.add("Archivos diarios encontrados: " + diarios.size());

        List<String> lineas;
        try {
            lineas = leerLineasDesdeConfig();
        } catch (Exception e) {
            log.add("Error leyendo linea-id-config.json: " + e.getMessage());
            return new ResultadoReparacion(log, 0, false);
        }
        log.add("Lineas a procesar: " + lineas.size());

        try (Connection mensualConn = DriverManager.getConnection("jdbc:sqlite:" + mensualPath)) {
            for (File diario : diarios) {
                procesarDia(mensualConn, diario, lineas, log);
            }
        } catch (SQLException e) {
            log.add("Error abriendo el archivo mensual: " + e.getMessage());
            return new ResultadoReparacion(log, diarios.size(), false);
        }
        log.add("Listo.");
        return new ResultadoReparacion(log, diarios.size(), true);
    }

    private void procesarDia(Connection mensualConn, File diario, List<String> lineas, List<String> log) {
        log.add("Procesando " + diario.getName() + "...");
        String rutaEscapada = diario.getAbsolutePath().replace("'", "''");
        try (Statement st = mensualConn.createStatement()) {
            st.execute("ATTACH DATABASE '" + rutaEscapada + "' AS diaAdjunto");
            try {
                for (String linea : lineas) {
                    try {
                        st.execute("INSERT OR REPLACE INTO " + linea + " SELECT * FROM diaAdjunto." + linea);
                    } catch (SQLException e) {
                        // tabla inexistente en ese diario (linea sin datos ese dia), o similar: se salta y sigue
                        log.add("  " + linea + ": " + e.getMessage());
                    }
                }
            } finally {
                st.execute("DETACH DATABASE diaAdjunto");
            }
        } catch (SQLException e) {
            log.add("  Error con " + diario.getName() + ": " + e.getMessage());
        }
    }

    private List<File> listarArchivosDiarios(String carpetaMes, String nombreMes, String sufijo) {
        Pattern patron = Pattern.compile("\\d{2}" + Pattern.quote(nombreMes) + Pattern.quote(sufijo));
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
    private List<String> leerLineasDesdeConfig() throws Exception {
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

    public record ResultadoReparacion(List<String> log, int archivosProcesados, boolean ok) {
    }
}
