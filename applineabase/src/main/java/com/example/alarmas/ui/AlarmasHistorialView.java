package com.example.alarmas.ui;

import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.base.ui.MainLayout;
import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import jakarta.annotation.security.PermitAll;

import java.time.format.DateTimeFormatter;

/**
 * Alarmas activas en este momento. Se quitan solas de la lista en cuanto se
 * resuelven (MainLayout ya deja el poll de Vaadin activo para quien puede ver
 * alarmas, así que esta vista se refresca sola sin que el usuario tenga que
 * recargar). Para ver el historial completo (activas + resueltas) está
 * AlarmasHistorialCompletoView en /alarmas/historial.
 */
@PageTitle("Alarmas Activas | LineaBase")
@Route(value = "alarmas", layout = MainLayout.class)
@PermitAll
public class AlarmasHistorialView extends VerticalLayout implements BeforeEnterObserver {

    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);

    private final AlarmaEventoRepository eventoRepository;
    private final LineaAccessService lineaAccessService;
    private final Grid<AlarmaEvento> grid = new Grid<>(AlarmaEvento.class, false);

    public AlarmasHistorialView(AlarmaEventoRepository eventoRepository, LineaAccessService lineaAccessService) {
        this.eventoRepository = eventoRepository;
        this.lineaAccessService = lineaAccessService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Alarmas Activas"));

        Button refrescarBtn = new Button("Refrescar", e -> refrescarGrid());
        refrescarBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        RouterLink historialLink = new RouterLink("Ver historial completo", AlarmasHistorialCompletoView.class);
        add(new HorizontalLayout(refrescarBtn, historialLink));

        grid.addColumn(e -> e.getFechaInicio() == null ? "-" : e.getFechaInicio().format(FORMATO_FECHA))
                .setHeader("Inicio").setAutoWidth(true).setSortable(true);
        grid.addColumn(AlarmaEvento::getLineaMaquina).setHeader("Línea/Máquina").setAutoWidth(true).setSortable(true);
        grid.addColumn(AlarmaEvento::getTipoAlarma).setHeader("Tipo").setAutoWidth(true).setSortable(true);
        grid.addColumn(AlarmaEvento::getMensaje).setHeader("Detalle").setFlexGrow(1);
        grid.setSizeFull();

        add(grid);
        setFlexGrow(1, grid);

        refrescarGrid();

        addAttachListener(e -> {
            UI ui = e.getUI();
            ui.addPollListener(pollEvent -> refrescarGrid());
        });
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        AccesoAlarmas.verificar(event, lineaAccessService);
    }

    private void refrescarGrid() {
        grid.setItems(eventoRepository.findByActivaTrueOrderByFechaInicioDesc());
    }
}
