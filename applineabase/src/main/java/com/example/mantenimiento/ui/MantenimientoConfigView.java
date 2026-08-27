package com.example.mantenimiento.ui;

import com.example.base.ui.MainLayout;
import com.example.base.ui.NotificacionesUtil;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.mantenimiento.model.EquipoTag;
import com.example.mantenimiento.model.EstadoPlanDTO;
import com.example.mantenimiento.model.ItemTag;
import com.example.mantenimiento.model.LineaTag;
import com.example.mantenimiento.model.PlanMantenimiento;
import com.example.mantenimiento.service.MantenimientoService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Alta y edición de planes de mantenimiento preventivo por horas. El TAG no se elige de una
 * lista plana: se arma solo a partir de una selección en cascada Línea/Máquina → Equipo →
 * Ítem mantenible (opcional, para quedarse a nivel de equipo completo). Las líneas de Extrusión
 * con catálogo ISO 14224 habilitan los combos de Equipo/Ítem; el resto de las máquinas
 * (Mezcla, Casa Fuerza, etc., sin taxonomía todavía) usan directamente su nombre como TAG.
 * Solo el ADMIN gestiona los planes — el dashboard de estado (a implementar) sí es visible
 * para más gente vía LineaAccessService.puedeVerMantenimientoPreventivo.
 */
@PageTitle("Configuración de Mantenimiento | LineaBase")
@Route(value = "mantenimiento/config", layout = MainLayout.class)
@RolesAllowed("ADMIN")
public class MantenimientoConfigView extends VerticalLayout {

    private static final String SIN_EQUIPO = "(todo el equipo)";
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final MantenimientoService mantenimientoService;
    private final List<LineaTag> catalogo;
    private final Grid<EstadoPlanDTO> grid = new Grid<>(EstadoPlanDTO.class, false);

    private final Span formTitle = new Span("Selecciona un plan para editarlo, o crea uno nuevo");
    private final ComboBox<String> lineaMaquinaCombo = new ComboBox<>("Línea / Máquina");
    private final ComboBox<EquipoTag> equipoCombo = new ComboBox<>("Equipo");
    private final ComboBox<ItemTag> itemCombo = new ComboBox<>("Elemento mantenible");
    private final Span tagResultante = new Span();
    private final TextField tareaField = new TextField("Tarea");
    private final NumberField intervaloField = new NumberField("Intervalo (horas)");
    private final NumberField avisoAnticipadoField = new NumberField("Aviso anticipado (horas antes)");
    private final DateTimePicker fechaUltimoMantenimientoField = new DateTimePicker("Última vez que se hizo esta tarea");
    private final Checkbox habilitadoCheckbox = new Checkbox("Habilitado", true);
    private final Button guardarBtn = new Button("Guardar");
    private final Button eliminarBtn = new Button("Eliminar");

    private PlanMantenimiento planEnEdicion;

