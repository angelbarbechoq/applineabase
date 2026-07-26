package com.example.alarmas.ui;

import com.example.alarmas.model.AlarmaEvento;
import com.example.dataacquisition.RutaArchivosEnergia;
import com.vaadin.flow.component.grid.Grid;

import java.time.format.DateTimeFormatter;

/**
 * Columnas de grilla compartidas por AlarmasHistorialView y AlarmasHistorialCompletoView —
 * ambas muestran Inicio/Línea/Tipo/Detalle igual; la única diferencia es que el historial
 * completo agrega una columna "Estado" entre Tipo y Detalle, por eso quedan como dos llamadas
 * separadas en vez de un solo método que arme la grilla completa.
 */
final class GrillaAlarmaEventoUtil {

    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);

    private GrillaAlarmaEventoUtil() {
    }

    static void agregarColumnasInicioLineaTipo(Grid<AlarmaEvento> grid) {
        grid.addColumn(e -> e.getFechaInicio() == null ? "-" : e.getFechaInicio().format(FORMATO_FECHA))
                .setHeader("Inicio").setAutoWidth(true).setSortable(true);
        grid.addColumn(AlarmaEvento::getLineaMaquina).setHeader("Línea/Máquina").setAutoWidth(true).setSortable(true);
        grid.addColumn(AlarmaEvento::getTipoAlarma).setHeader("Tipo").setAutoWidth(true).setSortable(true);
    }

    static void agregarColumnaDetalle(Grid<AlarmaEvento> grid) {
        grid.addColumn(AlarmaEvento::getMensaje).setHeader("Detalle").setFlexGrow(1);
    }
}
