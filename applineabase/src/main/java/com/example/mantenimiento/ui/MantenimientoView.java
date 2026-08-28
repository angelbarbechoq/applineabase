package com.example.mantenimiento.ui;

import com.example.base.ui.MainLayout;
import com.example.base.ui.NotificacionesUtil;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.mantenimiento.model.EquipoTag;
import com.example.mantenimiento.model.ItemTag;
import com.example.mantenimiento.model.LineaTag;
import com.example.mantenimiento.model.MantenimientoRealizado;
import com.example.mantenimiento.model.PlanMantenimiento;
import com.example.mantenimiento.service.MantenimientoService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
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

        tareaField.setItems(mantenimientoService.catalogoTareas());
        tareaField.setAllowCustomValue(true);
        tareaField.addCustomValueSetListener(e -> tareaField.setValue(e.getDetail()));
        tareaField.setWidth("200px");

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

        HorizontalLayout formLayout = new HorizontalLayout(
                tareaField, fechaField, lineaMaquinaCombo, equipoCombo, itemCombo,
                numeroOtField, tecnicoField, horometroField, registrarBtn
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
        mantenimientoService.registrarMantenimientoRealizado(
                plan.get(), fechaField.getValue(), tareaField.getValue(), horometroField.getValue(),
                numeroOtField.getValue(), tecnicoField.getValue(), null);
        Notification.show("Tarea registrada", 2500, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        limpiarFormulario();
        refrescarGrid();
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