    public MantenimientoConfigView(MantenimientoService mantenimientoService, ConfigLoaderService configLoaderService) {
        this.mantenimientoService = mantenimientoService;
        this.catalogo = mantenimientoService.catalogoLineasExtrusion();

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Configuración de Mantenimiento Preventivo"));

        lineaMaquinaCombo.setItems(configLoaderService.listarNombresLinea());
        lineaMaquinaCombo.setAllowCustomValue(true);
        lineaMaquinaCombo.setWidth("220px");
        lineaMaquinaCombo.addValueChangeListener(e -> onLineaCambiada(e.getValue()));

        equipoCombo.setItemLabelGenerator(EquipoTag::etiqueta);
        equipoCombo.setWidth("220px");
        equipoCombo.setClearButtonVisible(true);
        equipoCombo.addValueChangeListener(e -> onEquipoCambiado(e.getValue()));

        itemCombo.setItemLabelGenerator(ItemTag::etiqueta);
        itemCombo.setWidth("240px");
        itemCombo.setClearButtonVisible(true);
        itemCombo.setPlaceholder(SIN_EQUIPO);
        itemCombo.addValueChangeListener(e -> actualizarTagResultante());

        tagResultante.getStyle().set("font-weight", "600");

        tareaField.setWidth("220px");
        intervaloField.setWidth("170px");
        intervaloField.setStep(1);
        intervaloField.setMin(0);
        avisoAnticipadoField.setWidth("200px");
        avisoAnticipadoField.setStep(1);
        avisoAnticipadoField.setMin(0);

        fechaUltimoMantenimientoField.setWidth("260px");
        fechaUltimoMantenimientoField.setHelperText("Puede ser una fecha pasada, si la tarea ya se hizo y recién ahora se carga el plan");
        fechaUltimoMantenimientoField.setMax(LocalDateTime.now());

        grid.addColumn(e -> e.plan().getTag()).setHeader("TAG").setAutoWidth(true).setSortable(true);
        grid.addColumn(e -> e.plan().getTarea()).setHeader("Tarea").setAutoWidth(true).setSortable(true);
        grid.addColumn(e -> e.plan().getIntervaloHoras()).setHeader("Intervalo (h)").setAutoWidth(true);
        grid.addColumn(this::formatearFecha).setHeader("Última vez realizado").setAutoWidth(true).setSortable(true);
        grid.addColumn(e -> String.format("%.1f", e.horasTranscurridas())).setHeader("Horas transcurridas").setAutoWidth(true);
        grid.addColumn(this::formatearEstadoProximoAviso).setHeader("Próximo aviso").setAutoWidth(true);
        grid.addColumn(e -> e.plan().isHabilitado() ? "Sí" : "No").setHeader("Habilitado").setAutoWidth(true);
        grid.asSingleSelect().addValueChangeListener(e -> cargarEnFormulario(e.getValue() == null ? null : e.getValue().plan()));
        grid.setSizeFull();

        guardarBtn.addClickListener(e -> guardar());
        guardarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button nuevoBtn = new Button("Nuevo plan", e -> limpiarFormulario());
        nuevoBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        eliminarBtn.addClickListener(e -> eliminar());
        eliminarBtn.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        eliminarBtn.setEnabled(false);

        HorizontalLayout selectorLayout = new HorizontalLayout(lineaMaquinaCombo, equipoCombo, itemCombo, tagResultante);
        selectorLayout.setAlignItems(Alignment.END);
        selectorLayout.getStyle().set("flex-wrap", "wrap");

        HorizontalLayout formLayout = new HorizontalLayout(
                tareaField, intervaloField, avisoAnticipadoField, fechaUltimoMantenimientoField, habilitadoCheckbox,
                guardarBtn, nuevoBtn, eliminarBtn
        );
        formLayout.setAlignItems(Alignment.END);
        formLayout.getStyle().set("flex-wrap", "wrap");

        add(formTitle, selectorLayout, formLayout, grid);
        setFlexGrow(1, grid);

        limpiarFormulario();
        refrescarGrid();
    }

    private Optional<LineaTag> lineaDelCatalogo(String lineaMaquina) {
        return catalogo.stream().filter(l -> l.lineaMaquina().equals(lineaMaquina)).findFirst();
    }

    private void onLineaCambiada(String lineaMaquina) {
        equipoCombo.clear();
        itemCombo.clear();
        Optional<LineaTag> linea = lineaMaquina == null ? Optional.empty() : lineaDelCatalogo(lineaMaquina);
        equipoCombo.setItems(linea.map(LineaTag::equipos).orElse(List.of()));
        equipoCombo.setVisible(linea.isPresent());
        itemCombo.setVisible(false);
        actualizarTagResultante();
    }

    private void onEquipoCambiado(EquipoTag equipo) {
        itemCombo.clear();
        itemCombo.setItems(equipo == null ? List.of() : equipo.items());
        itemCombo.setVisible(equipo != null);
        actualizarTagResultante();
    }

    private void actualizarTagResultante() {
        tagResultante.setText("TAG: " + tagActual().orElse("—"));
    }

    private Optional<String> tagActual() {
        if (itemCombo.getValue() != null) {
            return Optional.of(itemCombo.getValue().tagExtendido());
        }
        if (equipoCombo.getValue() != null) {
            return Optional.of(equipoCombo.getValue().tag());
        }
        return Optional.ofNullable(lineaMaquinaCombo.getValue());
    }

    private void cargarEnFormulario(PlanMantenimiento plan) {
        planEnEdicion = plan;
        if (plan == null) {
            limpiarFormulario();
            return;
        }
        formTitle.setText("Editando: " + plan.getTag() + " / " + plan.getTarea());
        seleccionarCascadaPorTag(plan.getTag());
        lineaMaquinaCombo.setEnabled(false);
        equipoCombo.setEnabled(false);
        itemCombo.setEnabled(false);
        tareaField.setValue(plan.getTarea());
        tareaField.setEnabled(false);
        intervaloField.setValue(plan.getIntervaloHoras());
        avisoAnticipadoField.setValue(plan.getHorasAvisoAnticipado());
        fechaUltimoMantenimientoField.setVisible(false);
        habilitadoCheckbox.setValue(plan.isHabilitado());
        eliminarBtn.setEnabled(true);
    }

