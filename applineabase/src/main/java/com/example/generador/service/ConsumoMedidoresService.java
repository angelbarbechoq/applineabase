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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Con qué máquinas trabajó el generador en un período en marcha: cuáles estuvieron encendidas, cuáles
 * apagadas consumiendo en espera y cuáles en cero, entre el inicio y el fin del arranque.
 *
 * Encendida o apagada, sin porcentajes de "carga": la carga de una línea depende del producto. Se usa
 * el criterio del horómetro y de la advertencia de detención (Horómetro > Ajustar umbrales): la
 * máquina está apagada cuando pasa un tiempo seguido por debajo de su umbral de encendido (5 lecturas
 * para las líneas, como el horómetro). Los compresores paran y arrancan solos en su ciclo normal (los
 * datos de octubre muestran paradas de 10 a 30 minutos), así que para ellos "apagado" es más de
 * {@link #MINUTOS_COMPRESOR_APAGADO} minutos seguidos por debajo del umbral; una parada del ciclo es
 * parte de su trabajo.
 *
 * Lo que consume una máquina apagada es desperdicio (ej. Linea05 con ~1,7 kW de su transformador de
 * aislamiento): lo entrega igual el generador o la red. Los transformadores y el medidor general no
 * están en esta lista: bajan su carga porque el generador está en marcha, no es desperdicio.
 *
 * "Apagada (en cero)" se decide con TODAS las lecturas del intervalo, nunca con un promedio ni una
 * lectura suelta: el contador de kWh no subió y ninguna lectura de potencia fue distinta de cero.
 */
@Service
public class ConsumoMedidoresService {

    private static final Logger logger = LoggerFactory.getLogger(ConsumoMedidoresService.class);

    /** Sensores que se guardan como "línea" pero no miden energía (mismo criterio que AlarmaConfigSeeder). */
    private static final Set<String> SENSORES = Set.of(MaquinasVirtuales.TEMPERATURA_AMBIENTE, MaquinasVirtuales.TEMPERATURA_AGUA,
            "PsiAireP1", "PsiAgua", "BarCompHP");
    /** Mismo valor por defecto que el horómetro cuando la máquina no tiene umbral configurado. */
    public static final double UMBRAL_DEFECTO = 15.0;
    /** Lecturas seguidas bajo el umbral que confirman apagado: el valor por defecto del horómetro (DETENCION). */
    public static final int VENTANA_DEFECTO = 5;
    /** Compresores (CICLO_COMPRESOR): minutos seguidos bajo el umbral para darlos por apagados y no en su ciclo. */
    public static final int MINUTOS_COMPRESOR_APAGADO = 60;
    /** Salto máximo creíble del contador entre dos lecturas seguidas (kWh); más es basura de lectura. */
    private static final double SALTO_MAXIMO_KWH = 20000;
    private static final DateTimeFormatter CLAVE = DateTimeFormatter.ofPattern("yyyyMMddHH:mm:ss");
    /** Fecha "dd-MM-yyyy HH:mm:ss" pasada a una clave que se ordena como texto. */
    private static final String SQL_CLAVE = "substr(fecha,7,4)||substr(fecha,4,2)||substr(fecha,1,2)||substr(fecha,12)";

    public record Ventana(LocalDateTime desde, LocalDateTime hasta) {
    }

    public enum Estado {
        ENCENDIDA("Encendida"), ENCENDIDA_Y_APAGADA("Encendida y apagada"), APAGADA_CONSUMO("Apagada, consumo en espera"),
        APAGADA_CERO("Apagada (en cero)"), SIN_DATOS("Sin datos");

        private final String etiqueta;

        Estado(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        public String etiqueta() {
            return etiqueta;
        }
    }

    /**
     * @param dentroDe             medidor que lo contiene (submedidor) o null; los submedidores no se suman
     *                             en el balance del transformador (ya están en el de arriba)
     * @param umbral               umbral de encendido de la máquina (en la misma unidad que su potencia guardada)
     * @param lecturasConfirmacion lecturas seguidas bajo el umbral (una por minuto) para darla por apagada
     * @param kwh                  aumento del contador en los intervalos (null = sin lecturas de energía)
     * @param kwhEnEspera          parte de kwh consumida con la máquina apagada: desperdicio
     * @param horasEncendida       tiempo del período con la máquina encendida
     * @param horasApagada         tiempo del período con la máquina apagada
     */
    public record ConsumoMedidor(String medidor, String zona, String dentroDe, Estado estado, double umbral,
                                 int lecturasConfirmacion, Double kwh, Double kwhEnEspera, Double kwMedio,
                                 double horasEncendida, double horasApagada) {
    }

    private record Criterio(double umbral, int lecturasConfirmacion) {
    }

    private record Lectura(String clave, double pw) {
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

    /** Desperdicio: energía de máquinas apagadas, sin contar submedidores dos veces. */
    public static double sumaEnEspera(List<ConsumoMedidor> lista) {
        double total = 0;
        for (ConsumoMedidor c : lista) {
            if (c.dentroDe() == null && c.kwhEnEspera() != null) total += c.kwhEnEspera();
        }
        return total;
    }

    /**
     * Umbral y confirmación de apagado por máquina: los de DETENCION (como el horómetro) o, si solo
     * tiene CICLO_COMPRESOR, su umbral con {@link #MINUTOS_COMPRESOR_APAGADO}.
     */
    private Map<String, Criterio> criterios() {
        Map<String, Criterio> mapa = new HashMap<>();
        for (AlarmaConfig c : alarmaConfigRepository.findAll()) {
            if (c.getUmbralMinimoKw() == null) continue;
            if (c.getTipoAlarma() == TipoAlarma.DETENCION) {
                int ventana = c.getVentanaCiclos() != null && c.getVentanaCiclos() > 0 ? c.getVentanaCiclos() : VENTANA_DEFECTO;
                mapa.put(c.getLineaMaquina(), new Criterio(c.getUmbralMinimoKw(), ventana));
            } else if (c.getTipoAlarma() == TipoAlarma.CICLO_COMPRESOR) {
                mapa.putIfAbsent(c.getLineaMaquina(), new Criterio(c.getUmbralMinimoKw(), MINUTOS_COMPRESOR_APAGADO));
            }
        }
        return mapa;
    }

    List<ConsumoMedidor> calcular(List<Ventana> ventanas, String transformador) {
        List<TopologiaMedidores.Medidor> maquinas = topologia.medidores().stream()
                .filter(m -> m.tipo() == TipoMedidor.MAQUINA && !SENSORES.contains(m.nombre())
                        && PLCDataQueryService.esNombreMaquinaValido(m.nombre())
                        && (transformador == null || transformador.equals(m.transformador())))
                .toList();
        Map<String, Criterio> criterios = criterios();
        double horas = 0;
        for (Ventana v : ventanas) horas += Duration.between(v.desde(), v.hasta()).toSeconds() / 3600.0;

        List<ConsumoMedidor> lista = new ArrayList<>();
        for (TopologiaMedidores.Medidor maquina : maquinas) {
            String m = maquina.nombre();
            Criterio criterio = criterios.getOrDefault(m, new Criterio(UMBRAL_DEFECTO, VENTANA_DEFECTO));
            Double kwh = null;
            for (YearMonth mes : meses(ventanas)) {
                // Cada archivo mensual se limita a su propio mes: el de septiembre repite las
                // primeras horas del 1 de octubre y se contarían dos veces.
                List<Ventana> delMes = recortar(ventanas, mes);
                if (delMes.isEmpty()) continue;
                Double k = kwhEnVentanas(RutaArchivosEnergia.construirRutaMensual(mes.getYear(), mes.getMonthValue(), false), m, delMes);
                if (k != null) kwh = (kwh == null ? 0 : kwh) + k;
            }

            int lecturas = 0, conPotencia = 0, encendida = 0, apagada = 0;
            double potenciaApagada = 0, potenciaTotal = 0;
            for (Ventana v : ventanas) {
                // Se leen unos minutos antes y después del período para saber si una parada que lo
                // cruza ya venía de antes o sigue después (si no, se cortaría y no llegaría a confirmarse).
                long margen = criterio.lecturasConfirmacion() + 1L;
                List<Lectura> l = lecturasPotencia(m, v.desde().minusMinutes(margen), v.hasta().plusMinutes(margen));
                boolean[] apagadas = marcarApagadas(l, criterio.umbral(), criterio.lecturasConfirmacion());
                String desde = v.desde().format(CLAVE), hasta = v.hasta().format(CLAVE);
                for (int i = 0; i < l.size(); i++) {
                    String k = l.get(i).clave();
                    if (k.compareTo(desde) < 0 || k.compareTo(hasta) > 0) continue;
                    double pw = l.get(i).pw();
                    lecturas++;
                    if (pw != 0) conPotencia++;
                    double positiva = Math.max(pw, 0);
                    potenciaTotal += positiva;
                    if (apagadas[i]) {
                        apagada++;
                        potenciaApagada += positiva;
                    } else {
                        encendida++;
                    }
                }
            }

            Estado estado;
            if (lecturas == 0) estado = Estado.SIN_DATOS;
            else if (encendida == 0) estado = (kwh == null || kwh <= 0) && conPotencia == 0 ? Estado.APAGADA_CERO : Estado.APAGADA_CONSUMO;
            else if (apagada == 0) estado = Estado.ENCENDIDA;
            else estado = Estado.ENCENDIDA_Y_APAGADA;
            // Energía en espera: la del contador repartida según la potencia de los minutos apagada (la
            // proporción no depende de si el medidor guarda la potencia en kW o en W).
            Double kwhEnEspera;
            if (kwh == null || estado == Estado.SIN_DATOS) kwhEnEspera = null;
            else if (estado == Estado.APAGADA_CONSUMO || estado == Estado.APAGADA_CERO) kwhEnEspera = kwh;
            else if (estado == Estado.ENCENDIDA) kwhEnEspera = 0.0;
            else kwhEnEspera = potenciaTotal > 0 ? kwh * potenciaApagada / potenciaTotal : 0.0;
            // El tiempo se reparte sobre la duración del período según las lecturas, así encendida +
            // apagada da el total aunque falte alguna lectura suelta.
            double horasEncendida = lecturas == 0 ? 0 : horas * encendida / lecturas;
            double horasApagada = lecturas == 0 ? 0 : horas - horasEncendida;
            Double kwMedio = kwh == null || horas <= 0 ? null : kwh / horas;
            lista.add(new ConsumoMedidor(m, maquina.zona(), maquina.dentroDe(), estado, criterio.umbral(),
                    criterio.lecturasConfirmacion(), kwh, kwhEnEspera, kwMedio, horasEncendida, horasApagada));
        }
        lista.sort(Comparator.comparing((ConsumoMedidor c) -> c.estado().ordinal())
                .thenComparing(c -> c.kwh() == null ? 0 : -c.kwh()));
        return lista;
    }

    /**
     * Marca las lecturas que forman parte de una racha de al menos {@code confirmacion} lecturas
     * seguidas por debajo del umbral (máquina apagada). Una racha más corta es una pausa: la máquina
     * sigue encendida (igual que el horómetro, que confirma el apagado retroactivo al inicio de la racha).
     */
    private static boolean[] marcarApagadas(List<Lectura> lecturas, double umbral, int confirmacion) {
        boolean[] apagadas = new boolean[lecturas.size()];
        int i = 0;
        while (i < lecturas.size()) {
            if (lecturas.get(i).pw() >= umbral) {
                i++;
                continue;
            }
            int j = i;
            while (j < lecturas.size() && lecturas.get(j).pw() < umbral) j++;
            if (j - i >= confirmacion) Arrays.fill(apagadas, i, j, true);
            i = j;
        }
        return apagadas;
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
     * Potencia (PW) de la máquina entre {@code desde} y {@code hasta}, en orden, de los archivos VIP
     * mensuales (cada uno limitado a su propio mes, por el solapamiento en el borde). Se compara contra
     * el umbral tal cual, igual que el horómetro.
     */
    private List<Lectura> lecturasPotencia(String maquina, LocalDateTime desde, LocalDateTime hasta) {
        List<Lectura> lecturas = new ArrayList<>();
        for (YearMonth mes = YearMonth.from(desde); !mes.isAfter(YearMonth.from(hasta)); mes = mes.plusMonths(1)) {
            LocalDateTime ini = mes.atDay(1).atStartOfDay();
            LocalDateTime fin = mes.atEndOfMonth().atTime(23, 59, 59);
            LocalDateTime a = desde.isBefore(ini) ? ini : desde;
            LocalDateTime b = hasta.isAfter(fin) ? fin : hasta;
            String ruta = RutaArchivosEnergia.construirRutaMensual(mes.getYear(), mes.getMonthValue(), true);
            if (b.isBefore(a) || !new File(ruta).exists()) continue;
            String sql = "SELECT k, PW FROM (SELECT " + SQL_CLAVE + " AS k, PW FROM \"" + maquina + "\")"
                    + " WHERE k BETWEEN ? AND ? AND PW IS NOT NULL ORDER BY k";
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta.replace('\\', '/') + "?mode=ro");
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, a.format(CLAVE));
                ps.setString(2, b.format(CLAVE));
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) lecturas.add(new Lectura(r.getString(1), r.getDouble(2)));
                }
            } catch (Exception e) {
                if (!String.valueOf(e.getMessage()).contains("no such table")) {
                    logger.warn("Consumo por medidor: no se pudo leer PW de {} en {}: {}", maquina, ruta, e.getMessage());
                }
            }
        }
        return lecturas;
    }
}
