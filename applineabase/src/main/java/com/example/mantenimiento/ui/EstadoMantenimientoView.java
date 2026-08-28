package com.example.mantenimiento.ui;

import com.example.base.ui.MainLayout;
import com.example.mantenimiento.model.EstadoPlanDTO;
import com.example.mantenimiento.service.MantenimientoService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

/**
 * Reporte de solo lectura: estado actual (al dia / proximo a vencer / vencido) de cada plan de
 * mantenimiento por horas. Separado de MantenimientoView a proposito -- esa vista es para
 * REGISTRAR una tarea (formulario + historial), esta es para MIRAR que esta por vencer, son
 * dos tareas distintas y mezclarlas en una sola pantalla tapaba el formulario con la tabla de
 * estado (14 lineas siempre visibles).
 */
@PageTitle("Estado Barril y Tornillo | LineaBase")
@Route(value = "reportes/mantenimiento", layout = MainLayout.class)
@PermitAll
public class EstadoMantenimientoView extends VerticalLayout implements BeforeEnterObserver {

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
    private final Grid<EstadoPlanDTO> grid = new Grid<>(EstadoPlanDTO.class, false);

    public EstadoMantenimientoView(MantenimientoService mantenimientoService, LineaAccessService lineaAccessService) {
        this.mantenimientoService = mantenimientoService;
        this.lineaAccessService = lineaAccessService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Estado Barril y Tornillo"));

        grid.addColumn(e -> e.plan().getTag()).setHeader("TAG").setAutoWidth(true).setSortable(true);
        grid.addColumn(e -> e.plan().getTarea()).setHeader("Tarea").setAutoWidth(true).setSortable(true);
        grid.addColumn(this::formatearHorasRestantes).setHeader("Horas restantes").setAutoWidth(true).setSortable(true);
        grid.addComponentColumn(this::estadoBadge).setHeader("Estado").setAutoWidth(true);
        grid.setSizeFull();

        add(grid);
        setFlexGrow(1, grid);

        refrescarGrid();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        AccesoMantenimiento.verificar(event, lineaAccessService);
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

    private void refrescarGrid() {
        grid.setItems(mantenimientoService.listarEstadoPlanes());
    }
}
