package com.example.mantenimiento.ui;

import com.example.base.ui.MainLayout;
import com.example.base.ui.NotificacionesUtil;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.mantenimiento.model.PlanMantenimiento;
import com.example.mantenimiento.service.MantenimientoService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Alta y edición de planes de mantenimiento preventivo por horas: un TAG (equipo o ítem del
 * catálogo ISO 14224 de Extrusión, o un lineaMaquina plano para el resto de las zonas) + una
 * tarea + intervalo de horas. Solo el ADMIN gestiona los planes — el dashboard de estado (a
 * implementar) sí es visible para más gente vía LineaAccessService.puedeVerMantenimientoPreventivo.
 */
@PageTitle("Configuración de Mantenimiento | LineaBase")
@Route(value = "mantenimiento/config", layout = MainLayout.class)
@RolesAllowed("ADMIN")
public class MantenimientoConfigView extends VerticalLayout {

    private final MantenimientoService mantenimientoService;
    private final Grid<PlanMantenimiento> grid = new Grid<>(PlanMantenimiento.class, false);

    private final Span formTitle = new Span("Selecciona un plan para editarlo, o crea uno nuevo");
    private final ComboBox<String> tagCombo = new ComboBox<>("TAG (equipo/ítem) o línea");
    private final TextField tareaField = new TextField("Tarea");
    private final NumberField intervaloField = new NumberField("Intervalo (horas)");
    private final NumberField avisoAnticipadoField = new NumberField("Aviso anticipado (horas antes)");
    private final Checkbox habilitadoCheckbox = new Checkbox("Habilitado", true);
    private final Button guardarBtn = new Button("Guardar");
    private final Button eliminarBtn = new Button("Eliminar");

    private PlanMantenimiento planEnEdicion;

    public MantenimientoConfigView(MantenimientoService mantenimientoService, ConfigLoaderService configLoaderService) {
        this.mantenimientoService = mantenimientoService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Configuración de Mantenimiento Preventivo"));

        List<String> tags = new ArrayList<>(configLoaderService.listarTodosLosTagsExtrusion());
        tags.addAll(configLoaderService.listarNombresLinea());
        tagCombo.setItems(tags.stream().distinct().sorted().toList());
        tagCombo.setAllowCustomValue(true);
        tagCombo.setWidth("280px");

        tareaField.setWidth("220px");
        intervaloField.setWidth("170px");
        intervaloField.setStep(1);
        intervaloField.setMin(0);
        avisoAnticipadoField.setWidth("200px");
        avisoAnticipadoField.setStep(1);
        avisoAnticipadoField.setMin(0);

        grid.addColumn(PlanMantenimiento::getTag).setHeader("TAG").setAutoWidth(true).setSortable(true);
        grid.addColumn(PlanMantenimiento::getTarea).setHeader("Tarea").setAutoWidth(true).setSortable(true);
        grid.addColumn(PlanMantenimiento::getIntervaloHoras).setHeader("Intervalo (h)").setAutoWidth(true);
        grid.addColumn(p -> p.isHabilitado() ? "Sí" : "No").setHeader("Habilitado").setAutoWidth(true);
        grid.asSingleSelect().addValueChangeListener(e -> cargarEnFormulario(e.getValue()));
        grid.setSizeFull();

        guardarBtn.addClickListener(e -> guardar());
        guardarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button nuevoBtn = new Button("Nuevo plan", e -> limpiarFormulario());
        nuevoBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        eliminarBtn.addClickListener(e -> eliminar());
        eliminarBtn.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        eliminarBtn.setEnabled(false);

        HorizontalLayout formLayout = new HorizontalLayout(
                tagCombo, tareaField, intervaloField, avisoAnticipadoField, habilitadoCheckbox,
                guardarBtn, nuevoBtn, eliminarBtn
        );
        formLayout.setAlignItems(Alignment.END);
        formLayout.getStyle().set("flex-wrap", "wrap");

        add(formTitle, formLayout, grid);
        setFlexGrow(1, grid);

        limpiarFormulario();
        refrescarGrid();
    }

    private void cargarEnFormulario(PlanMantenimiento plan) {
        planEnEdicion = plan;
        if (plan == null) {
            limpiarFormulario();
            return;
        }
        formTitle.setText("Editando: " + plan.getTag() + " / " + plan.getTarea());
        tagCombo.setValue(plan.getTag());
        tagCombo.setEnabled(false);
        tareaField.setValue(plan.getTarea());
        tareaField.setEnabled(false);
        intervaloField.setValue(plan.getIntervaloHoras());
        avisoAnticipadoField.setValue(plan.getHorasAvisoAnticipado());
        habilitadoCheckbox.setValue(plan.isHabilitado());
        eliminarBtn.setEnabled(true);
    }

    private void limpiarFormulario() {
        planEnEdicion = null;
        formTitle.setText("Nuevo plan");
        grid.asSingleSelect().clear();
        tagCombo.clear();
        tagCombo.setEnabled(true);
        tareaField.clear();
        tareaField.setEnabled(true);
        intervaloField.clear();
        avisoAnticipadoField.clear();
        habilitadoCheckbox.setValue(true);
        eliminarBtn.setEnabled(false);
    }

    private void guardar() {
        PlanMantenimiento plan = planEnEdicion;

        if (plan == null) {
            String tag = tagCombo.getValue();
            String tarea = tareaField.getValue();
            if (tag == null || tag.isBlank() || tarea == null || tarea.isBlank()) {
                NotificacionesUtil.mostrarError("Selecciona un TAG e ingresa la tarea");
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

        plan.setIntervaloHoras(intervaloField.getValue());
        plan.setHorasAvisoAnticipado(avisoAnticipadoField.getValue());
        plan.setHabilitado(habilitadoCheckbox.getValue());

        mantenimientoService.guardar(plan);
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
        grid.setItems(mantenimientoService.listarPlanes());
    }
}
