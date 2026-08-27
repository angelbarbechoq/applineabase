package com.example.mantenimiento.service;

import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.horometro.model.HorometroDiario;
import com.example.horometro.model.HorometroTotal;
import com.example.horometro.repository.HorometroDiarioRepository;
import com.example.horometro.repository.HorometroTotalRepository;
import com.example.mantenimiento.model.EquipoTag;
import com.example.mantenimiento.model.EstadoPlanDTO;
import com.example.mantenimiento.model.ItemTag;
import com.example.mantenimiento.model.LineaTag;
import com.example.mantenimiento.model.MantenimientoRealizado;
import com.example.mantenimiento.model.PlanMantenimiento;
import com.example.mantenimiento.repository.MantenimientoRealizadoRepository;
import com.example.mantenimiento.repository.PlanMantenimientoRepository;
import com.example.security.LineaAccessService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MantenimientoService {

    private static final int DIAS_HISTORIAL_PARA_PROMEDIO = 30;

    private final PlanMantenimientoRepository planRepository;
    private final MantenimientoRealizadoRepository realizadoRepository;
    private final HorometroDiarioRepository horometroDiarioRepository;
    private final HorometroTotalRepository horometroTotalRepository;
    private final ConfigLoaderService configLoaderService;
    private final LineaAccessService lineaAccessService;

    public MantenimientoService(PlanMantenimientoRepository planRepository,
                                 MantenimientoRealizadoRepository realizadoRepository,
                                 HorometroDiarioRepository horometroDiarioRepository,
                                 HorometroTotalRepository horometroTotalRepository,
                                 ConfigLoaderService configLoaderService,
                                 LineaAccessService lineaAccessService) {
        this.planRepository = planRepository;
        this.realizadoRepository = realizadoRepository;
        this.horometroDiarioRepository = horometroDiarioRepository;
        this.horometroTotalRepository = horometroTotalRepository;
        this.configLoaderService = configLoaderService;
        this.lineaAccessService = lineaAccessService;
    }

    public List<PlanMantenimiento> listarPlanes() {
        return planRepository.findAllByOrderByTagAsc();
    }

    /** Estado calculado de cada plan (última vez realizado, horas transcurridas, próximo aviso
     * estimado), para la grilla de la vista de configuración. */
    public List<EstadoPlanDTO> listarEstadoPlanes() {
        return listarPlanes().stream().map(this::calcularEstado).toList();
    }

    private EstadoPlanDTO calcularEstado(PlanMantenimiento plan) {
        String lineaMaquina = resolverLineaMaquina(plan.getTag());
        double horasActuales = horometroTotalRepository.findById(lineaMaquina)
                .map(HorometroTotal::getHorasAcumuladas).orElse(0.0);

        Optional<MantenimientoRealizado> ultimo = realizadoRepository.findFirstByPlanMantenimientoOrderByFechaRealizadoDesc(plan);
        LocalDateTime ultimaFecha = ultimo.map(MantenimientoRealizado::getFechaRealizado).orElse(null);
        double horasBase = ultimo.map(MantenimientoRealizado::getHorasAcumuladasEnMomento).orElse(0.0);

        double horasTranscurridas = horasActuales - horasBase;
        double horasRestantes = plan.getIntervaloHoras() - horasTranscurridas;
        LocalDateTime proximoAviso = horasRestantes <= 0 ? null : estimarFechaProximoAviso(lineaMaquina, horasRestantes);

        return new EstadoPlanDTO(plan, ultimaFecha, horasTranscurridas, horasRestantes, proximoAviso);
    }

    /** Proyecta una fecha aproximada de vencimiento a partir del promedio de horas/día de los
     * últimos 30 días de HorometroDiario — no es exacta (la línea no corre a ritmo constante),
     * es una referencia. Devuelve null si no hay uso reciente registrado para proyectar. */
    private LocalDateTime estimarFechaProximoAviso(String lineaMaquina, double horasRestantes) {
        LocalDate hasta = LocalDate.now();
        LocalDate desde = hasta.minusDays(DIAS_HISTORIAL_PARA_PROMEDIO);
        List<HorometroDiario> historico = horometroDiarioRepository
                .findByLineaMaquinaAndFechaBetweenOrderByFecha(lineaMaquina, desde, hasta);
        if (historico.isEmpty()) {
            return null;
        }
        double promedioHorasPorDia = historico.stream().mapToDouble(HorometroDiario::getHoras).sum() / historico.size();
        if (promedioHorasPorDia <= 0) {
            return null;
        }
        double diasRestantes = horasRestantes / promedioHorasPorDia;
        return LocalDateTime.now().plusMinutes(Math.round(diasRestantes * 24 * 60));
    }

    public boolean existePlan(String tag, String tarea) {
        return planRepository.existsByTagAndTarea(tag, tarea);
    }

    /**
     * Crea un plan nuevo y, junto con él, el primer registro de MantenimientoRealizado a partir
     * de la fecha en que el usuario indica que se hizo la tarea por última vez (puede ser una
     * fecha pasada — el plan a veces se carga en el sistema después de haber hecho la tarea).
     * Las horas acumuladas de ese registro se reconstruyen con el histórico del horómetro
     * (HorometroDiario.sumHorasHastaFecha) tal como estaban al cierre de esa fecha, no con las
     * horas actuales — así "horas desde el último mantenimiento" se calcula bien desde el día 1.
     */
    public PlanMantenimiento crearPlan(PlanMantenimiento plan, LocalDateTime fechaUltimoMantenimiento) {
        PlanMantenimiento guardado = planRepository.save(plan);

        String lineaMaquina = resolverLineaMaquina(plan.getTag());
        double horasEnEsaFecha = horometroDiarioRepository.sumHorasHastaFecha(lineaMaquina, fechaUltimoMantenimiento.toLocalDate());

        MantenimientoRealizado registroInicial = new MantenimientoRealizado();
        registroInicial.setPlanMantenimiento(guardado);
        registroInicial.setFechaRealizado(fechaUltimoMantenimiento);
        registroInicial.setHorasAcumuladasEnMomento(horasEnEsaFecha);
        registroInicial.setUsuario(lineaAccessService.usuarioActual());
        registroInicial.setNotas("Registro inicial al crear el plan");
        realizadoRepository.save(registroInicial);

        return guardado;
    }

    public PlanMantenimiento guardar(PlanMantenimiento plan) {
        return planRepository.save(plan);
    }

    /** Borra primero el historial de MantenimientoRealizado del plan — sin esto, la FK
     * obligatoria hacia PlanMantenimiento rechaza el borrado del plan por integridad
     * referencial en cuanto tiene al menos un registro (siempre tiene uno: el que crearPlan
     * genera al dar de alta el plan). */
    public void eliminar(PlanMantenimiento plan) {
        realizadoRepository.deleteAll(realizadoRepository.findByPlanMantenimientoOrderByFechaRealizadoDesc(plan));
        planRepository.delete(plan);
    }

    /** Resuelve un TAG (equipo o ítem del catálogo de Extrusión) a su lineaMaquina real para
     * consultar el horómetro; si no está en el catálogo, el propio tag ya es el lineaMaquina
     * (máquinas de Mezcla, Casa Fuerza, etc. sin taxonomía todavía). */
    public String resolverLineaMaquina(String tag) {
        for (LineaTag linea : catalogoLineasExtrusion()) {
            for (EquipoTag equipo : linea.equipos()) {
                if (equipo.tag().equals(tag)) {
                    return linea.lineaMaquina();
                }
                for (ItemTag item : equipo.items()) {
                    if (item.tagExtendido().equals(tag)) {
                        return linea.lineaMaquina();
                    }
                }
            }
        }
        return tag;
    }

    /**
     * Catálogo tipado de líneas de Extrusión (Línea → Equipo → Ítem mantenible), para poblar
     * la selección en cascada de la vista de configuración. Se parsea desde el JSON crudo de
     * ConfigLoaderService en cada llamada — el catálogo es chico y se edita a mano de vez en
     * cuando, no vale la pena cachearlo en memoria.
     */
    @SuppressWarnings("unchecked")
    public List<LineaTag> catalogoLineasExtrusion() {
        return configLoaderService.loadExtrusionTagConfig().stream()
                .map(lineaMap -> {
                    List<Object> equiposRaw = (List<Object>) lineaMap.getOrDefault("equipos", List.of());
                    List<EquipoTag> equipos = equiposRaw.stream()
                            .map(o -> (Map<String, Object>) o)
                            .map(this::parsearEquipo)
                            .toList();
                    return new LineaTag((String) lineaMap.get("lineaMaquina"), (String) lineaMap.get("tagLinea"), equipos);
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private EquipoTag parsearEquipo(Map<String, Object> equipoMap) {
        List<Object> itemsRaw = (List<Object>) equipoMap.getOrDefault("items", List.of());
        List<ItemTag> items = itemsRaw.stream()
                .map(o -> (Map<String, Object>) o)
                .map(itemMap -> new ItemTag(
                        (String) itemMap.get("subunidad"),
                        (String) itemMap.get("item"),
                        (String) itemMap.get("codigoItem"),
                        (String) itemMap.get("posicion"),
                        (String) itemMap.get("tagExtendido")))
                .toList();
        return new EquipoTag(
                (String) equipoMap.get("codigo"),
                (String) equipoMap.get("nombre"),
                (String) equipoMap.get("salida"),
                (String) equipoMap.get("tag"),
                items);
    }
}
