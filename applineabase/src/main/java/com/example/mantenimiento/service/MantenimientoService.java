package com.example.mantenimiento.service;

import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.horometro.model.HorometroDiario;
import com.example.horometro.repository.HorometroDiarioRepository;
import com.example.horometro.service.HorometroBackfillRunner;
import com.example.horometro.service.HorometroService;
import com.example.mantenimiento.model.EquipoTag;
import com.example.mantenimiento.model.EstadoPlanDTO;
import com.example.mantenimiento.model.ItemTag;
import com.example.mantenimiento.model.LineaTag;
import com.example.mantenimiento.model.MantenimientoRealizado;
import com.example.mantenimiento.model.PlanMantenimiento;
import com.example.mantenimiento.model.TecnicoMantenimiento;
import com.example.mantenimiento.repository.MantenimientoRealizadoRepository;
import com.example.mantenimiento.repository.PlanMantenimientoRepository;
import com.example.mantenimiento.repository.TecnicoMantenimientoRepository;
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
    private final ConfigLoaderService configLoaderService;
    private final LineaAccessService lineaAccessService;
    private final TecnicoMantenimientoRepository tecnicoRepository;
    private final HorometroBackfillRunner horometroBackfillRunner;
    private final HorometroService horometroService;

    public MantenimientoService(PlanMantenimientoRepository planRepository,
                                 MantenimientoRealizadoRepository realizadoRepository,
                                 HorometroDiarioRepository horometroDiarioRepository,
                                 ConfigLoaderService configLoaderService,
                                 LineaAccessService lineaAccessService,
                                 TecnicoMantenimientoRepository tecnicoRepository,
                                 HorometroBackfillRunner horometroBackfillRunner,
                                 HorometroService horometroService) {
        this.planRepository = planRepository;
        this.realizadoRepository = realizadoRepository;
        this.horometroDiarioRepository = horometroDiarioRepository;
        this.configLoaderService = configLoaderService;
        this.lineaAccessService = lineaAccessService;
        this.tecnicoRepository = tecnicoRepository;
        this.horometroBackfillRunner = horometroBackfillRunner;
        this.horometroService = horometroService;
    }

    public List<String> listarTecnicos() {
        return tecnicoRepository.findAllByOrderByNombreAsc().stream().map(TecnicoMantenimiento::getNombre).toList();
    }

    public void agregarTecnico(String ci, String nombre, String especialidad) {
        if (nombre == null || nombre.isBlank()) {
            return;
        }
        if (ci != null && !ci.isBlank() && tecnicoRepository.existsByCi(ci.trim())) {
            return;
        }
        tecnicoRepository.save(new TecnicoMantenimiento(
                ci == null ? null : ci.trim(), nombre.trim(), especialidad));
    }

    public void eliminarTecnico(TecnicoMantenimiento tecnico) {
        tecnicoRepository.delete(tecnico);
    }

    public void guardarTecnico(TecnicoMantenimiento tecnico) {
        tecnicoRepository.save(tecnico);
    }

    public List<TecnicoMantenimiento> listarTecnicosCompleto() {
        return tecnicoRepository.findAllByOrderByNombreAsc();
    }

    /** Especialidades ya usadas en el catalogo de personal, para el combo desplegable de
     * Especialidad (sigue permitiendo texto libre para una especialidad nueva). */
    public List<String> listarEspecialidades() {
        return tecnicoRepository.findAllByOrderByNombreAsc().stream()
                .map(TecnicoMantenimiento::getEspecialidad)
                .filter(e -> e != null && !e.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
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
        double horasActuales = horometroService.obtenerSnapshot(lineaMaquina).horasTotal();

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

    /** Plan configurado para un TAG puntual (para la pantalla operativa, que elige el TAG y
     * resuelve solo cual plan/intervalo aplica). Si hay mas de un plan para el mismo TAG,
     * devuelve el primero -- hoy no se da ese caso en ningun TAG real del catalogo. */
    public Optional<PlanMantenimiento> planPorTag(String tag) {
        return planRepository.findByTag(tag).stream().findFirst();
    }

    /** Catalogo de tareas ya usadas (nombre nominal de algun plan, o tarea efectivamente
     * registrada en el historial), para sugerir en el combo de tarea sin dejar de permitir
     * texto libre para una tarea nueva. */
    public List<String> catalogoTareas() {
        return java.util.stream.Stream.concat(
                        planRepository.findAll().stream().map(PlanMantenimiento::getTarea),
                        realizadoRepository.findAll().stream().map(MantenimientoRealizado::getTareaRealizada))
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** Horas acumuladas del horometro de la linea del plan hasta el momento exacto (fecha y
     * hora) de la tarea, no solo hasta el cierre del dia -- para sugerir el valor de
     * "Horometro" en el formulario (el usuario puede corregirlo a mano si hace falta). */
    public double horasEnFecha(PlanMantenimiento plan, LocalDateTime momento) {
        return horometroBackfillRunner.horasHastaMomento(resolverLineaMaquina(plan.getTag()), momento);
    }

    /** Horas acumuladas actuales (ahora mismo) de la linea del plan, para calcular en vivo
     * "horas transcurridas" y "horas faltantes" de cada fila del historial. */
    public double horasActuales(PlanMantenimiento plan) {
        return horometroService.obtenerSnapshot(resolverLineaMaquina(plan.getTag())).horasTotal();
    }

    /** Historial completo de tareas ya ejecutadas, mas reciente primero, para la grilla de la
     * pantalla operativa. */
    public List<MantenimientoRealizado> listarHistorial() {
        return realizadoRepository.findAllByOrderByFechaRealizadoDesc();
    }

    /**
     * Crea un plan nuevo, sin ningún registro de MantenimientoRealizado todavía — cuándo se
     * hizo la tarea por última vez es un evento operativo, se carga aparte (vista de "marcar
     * mantenimiento realizado", a implementar), no en el formulario de la regla. Hasta que se
     * cargue ese primer registro, el estado del plan queda como "sin registro" (ver
     * EstadoPlanDTO.sinRegistro()).
     */
    public PlanMantenimiento crearPlan(PlanMantenimiento plan) {
        return planRepository.save(plan);
    }

    public PlanMantenimiento guardar(PlanMantenimiento plan) {
        return planRepository.save(plan);
    }

    /**
     * Registra que la tarea se realizo (log de tareas ya ejecutadas: no valida reglas de
     * negocio, solo registra lo que paso en planta). Crea siempre un registro NUEVO en el
     * historial (no edita el anterior) para conservar trazabilidad. El horometro lo confirma
     * o corrige el usuario en el formulario (sugerido con horasEnFecha, pero editable) en vez
     * de forzar el valor calculado automaticamente.
     *
     * tareaRealizada permite anotar que se hizo en concreto cuando difiere de la tarea nominal
     * del plan (ej. plan "Recalibracion de Barril y Tornillo" pero lo que se hizo fue un
     * "Cambio"); si viene vacio, se usa la tarea del plan tal cual. El contador del plan se
     * reinicia igual en ambos casos porque es el mismo registro el que define horasBase.
     */
    public void registrarMantenimientoRealizado(PlanMantenimiento plan, LocalDateTime fechaRealizado,
                                                 String tareaRealizada, double horasAcumuladasEnMomento,
                                                 String numeroOt, String tecnico, String notas) {
        MantenimientoRealizado registro = new MantenimientoRealizado();
        registro.setPlanMantenimiento(plan);
        registro.setFechaRealizado(fechaRealizado);
        registro.setHorasAcumuladasEnMomento(horasAcumuladasEnMomento);
        registro.setUsuario(lineaAccessService.usuarioActual());
        registro.setTareaRealizada(tareaRealizada == null || tareaRealizada.isBlank() ? plan.getTarea() : tareaRealizada);
        registro.setNumeroOt(numeroOt);
        registro.setTecnico(tecnico);
        registro.setNotas(notas);
        realizadoRepository.save(registro);
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
