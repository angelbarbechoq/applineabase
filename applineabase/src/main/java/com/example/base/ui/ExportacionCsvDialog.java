package com.example.base.ui;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.ExportacionCsvService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.server.StreamResource;

import java.time.YearMonth;
import java.util.List;

/**
 * Exportación masiva (solo ADMIN, se abre desde HistoricoView): todas las tablas de energía de
 * los meses elegidos, dos CSV por tabla con todo el rango (energía y VIP), dentro de un ZIP. Es
 * una descarga normal del navegador, así que funciona desde cualquier PC y el navegador pregunta
 * dónde guardarlo.
 */
class ExportacionCsvDialog extends Dialog {

    private final ExportacionCsvService exportacionCsvService;
    private final ComboBox<YearMonth> desdeCombo = new ComboBox<>("Mes desde");
    private final ComboBox<YearMonth> hastaCombo = new ComboBox<>("Mes hasta");
    private final Anchor descargarLink = new Anchor();
    private final Span mensaje = new Span();

    ExportacionCsvDialog(ExportacionCsvService exportacionCsvService) {
        this.exportacionCsvService = exportacionCsvService;
        setHeaderTitle("Exportar CSV de todas las maquinas");
        setWidth("520px");

        List<YearMonth> meses = exportacionCsvService.mesesDisponibles();
        for (ComboBox<YearMonth> combo : List.of(desdeCombo, hastaCombo)) {
            combo.setItems(meses);
            combo.setItemLabelGenerator(ym -> RutaArchivosEnergia.getNombreMes(ym.getMonthValue()) + " " + ym.getYear());
            combo.setWidth("200px");
            if (!meses.isEmpty()) combo.setValue(meses.get(0));
            combo.addValueChangeListener(e -> actualizarDescarga());
        }

        Span ayuda = new Span("Se descarga un ZIP con dos carpetas y un CSV por maquina con todo el rango elegido: "
                + "Energia (tabla_mes_anio.csv, kWh) y Voltaje-Corriente-Potencia-PF (tablaVIP_mes_anio.csv). "
                + "El archivo resumen.txt indica que maquinas tienen datos, cuales estan en cero y cuales no tienen datos. "
                + "Puede tardar varios minutos si se eligen muchos meses.");
        ayuda.getStyle().set("font-size", "12px").set("color", "var(--vaadin-text-color-secondary, #666)");
        mensaje.getStyle().set("color", "#721c24");

        add(new VerticalLayout(new HorizontalLayout(desdeCombo, hastaCombo), ayuda, mensaje));

        descargarLink.setText("Exportar");
        descargarLink.getElement().setAttribute("download", true);
        descargarLink.getElement().getThemeList().add("button");
        descargarLink.getElement().getThemeList().add("primary");
        getFooter().add(new Button("Cerrar", e -> close()), descargarLink);

        actualizarDescarga();
    }

    /**
     * Rearma el recurso de descarga con la selección actual. Los parámetros se copian acá (en el
     * hilo de la UI) porque el escritor del ZIP corre después, en el request de la descarga, fuera
     * del lock de la sesión.
     */
    private void actualizarDescarga() {
        YearMonth desde = desdeCombo.getValue();
        YearMonth hasta = hastaCombo.getValue();

        String error = null;
        if (desde == null || hasta == null) {
            error = "Elija el mes desde y hasta";
        } else if (desde.isAfter(hasta)) {
            error = "El mes desde no puede ser posterior al mes hasta";
        }
        mensaje.setText(error == null ? "" : error);
        descargarLink.setEnabled(error == null);
        if (error != null) {
            descargarLink.removeHref();
            return;
        }

        String nombreZip = "energia_" + ExportacionCsvService.sufijoRango(desde, hasta) + ".zip";
        StreamResource recurso = new StreamResource(nombreZip,
                (out, session) -> exportacionCsvService.exportarZip(desde, hasta, out));
        recurso.setContentType("application/zip");
        descargarLink.setHref(recurso);
    }
}
