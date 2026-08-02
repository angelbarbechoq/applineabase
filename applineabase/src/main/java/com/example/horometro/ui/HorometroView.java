package com.example.horometro.ui;

import com.example.alarmas.model.AlarmaConfig;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaConfigRepository;
import com.example.alarmas.ui.AlarmasConfigView;
import com.example.base.model.GraficaModel;
import com.example.base.ui.ChartsView;
import com.example.base.ui.CsvUtil;
import com.example.base.ui.MainLayout;
import com.example.horometro.service.HorometroBackfillRunner;
import com.example.horometro.service.HorometroService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
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

    private final Grid<HorometroRow> grid = new Grid<>(HorometroRow.class, false);

    // Grupos para el grafico de barras (horas trabajadas en el mes), debajo de la tabla de arriba.
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
    private Tab tabMensualTabla;
    private Tab tabMensualGrafico;
    // Solo se refresca el grafico de la pestaña de grupo actualmente visible (nunca las tres a
    // la vez): asi el gráfico amCharts5 recien se crea (am5.Root.new) cuando su contenedor ya
    // esta visible/con tamaño, evitando que quede en blanco hasta el proximo dato.
    private Runnable refrescoGrupoActual = () -> { };

    // Pestaña "Horas por mes" (gráfico): historial completo por mes de UNA máquina elegida por
    // el usuario, a diferencia de los gráficos de grupo de arriba (que comparan varias máquinas
    // pero solo en el mes actual). Mismo id fijo de contenedor: no hace falta uno por máquina
    // porque solo se muestra una a la vez.
    private static final String ID_CHART_MENSUAL_MAQUINA = "chartdiv_horo_mensual_maquina";
    private final ComboBox<String> comboMaquinaMensual = new ComboBox<>("Máquina");
    private final Grid<FilaMensualPivot> gridMensual = new Grid<>(FilaMensualPivot.class, false);
    // Meses (año en curso) con columna ya creada en gridMensual — para no reconstruir las
    // columnas en cada poll, solo cuando cambian (arranque, o el primer poll tras un
    // rollover de mes/año mientras la app sigue corriendo).
    private List<YearMonth> mesesTablaMensual = List.of();

    public HorometroView(HorometroService horometroService, HorometroBackfillRunner horometroBackfillRunner,
                          LineaAccessService lineaAccessService, AlarmaConfigRepository alarmaConfigRepository) {
        this.horometroService = horometroService;
        this.horometroBackfillRunner = horometroBackfillRunner;
        this.lineaAccessService = lineaAccessService;
        this.alarmaConfigRepository = alarmaConfigRepository;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        H3 titulo = new H3("Horómetro de Máquinas");
        titulo.getStyle().set("margin", "0");
        HorizontalLayout filaTitulo = new HorizontalLayout(titulo);
        filaTitulo.setAlignItems(Alignment.CENTER);
        filaTitulo.setSpacing(true);
        add(filaTitulo);
        if (lineaAccessService.esAdmin()) {
            agregarAccionesAdmin(filaTitulo);
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

        List<Map<String, Object>> lineasPermitidas = lineaAccessService.getLineasPermitidas();
        maquinasExtrusion = maquinasPorCampo(lineasPermitidas, "zona", "Extrusión");
        maquinasMezcla = maquinasPorCampo(lineasPermitidas, "zona", "Mezcla");
        maquinasCasaFuerza = maquinasPorCampo(lineasPermitidas, "grupo", GRUPO_CASA_FUERZA);

        gridMensual.setSizeFull();

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
        tabMensualTabla = tabSheetPrincipal.add("Horas por mes (tabla)", gridMensual);
        tabMensualGrafico = tabSheetPrincipal.add("Horas por mes (gráfico)", crearPanelGraficoMensual());
        add(tabSheetPrincipal);
        setFlexGrow(1, tabSheetPrincipal);

        tabSheetPrincipal.addSelectedChangeListener(this::onTabSeleccionadaCambio);

        refrescarGrid();
        refrescarTablaMensual();

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
     * Panel de la pestaña "Horas por mes (gráfico)": selector de máquina + contenedor del
     * gráfico de barras. A diferencia de los gráficos de grupo (una barra por máquina, mes
     * actual), acá cada barra es un mes distinto de la máquina elegida — historial completo.
     */
    private VerticalLayout crearPanelGraficoMensual() {
        List<String> maquinas = maquinasConHorometro();
        comboMaquinaMensual.setItems(maquinas);
        comboMaquinaMensual.setWidth("300px");
        if (!maquinas.isEmpty()) {
            comboMaquinaMensual.setValue(maquinas.get(0));
        }
        comboMaquinaMensual.addValueChangeListener(e -> refrescarGraficoMensual());

        VerticalLayout panel = new VerticalLayout();
        panel.setSizeFull();
        panel.setPadding(false);

        Div chartDiv = new Div();
        chartDiv.setId(ID_CHART_MENSUAL_MAQUINA);
        chartDiv.setWidthFull();
        chartDiv.setHeight("100%");

        panel.add(comboMaquinaMensual, chartDiv);
        panel.setFlexGrow(1, chartDiv);
        return panel;
    }

    /** Máquinas visibles para el usuario que tienen horómetro configurado, en orden alfabético. */
    private List<String> maquinasConHorometro() {
        return lineaAccessService.getMaquinasPermitidas().stream()
                .filter(m -> buscarConfigHorometro(m).isPresent())
                .distinct().sorted().collect(Collectors.toList());
    }

    /**
     * Tabla pivote: una fila por máquina, una columna por mes del año en curso (Enero hasta el
     * mes actual) — igual al formato que se usa a mano para comparar contra el horómetro
     * físico. Las columnas se reconstruyen solo cuando cambia la lista de meses (arranque, o el
     * primer poll después de un rollover de mes con la app corriendo), no en cada poll.
     */
    private void refrescarTablaMensual() {
        LocalDate hoy = LocalDate.now();
        List<YearMonth> meses = new ArrayList<>();
        for (int mes = 1; mes <= hoy.getMonthValue(); mes++) {
            meses.add(YearMonth.of(hoy.getYear(), mes));
        }

        if (!meses.equals(mesesTablaMensual)) {
            mesesTablaMensual = meses;
            gridMensual.removeAllColumns();
            gridMensual.addColumn(FilaMensualPivot::maquina).setHeader("Línea/Máquina").setAutoWidth(true).setSortable(true).setFrozen(true);
            for (int i = 0; i < meses.size(); i++) {
                int indice = i;
                gridMensual.addColumn(f -> formatoHoras(f.horasPorMes().get(indice)))
                        .setHeader(nombreMesCorto(meses.get(indice))).setAutoWidth(true).setSortable(true);
            }
        }

        List<FilaMensualPivot> filas = new ArrayList<>();
        for (String maquina : maquinasConHorometro()) {
            List<Double> horasPorMes = new ArrayList<>();
            for (YearMonth mes : meses) {
                horasPorMes.add(horometroService.horasDelMes(maquina, mes));
            }
            filas.add(new FilaMensualPivot(maquina, horasPorMes));
        }
        gridMensual.setItems(filas);
    }

    /** Gráfico de barras (una barra por mes) de la máquina actualmente elegida en el combo. */
    private void refrescarGraficoMensual() {
        String maquina = comboMaquinaMensual.getValue();
        if (maquina == null) {
            return;
        }
        List<HorometroService.HistorialMes> historial = horometroService.historialMensual(maquina);
        List<String> categorias = historial.stream().map(h -> nombreMes(h.mes())).collect(Collectors.toList());
        List<Double> horas = historial.stream().map(HorometroService.HistorialMes::horas).collect(Collectors.toList());
        getElement().executeJs(GraficaModel.getBarChartScript(ID_CHART_MENSUAL_MAQUINA, categorias, horas));
    }

    /**
     * Cambia qué contenido (gráfico de grupo o tabla/gráfico mensual) se refresca en cada poll
     * — nunca más de uno a la vez — y, para la pestaña recién seleccionada, lo dispara de
     * inmediato. Además de crear los gráficos amCharts5 recién cuando su contenedor ya está
     * visible (evita que queden en blanco), esto evita que la tabla mensual (que consulta
     * horasDelMes por cada máquina y cada mes del año) haga esas consultas cada 30s aunque
     * nadie esté mirando esa pestaña — esa carga de fondo competía con operaciones pesadas como
     * "Recalcular todas las máquinas" y las hacía notablemente más lentas.
     */
    private void onTabSeleccionadaCambio(TabSheet.SelectedChangeEvent event) {
        Tab seleccionada = event.getSelectedTab();
        if (seleccionada == tabExtrusion) {
            refrescoGrupoActual = () -> refrescarGraficoGrupo(ID_CHART_EXTRUSION, maquinasExtrusion);
        } else if (seleccionada == tabMezcla) {
            refrescoGrupoActual = () -> refrescarGraficoGrupo(ID_CHART_MEZCLA, maquinasMezcla);
        } else if (seleccionada == tabCasaFuerza) {
            refrescoGrupoActual = () -> refrescarGraficoGrupo(ID_CHART_CASA_FUERZA, maquinasCasaFuerza);
        } else if (seleccionada == tabMensualTabla) {
            refrescoGrupoActual = this::refrescarTablaMensual;
        } else if (seleccionada == tabMensualGrafico) {
            refrescoGrupoActual = this::refrescarGraficoMensual;
        } else {
            refrescoGrupoActual = () -> { };
            return;
        }
        refrescoGrupoActual.run();
    }

    /**
     * Filtra las líneas permitidas por un campo arbitrario (zona o grupo) — antes había un
     * método casi idéntico por cada campo. Recibe la lista ya cargada (en vez de volver a
     * pedirla) porque el constructor la necesita para los tres filtros de este método.
     */
    private List<String> maquinasPorCampo(List<Map<String, Object>> lineas, String campo, String valor) {
        return lineas.stream()
                .filter(l -> valor.equalsIgnoreCase(String.valueOf(l.get(campo))))
                .map(l -> (String) l.get("lineaMaquina"))
                .distinct().sorted().collect(Collectors.toList());
    }

    private VerticalLayout crearPanelGrupo(String containerId) {
        VerticalLayout panel = new VerticalLayout();
        panel.setSizeFull();
        panel.setPadding(false);

        Div chartDiv = new Div();
        chartDiv.setId(containerId);
        chartDiv.setWidthFull();
        chartDiv.setHeight("100%");
        panel.add(chartDiv);
        panel.setFlexGrow(1, chartDiv);

        return panel;
    }

    /**
     * Horas trabajadas en el mes (HorometroService.obtenerSnapshot, la misma fuente que ya
     * muestra la tabla de arriba), ordenado de mayor a menor. Se llama solo para el grupo de la
     * pestaña actualmente seleccionada (ver onTabSeleccionadaCambio), nunca para los tres a la vez.
     */
    private void refrescarGraficoGrupo(String containerId, List<String> maquinas) {
        record FilaGrupo(String maquina, double horasMes) {
        }
        List<FilaGrupo> filas = new ArrayList<>();
        for (String maquina : maquinas) {
            double horasMes = horometroService.obtenerSnapshot(maquina).horasMes();
            filas.add(new FilaGrupo(maquina, horasMes));
        }
        filas.sort((a, b) -> Double.compare(b.horasMes(), a.horasMes()));

        List<String> categorias = filas.stream().map(FilaGrupo::maquina).collect(Collectors.toList());
        List<Double> horas = filas.stream().map(FilaGrupo::horasMes).collect(Collectors.toList());

        getElement().executeJs(GraficaModel.getBarChartScript(containerId, categorias, horas));
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
     * Acciones rápidas (descargar CSV, ajustar umbrales, recalcular todas) en la MISMA fila
     * que el título, para ganar una fila entera de alto — antes iban en una fila propia debajo
     * del selector de semana. El selector de semana sigue en su fila aparte, justo debajo.
     */
    private void agregarAccionesAdmin(HorizontalLayout filaTitulo) {
        DatePicker fechaSemanaPicker = new DatePicker("Semana a reportar");
        fechaSemanaPicker.setValue(LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)));
        fechaSemanaPicker.setWidth("220px");

        filaTitulo.add(crearLinkDescargaCsv(fechaSemanaPicker),
                new RouterLink("Ajustar umbrales de encendido/apagado", AlarmasConfigView.class),
                crearBotonRecalcularTodos());

        Span notaSemana = new Span("Cualquier día de esa semana; se usa el domingo que la contiene");
        notaSemana.getStyle().set("font-size", "12px").set("color", "var(--lumo-secondary-text-color)");

        HorizontalLayout filaSemana = new HorizontalLayout(fechaSemanaPicker, notaSemana);
        filaSemana.setAlignItems(Alignment.CENTER);
        add(filaSemana);
    }

    /**
     * Exportador CSV semanal, solo ADMIN. Ancla las 4 columnas al domingo de la semana
     * elegida (por defecto el último domingo completo), para que el reporte coincida con
     * la rutina real: cada lunes se toma el horómetro físico de la semana que acaba de
     * terminar. "Horas totales" se reconstruye histórico hasta esa fecha, no es el valor
     * actual — así sirve para comparar contra la lectura física de cualquier semana pasada.
     */
    private Anchor crearLinkDescargaCsv(DatePicker fechaSemanaPicker) {
        StreamResource recurso = new StreamResource("horometro-semanal.csv",
                () -> generarCsv(fechaSemanaPicker.getValue()));
        recurso.setContentType("text/csv; charset=UTF-8");

        Anchor descargarLink = new Anchor(recurso, "Descargar CSV semanal");
        descargarLink.getElement().setAttribute("download", true);
        return descargarLink;
    }

    private static final String[] NOMBRES_DIA_CORTOS = {"Lun", "Mar", "Mie", "Jue", "Vie", "Sab", "Dom"};

    /**
     * CSV semanal, una fila fija por máquina (igual que el formato original, para poder
     * tratarlo como tabla en Excel — filtrar, ordenar, sumar). El desglose día a día pedido va
     * en 7 columnas fijas (Lun..Dom, siempre esa semana), en vez de filas variables: como la
     * fecha de "Lunes" ya está en la fila, la fecha de cada columna se deduce por posición, sin
     * romper la estructura de una fila = una máquina. La columna del mes que se cierra en esa
     * semana solo aparece si la semana elegida cruza de mes — es la misma semana para todas las
     * máquinas del reporte, así que la cabecera no varía fila a fila.
     */
    private InputStream generarCsv(LocalDate fechaSeleccionada) {
        LocalDate fecha = fechaSeleccionada != null ? fechaSeleccionada : LocalDate.now();
        LocalDate domingo = fecha.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        LocalDate lunes = domingo.minusDays(6);
        YearMonth mesQueTermina = YearMonth.from(lunes);
        boolean semanaCruzaMes = !mesQueTermina.equals(YearMonth.from(domingo));

        StringBuilder sb = new StringBuilder();
        sb.append('﻿'); // BOM UTF-8, para que Excel muestre bien tildes/ñ
        sb.append("Linea;Lunes;Domingo;");
        for (String nombreDia : NOMBRES_DIA_CORTOS) {
            sb.append("Horas ").append(nombreDia).append(';');
        }
        sb.append("Horas semana;");
        if (semanaCruzaMes) {
            sb.append("Horas ").append(nombreMes(mesQueTermina)).append(" (mes completo);");
        }
        sb.append("Horas totales acumuladas\r\n");

        for (String maquina : lineaAccessService.getMaquinasPermitidas()) {
            if (buscarConfigHorometro(maquina).isEmpty()) {
                continue;
            }
            HorometroService.ReporteSemanalDetallado r = horometroService.generarReporteDetallado(maquina, fecha);
            List<HorometroService.DiaHoras> semana = new ArrayList<>(r.diasMesQueTermina());
            semana.addAll(r.diasMesQueEmpieza());

            sb.append(CsvUtil.escape(maquina)).append(';')
                    .append(r.lunes().format(FORMATO_FECHA_CORTA)).append(';')
                    .append(r.domingo().format(FORMATO_FECHA_CORTA)).append(';');
            for (HorometroService.DiaHoras dia : semana) {
                sb.append(formatoDecimalExcel(dia.horas())).append(';');
            }
            sb.append(formatoDecimalExcel(r.horasSemana())).append(';');
            if (semanaCruzaMes) {
                sb.append(formatoDecimalExcel(r.horasMesQueTermina())).append(';');
            }
            sb.append(formatoDecimalExcel(r.horasTotal())).append("\r\n");
        }
        return new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static final Locale LOCALE_ES = Locale.of("es", "ES");
    private static final DateTimeFormatter FORMATO_FECHA_CORTA = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter FORMATO_MES = DateTimeFormatter.ofPattern("MMMM yyyy", LOCALE_ES);
    private static final DateTimeFormatter FORMATO_MES_SOLO = DateTimeFormatter.ofPattern("MMMM", LOCALE_ES);

    private String nombreMes(YearMonth mes) {
        return mes.atDay(1).format(FORMATO_MES);
    }

    /** Nombre del mes solo (sin año), con mayúscula inicial — para encabezados de columna. */
    private String nombreMesCorto(YearMonth mes) {
        String nombre = mes.atDay(1).format(FORMATO_MES_SOLO);
        return Character.toUpperCase(nombre.charAt(0)) + nombre.substring(1);
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

    private record FilaMensualPivot(String maquina, List<Double> horasPorMes) {
    }
}
