package com.example.base.ui;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.ExportacionCsvService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.server.StreamResource;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Exportación masiva (solo ADMIN, se abre desde HistoricoView): todas las tablas de energía del
 * rango elegido (por meses completos o entre dos fechas), dos CSV por tabla (energía y VIP) en
 * carpetas separadas dentro de un ZIP. Es una descarga normal del navegador, así que funciona
 * desde cualquier PC y el navegador pregunta dónde guardarlo.
 */
class ExportacionCsvDialog extends Dialog {

    private static final String POR_MESES = "Meses";
    private static final String POR_FECHAS = "Fechas";

    private final ExportacionCsvService exportacionCsvService;
    private final ComboBox<String> tipoRangoCombo = new ComboBox<>("Rango por");
    private final ComboBox<YearMonth> desdeMesCombo = new ComboBox<>("Mes desde");
    private final ComboBox<YearMonth> hastaMesCombo = new ComboBox<>("Mes hasta");
    private final DatePicker desdeFecha = new DatePicker("Fecha desde");
    private final DatePicker hastaFecha = new DatePicker("Fecha hasta");
    private final Anchor descargarLink = new Anchor();
    private final Span mensaje = new Span();

    ExportacionCsvDialog(ExportacionCsvService exportacionCsvService) {
        this.exportacionCsvService = exportacionCsvService;
        setHeaderTitle("Exportar CSV de todas las maquinas");
        setWidth("560px");

        tipoRangoCombo.setItems(POR_MESES, POR_FECHAS);
        tipoRangoCombo.setValue(POR_MESES);
        tipoRangoCombo.setWidth("140px");
        tipoRangoCombo.addValueChangeListener(e -> actualizarDescarga());

        List<YearMonth> meses = exportacionCsvService.mesesDisponibles();
        for (ComboBox<YearMonth> combo : List.of(desdeMesCombo, hastaMesCombo)) {
            combo.setItems(meses);
            combo.setItemLabelGenerator(ym -> RutaArchivosEnergia.getNombreMes(ym.getMonthValue()) + " " + ym.getYear());
            combo.setWidth("180px");
            if (!meses.isEmpty()) combo.setValue(meses.get(0));
            combo.addValueChangeListener(e -> actualizarDescarga());
        }

        LocalDate hoy = LocalDate.now();
        desdeFecha.setValue(hoy.withDayOfMonth(1));
        hastaFecha.setValue(hoy);
        for (DatePicker picker : List.of(desdeFecha, hastaFecha)) {
            picker.setMax(hoy);
            picker.setWidth("180px");
            picker.addValueChangeListener(e -> actualizarDescarga());
        }

        Span ayuda = new Span("Se descarga un ZIP con dos carpetas y un CSV por maquina con todo el rango elegido: "
                + "Energia (kWh) y Voltaje-Corriente-Potencia-PF (archivos con VIP en el nombre). "
                + "El archivo resumen.txt indica que maquinas tienen datos, cuales estan en cero y cuales no tienen datos. "
                + "Puede tardar varios minutos si el rango es grande.");
        ayuda.getStyle().set("font-size", "12px").set("color", "var(--vaadin-text-color-secondary, #666)");
        mensaje.getStyle().set("color", "#721c24");

        add(new VerticalLayout(
                tipoRangoCombo,
                new HorizontalLayout(desdeMesCombo, hastaMesCombo),
                new HorizontalLayout(desdeFecha, hastaFecha),
                ayuda, mensaje));

        descargarLink.setText("Exportar");
        descargarLink.getElement().setAttribute("download", true);
        descargarLink.getElement().getThemeList().add("button");
        descargarLink.getElement().getThemeList().add("primary");
        getFooter().add(new Button("Cerrar", e -> close()), descargarLink);

        actualizarDescarga();
    }

    /**
     * Muestra los campos del tipo de rango elegido y rearma el recurso de descarga. Los
     * parámetros se copian acá (en el hilo de la UI) porque el escritor del ZIP corre después, en
     * el request de la descarga, fuera del lock de la sesión.
     */
    private void actualizarDescarga() {
        boolean porFechas = POR_FECHAS.equals(tipoRangoCombo.getValue());
        desdeMesCombo.setVisible(!porFechas);
        hastaMesCombo.setVisible(!porFechas);
        desdeFecha.setVisible(porFechas);
        hastaFecha.setVisible(porFechas);

        LocalDate desde;
        LocalDate hasta;
        String sufijo = null;
        String error = null;
        if (porFechas) {
            desde = desdeFecha.getValue();
            hasta = hastaFecha.getValue();
            if (desde == null || hasta == null) {
                error = "Elija la fecha desde y hasta";
            } else if (desde.isAfter(hasta)) {
                error = "La fecha desde no puede ser posterior a la fecha hasta";
            } else {
                sufijo = ExportacionCsvService.sufijoFechas(desde, hasta);
            }
        } else {
            YearMonth mesDesde = desdeMesCombo.getValue();
            YearMonth mesHasta = hastaMesCombo.getValue();
            desde = mesDesde != null ? mesDesde.atDay(1) : null;
            hasta = mesHasta != null ? mesHasta.atEndOfMonth() : null;
            if (mesDesde == null || mesHasta == null) {
                error = "Elija el mes desde y hasta";
            } else if (mesDesde.isAfter(mesHasta)) {
                error = "El mes desde no puede ser posterior al mes hasta";
            } else {
                sufijo = ExportacionCsvService.sufijoRango(mesDesde, mesHasta);
            }
        }

        mensaje.setText(error == null ? "" : error);
        descargarLink.setEnabled(error == null);
        if (error != null) {
            descargarLink.removeHref();
            return;
        }

        String sufijoFinal = sufijo;
        StreamResource recurso = new StreamResource("energia_" + sufijoFinal + ".zip",
                (out, session) -> exportacionCsvService.exportarZip(desde, hasta, sufijoFinal, out));
        recurso.setContentType("application/zip");
        descargarLink.setHref(recurso);
    }
}
