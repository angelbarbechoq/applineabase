package com.example.mantenimiento.ui;

import com.example.base.ui.MainLayout;
import com.example.base.ui.NotificacionesUtil;
import com.example.mantenimiento.model.EstadoPlanDTO;
import com.example.mantenimiento.model.StockBarrilTornillo;
import com.example.mantenimiento.service.MantenimientoService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.util.List;

/**
 * Reporte de Barril y Tornillo, dos pestañas separadas a proposito de MantenimientoView (esa
 * vista es para REGISTRAR una tarea, esta es solo para MIRAR):
 * - Estado: al dia / proximo a vencer / vencido de cada plan por horas.
 * - Stock: cantidad de repuestos por modelo + sistema de refrigeracion.
 */
@PageTitle("Barril y Tornillo | LineaBase")
@Route(value = "reportes/mantenimiento", layout = MainLayout.class)
@PermitAll
public class EstadoMantenimientoView extends VerticalLayout implements BeforeEnterObserver {

    private static final String[] MODELOS_CONOCIDOS = {"LSE-65", "LSE-80", "LSE-92", "LSDP-75", "CM-80", "CM-92"};
    private static final String[] SISTEMAS_REFRIGERACION = {"Agua", "Aceite"};

    private static final String FONDO_VERDE = "#d4edda";
    private static final String TEXTO_VERDE = "#155724";
    private static final String FONDO_AMARILLO = "#fff3cd";
    private static final String TEXTO_AMARILLO = "#856404";
    private static final String FONDO_ROJO = "#f8d7da";
    private static final String TEXTO_ROJO = "#721c24";
    private static final String FONDO_GRIS = "#e2e3e5";
    private static final String TEXTO_GRIS = "#383d41";

    private final MantenimientoService mantenimientoService;
    private final LineaAccessService lineaAccessService;

    private final Grid<EstadoPlanDTO> estadoGrid = new Grid<>(EstadoPlanDTO.class, false);

    private final Grid<StockBarrilTornillo> stockGrid = new Grid<>(StockBarrilTornillo.class, false);
    private final ComboBox<String> modeloField = new ComboBox<>("Modelo");
    private final ComboBox<String> sistemaField = new ComboBox<>("Sistema de refrigeracion");
    private final IntegerField cantidadField = new IntegerField("Cantidad");
    private final TextField observacionField = new TextField("Observacion");
    private final Button guardarStockBtn = new Button("Guardar");
    private final Button nuevoStockBtn = new Button("Nuevo");

    private StockBarrilTornillo stockEnEdicion;

    public EstadoMantenimientoView(MantenimientoService mantenimientoService, LineaAccessService lineaAccessService) {
        this.mantenimientoService = mantenimientoService;
        this.lineaAccessService = lineaAccessService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Barril y Tornillo"));

        TabSheet tabSheet = new TabSheet();
        tabSheet.setSizeFull();
        tabSheet.add("Estado", crearPanelEstado());
        tabSheet.add("Stock", crearPanelStock());

        add(tabSheet);
        setFlexGrow(1, tabSheet);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        AccesoMantenimiento.verificar(event, lineaAccessService);
    }

    // ================= Estado =================

    private VerticalLayout crearPanelEstado() {
        VerticalLayout panel = new VerticalLayout();
        panel.setSizeFull();
        panel.setPadding(false);

        estadoGrid.addColumn(e -> e.plan().getTag()).setHeader("TAG").setAutoWidth(true).setSortable(true);
        estadoGrid.addColumn(e -> e.plan().getTarea()).setHeader("Tarea").setAutoWidth(true).setSortable(true);
        estadoGrid.addColumn(this::formatearHorasRestantes).setHeader("Horas restantes").setAutoWidth(true).setSortable(true);
        estadoGrid.addComponentColumn(this::estadoBadge).setHeader("Estado").setAutoWidth(true);
        estadoGrid.setSizeFull();

        panel.add(estadoGrid);
        panel.setFlexGrow(1, estadoGrid);

        estadoGrid.setItems(mantenimientoService.listarEstadoPlanes());
        return panel;
    }

    private String formatearHorasRestantes(EstadoPlanDTO estado) {
        return estado.sinRegistro() ? "-" : String.format("%.1f", estado.horasRestantes());
    }

    /** Colores puestos a mano (no via theme variant "badge") porque el tema del proyecto es
     * Aura, no Lumo, y el badge de Lumo no se pinta bajo Aura -- con estilo directo se ve
     * igual sin importar el tema activo. */
    private Span estadoBadge(EstadoPlanDTO estado) {
        if (estado.sinRegistro()) {
            return badge("SIN REGISTRO", FONDO_GRIS, TEXTO_GRIS);
        }
        if (estado.vencido()) {
            return badge("VENCIDO", FONDO_ROJO, TEXTO_ROJO);
        }
        if (estado.proximoAVencer()) {
            return badge("PROXIMO A VENCER", FONDO_AMARILLO, TEXTO_AMARILLO);
        }
        return badge("AL DIA", FONDO_VERDE, TEXTO_VERDE);
    }

