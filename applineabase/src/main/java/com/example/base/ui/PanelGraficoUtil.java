package com.example.base.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

/**
 * Wrapper del Div de un grafico amCharts5 (id + ancho completo + alto 100%+flexGrow, en vez de
 * una altura fija en px) — usado por ChartsView e HistoricoView, para que un cambio de politica
 * de alto se aplique a todos los graficos de las dos vistas de una sola vez, en vez de tocar
 * cada panel por separado. Horometro tiene su propia copia (HorometroView.crearPanelGrupo, otro
 * paquete) y por ahora se deja aparte a proposito.
 */
final class PanelGraficoUtil {

    private PanelGraficoUtil() {
    }

    /**
     * Crea el Div del grafico y lo agrega a panel con flexGrow 1 — panel ya debe tener
     * setSizeFull()/setPadding(false) hecho por el llamador, que agrega sus propios elementos
     * (selector, mensaje de estado, etc.) antes de llamar esto. Con un tamaño fijo, si la
     * ventana del navegador es mas baja que ese valor el contenido se pasa del alto disponible
     * y aparece la barra de scroll vertical de la pagina, en vez de que el grafico simplemente
     * se achique junto con la ventana.
     */
    static Div agregarDivGrafico(VerticalLayout panel, String containerId) {
        Div chartDiv = new Div();
        chartDiv.setId(containerId);
        chartDiv.setWidthFull();
        chartDiv.setHeight("100%");
        panel.add(chartDiv);
        panel.setFlexGrow(1, chartDiv);
        return chartDiv;
    }

    /**
     * Panel de grafico standalone (VerticalLayout sizeFull + el Div de arriba), para cuando el
     * grafico es todo el contenido de la pestaña, sin nada mas arriba (ver agregarDivGrafico
     * para el caso con selector/mensaje propio, como las pestañas de ChartsView).
     *
     * tablaLateral (solo ADMIN en Historico, null para el resto) queda a la derecha del grafico
     * en un ancho fijo — el grafico se lleva el espacio que sobra (flexGrow 1), la tabla no se
     * achica (flexShrink 0). Sin tabla, devuelve el panel del grafico solo, sin la fila extra
     * alrededor.
     */
    static Component crearPanelGrafico(String containerId, Component tablaLateral) {
        VerticalLayout panel = new VerticalLayout();
        panel.setSizeFull();
        panel.setPadding(false);
        agregarDivGrafico(panel, containerId);

        if (tablaLateral == null) {
            return panel;
        }

        HorizontalLayout fila = new HorizontalLayout(panel, tablaLateral);
        fila.setSizeFull();
        fila.setPadding(false);
        fila.setSpacing(true);
        fila.setFlexGrow(1, panel);
        fila.setFlexGrow(0, tablaLateral);
        tablaLateral.getElement().getStyle().set("width", "320px");
        tablaLateral.getElement().getStyle().set("flex-shrink", "0");
        return fila;
    }
}
