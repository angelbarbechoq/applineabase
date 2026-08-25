package com.example.mezcladores.ui;

import com.example.base.ui.MainLayout;
import com.example.base.ui.NotificacionesUtil;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Alta/edición/baja de mezcladores-config.json: cada mezclador tiene un canal de
 * temperatura de calentamiento y uno de enfriamiento (dos direcciones Modbus RTU
 * distintas, 1 a 8, detrás del mismo gateway Link150 que ya usan los PAS600L).
 * Config propia y separada de linea-id-config.json porque los mezcladores no son
 * medidores de energía y no comparten su esquema.
 *
 * Visible solo para ADMIN y zona Mezcla (ver LineaAccessService.puedeVerMezcladores).
 */
@PageTitle("Configuración Mezcladores | LineaBase")
@Route(value = "mezcladores/config", layout = MainLayout.class)
@PermitAll
public class MezcladoresConfigView extends VerticalLayout implements BeforeEnterObserver {

    private final ConfigLoaderService configLoaderService;
    private final LineaAccessService lineaAccessService;

    private List<Map<String, Object>> mezcladores;
    private List<Map<String, Object>> gateways;

    private final Grid<Map<String, Object>> grid = new Grid<>();

    public MezcladoresConfigView(ConfigLoaderService configLoaderService, LineaAccessService lineaAccessService) {
        this.configLoaderService = configLoaderService;
        this.lineaAccessService = lineaAccessService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        Button nuevoBtn = new Button("Nuevo mezclador", VaadinIcon.PLUS.create(), e -> abrirDialogo(null));
        nuevoBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout header = new HorizontalLayout(new H3("Configuración Mezcladores"), nuevoBtn);
        header.setWidthFull();
        header.setJustifyContentMode(JustifyContentMode.BETWEEN);
        header.setDefaultVerticalComponentAlignment(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER);
        add(header);

        grid.addColumn(m -> m.get("numero")).setHeader("N°").setAutoWidth(true).setSortable(true);
        grid.addColumn(m -> m.get("nombre")).setHeader("Nombre").setAutoWidth(true).setSortable(true);
        grid.addColumn(m -> m.get("gatewayNombre")).setHeader("Gateway").setAutoWidth(true);
        grid.addColumn(m -> m.get("idCalentamiento")).setHeader("ID Calentamiento").setAutoWidth(true);
        grid.addColumn(m -> m.get("idEnfriamiento")).setHeader("ID Enfriamiento").setAutoWidth(true);
        grid.addComponentColumn(this::crearAcciones).setHeader("Acciones").setAutoWidth(true);
        grid.setSizeFull();

        add(grid);
        setFlexGrow(1, grid);

        cargarTodo();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        AccesoMezcladores.verificar(event, lineaAccessService);
    }

    private HorizontalLayout crearAcciones(Map<String, Object> mezclador) {
        Button editar = new Button("Editar", e -> abrirDialogo(mezclador));
        Button eliminar = new Button("Eliminar", e -> eliminar(mezclador));
        eliminar.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        return new HorizontalLayout(editar, eliminar);
    }

    private void abrirDialogo(Map<String, Object> mezcladorEnEdicion) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(mezcladorEnEdicion == null ? "Nuevo mezclador" : "Editar: " + mezcladorEnEdicion.get("nombre"));
        dialog.setWidth("420px");

        IntegerField numeroField = new IntegerField("N°");
        numeroField.setStepButtonsVisible(false);
        TextField nombreField = new TextField("Nombre");
        ComboBox<String> gatewayField = new ComboBox<>("Gateway");
        gatewayField.setItems(gateways.stream().map(g -> String.valueOf(g.get("nombre"))).collect(Collectors.toList()));
        IntegerField calentamientoField = new IntegerField("ID Calentamiento (Modbus 1-8)");
        calentamientoField.setStepButtonsVisible(false);
        IntegerField enfriamientoField = new IntegerField("ID Enfriamiento (Modbus 1-8)");
        enfriamientoField.setStepButtonsVisible(false);

