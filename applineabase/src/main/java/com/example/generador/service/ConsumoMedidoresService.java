package com.example.generador.service;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Con qué máquinas trabajó el generador en un período en marcha: cuáles estaban trabajando y cuáles
 * paradas entre el inicio y el fin del arranque.
 *
 * "Parada (en cero)" se decide con TODAS las lecturas del intervalo, nunca con un promedio ni una
 * lectura suelta: el contador de kWh no subió y ninguna lectura de potencia fue distinta de cero. Así
 * una carga intermitente (compresores de aire que paran y arrancan) aparece como "trabajando a ratos"
 * con el porcentaje del tiempo con carga, no como parada.
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
     * Potencia media mientras hubo potencia (del contador de kWh, que está en kWh en todos los
     * medidores; la columna PW no: algunos la guardan en W) por debajo de la cual solo hay consumo
     * mínimo: el medidor no está en cero (ej. Mixer01 parado con ~50 W de un tablero auxiliar) pero la
     * máquina no trabajó.
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
        CONSUMIO("Trabajando"), INTERMITENTE("Trabajando a ratos"), CONSUMO_MINIMO("Parada, solo consumo minimo"),
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
     * @param dentroDe       medidor que lo contiene (submedidor) o null; los submedidores no se suman
     *                       en el balance del transformador (ya están en el de arriba)
     * @param kwh            aumento del contador en los intervalos (null = sin lecturas de energía)
     * @param lecturas       lecturas de potencia en los intervalos
     * @param lecturasConCarga lecturas con potencia distinta de cero
     */
    public record ConsumoMedidor(String medidor, String zona, String dentroDe, Estado estado, Double kwh, Double kwMedio,
                                 int lecturas, int lecturasConCarga) {
        public Double porcentajeConCarga() {
            return lecturas == 0 ? null : lecturasConCarga * 100.0 / lecturas;
        }
    }

    private final TopologiaMedidores topologia;

    public ConsumoMedidoresService(TopologiaMedidores topologia) {
        this.topologia = topologia;
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

    List<ConsumoMedidor> calcular(List<Ventana> ventanas, String transformador) {
        List<TopologiaMedidores.Medidor> maquinas = topologia.medidores().stream()
                .filter(m -> m.tipo() == TipoMedidor.MAQUINA && !SENSORES.contains(m.nombre())
                        && PLCDataQueryService.esNombreMaquinaValido(m.nombre())
                        && (transformador == null || transformador.equals(m.transformador())))
                .toList();
        double horas = 0;
        for (Ventana v : ventanas) horas += Duration.between(v.desde(), v.hasta()).toSeconds() / 3600.0;

        List<ConsumoMedidor> lista = new ArrayList<>();
        for (TopologiaMedidores.Medidor maquina : maquinas) {
            String m = maquina.nombre();
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
            // Potencia media mientras tuvo potencia (no repartida en todo el intervalo): una línea que
            // trabajó 2 h de 8 a 30 kW no es "consumo mínimo" aunque su media de las 8 h sea baja.
            Double kwConPotencia = kwh == null || horas <= 0 ? null
                    : lecturas > 0 && conCarga > 0 ? kwh / (horas * conCarga / lecturas) : kwMedio;
            Estado estado;
            if (kwh == null && lecturas == 0) estado = Estado.SIN_DATOS;
            else if ((kwh == null || kwh <= 0) && conCarga == 0) estado = Estado.EN_CERO;
            else if (kwConPotencia != null && kwConPotencia < KW_MEDIO_MINIMO) estado = Estado.CONSUMO_MINIMO;
            else if (lecturas > 0 && conCarga * 100.0 / lecturas < PORCENTAJE_CONTINUO) estado = Estado.INTERMITENTE;
            else estado = Estado.CONSUMIO;
            lista.add(new ConsumoMedidor(m, maquina.zona(), maquina.dentroDe(), estado, kwh, kwMedio, lecturas, conCarga));
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
