package com.example.horometro.ui;

import com.example.alarmas.model.AlarmaConfig;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaConfigRepository;
import com.example.alarmas.ui.AlarmasConfigView;
import com.example.base.ui.ChartsView;
import com.example.base.ui.MainLayout;
import com.example.horometro.service.HorometroService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.StreamResource;
import jakarta.annotation.security.PermitAll;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dashboard con todas las máquinas a la vez: estado ON/OFF, PW en vivo junto al umbral
 * configurado (para facilitar el ajuste por máquina desde AlarmasConfigView), horas de
 * funcionamiento (hoy, mes, sin límite de tiempo) y, solo para ADMIN, exportación CSV
 * semanal (lunes-domingo) para comparar contra el horómetro físico de la máquina.
 *
 * Se refresca con el mismo mecanismo de poll que ya usan MainLayout/AlarmasHistorialView
 * (cada 30s) en vez de empujar datos por SSE hacia el Grid — como los datos de origen
 * solo cambian una vez por minuto (ciclo de lectura de PLCs), no hay ganancia real en
 * empujar más rápido, y así se evita la complejidad de UI.access() para actualizar
 * un componente server-side desde un evento asíncrono.
 */
@PageTitle("Horómetro | LineaBase")
@Route(value = "horometro", layout = MainLayout.class)
@PermitAll
public class HorometroView extends VerticalLayout implements BeforeEnterObserver {

    private static final int POLL_INTERVAL_MS = 30000;

    private final HorometroService horometroService;
    private final LineaAccessService lineaAccessService;
    private final AlarmaConfigRepository alarmaConfigRepository;

    private final Grid<HorometroRow> grid = new Grid<>(HorometroRow.class, false);

    public HorometroView(HorometroService horometroService, LineaAccessService lineaAccessService,
                          AlarmaConfigRepository alarmaConfigRepository) {
        this.horometroService = horometroService;
        this.lineaAccessService = lineaAccessService;
        this.alarmaConfigRepository = alarmaConfigRepository;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Horómetro de Máquinas"));
        if (lineaAccessService.esAdmin()) {
            add(new RouterLink("Ajustar umbrales de encendido/apagado", AlarmasConfigView.class));
            add(crearExportadorCsv());
        }

        grid.addColumn(HorometroRow::maquina).setHeader("Línea/Máquina").setAutoWidth(true).setSortable(true);
        grid.addComponentColumn(this::estadoBadge).setHeader("Estado").setAutoWidth(true);
        grid.addColumn(r -> String.format("%.2f kW", r.pwActual())).setHeader("PW actual").setAutoWidth(true);
        grid.addColumn(r -> String.format("%.2f kW", r.umbralKw())).setHeader("Umbral configurado").setAutoWidth(true);
        grid.addColumn(r -> formatoHoras(r.horasHoy())).setHeader("Horas hoy").setAutoWidth(true).setSortable(true);
        grid.addColumn(r -> formatoHoras(r.horasMes())).setHeader("Horas del mes").setAutoWidth(true).setSortable(true);
        grid.addColumn(r -> formatoHoras(r.horasTotal())).setHeader("Horas totales").setAutoWidth(true).setSortable(true);
        grid.addColumn(r -> formatoFechaDesde(r.desdeCuandoCuentaTotal())).setHeader("Total desde").setAutoWidth(true).setSortable(true);
        grid.setSizeFull();

        add(grid);
        setFlexGrow(1, grid);

        refrescarGrid();

        addAttachListener(e -> {
            UI ui = e.getUI();
            ui.setPollInterval(POLL_INTERVAL_MS);
            ui.addPollListener(pollEvent -> refrescarGrid());
        });
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (lineaAccessService.getMaquinasPermitidas().isEmpty()) {
            Notification.show("No tienes máquinas asignadas", 3000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            event.forwardTo(ChartsView.class);
        }
    }

    // "Parada" ya no es una alarma (a pedido: no queremos que dispare alarma/notificación),
    // así que se muestra como advertencia amarilla, no en rojo como el resto de las alarmas.
    private static final String COLOR_ADVERTENCIA_FONDO = "#fff3cd";
    private static final String COLOR_ADVERTENCIA_TEXTO = "#856404";

    private Span estadoBadge(HorometroRow r) {
        if (r.encendida()) {
            Span badge = new Span("ENCENDIDA");
            badge.getElement().getThemeList().add("badge");
            badge.getElement().getThemeList().add("success");
            return badge;
        }
        Span badge = new Span("⚠ PARADA");
        badge.getElement().getThemeList().add("badge");
        badge.getStyle()
                .set("background-color", COLOR_ADVERTENCIA_FONDO)
                .set("color", COLOR_ADVERTENCIA_TEXTO);
        return badge;
    }

    /**
     * Exportador CSV semanal, solo ADMIN. Ancla las 4 columnas al domingo de la semana
     * elegida (por defecto el último domingo completo), para que el reporte coincida con
     * la rutina real: cada lunes se toma el horómetro físico de la semana que acaba de
     * terminar. "Horas totales" se reconstruye histórico hasta esa fecha, no es el valor
     * actual — así sirve para comparar contra la lectura física de cualquier semana pasada.
     */
    private HorizontalLayout crearExportadorCsv() {
        DatePicker fechaSemanaPicker = new DatePicker("Semana a reportar (cualquier día de esa semana)");
        fechaSemanaPicker.setValue(LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)));
        fechaSemanaPicker.setWidth("300px");
        fechaSemanaPicker.setHelperText("El reporte usa el domingo de la semana que contiene esta fecha");

