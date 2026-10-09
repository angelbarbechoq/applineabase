package com.example.generador.service;

import com.example.dataacquisition.MaquinasVirtuales;
import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.generador.model.Generador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Qué medidores consumieron y cuáles quedaron en cero en un conjunto de intervalos (todo un
 * período, o solo mientras el generador estuvo en marcha).
 *
 * "En cero" se decide con TODAS las lecturas del intervalo, nunca con un promedio ni una lectura
 * suelta: el contador de kWh no subió y ninguna lectura de potencia fue mayor que cero. Así una
 * carga intermitente (compresores de aire que paran y arrancan) aparece como "intermitente" con el
 * porcentaje del tiempo con carga, no como parada.
 */
@Service
public class ConsumoMedidoresService {

    private static final Logger logger = LoggerFactory.getLogger(ConsumoMedidoresService.class);

    /** Sensores que se guardan como "línea" pero no miden energía (mismo criterio que AlarmaConfigSeeder). */
    private static final Set<String> SENSORES = Set.of(MaquinasVirtuales.TEMPERATURA_AMBIENTE, MaquinasVirtuales.TEMPERATURA_AGUA,
            "PsiAireP1", "PsiAgua", "BarCompHP");
    /** Con potencia menos de este % del tiempo con lectura = intermitente. */
    private static final double PORCENTAJE_CONTINUO = 95.0;
    /**
     * Potencia media (del contador de kWh, que está en kWh en todos los medidores; la columna PW no:
     * algunos la guardan en W) por debajo de la cual solo hay consumo mínimo: el medidor no está en
     * cero (ej. Mixer01 parado con ~50 W de un tablero auxiliar) pero la máquina no trabajó.
     */
    public static final double KW_MEDIO_MINIMO = 1.0;
    /** Salto máximo creíble del contador entre dos lecturas seguidas (kWh); más es basura de lectura. */
    private static final double SALTO_MAXIMO_KWH = 20000;
    private static final DateTimeFormatter CLAVE = DateTimeFormatter.ofPattern("yyyyMMddHH:mm:ss");
    /** Fecha "dd-MM-yyyy HH:mm:ss" pasada a una clave que se ordena como texto. */
    private static final String SQL_CLAVE = "substr(fecha,7,4)||substr(fecha,4,2)||substr(fecha,1,2)||substr(fecha,12)";

    public record Ventana(LocalDateTime desde, LocalDateTime hasta) {
    }

    public enum Estado {
        CONSUMIO("Consumio"), INTERMITENTE("Intermitente"), CONSUMO_MINIMO("Consumo minimo"), EN_CERO("En cero"),
        SIN_DATOS("Sin datos");

        private final String etiqueta;

        Estado(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        public String etiqueta() {
            return etiqueta;
        }
    }

    /**
     * @param kwh            aumento del contador en los intervalos (null = sin lecturas de energía)
     * @param lecturas       lecturas de potencia en los intervalos
     * @param lecturasConCarga lecturas con potencia distinta de cero
     */
    public record ConsumoMedidor(String medidor, String zona, Estado estado, Double kwh, Double kwMedio,
                                 int lecturas, int lecturasConCarga) {
        public Double porcentajeConCarga() {
            return lecturas == 0 ? null : lecturasConCarga * 100.0 / lecturas;
        }
    }

    private final ConfigLoaderService configLoaderService;
    private final GeneradorService generadorService;

    public ConsumoMedidoresService(ConfigLoaderService configLoaderService, GeneradorService generadorService) {
        this.configLoaderService = configLoaderService;
        this.generadorService = generadorService;
    }

