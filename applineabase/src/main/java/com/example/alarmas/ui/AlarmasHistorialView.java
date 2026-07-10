package com.example.alarmas.ui;

import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.base.ui.ChartsView;
import com.example.base.ui.MainLayout;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.format.DateTimeFormatter;

/**
 * Historial de alarmas disparadas. Visible solo para ADMIN y usuarios de la zona
 * Mantenimiento (LineaAccessService.puedeVerAlarmas), ya que las alarmas son de
 * planta completa y no están filtradas por zona como el resto de las vistas.
 */
@PageTitle("Alarmas | LineaBase")
@Route(value = "alarmas", layout = MainLayout.class)
@PermitAll
public class AlarmasHistorialView extends VerticalLayout implements BeforeEnterObserver {

    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private final AlarmaEventoRepository eventoRepository;
    private final LineaAccessService lineaAccessService;
    private final Grid<AlarmaEvento> grid = new Grid<>(AlarmaEvento.class, false);

    public AlarmasHistorialView(AlarmaEventoRepository eventoRepository, LineaAccessService lineaAccessService) {
        this.eventoRepository = eventoRepository;
        this.lineaAccessService = lineaAccessService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Historial de Alarmas"));

        Button refrescarBtn = new Button("Refrescar", e -> refrescarGrid());
        refrescarBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        add(refrescarBtn);

        grid.addColumn(e -> e.getFechaInicio() == null ? "-" : e.getFechaInicio().format(FORMATO_FECHA))
                .setHeader("Inicio").setAutoWidth(true).setSortable(true);
        grid.addColumn(e -> e.getFechaFin() == null ? (e.isActiva() ? "En curso" : "-") : e.getFechaFin().format(FORMATO_FECHA))
                .setHeader("Fin").setAutoWidth(true);
        grid.addColumn(AlarmaEvento::getLineaMaquina).setHeader("Línea/Máquina").setAutoWidth(true).setSortable(true);
        grid.addColumn(AlarmaEvento::getTipoAlarma).setHeader("Tipo").setAutoWidth(true).setSortable(true);
        grid.addColumn(e -> e.isActiva() ? "Activa" : "Resuelta").setHeader("Estado").setAutoWidth(true);
        grid.addColumn(AlarmaEvento::getMensaje).setHeader("Detalle").setFlexGrow(1);
        grid.setSizeFull();

        add(grid);
        setFlexGrow(1, grid);

        refrescarGrid();
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
        grid.setItems(eventoRepository.findAllByOrderByFechaInicioDesc());
    }
}