        StreamResource recurso = new StreamResource("horometro-semanal.csv",
                () -> generarCsv(fechaSemanaPicker.getValue()));
        recurso.setContentType("text/csv; charset=UTF-8");

        Anchor descargarLink = new Anchor(recurso, "Descargar CSV semanal");
        descargarLink.getElement().setAttribute("download", true);
        descargarLink.getElement().getThemeList().add("button");
        descargarLink.getElement().getThemeList().add("primary");

        HorizontalLayout layout = new HorizontalLayout(fechaSemanaPicker, descargarLink);
        layout.setAlignItems(Alignment.END);
        return layout;
    }

    private InputStream generarCsv(LocalDate fechaSeleccionada) {
        LocalDate fecha = fechaSeleccionada != null ? fechaSeleccionada : LocalDate.now();
        StringBuilder sb = new StringBuilder();
        sb.append('﻿'); // BOM UTF-8, para que Excel muestre bien tildes/ñ
        sb.append("Linea;Lunes;Domingo;Horas domingo;Horas semana;Horas mes;Horas totales\r\n");

        for (String maquina : lineaAccessService.getMaquinasPermitidas()) {
            boolean aplicaHorometro = alarmaConfigRepository.findByLineaMaquinaAndTipoAlarma(maquina, TipoAlarma.DETENCION).isPresent()
                    || alarmaConfigRepository.findByLineaMaquinaAndTipoAlarma(maquina, TipoAlarma.CICLO_COMPRESOR).isPresent();
            if (!aplicaHorometro) {
                continue;
            }
            HorometroService.ReporteSemanal r = horometroService.generarReporte(maquina, fecha);
            sb.append(csvEscape(maquina)).append(';')
                    .append(r.lunes().format(FORMATO_FECHA_CORTA)).append(';')
                    .append(r.domingo().format(FORMATO_FECHA_CORTA)).append(';')
                    .append(formatoDecimalExcel(r.horasDomingo())).append(';')
                    .append(formatoDecimalExcel(r.horasSemana())).append(';')
                    .append(formatoDecimalExcel(r.horasMes())).append(';')
                    .append(formatoDecimalExcel(r.horasTotal())).append("\r\n");
        }
        return new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static final Locale LOCALE_ES = Locale.of("es", "ES");
    private static final DateTimeFormatter FORMATO_FECHA_CORTA = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private String formatoDecimalExcel(double valor) {
        return String.format(LOCALE_ES, "%.1f", valor);
    }

    private String csvEscape(String valor) {
        if (valor.contains(";") || valor.contains("\"") || valor.contains("\n")) {
            return "\"" + valor.replace("\"", "\"\"") + "\"";
        }
        return valor;
    }

    private void refrescarGrid() {
        List<HorometroRow> filas = new ArrayList<>();
        for (String maquina : lineaAccessService.getMaquinasPermitidas()) {
            AlarmaConfig config = alarmaConfigRepository.findByLineaMaquinaAndTipoAlarma(maquina, TipoAlarma.DETENCION)
                    .or(() -> alarmaConfigRepository.findByLineaMaquinaAndTipoAlarma(maquina, TipoAlarma.CICLO_COMPRESOR))
                    .orElse(null);
            if (config == null) {
                continue; // línea sin regla de encendido/apagado configurada: el horómetro no aplica
            }
            HorometroService.HorometroSnapshot snap = horometroService.obtenerSnapshot(maquina);
            Double pw = horometroService.getUltimoPw(maquina);
            double umbral = config.getUmbralMinimoKw() != null ? config.getUmbralMinimoKw() : 0.0;
            filas.add(new HorometroRow(maquina, snap.encendida(), pw == null ? 0.0 : pw, umbral,
                    snap.horasHoy(), snap.horasMes(), snap.horasTotal(), snap.desdeCuandoCuentaTotal()));
        }
        grid.setItems(filas);
    }

    private String formatoHoras(double horas) {
        return String.format("%.1f h", horas);
    }

    private String formatoFechaDesde(java.time.LocalDateTime fecha) {
        return fecha == null ? "-" : fecha.format(FORMATO_FECHA_DESDE);
    }

    private static final java.time.format.DateTimeFormatter FORMATO_FECHA_DESDE =
            java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    private record HorometroRow(String maquina, boolean encendida, double pwActual, double umbralKw,
                                 double horasHoy, double horasMes, double horasTotal,
                                 java.time.LocalDateTime desdeCuandoCuentaTotal) {
    }
}
