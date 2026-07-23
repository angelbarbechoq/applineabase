package com.example.base.ui;

import com.example.dataacquisition.service.ConfigLoaderService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
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
 *
 * El alta/edicion se hace en dialogos (no inline) para que las 3 grillas
 * puedan ocupar toda la pantalla en vez de competir por espacio.
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
    private final Grid<Map<String, Object>> plcsGrid = new Grid<>();
    private final Grid<Map<String, Object>> gatewaysGrid = new Grid<>();

    public ConfiguracionView(ConfigLoaderService configLoaderService) {
        this.configLoaderService = configLoaderService;
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Configuración de Líneas, PLCs y Gateways"));
        add(crearAvisoReinicio());

        TabSheet tabSheet = new TabSheet();
        tabSheet.setSizeFull();
        tabSheet.add("Líneas / Máquinas", crearPanelLineas());
        tabSheet.add("PLCs", crearPanelPlcs());
        tabSheet.add("Gateways", crearPanelGateways());
        add(tabSheet);
        setFlexGrow(1, tabSheet);

        cargarTodo();
    }

    private Span crearAvisoReinicio() {
        Span aviso = new Span(
                "Los cambios se guardan de inmediato, pero el servicio de lectura Modbus (PLCs y PAS600L) " +
                        "solo los toma en cuenta despues de reiniciar la aplicación.");
        aviso.getStyle()
                .set("background", "#fff3cd")
                .set("color", "#7a5b00")
                .set("padding", "6px 12px")
                .set("border-radius", "6px")
                .set("font-size", "12px");
        return aviso;
    }

    // ================= Lineas/Maquinas =================

    private VerticalLayout crearPanelLineas() {
        VerticalLayout panel = new VerticalLayout();
        panel.setSizeFull();
        panel.setPadding(false);
        panel.setSpacing(false);

        Button nuevaBtn = new Button("Nueva línea", VaadinIcon.PLUS.create(), e -> abrirDialogoLinea(null));
        nuevaBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout toolbar = new HorizontalLayout(nuevaBtn);
        toolbar.setWidthFull();
        toolbar.setJustifyContentMode(JustifyContentMode.END);
        toolbar.getStyle().set("padding", "8px 0");

        lineasGrid.addColumn(l -> l.get("id")).setHeader("ID").setAutoWidth(true).setSortable(true);
        lineasGrid.addColumn(l -> l.get("lineaMaquina")).setHeader("Línea/Máquina").setAutoWidth(true).setSortable(true);
        lineasGrid.addColumn(l -> l.get("modeloMedidor")).setHeader("Medidor").setAutoWidth(true);
        lineasGrid.addColumn(l -> l.get("nombrePLC")).setHeader("PLC").setAutoWidth(true).setSortable(true);
        lineasGrid.addColumn(l -> l.get("numeroSerie")).setHeader("Serial").setAutoWidth(true);
        lineasGrid.addColumn(l -> l.get("zona")).setHeader("Zona").setAutoWidth(true).setSortable(true);
        lineasGrid.addComponentColumn(this::crearAccionesLinea).setHeader("Acciones").setAutoWidth(true);
        lineasGrid.setSizeFull();

        panel.add(toolbar, lineasGrid);
        panel.setFlexGrow(1, lineasGrid);
        return panel;
    }

    private HorizontalLayout crearAccionesLinea(Map<String, Object> linea) {
        Button editar = new Button("Editar", e -> abrirDialogoLinea(linea));
        Button eliminar = new Button("Eliminar", e -> eliminarLinea(linea));
        eliminar.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        return new HorizontalLayout(editar, eliminar);
    }

    private void abrirDialogoLinea(Map<String, Object> lineaEnEdicion) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(lineaEnEdicion == null ? "Nueva línea" : "Editar: " + lineaEnEdicion.get("lineaMaquina"));
        dialog.setWidth("480px");

        IntegerField idField = new IntegerField("ID");
        idField.setStepButtonsVisible(false);
        TextField nombreField = new TextField("Línea/Máquina");
        TextField medidorField = new TextField("Medidor");
        ComboBox<String> plcField = new ComboBox<>("PLC");
        plcField.setItems(Stream.concat(
                plcs.stream().map(p -> String.valueOf(p.get("nombre"))),
                gateways.stream().map(g -> String.valueOf(g.get("nombre")))
        ).collect(Collectors.toList()));
        TextField serialField = new TextField("Serial");
        ComboBox<String> zonaField = new ComboBox<>("Zona");
        zonaField.setItems(lineas.stream()
                .map(l -> (String) l.get("zona"))
                .filter(z -> z != null && !z.isBlank())
                .distinct().sorted().collect(Collectors.toList()));
        zonaField.setAllowCustomValue(true);
        zonaField.addCustomValueSetListener(e -> zonaField.setValue(e.getDetail()));

        if (lineaEnEdicion != null) {
            idField.setValue(((Number) lineaEnEdicion.get("id")).intValue());
            nombreField.setValue(String.valueOf(lineaEnEdicion.getOrDefault("lineaMaquina", "")));
            medidorField.setValue(String.valueOf(lineaEnEdicion.getOrDefault("modeloMedidor", "")));
            plcField.setValue((String) lineaEnEdicion.get("nombrePLC"));
            serialField.setValue(String.valueOf(lineaEnEdicion.getOrDefault("numeroSerie", "")));
            zonaField.setValue((String) lineaEnEdicion.get("zona"));
        }

        FormLayout form = new FormLayout(idField, nombreField, medidorField, plcField, serialField, zonaField);
        form.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("320px", 2));
        dialog.add(form);

        Button cancelarBtn = new Button("Cancelar", e -> dialog.close());
        Button guardarBtn = new Button("Guardar", e -> {
            boolean ok = guardarLinea(lineaEnEdicion, idField.getValue(), nombreField.getValue(),
                    medidorField.getValue(), plcField.getValue(), serialField.getValue(), zonaField.getValue());
            if (ok) {
                dialog.close();
            }
        });
        guardarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(cancelarBtn, guardarBtn);

        dialog.open();
    }

    private boolean guardarLinea(Map<String, Object> lineaEnEdicion, Integer id, String nombre, String medidor,
                                  String plc, String serial, String zona) {
        if (id == null) {
            mostrarError("El ID es obligatorio");
            return false;
        }
        if (nombre == null || nombre.isBlank()) {
            mostrarError("Línea/Máquina es obligatorio");
            return false;
        }
        if (plc == null || plc.isBlank()) {
            mostrarError("Debes asignar un PLC o Gateway");
            return false;
        }
        if (zona == null || zona.isBlank()) {
            mostrarError("La zona es obligatoria");
            return false;
        }
        boolean idDuplicado = lineas.stream()
                .anyMatch(l -> l != lineaEnEdicion && id.equals(((Number) l.get("id")).intValue()));
        if (idDuplicado) {
            mostrarError("Ya existe una línea con ese ID");
            return false;
        }
        boolean nombreDuplicado = lineas.stream()
                .anyMatch(l -> l != lineaEnEdicion && nombre.equalsIgnoreCase(String.valueOf(l.get("lineaMaquina"))));
        if (nombreDuplicado) {
            mostrarError("Ya existe una línea con ese nombre");
            return false;
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
        linea.put("modeloMedidor", medidor);
        linea.put("nombrePLC", plc);
        linea.put("numeroSerie", serial == null ? "" : serial);
        linea.put("zona", zona);

        configLoaderService.saveLineaIDConfig(lineas);
        Notification.show("Línea guardada", 2500, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        cargarTodo();
        return true;
    }

    private void eliminarLinea(Map<String, Object> linea) {
        lineas.remove(linea);
        configLoaderService.saveLineaIDConfig(lineas);
        Notification.show("Línea eliminada. Su histórico en SQLite queda intacto bajo el nombre anterior.",
                3500, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        cargarTodo();
    }

    // ================= PLCs =================

    private VerticalLayout crearPanelPlcs() {
        VerticalLayout panel = new VerticalLayout();
        panel.setSizeFull();
        panel.setPadding(false);
        panel.setSpacing(false);

        Button nuevoBtn = new Button("Nuevo PLC", VaadinIcon.PLUS.create(), e -> abrirDialogoPlc(null));
        nuevoBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout toolbar = new HorizontalLayout(nuevoBtn);
        toolbar.setWidthFull();
        toolbar.setJustifyContentMode(JustifyContentMode.END);
        toolbar.getStyle().set("padding", "8px 0");

        plcsGrid.addColumn(p -> p.get("nombre")).setHeader("Nombre").setAutoWidth(true);
        plcsGrid.addColumn(p -> p.get("ipAddress")).setHeader("IP").setAutoWidth(true);
        plcsGrid.addComponentColumn(this::crearAccionesPlc).setHeader("Acciones").setAutoWidth(true);
        plcsGrid.setSizeFull();

        panel.add(toolbar, plcsGrid);
        panel.setFlexGrow(1, plcsGrid);
        return panel;
    }

    private HorizontalLayout crearAccionesPlc(Map<String, Object> plc) {
        Button editar = new Button("Editar", e -> abrirDialogoPlc(plc));
        Button eliminar = new Button("Eliminar", e -> eliminarPlc(plc));
        eliminar.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        return new HorizontalLayout(editar, eliminar);
    }

    private void abrirDialogoPlc(Map<String, Object> plcEnEdicion) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(plcEnEdicion == null ? "Nuevo PLC" : "Editar: " + plcEnEdicion.get("nombre"));
        dialog.setWidth("360px");

        TextField nombreField = new TextField("Nombre");
        TextField ipField = new TextField("IP");

        if (plcEnEdicion != null) {
            nombreField.setValue(String.valueOf(plcEnEdicion.getOrDefault("nombre", "")));
            nombreField.setReadOnly(true);
            ipField.setValue(String.valueOf(plcEnEdicion.getOrDefault("ipAddress", "")));
        }

        FormLayout form = new FormLayout(nombreField, ipField);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1));
        dialog.add(form);

        Button cancelarBtn = new Button("Cancelar", e -> dialog.close());
        Button guardarBtn = new Button("Guardar", e -> {
            if (guardarPlc(plcEnEdicion, nombreField.getValue(), ipField.getValue())) {
                dialog.close();
            }
        });
        guardarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(cancelarBtn, guardarBtn);

        dialog.open();
    }

    private boolean guardarPlc(Map<String, Object> plcEnEdicion, String nombre, String ip) {
        if (nombre == null || nombre.isBlank()) {
            mostrarError("El nombre del PLC es obligatorio");
            return false;
        }
        if (!esIpValida(ip)) {
            mostrarError("La IP no tiene un formato válido");
            return false;
        }
        boolean nombreDuplicado = plcs.stream()
                .anyMatch(p -> p != plcEnEdicion && nombre.equalsIgnoreCase(String.valueOf(p.get("nombre"))));
        if (nombreDuplicado) {
            mostrarError("Ya existe un PLC con ese nombre");
            return false;
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
        return true;
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

    // ================= Gateways =================

    private VerticalLayout crearPanelGateways() {
        VerticalLayout panel = new VerticalLayout();
        panel.setSizeFull();
        panel.setPadding(false);
        panel.setSpacing(false);

        Button nuevoBtn = new Button("Nuevo Gateway", VaadinIcon.PLUS.create(), e -> abrirDialogoGateway(null));
        nuevoBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout toolbar = new HorizontalLayout(nuevoBtn);
        toolbar.setWidthFull();
        toolbar.setJustifyContentMode(JustifyContentMode.END);
        toolbar.getStyle().set("padding", "8px 0");

        gatewaysGrid.addColumn(g -> g.get("nombre")).setHeader("Nombre").setAutoWidth(true);
        gatewaysGrid.addColumn(g -> g.get("ipAddress")).setHeader("IP").setAutoWidth(true);
        gatewaysGrid.addColumn(g -> g.get("descripcion")).setHeader("Descripción").setAutoWidth(true);
        gatewaysGrid.addComponentColumn(this::crearAccionesGateway).setHeader("Acciones").setAutoWidth(true);
        gatewaysGrid.setSizeFull();

        panel.add(toolbar, gatewaysGrid);
        panel.setFlexGrow(1, gatewaysGrid);
        return panel;
    }

    private HorizontalLayout crearAccionesGateway(Map<String, Object> gateway) {
        Button editar = new Button("Editar", e -> abrirDialogoGateway(gateway));
        Button eliminar = new Button("Eliminar", e -> eliminarGateway(gateway));
        eliminar.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        return new HorizontalLayout(editar, eliminar);
    }

    private void abrirDialogoGateway(Map<String, Object> gatewayEnEdicion) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(gatewayEnEdicion == null ? "Nuevo gateway" : "Editar: " + gatewayEnEdicion.get("nombre"));
        dialog.setWidth("360px");

        TextField nombreField = new TextField("Nombre");
        TextField ipField = new TextField("IP");
        TextField descripcionField = new TextField("Descripción");

        if (gatewayEnEdicion != null) {
            nombreField.setValue(String.valueOf(gatewayEnEdicion.getOrDefault("nombre", "")));
            nombreField.setReadOnly(true);
            ipField.setValue(String.valueOf(gatewayEnEdicion.getOrDefault("ipAddress", "")));
            descripcionField.setValue(String.valueOf(gatewayEnEdicion.getOrDefault("descripcion", "")));
        }

        FormLayout form = new FormLayout(nombreField, ipField, descripcionField);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1));
        dialog.add(form);

        Button cancelarBtn = new Button("Cancelar", e -> dialog.close());
        Button guardarBtn = new Button("Guardar", e -> {
            if (guardarGateway(gatewayEnEdicion, nombreField.getValue(), ipField.getValue(), descripcionField.getValue())) {
                dialog.close();
            }
        });
        guardarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(cancelarBtn, guardarBtn);

        dialog.open();
    }

    private boolean guardarGateway(Map<String, Object> gatewayEnEdicion, String nombre, String ip, String descripcion) {
        if (nombre == null || nombre.isBlank()) {
            mostrarError("El nombre del gateway es obligatorio");
            return false;
        }
        if (!esIpValida(ip)) {
            mostrarError("La IP no tiene un formato válido");
            return false;
        }
        boolean nombreDuplicado = gateways.stream()
                .anyMatch(g -> g != gatewayEnEdicion && nombre.equalsIgnoreCase(String.valueOf(g.get("nombre"))));
        if (nombreDuplicado) {
            mostrarError("Ya existe un gateway con ese nombre");
            return false;
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
        gateway.put("descripcion", descripcion);

        configLoaderService.savePLCsYGateways(plcs, gateways);
        Notification.show("Gateway guardado", 2500, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        cargarTodo();
        return true;
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

    private void cargarTodo() {
        lineas = configLoaderService.loadLineaIDConfig();
        plcs = configLoaderService.loadPLCConfig();
        gateways = configLoaderService.loadGatewayConfig();

        lineasGrid.setItems(lineas);
        plcsGrid.setItems(plcs);
        gatewaysGrid.setItems(gateways);
        lineasGrid.recalculateColumnWidths();
        plcsGrid.recalculateColumnWidths();
        gatewaysGrid.recalculateColumnWidths();
    }
}
