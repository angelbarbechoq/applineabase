package com.example.mantenimiento.ui;

import com.example.base.ui.MainLayout;
import com.example.base.ui.NotificacionesUtil;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.mantenimiento.model.EquipoTag;
import com.example.mantenimiento.model.ItemTag;
import com.example.mantenimiento.model.LineaTag;
import com.example.mantenimiento.model.MantenimientoRealizado;
import com.example.mantenimiento.model.PlanMantenimiento;
import com.example.mantenimiento.model.StockBarrilTornillo;
import com.example.mantenimiento.service.MantenimientoService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Vista operativa: log de tareas de mantenimiento preventivo ya ejecutadas. No valida reglas
 * de negocio, solo registra lo que paso en planta -- formulario arriba con los datos de la
 * tarea, tabla abajo con el historial completo, la mas reciente primero. El TAG se elige con
 * la misma cascada Linea/Equipo/Item que Configuracion, y a partir de el se resuelve solo el
 * plan/intervalo que corresponde.
 */
@PageTitle("Mantenimiento Preventivo | LineaBase")
@Route(value = "mantenimiento", layout = MainLayout.class)
@PermitAll
public class MantenimientoView extends VerticalLayout implements BeforeEnterObserver {

    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String[] TAREAS_BARRIL_TORNILLO = {"Cambio", "Recalibracion"};

    private final MantenimientoService mantenimientoService;
    private final LineaAccessService lineaAccessService;
    private final List<LineaTag> catalogo;
    private final Grid<MantenimientoRealizado> grid = new Grid<>(MantenimientoRealizado.class, false);

    private final ComboBox<String> tareaField = new ComboBox<>("Tarea ejecutada");
    private final DateTimePicker fechaField = new DateTimePicker("Fecha y hora");
    private final ComboBox<String> lineaMaquinaCombo = new ComboBox<>("Linea / Maquina");
    private final ComboBox<EquipoTag> equipoCombo = new ComboBox<>("Equipo");
    private final ComboBox<ItemTag> itemCombo = new ComboBox<>("Elemento mantenible");
    private final TextField numeroOtField = new TextField("# OT");
    private final ComboBox<String> tecnicoField = new ComboBox<>("Tecnico");
    private final NumberField horometroField = new NumberField("Horometro");
    private final ComboBox<StockBarrilTornillo> stockField = new ComboBox<>("Barril/Tornillo instalado");
    private final Button registrarBtn = new Button("Registrar");

    public MantenimientoView(MantenimientoService mantenimientoService, ConfigLoaderService configLoaderService,
                              LineaAccessService lineaAccessService) {
        this.mantenimientoService = mantenimientoService;
        this.lineaAccessService = lineaAccessService;
        this.catalogo = mantenimientoService.catalogoLineasExtrusion();

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Mantenimiento Preventivo"));

        if (!lineaAccessService.esAdmin()) {
            add(new H3("Historial"));
            configurarColumnas();
            add(grid);
            setFlexGrow(1, grid);
            refrescarGrid();
            return;
        }

        tareaField.setItems(TAREAS_BARRIL_TORNILLO);
        tareaField.setWidth("160px");
        tareaField.addValueChangeListener(e -> actualizarVisibilidadStockField());

        stockField.setItemLabelGenerator(this::etiquetaStock);
        stockField.setWidth("220px");
        stockField.setVisible(false);

        fechaField.setMax(LocalDateTime.now());
        fechaField.setWidth("200px");
        fechaField.addValueChangeListener(e -> actualizarHorometroSugerido());

        lineaMaquinaCombo.setItems(configLoaderService.listarNombresLinea());
        lineaMaquinaCombo.setAllowCustomValue(true);
        lineaMaquinaCombo.setWidth("180px");
        lineaMaquinaCombo.addValueChangeListener(e -> onLineaCambiada(e.getValue()));

        equipoCombo.setItemLabelGenerator(EquipoTag::etiqueta);
        equipoCombo.setWidth("180px");
        equipoCombo.setClearButtonVisible(true);
        equipoCombo.addValueChangeListener(e -> onEquipoCambiado(e.getValue()));

        itemCombo.setItemLabelGenerator(ItemTag::etiqueta);
        itemCombo.setWidth("200px");
        itemCombo.setClearButtonVisible(true);
        itemCombo.addValueChangeListener(e -> actualizarHorometroSugerido());

        numeroOtField.setWidth("130px");
        tecnicoField.setItems(mantenimientoService.listarTecnicos());
        tecnicoField.setAllowCustomValue(true);
        tecnicoField.addCustomValueSetListener(e -> tecnicoField.setValue(e.getDetail()));
        tecnicoField.setWidth("170px");
        horometroField.setWidth("160px");

        registrarBtn.addClickListener(e -> registrar());
        registrarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        configurarColumnas();
        grid.addComponentColumn(this::botonEliminarHistorial).setHeader("").setAutoWidth(true);

        HorizontalLayout formLayout = new HorizontalLayout(
                tareaField, fechaField, lineaMaquinaCombo, equipoCombo, itemCombo,
                numeroOtField, tecnicoField, horometroField, stockField, registrarBtn
        );
        formLayout.setAlignItems(Alignment.END);
        formLayout.getStyle().set("flex-wrap", "wrap");

        add(formLayout, new H3("Historial"), grid);
        setFlexGrow(1, grid);

        limpiarFormulario();
        refrescarGrid();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        AccesoMantenimiento.verificar(event, lineaAccessService);
    }