    /** Reconstruye la selección de los 3 combos a partir de un TAG ya guardado, para poder
     * editar un plan existente sin que el usuario tenga que rearmar la cascada a mano. */
    private void seleccionarCascadaPorTag(String tag) {
        for (LineaTag linea : catalogo) {
            for (EquipoTag equipo : linea.equipos()) {
                for (ItemTag item : equipo.items()) {
                    if (item.tagExtendido().equals(tag)) {
                        lineaMaquinaCombo.setValue(linea.lineaMaquina());
                        equipoCombo.setValue(equipo);
                        itemCombo.setValue(item);
                        return;
                    }
                }
                if (equipo.tag().equals(tag)) {
                    lineaMaquinaCombo.setValue(linea.lineaMaquina());
                    equipoCombo.setValue(equipo);
                    return;
                }
            }
        }
        // No está en el catálogo de Extrusión: es una máquina plana (Mezcla, Casa Fuerza, etc.).
        lineaMaquinaCombo.setValue(tag);
    }

    private void limpiarFormulario() {
        planEnEdicion = null;
        formTitle.setText("Nuevo plan");
        grid.asSingleSelect().clear();
        lineaMaquinaCombo.clear();
        lineaMaquinaCombo.setEnabled(true);
        equipoCombo.clear();
        equipoCombo.setEnabled(true);
        equipoCombo.setVisible(false);
        itemCombo.clear();
        itemCombo.setEnabled(true);
        itemCombo.setVisible(false);
        actualizarTagResultante();
        tareaField.clear();
        tareaField.setEnabled(true);
        intervaloField.clear();
        avisoAnticipadoField.clear();
        fechaUltimoMantenimientoField.setValue(LocalDateTime.now());
        fechaUltimoMantenimientoField.setVisible(true);
        habilitadoCheckbox.setValue(true);
        eliminarBtn.setEnabled(false);
    }

    private void guardar() {
        boolean esNuevo = planEnEdicion == null;
        PlanMantenimiento plan = planEnEdicion;

        if (esNuevo) {
            String tag = tagActual().orElse(null);
            String tarea = tareaField.getValue();
            if (tag == null || tag.isBlank() || tarea == null || tarea.isBlank()) {
                NotificacionesUtil.mostrarError("Selecciona una línea/máquina e ingresa la tarea");
                return;
            }
            if (mantenimientoService.existePlan(tag, tarea)) {
                NotificacionesUtil.mostrarError("Ya existe un plan de \"" + tarea + "\" para " + tag + "; selecciónalo en la tabla para editarlo");
                return;
            }
            plan = new PlanMantenimiento();
            plan.setTag(tag);
            plan.setTarea(tarea);
        }

        if (intervaloField.getValue() == null || intervaloField.getValue() <= 0) {
            NotificacionesUtil.mostrarError("El intervalo de horas debe ser mayor a 0");
            return;
        }
        if (esNuevo && fechaUltimoMantenimientoField.getValue() == null) {
            NotificacionesUtil.mostrarError("Indica cuándo se hizo esta tarea por última vez");
            return;
        }

        plan.setIntervaloHoras(intervaloField.getValue());
        plan.setHorasAvisoAnticipado(avisoAnticipadoField.getValue());
        plan.setHabilitado(habilitadoCheckbox.getValue());

        if (esNuevo) {
            mantenimientoService.crearPlan(plan, fechaUltimoMantenimientoField.getValue());
        } else {
            mantenimientoService.guardar(plan);
        }
        Notification.show("Plan guardado", 2500, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);

        limpiarFormulario();
        refrescarGrid();
    }

    private void eliminar() {
        if (planEnEdicion == null) {
            return;
        }
        mantenimientoService.eliminar(planEnEdicion);
        Notification.show("Plan eliminado", 2500, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        limpiarFormulario();
        refrescarGrid();
    }

    private void refrescarGrid() {
        grid.setItems(mantenimientoService.listarEstadoPlanes());
    }

    private String formatearFecha(EstadoPlanDTO estado) {
        return estado.ultimaFechaRealizado() == null ? "—" : estado.ultimaFechaRealizado().format(FORMATO_FECHA);
    }

    private String formatearEstadoProximoAviso(EstadoPlanDTO estado) {
        if (estado.vencido()) {
            return "Vencido";
        }
        if (estado.proximoAvisoEstimado() == null) {
            return "Sin uso reciente para estimar";
        }
        return estado.proximoAvisoEstimado().format(FORMATO_FECHA) + " (estimado)";
    }
}
