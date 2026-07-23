package com.example.horometro.ui;

import com.example.alarmas.model.AlarmaConfig;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaConfigRepository;
import com.example.alarmas.ui.AlarmasConfigView;
import com.example.base.model.GraficaModel;
import com.example.base.ui.ChartsView;
import com.example.base.ui.MainLayout;
import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.horometro.service.HorometroBackfillRunner;
import com.example.horometro.service.HorometroService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.TabSheet;
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
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
    private final HorometroBackfillRunner horometroBackfillRunner;
    private final LineaAccessService lineaAccessService;
    private final AlarmaConfigRepository alarmaConfigRepository;
    private final PLCDataQueryService plcDataQueryService;

    private final Grid<HorometroRow> grid = new Grid<>(HorometroRow.class, false);

    // Grupos para el grafico de barras (KWh + horas trabajadas), debajo de la tabla de arriba.
    // Extrusion/Mezcla salen del campo zona; Casa Fuerza es un grupo curado (campo "grupo" en
    // linea-id-config.json, editable desde la pantalla de Configuracion) porque no todas las
    // maquinas de la zona Mantenimiento son parte de el (sensores, medidores generales, etc).
    private static final String GRUPO_CASA_FUERZA = "Casa Fuerza";
    private static final String ID_CHART_EXTRUSION = "chartdiv_horo_extrusion";
    private static final String ID_CHART_MEZCLA = "chartdiv_horo_mezcla";
    private static final String ID_CHART_CASA_FUERZA = "chartdiv_horo_casafuerza";

    private List<String> maquinasExtrusion;
    private List<String> maquinasMezcla;
    private List<String> maquinasCasaFuerza;

    private Tab tabExtrusion;
    private Tab tabMezcla;
    private Tab tabCasaFuerza;
    // Solo se refresca el grafico de la pestaña de grupo actualmente visible (nunca las tres a
    // la vez): asi el gráfico amCharts5 recien se crea (am5.Root.new) cuando su contenedor ya
    // esta visible/con tamaño, evitando que quede en blanco hasta el proximo dato.
    private Runnable refrescoGrupoActual = () -> { };

    public HorometroView(HorometroService horometroService, HorometroBackfillRunner horometroBackfillRunner,
                          LineaAccessService lineaAccessService, AlarmaConfigRepository alarmaConfigRepository,
                          PLCDataQueryService plcDataQueryService) {
        this.horometroService = horometroService;
        this.horometroBackfillRunner = horometroBackfillRunner;
        this.lineaAccessService = lineaAccessService;
        this.alarmaConfigRepository = alarmaConfigRepository;
        this.plcDataQueryService = plcDataQueryService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Horómetro de Máquinas"));
        if (lineaAccessService.esAdmin()) {
            HorizontalLayout accionesAdmin = new HorizontalLayout(
                    new RouterLink("Ajustar umbrales de encendido/apagado", AlarmasConfigView.class),
                    crearBotonRecalcularTodos(),
                    crearExportadorCsv());
            accionesAdmin.setAlignItems(Alignment.END);
            accionesAdmin.setWidthFull();
            add(accionesAdmin);
        }

        grid.addColumn(HorometroRow::maquina).setHeader("Línea/Máquina").setAutoWidth(true).setSortable(true);
        grid.addComponentColumn(this::estadoBadge).setHeader("Estado").setAutoWidth(true);
        grid.addColumn(r -> String.format("%.2f kW", r.pwActual())).setHeader("PW actual").setAutoWidth(true);
        grid.addColumn(r -> String.format("%.2f kW", r.umbralKw())).setHeader("Umbral configurado").setAutoWidth(true);
        grid.addColumn(r -> formatoHoras(r.horasHoy())).setHeader("Horas hoy").setAutoWidth(true).setSortable(true);
        grid.addColumn(r -> formatoHoras(r.horasMes())).setHeader("Horas del mes").setAutoWidth(true).setSortable(true);
        grid.addColumn(r -> formatoHoras(r.horasTotal())).setHeader("Horas totales").setAutoWidth(true).setSortable(true);
        grid.addColumn(r -> formatoFechaDesde(r.desdeCuandoCuentaTotal())).setHeader("Total desde").setAutoWidth(true).setSortable(true);
        if (lineaAccessService.esAdmin()) {
            grid.addComponentColumn(this::botonRecalcular).setHeader("Recalcular").setAutoWidth(true);
        }
        grid.setSizeFull();

        maquinasExtrusion = maquinasPorZona("Extrusión");
        maquinasMezcla = maquinasPorZona("Mezcla");
        maquinasCasaFuerza = maquinasPorGrupo(GRUPO_CASA_FUERZA);

        TabSheet tabSheetPrincipal = new TabSheet();
        tabSheetPrincipal.setSizeFull();
        tabSheetPrincipal.add("Tabla", grid);
        if (!maquinasExtrusion.isEmpty()) {
            tabExtrusion = tabSheetPrincipal.add("Extrusión", crearPanelGrupo(ID_CHART_EXTRUSION));
        }
        if (!maquinasMezcla.isEmpty()) {
            tabMezcla = tabSheetPrincipal.add("Mezcla", crearPanelGrupo(ID_CHART_MEZCLA));
        }
        if (!maquinasCasaFuerza.isEmpty()) {
            tabCasaFuerza = tabSheetPrincipal.add(GRUPO_CASA_FUERZA, crearPanelGrupo(ID_CHART_CASA_FUERZA));
        }
        add(tabSheetPrincipal);
        setFlexGrow(1, tabSheetPrincipal);

        tabSheetPrincipal.addSelectedChangeListener(this::onTabSeleccionadaCambio);

        refrescarGrid();

        addAttachListener(e -> {
            UI ui = e.getUI();
            ui.setPollInterval(POLL_INTERVAL_MS);
            ui.addPollListener(pollEvent -> {
                refrescarGrid();
                refrescoGrupoActual.run();
            });
        });
    }

    /**
     * Cambia qué gráfico de grupo se refresca en cada poll (nunca los tres a la vez) y, para la
     * pestaña recién seleccionada, lo dispara de inmediato — así el gráfico se crea recién
     * cuando su contenedor ya está visible, en vez de precargarse oculto y quedar en blanco.
     */
    private void onTabSeleccionadaCambio(TabSheet.SelectedChangeEvent event) {
        Tab seleccionada = event.getSelectedTab();
        if (seleccionada == tabExtrusion) {
            refrescoGrupoActual = () -> refrescarGraficoGrupo(ID_CHART_EXTRUSION, maquinasExtrusion);
        } else if (seleccionada == tabMezcla) {
            refrescoGrupoActual = () -> refrescarGraficoGrupo(ID_CHART_MEZCLA, maquinasMezcla);
        } else if (seleccionada == tabCasaFuerza) {
            refrescoGrupoActual = () -> refrescarGraficoGrupo(ID_CHART_CASA_FUERZA, maquinasCasaFuerza);
        } else {
            refrescoGrupoActual = () -> { };
            return;
        }
        refrescoGrupoActual.run();
    }

    private List<String> maquinasPorZona(String zona) {
        return lineaAccessService.getLineasPermitidas().stream()
                .filter(l -> zona.equalsIgnoreCase(String.valueOf(l.get("zona"))))
                .map(l -> (String) l.get("lineaMaquina"))
                .distinct().sorted().collect(Collectors.toList());
    }

    private List<String> maquinasPorGrupo(String grupo) {
        return lineaAccessService.getLineasPermitidas().stream()
                .filter(l -> grupo.equalsIgnoreCase(String.valueOf(l.get("grupo"))))
                .map(l -> (String) l.get("lineaMaquina"))
                .distinct().sorted().collect(Collectors.toList());
    }

    private VerticalLayout crearPanelGrupo(String containerId) {
        VerticalLayout panel = new VerticalLayout();
        panel.setSizeFull();
        panel.setPadding(false);
        panel.setSpacing(false);

        Span tituloKwh = new Span("KWh consumido hoy");
        tituloKwh.getStyle().set("font-weight", "600").set("margin", "8px 0 0 0");
        Div chartDivKwh = new Div();
        chartDivKwh.setId(containerId + "_kwh");
        chartDivKwh.setWidthFull();
        chartDivKwh.setHeight("100%");

        Span tituloHoras = new Span("Horas trabajadas este mes");
        tituloHoras.getStyle().set("font-weight", "600").set("margin", "8px 0 0 0");
        Div chartDivHoras = new Div();
        chartDivHoras.setId(containerId + "_horas");
        chartDivHoras.setWidthFull();
        chartDivHoras.setHeight("100%");

        panel.add(tituloKwh, chartDivKwh, tituloHoras, chartDivHoras);
        panel.setFlexGrow(1, chartDivKwh);
        panel.setFlexGrow(1, chartDivHoras);

        return panel;
    }

    /**
     * KWh consumido hoy (suma de diferencias entre lecturas consecutivas, misma logica que
     * usan ChartsView/Historico via GraficaModel.calcularSerieKWh) y horas trabajadas en el mes
     * (HorometroService.obtenerSnapshot, la misma fuente que ya muestra la tabla de arriba),
     * ordenado de mayor a menor por KWh. Se llama solo para el grupo de la pestaña actualmente
     * seleccionada (ver onTabSeleccionadaCambio), nunca para los tres a la vez.
     */
    private void refrescarGraficoGrupo(String containerId, List<String> maquinas) {
        record FilaGrupo(String maquina, double kwh, double horasMes) {
        }
        List<FilaGrupo> filas = new ArrayList<>();
        for (String maquina : maquinas) {
            List<Map<String, Object>> datos = plcDataQueryService.getTodayKWhDataByMaquina(maquina);
            GraficaModel.SerieKWh serie = GraficaModel.calcularSerieKWh(datos, true);
            double kwhHoy = serie.valores().stream().mapToDouble(Float::doubleValue).sum();
            double horasMes = horometroService.obtenerSnapshot(maquina).horasMes();
            filas.add(new FilaGrupo(maquina, kwhHoy, horasMes));
        }
        filas.sort((a, b) -> Double.compare(b.kwh(), a.kwh()));

        List<String> categorias = filas.stream().map(FilaGrupo::maquina).collect(Collectors.toList());
        List<Double> kwhs = filas.stream().map(FilaGrupo::kwh).collect(Collectors.toList());
        List<Double> horas = filas.stream().map(FilaGrupo::horasMes).collect(Collectors.toList());

        getElement().executeJs(GraficaModel.getBarChartScript(
                containerId + "_kwh", containerId + "_horas", categorias, kwhs, horas));
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
     * Fuerza el recálculo completo del histórico de una máquina con el umbral vigente en
     * este momento. Necesario porque el backfill normal se salta los días ya calculados —
     * si acabas de ajustar el umbral en AlarmasConfigView, los días viejos quedarían con el
     * cálculo hecho con el umbral anterior hasta el próximo reinicio de la app, a menos que
     * uses este botón.
     */
    private Button botonRecalcular(HorometroRow r) {
        Button btn = new Button("Recalcular", e -> {
            horometroBackfillRunner.recalcularLinea(r.maquina());
            Notification.show("Horas de " + r.maquina() + " recalculadas con el umbral actual",
                    3000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            refrescarGrid();
        });
        btn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        return btn;
    }

    /**
     * Recalcula TODAS las máquinas visibles, una por una. Es notoriamente más lento que
     * el botón individual — cada línea implica volver a leer sus archivos SQLite mensuales
     * completos, y ese costo no se comparte entre líneas (cada una vive en su propia tabla).
     * Útil después de reparar la base de energía completa (ver "Reparar VIP Mensual") en vez
     * de tener que entrar línea por línea.
     */
    private Button crearBotonRecalcularTodos() {
        Button btn = new Button("Recalcular todas las máquinas", e -> {
            int total = 0;
            for (String maquina : lineaAccessService.getMaquinasPermitidas()) {
                if (buscarConfigHorometro(maquina).isEmpty()) {
                    continue;
                }
                horometroBackfillRunner.recalcularLinea(maquina);
                total++;
            }
            Notification.show(total + " máquinas recalculadas con el umbral actual",
                            3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            refrescarGrid();
        });
        btn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        return btn;
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
        LocalDate domingo = fecha.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        LocalDate lunes = domingo.minusDays(6);
        YearMonth mesDeLunes = YearMonth.from(lunes);
        YearMonth mesDeDomingo = YearMonth.from(domingo);
        boolean semanaCruzaMes = !mesDeLunes.equals(mesDeDomingo);

        StringBuilder sb = new StringBuilder();
        sb.append('﻿'); // BOM UTF-8, para que Excel muestre bien tildes/ñ
        sb.append("Linea;Lunes;Domingo;Horas domingo;Horas semana;");
        if (semanaCruzaMes) {
            sb.append("Horas ").append(nombreMes(mesDeLunes)).append(';')
                    .append("Horas ").append(nombreMes(mesDeDomingo)).append(';');
        } else {
            sb.append("Horas ").append(nombreMes(mesDeDomingo)).append(';');
        }
        sb.append("Horas totales\r\n");

        for (String maquina : lineaAccessService.getMaquinasPermitidas()) {
            if (buscarConfigHorometro(maquina).isEmpty()) {
                continue;
            }
            HorometroService.ReporteSemanal r = horometroService.generarReporte(maquina, fecha);
            sb.append(csvEscape(maquina)).append(';')
                    .append(r.lunes().format(FORMATO_FECHA_CORTA)).append(';')
                    .append(r.domingo().format(FORMATO_FECHA_CORTA)).append(';')
                    .append(formatoDecimalExcel(r.horasDomingo())).append(';')
                    .append(formatoDecimalExcel(r.horasSemana())).append(';');
            if (semanaCruzaMes) {
                double horasMesInicio = horometroService.horasDelMes(maquina, mesDeLunes);
                sb.append(formatoDecimalExcel(horasMesInicio)).append(';')
                        .append(formatoDecimalExcel(r.horasMes())).append(';');
            } else {
                sb.append(formatoDecimalExcel(r.horasMes())).append(';');
            }
            sb.append(formatoDecimalExcel(r.horasTotal())).append("\r\n");
        }
        return new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static final Locale LOCALE_ES = Locale.of("es", "ES");
    private static final DateTimeFormatter FORMATO_FECHA_CORTA = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter FORMATO_MES = DateTimeFormatter.ofPattern("MMMM yyyy", LOCALE_ES);

    private String nombreMes(YearMonth mes) {
        return mes.atDay(1).format(FORMATO_MES);
    }

    private String formatoDecimalExcel(double valor) {
        return String.format(LOCALE_ES, "%.1f", valor);
    }

    /**
     * Busca la regla de alarma (DETENCION o CICLO_COMPRESOR) que hace que el horómetro
     * aplique a esta máquina. Única función para esta decisión: la usan tanto el botón de
     * recalcular todas, la exportación CSV, como la tabla, para que las tres coincidan
     * siempre en qué máquinas tienen horómetro.
     */
    private Optional<AlarmaConfig> buscarConfigHorometro(String maquina) {
        return alarmaConfigRepository.findByLineaMaquinaAndTipoAlarma(maquina, TipoAlarma.DETENCION)
                .or(() -> alarmaConfigRepository.findByLineaMaquinaAndTipoAlarma(maquina, TipoAlarma.CICLO_COMPRESOR));
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
            AlarmaConfig config = buscarConfigHorometro(maquina).orElse(null);
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
