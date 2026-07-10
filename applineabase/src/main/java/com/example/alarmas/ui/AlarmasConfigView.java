package com.example.alarmas.ui;

import com.example.alarmas.model.AlarmaConfig;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaConfigRepository;
import com.example.base.ui.MainLayout;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

/**
 * Configuración de umbrales de alarma por línea. Solo el ADMIN puede editar.
 * Cada fila fue creada por AlarmaConfigSeeder con valores por defecto; aquí solo
 * se ajustan (no se crean/eliminan filas, para mantener el catálogo consistente
 * con linea-id-config.json).
 */
@PageTitle("Configuración de Alarmas | LineaBase")
@Route(value = "alarmas/config", layout = MainLayout.class)
@RolesAllowed("ADMIN")
public class AlarmasConfigView extends VerticalLayout {

    private final AlarmaConfigRepository configRepository;
    private final Grid<AlarmaConfig> grid = new Grid<>(AlarmaConfig.class, false);

    private final Span formTitle = new Span("Selecciona una regla para editarla");
    private final Checkbox habilitadaCheckbox = new Checkbox("Habilitada");
    private final NumberField epsilonField = new NumberField("Epsilon KWh (~0)");
    private final NumberField ventanaField = new NumberField("Ventana (ciclos)");
    private final NumberField minutosField = new NumberField("Máx. minutos encendido");
    private final NumberField temperaturaField = new NumberField("Temperatura máxima (°C)");
    private final NumberField factorPotenciaField = new NumberField("Factor de potencia mínimo");

    private AlarmaConfig configEnEdicion;

    public AlarmasConfigView(AlarmaConfigRepository configRepository) {
        this.configRepository = configRepository;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Configuración de Alarmas"));

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

        Button guardarBtn = new Button("Guardar", e -> guardar());
        guardarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        guardarBtn.setEnabled(false);

        HorizontalLayout formLayout = new HorizontalLayout(
                habilitadaCheckbox, epsilonField, ventanaField, minutosField, temperaturaField, factorPotenciaField, guardarBtn
        );
        formLayout.setAlignItems(Alignment.END);
        formLayout.getStyle().set("flex-wrap", "wrap");

        grid.asSingleSelect().addValueChangeListener(e -> guardarBtn.setEnabled(e.getValue() != null));

        add(formTitle, formLayout, grid);
        setFlexGrow(1, grid);

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
            formTitle.setText("Selecciona una regla para editarla");
            return;
        }
        formTitle.setText("Editando: " + config.getLineaMaquina() + " / " + config.getTipoAlarma());
        habilitadaCheckbox.setValue(config.isHabilitada());
        epsilonField.setValue(config.getEpsilonKwh());
        ventanaField.setValue(config.getVentanaCiclos() == null ? null : config.getVentanaCiclos().doubleValue());
        minutosField.setValue(config.getMinutosMaxEncendido() == null ? null : config.getMinutosMaxEncendido().doubleValue());
        temperaturaField.setValue(config.getTemperaturaMaxima());
        factorPotenciaField.setValue(config.getFactorPotenciaMinimo());

        boolean esDetencionOCiclo = config.getTipoAlarma() == TipoAlarma.DETENCION || config.getTipoAlarma() == TipoAlarma.CICLO_COMPRESOR;
        epsilonField.setVisible(esDetencionOCiclo);
        ventanaField.setVisible(config.getTipoAlarma() == TipoAlarma.DETENCION);
        minutosField.setVisible(config.getTipoAlarma() == TipoAlarma.CICLO_COMPRESOR);
        temperaturaField.setVisible(config.getTipoAlarma() == TipoAlarma.TEMPERATURA_ALTA);
        factorPotenciaField.setVisible(config.getTipoAlarma() == TipoAlarma.FACTOR_POTENCIA_BAJO);
    }

    private void guardar() {
        if (configEnEdicion == null) {
            return;
        }
        configEnEdicion.setHabilitada(habilitadaCheckbox.getValue());
        configEnEdicion.setEpsilonKwh(epsilonField.getValue());
        configEnEdicion.setVentanaCiclos(ventanaField.getValue() == null ? null : ventanaField.getValue().intValue());
        configEnEdicion.setMinutosMaxEncendido(minutosField.getValue() == null ? null : minutosField.getValue().intValue());
        configEnEdicion.setTemperaturaMaxima(temperaturaField.getValue());
        configEnEdicion.setFactorPotenciaMinimo(factorPotenciaField.getValue());

        configRepository.save(configEnEdicion);
        Notification.show("Configuración guardada", 2500, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);

        refrescarGrid();
    }

    private void refrescarGrid() {
        grid.setItems(configRepository.findAllByOrderByLineaMaquinaAsc());
    }
}
