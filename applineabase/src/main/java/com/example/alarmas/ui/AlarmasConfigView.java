package com.example.alarmas.ui;

import com.example.alarmas.model.AlarmaConfig;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaConfigRepository;
import com.example.base.ui.MainLayout;
import com.example.dataacquisition.service.ConfigLoaderService;
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
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Configuración de umbrales de alarma por línea. Solo el ADMIN puede editar,
 * crear o eliminar reglas. AlarmaConfigSeeder crea filas por defecto al primer
 * arranque para las líneas conocidas de linea-id-config.json, pero desde aquí
 * también se pueden agregar reglas nuevas (p. ej. una línea agregada después,
 * o un segundo tipo de alarma para una línea que ya tiene otro).
 */
@PageTitle("Configuración de Alarmas | LineaBase")
@Route(value = "alarmas/config", layout = MainLayout.class)
@RolesAllowed("ADMIN")
public class AlarmasConfigView extends VerticalLayout {

    private final AlarmaConfigRepository configRepository;
    private final Grid<AlarmaConfig> grid = new Grid<>(AlarmaConfig.class, false);

    private final Span formTitle = new Span("Selecciona una regla para editarla, o crea una nueva");
    private final ComboBox<String> lineaCombo = new ComboBox<>("Línea/Máquina");
    private final Select<TipoAlarma> tipoSelect = new Select<>();
    private final Checkbox habilitadaCheckbox = new Checkbox("Habilitada", true);
    private final NumberField epsilonField = new NumberField("Epsilon KWh (~0)");
    private final NumberField ventanaField = new NumberField("Ventana (ciclos)");
    private final NumberField minutosField = new NumberField("Máx. minutos encendido");
    private final NumberField temperaturaField = new NumberField("Temperatura máxima (°C)");
    private final NumberField factorPotenciaField = new NumberField("Factor de potencia mínimo");
    private final Button guardarBtn = new Button("Guardar");
    private final Button eliminarBtn = new Button("Eliminar");

    private AlarmaConfig configEnEdicion;

    public AlarmasConfigView(AlarmaConfigRepository configRepository, ConfigLoaderService configLoaderService) {
        this.configRepository = configRepository;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Configuración de Alarmas"));

        List<String> lineas = configLoaderService.loadLineaIDConfig().stream()
                .map(l -> (String) l.get("lineaMaquina"))
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        lineaCombo.setItems(lineas);
        lineaCombo.setAllowCustomValue(true);
        lineaCombo.setWidth("220px");

        tipoSelect.setLabel("Tipo de alarma");
        tipoSelect.setItems(TipoAlarma.values());
        tipoSelect.addValueChangeListener(e -> actualizarVisibilidadCampos(e.getValue()));

        grid.addColumn(AlarmaConfig::getLineaMaquina).setHeader("Línea/Máquina").setAutoWidth(true).setSortable(true);
        grid.addColumn(AlarmaConfig::getTipoAlarma).setHeader("Tipo").setAutoWidth(true).setSortable(true);
        grid.addColumn(c -> c.isHabilitada() ? "Sí" : "No").setHeader("Habilitada").setAutoWidth(true);
        grid.addColumn(this::resumenUmbral).setHeader("Umbral actual").setAutoWidth(true).setFlexGrow(1);
        grid.asSingleSelect().addValueChangeListener(e -> cargarEnFormulario(e.getValue()));
        grid.setSizeFull();

        epsilonField.setStep(0.001);
        temperaturaField.setStep(0.1);
        factorPotenciaField.setStep(0.01);
        for (NumberField field : new NumberField[]{epsilonField, ventanaField, minutosField, temperaturaField, factorPotenciaField}) {
            field.setWidth("190px");
        }

        guardarBtn.addClickListener(e -> guardar());
        guardarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button nuevaBtn = new Button("Nueva regla", e -> limpiarFormulario());
        nuevaBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        eliminarBtn.addClickListener(e -> eliminar());
        eliminarBtn.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        eliminarBtn.setEnabled(false);

        HorizontalLayout formLayout = new HorizontalLayout(
                lineaCombo, tipoSelect, habilitadaCheckbox, epsilonField, ventanaField, minutosField,
                temperaturaField, factorPotenciaField, guardarBtn, nuevaBtn, eliminarBtn
        );
        formLayout.setAlignItems(Alignment.END);
        formLayout.getStyle().set("flex-wrap", "wrap");

        add(formTitle, formLayout, grid);
        setFlexGrow(1, grid);

        limpiarFormulario();
        refrescarGrid();
    }