    /** Intervalos en marcha de los generadores (arranques registrados), recortados al período y unidos si se pisan. */
    public List<Ventana> ventanasEnMarcha(List<Generador> generadores, LocalDateTime desde, LocalDateTime hasta) {
        List<Ventana> lista = new ArrayList<>();
        for (Generador g : generadores) {
            for (GeneradorService.Arranque a : generadorService.arranques(g)) {
                LocalDateTime ini = a.inicio().isBefore(desde) ? desde : a.inicio();
                LocalDateTime fin = a.fin() == null ? LocalDateTime.now() : a.fin();
                if (fin.isAfter(hasta)) fin = hasta;
                if (fin.isAfter(ini)) lista.add(new Ventana(ini, fin));
            }
        }
        lista.sort(Comparator.comparing(Ventana::desde));
        List<Ventana> unidas = new ArrayList<>();
        for (Ventana v : lista) {
            Ventana ult = unidas.isEmpty() ? null : unidas.get(unidas.size() - 1);
            if (ult != null && !v.desde().isAfter(ult.hasta())) {
                unidas.set(unidas.size() - 1, new Ventana(ult.desde(), v.hasta().isAfter(ult.hasta()) ? v.hasta() : ult.hasta()));
            } else {
                unidas.add(v);
            }
        }
        return unidas;
    }

    public List<ConsumoMedidor> calcular(List<Ventana> ventanas) {
        Map<String, String> zonas = new LinkedHashMap<>();
        for (Map<String, Object> l : configLoaderService.loadLineaIDConfig()) {
            String nombre = String.valueOf(l.get("lineaMaquina"));
            if (nombre.isBlank() || "null".equals(nombre) || SENSORES.contains(nombre)
                    || !PLCDataQueryService.esNombreMaquinaValido(nombre)) continue;
            String zona = l.get("grupo") != null ? String.valueOf(l.get("grupo"))
                    : l.get("zona") != null ? String.valueOf(l.get("zona")) : "";
            zonas.putIfAbsent(nombre, zona);
        }
        double horas = 0;
        for (Ventana v : ventanas) horas += Duration.between(v.desde(), v.hasta()).toSeconds() / 3600.0;

        List<ConsumoMedidor> lista = new ArrayList<>();
        for (Map.Entry<String, String> e : zonas.entrySet()) {
            String m = e.getKey();
            Double kwh = null;
            int lecturas = 0, conCarga = 0;
            for (YearMonth mes : meses(ventanas)) {
                // Cada archivo mensual se limita a su propio mes: el de septiembre repite las
                // primeras horas del 1 de octubre y se contarían dos veces.
                List<Ventana> delMes = recortar(ventanas, mes);
                if (delMes.isEmpty()) continue;
                Double k = kwhEnVentanas(RutaArchivosEnergia.construirRutaMensual(mes.getYear(), mes.getMonthValue(), false), m, delMes);
                if (k != null) kwh = (kwh == null ? 0 : kwh) + k;
                int[] p = potenciaEnVentanas(RutaArchivosEnergia.construirRutaMensual(mes.getYear(), mes.getMonthValue(), true), m, delMes);
                lecturas += p[0];
                conCarga += p[1];
            }
            Double kwMedio = kwh == null || horas <= 0 ? null : kwh / horas;
            Estado estado;
            if (kwh == null && lecturas == 0) estado = Estado.SIN_DATOS;
            else if ((kwh == null || kwh <= 0) && conCarga == 0) estado = Estado.EN_CERO;
            else if (kwMedio != null && kwMedio < KW_MEDIO_MINIMO) estado = Estado.CONSUMO_MINIMO;
            else if (lecturas > 0 && conCarga * 100.0 / lecturas < PORCENTAJE_CONTINUO) estado = Estado.INTERMITENTE;
            else estado = Estado.CONSUMIO;
            lista.add(new ConsumoMedidor(m, e.getValue(), estado, kwh, kwMedio, lecturas, conCarga));
        }
        lista.sort(Comparator.comparing((ConsumoMedidor c) -> c.estado().ordinal())
                .thenComparing(c -> c.kwh() == null ? 0 : -c.kwh()));
        return lista;
    }

    private static List<YearMonth> meses(List<Ventana> ventanas) {
        List<YearMonth> meses = new ArrayList<>();
        if (ventanas.isEmpty()) return meses;
        YearMonth desde = YearMonth.from(ventanas.get(0).desde());
        YearMonth hasta = YearMonth.from(ventanas.get(ventanas.size() - 1).hasta());
        for (YearMonth m = desde; !m.isAfter(hasta); m = m.plusMonths(1)) meses.add(m);
        return meses;
    }

