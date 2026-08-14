package com.example.alarmas.ui;

import com.example.alarmas.model.AlarmaEvento;
import com.example.dataacquisition.RutaArchivosEnergia;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;

import java.time.format.DateTimeFormatter;

/**
 * Columnas de grilla compartidas por AlarmasHistorialView y AlarmasHistorialCompletoView —
 * ambas muestran Inicio/Línea/Tipo/Detalle igual; la única diferencia es que el historial
 * completo agrega una columna "Estado" entre Tipo y Detalle, por eso quedan como dos llamadas
 * separadas en vez de un solo método que arme la grilla completa.
 *
 * Todas las columnas salvo "Detalle" quedan con flexGrow=0 (ancho fijo) para que el espacio
 * sobrante de la grilla se lo lleve "Detalle", que es la única con flexGrow=1 — si no, Grid
 * les da flexGrow=1 a todas por defecto y "Detalle" termina compitiendo por espacio en vez de
 * quedarse con el resto.
 */
final class GrillaAlarmaEventoUtil {

    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);

    private GrillaAlarmaEventoUtil() {
    }

    static void agregarColumnasInicioLineaTipo(Grid<AlarmaEvento> grid) {
        grid.addColumn(e -> e.getFechaInicio() == null ? "-" : e.getFechaInicio().format(FORMATO_FECHA))
                .setHeader("Inicio").setWidth("190px").setFlexGrow(0).setSortable(true);
        grid.addColumn(AlarmaEvento::getLineaMaquina).setHeader("Línea/Máquina").setAutoWidth(true).setFlexGrow(0).setSortable(true);
        grid.addColumn(AlarmaEvento::getTipoAlarma).setHeader("Tipo").setAutoWidth(true).setFlexGrow(0).setSortable(true);
    }

    /** La fila se mantiene en una sola línea (sin wrap): si el mensaje no entra en el ancho
     * de la celda, esta queda con scroll horizontal propio en vez de cortarse con "…" o
     * agrandar la fila. */
    static void agregarColumnaDetalle(Grid<AlarmaEvento> grid) {
        grid.addComponentColumn(evento -> {
            Span mensaje = new Span(evento.getMensaje());
            mensaje.getStyle()
                    .set("display", "block")
                    .set("overflow-x", "auto")
                    .set("white-space", "nowrap");
            return mensaje;
        }).setHeader("Detalle").setFlexGrow(1);
    }

    static void agregarColumnaUrgente(Grid<AlarmaEvento> grid) {
        grid.addColumn(e -> e.isUrgente() ? "🔴 Urgente" : "").setHeader("").setAutoWidth(true).setFlexGrow(0);
    }
}