    private void configurarColumnas() {
        grid.addColumn(MantenimientoRealizado::getTareaRealizada).setHeader("Tarea ejecutada").setAutoWidth(true);
        grid.addColumn(r -> r.getFechaRealizado().format(FORMATO_FECHA)).setHeader("Fecha y hora").setAutoWidth(true);
        grid.addColumn(r -> r.getPlanMantenimiento().getTag()).setHeader("TAG").setAutoWidth(true);
        grid.addColumn(MantenimientoRealizado::getNumeroOt).setHeader("# OT").setAutoWidth(true);
        grid.addColumn(MantenimientoRealizado::getTecnico).setHeader("Tecnico").setAutoWidth(true);
        grid.addColumn(r -> String.format("%.1f", r.getHorasAcumuladasEnMomento())).setHeader("Horometro").setAutoWidth(true);
        grid.addColumn(this::formatearHorasTranscurridas).setHeader("Horas transcurridas").setAutoWidth(true);
        grid.addColumn(this::formatearHorasFaltantes).setHeader("Horas faltantes para la recalibracion").setAutoWidth(true);
        grid.setSizeFull();
    }

    private Optional<LineaTag> lineaDelCatalogo(String lineaMaquina) {
        return catalogo.stream().filter(l -> l.lineaMaquina().equals(lineaMaquina)).findFirst();
    }

    private void onLineaCambiada(String lineaMaquina) {
        equipoCombo.clear();
        itemCombo.clear();
        Optional<LineaTag> linea = lineaMaquina == null ? Optional.empty() : lineaDelCatalogo(lineaMaquina);
        List<EquipoTag> equipos = new java.util.ArrayList<>(linea.map(LineaTag::equipos).orElse(List.of()));
        equipos.sort(Comparator.comparing(EquipoTag::etiqueta));
        equipoCombo.setItems(equipos);
        equipoCombo.setVisible(linea.isPresent());
        itemCombo.setVisible(false);
        actualizarHorometroSugerido();
    }

