package com.example.base.ui;

import com.example.dataacquisition.service.ConfigLoaderService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Alta/edicion/baja de linea-id-config.json y plc-config.json (lineas,
 * PLCs y gateways). Persisten via ConfigLoaderService en la carpeta externa
 * app.config.dir; el polling Modbus (PASReaderService, PLCDataAcquisitionService)
 * solo toma estos cambios tras reiniciar la aplicacion, porque arma sus
 * dispositivos una sola vez al iniciar.
 */
@PageTitle("Configuración | LineaBase")
@Route(value = "configuracion", layout = MainLayout.class)
@RolesAllowed("ADMIN")
public class ConfiguracionView extends VerticalLayout {

    private final ConfigLoaderService configLoaderService;

    private List<Map<String, Object>> lineas;
    private List<Map<String, Object>> plcs;
    private List<Map<String, Object>> gateways;

    private final Grid<Map<String, Object>> lineasGrid = new Grid<>();
    private final IntegerField lineaIdField = new IntegerField("ID");
    private final TextField lineaNombreField = new TextField("Línea/Máquina");
    private final TextField medidorField = new TextField("Medidor");
    private final ComboBox<String> lineaPlcField = new ComboBox<>("PLC");
    private final TextField serialField = new TextField("Serial");
    private final ComboBox<String> zonaField = new ComboBox<>("Zona");
    private final Span lineaFormTitle = new Span("Nueva línea");
    private Map<String, Object> lineaEnEdicion;

    private final Grid<Map<String, Object>> plcsGrid = new Grid<>();
    private final TextField plcNombreField = new TextField("Nombre");
    private final TextField plcIpField = new TextField("IP");
    private final Span plcFormTitle = new Span("Nuevo PLC");
    private Map<String, Object> plcEnEdicion;

    private final Grid<Map<String, Object>> gatewaysGrid = new Grid<>();
    private final TextField gatewayNombreField = new TextField("Nombre");
    private final TextField gatewayIpField = new TextField("IP");
    private final TextField gatewayDescripcionField = new TextField("Descripción");
    private final Span gatewayFormTitle = new Span("Nuevo gateway");
    private Map<String, Object> gatewayEnEdicion;

    public ConfiguracionView(ConfigLoaderService configLoaderService) {
        this.configLoaderService = configLoaderService;
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Configuración de Líneas, PLCs y Gateways"));
        add(crearAvisoReinicio());

        VerticalLayout panelLineas = crearPanelLineas();
        VerticalLayout panelDerecho = crearPanelDerecho();

        HorizontalLayout columnas = new HorizontalLayout(panelLineas, panelDerecho);
        columnas.setSizeFull();
        columnas.setSpacing(true);
        columnas.setFlexGrow(3, panelLineas);
        columnas.setFlexGrow(2, panelDerecho);
        add(columnas);
        setFlexGrow(1, columnas);

        cargarTodo();
    }

    private Span crearAvisoReinicio() {
        Span aviso = new Span(
                "Los cambios se guardan de inmediato, pero el servicio de lectura Modbus (PLCs y PAS600L) " +
                        "solo los toma en cuenta despues de reiniciar la aplicación.");
        aviso.getStyle()
                .set("background", "#fff3cd")
                .set("color", "#7a5b00")
                .set("padding", "8px 12px")
                .set("border-radius", "6px")
                .set("font-size", "13px");
        return aviso;
    }

    // ================= Lineas/Maquinas =================

