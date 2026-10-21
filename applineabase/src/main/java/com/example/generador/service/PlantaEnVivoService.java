package com.example.generador.service;

import com.example.alarmas.model.AlarmaConfig;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaConfigRepository;
import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.generador.model.Generador;
import com.example.generador.model.LecturaGenerador;
import com.example.horometro.service.HorometroService;
import com.example.medidores.TipoMedidor;
import com.example.medidores.service.TopologiaMedidores;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * La planta en este momento: cuánto consume, cuánto de ese consumo pone cada generador y cuánto viene de la red.
 *
 * Por cada generador: su potencia leída en vivo del controlador (SOLO LECTURA, función 03). La red es el medidor
 * principal (KWhPlanta1, transformadores 1 y 2 juntos; último minuto guardado del PLC, + importa / - exporta). Los
 * medidores de los transformadores no se usan: con los generadores en marcha no coinciden con el principal (hasta
 * ~100 kW de diferencia) y confundían. Lo que cada generador pone en la planta = lo que genera menos su parte de lo
 * exportado; consumo de la planta = generadores + importada (100 %), y lo exportado va aparte.
 */
@Service
public class PlantaEnVivoService {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);
    /** Lectura del medidor más vieja que esto = no se usa (el PLC guarda una por minuto). */
    public static final long RED_VIGENTE_SEGUNDOS = 180;

    public enum EstadoGenerador {
        EN_MARCHA("En marcha"), PARADO("Parado"), SIN_COMUNICACION("Sin comunicacion");

        private final String etiqueta;

        EstadoGenerador(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        public String etiqueta() {
            return etiqueta;
        }
    }

    /**
     * Un generador ahora (red = nombre del transformador al que está conectado, solo para mostrarlo).
     *
     * @param kwGenerador lo que genera (0 parado; null sin comunicación)
     */
    public record FilaGenerador(String generador, String red, EstadoGenerador estado, Double kwGenerador, String error) {
    }

    /**
     * Una máquina ahora: trabajando o parada con el mismo criterio que el horómetro (umbral y lecturas seguidas
     * bajo el umbral). trabajando null = sin dato.
     */
    public record EstadoMaquina(String grupo, String nombre, Boolean trabajando, Double kw) {
    }

    /**
     * Consumo de la planta antes del arranque de los generadores: promedio del medidor principal en los
     * {@link #MINUTOS_ANTES} minutos previos. enCurso = del arranque en curso; si no hay ninguno, del último.
     */
    public record AntesDelArranque(LocalDateTime inicio, boolean enCurso, boolean inicioEstimado, Double kw) {
    }

    /**
     * Medidor principal (KWhPlanta1, el punto que factura la empresa eléctrica): kw + toma de la red, - entrega
     * (null sin lectura reciente). Ve la suma de los transformadores: lo que sale por uno y entra por el otro no pasa por él.
     */
    public record MedidorPrincipal(Double kw, LocalDateTime fecha) {
    }

    /**
     * La planta ahora. Importada y exportada salen del medidor principal (toda la planta 1); cada generador pone en la
     * planta lo que genera menos su parte de lo exportado (repartido según lo que genera cada uno). Sin lectura reciente
     * del principal (conPrincipal() = false) no hay consumo ni red: la pantalla lo avisa. Solo cuentan los generadores
     * con lectura (los demás se avisan).
     */
    public record Planta(LocalDateTime leido, List<FilaGenerador> filas, AntesDelArranque antes, List<EstadoMaquina> maquinas,
                         MedidorPrincipal principal) {

        public boolean conPrincipal() {
            return principal != null && principal.kw() != null;
        }

        /** Si el generador entra en el total (tiene lectura del controlador). */
        public boolean contado(FilaGenerador f) {
            return f.kwGenerador() != null;
        }

        private List<FilaGenerador> contadas() {
            return filas.stream().filter(this::contado).toList();
        }

        public boolean completa() {
            return filas.stream().allMatch(this::contado);
        }

        public double consumo() {
            return consumidaDeGeneradores() + importada();
        }

        public double generada() {
            return contadas().stream().mapToDouble(FilaGenerador::kwGenerador).sum();
        }

        public double consumidaDeGeneradores() {
            return Math.max(0, generada() - exportada());
        }

        public double importada() {
            return conPrincipal() ? Math.max(0, principal.kw()) : 0;
        }

        public double exportada() {
            return conPrincipal() ? Math.max(0, -principal.kw()) : 0;
        }

        /** Lo que pone un generador en la planta: lo que genera menos su parte de lo exportado; null si no cuenta. */
        public Double aporte(FilaGenerador f) {
            if (!contado(f)) return null;
            double g = generada();
            return g <= 0 ? 0.0 : Math.max(0, f.kwGenerador() - exportada() * f.kwGenerador() / g);
        }

        /** Parte del consumo de la planta (%), o null si no hay consumo. */
        public Double porcentaje(double kw) {
            double c = consumo();
            return c <= 0 ? null : kw / c * 100;
        }
    }

    /** Minutos antes del arranque que se promedian para el consumo previo. */
    public static final int MINUTOS_ANTES = 10;
    /** Máquina con submedidores que se muestra neta (sin ellos): Inyección menos OrientadoraL2, GA752 y HornoL3. */
    public static final String INYECCION = "Inyeccion";
    public static final String GRUPO_EXTRUSION = "Extrusion", GRUPO_MEZCLA = "Mezcla", GRUPO_INYECCION = "Inyeccion";

    private final GeneradorService generadorService;
    private final GeneradorAnalisisService analisis;
    private final HorometroService horometro;
    private final TopologiaMedidores topologia;
    private final AlarmaConfigRepository alarmaConfigRepository;
    private final PLCDataQueryService plcDataQueryService;
    /** Consumo previo ya calculado por inicio de arranque (no cambia; evita leer el archivo del mes cada 15 s). */
    private final Map<LocalDateTime, Double> antesCalculado = new ConcurrentHashMap<>();

    public PlantaEnVivoService(GeneradorService generadorService, GeneradorAnalisisService analisis, HorometroService horometro,
                               TopologiaMedidores topologia, AlarmaConfigRepository alarmaConfigRepository,
                               PLCDataQueryService plcDataQueryService) {
        this.generadorService = generadorService;
        this.analisis = analisis;
        this.horometro = horometro;
        this.topologia = topologia;
        this.alarmaConfigRepository = alarmaConfigRepository;
        this.plcDataQueryService = plcDataQueryService;
    }

    public Planta leer() {
        LocalDateTime ahora = LocalDateTime.now().withNano(0);
        List<FilaGenerador> filas = new ArrayList<>();
        for (Generador g : generadorService.generadores()) {
            EstadoGenerador estado;
            Double kw;
            String error = null;
            try {
                LecturaGenerador l = generadorService.leerEnVivo(g);
                boolean marcha = l.enMarcha();
                estado = marcha ? EstadoGenerador.EN_MARCHA : EstadoGenerador.PARADO;
                kw = marcha ? (l.kw() == null ? null : Math.max(0, l.kw())) : 0.0;
                if (marcha && l.kw() == null) error = "el controlador no dio la potencia";
            } catch (Exception e) {
                estado = EstadoGenerador.SIN_COMUNICACION;
                kw = null;
                error = e.getMessage();
            }
            filas.add(new FilaGenerador(g.nombre(), g.redAsociada(), estado, kw, error));
        }
        return new Planta(ahora, filas, antesDelArranque(), maquinas(), principal(ahora));
    }

    /** Última lectura del medidor principal; el PLC guarda su potencia en W. */
    private MedidorPrincipal principal(LocalDateTime ahora) {
        try {
            Map<String, Object> m = plcDataQueryService.getLatestVIPDataByMaquina(GeneradorAnalisisService.PRINCIPAL);
            LocalDateTime f = fecha(m.get("fecha"));
            if (m.containsKey("error") || f == null || !(m.get("PW") instanceof Number pw)) return new MedidorPrincipal(null, null);
            return new MedidorPrincipal(f.isBefore(ahora.minusSeconds(RED_VIGENTE_SEGUNDOS)) ? null
                    : pw.doubleValue() * GeneradorAnalisisService.KW_POR_UNIDAD_PRINCIPAL, f);
        } catch (Exception e) {
            return new MedidorPrincipal(null, null);
        }
    }

    /** Arranque de referencia: el más viejo de los que siguen en marcha; si no hay ninguno, el último. */
    private AntesDelArranque antesDelArranque() {
        GeneradorService.Arranque enCurso = null, ultimo = null;
        for (Generador g : generadorService.generadores()) {
            for (GeneradorService.Arranque a : generadorService.arranques(g)) {
                if (a.fin() == null && (enCurso == null || a.inicio().isBefore(enCurso.inicio()))) enCurso = a;
                if (ultimo == null || a.inicio().isAfter(ultimo.inicio())) ultimo = a;
            }
        }
        GeneradorService.Arranque ref = enCurso != null ? enCurso : ultimo;
        if (ref == null) return null;
        LocalDateTime inicio = ref.inicio();
        Double kw = antesCalculado.get(inicio);
        if (kw == null) {
            kw = analisis.potenciaMediaPrincipal(inicio.minusMinutes(MINUTOS_ANTES), inicio);
            // Solo se guarda si se pudo calcular: si faltaban lecturas, se vuelve a intentar en la próxima lectura.
            if (kw != null) antesCalculado.put(inicio, kw);
        }
        return new AntesDelArranque(inicio, enCurso != null, ref.inicioEstimado(), kw);
    }

    /**
     * Líneas de extrusión y máquinas de mezcla (zona de linea-id-config.json) con su estado del horómetro, e
     * Inyección neta: su medidor menos los submedidores que tiene adentro, contra el umbral de Inyección.
     */
    private List<EstadoMaquina> maquinas() {
        List<EstadoMaquina> lista = new ArrayList<>();
        List<TopologiaMedidores.Medidor> medidores = topologia.medidores();
        for (TopologiaMedidores.Medidor m : medidores) {
            if (m.tipo() != TipoMedidor.MAQUINA || m.zona() == null) continue;
            String grupo = m.zona().startsWith("Extrusi") ? GRUPO_EXTRUSION : m.zona().startsWith("Mezcla") ? GRUPO_MEZCLA : null;
            if (grupo == null) continue;
            Double kw = horometro.getUltimoPw(m.nombre());
            lista.add(new EstadoMaquina(grupo, m.nombre(), kw == null ? null : horometro.estaEncendida(m.nombre()), kw));
        }
        if (medidores.stream().anyMatch(m -> INYECCION.equals(m.nombre()))) {
            Double neto = horometro.getUltimoPw(INYECCION);
            for (TopologiaMedidores.Medidor sub : medidores) {
                if (!INYECCION.equals(sub.dentroDe()) || neto == null) continue;
                Double kwSub = horometro.getUltimoPw(sub.nombre());
                neto = kwSub == null ? null : neto - kwSub;
            }
            double umbral = alarmaConfigRepository.findByLineaMaquinaAndTipoAlarma(INYECCION, TipoAlarma.DETENCION)
                    .map(AlarmaConfig::getUmbralMinimoKw).filter(Objects::nonNull).orElse(ConsumoMedidoresService.UMBRAL_DEFECTO);
            lista.add(new EstadoMaquina(GRUPO_INYECCION, "Inyeccion (neta)", neto == null ? null : neto >= umbral,
                    neto == null ? null : Math.max(0, neto)));
        }
        lista.sort(Comparator.comparing(EstadoMaquina::nombre));
        return lista;
    }

    /** Submedidores que se restan de Inyección (para explicarlo en pantalla). */
    public List<String> submedidoresInyeccion() {
        return topologia.medidores().stream().filter(m -> INYECCION.equals(m.dentroDe())).map(TopologiaMedidores.Medidor::nombre).sorted().toList();
    }

    private static LocalDateTime fecha(Object o) {
        try {
            return o == null ? null : LocalDateTime.parse(String.valueOf(o), FECHA);
        } catch (Exception e) {
            return null;
        }
    }
}