    private void onEquipoCambiado(EquipoTag equipo) {
        itemCombo.clear();
        List<ItemTag> items = new java.util.ArrayList<>(equipo == null ? List.of() : equipo.items());
        items.sort(Comparator.comparing(ItemTag::etiqueta));
        itemCombo.setItems(items);
        itemCombo.setVisible(equipo != null);
        actualizarHorometroSugerido();
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

    private void actualizarHorometroSugerido() {
        Optional<String> tag = tagActual();
        if (tag.isEmpty() || fechaField.getValue() == null) {
            return;
        }
        Optional<PlanMantenimiento> plan = mantenimientoService.planPorTag(tag.get());
        plan.ifPresent(p -> horometroField.setValue(
                mantenimientoService.horasEnFecha(p, fechaField.getValue())));
    }

    private void registrar() {
        Optional<String> tag = tagActual();
        if (tag.isEmpty()) {
            NotificacionesUtil.mostrarError("Selecciona el TAG (Linea/Equipo/Elemento)");
            return;
        }
        Optional<PlanMantenimiento> plan = mantenimientoService.planPorTag(tag.get());
        if (plan.isEmpty()) {
            NotificacionesUtil.mostrarError("No hay ningun plan configurado para ese TAG en Configuracion de Mantenimiento");
            return;
        }
        if (fechaField.getValue() == null) {
            NotificacionesUtil.mostrarError("Indica la fecha y hora en que se realizo la tarea");
            return;
        }
        if (horometroField.getValue() == null) {
            NotificacionesUtil.mostrarError("Indica el horometro (horas totales a la fecha de la tarea)");
            return;
        }
        boolean esCambio = esTareaCambio();
        if (esCambio && stockField.getValue() == null) {
            NotificacionesUtil.mostrarError("Selecciona que barril/tornillo del stock se instalo");
            return;
        }
        StockBarrilTornillo stockConsumido = esCambio ? stockField.getValue() : null;

        mantenimientoService.registrarMantenimientoRealizado(
                plan.get(), fechaField.getValue(), tareaField.getValue(), horometroField.getValue(),
                numeroOtField.getValue(), tecnicoField.getValue(), null, stockConsumido);

        // stockConsumido ya viene con la cantidad descontada aca (misma instancia que actualizo
        // el servicio), por eso el chequeo de "quedo en 0 o negativo" es DESPUES de guardar.
        boolean avisarStockBajo = esCambio && stockConsumido.getCantidad() <= 0;

        if (avisarStockBajo) {
            Notification.show("Tarea registrada -- ojo, el stock de " + etiquetaStock(stockConsumido) + " quedo en 0 o negativo",
                    4000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_CONTRAST);
        } else {
            Notification.show("Tarea registrada", 2500, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        }
        limpiarFormulario();
        refrescarGrid();
    }

    private boolean esTareaCambio() {
        return MantenimientoService.TAREA_CAMBIO.equalsIgnoreCase(tareaField.getValue());
    }

    private void actualizarVisibilidadStockField() {
        boolean esCambio = esTareaCambio();
        stockField.setVisible(esCambio);
        if (esCambio) {
            stockField.setItems(mantenimientoService.listarStockBarrilYTornillo());
        } else {
            stockField.clear();
        }
    }

    private String etiquetaStock(StockBarrilTornillo stock) {
        return stock.getModelo() + " / " + stock.getSistemaRefrigeracion() + " (" + stock.getCantidad() + " disp.)";
    }

    private Button botonEliminarHistorial(MantenimientoRealizado registro) {
        Button boton = new Button(VaadinIcon.TRASH.create());
        boton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        boton.addClickListener(e -> abrirDialogoEliminarHistorial(registro));
        return boton;
    }

    /** Si la tarea era un "Cambio" con stock consumido, borrar exige motivo y quien autoriza
     * el retorno de la pieza al stock -- si no, es solo una confirmacion simple. */
    private void abrirDialogoEliminarHistorial(MantenimientoRealizado registro) {
        Dialog dialog = new Dialog();
        if (registro.getStockConsumido() != null) {
            dialog.setHeaderTitle("Borrar Cambio y devolver pieza al stock");
            TextField motivoField = new TextField("Motivo del retorno");
            motivoField.setWidthFull();
            TextField autorizaField = new TextField("Autorizado por");
            autorizaField.setWidthFull();

            Button confirmar = new Button("Confirmar", e -> {
                try {
                    mantenimientoService.eliminarMantenimientoRealizado(registro, motivoField.getValue(), autorizaField.getValue());
                } catch (IllegalArgumentException ex) {
                    NotificacionesUtil.mostrarError(ex.getMessage());
                    return;
                }
                dialog.close();
                refrescarGrid();
                actualizarVisibilidadStockField();
            });
            confirmar.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
            Button cancelar = new Button("Cancelar", e -> dialog.close());

            VerticalLayout contenido = new VerticalLayout(motivoField, autorizaField);
            contenido.setPadding(false);
            dialog.add(contenido);
            dialog.getFooter().add(cancelar, confirmar);
        } else {
            dialog.setHeaderTitle("Borrar tarea");
            dialog.add(new Span("Esta accion no se puede deshacer. Confirmas?"));
            Button confirmar = new Button("Confirmar", e -> {
                mantenimientoService.eliminarMantenimientoRealizado(registro, null, null);
                dialog.close();
                refrescarGrid();
            });
            confirmar.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
            Button cancelar = new Button("Cancelar", e -> dialog.close());
            dialog.getFooter().add(cancelar, confirmar);
        }
        dialog.open();
    }

    private void limpiarFormulario() {
        tareaField.clear();
        fechaField.setValue(LocalDateTime.now());
        lineaMaquinaCombo.clear();
        equipoCombo.clear();
        equipoCombo.setVisible(false);
        itemCombo.clear();
        itemCombo.setVisible(false);
        numeroOtField.clear();
        tecnicoField.clear();
        horometroField.clear();
        stockField.clear();
        stockField.setVisible(false);
    }

    private String formatearHorasTranscurridas(MantenimientoRealizado registro) {
        double transcurridas = mantenimientoService.horasActuales(registro.getPlanMantenimiento())
                - registro.getHorasAcumuladasEnMomento();
        return String.format("%.1f", transcurridas);
    }

    private String formatearHorasFaltantes(MantenimientoRealizado registro) {
        double transcurridas = mantenimientoService.horasActuales(registro.getPlanMantenimiento())
                - registro.getHorasAcumuladasEnMomento();
        double faltantes = registro.getPlanMantenimiento().getIntervaloHoras() - transcurridas;
        return String.format("%.1f", faltantes);
    }

    private void refrescarGrid() {
        grid.setItems(mantenimientoService.listarHistorial());
    }
}