        if (mezcladorEnEdicion != null) {
            numeroField.setValue(((Number) mezcladorEnEdicion.get("numero")).intValue());
            nombreField.setValue(String.valueOf(mezcladorEnEdicion.getOrDefault("nombre", "")));
            gatewayField.setValue((String) mezcladorEnEdicion.get("gatewayNombre"));
            calentamientoField.setValue(((Number) mezcladorEnEdicion.get("idCalentamiento")).intValue());
            enfriamientoField.setValue(((Number) mezcladorEnEdicion.get("idEnfriamiento")).intValue());
        } else {
            int siguienteNumero = mezcladores.stream()
                    .mapToInt(m -> ((Number) m.get("numero")).intValue()).max().orElse(0) + 1;
            int siguienteId = mezcladores.stream()
                    .flatMap(m -> java.util.stream.Stream.of(
                            ((Number) m.get("idCalentamiento")).intValue(),
                            ((Number) m.get("idEnfriamiento")).intValue()))
                    .max(Integer::compareTo).orElse(0);
            numeroField.setValue(siguienteNumero);
            // Nombre sugerido con el mismo formato que la línea de energía del mismo mezclador
            // físico (ej. "Mixer01" en linea-id-config.json), para que las tablas de temperatura
            // queden asociadas a simple vista con su máquina. Editable si no calza.
            nombreField.setValue(String.format("Mixer%02d", siguienteNumero));
            calentamientoField.setValue(siguienteId + 1);
            enfriamientoField.setValue(siguienteId + 2);
            // Sin gateway preseleccionado: el primero de la lista podría ser el del PAS600L
            // (energía), no el del Link150 de los mezcladores — que el usuario elija a propósito.
        }

        FormLayout form = new FormLayout(numeroField, nombreField, gatewayField, calentamientoField, enfriamientoField);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1));
        dialog.add(form);

        Button cancelarBtn = new Button("Cancelar", e -> dialog.close());
        Button guardarBtn = new Button("Guardar", e -> {
            boolean ok = guardar(mezcladorEnEdicion, numeroField.getValue(), nombreField.getValue(),
                    gatewayField.getValue(), calentamientoField.getValue(), enfriamientoField.getValue());
            if (ok) {
                dialog.close();
            }
        });
        guardarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(cancelarBtn, guardarBtn);

        dialog.open();
    }

    private boolean guardar(Map<String, Object> mezcladorEnEdicion, Integer numero, String nombre, String gateway,
                             Integer idCalentamiento, Integer idEnfriamiento) {
        if (numero == null) {
            NotificacionesUtil.mostrarError("El N° es obligatorio");
            return false;
        }
        if (nombre == null || nombre.isBlank()) {
            NotificacionesUtil.mostrarError("El nombre es obligatorio");
            return false;
        }
        if (gateway == null || gateway.isBlank()) {
            NotificacionesUtil.mostrarError("Debes asignar un gateway");
            return false;
        }
        if (idCalentamiento == null || idEnfriamiento == null) {
            NotificacionesUtil.mostrarError("Los ID de calentamiento y enfriamiento son obligatorios");
            return false;
        }
        if (idCalentamiento.equals(idEnfriamiento)) {
            NotificacionesUtil.mostrarError("El ID de calentamiento y enfriamiento no pueden ser el mismo");
            return false;
        }

        boolean numeroDuplicado = mezcladores.stream()
                .anyMatch(m -> m != mezcladorEnEdicion && numero.equals(((Number) m.get("numero")).intValue()));
        if (numeroDuplicado) {
            NotificacionesUtil.mostrarError("Ya existe un mezclador con ese N°");
            return false;
        }

        boolean idDuplicado = mezcladores.stream()
                .filter(m -> m != mezcladorEnEdicion && gateway.equals(m.get("gatewayNombre")))
                .flatMap(m -> java.util.stream.Stream.of(
                        ((Number) m.get("idCalentamiento")).intValue(),
                        ((Number) m.get("idEnfriamiento")).intValue()))
                .anyMatch(id -> id.equals(idCalentamiento) || id.equals(idEnfriamiento));
        if (idDuplicado) {
            NotificacionesUtil.mostrarError("Ya existe un canal con ese ID Modbus en ese gateway");
            return false;
        }

        Map<String, Object> mezclador;
        if (mezcladorEnEdicion == null) {
            mezclador = new LinkedHashMap<>();
            mezcladores.add(mezclador);
        } else {
            mezclador = mezcladorEnEdicion;
        }
        mezclador.put("numero", numero);
        mezclador.put("nombre", nombre);
        mezclador.put("gatewayNombre", gateway);
        mezclador.put("idCalentamiento", idCalentamiento);
        mezclador.put("idEnfriamiento", idEnfriamiento);

        configLoaderService.saveMezcladoresConfig(mezcladores);
        Notification.show("Mezclador guardado", 2500, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        cargarTodo();
        return true;
    }

    private void eliminar(Map<String, Object> mezclador) {
        mezcladores.remove(mezclador);
        configLoaderService.saveMezcladoresConfig(mezcladores);
        Notification.show("Mezclador eliminado", 2500, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        cargarTodo();
    }

    private void cargarTodo() {
        mezcladores = configLoaderService.loadMezcladoresConfig();
        gateways = configLoaderService.loadGatewayConfig();
        grid.setItems(mezcladores);
        grid.recalculateColumnWidths();
    }
}
