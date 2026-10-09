package com.example.generador.service;

import com.example.alarmas.model.AlarmaConfig;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaConfigRepository;
import com.example.dataacquisition.MaquinasVirtuales;
import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.medidores.TipoMedidor;
import com.example.medidores.service.TopologiaMedidores;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Con qué máquinas trabajó el generador en un período en marcha: cuáles estaban trabajando, cuáles
 * paradas consumiendo en espera y cuáles en cero, entre el inicio y el fin del arranque.
 *
 * "Trabajando" se decide con el umbral de encendido de cada máquina, el mismo del horómetro y de la
 * advertencia de detención (Horómetro > Ajustar umbrales): por debajo del umbral la máquina está
 * parada aunque consuma (ej. Linea05 con ~1,7 kW de su transformador de aislamiento). Esa energía
 * de una máquina parada es desperdicio: la entrega igual el generador o la red.
 *
 * "Parada (en cero)" se decide con TODAS las lecturas del intervalo, nunca con un promedio ni una
 * lectura suelta: el contador de kWh no subió y ninguna lectura de potencia fue distinta de cero.
 */
@Service
public class ConsumoMedidoresService {

    private static final Logger logger = LoggerFactory.getLogger(ConsumoMedidoresService.class);

    /** Sensores que se guardan como "línea" pero no miden energía (mismo criterio que AlarmaConfigSeeder). */
    private static final Set<String> SENSORES = Set.of(MaquinasVirtuales.TEMPERATURA_AMBIENTE, MaquinasVirtuales.TEMPERATURA_AGUA,
            "PsiAireP1", "PsiAgua", "BarCompHP");
    /** Trabajando (por encima del umbral) menos de este % del tiempo con lectura = a ratos. */
    private static final double PORCENTAJE_CONTINUO = 95.0;
    /** Mismo valor por defecto que el horómetro cuando la máquina no tiene umbral configurado. */
    public static final double UMBRAL_DEFECTO = 15.0;
    /** Salto máximo creíble del contador entre dos lecturas seguidas (kWh); más es basura de lectura. */
    private static final double SALTO_MAXIMO_KWH = 20000;
    private static final DateTimeFormatter CLAVE = DateTimeFormatter.ofPattern("yyyyMMddHH:mm:ss");
    /** Fecha "dd-MM-yyyy HH:mm:ss" pasada a una clave que se ordena como texto. */
    private static final String SQL_CLAVE = "substr(fecha,7,4)||substr(fecha,4,2)||substr(fecha,1,2)||substr(fecha,12)";

    public record Ventana(LocalDateTime desde, LocalDateTime hasta) {
    }

    public enum Estado {
        CONSUMIO("Trabajando"), INTERMITENTE("Trabajando a ratos"), EN_ESPERA("Parada, consumo en espera"),
        EN_CERO("Parada (en cero)"), SIN_DATOS("Sin datos");

        private final String etiqueta;

        Estado(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        public String etiqueta() {
            return etiqueta;
        }
    }

    /**
     * @param dentroDe        medidor que lo contiene (submedidor) o null; los submedidores no se suman
     *                        en el balance del transformador (ya están en el de arriba)
     * @param umbral          umbral de encendido de la máquina (en la misma unidad que su potencia guardada)
     * @param kwh             aumento del contador en los intervalos (null = sin lecturas de energía)
     * @param kwhEnEspera     parte de kwh consumida con la máquina parada (por debajo del umbral): desperdicio
     * @param lecturas        lecturas de potencia en los intervalos
     * @param lecturasTrabajando lecturas por encima del umbral
     */
    public record ConsumoMedidor(String medidor, String zona, String dentroDe, Estado estado, double umbral,
                                 Double kwh, Double kwhEnEspera, Double kwMedio, int lecturas, int lecturasTrabajando) {
        public Double porcentajeTrabajando() {
            return lecturas == 0 ? null : lecturasTrabajando * 100.0 / lecturas;
        }
    }

    private final TopologiaMedidores topologia;
    private final AlarmaConfigRepository alarmaConfigRepository;

    public ConsumoMedidoresService(TopologiaMedidores topologia, AlarmaConfigRepository alarmaConfigRepository) {
        this.topologia = topologia;
        this.alarmaConfigRepository = alarmaConfigRepository;
    }

    /**
     * Estado de cada máquina de un transformador entre {@code desde} y {@code hasta} (un período en
     * marcha). Solo máquinas: no entran el transformador, el medidor general, los sensores ni los
     * marcados "no se cuenta" (MotorL3, MotorL4).
     *
     * @param transformador null = las máquinas de todos los transformadores
     */
    public List<ConsumoMedidor> calcular(LocalDateTime desde, LocalDateTime hasta, String transformador) {
        return calcular(List.of(new Ventana(desde, hasta)), transformador);
    }

    /** Suma de las máquinas que van en el balance (sin submedidores), o null si ninguna tiene dato. */
    public static Double sumaBalance(List<ConsumoMedidor> lista) {
        Double total = null;
        for (ConsumoMedidor c : lista) {
            if (c.dentroDe() == null && c.kwh() != null) total = (total == null ? 0 : total) + c.kwh();
        }
        return total;
    }

    /** Desperdicio: energía de máquinas paradas (por debajo de su umbral), sin contar submedidores dos veces. */
    public static double sumaEnEspera(List<ConsumoMedidor> lista) {
        double total = 0;
        for (ConsumoMedidor c : lista) {
            if (c.dentroDe() == null && c.kwhEnEspera() != null) total += c.kwhEnEspera();
        }
        return total;
    }

