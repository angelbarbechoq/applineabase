package com.example.calidad.service;

import com.example.calidad.model.ConfiguracionCalidad;
import com.example.calidad.model.Indicador;
import com.example.calidad.model.TensionNominal;
import com.example.calidad.repository.ConfiguracionCalidadRepository;
import com.example.calidad.repository.TensionNominalRepository;
import com.example.dataacquisition.RutaArchivosEnergia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Lectura y evaluación de los archivos de calidad de energía: última lectura por máquina,
 * promedios de 10 minutos (como pide la norma) y cumplimiento de límites en un período. Los
 * resúmenes se calculan al leer; el archivo guarda el dato crudo de cada minuto.
 */
@Service
public class CalidadEnergiaService {

    private static final Logger logger = LoggerFactory.getLogger(CalidadEnergiaService.class);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);
    public static final int MINUTOS_BLOQUE = 10;

    public enum Estado { OK, AVISO, FUERA, SIN_DATO }

    /** Una fila (un minuto) del archivo de calidad. */
    public record Fila(LocalDateTime fecha, Map<String, Double> valores, boolean desbICalculado, boolean desbVCalculado) {
    }

    /** Un bloque de 10 minutos: promedio por columna. */
    public record Bloque(LocalDateTime inicio, Map<String, Double> promedios) {
    }

    /** Cumplimiento de un indicador en un período, sobre promedios de 10 minutos. */
    public record Cumplimiento(int bloques, double pctDentro, Double peor, Estado estadoPeor) {
    }

    private final ConfiguracionCalidadRepository configRepository;
    private final TensionNominalRepository nominalRepository;

    public CalidadEnergiaService(ConfiguracionCalidadRepository configRepository, TensionNominalRepository nominalRepository) {
        this.configRepository = configRepository;
        this.nominalRepository = nominalRepository;
    }

    // ================= Configuración =================

    public ConfiguracionCalidad configuracion() {
        return configRepository.findById(ConfiguracionCalidad.ID_UNICO)
                .orElseGet(() -> configRepository.save(new ConfiguracionCalidad()));
    }

    public ConfiguracionCalidad guardarConfiguracion(ConfiguracionCalidad c) {
        return configRepository.save(c);
    }

    public double tensionNominal(String maquina) {
        return nominalRepository.findByLineaMaquina(maquina).map(TensionNominal::getVoltiosFaseFase)
                .orElse(configuracion().getTensionNominalPorDefecto());
    }

    public boolean tieneNominalPropia(String maquina) {
        return nominalRepository.findByLineaMaquina(maquina).isPresent();
    }

    public void guardarTensionNominal(String maquina, double voltios) {
        TensionNominal t = nominalRepository.findByLineaMaquina(maquina).orElseGet(() -> new TensionNominal(maquina, voltios));
        t.setVoltiosFaseFase(voltios);
        nominalRepository.save(t);
    }

    // ================= Evaluación =================

    public Estado evaluar(Indicador ind, Double valor, double nominal, ConfiguracionCalidad cfg) {
        if (valor == null) {
            return Estado.SIN_DATO;
        }
        return switch (ind) {
            case THD_TENSION -> porLimite(valor, nominal <= 1000 ? cfg.getThdTensionBajaTension() : cfg.getThdTensionMediaTension());
            case THD_CORRIENTE -> porLimite(valor, cfg.getThdCorriente());
            case DESBALANCE_TENSION -> porLimite(valor, cfg.getDesbalanceTension());
            case DESBALANCE_CORRIENTE -> porLimite(valor, cfg.getDesbalanceCorriente());
            case TENSION -> {
                double desvio = Math.abs(valor - nominal) / nominal * 100.0;
                yield desvio > cfg.getTensionFueraPct() ? Estado.FUERA
                        : desvio > cfg.getTensionAvisoPct() ? Estado.AVISO : Estado.OK;
            }
            case FRECUENCIA -> {
                double desvio = Math.abs(valor - cfg.getFrecuenciaNominal()) / cfg.getFrecuenciaNominal() * 100.0;
                yield desvio > cfg.getFrecuenciaPct() ? Estado.FUERA
                        : desvio > cfg.getFrecuenciaPct() / 2 ? Estado.AVISO : Estado.OK;
            }
            case FACTOR_POTENCIA -> valor < cfg.getPfMinimo() ? Estado.FUERA
                    : valor < cfg.getPfMinimo() + 0.03 ? Estado.AVISO : Estado.OK;
        };
    }

    /** Texto del límite que aplica, para mostrar en pantalla. */
    public String textoLimite(Indicador ind, double nominal, ConfiguracionCalidad cfg) {
        return switch (ind) {
            case THD_TENSION -> "<= " + fmt(nominal <= 1000 ? cfg.getThdTensionBajaTension() : cfg.getThdTensionMediaTension()) + " %";
            case THD_CORRIENTE -> "<= " + fmt(cfg.getThdCorriente()) + " %";
            case DESBALANCE_TENSION -> "<= " + fmt(cfg.getDesbalanceTension()) + " %";
            case DESBALANCE_CORRIENTE -> "<= " + fmt(cfg.getDesbalanceCorriente()) + " %";
            case TENSION -> fmt(nominal) + " V +/- " + fmt(cfg.getTensionFueraPct()) + " %";
            case FRECUENCIA -> fmt(cfg.getFrecuenciaNominal()) + " Hz +/- " + fmt(cfg.getFrecuenciaPct()) + " %";
            case FACTOR_POTENCIA -> ">= " + fmt(cfg.getPfMinimo());
        };
    }

    /** Valor del límite para dibujarlo como línea en el gráfico (null si no aplica una sola línea). */
    public Double lineaLimite(Indicador ind, double nominal, ConfiguracionCalidad cfg) {
        return switch (ind) {
            case THD_TENSION -> nominal <= 1000 ? cfg.getThdTensionBajaTension() : cfg.getThdTensionMediaTension();
            case THD_CORRIENTE -> cfg.getThdCorriente();
            case DESBALANCE_TENSION -> cfg.getDesbalanceTension();
            case DESBALANCE_CORRIENTE -> cfg.getDesbalanceCorriente();
            case FACTOR_POTENCIA -> cfg.getPfMinimo();
            case TENSION, FRECUENCIA -> null;
        };
    }

    private static Estado porLimite(double valor, double limite) {
        return valor > limite ? Estado.FUERA : valor > limite * 0.8 ? Estado.AVISO : Estado.OK;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ================= Lectura de archivos =================

    /** Máquinas con datos de calidad en el mes actual o el anterior. */
    public List<String> maquinasConDatos() {
        TreeSet<String> maquinas = new TreeSet<>();
        YearMonth hoy = YearMonth.now();
        for (YearMonth m : List.of(hoy, hoy.minusMonths(1))) {
            maquinas.addAll(tablas(m));
        }
        return new ArrayList<>(maquinas);
    }

    /** Meses que tienen archivo de calidad, del más reciente al más antiguo. */
    public List<YearMonth> mesesConDatos() {
        List<YearMonth> meses = new ArrayList<>();
        YearMonth m = YearMonth.now();
        for (int i = 0; i < 36; i++, m = m.minusMonths(1)) {
            if (new File(CalidadEnergiaAlmacen.rutaArchivo(m)).exists()) {
                meses.add(m);
            }
        }
        return meses;
    }

    public List<String> tablas(YearMonth mes) {
        List<String> tablas = new ArrayList<>();
        String ruta = CalidadEnergiaAlmacen.rutaArchivo(mes);
        if (!new File(ruta).exists()) {
            return tablas;
        }
        try (Connection c = abrir(ruta); ResultSet r = c.createStatement()
                .executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
            while (r.next()) {
                tablas.add(r.getString(1));
            }
        } catch (SQLException e) {
            logger.warn("No se pudieron listar las tablas de {}: {}", ruta, e.getMessage());
        }
        return tablas;
    }

    /** Última fila (minuto más reciente) de la máquina, en el mes actual o el anterior. */
    public Fila ultima(String maquina) {
        YearMonth hoy = YearMonth.now();
        for (YearMonth m : List.of(hoy, hoy.minusMonths(1))) {
            // Dentro de un mismo mes "dd-MM-yyyy HH:mm:ss" ordena bien como texto.
            List<Fila> filas = consultar(m, maquina, "ORDER BY fecha DESC LIMIT 1");
            if (!filas.isEmpty()) {
                return filas.get(0);
            }
        }
        return null;
    }

    /** Filas de la máquina entre dos momentos (inclusive), recorriendo los meses necesarios. */
    public List<Fila> filas(String maquina, LocalDateTime desde, LocalDateTime hasta) {
        List<Fila> resultado = new ArrayList<>();
        for (YearMonth m = YearMonth.from(desde); !m.isAfter(YearMonth.from(hasta)); m = m.plusMonths(1)) {
            for (Fila f : consultar(m, maquina, "")) {
                if (!f.fecha().isBefore(desde) && !f.fecha().isAfter(hasta)) {
                    resultado.add(f);
                }
            }
        }
        resultado.sort((a, b) -> a.fecha().compareTo(b.fecha()));
        return resultado;
    }

    private List<Fila> consultar(YearMonth mes, String maquina, String sufijo) {
        List<Fila> filas = new ArrayList<>();
        String ruta = CalidadEnergiaAlmacen.rutaArchivo(mes);
        if (!new File(ruta).exists() || !tablas(mes).contains(maquina)) {
            return filas;
        }
        try (Connection c = abrir(ruta);
             ResultSet r = c.createStatement().executeQuery("SELECT * FROM \"" + maquina + "\" " + sufijo)) {
            ResultSetMetaData md = r.getMetaData();
            while (r.next()) {
                Map<String, Double> valores = new HashMap<>();
                boolean dI = false;
                boolean dV = false;
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String col = md.getColumnName(i).toUpperCase();
                    if (col.equals("FECHA")) continue;
                    Object o = r.getObject(i);
                    if (o == null) continue;
                    double v = ((Number) o).doubleValue();
                    if (col.equals(CalidadEnergiaAlmacen.COL_DESB_I_CALC)) dI = v != 0;
                    else if (col.equals(CalidadEnergiaAlmacen.COL_DESB_V_CALC)) dV = v != 0;
                    else valores.put(col, v);
                }
                filas.add(new Fila(LocalDateTime.parse(r.getString("fecha"), FECHA), valores, dI, dV));
            }
        } catch (Exception e) {
            logger.warn("Error leyendo calidad de {} en {}: {}", maquina, ruta, e.getMessage());
        }
        return filas;
    }

    private static Connection abrir(String ruta) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:file:" + ruta.replace('\\', '/') + "?mode=ro");
    }

    // ================= Resúmenes =================

    /** Promedios de 10 minutos de cada columna (solo con los minutos que tienen dato). */
    public List<Bloque> bloques10(List<Fila> filas) {
        TreeMap<LocalDateTime, Map<String, double[]>> acum = new TreeMap<>();
        for (Fila f : filas) {
            LocalDateTime t = f.fecha().truncatedTo(ChronoUnit.HOURS)
                    .plusMinutes((f.fecha().getMinute() / MINUTOS_BLOQUE) * MINUTOS_BLOQUE);
            Map<String, double[]> b = acum.computeIfAbsent(t, k -> new HashMap<>());
            for (Map.Entry<String, Double> e : f.valores().entrySet()) {
                double[] s = b.computeIfAbsent(e.getKey(), k -> new double[2]);
                s[0] += e.getValue();
                s[1]++;
            }
        }
        List<Bloque> bloques = new ArrayList<>();
        acum.forEach((t, b) -> {
            Map<String, Double> prom = new HashMap<>();
            b.forEach((col, s) -> prom.put(col, s[0] / s[1]));
            bloques.add(new Bloque(t, prom));
        });
        return bloques;
    }

    /** Cumplimiento por indicador en el período: % de bloques de 10 min sin estar FUERA y peor valor. */
    public Map<Indicador, Cumplimiento> cumplimiento(String maquina, List<Fila> filas) {
        ConfiguracionCalidad cfg = configuracion();
        double nominal = tensionNominal(maquina);
        List<Bloque> bloques = bloques10(filas);
        Map<Indicador, Cumplimiento> resultado = new EnumMap<>(Indicador.class);
        for (Indicador ind : Indicador.values()) {
            int n = 0;
            int dentro = 0;
            Double peor = null;
            double peorDesvio = -1;
            for (Bloque b : bloques) {
                Double v = ind.valor(b.promedios());
                if (v == null) continue;
                n++;
                if (evaluar(ind, v, nominal, cfg) != Estado.FUERA) dentro++;
                double desvio = switch (ind) {
                    case FACTOR_POTENCIA -> -v;                                          // peor = el más bajo
                    case TENSION -> Math.abs(v - nominal);                               // peor = el más alejado
                    case FRECUENCIA -> Math.abs(v - cfg.getFrecuenciaNominal());
                    default -> v;                                                        // peor = el más alto
                };
                if (desvio > peorDesvio || peor == null) {
                    peorDesvio = desvio;
                    peor = v;
                }
            }
            resultado.put(ind, new Cumplimiento(n, n == 0 ? 0 : dentro * 100.0 / n, peor,
                    evaluar(ind, peor, nominal, cfg)));
        }
        return resultado;
    }
}
