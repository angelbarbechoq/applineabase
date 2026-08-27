package com.example.mantenimiento.service;

import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.horometro.repository.HorometroDiarioRepository;
import com.example.mantenimiento.model.EquipoTag;
import com.example.mantenimiento.model.ItemTag;
import com.example.mantenimiento.model.LineaTag;
import com.example.mantenimiento.model.MantenimientoRealizado;
import com.example.mantenimiento.model.PlanMantenimiento;
import com.example.mantenimiento.repository.MantenimientoRealizadoRepository;
import com.example.mantenimiento.repository.PlanMantenimientoRepository;
import com.example.security.LineaAccessService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class MantenimientoService {

    private final PlanMantenimientoRepository planRepository;
    private final MantenimientoRealizadoRepository realizadoRepository;
    private final HorometroDiarioRepository horometroDiarioRepository;
    private final ConfigLoaderService configLoaderService;
    private final LineaAccessService lineaAccessService;

    public MantenimientoService(PlanMantenimientoRepository planRepository,
                                 MantenimientoRealizadoRepository realizadoRepository,
                                 HorometroDiarioRepository horometroDiarioRepository,
                                 ConfigLoaderService configLoaderService,
                                 LineaAccessService lineaAccessService) {
        this.planRepository = planRepository;
        this.realizadoRepository = realizadoRepository;
        this.horometroDiarioRepository = horometroDiarioRepository;
        this.configLoaderService = configLoaderService;
        this.lineaAccessService = lineaAccessService;
    }

    public List<PlanMantenimiento> listarPlanes() {
        return planRepository.findAllByOrderByTagAsc();
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

    public void eliminar(PlanMantenimiento plan) {
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