    /** Umbral de encendido por máquina: el de DETENCION (o CICLO_COMPRESOR), como el horómetro. */
    private Map<String, Double> umbrales() {
        Map<String, Double> mapa = new HashMap<>();
        for (AlarmaConfig c : alarmaConfigRepository.findAll()) {
            if (c.getUmbralMinimoKw() == null) continue;
            if (c.getTipoAlarma() == TipoAlarma.DETENCION) mapa.put(c.getLineaMaquina(), c.getUmbralMinimoKw());
            else if (c.getTipoAlarma() == TipoAlarma.CICLO_COMPRESOR) mapa.putIfAbsent(c.getLineaMaquina(), c.getUmbralMinimoKw());
        }
        return mapa;
    }

    List<ConsumoMedidor> calcular(List<Ventana> ventanas, String transformador) {
        List<TopologiaMedidores.Medidor> maquinas = topologia.medidores().stream()
                .filter(m -> m.tipo() == TipoMedidor.MAQUINA && !SENSORES.contains(m.nombre())
                        && PLCDataQueryService.esNombreMaquinaValido(m.nombre())
                        && (transformador == null || transformador.equals(m.transformador())))
                .toList();
        Map<String, Double> umbrales = umbrales();
        double horas = 0;
        for (Ventana v : ventanas) horas += Duration.between(v.desde(), v.hasta()).toSeconds() / 3600.0;

        List<ConsumoMedidor> lista = new ArrayList<>();
        for (TopologiaMedidores.Medidor maquina : maquinas) {
            String m = maquina.nombre();
            double umbral = umbrales.getOrDefault(m, UMBRAL_DEFECTO);
            Double kwh = null;
            int lecturas = 0, conPotencia = 0, trabajando = 0;
            double potenciaEspera = 0, potenciaTotal = 0;
            for (YearMonth mes : meses(ventanas)) {
                // Cada archivo mensual se limita a su propio mes: el de septiembre repite las
                // primeras horas del 1 de octubre y se contarían dos veces.
                List<Ventana> delMes = recortar(ventanas, mes);
                if (delMes.isEmpty()) continue;
                Double k = kwhEnVentanas(RutaArchivosEnergia.construirRutaMensual(mes.getYear(), mes.getMonthValue(), false), m, delMes);
                if (k != null) kwh = (kwh == null ? 0 : kwh) + k;
                double[] p = potenciaEnVentanas(RutaArchivosEnergia.construirRutaMensual(mes.getYear(), mes.getMonthValue(), true), m, delMes, umbral);
                lecturas += (int) p[0];
                conPotencia += (int) p[1];
                trabajando += (int) p[2];
                potenciaEspera += p[3];
                potenciaTotal += p[4];
            }
            Double kwMedio = kwh == null || horas <= 0 ? null : kwh / horas;
            Estado estado;
            if (kwh == null && lecturas == 0) estado = Estado.SIN_DATOS;
            else if ((kwh == null || kwh <= 0) && conPotencia == 0) estado = Estado.EN_CERO;
            else if (trabajando == 0) estado = Estado.EN_ESPERA;
            else if (lecturas > 0 && trabajando * 100.0 / lecturas < PORCENTAJE_CONTINUO) estado = Estado.INTERMITENTE;
            else estado = Estado.CONSUMIO;
            // Energía en espera: la del contador repartida según la potencia de los minutos parados (la
            // proporción no depende de si el medidor guarda la potencia en kW o en W).
            Double kwhEnEspera = kwh == null ? null
                    : estado == Estado.EN_ESPERA ? kwh
                    : potenciaTotal > 0 ? kwh * potenciaEspera / potenciaTotal : 0.0;
            lista.add(new ConsumoMedidor(m, maquina.zona(), maquina.dentroDe(), estado, umbral, kwh, kwhEnEspera, kwMedio,
                    lecturas, trabajando));
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
     * [lecturas, lecturas con potencia distinta de cero, lecturas por encima del umbral (trabajando),
     * suma de potencia de las lecturas paradas con consumo (0 &lt; PW &lt; umbral), suma de potencia positiva].
     * Se compara la potencia guardada contra el umbral tal cual, igual que el horómetro.
     */
    private double[] potenciaEnVentanas(String ruta, String maquina, List<Ventana> ventanas, double umbral) {
        if (!new File(ruta).exists()) return new double[5];
        String sql = "SELECT count(*), sum(CASE WHEN PW <> 0 THEN 1 ELSE 0 END), sum(CASE WHEN PW >= ? THEN 1 ELSE 0 END), "
                + "sum(CASE WHEN PW > 0 AND PW < ? THEN PW ELSE 0 END), sum(CASE WHEN PW > 0 THEN PW ELSE 0 END) FROM ("
                + " SELECT PW, " + casoVentana(ventanas) + " AS w FROM ("
                + "  SELECT PW, " + SQL_CLAVE + " AS k FROM \"" + maquina + "\")"
                + ") WHERE w IS NOT NULL";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta.replace('\\', '/') + "?mode=ro");
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, umbral);
            ps.setDouble(2, umbral);
            ponerVentanas(ps, ventanas, 3);
            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? new double[]{r.getInt(1), r.getInt(2), r.getInt(3), r.getDouble(4), r.getDouble(5)} : new double[5];
            }
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("no such table")) {
                logger.warn("Consumo por medidor: no se pudo leer PW de {} en {}: {}", maquina, ruta, e.getMessage());
            }
            return new double[5];
        }
    }
}
