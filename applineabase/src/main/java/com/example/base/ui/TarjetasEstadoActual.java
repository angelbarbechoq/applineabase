package com.example.base.ui;

import com.example.base.model.GraficaModel;
import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * Franja de valores en vivo (KWh/VAB/VAC/etc.) y tarjeta compartida de último click
 * (MainLayout.getUltimoClickCard()) — ChartsView e HistoricoView muestran exactamente lo mismo
 * en el mismo lugar, así que esta lógica vive acá una sola vez en vez de repetida en las dos vistas.
 */
final class TarjetasEstadoActual {

    private TarjetasEstadoActual() {
    }

    /** Carga y muestra la franja de valores en vivo para la máquina dada, o la oculta si falla. */
    static void cargarDatosActuales(LineaAccessService lineaAccessService, PLCDataQueryService plcDataQueryService,
                                     String maquina, Div card) {
        try {
            if (!lineaAccessService.tieneAccesoAMaquina(maquina)) {
                card.setVisible(false);
                return;
            }
            Map<String, Object> datosVIP = plcDataQueryService.getLatestVIPDataByMaquina(maquina);
            Map<String, Object> datosKWh = plcDataQueryService.getLatestKWhDataByMaquina(maquina);

            if (!datosVIP.containsKey("error") && !datosKWh.containsKey("error")) {
                card.getElement().setProperty("innerHTML",
                        GraficaModel.construirHtmlValoresActuales(datosVIP, datosKWh));
                card.setVisible(true);
            } else {
                card.setVisible(false);
            }
        } catch (Exception e) {
            card.setVisible(false);
        }
    }

    /** Muestra/oculta la tarjeta compartida de último click según si la vista está adjunta. */
    static void mostrarUltimoClickCard(UI ui, boolean visible) {
        ui.getChildren()
                .filter(c -> c instanceof MainLayout)
                .findFirst()
                .ifPresent(layout -> ((MainLayout) layout).getUltimoClickCard().setVisible(visible));
    }

    /** Resetea la tarjeta compartida de último click a ceros (doble-click en el gráfico). */
    static void limpiarUltimoClick(MainLayout layout) {
        layout.getUltimoClickCard().getElement().setProperty("innerHTML",
                GraficaModel.construirHtmlUltimoClick("00-00-00", "00:00:00", "0.0"));
    }

    /**
     * Calcula fecha/hora/KWh para el timestamp clickeado y actualiza la tarjeta compartida de
     * último click. graficaActiva puede ser null; si no lo es, también anota el click ahí
     * (registrarClick), igual que hacía cada vista por su cuenta antes de esta extracción.
     */
    static void actualizarUltimoClick(LineaAccessService lineaAccessService, PLCDataQueryService plcDataQueryService,
                                       GraficaModel graficaActiva, String maquina, MainLayout layout, long timestamp) {
        if (graficaActiva != null) {
            graficaActiva.registrarClick(timestamp);
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
        Date fecha = new Date(timestamp);
        String fechaStr = dateFormat.format(fecha);
        String horaStr = timeFormat.format(fecha);

        String valorStr = "";
        try {
            if (maquina != null && lineaAccessService.tieneAccesoAMaquina(maquina)) {
                Map<String, Object> data = plcDataQueryService.getKWhByFechaExacta(maquina, fechaStr + " " + horaStr);
                if (data.containsKey("kwh")) {
                    valorStr = String.format("%.2f", data.get("kwh"));
                }
            }
        } catch (Exception e) {
            valorStr = "Error";
        }

        layout.getUltimoClickCard().getElement().setProperty("innerHTML",
                GraficaModel.construirHtmlUltimoClick(fechaStr, horaStr, valorStr));
    }
}