    private VerticalLayout crearPanelLineas() {
        VerticalLayout panel = new VerticalLayout();
        panel.setPadding(false);
        panel.setSpacing(true);
        panel.setSizeFull();

        lineaIdField.setWidth("90px");
        lineaIdField.setStepButtonsVisible(false);
        lineaNombreField.setWidth("160px");
        medidorField.setWidth("120px");
        lineaPlcField.setWidth("150px");
        serialField.setWidth("160px");
        zonaField.setWidth("150px");
        zonaField.setAllowCustomValue(true);
        zonaField.addCustomValueSetListener(e -> zonaField.setValue(e.getDetail()));

        Button guardarBtn = new Button("Guardar", e -> guardarLinea());
        guardarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button nuevaBtn = new Button("Nueva", e -> limpiarFormularioLinea());
        nuevaBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout fila1 = new HorizontalLayout(lineaIdField, lineaNombreField, medidorField, lineaPlcField);
        HorizontalLayout fila2 = new HorizontalLayout(serialField, zonaField, guardarBtn, nuevaBtn);
        fila1.setAlignItems(Alignment.END);
        fila2.setAlignItems(Alignment.END);
        fila1.getStyle().set("flex-wrap", "wrap");
        fila2.getStyle().set("flex-wrap", "wrap");

        lineasGrid.addColumn(l -> l.get("id")).setHeader("ID").setAutoWidth(true).setSortable(true);
        lineasGrid.addColumn(l -> l.get("lineaMaquina")).setHeader("Línea/Máquina").setAutoWidth(true).setSortable(true);
        lineasGrid.addColumn(l -> l.get("modeloMedidor")).setHeader("Medidor").setAutoWidth(true);
        lineasGrid.addColumn(l -> l.get("nombrePLC")).setHeader("PLC").setAutoWidth(true);
        lineasGrid.addColumn(l -> l.get("numeroSerie")).setHeader("Serial").setAutoWidth(true);
        lineasGrid.addColumn(l -> l.get("zona")).setHeader("Zona").setAutoWidth(true);
        lineasGrid.addComponentColumn(this::crearAccionesLinea).setHeader("Acciones").setAutoWidth(true);
        lineasGrid.setSizeFull();

        panel.add(lineaFormTitle, fila1, fila2, lineasGrid);
        panel.setFlexGrow(1, lineasGrid);
        return panel;
    }

    private HorizontalLayout crearAccionesLinea(Map<String, Object> linea) {
        Button editar = new Button("Editar", e -> cargarLineaEnFormulario(linea));
        Button eliminar = new Button("Eliminar", e -> eliminarLinea(linea));
        eliminar.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        return new HorizontalLayout(editar, eliminar);
    }

    private void cargarLineaEnFormulario(Map<String, Object> linea) {
        lineaEnEdicion = linea;
        lineaFormTitle.setText("Editando: " + linea.get("lineaMaquina"));
        lineaIdField.setValue(((Number) linea.get("id")).intValue());
        lineaNombreField.setValue(String.valueOf(linea.getOrDefault("lineaMaquina", "")));
        medidorField.setValue(String.valueOf(linea.getOrDefault("modeloMedidor", "")));
        lineaPlcField.setValue((String) linea.get("nombrePLC"));
        serialField.setValue(String.valueOf(linea.getOrDefault("numeroSerie", "")));
        zonaField.setValue((String) linea.get("zona"));
    }

    private void limpiarFormularioLinea() {
        lineaEnEdicion = null;
        lineaFormTitle.setText("Nueva línea");
        lineaIdField.clear();
        lineaNombreField.clear();
        medidorField.clear();
        lineaPlcField.clear();
        serialField.clear();
        zonaField.clear();
    }

