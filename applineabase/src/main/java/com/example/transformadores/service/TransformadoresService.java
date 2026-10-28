package com.example.transformadores.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.generador.model.Generador;
import com.example.generador.model.LecturaGenerador;
import com.example.generador.model.ModeloControlador;
import com.example.generador.model.SenalesRed;
import com.example.generador.service.GeneradorAnalisisService;
import com.example.generador.service.GeneradorService;
import com.example.generador.service.RedControladorService;
import com.example.medidores.service.TopologiaMedidores;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * La planta 1 en este momento, medidor por medidor, para el diagrama unifilar de Transformadores (pedido del usuario el
 * 2026-10-27): el medidor principal (KWhPlanta1, + importa / - exporta), el medidor de cada transformador (+ baja de media
 * a baja tensión / - sale hacia media tensión), los generadores conectados en la baja tensión de cada uno (leídos en vivo
 * del controlador, SOLO LECTURA) y la suma de las máquinas con medidor de cada transformador.
 *
 * Los medidores se muestran tal como miden, sin ajustar: los de los transformadores no cuadran con el principal (ver la
 * revisión del 2026-10-20 en el contexto del proyecto) y la pantalla deja la diferencia a la vista.
 */
@Service
public class TransformadoresService {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);
    /** Lectura del medidor más vieja que esto = sin dato (se guarda una por minuto). */
    public static final long VIGENTE_SEGUNDOS = 180;
    /** Modelos que el PLC guarda con la potencia en W (el resto, en kW): ION8600 (principal) y PAC1020 (TDGeneradorSA). */
    private static final Set<String> MODELOS_PLC_EN_W = Set.of("ION8600", "PAC1020");
    /** Modelos que el PLC guarda con las tensiones fase-neutro (ver faseNeutroPlc). */
    private static final Set<String> MODELOS_PLC_FASE_NEUTRO = Set.of("ION8600");
    /** Estados del motor ComAp que son falla (parado por falla o no puede arrancar), como en el tablero de Generadores. */
    private static final Set<String> FALLA_MOTOR = Set.of("Shutdown", "NotReady", "SDVentil");

    public enum EstadoGenerador { EN_MARCHA, PARADO, SIN_COMUNICACION }

    /** Un medidor ahora: kw null = sin lectura reciente; pf tal como lo guarda el medidor (el principal, x100); amperes y
     * voltios = promedio de las tres fases (voltios entre fases; solo informativo). */
    public record Medicion(Double kw, Double pf, Double amperes, Double voltios, LocalDateTime fecha) {
        static final Medicion SIN_DATO = new Medicion(null, null, null, null, null);
    }

    /**
     * Un generador ahora (kw 0 parado, null sin comunicación). listo: parado y listo para arrancar (sin falla del motor
     * y listo para AMF si el modelo lo publica). alarmas: cuántas tiene la lista de alarmas del controlador. redPresente:
     * si el controlador ve tensión de red y no avisa falla de red (null = no lo sabe). voltios: tensión entre fases media del
     * generador en marcha (solo informativo). mcbAbierto: interruptor de la planta abierto según el controlador (MCB
     * Feedback, o un estado sin red si el modelo no tiene esa entrada; null = no lo sabe). isla: además con el generador
     * alimentando su parte de la planta; islaDesde, la hora en que empezó (de las lecturas de fondo); selectorIsla, el
     * selector del controlador en Isla (isla a propósito).
     */
    public record GeneradorAhora(String nombre, EstadoGenerador estado, Double kw, Double pf, String error, boolean listo,
                                 int alarmas, Boolean redPresente, Double voltios, Boolean mcbAbierto, boolean isla,
                                 LocalDateTime islaDesde, boolean selectorIsla) {
    }

    /**
     * Un transformador ahora: su medidor, sus generadores y la suma de las máquinas con medidor que cuelgan de él
     * (sin los submedidores); maquinasConDato de maquinas tuvieron lectura reciente.
     */
    public record TransformadorAhora(String nombre, Medicion medidor, List<GeneradorAhora> generadores, double kwMaquinas,
                                     int maquinasConDato, int maquinas) {
    }

    public record Foto(LocalDateTime leido, Medicion principal, List<TransformadorAhora> transformadores) {
    }

    private final TopologiaMedidores topologia;
    private final GeneradorService generadorService;
    private final PLCDataQueryService plcDataQueryService;
    private final ConfigLoaderService configLoaderService;
    private final RedControladorService redControlador;

    public TransformadoresService(TopologiaMedidores topologia, GeneradorService generadorService,
                                  PLCDataQueryService plcDataQueryService, ConfigLoaderService configLoaderService,
                                  RedControladorService redControlador) {
        this.redControlador = redControlador;
        this.topologia = topologia;
        this.generadorService = generadorService;
        this.plcDataQueryService = plcDataQueryService;
        this.configLoaderService = configLoaderService;
    }

    public Foto leer() {
        LocalDateTime ahora = LocalDateTime.now().withNano(0);
        List<TopologiaMedidores.Medidor> medidores = topologia.medidores();
        List<String> transformadores = topologia.transformadores().stream().sorted().toList();

        Set<String> aLeer = new LinkedHashSet<>();
        aLeer.add(GeneradorAnalisisService.PRINCIPAL);
        aLeer.addAll(transformadores);
        for (TopologiaMedidores.Medidor m : medidores) {
            if (m.sumaEnBalance() && transformadores.contains(m.transformador())) aLeer.add(m.nombre());
        }
        Map<String, Map<String, Object>> ultimas = plcDataQueryService.getLatestVIPData(aLeer);
        Map<String, Double> kwPorUnidad = kwPorUnidad();
        Set<String> faseNeutro = faseNeutroPlc();

        Map<String, List<GeneradorAhora>> generadores = new HashMap<>();
        for (Generador g : generadorService.generadores()) {
            if (g.redAsociada() == null) continue;
            generadores.computeIfAbsent(g.redAsociada(), k -> new ArrayList<>()).add(leerGenerador(g));
        }

        List<TransformadorAhora> lista = new ArrayList<>();
        for (String t : transformadores) {
            double suma = 0;
            int conDato = 0, total = 0;
            for (TopologiaMedidores.Medidor m : medidores) {
                if (!m.sumaEnBalance() || !t.equals(m.transformador())) continue;
                total++;
                Medicion med = medicion(ultimas.get(m.nombre()), kwPorUnidad.getOrDefault(m.nombre(), 1.0), faseNeutro.contains(m.nombre()), ahora);
                if (med.kw() == null) continue;
                conDato++;
                suma += med.kw();
            }
            lista.add(new TransformadorAhora(t, medicion(ultimas.get(t), kwPorUnidad.getOrDefault(t, 1.0), faseNeutro.contains(t), ahora),
                    generadores.getOrDefault(t, List.of()).stream().sorted(Comparator.comparing(GeneradorAhora::nombre)).toList(),
                    suma, conDato, total));
        }
        return new Foto(ahora, medicion(ultimas.get(GeneradorAnalisisService.PRINCIPAL), GeneradorAnalisisService.KW_POR_UNIDAD_PRINCIPAL,
                faseNeutro.contains(GeneradorAnalisisService.PRINCIPAL), ahora), lista);
    }

    private GeneradorAhora leerGenerador(Generador g) {
        LecturaGenerador l;
        try {
            l = generadorService.leerEnVivo(g);
        } catch (Exception e) {
            return new GeneradorAhora(g.nombre(), EstadoGenerador.SIN_COMUNICACION, null, null, e.getMessage(), false, 0, null, null,
                    null, false, null, false);
        }
        ModeloControlador m = generadorService.modelo(g).orElse(null);
        int alarmas = l.alarmas() == null ? 0 : l.alarmas().size();
        Boolean red = redPresente(m, l);
        Boolean mcbAbierto = mcbAbierto(m, l);
        RedControladorService.EstadoActual fondo = redControlador.estadoActual(g.nombre());
        boolean isla = Boolean.TRUE.equals(mcbAbierto) && l.enMarcha() && l.kw() != null && l.kw() >= GeneradorAnalisisService.KW_VACIO;
        LocalDateTime islaDesde = isla && fondo != null && fondo.isla() ? fondo.desde() : null;
        if (!l.enMarcha()) {
            // Mismo criterio que el tablero de Generadores: estado del motor en falla, o no listo para AMF = no listo.
            boolean listo = m == null || (!FALLA_MOTOR.contains(m.estadoMotor(l)) && !Boolean.FALSE.equals(m.listoAmf(l)));
            return new GeneradorAhora(g.nombre(), EstadoGenerador.PARADO, 0.0, null, null, listo, alarmas, red, null, mcbAbierto, false, null, false);
        }
        return new GeneradorAhora(g.nombre(), EstadoGenerador.EN_MARCHA, l.kw() == null ? null : Math.max(0, l.kw()), l.pf(),
                l.kw() == null ? "el controlador no dio la potencia" : null, true, alarmas, red, promedio(l.vL1L2(), l.vL2L3(), l.vL3L1()),
                mcbAbierto, isla, islaDesde, m != null && m.red() != null && Boolean.TRUE.equals(SenalesRed.valor(m.red().forzarIsla(), l)));
    }

    /** Promedio de las tres fases, o null si falta alguna o están en cero. */
    private static Double promedio(Object a, Object b, Object c) {
        if (!(a instanceof Number x) || !(b instanceof Number y) || !(c instanceof Number z)) return null;
        double s = x.doubleValue() + y.doubleValue() + z.doubleValue();
        return s <= 0 ? null : s / 3;
    }

    /**
     * Si el controlador ve la red: con tensión de red (más de 100 V entre fases) y sin falla de red según el estado de los
     * interruptores o su aviso de falla de red (mismo criterio que la torre del tablero de Generadores). null = no lo sabe.
     */
    private static Boolean redPresente(ModeloControlador m, LecturaGenerador l) {
        SenalesRed red = m == null ? null : m.red();
        if (red != null) {
            Integer codigo = SenalesRed.codigo(l);
            if ((codigo != null && red.estado(codigo) == SenalesRed.Estado.FALLA_RED)
                    || Boolean.TRUE.equals(SenalesRed.valor(red.avisoFallaRed(), l))) return false;
        }
        Double v = l.redVL1L2();
        return v == null ? null : v > 100;
    }

    /**
     * Interruptor de la planta abierto según el controlador: su entrada MCB Feedback y, si el modelo no la tiene, un estado
     * de los interruptores sin red (falla, isla o retorno de red). null = el modelo no lo publica.
     */
    private static Boolean mcbAbierto(ModeloControlador m, LecturaGenerador l) {
        SenalesRed red = m == null ? null : m.red();
        if (red == null) return null;
        Boolean mcb = SenalesRed.valor(red.mcbCerrado(), l);
        if (mcb != null) return !mcb;
        Integer codigo = SenalesRed.codigo(l);
        return codigo == null ? null : SenalesRed.sinRed(red.estado(codigo));
    }

    /** kW por unidad guardada de cada medidor: 0,001 los que el PLC guarda en W, 1 el resto. */
    private Map<String, Double> kwPorUnidad() {
        Map<String, Double> m = new HashMap<>();
        for (Map<String, Object> l : configLoaderService.loadLineaIDConfig()) {
            String plc = String.valueOf(l.get("nombrePLC"));
            if (plc.startsWith("PLC") && MODELOS_PLC_EN_W.contains(String.valueOf(l.get("modeloMedidor")))) {
                m.put(String.valueOf(l.get("lineaMaquina")), 0.001);
            }
        }
        return m;
    }

    /**
     * Medidores cuyas tensiones el PLC guarda fase-neutro (el resto, fase-fase): el ION8600 del principal leído por PLC
     * (docs/PLAN-CALIDAD-ENERGIA.md, fase B: "el PLC: Vln"). Comprobado el 2026-10-27: guarda ~12,5 kV y con P, FP e I del
     * mismo minuto la tensión entre fases da ~21,6 kV.
     */
    private Set<String> faseNeutroPlc() {
        Set<String> s = new java.util.HashSet<>();
        for (Map<String, Object> l : configLoaderService.loadLineaIDConfig()) {
            String plc = String.valueOf(l.get("nombrePLC"));
            if (plc.startsWith("PLC") && MODELOS_PLC_FASE_NEUTRO.contains(String.valueOf(l.get("modeloMedidor")))) {
                s.add(String.valueOf(l.get("lineaMaquina")));
            }
        }
        return s;
    }

    /** faseNeutro: la tensión guardada es fase-neutro y se pasa a fase-fase (x raíz de 3), como la de los demás medidores. */
    private static Medicion medicion(Map<String, Object> fila, double kwPorUnidad, boolean faseNeutro, LocalDateTime ahora) {
        if (fila == null) return Medicion.SIN_DATO;
        LocalDateTime f;
        try {
            f = LocalDateTime.parse(String.valueOf(fila.get("fecha")), FECHA);
        } catch (Exception e) {
            return Medicion.SIN_DATO;
        }
        if (f.isBefore(ahora.minusSeconds(VIGENTE_SEGUNDOS)) || !(fila.get("PW") instanceof Number pw)) return Medicion.SIN_DATO;
        Double v = promedio(fila.get("VAB"), fila.get("VAC"), fila.get("VBC"));
        return new Medicion(pw.doubleValue() * kwPorUnidad, fila.get("PF") instanceof Number pf ? pf.doubleValue() : null,
                promedio(fila.get("IA"), fila.get("IB"), fila.get("IC")), v != null && faseNeutro ? v * Math.sqrt(3) : v, f);
    }
}
