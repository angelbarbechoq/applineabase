package com.example.base.ui;

import com.example.alarmas.model.AlarmaConfig;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaConfigRepository;
import com.example.alarmas.service.AlarmaEvaluatorService;
import com.example.base.model.GraficaModel;
import com.example.dataacquisition.MaquinasVirtuales;
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
                                     AlarmaConfigRepository alarmaConfigRepository, String maquina, Div card) {
        try {
            if (!lineaAccessService.tieneAccesoAMaquina(maquina)) {
                card.setVisible(false);
                return;
            }
            Map<String, Object> datosVIP = plcDataQueryService.getLatestVIPDataByMaquina(maquina);
            Map<String, Object> datosKWh = plcDataQueryService.getLatestKWhDataByMaquina(maquina);

            // Temperatura Agua/Ambiente y PF general se guardan con el mismo esquema de tabla que
            // el KWh normal, así que se leen con los mismos métodos de siempre — mismos nombres
            // de "máquina virtual" que usa ChartsView para sus pestañas Temperatura y PF general
            // (ver mostrarTemperatura/mostrarPFGeneral).
            boolean accesoTemperatura = lineaAccessService.tieneAccesoAMaquina(MaquinasVirtuales.TEMPERATURA_AGUA);
            Double temperaturaAgua = accesoTemperatura
                    ? extraerKwh(plcDataQueryService.getLatestKWhDataByMaquina(MaquinasVirtuales.TEMPERATURA_AGUA)) : null;
            Double temperaturaAmbiente = accesoTemperatura
                    ? extraerKwh(plcDataQueryService.getLatestKWhDataByMaquina(MaquinasVirtuales.TEMPERATURA_AMBIENTE)) : null;
            Double pfGeneral = lineaAccessService.tieneAccesoAMaquina(MaquinasVirtuales.KWH_PLANTA_1)
                    ? extraerPFGeneral(plcDataQueryService.getLatestVIPDataByMaquina(MaquinasVirtuales.KWH_PLANTA_1)) : null;
            double umbralPF = umbralPFMinimo(alarmaConfigRepository);

            mostrarDatosActuales(card, datosVIP, datosKWh, temperaturaAgua, temperaturaAmbiente, pfGeneral, umbralPF);
        } catch (Exception e) {
            card.setVisible(false);
        }
    }

    /**
     * Mismo umbral que usa el sistema de alarmas (AlarmaEvaluatorService) para KWhPlanta1 —
     * configurable por AlarmasConfigView, con el mismo valor por defecto si no hay regla.
     */
    private static double umbralPFMinimo(AlarmaConfigRepository alarmaConfigRepository) {
        return alarmaConfigRepository.findByLineaMaquinaAndTipoAlarma(MaquinasVirtuales.KWH_PLANTA_1, TipoAlarma.FACTOR_POTENCIA_BAJO)
                .map(AlarmaConfig::getFactorPotenciaMinimo)
                .filter(java.util.Objects::nonNull)
                .orElse(AlarmaEvaluatorService.FACTOR_POTENCIA_MIN_DEFAULT);
    }

    /** El "kwh" de estas máquinas virtuales guarda en realidad temperatura, según el caso. */
    private static Double extraerKwh(Map<String, Object> datos) {
        return datos.containsKey("kwh") ? ((Number) datos.get("kwh")).doubleValue() : null;
    }

    /**
     * KWhPlanta1 reporta el PF en el campo VIP "PF" (no en "kwh", que ahí es KWh real de esa
     * planta) y en negativo y escala de porcentaje (ej. -85.5) — mismo ajuste que ya usa
     * ChartsView.cargarPFGeneralChart: valor absoluto y dividido entre 100 para verlo en 0-1.
     */
    private static Double extraerPFGeneral(Map<String, Object> datosVIP) {
        Float pf = GraficaModel.toFloatAbs(datosVIP.get("PF"));
        return pf == null ? null : pf / 100.0;
    }

    private static void mostrarDatosActuales(Div card, Map<String, Object> datosVIP, Map<String, Object> datosKWh,
                                              Double temperaturaAgua, Double temperaturaAmbiente, Double pfGeneral,
                                              double umbralPFMinimo) {
        if (!datosVIP.containsKey("error") && !datosKWh.containsKey("error")) {
            card.getElement().setProperty("innerHTML",
                    GraficaModel.construirHtmlValoresActuales(datosVIP, datosKWh, temperaturaAgua, temperaturaAmbiente, pfGeneral, umbralPFMinimo));
            card.setVisible(true);
        } else {
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

    /** Fecha ("dd-MM-yyyy") y hora ("HH:mm:ss") de un timestamp, ya separadas como las necesita
     * GraficaModel.construirHtmlUltimoClick. */
    private record FechaHora(String fecha, String hora) {
    }

    /** Anota el click en graficaActiva (si no es null) y separa el timestamp en fecha/hora —
     * igual en ChartsView e HistoricoView, así que queda una sola vez acá. */
    private static FechaHora registrarClickYFormatear(GraficaModel graficaActiva, long timestamp) {
        if (graficaActiva != null) {
            graficaActiva.registrarClick(timestamp);
        }
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
        Date fecha = new Date(timestamp);
        return new FechaHora(dateFormat.format(fecha), timeFormat.format(fecha));
    }

    /**
     * Calcula fecha/hora/KWh para el timestamp clickeado y actualiza la tarjeta compartida de
     * último click. graficaActiva puede ser null; si no lo es, también anota el click ahí
     * (registrarClick), igual que hacía cada vista por su cuenta antes de esta extracción.
     */
    static void actualizarUltimoClick(LineaAccessService lineaAccessService, PLCDataQueryService plcDataQueryService,
                                       GraficaModel graficaActiva, String maquina, MainLayout layout, long timestamp) {
        FechaHora fechaHora = registrarClickYFormatear(graficaActiva, timestamp);
        String fechaStr = fechaHora.fecha();
        String horaStr = fechaHora.hora();

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

    /**
     * Variante de actualizarUltimoClick solo para Histórico: a diferencia de ChartsView (donde
     * el click siempre cae en "hoy"), acá el punto clickeado puede ser de cualquier día del
     * rango consultado, así que la búsqueda de KWh tiene que ir al archivo mensual
     * correspondiente (ver PLCDataQueryService.getKWhByFechaExactaHistorico), no al diario.
     * Además, a pedido, actualiza también la franja de valores completa (KWh/VAB/VAC/etc.) con
     * los valores de ESE momento en vez de los últimos en vivo.
     */
    static void actualizarUltimoClickHistorico(LineaAccessService lineaAccessService, PLCDataQueryService plcDataQueryService,
                                                GraficaModel graficaActiva, String maquina, MainLayout layout,
                                                Div franjaValores, long timestamp) {
        FechaHora fechaHora = registrarClickYFormatear(graficaActiva, timestamp);
        String fechaStr = fechaHora.fecha();
        String horaStr = fechaHora.hora();
        String fechaHoraStr = fechaStr + " " + horaStr;

        // Temperatura Agua/Ambiente y PF general son solo para Tiempo Real (ver
        // TarjetasEstadoActual.cargarDatosActuales) — acá van null a propósito, así que
        // construirHtmlValoresActuales las omite y la franja de Histórico queda igual que antes.
        String valorStr = "";
        if (maquina != null && lineaAccessService.tieneAccesoAMaquina(maquina)) {
            try {
                Map<String, Object> datosKWh = plcDataQueryService.getKWhByFechaExactaHistorico(maquina, fechaHoraStr);
                if (datosKWh.containsKey("kwh")) {
                    valorStr = String.format("%.2f", datosKWh.get("kwh"));
                }

                Map<String, Object> datosVIP = plcDataQueryService.getVIPByFechaExactaHistorico(maquina, fechaHoraStr);
                if (!datosVIP.containsKey("error") && !datosKWh.containsKey("error")) {
                    franjaValores.getElement().setProperty("innerHTML",
                            // pfGeneral va null (ver comentario arriba), así que el umbral no se usa acá.
                            GraficaModel.construirHtmlValoresActuales(datosVIP, datosKWh, null, null, null,
                                    AlarmaEvaluatorService.FACTOR_POTENCIA_MIN_DEFAULT));
                    franjaValores.setVisible(true);
                } else {
                    franjaValores.setVisible(false);
                }
            } catch (Exception e) {
                valorStr = "Error";
                franjaValores.setVisible(false);
            }
        } else {
            franjaValores.setVisible(false);
        }

        layout.getUltimoClickCard().getElement().setProperty("innerHTML",
                GraficaModel.construirHtmlUltimoClick(fechaStr, horaStr, valorStr));
    }

    /**
     * Variante de limpiarUltimoClick solo para Histórico: además de resetear la tarjeta de
     * último click, oculta la franja de valores (que quedó "pinneada" al punto clickeado) —
     * a pedido, el doble-click debe borrar ambas cosas.
     */
    static void limpiarUltimoClickHistorico(MainLayout layout, Div franjaValores) {
        limpiarUltimoClick(layout);
        franjaValores.setVisible(false);
    }
}
