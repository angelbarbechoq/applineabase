package com.example.mantenimiento.ui;

import com.example.base.ui.MainLayout;
import com.example.base.ui.NotificacionesUtil;
import com.example.mantenimiento.model.TecnicoMantenimiento;
import com.example.mantenimiento.service.MantenimientoService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

/**
 * Catalogo de personal de mantenimiento (CI, nombre, especialidad) que alimenta el combo
 * "Tecnico" del formulario de tareas ya ejecutadas con los nombres registrados.
 */
@PageTitle("Personal de Mantenimiento | LineaBase")
@Route(value = "mantenimiento/personal", layout = MainLayout.class)
@RolesAllowed("ADMIN")
public class PersonalMantenimientoView extends VerticalLayout {

    private final MantenimientoService mantenimientoService;
    private final Grid<TecnicoMantenimiento> grid = new Grid<>(TecnicoMantenimiento.class, false);
    private final TextField ciField = new TextField("CI");
    private final TextField nombreField = new TextField("Tecnico");
    private final ComboBox<String> especialidadField = new ComboBox<>("Especialidad");
    private final Button guardarBtn = new Button("Guardar");
    private final Button nuevoBtn = new Button("Nuevo");

    private TecnicoMantenimiento tecnicoEnEdicion;

    public PersonalMantenimientoView(MantenimientoService mantenimientoService) {
        this.mantenimientoService = mantenimientoService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Personal de Mantenimiento"));

        ciField.setWidth("150px");
        nombreField.setWidth("260px");

        especialidadField.setItems(mantenimientoService.listarEspecialidades());
        especialidadField.setAllowCustomValue(true);
        especialidadField.addCustomValueSetListener(e -> especialidadField.setValue(e.getDetail()));
        especialidadField.setWidth("180px");

        guardarBtn.addClickListener(e -> guardar());
        guardarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        nuevoBtn.addClickListener(e -> limpiarFormulario());
        nuevoBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout formLayout = new HorizontalLayout(ciField, nombreField, especialidadField, guardarBtn, nuevoBtn);
        formLayout.setAlignItems(Alignment.END);
        formLayout.getStyle().set("flex-wrap", "wrap");

        grid.addColumn(TecnicoMantenimiento::getCi).setHeader("CI").setAutoWidth(true).setSortable(true);
        grid.addColumn(TecnicoMantenimiento::getNombre).setHeader("Tecnico").setAutoWidth(true).setSortable(true);
        grid.addColumn(TecnicoMantenimiento::getEspecialidad).setHeader("Especialidad").setAutoWidth(true).setSortable(true);
        grid.addComponentColumn(this::botonEliminar).setHeader("").setAutoWidth(true);
        grid.asSingleSelect().addValueChangeListener(e -> cargarEnFormulario(e.getValue()));
        grid.setSizeFull();

        add(formLayout, grid);
        setFlexGrow(1, grid);

        refrescarGrid();
    }

    private Button botonEliminar(TecnicoMantenimiento tecnico) {
        Button boton = new Button(VaadinIcon.TRASH.create());
        boton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        boton.addClickListener(e -> {
            mantenimientoService.eliminarTecnico(tecnico);
            refrescarGrid();
        });
        return boton;
    }

    private void cargarEnFormulario(TecnicoMantenimiento tecnico) {
        tecnicoEnEdicion = tecnico;
        if (tecnico == null) {
            limpiarFormulario();
            return;
        }
        ciField.setValue(tecnico.getCi() == null ? "" : tecnico.getCi());
        nombreField.setValue(tecnico.getNombre() == null ? "" : tecnico.getNombre());
        especialidadField.setValue(tecnico.getEspecialidad());
    }

    private void limpiarFormulario() {
        tecnicoEnEdicion = null;
        grid.asSingleSelect().clear();
        ciField.clear();
        nombreField.clear();
        especialidadField.clear();
    }

    private void guardar() {
        if (nombreField.getValue() == null || nombreField.getValue().isBlank()) {
            NotificacionesUtil.mostrarError("Ingresa el nombre del tecnico");
            return;
        }
        if (tecnicoEnEdicion != null) {
            tecnicoEnEdicion.setCi(ciField.getValue());
            tecnicoEnEdicion.setNombre(nombreField.getValue());
            tecnicoEnEdicion.setEspecialidad(especialidadField.getValue());
            mantenimientoService.guardarTecnico(tecnicoEnEdicion);
        } else {
            mantenimientoService.agregarTecnico(ciField.getValue(), nombreField.getValue(), especialidadField.getValue());
        }
        Notification.show("Guardado", 2000, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        limpiarFormulario();
        refrescarGrid();
    }

    private void refrescarGrid() {
        grid.setItems(mantenimientoService.listarTecnicosCompleto());
    }
}