    private static List<Ventana> recortar(List<Ventana> ventanas, YearMonth mes) {
        LocalDateTime ini = mes.atDay(1).atStartOfDay();
        LocalDateTime fin = mes.atEndOfMonth().atTime(23, 59, 59);
        List<Ventana> r = new ArrayList<>();
        for (Ventana v : ventanas) {
            LocalDateTime a = v.desde().isBefore(ini) ? ini : v.desde();
            LocalDateTime b = v.hasta().isAfter(fin) ? fin : v.hasta();
            if (!b.isBefore(a)) r.add(new Ventana(a, b));
        }
        return r;
    }

    /** CASE que numera la ventana de cada fila (NULL = fuera de todas). */
    private static String casoVentana(List<Ventana> ventanas) {
        StringBuilder sb = new StringBuilder("CASE");
        for (int i = 0; i < ventanas.size(); i++) sb.append(" WHEN k BETWEEN ? AND ? THEN ").append(i);
        return sb.append(" END").toString();
    }

    private static int ponerVentanas(PreparedStatement ps, List<Ventana> ventanas, int desde) throws java.sql.SQLException {
        int i = desde;
        for (Ventana v : ventanas) {
            ps.setString(i++, v.desde().format(CLAVE));
            ps.setString(i++, v.hasta().format(CLAVE));
        }
        return i;
    }

    /** Suma de aumentos del contador dentro de cada ventana (sin cruzar de una ventana a otra). */
    private Double kwhEnVentanas(String ruta, String maquina, List<Ventana> ventanas) {
        if (!new File(ruta).exists()) return null;
        String sql = "SELECT count(*), sum(CASE WHEN d >= 0 AND d < " + SALTO_MAXIMO_KWH + " THEN d ELSE 0 END) FROM ("
                + " SELECT kwh - LAG(kwh) OVER (PARTITION BY w ORDER BY k) AS d FROM ("
                + "  SELECT kwh, k, " + casoVentana(ventanas) + " AS w FROM ("
                + "   SELECT kwh, " + SQL_CLAVE + " AS k FROM \"" + maquina + "\" WHERE kwh > 1)"
                + " ) WHERE w IS NOT NULL)";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta.replace('\\', '/') + "?mode=ro");
             PreparedStatement ps = c.prepareStatement(sql)) {
            ponerVentanas(ps, ventanas, 1);
            try (ResultSet r = ps.executeQuery()) {
                return r.next() && r.getInt(1) > 0 ? r.getDouble(2) : null;
            }
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("no such table")) {
                logger.warn("Consumo por medidor: no se pudo leer kwh de {} en {}: {}", maquina, ruta, e.getMessage());
            }
            return null;
        }
    }

    /**
     * [lecturas de potencia en las ventanas, lecturas con potencia distinta de cero]. Distinta de
     * cero y no mayor: en los transformadores la potencia negativa es retorno a la red, no "sin carga".
     */
    private int[] potenciaEnVentanas(String ruta, String maquina, List<Ventana> ventanas) {
        if (!new File(ruta).exists()) return new int[2];
        String sql = "SELECT count(*), sum(CASE WHEN PW <> 0 THEN 1 ELSE 0 END) FROM ("
                + " SELECT PW, " + casoVentana(ventanas) + " AS w FROM ("
                + "  SELECT PW, " + SQL_CLAVE + " AS k FROM \"" + maquina + "\")"
                + ") WHERE w IS NOT NULL";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta.replace('\\', '/') + "?mode=ro");
             PreparedStatement ps = c.prepareStatement(sql)) {
            ponerVentanas(ps, ventanas, 1);
            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? new int[]{r.getInt(1), r.getInt(2)} : new int[2];
            }
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("no such table")) {
                logger.warn("Consumo por medidor: no se pudo leer PW de {} en {}: {}", maquina, ruta, e.getMessage());
            }
            return new int[2];
        }
    }
}
