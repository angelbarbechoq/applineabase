package com.example.alarmas.ui;

import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.base.ui.ChartsView;
import com.example.base.ui.MainLayout;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Historial completo de alarmas (activas + resueltas), filtrable por
 * línea/máquina. Las alarmas nunca se borran de aquí; solo AlarmasHistorialView
 * (/alarmas) deja de mostrarlas cuando se resuelven.
 */
@PageTitle("Historial de Alarmas | LineaBase")
@Route(value = "alarmas/historial", layout = MainLayout.class)
@PermitAll
public class AlarmasHistorialCompletoView extends VerticalLayout implements BeforeEnterObserver {

    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private static final String TODAS = "Todas las líneas";

    private final AlarmaEventoRepository eventoRepository;
    private final LineaAccessService lineaAccessService;
    private final Grid<AlarmaEvento> grid = new Grid<>(AlarmaEvento.class, false);
    private final ComboBox<String> lineaFiltro = new ComboBox<>("Línea/Máquina");

    public AlarmasHistorialCompletoView(AlarmaEventoRepository eventoRepository, LineaAccessService lineaAccessService,
                                         ConfigLoaderService configLoaderService) {
        this.eventoRepository = eventoRepository;
        this.lineaAccessService = lineaAccessService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Historial de Alarmas"));

        List<String> lineas = configLoaderService.loadLineaIDConfig().stream()
                .map(l -> (String) l.get("lineaMaquina"))
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        lineaFiltro.setItems(concatenarTodas(lineas));
        lineaFiltro.setValue(TODAS);
        lineaFiltro.setWidth("220px");
        lineaFiltro.addValueChangeListener(e -> refrescarGrid());

        Button refrescarBtn = new Button("Refrescar", e -> refrescarGrid());
        refrescarBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout filtros = new HorizontalLayout(lineaFiltro, refrescarBtn);
        filtros.setAlignItems(Alignment.END);

        grid.addColumn(e -> e.getFechaInicio() == null ? "-" : e.getFechaInicio().format(FORMATO_FECHA))
                .setHeader("Inicio").setAutoWidth(true).setSortable(true);
        grid.addColumn(AlarmaEvento::getLineaMaquina).setHeader("Línea/Máquina").setAutoWidth(true).setSortable(true);
        grid.addColumn(AlarmaEvento::getTipoAlarma).setHeader("Tipo").setAutoWidth(true).setSortable(true);
        grid.addColumn(e -> e.isActiva() ? "Activa" : "Resuelta").setHeader("Estado").setAutoWidth(true);
        grid.addColumn(AlarmaEvento::getMensaje).setHeader("Detalle").setFlexGrow(1);
        grid.setSizeFull();

        add(filtros, grid);
        setFlexGrow(1, grid);

        refrescarGrid();
    }

    private List<String> concatenarTodas(List<String> lineas) {
        List<String> items = new java.util.ArrayList<>();
        items.add(TODAS);
        items.addAll(lineas);
        return items;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!lineaAccessService.puedeVerAlarmas()) {
            Notification.show("No tienes permiso para ver las alarmas", 3000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            event.forwardTo(ChartsView.class);
        }
    }

    private void refrescarGrid() {
        String linea = lineaFiltro.getValue();
        if (linea == null || TODAS.equals(linea)) {
            grid.setItems(eventoRepository.findAllByOrderByFechaInicioDesc());
        } else {
            grid.setItems(eventoRepository.findByLineaMaquinaOrderByFechaInicioDesc(linea));
        }
    }
}