    private void guardarLinea() {
        Integer id = lineaIdField.getValue();
        String nombre = lineaNombreField.getValue();
        String plc = lineaPlcField.getValue();
        String zona = zonaField.getValue();

        if (id == null) {
            mostrarError("El ID es obligatorio");
            return;
        }
        if (nombre == null || nombre.isBlank()) {
            mostrarError("Línea/Máquina es obligatorio");
            return;
        }
        if (plc == null || plc.isBlank()) {
            mostrarError("Debes asignar un PLC o Gateway");
            return;
        }
        if (zona == null || zona.isBlank()) {
            mostrarError("La zona es obligatoria");
            return;
        }
        boolean idDuplicado = lineas.stream()
                .anyMatch(l -> l != lineaEnEdicion && id.equals(((Number) l.get("id")).intValue()));
        if (idDuplicado) {
            mostrarError("Ya existe una línea con ese ID");
            return;
        }
        boolean nombreDuplicado = lineas.stream()
                .anyMatch(l -> l != lineaEnEdicion && nombre.equalsIgnoreCase(String.valueOf(l.get("lineaMaquina"))));
        if (nombreDuplicado) {
            mostrarError("Ya existe una línea con ese nombre");
            return;
        }

        Map<String, Object> linea;
        if (lineaEnEdicion == null) {
            linea = new LinkedHashMap<>();
            lineas.add(linea);
        } else {
            linea = lineaEnEdicion;
        }
        linea.put("id", id);
        linea.put("lineaMaquina", nombre);
        linea.put("modeloMedidor", medidorField.getValue());
        linea.put("nombrePLC", plc);
        linea.put("numeroSerie", serialField.getValue() == null ? "" : serialField.getValue());
        linea.put("zona", zona);

        configLoaderService.saveLineaIDConfig(lineas);
        Notification.show("Línea guardada", 2500, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        cargarTodo();
    }

    private void eliminarLinea(Map<String, Object> linea) {
        lineas.remove(linea);
        configLoaderService.saveLineaIDConfig(lineas);
        Notification.show("Línea eliminada. Su histórico en SQLite queda intacto bajo el nombre anterior.",
                3500, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        cargarTodo();
    }

    // ================= PLCs y Gateways =================

    private VerticalLayout crearPanelDerecho() {
        VerticalLayout panel = new VerticalLayout(crearPanelPlcs(), crearPanelGateways());
        panel.setPadding(false);
        panel.setSpacing(true);
        return panel;
    }

    private VerticalLayout crearPanelPlcs() {
        VerticalLayout panel = new VerticalLayout();
        panel.setPadding(false);
        panel.setSpacing(true);

        plcNombreField.setWidth("110px");
        plcIpField.setWidth("130px");

        Button guardarBtn = new Button("Guardar", e -> guardarPlc());
        guardarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button nuevoBtn = new Button("Nuevo", e -> limpiarFormularioPlc());
        nuevoBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout fila = new HorizontalLayout(plcNombreField, plcIpField, guardarBtn, nuevoBtn);
        fila.setAlignItems(Alignment.END);
        fila.getStyle().set("flex-wrap", "wrap");

        plcsGrid.addColumn(p -> p.get("nombre")).setHeader("Nombre").setAutoWidth(true);
        plcsGrid.addColumn(p -> p.get("ipAddress")).setHeader("IP").setAutoWidth(true);
        plcsGrid.addComponentColumn(this::crearAccionesPlc).setHeader("Acciones").setAutoWidth(true);
        plcsGrid.setHeight("220px");
        plcsGrid.setWidthFull();

        panel.add(new H4("PLCs"), plcFormTitle, fila, plcsGrid);
        return panel;
    }

    private HorizontalLayout crearAccionesPlc(Map<String, Object> plc) {
        Button editar = new Button("Editar", e -> cargarPlcEnFormulario(plc));
        Button eliminar = new Button("Eliminar", e -> eliminarPlc(plc));
        eliminar.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        return new HorizontalLayout(editar, eliminar);
    }

    private void cargarPlcEnFormulario(Map<String, Object> plc) {
        plcEnEdicion = plc;
        plcFormTitle.setText("Editando: " + plc.get("nombre"));
        plcNombreField.setValue(String.valueOf(plc.getOrDefault("nombre", "")));
        plcNombreField.setReadOnly(true);
        plcIpField.setValue(String.valueOf(plc.getOrDefault("ipAddress", "")));
    }

    private void limpiarFormularioPlc() {
        plcEnEdicion = null;
        plcFormTitle.setText("Nuevo PLC");
        plcNombreField.clear();
        plcNombreField.setReadOnly(false);
        plcIpField.clear();
    }

    private void guardarPlc() {
        String nombre = plcNombreField.getValue();
        String ip = plcIpField.getValue();

        if (nombre == null || nombre.isBlank()) {
            mostrarError("El nombre del PLC es obligatorio");
            return;
        }
        if (!esIpValida(ip)) {
            mostrarError("La IP no tiene un formato válido");
            return;
        }
        boolean nombreDuplicado = plcs.stream()
                .anyMatch(p -> p != plcEnEdicion && nombre.equalsIgnoreCase(String.valueOf(p.get("nombre"))));
        if (nombreDuplicado) {
            mostrarError("Ya existe un PLC con ese nombre");
            return;
        }

        Map<String, Object> plc;
        if (plcEnEdicion == null) {
            plc = new LinkedHashMap<>();
            plcs.add(plc);
        } else {
            plc = plcEnEdicion;
        }
        plc.put("nombre", nombre);
        plc.put("ipAddress", ip);

        configLoaderService.savePLCsYGateways(plcs, gateways);
        Notification.show("PLC guardado", 2500, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        cargarTodo();
    }

    private void eliminarPlc(Map<String, Object> plc) {
        String nombre = String.valueOf(plc.get("nombre"));
        long lineasAfectadas = lineas.stream().filter(l -> nombre.equals(l.get("nombrePLC"))).count();

        plcs.remove(plc);
        configLoaderService.savePLCsYGateways(plcs, gateways);

        String mensaje = lineasAfectadas > 0
                ? "PLC eliminado. " + lineasAfectadas + " línea(s) quedaron sin PLC asignado."
                : "PLC eliminado";
        Notification.show(mensaje, 3500, Notification.Position.BOTTOM_END)
                .addThemeVariants(lineasAfectadas == 0 ? NotificationVariant.LUMO_SUCCESS : NotificationVariant.LUMO_ERROR);
        cargarTodo();
    }

    private VerticalLayout crearPanelGateways() {
        VerticalLayout panel = new VerticalLayout();
        panel.setPadding(false);
        panel.setSpacing(true);

        gatewayNombreField.setWidth("110px");
        gatewayIpField.setWidth("130px");
        gatewayDescripcionField.setWidth("180px");

        Button guardarBtn = new Button("Guardar", e -> guardarGateway());
        guardarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button nuevoBtn = new Button("Nuevo", e -> limpiarFormularioGateway());
        nuevoBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout fila = new HorizontalLayout(gatewayNombreField, gatewayIpField, gatewayDescripcionField, guardarBtn, nuevoBtn);
        fila.setAlignItems(Alignment.END);
        fila.getStyle().set("flex-wrap", "wrap");

        gatewaysGrid.addColumn(g -> g.get("nombre")).setHeader("Nombre").setAutoWidth(true);
        gatewaysGrid.addColumn(g -> g.get("ipAddress")).setHeader("IP").setAutoWidth(true);
        gatewaysGrid.addColumn(g -> g.get("descripcion")).setHeader("Descripción").setAutoWidth(true);
        gatewaysGrid.addComponentColumn(this::crearAccionesGateway).setHeader("Acciones").setAutoWidth(true);
        gatewaysGrid.setHeight("220px");
        gatewaysGrid.setWidthFull();

        panel.add(new H4("Gateways"), gatewayFormTitle, fila, gatewaysGrid);
        return panel;
    }

    private HorizontalLayout crearAccionesGateway(Map<String, Object> gateway) {
        Button editar = new Button("Editar", e -> cargarGatewayEnFormulario(gateway));
        Button eliminar = new Button("Eliminar", e -> eliminarGateway(gateway));
        eliminar.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        return new HorizontalLayout(editar, eliminar);
    }

    private void cargarGatewayEnFormulario(Map<String, Object> gateway) {
        gatewayEnEdicion = gateway;
        gatewayFormTitle.setText("Editando: " + gateway.get("nombre"));
        gatewayNombreField.setValue(String.valueOf(gateway.getOrDefault("nombre", "")));
        gatewayNombreField.setReadOnly(true);
        gatewayIpField.setValue(String.valueOf(gateway.getOrDefault("ipAddress", "")));
        gatewayDescripcionField.setValue(String.valueOf(gateway.getOrDefault("descripcion", "")));
    }

    private void limpiarFormularioGateway() {
        gatewayEnEdicion = null;
        gatewayFormTitle.setText("Nuevo gateway");
        gatewayNombreField.clear();
        gatewayNombreField.setReadOnly(false);
        gatewayIpField.clear();
        gatewayDescripcionField.clear();
    }

    private void guardarGateway() {
        String nombre = gatewayNombreField.getValue();
        String ip = gatewayIpField.getValue();

        if (nombre == null || nombre.isBlank()) {
            mostrarError("El nombre del gateway es obligatorio");
            return;
        }
        if (!esIpValida(ip)) {
            mostrarError("La IP no tiene un formato válido");
            return;
        }
        boolean nombreDuplicado = gateways.stream()
                .anyMatch(g -> g != gatewayEnEdicion && nombre.equalsIgnoreCase(String.valueOf(g.get("nombre"))));
        if (nombreDuplicado) {
            mostrarError("Ya existe un gateway con ese nombre");
            return;
        }

        Map<String, Object> gateway;
        if (gatewayEnEdicion == null) {
            gateway = new LinkedHashMap<>();
            gateways.add(gateway);
        } else {
            gateway = gatewayEnEdicion;
        }
        gateway.put("nombre", nombre);
        gateway.put("ipAddress", ip);
        gateway.put("descripcion", gatewayDescripcionField.getValue());

        configLoaderService.savePLCsYGateways(plcs, gateways);
        Notification.show("Gateway guardado", 2500, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        cargarTodo();
    }

    private void eliminarGateway(Map<String, Object> gateway) {
        String nombre = String.valueOf(gateway.get("nombre"));
        long lineasAfectadas = lineas.stream().filter(l -> nombre.equals(l.get("nombrePLC"))).count();

        gateways.remove(gateway);
        configLoaderService.savePLCsYGateways(plcs, gateways);

        String mensaje = lineasAfectadas > 0
                ? "Gateway eliminado. " + lineasAfectadas + " línea(s) quedaron sin PLC asignado."
                : "Gateway eliminado";
        Notification.show(mensaje, 3500, Notification.Position.BOTTOM_END)
                .addThemeVariants(lineasAfectadas == 0 ? NotificationVariant.LUMO_SUCCESS : NotificationVariant.LUMO_ERROR);
        cargarTodo();
    }

    // ================= Comun =================

    private boolean esIpValida(String ip) {
        return ip != null && ip.matches("^\\d{1,3}(\\.\\d{1,3}){3}$");
    }

    private void mostrarError(String mensaje) {
        Notification.show(mensaje, 3000, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    /**
     * Vuelve a leer los 3 JSON desde disco y refresca las grillas. Se llama
     * tras cada alta/edicion/baja, asi que tambien resetea los 3 formularios:
     * de lo contrario un formulario "en edicion" quedaria apuntando a un Map
     * que ya no pertenece a las listas recien cargadas.
     */
    private void cargarTodo() {
        lineas = configLoaderService.loadLineaIDConfig();
        plcs = configLoaderService.loadPLCConfig();
        gateways = configLoaderService.loadGatewayConfig();

        List<String> nombresPlcYGateway = Stream.concat(
                plcs.stream().map(p -> String.valueOf(p.get("nombre"))),
                gateways.stream().map(g -> String.valueOf(g.get("nombre")))
        ).collect(Collectors.toList());
        lineaPlcField.setItems(nombresPlcYGateway);

        List<String> zonas = lineas.stream()
                .map(l -> (String) l.get("zona"))
                .filter(z -> z != null && !z.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        zonaField.setItems(zonas);

        lineasGrid.setItems(lineas);
        plcsGrid.setItems(plcs);
        gatewaysGrid.setItems(gateways);
        lineasGrid.recalculateColumnWidths();
        plcsGrid.recalculateColumnWidths();
        gatewaysGrid.recalculateColumnWidths();

        limpiarFormularioLinea();
        limpiarFormularioPlc();
        limpiarFormularioGateway();
    }
}