    private String resumenUmbral(AlarmaConfig c) {
        return switch (c.getTipoAlarma()) {
            case DETENCION -> String.format("epsilon=%.3f kWh, ventana=%d ciclos",
                    valor(c.getEpsilonKwh()), c.getVentanaCiclos() == null ? 0 : c.getVentanaCiclos());
            case CICLO_COMPRESOR -> String.format("epsilon=%.3f kWh, máx=%d min",
                    valor(c.getEpsilonKwh()), c.getMinutosMaxEncendido() == null ? 0 : c.getMinutosMaxEncendido());
            case TEMPERATURA_ALTA -> String.format("máx=%.1f°C", valor(c.getTemperaturaMaxima()));
            case FACTOR_POTENCIA_BAJO -> String.format("mín=%.3f", valor(c.getFactorPotenciaMinimo()));
        };
    }

    private double valor(Double v) {
        return v == null ? 0 : v;
    }

    private void cargarEnFormulario(AlarmaConfig config) {
        configEnEdicion = config;
        if (config == null) {
            limpiarFormulario();
            return;
        }
        formTitle.setText("Editando: " + config.getLineaMaquina() + " / " + config.getTipoAlarma());
        lineaCombo.setValue(config.getLineaMaquina());
        lineaCombo.setEnabled(false);
        tipoSelect.setValue(config.getTipoAlarma());
        tipoSelect.setEnabled(false);
        habilitadaCheckbox.setValue(config.isHabilitada());
        epsilonField.setValue(config.getEpsilonKwh());
        ventanaField.setValue(config.getVentanaCiclos() == null ? null : config.getVentanaCiclos().doubleValue());
        minutosField.setValue(config.getMinutosMaxEncendido() == null ? null : config.getMinutosMaxEncendido().doubleValue());
        temperaturaField.setValue(config.getTemperaturaMaxima());
        factorPotenciaField.setValue(config.getFactorPotenciaMinimo());
        actualizarVisibilidadCampos(config.getTipoAlarma());
        eliminarBtn.setEnabled(true);
    }

    private void actualizarVisibilidadCampos(TipoAlarma tipo) {
        boolean esDetencionOCiclo = tipo == TipoAlarma.DETENCION || tipo == TipoAlarma.CICLO_COMPRESOR;
        epsilonField.setVisible(esDetencionOCiclo);
        ventanaField.setVisible(tipo == TipoAlarma.DETENCION);
        minutosField.setVisible(tipo == TipoAlarma.CICLO_COMPRESOR);
        temperaturaField.setVisible(tipo == TipoAlarma.TEMPERATURA_ALTA);
        factorPotenciaField.setVisible(tipo == TipoAlarma.FACTOR_POTENCIA_BAJO);
    }

    private void limpiarFormulario() {
        configEnEdicion = null;
        formTitle.setText("Nueva regla");
        grid.asSingleSelect().clear();
        lineaCombo.clear();
        lineaCombo.setEnabled(true);
        tipoSelect.clear();
        tipoSelect.setEnabled(true);
        habilitadaCheckbox.setValue(true);
        epsilonField.clear();
        ventanaField.clear();
        minutosField.clear();
        temperaturaField.clear();
        factorPotenciaField.clear();
        for (NumberField field : new NumberField[]{epsilonField, ventanaField, minutosField, temperaturaField, factorPotenciaField}) {
            field.setVisible(false);
        }
        eliminarBtn.setEnabled(false);
    }

    private void guardar() {
        AlarmaConfig config = configEnEdicion;

        if (config == null) {
            String linea = lineaCombo.getValue();
            TipoAlarma tipo = tipoSelect.getValue();
            if (linea == null || linea.isBlank() || tipo == null) {
                mostrarError("Selecciona línea/máquina y tipo de alarma");
                return;
            }
            if (configRepository.existsByLineaMaquinaAndTipoAlarma(linea, tipo)) {
                mostrarError("Ya existe una regla de " + tipo + " para " + linea + "; selecciónala en la tabla para editarla");
                return;
            }
            config = new AlarmaConfig();
            config.setLineaMaquina(linea);
            config.setTipoAlarma(tipo);
        }

        config.setHabilitada(habilitadaCheckbox.getValue());
        config.setEpsilonKwh(epsilonField.getValue());
        config.setVentanaCiclos(ventanaField.getValue() == null ? null : ventanaField.getValue().intValue());
        config.setMinutosMaxEncendido(minutosField.getValue() == null ? null : minutosField.getValue().intValue());
        config.setTemperaturaMaxima(temperaturaField.getValue());
        config.setFactorPotenciaMinimo(factorPotenciaField.getValue());

        configRepository.save(config);
        Notification.show("Configuración guardada", 2500, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);

        limpiarFormulario();
        refrescarGrid();
    }

    private void eliminar() {
        if (configEnEdicion == null) {
            return;
        }
        configRepository.delete(configEnEdicion);
        Notification.show("Regla eliminada", 2500, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        limpiarFormulario();
        refrescarGrid();
    }

    private void mostrarError(String mensaje) {
        Notification.show(mensaje, 3000, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    private void refrescarGrid() {
        grid.setItems(configRepository.findAllByOrderByLineaMaquinaAsc());
    }
}