    private Span badge(String texto, String fondo, String color) {
        Span badge = new Span(texto);
        badge.getStyle()
                .set("background-color", fondo)
                .set("color", color)
                .set("padding", "2px 10px")
                .set("border-radius", "12px")
                .set("font-weight", "600")
                .set("font-size", "0.85em");
        return badge;
    }

    // ================= Stock =================

    private VerticalLayout crearPanelStock() {
        VerticalLayout panel = new VerticalLayout();
        panel.setSizeFull();
        panel.setPadding(false);

        stockGrid.addColumn(StockBarrilTornillo::getModelo).setHeader("Modelo").setAutoWidth(true).setSortable(true);
        stockGrid.addColumn(StockBarrilTornillo::geometriaTornillo).setHeader("Geometria").setAutoWidth(true);
        stockGrid.addColumn(StockBarrilTornillo::getSistemaRefrigeracion).setHeader("Refrigeracion").setAutoWidth(true).setSortable(true);
        stockGrid.addColumn(StockBarrilTornillo::getCantidad).setHeader("Cantidad").setAutoWidth(true).setSortable(true);
        stockGrid.addColumn(StockBarrilTornillo::getObservacion).setHeader("Observacion").setAutoWidth(true).setFlexGrow(1);
        stockGrid.setSizeFull();

        if (lineaAccessService.esAdmin()) {
            stockGrid.addComponentColumn(this::botonEliminarStock).setHeader("").setAutoWidth(true);
            stockGrid.asSingleSelect().addValueChangeListener(e -> cargarStockEnFormulario(e.getValue()));

            modeloField.setItems(MODELOS_CONOCIDOS);
            modeloField.setAllowCustomValue(true);
            modeloField.addCustomValueSetListener(e -> modeloField.setValue(e.getDetail()));
            modeloField.setWidth("140px");

            sistemaField.setItems(SISTEMAS_REFRIGERACION);
            sistemaField.setAllowCustomValue(true);
            sistemaField.addCustomValueSetListener(e -> sistemaField.setValue(e.getDetail()));
            sistemaField.setWidth("180px");

            cantidadField.setWidth("110px");
            cantidadField.setMin(0);
            cantidadField.setStepButtonsVisible(true);

            observacionField.setWidth("320px");

            guardarStockBtn.addClickListener(e -> guardarStock());
            guardarStockBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

            nuevoStockBtn.addClickListener(e -> limpiarFormularioStock());
            nuevoStockBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

            HorizontalLayout formLayout = new HorizontalLayout(
                    modeloField, sistemaField, cantidadField, observacionField, guardarStockBtn, nuevoStockBtn
            );
            formLayout.setAlignItems(Alignment.END);
            formLayout.getStyle().set("flex-wrap", "wrap");

            panel.add(formLayout);
        }

        panel.add(stockGrid);
        panel.setFlexGrow(1, stockGrid);

        refrescarStockGrid();
        return panel;
    }

    private Button botonEliminarStock(StockBarrilTornillo stock) {
        Button boton = new Button(VaadinIcon.TRASH.create());
        boton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        boton.addClickListener(e -> {
            mantenimientoService.eliminarStockBarrilYTornillo(stock);
            refrescarStockGrid();
        });
        return boton;
    }

    private void cargarStockEnFormulario(StockBarrilTornillo stock) {
        stockEnEdicion = stock;
        if (stock == null) {
            limpiarFormularioStock();
            return;
        }
        modeloField.setValue(stock.getModelo());
        sistemaField.setValue(stock.getSistemaRefrigeracion());
        cantidadField.setValue(stock.getCantidad());
        observacionField.setValue(stock.getObservacion() == null ? "" : stock.getObservacion());
    }

    private void limpiarFormularioStock() {
        stockEnEdicion = null;
        stockGrid.asSingleSelect().clear();
        modeloField.clear();
        sistemaField.clear();
        cantidadField.clear();
        observacionField.clear();
    }

    private void guardarStock() {
        if (modeloField.getValue() == null || modeloField.getValue().isBlank()) {
            NotificacionesUtil.mostrarError("Selecciona el modelo");
            return;
        }
        if (sistemaField.getValue() == null || sistemaField.getValue().isBlank()) {
            NotificacionesUtil.mostrarError("Selecciona el sistema de refrigeracion");
            return;
        }
        StockBarrilTornillo stock = stockEnEdicion != null ? stockEnEdicion : new StockBarrilTornillo();
        stock.setModelo(modeloField.getValue());
        stock.setSistemaRefrigeracion(sistemaField.getValue());
        stock.setCantidad(cantidadField.getValue() == null ? 0 : cantidadField.getValue());
        stock.setObservacion(observacionField.getValue());
        mantenimientoService.guardarStockBarrilYTornillo(stock);

        Notification.show("Guardado", 2000, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        limpiarFormularioStock();
        refrescarStockGrid();
    }

    private void refrescarStockGrid() {
        List<StockBarrilTornillo> items = mantenimientoService.listarStockBarrilYTornillo();
        stockGrid.setItems(items);
    }
}
