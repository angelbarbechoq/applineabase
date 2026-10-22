package com.example.generador.ui;

import com.example.base.model.GraficaModel;
import com.example.base.ui.ChartsView;
import com.example.base.ui.MainLayout;
import com.example.calidad.service.CalidadEnergiaService.Estado;
import com.example.generador.model.CostoCombustible;
import com.example.generador.model.Generador;
import com.example.franjahoraria.model.Franja;
import com.example.generador.model.MetaImportacionRed;
import com.example.generador.service.ConsumoMedidoresService;
import com.example.generador.service.ConsumoMedidoresService.ConsumoMedidor;
import com.example.generador.service.CostoCombustibleService;
import com.example.generador.service.GeneradorAnalisisService;
import com.example.generador.service.GeneradorAnalisisService.Agrupacion;
import com.example.generador.service.GeneradorAnalisisService.Fila;
import com.example.generador.service.GeneradorAnalisisService.PeriodoMarcha;
import com.example.generador.service.GeneradorService;
import com.example.generador.service.GeneradorService.Punto;
import com.example.generador.service.ReporteGeneradoresService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.AttachmentType;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.io.OutputStream;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Análisis del grupo electrógeno, con el mismo filtro (generador y período) para todas las pestañas:
 * - Energía: importada de la red, exportada a la red (solo con algún generador en marcha), generada y consumo de la
 *   planta 1 por día, semana o mes; horas en paralelo / isla / vacío; arranques. La red es la del medidor principal.
 * - Períodos en marcha: por cada arranque, lo que trabajó el generador y lo que la planta 1 tomó de la red y le
 *   entregó en ese mismo intervalo (repartido si otro generador estaba en marcha), con el horómetro al arrancar y al
 *   parar (reemplaza a la pestaña Arranques que había en Generadores).
 * - Tendencias: una variable guardada del controlador (batería, temperaturas, potencia...) en las últimas 24 h a 90
 *   días; usa solo el generador del filtro, con su propio período (son lecturas de cada minuto).
 * - Costos: galones y precio del galón cargados a mano por período en marcha; comparación del costo del diésel con
 *   lo que habría costado la misma energía en la red, con el precio de la franja horaria de cada hora (tarifas y
 *   feriados de Consumo por franja horaria).
 *   Al elegir un arranque se ve su balance y con qué máquinas trabajó el generador (encendido / con paradas / apagado).
 * En pantalla se nombra el transformador ("Transformador 1"), nunca "el tablero".
 */
@PageTitle("Analisis grupo electrogeno | LineaBase")
@Route(value = "generador/analisis", layout = MainLayout.class)
@PermitAll
public class GeneradorAnalisisView extends VerticalLayout implements BeforeEnterObserver {

    private static final String CHART_ID = "chartdiv_generador_analisis";
    private static final String TODOS = "Todos";
    private static final String[] MESES = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
            "agosto", "septiembre", "octubre", "noviembre", "diciembre"};
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter CORTA = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    // Paleta categórica de referencia (validada para daltonismo): azul, naranja, aqua.
    private static final String AZUL = "0x2a78d6", NARANJA = "0xeb6834", AQUA = "0x1baf7a";

    private final GeneradorService generadorService;
    private final GeneradorAnalisisService analisis;
    private final ConsumoMedidoresService consumoMedidores;
    private final CostoCombustibleService costos;
    private final ReporteGeneradoresService reporte;
    private final LineaAccessService lineaAccessService;
    /** Semáforo de la meta "consumo cero de la red" (generador-config.json); se relee en cada "Ver". */
    private MetaImportacionRed meta;

    private final ComboBox<String> generadorCombo = new ComboBox<>("Generador");
    private final ComboBox<Agrupacion> agrupacionCombo = new ComboBox<>("Ver por");
    private final ComboBox<YearMonth> mesCombo = new ComboBox<>("Mes");
    private final ComboBox<Integer> semanasCombo = new ComboBox<>("Semanas");
    private final ComboBox<Integer> anioCombo = new ComboBox<>("Anio");
    private final Span mensaje = new Span();
    private final TabSheet tabs = new TabSheet();
    private Tab tabEnergia, tabPeriodos, tabTendencias, tabCostos;
    private String scriptGrafico, scriptPeriodos, scriptCostos;
    private static final String CHART_PERIODOS = "chartdiv_periodos_marcha";
    private static final String CHART_TENDENCIAS = "chartdiv_tendencias_generador";
    private static final String CHART_COSTOS = "chartdiv_costos_red";
    private static final String CHART_MINUTOS = "chartdiv_marcha_minutos";

    // Energía
    private final HorizontalLayout tarjetasEnergia = new HorizontalLayout();
    private final Grid<Fila> grid = new Grid<>();
    /** Con un solo generador elegido: lo que pusieron en la planta los demás (para que el consumo sea el de la planta). */
    private Grid.Column<Fila> colOtros;
    // Con "Todos": lo que puso cada generador por período (nombre -> período -> kWh) y el consumo de toda la planta.
    private Map<String, Map<String, Double>> usadoPorGenerador = new LinkedHashMap<>();
    private final Map<String, Grid.Column<Fila>> colPorGenerador = new LinkedHashMap<>();
    private static final String CHART_PLANTA = "chartdiv_analisis_planta";
    private final H4 tituloPlanta = new H4("Consumo de toda la planta");
    private final Span resumenPlanta = new Span();
    private final Div chartPlanta = new Div();
    private String scriptPlanta;
    // Períodos en marcha
    private final HorizontalLayout tarjetasPeriodos = new HorizontalLayout();
    private final Div kpiMeta = new Div();
    private final Grid<PeriodoMarcha> periodosGrid = new Grid<>();
    // Tendencias
    private final Span tendenciaMensaje = new Span();
    /** Columnas de {mes}Generador que se pueden graficar. */
    private static final Map<String, String[]> VARIABLES = new LinkedHashMap<>();
    static {
        VARIABLES.put("Tension de bateria (V)", new String[]{"bateria"});
        VARIABLES.put("Temperatura refrigerante (C)", new String[]{"temp_refrigerante"});
        VARIABLES.put("Presion de aceite (bar)", new String[]{"presion_aceite"});
        VARIABLES.put("Potencia (kW)", new String[]{"kw"});
        VARIABLES.put("Corrientes (A)", new String[]{"i_l1", "i_l2", "i_l3"});
        VARIABLES.put("Tension fase-fase (V)", new String[]{"v_l1l2", "v_l2l3", "v_l3l1"});
        VARIABLES.put("Frecuencia (Hz)", new String[]{"frecuencia"});
        VARIABLES.put("RPM", new String[]{"rpm"});
        VARIABLES.put("Tension de red fase-fase (V)", new String[]{"red_v_l1l2", "red_v_l2l3", "red_v_l3l1"});
        VARIABLES.put("Frecuencia de red (Hz)", new String[]{"red_frecuencia"});
        VARIABLES.put("Temperatura de aceite (C)", new String[]{"temp_aceite"});
        VARIABLES.put("Nivel de combustible (%)", new String[]{"nivel_combustible"});
    }
    // Costos
    private final HorizontalLayout tarjetasCostos = new HorizontalLayout();
    private final Div chartCostos = new Div();
    private final Grid<PeriodoMarcha> costosGrid = new Grid<>();
    private Map<String, CostoCombustible> combustible = Map.of();
    private List<PeriodoMarcha> periodos = List.of();
    // Máquinas del período en marcha elegido
    private final H4 maquinasTitulo = new H4();
    private final HorizontalLayout kpisDetalle = new HorizontalLayout();
    private final Div balance = new Div();
    private final Div chartMinutos = new Div();
    private final Span maquinasResumen = new Span();
    private final Grid<ConsumoMedidor> maquinasGrid = new Grid<>();

    private LocalDate desde, hasta;
    /** Generadores del último "Ver" (los que usa el reporte, junto con desde y hasta). */
    private List<Generador> gensVistos = List.of();

    public GeneradorAnalisisView(GeneradorService generadorService, GeneradorAnalisisService analisis,
                                 ConsumoMedidoresService consumoMedidores, CostoCombustibleService costos,
                                 ReporteGeneradoresService reporte, LineaAccessService lineaAccessService) {
        this.generadorService = generadorService;
        this.analisis = analisis;
        this.consumoMedidores = consumoMedidores;
        this.costos = costos;
        this.reporte = reporte;
        this.lineaAccessService = lineaAccessService;
        this.meta = generadorService.metaImportacionRed();
        setPadding(true);
        setSpacing(true);
        setWidthFull();

        H3 titulo = new H3("Analisis del grupo electrogeno");
        titulo.getStyle().set("margin", "0");
        add(titulo);
        List<Generador> generadores = generadorService.generadores();
        if (generadores.isEmpty()) {
            add(new Span("No hay generadores en C:\\LineaBaseX\\config\\generador-config.json"));
            return;
        }

        List<String> nombres = new ArrayList<>();
        if (generadores.size() > 1) nombres.add(TODOS);
        generadores.forEach(g -> nombres.add(g.nombre()));
        generadorCombo.setItems(nombres);
        generadorCombo.setValue(nombres.get(0));
        generadorCombo.setWidth("200px");

        agrupacionCombo.setItems(Agrupacion.values());
        agrupacionCombo.setItemLabelGenerator(Agrupacion::etiqueta);
        agrupacionCombo.setValue(Agrupacion.DIA);
        agrupacionCombo.setWidth("120px");

        List<YearMonth> meses = new ArrayList<>();
        for (YearMonth m = YearMonth.now(); meses.size() < 24; m = m.minusMonths(1)) meses.add(m);
        mesCombo.setItems(meses);
        mesCombo.setItemLabelGenerator(m -> MESES[m.getMonthValue() - 1] + " " + m.getYear());
        mesCombo.setValue(YearMonth.now());
        mesCombo.setWidth("170px");

        semanasCombo.setItems(4, 8, 12, 26, 52);
        semanasCombo.setItemLabelGenerator(n -> "Ultimas " + n);
        semanasCombo.setValue(8);
        semanasCombo.setWidth("140px");

        int anioActual = LocalDate.now().getYear();
        anioCombo.setItems(anioActual, anioActual - 1, anioActual - 2);
        anioCombo.setValue(anioActual);
        anioCombo.setWidth("110px");

        agrupacionCombo.addValueChangeListener(e -> visibilidadPeriodo());
        visibilidadPeriodo();

        Button ver = new Button("Ver", VaadinIcon.CHART.create(), e -> ver());
        ver.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        // Reporte de generadores del período del último "Ver": se arma al pedirlo (datos del momento) y se abre en una
        // pestaña nueva; desde ahí se eligen los arranques y se imprime o se guarda como PDF. Mismo enlace fijo que
        // la exportación de configuración (ver TraspasoConfiguracionView: cambiarlo en cada clic daba "f.txt").
        Anchor reporteLink = new Anchor();
        reporteLink.setText("Reporte de generadores");
        reporteLink.getElement().getThemeList().add("button");
        reporteLink.getElement().setAttribute("title", "Abre el reporte del periodo elegido en una pestana nueva. "
                + "Ahi se eligen los arranques y se imprime o se guarda como PDF.");
        reporteLink.setTarget("_blank");
        reporteLink.setHref(event -> {
            LocalDate d = desde, h = hasta;
            List<Generador> gens = gensVistos;
            event.setFileName(ReporteGeneradoresService.nombreArchivo(d, h));
            event.setContentType("text/html; charset=utf-8");
            try (OutputStream out = event.getOutputStream()) {
                reporte.escribir(out, gens, d, h);
            }
        }, AttachmentType.INLINE);
        HorizontalLayout filtros = new HorizontalLayout(generadorCombo, agrupacionCombo, mesCombo, semanasCombo, anioCombo, ver, reporteLink);
        filtros.setAlignItems(Alignment.END);
        filtros.getStyle().set("flex-wrap", "wrap");
        mensaje.getStyle().set("font-size", "12px").set("color", "#555");

        tabs.setWidthFull();
        tabEnergia = tabs.add("Resumen de energia", crearEnergia());
        tabPeriodos = tabs.add("Periodos en marcha", crearPeriodos());
        tabTendencias = tabs.add("Tendencias", crearTendencias());
        tabCostos = tabs.add("Costos", crearCostos());
        // El gráfico se arma con la pestaña visible (con display:none amCharts lo dibuja sin tamaño).
        tabs.addSelectedChangeListener(e -> {
            if (e.getSelectedTab() == tabEnergia && scriptGrafico != null) getElement().executeJs(scriptGrafico);
            if (e.getSelectedTab() == tabEnergia && scriptPlanta != null) getElement().executeJs(scriptPlanta);
            if (e.getSelectedTab() == tabPeriodos && scriptPeriodos != null) getElement().executeJs(scriptPeriodos);
            if (e.getSelectedTab() == tabCostos && scriptCostos != null) getElement().executeJs(scriptCostos);
            // Tendencias tiene su propio período (lecturas de cada minuto): el de arriba, Ver y el reporte no le aplican.
            boolean tendencias = e.getSelectedTab() == tabTendencias;
            for (Component c : new Component[]{agrupacionCombo, ver, reporteLink, mensaje}) c.setVisible(!tendencias);
            if (tendencias) {
                mesCombo.setVisible(false);
                semanasCombo.setVisible(false);
                anioCombo.setVisible(false);
            } else {
                visibilidadPeriodo();
            }
        });

        add(filtros, mensaje, tabs);
        ver();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        AccesoGeneradores.verificar(event, lineaAccessService);
    }

    private void visibilidadPeriodo() {
        Agrupacion a = agrupacionCombo.getValue();
        mesCombo.setVisible(a == Agrupacion.DIA);
        semanasCombo.setVisible(a == Agrupacion.SEMANA);
        anioCombo.setVisible(a == Agrupacion.MES);
    }

    private List<Generador> seleccionados() {
        String v = generadorCombo.getValue();
        List<Generador> todos = generadorService.generadores();
        if (v == null || TODOS.equals(v)) return todos;
        return todos.stream().filter(g -> g.nombre().equals(v)).toList();
    }

    private void ver() {
        Agrupacion a = agrupacionCombo.getValue() == null ? Agrupacion.DIA : agrupacionCombo.getValue();
        LocalDate hoy = LocalDate.now();
        switch (a) {
            case DIA -> {
                YearMonth m = mesCombo.getValue() == null ? YearMonth.now() : mesCombo.getValue();
                desde = m.atDay(1);
                hasta = m.atEndOfMonth();
            }
            case SEMANA -> {
                int n = semanasCombo.getValue() == null ? 8 : semanasCombo.getValue();
                desde = hoy.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(n - 1L);
                hasta = hoy;
            }
            case MES -> {
                int anio = anioCombo.getValue() == null ? hoy.getYear() : anioCombo.getValue();
                desde = LocalDate.of(anio, 1, 1);
                hasta = LocalDate.of(anio, 12, 31);
            }
        }
        if (hasta.isAfter(hoy)) hasta = hoy;
        if (desde.isAfter(hasta)) {
            mensaje.setText("El periodo elegido todavia no empezo.");
            return;
        }

        long t0 = System.currentTimeMillis();
        List<Generador> gens = seleccionados();
        gensVistos = gens;
        meta = generadorService.metaImportacionRed();
        // Una sola consulta para todo el "Ver": cada archivo se lee una vez y la isla se detecta una vez por generador.
        GeneradorAnalisisService.Consulta consulta = new GeneradorAnalisisService.Consulta();
        verEnergia(consulta, gens, a);
        periodos = analisis.periodosEnMarcha(consulta, gens, desde, hasta);
        verPeriodos();
        verCostos();
        limpiarDetalleMaquinas();

        mensaje.setText(gens.stream().map(Generador::nombre).reduce((x, y) -> x + " + " + y).orElse("")
                + ", red de la planta 1 (medidor principal), del " + desde.format(DIA) + " al " + hasta.format(DIA)
                + " (" + (System.currentTimeMillis() - t0) + " ms).");
    }

    // ================= Energía =================

    private VerticalLayout crearEnergia() {
        tarjetasEnergia.setWidthFull();
        tarjetasEnergia.getStyle().set("flex-wrap", "wrap");
        Div chart = new Div();
        chart.setId(CHART_ID);
        chart.setWidthFull();
        chart.setHeight("380px");

        grid.addColumn(Fila::periodo).setHeader("Periodo").setAutoWidth(true).setFlexGrow(0).setFrozen(true);
        columna(grid, "Importada de la red (kWh)", f -> kwhNum(f.redImportada()));
        columna(grid, "Exportada a la red (kWh)", f -> kwhNum(f.redExportada()));
        columna(grid, "Generado (kWh)", f -> kwhNum(f.generado()));
        // Con "Todos": lo que puso cada generador en la planta (generado - su parte de lo exportado).
        for (Generador g : generadorService.generadores()) {
            Grid.Column<Fila> c = columna(grid, "Aporte de " + g.nombre() + " (kWh)",
                    f -> kwhNum(usadoPorGenerador.getOrDefault(g.nombre(), Map.of()).get(f.periodo())));
            c.setVisible(false);
            colPorGenerador.put(g.nombre(), c);
        }
        columna(grid, "En paralelo (kWh)", f -> kwhNum(f.generadoParalelo()));
        columna(grid, "En isla (kWh)", f -> kwhNum(f.generadoIsla()));
        colOtros = columna(grid, "Aporte de otros generadores (kWh)", f -> kwhNum(f.aporteOtros()));
        columna(grid, "Consumo de la planta 1 (kWh)", f -> kwhNum(f.consumo()));
        grid.addComponentColumn(f -> badgeRed(f.porcentajeDeRed())).setHeader("% importado de la red (meta 0)").setAutoWidth(true).setFlexGrow(0);
        columna(grid, "% aportado por el generador", f -> porcentaje(f.aporteGenerador()));
        columna(grid, "Balance neto red (kWh)", f -> kwhNum(f.balanceNeto()));
        columna(grid, "Horas marcha", f -> horas(f.horasMarcha()));
        columna(grid, "En paralelo (h)", f -> horas(f.horasParalelo()));
        columna(grid, "En isla (h)", f -> horas(f.horasIsla()));
        columna(grid, "En vacio (h)", f -> horas(f.horasVacio()));
        columna(grid, "Arranques", f -> f.arranques() == null ? "-" : String.valueOf(f.arranques()));
        columna(grid, "Carga max (kW)", f -> f.kwMax() == null ? "-" : String.format(Locale.ROOT, "%.0f", f.kwMax()));
        grid.setWidthFull();
        grid.setHeight("420px");

        Span nota = nota("% importado de la red = parte del consumo de la planta 1 que vino de la red (meta en estiaje: 0; verde hasta "
                + pctUmbral(meta.verdeHasta()) + ", amarillo hasta " + pctUmbral(meta.amarilloHasta())
                + ", rojo por encima; se cambia en generador-config.json). % aportado por el generador = su parte del consumo. "
                + "Balance neto = importada - exportada por la planta (negativo = se exporto mas de lo que se importo). Importada y "
                + "exportada = contadores de energia del medidor principal de la planta 1 (transformadores 1 y 2 juntos), el que "
                + "coincide con el de la empresa electrica. La importada es de todo el dia; la exportada solo cuenta con algun "
                + "generador en marcha y, con dos en marcha, se reparte entre ellos segun lo que generaba cada uno. Generado = "
                + "contador del controlador del generador. Consumo de la planta 1 = importada + lo que pusieron en la planta los "
                + "generadores (generado - su parte de lo exportado); con un solo generador elegido, los demas entran como "
                + "\"Aporte de otros generadores\". En paralelo = el generador tenia carga y habia tension de red; en isla = con carga y "
                + "sin tension de red, o con el interruptor de la planta abierto (el medidor de su transformador en 0 tres minutos o mas, "
                + "con el cambio de carga, kVAr o frecuencia que lo confirma; desde el 11-10-2026); en vacio = en marcha con menos de " + (int) GeneradorAnalisisService.KW_VACIO + " kW. "
                + "Datos del generador: GenPower desde el 08-10-2026, Caterpillar desde el 09-10-2026 09:19. "
                + "La separacion paralelo / isla existe desde el 09-10-2026. Lo exportado se dibuja por debajo de cero. \"-\" = sin datos. "
                + "Con Todos: el aporte de cada generador y, abajo, el consumo de la planta 1 en el tiempo, calculado con la "
                + "potencia (kW) del medidor principal y de cada generador tomada de la base de datos (aprox.).");
        tituloPlanta.getStyle().set("margin", "8px 0 0 0");
        resumenPlanta.getStyle().set("font-size", "13px").set("color", "#333");
        chartPlanta.setId(CHART_PLANTA);
        chartPlanta.setWidthFull();
        chartPlanta.setHeight("380px");
        tituloPlanta.setVisible(false);
        resumenPlanta.setVisible(false);
        chartPlanta.setVisible(false);
        VerticalLayout v = new VerticalLayout(descripcion("Todo el periodo elegido: por cada dia, semana o mes, cuanta energia se "
                + "importo de la red, cuanta aporto el generador, cuanta se exporto a la red mientras el generador estaba en marcha, "
                + "y cuanto consumio la planta 1 en total."), tarjetasEnergia, chart, grid, tituloPlanta, resumenPlanta, chartPlanta, nota);
        v.setPadding(false);
        v.setWidthFull();
        return v;
    }

    private void verEnergia(GeneradorAnalisisService.Consulta consulta, List<Generador> gens, Agrupacion a) {
        List<Fila> filas = analisis.analizar(consulta, gens, a, desde, hasta);
        Fila t = analisis.total(filas);
        // Con "Todos": lo que puso cada generador, de su propio análisis (su generado y su parte de lo exportado).
        boolean todos = gens.size() > 1;
        boolean conOtros = !todos && t.aporteOtros() != null && t.aporteOtros() >= 0.5;
        usadoPorGenerador = new LinkedHashMap<>();
        Map<String, Double> totalPorGenerador = new LinkedHashMap<>();
        if (todos) {
            for (Generador g : gens) {
                List<Fila> delGenerador = analisis.analizar(consulta, List.of(g), a, desde, hasta);
                Map<String, Double> porPeriodo = new HashMap<>();
                delGenerador.forEach(f -> porPeriodo.put(f.periodo(), f.generadorUsado()));
                usadoPorGenerador.put(g.nombre(), porPeriodo);
                totalPorGenerador.put(g.nombre(), analisis.total(delGenerador).generadorUsado());
            }
        }
        colPorGenerador.forEach((nombre, c) -> c.setVisible(todos && usadoPorGenerador.containsKey(nombre)));
        colOtros.setVisible(conOtros);
        grid.setItems(filas);
        tarjetasEnergia.removeAll();
        tarjetasEnergia.add(kpiTomadoDeRed(t.porcentajeDeRed(), t.redImportada(), t.consumo()));
        tarjetasEnergia.add(kpiBalance(t.balanceNeto(), t.consumo()));
        StringBuilder origen = new StringBuilder();
        if (todos) totalPorGenerador.forEach((nombre, usado) -> origen.append(nombre).append(' ').append(kwh(usado)).append(" + "));
        else origen.append(gens.get(0).nombre()).append(' ').append(kwh(t.generadorUsado())).append(" + ");
        if (conOtros) origen.append("otros generadores ").append(kwh(t.aporteOtros())).append(" + ");
        tarjetasEnergia.add(tarjeta("Consumo de la planta 1", kwh(t.consumo()), origen + "red " + kwh(t.redImportada())));
        if (todos) {
            totalPorGenerador.forEach((nombre, usado) -> tarjetasEnergia.add(tarjeta("Aporte de " + nombre, kwh(usado),
                    usado == null || t.consumo() == null || t.consumo() <= 0 ? null
                            : String.format(Locale.ROOT, "%.1f %% del consumo", usado / t.consumo() * 100))));
        }
        tarjetasEnergia.add(tarjeta("Generado", kwh(t.generado()),
                t.generadoParalelo() == null && t.generadoIsla() == null ? null
                        : "en paralelo " + kwh(t.generadoParalelo()) + " / en isla " + kwh(t.generadoIsla())));
        tarjetasEnergia.add(tarjeta("Exportada a la red", kwh(t.redExportada()), "excedente, con el generador en marcha"));
        tarjetasEnergia.add(tarjeta("Horas de marcha", horas(t.horasMarcha()),
                "paralelo " + horas(t.horasParalelo()) + " / isla " + horas(t.horasIsla()) + " / vacio " + horas(t.horasVacio())));
        tarjetasEnergia.add(tarjeta("Arranques", t.arranques() == null ? "-" : String.valueOf(t.arranques()), null));
        tarjetasEnergia.add(tarjeta("Carga maxima", t.kwMax() == null ? "-" : String.format(Locale.ROOT, "%.0f kW", t.kwMax()), null));

        List<String> categorias = new ArrayList<>();
        List<Double[]> valores = new ArrayList<>();
        List<String> series = new ArrayList<>();
        List<String> colores = new ArrayList<>();
        if (todos) {
            for (String nombre : usadoPorGenerador.keySet()) {
                series.add("Aporte de " + nombre);
                colores.add(colorGenerador(nombre));
            }
        } else {
            series.add("Aporte del " + gens.get(0).nombre());
            colores.add(colorGenerador(gens.get(0).nombre()));
            if (conOtros) {
                series.add("Aporte de otros generadores");
                colores.add("0x9aa3ad");
            }
        }
        series.add("Importada de la red");
        series.add("Exportada a la red (excedente)");
        colores.add(AZUL);
        colores.add(AQUA);
        for (Fila f : filas) {
            categorias.add(a == Agrupacion.DIA ? f.periodo().substring(0, 5) : f.periodo());
            List<Double> v = new ArrayList<>();
            if (todos) usadoPorGenerador.values().forEach(m -> v.add(m.get(f.periodo())));
            else v.add(f.generadorUsado());
            if (conOtros) v.add(f.aporteOtros());
            v.add(f.redImportada());
            v.add(f.redExportada() == null || f.redExportada() == 0 ? null : -f.redExportada());
            valores.add(v.toArray(new Double[0]));
        }
        // Arriba de cero, el consumo partido en lo que puso cada generador + red; abajo, el excedente entregado.
        scriptGrafico = GraficaModel.getBarrasApiladasScript(CHART_ID, categorias, series.toArray(new String[0]),
                colores.toArray(new String[0]), valores, "kWh");
        if (tabs.getSelectedTab() == tabEnergia) getElement().executeJs(scriptGrafico);
        verConsumoPlanta(consulta, gens, todos);
    }

    /** Color de un generador: el mismo que en Balance energético P1 (según su lugar en generador-config.json). */
    private String colorGenerador(String nombre) {
        List<Generador> todos = generadorService.generadores();
        for (int i = 0; i < todos.size(); i++) {
            if (todos.get(i).nombre().equals(nombre)) return "0x" + GeneradorView.COLORES_GEN[i % GeneradorView.COLORES_GEN.length].substring(1);
        }
        return NARANJA;
    }

    /**
     * Con "Todos": consumo de toda la planta en el tiempo durante el período (mismo gráfico que Balance energético P1),
     * en puntos de 1 min (hasta 2 días), 15 min (hasta 8), 1 h (hasta 62) o 1 día.
     */
    private void verConsumoPlanta(GeneradorAnalisisService.Consulta consulta, List<Generador> gens, boolean todos) {
        tituloPlanta.setVisible(todos);
        resumenPlanta.setVisible(todos);
        chartPlanta.setVisible(todos);
        scriptPlanta = null;
        if (!todos) return;
        LocalDateTime ini = desde.atStartOfDay();
        LocalDateTime fin = hasta.plusDays(1).atStartOfDay();
        if (fin.isAfter(LocalDateTime.now())) fin = LocalDateTime.now().withNano(0);
        long dias = Duration.between(ini, fin).toDays();
        int minutos = dias <= 2 ? 1 : dias <= 8 ? 15 : dias <= 62 ? 60 : 1440;
        List<GeneradorAnalisisService.PuntoPlanta> puntos = analisis.consumoPlanta(consulta, gens, ini, fin, minutos);
        String[] nombres = gens.stream().map(Generador::nombre).toArray(String[]::new);
        String[] colores = new String[nombres.length];
        for (int i = 0; i < nombres.length; i++) colores[i] = colorGenerador(nombres[i]);
        List<Long> tiempos = new ArrayList<>();
        List<double[]> valores = new ArrayList<>();
        double consumo = 0, importada = 0, exportada = 0;
        for (GeneradorAnalisisService.PuntoPlanta p : puntos) {
            tiempos.add(p.fecha().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            double[] v = new double[nombres.length + 3];
            System.arraycopy(p.generado(), 0, v, 0, nombres.length);
            v[nombres.length] = p.consumo();
            v[nombres.length + 1] = p.importada();
            v[nombres.length + 2] = p.exportada();
            valores.add(v);
            consumo += p.consumo() * minutos / 60.0;
            importada += p.importada() * minutos / 60.0;
            exportada += p.exportada() * minutos / 60.0;
        }
        String cada = minutos == 1 ? "cada minuto" : minutos == 1440 ? "promedio de cada dia" : "promedio de " + (minutos == 60 ? "cada hora" : minutos + " min");
        resumenPlanta.setText(puntos.isEmpty() ? "Sin lecturas del medidor principal en el periodo."
                : String.format(Locale.ROOT, "Planta 1 (medidor principal y generadores; %s): consumo aprox. %,.0f kWh, de la red "
                        + "%,.0f kWh (%.1f %%), exportada %,.0f kWh. Lineas = lo que genero cada generador.", cada, consumo, importada,
                        consumo > 0 ? importada / consumo * 100 : 0, exportada));
        scriptPlanta = GraficaModel.getConsumoPlantaScript(CHART_PLANTA, tiempos, nombres, colores, valores, minutos, false);
        if (tabs.getSelectedTab() == tabEnergia) getElement().executeJs(scriptPlanta);
    }

    // ================= Períodos en marcha =================

    private VerticalLayout crearPeriodos() {
        tarjetasPeriodos.setWidthFull();
        tarjetasPeriodos.getStyle().set("flex-wrap", "wrap");
        periodosGrid.addColumn(PeriodoMarcha::generador).setHeader("Generador").setAutoWidth(true).setFlexGrow(0).setFrozen(true);
        periodosGrid.addColumn(p -> p.nro() == null ? "-" : String.valueOf(p.nro())).setHeader("Nro").setAutoWidth(true).setFlexGrow(0);
        periodosGrid.addColumn(p -> p.inicio().format(CORTA) + (p.inicioEstimado() ? " (aprox.)" : "")).setHeader("Inicio").setAutoWidth(true).setFlexGrow(0);
        periodosGrid.addComponentColumn(p -> {
            if (!p.enCurso()) return new Span(p.fin().format(CORTA));
            Span s = new Span();
            GeneradorView.badge(s, "En marcha", Estado.OK);
            return s;
        }).setHeader("Fin").setAutoWidth(true).setFlexGrow(0);
        columna(periodosGrid, "Duracion", p -> duracion(p.horas()));
        columna(periodosGrid, "Horometro (h)", p -> horometro(p.horometroInicio()) + " -> " + horometro(p.horometroFin()));
        columna(periodosGrid, "Consumo de la planta 1 (kWh)", p -> kwhNum(p.consumo()));
        periodosGrid.addComponentColumn(p -> badgeRed(p.porcentajeDeRed())).setHeader("% importado de la red (meta 0)").setAutoWidth(true).setFlexGrow(0);
        columna(periodosGrid, "Importada de la red (kWh)", p -> kwhNum(p.kwhRedImportada()));
        columna(periodosGrid, "% aportado por el generador", p -> porcentaje(p.aporteGenerador()));
        columna(periodosGrid, "Exportada a la red (kWh)", p -> kwhNum(p.kwhRedExportada()));
        columna(periodosGrid, "Balance neto red (kWh)", p -> kwhNum(p.balanceNeto()));
        columna(periodosGrid, "Tiempo importando de la red", p -> p.porcentajeTiempoTomando() == null ? "-"
                : String.format(Locale.ROOT, "%.0f %%", p.porcentajeTiempoTomando()));
        columna(periodosGrid, "Pico importado (kW)", p -> p.picoTomadoKw() == null ? "-" : String.format(Locale.ROOT, "%.0f", p.picoTomadoKw()));
        columna(periodosGrid, "Generado (kWh)", p -> kwhNum(p.kwhGenerado()));
        columna(periodosGrid, "Carga media (kW)", p -> p.kwMedio() == null ? "-" : String.format(Locale.ROOT, "%.0f", p.kwMedio()));
        columna(periodosGrid, "Carga max (kW)", p -> p.kwMax() == null ? "-" : String.format(Locale.ROOT, "%.0f", p.kwMax()));
        columna(periodosGrid, "Paralelo (min)", p -> minutos(p.minParalelo()));
        columna(periodosGrid, "Isla (min)", p -> minutos(p.minIsla()));
        columna(periodosGrid, "Vacio (min)", p -> minutos(p.minVacio()));
        periodosGrid.addColumn(p -> GeneradorView.nombreRed(p.red())).setHeader("Transformador").setAutoWidth(true).setFlexGrow(0);
        periodosGrid.setWidthFull();
        periodosGrid.setHeight("360px");
        periodosGrid.setSelectionMode(Grid.SelectionMode.SINGLE);
        periodosGrid.asSingleSelect().addValueChangeListener(e -> mostrarMaquinas(e.getValue()));
        Span nota = nota("Cada fila es un arranque: una vez que el generador estuvo encendido (RPM > 0); \"aprox.\" = la app arranco "
                + "con el generador ya en marcha. Horometro = horas de marcha del controlador al arrancar y al parar. "
                + "% importado de la red = parte del consumo "
                + "de la planta 1 que vino de la red mientras el generador estaba en marcha (meta 0; verde hasta "
                + pctUmbral(meta.verdeHasta()) + ", amarillo hasta " + pctUmbral(meta.amarilloHasta())
                + "). % aportado por el generador = el resto (suman 100%). Exportada a la red = excedente de la planta. "
                + "Importada y exportada salen de los contadores del medidor principal (transformadores 1 y 2 juntos, el que coincide "
                + "con el de la empresa electrica); si otro generador estaba en marcha a la vez, se reparten entre los dos segun lo "
                + "que generaba cada uno, asi la suma de los arranques no cuenta nada dos veces. Balance neto = importada - exportada (negativo = se exporto mas de lo que se importo). Tiempo "
                + "importando de la red y pico importado: cuanto tiempo y con cuanta potencia tomo la planta. El minuto que cruza el "
                + "inicio o el fin se reparte en proporcion. Paralelo / isla / vacio: minutos segun la tension de red y la carga "
                + "(desde el 09-10-2026).");
        Div chart = new Div();
        chart.setId(CHART_PERIODOS);
        chart.setWidthFull();
        chart.setHeight("320px");
        VerticalLayout v = new VerticalLayout(descripcion("Solo los momentos en que el generador estuvo encendido. Lo importante en "
                + "estiaje: cuanto se importo de la red (lo mas cercano a cero) y si sobro para exportar. Haga clic en un arranque para "
                + "ver abajo el detalle minuto a minuto y con que maquinas trabajo."),
                tarjetasPeriodos, kpiMeta, chart, periodosGrid, nota, crearDetalleMaquinas());
        kpiMeta.setWidthFull();
        v.setPadding(false);
        v.setWidthFull();
        return v;
    }

    private void verPeriodos() {
        periodosGrid.setItems(periodos);
        double horas = 0;
        int lecturas = 0, tomando = 0;
        Double gen = null, imp = null, exp = null, cons = null, pico = null;
        for (PeriodoMarcha p : periodos) {
            horas += p.horas();
            gen = suma(gen, p.kwhGenerado());
            imp = suma(imp, p.kwhRedImportada());
            exp = suma(exp, p.kwhRedExportada());
            cons = suma(cons, p.consumo());
            lecturas += p.lecturasRed();
            tomando += p.lecturasTomando();
            if (p.picoTomadoKw() != null && (pico == null || p.picoTomadoKw() > pico)) pico = p.picoTomadoKw();
        }
        tarjetasPeriodos.removeAll();
        Double pctRed = cons == null || cons <= 0 || imp == null ? null : Math.min(100, imp / cons * 100);
        Double balance = imp == null ? null : imp - (exp == null ? 0 : exp);
        tarjetasPeriodos.add(kpiTomadoDeRed(pctRed, imp, cons));
        tarjetasPeriodos.add(kpiBalance(balance, cons));
        pintarMeta(pctRed, imp, cons);
        tarjetasPeriodos.add(tarjeta("Tiempo importando de la red", lecturas == 0 ? "-" : String.format(Locale.ROOT, "%.0f %%", tomando * 100.0 / lecturas),
                (lecturas == 0 ? "" : tomando + " de " + lecturas + " minutos") + (pico == null ? "" : String.format(Locale.ROOT, "; pico %.0f kW", pico))));
        tarjetasPeriodos.add(tarjeta("Exportada a la red", kwh(exp), "excedente mientras el generador estaba encendido"));
        tarjetasPeriodos.add(tarjeta("Consumo de la planta 1", kwh(cons), cons == null ? null
                : "generador " + kwh(gen == null ? null : Math.max(0, gen - (exp == null ? 0 : exp))) + " + red " + kwh(imp)));
        tarjetasPeriodos.add(tarjeta("Generado", kwh(gen), periodos.size() + " arranques, " + duracion(horas)
                + (horas > 0 && gen != null ? String.format(Locale.ROOT, ", %.0f kW medio", gen / horas) : "")));

        // Un arranque por barra (del más viejo al más nuevo): arriba el consumo partido en generador + red,
        // abajo el excedente entregado.
        List<String> categorias = new ArrayList<>();
        List<Double[]> valores = new ArrayList<>();
        for (int i = periodos.size() - 1; i >= 0; i--) {
            PeriodoMarcha p = periodos.get(i);
            categorias.add(p.generador() + " " + p.inicio().format(DateTimeFormatter.ofPattern("dd-MM HH:mm")));
            valores.add(new Double[]{p.generadorUsado(), p.kwhRedImportada(),
                    p.kwhRedExportada() == null || p.kwhRedExportada() < 0.5 ? null : -p.kwhRedExportada()});
        }
        // Tooltips semitransparentes solo en esta pestaña (pedido del usuario: tapaban el detalle).
        scriptPeriodos = GraficaModel.conTooltipsTransparentes(GraficaModel.getBarrasApiladasScript(CHART_PERIODOS, categorias,
                new String[]{"Aporte del generador", "Importada de la red", "Exportada a la red (excedente)"},
                new String[]{NARANJA, AZUL, AQUA}, valores, "kWh"));
        if (tabs.getSelectedTab() == tabPeriodos) getElement().executeJs(scriptPeriodos);
    }

    /**
     * Meta "consumo cero de la red" del período elegido: el porcentaje importado, una barra sobre las zonas del
     * semáforo (como en el reporte de generadores) y cuántos arranques quedaron en verde y en rojo.
     */
    private void pintarMeta(Double pct, Double imp, Double cons) {
        kpiMeta.removeAll();
        kpiMeta.setVisible(pct != null);
        if (pct == null) return;
        int verdes = 0, rojos = 0, n = 0;
        for (PeriodoMarcha p : periodos) {
            Double x = p.porcentajeDeRed();
            if (x == null) continue;
            n++;
            if (meta.zona(x) == MetaImportacionRed.Zona.VERDE) verdes++;
            else if (meta.zona(x) == MetaImportacionRed.Zona.ROJO) rojos++;
        }
        double tope = Math.max(meta.amarilloHasta() * 2, Math.ceil(pct + 5));
        Function<Double, String> ancho = v -> String.format(Locale.ROOT, "%.2f%%", Math.min(v, tope) / tope * 100);
        Function<Double, String> marca = v -> "<span style='position:absolute;left:" + ancho.apply(v) + ";transform:translateX("
                + (v == 0 ? "0" : v >= tope ? "-100%" : "-50%") + ")'>" + pctUmbral(v) + "</span>";
        String chip = "padding:2px 8px;border-radius:10px;font-weight:600;font-size:13px;white-space:nowrap;";
        kpiMeta.add(new Html("<div style='display:grid;gap:6px;max-width:820px'>"
                + "<div style='font-weight:600'>Meta: cero importacion de la red</div>"
                + "<div style='display:flex;flex-wrap:wrap;align-items:baseline;gap:4px 12px'>"
                + "<span style='font-size:28px;font-weight:700'>" + String.format(Locale.ROOT, "%.1f %%", pct) + "</span>"
                + "<span style='color:#555'>del consumo se importo de la red (" + kwh(imp) + " de " + kwh(cons) + "); meta 0 %</span>"
                + "<span style='" + chip + "background:#d4edda;color:#155724'>" + verdes + " de " + n + " arranques hasta "
                + pctUmbral(meta.verdeHasta()) + "</span>"
                + "<span style='" + chip + "background:#f8d7da;color:#721c24'>" + rojos + " con mas de " + pctUmbral(meta.amarilloHasta()) + "</span></div>"
                + "<div style='position:relative;height:30px;display:flex;border-radius:4px;overflow:hidden'>"
                + "<div style='width:" + ancho.apply(meta.verdeHasta()) + ";background:#d4edda'></div>"
                + "<div style='width:" + ancho.apply(meta.amarilloHasta() - meta.verdeHasta()) + ";background:#fff3cd'></div>"
                + "<div style='flex:1;background:#f8d7da'></div>"
                + "<div style='position:absolute;left:0;top:7px;height:16px;width:" + ancho.apply(pct) + ";background:#2a78d6'></div></div>"
                + "<div style='position:relative;height:16px;font-size:12px;color:#555'>" + marca.apply(0.0) + marca.apply(meta.verdeHasta())
                + marca.apply(meta.amarilloHasta()) + marca.apply(tope) + "</div>"
                + "<div style='font-size:12px;color:#555'>Barra azul = importacion de la red del periodo, en % del consumo. Fondo: verde hasta "
                + pctUmbral(meta.verdeHasta()) + ", amarillo hasta " + pctUmbral(meta.amarilloHasta())
                + ", rojo por encima (se cambia en generador-config.json). Cada arranque, en la columna \"% importado de la red\".</div></div>"));
    }

    /** 2 -> "2 %", 2.5 -> "2.5 %". */
    private static String pctUmbral(double v) {
        return v == Math.rint(v) ? String.format(Locale.ROOT, "%.0f %%", v) : String.format(Locale.ROOT, "%.1f %%", v);
    }

    /** Indicador principal de estiaje: parte del consumo tomada de la red, con semáforo (meta 0). */
    private Component kpiTomadoDeRed(Double pct, Double kwhRed, Double consumo) {
        String detalle = kwhRed == null ? "meta: 0" : kwh(kwhRed) + (consumo == null ? "" : " de " + kwh(consumo)) + " consumidos; meta 0";
        return tarjetaKpi("Importada de la red", pct == null ? "-" : String.format(Locale.ROOT, "%.1f %%", pct), detalle, semaforoRed(pct));
    }

    /** Balance neto con la red (tomado - entregado): verde si se entregó más de lo que se tomó. */
    private Component kpiBalance(Double balance, Double consumo) {
        if (balance == null) return tarjeta("Balance neto con la red", "-", null);
        Estado e = balance <= 0 ? Estado.OK : semaforoRed(consumo == null || consumo <= 0 ? null : balance / consumo * 100);
        String detalle = Math.abs(balance) < 0.5 ? "sin intercambio neto con la red"
                : balance < 0 ? "se exporto a la red mas de lo que se importo" : "se importo de la red mas de lo que se exporto";
        return tarjetaKpi("Balance neto con la red", String.format(Locale.ROOT, "%,.0f kWh", balance), detalle, e);
    }

    /** Semáforo de la meta "consumo cero de la red", con los porcentajes de generador-config.json. */
    private Estado semaforoRed(Double pct) {
        if (pct == null) return Estado.SIN_DATO;
        return switch (meta.zona(pct)) {
            case VERDE -> Estado.OK;
            case AMARILLO -> Estado.AVISO;
            case ROJO -> Estado.FUERA;
        };
    }

    private Span badgeRed(Double pct) {
        Span s = new Span();
        GeneradorView.badge(s, pct == null ? "-" : String.format(Locale.ROOT, "%.1f %%", pct), semaforoRed(pct));
        return s;
    }

    // ================= Tendencias =================

    private VerticalLayout crearTendencias() {
        ComboBox<String> variable = new ComboBox<>("Variable");
        variable.setItems(VARIABLES.keySet());
        variable.setValue("Tension de bateria (V)");
        variable.setWidth("260px");
        ComboBox<Integer> rango = new ComboBox<>("Periodo");
        rango.setItems(1, 7, 30, 90);
        rango.setItemLabelGenerator(d -> d == 1 ? "Ultimas 24 h" : "Ultimos " + d + " dias");
        rango.setValue(7);
        Button verTendencia = new Button("Ver", VaadinIcon.CHART.create(), e -> graficarTendencia(variable.getValue(), rango.getValue()));
        verTendencia.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        HorizontalLayout filtros = new HorizontalLayout(variable, rango, verTendencia);
        filtros.setAlignItems(Alignment.END);

        tendenciaMensaje.getStyle().set("font-size", "12px").set("color", "#555");
        tendenciaMensaje.setText("Datos tomados de la base de datos: una lectura por minuto en marcha y una cada 15 min parado. "
                + "La bateria y la temperatura parado muestran si el generador esta listo para arrancar. Usa el generador elegido "
                + "arriba; con Todos, una linea por generador.");
        Div chart = new Div();
        chart.setId(CHART_TENDENCIAS);
        chart.setWidthFull();
        chart.setHeight("420px");
        VerticalLayout v = new VerticalLayout(descripcion("Como evoluciono una variable del controlador en las ultimas horas o "
                + "dias: bateria, temperaturas, presion de aceite, potencia, corrientes, tensiones o frecuencia."),
                filtros, tendenciaMensaje, chart);
        v.setPadding(false);
        v.setWidthFull();
        return v;
    }

    private void graficarTendencia(String variable, Integer dias) {
        if (variable == null || dias == null) return;
        String[] columnas = VARIABLES.get(variable);
        LocalDateTime hasta = LocalDateTime.now();
        LocalDateTime desde = hasta.minusDays(dias);
        // Una serie por columna; con "Todos", por cada generador (mismo color de generador si es una sola columna;
        // si son varias, el segundo generador en línea punteada).
        List<Generador> gens = seleccionados();
        boolean todos = gens.size() > 1;
        List<List<Punto>> series = new ArrayList<>();
        List<String> nombres = new ArrayList<>();
        List<String> colores = new ArrayList<>();
        List<Boolean> punteadas = new ArrayList<>();
        for (int gi = 0; gi < gens.size(); gi++) {
            Generador gen = gens.get(gi);
            for (String col : columnas) {
                series.add(generadorService.serie(gen, col, desde, hasta));
                nombres.add(!todos ? (columnas.length == 1 ? variable : col) : columnas.length == 1 ? gen.nombre() : gen.nombre() + " " + col);
                colores.add(colorGenerador(gen.nombre()));
                punteadas.add(gi > 0);
            }
        }
        int n = series.size();

        TreeMap<LocalDateTime, Float[]> filas = new TreeMap<>();
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int s = 0; s < n; s++) {
            for (Punto p : series.get(s)) {
                if (p.valor() == null) continue;
                filas.computeIfAbsent(p.fecha(), k -> new Float[n])[s] = p.valor().floatValue();
                min = Math.min(min, p.valor());
                max = Math.max(max, p.valor());
            }
        }
        GraficaModel g = new GraficaModel(n);
        g.setSeriesNames(nombres.toArray(new String[0]));
        g.setMostrarLeyenda(n > 1);
        if (todos && columnas.length == 1) {
            g.setColoresPersonalizados(colores.toArray(new String[0]));
        } else if (todos) {
            boolean[] d = new boolean[n];
            for (int i = 0; i < n; i++) d[i] = punteadas.get(i);
            g.setSeriesDiscontinuas(d);
        }
        if (filas.isEmpty()) {
            tendenciaMensaje.setText("Sin datos de " + variable + " en el periodo.");
            getElement().executeJs(g.getInitScript2(CHART_TENDENCIAS));
            return;
        }
        double margen = Math.max((max - min) * 0.1, 0.5);
        g.setMinY(Math.max(0, min - margen));
        g.setMaxY(max + margen);
        List<Long> t = new ArrayList<>();
        List<Float[]> v = new ArrayList<>();
        filas.forEach((f, val) -> {
            t.add(f.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            v.add(val);
        });
        tendenciaMensaje.setText(variable + ": " + filas.size() + " lecturas de "
                + gens.stream().map(Generador::nombre).reduce((x, y) -> x + " y " + y).orElse("")
                + (todos ? ", una linea por generador." : "."));
        getElement().executeJs(g.getInitScript2(CHART_TENDENCIAS) + g.getSetAllDataScript(CHART_TENDENCIAS, t, v)
                + g.getAplicarZoomInicialScript(CHART_TENDENCIAS));
    }

    // ================= Costos =================

    private VerticalLayout crearCostos() {
        tarjetasCostos.setWidthFull();
        tarjetasCostos.getStyle().set("flex-wrap", "wrap");
        chartCostos.setId(CHART_COSTOS);
        chartCostos.setWidthFull();
        chartCostos.setHeight("320px");
        costosGrid.addColumn(PeriodoMarcha::generador).setHeader("Generador").setAutoWidth(true).setFlexGrow(0).setFrozen(true);
        costosGrid.addColumn(p -> p.nro() == null ? "-" : String.valueOf(p.nro())).setHeader("Nro").setAutoWidth(true).setFlexGrow(0);
        costosGrid.addColumn(p -> p.inicio().format(CORTA)).setHeader("Inicio").setAutoWidth(true).setFlexGrow(0);
        columna(costosGrid, "Duracion", p -> duracion(p.horas()) + (p.enCurso() ? " (en marcha)" : ""));
        columna(costosGrid, "Generado (kWh)", p -> kwhNum(p.kwhGenerado()));
        columna(costosGrid, "Exportada a la red (kWh)", p -> kwhNum(p.kwhRedExportada()));
        columna(costosGrid, "Consumida en planta (kWh)", p -> kwhNum(p.generadorUsado()));
        costosGrid.addColumn(GeneradorAnalisisView::franjas).setHeader("Consumida en planta por franja (kWh)").setAutoWidth(true).setFlexGrow(0);
        columna(costosGrid, "Galones", p -> c(p) == null ? "-" : String.format(Locale.ROOT, "%,.2f", c(p).getGalones()));
        columna(costosGrid, "Precio galon", p -> c(p) == null ? "-" : dinero(c(p).getPrecioGalon()));
        columna(costosGrid, "Costo diesel", p -> c(p) == null ? "-" : dinero(c(p).costo()));
        columna(costosGrid, "Costo equivalente en la red", p -> p.costoRed() == null ? "-" : dinero(p.costoRed()));
        columna(costosGrid, "Diesel $/kWh", p -> precioKwh(precioDiesel(p)));
        columna(costosGrid, "Red $/kWh", p -> precioKwh(p.precioRedMedio()));
        costosGrid.addComponentColumn(this::vecesSpan).setHeader("Relacion diesel / red").setAutoWidth(true).setFlexGrow(0)
                .setTextAlign(ColumnTextAlign.END);
        costosGrid.addComponentColumn(p -> diferenciaSpan(diferencia(p))).setHeader("Costo de mas (o ahorro)").setAutoWidth(true).setFlexGrow(0)
                .setTextAlign(ColumnTextAlign.END);
        columna(costosGrid, "kWh por galon", p -> c(p) == null || c(p).getGalones() <= 0 || p.kwhGenerado() == null ? "-"
                : String.format(Locale.ROOT, "%.1f", p.kwhGenerado() / c(p).getGalones()));
        columna(costosGrid, "Galones por hora", p -> c(p) == null || p.horas() <= 0 ? "-"
                : String.format(Locale.ROOT, "%.1f", c(p).getGalones() / p.horas()));
        costosGrid.addColumn(p -> c(p) == null ? "" : (c(p).getUsuario() == null ? "" : c(p).getUsuario())
                + (c(p).getObservacion() == null ? "" : " - " + c(p).getObservacion()))
                .setHeader("Cargado por / observacion").setAutoWidth(true);
        if (lineaAccessService.esAdmin()) {
            costosGrid.addComponentColumn(p -> {
                Button b = new Button(c(p) == null ? "Cargar" : "Editar", VaadinIcon.EDIT.create(), e -> abrirDialogoCosto(p));
                b.addThemeVariants(ButtonVariant.LUMO_SMALL);
                return b;
            }).setHeader("").setAutoWidth(true).setFlexGrow(0);
        }
        costosGrid.setWidthFull();
        costosGrid.setHeight("520px");
        Span nota = nota("Por cada arranque se cargan a mano los galones de diesel usados y el precio del galon"
                + (lineaAccessService.esAdmin() ? " (boton Cargar / Editar)" : " (solo el administrador)")
                + "; al guardarlos aparece su comparacion. Consumida en planta = generado - exportado a la red: lo que salio a la red "
                + "no reemplazo energia de la red ni se paga. Costo equivalente en la red = cada kWh consumido en planta al precio de "
                + "la franja horaria (A, B, C o D) de la hora en que se genero, con la tarifa de su mes y los feriados de Consumo por "
                + "franja horaria. Diesel $/kWh = costo del diesel / kWh consumidos en planta. Relacion diesel / red = costo del diesel "
                + "/ costo equivalente en la red: 2.0 veces = generar costo el doble. Solo se compara energia contra combustible: no "
                + "entran el mantenimiento del generador ni los otros cargos de la factura de la red (demanda, comercializacion). Las "
                + "tarjetas de arriba suman solo los arranques con diesel cargado. Exportada a la red sin pago = kWh exportados x "
                + "costo del diesel por kWh generado.");

        VerticalLayout v = new VerticalLayout(descripcion("Cuanto cuesta generar con diesel contra comprar la misma energia a la red, "
                + "arranque por arranque. El precio de la red es el de la franja horaria de la hora en que se genero cada kWh."),
                tarjetasCostos, chartCostos, costosGrid, nota);
        v.setPadding(false);
        v.setWidthFull();
        return v;
    }

    /** "A 5,699 · B 2,178 · C 27,208" (sin las franjas en cero). */
    private static String franjas(PeriodoMarcha p) {
        if (p.aprovechadoFranja() == null) return "-";
        StringBuilder s = new StringBuilder();
        for (Franja f : Franja.values()) {
            double v = p.aprovechadoFranja()[f.ordinal()];
            if (v < 0.5) continue;
            s.append(s.isEmpty() ? "" : " · ").append(f.name()).append(' ').append(String.format(Locale.ROOT, "%,.0f", v));
        }
        return s.isEmpty() ? "-" : s.toString();
    }

    /** $ de diésel por kWh aprovechado (la energía que reemplazó a la de la red). */
    private Double precioDiesel(PeriodoMarcha p) {
        Double a = p.generadorUsado();
        return c(p) == null || a == null || a <= 0 ? null : c(p).costo() / a;
    }

    /** Costo del diésel - costo en la red: positivo = generar salió más caro. */
    private Double diferencia(PeriodoMarcha p) {
        return c(p) == null || p.costoRed() == null ? null : c(p).costo() - p.costoRed();
    }

    private Span vecesSpan(PeriodoMarcha p) {
        Span s = new Span();
        if (c(p) == null || p.costoRed() == null || p.costoRed() <= 0) {
            s.setText("-");
            return s;
        }
        double veces = c(p).costo() / p.costoRed();
        GeneradorView.badge(s, String.format(Locale.ROOT, "%.1f veces", veces), veces > 1 ? Estado.FUERA : Estado.OK);
        return s;
    }

    private static Span diferenciaSpan(Double v) {
        Span s = new Span(v == null ? "-" : v >= 0 ? dinero(v) + " de mas" : dinero(-v) + " de ahorro");
        if (v != null) s.getStyle().set("color", v > 0 ? "#721c24" : "#155724").set("font-weight", "600");
        return s;
    }

    private static String precioKwh(Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "$ %.3f", v);
    }

    private CostoCombustible c(PeriodoMarcha p) {
        return combustible.get(CostoCombustibleService.clave(p.generador(), p.arranqueId()));
    }

    private void verCostos() {
        combustible = costos.porPeriodo(periodos);
        costosGrid.setItems(periodos);
        // Con diesel cargado: galones, costo, horas y rendimiento por generador.
        double galones = 0, costoCargados = 0, horasCargados = 0;
        int cargados = 0;
        Map<String, double[]> rendimiento = new LinkedHashMap<>(); // generador -> {kWh generados, galones}
        // Comparados: además, con costo en la red y algo aprovechado.
        double costoDiesel = 0, costoRed = 0, kwhAprovechado = 0, kwhEntregado = 0, kwhGenerado = 0;
        int comparados = 0;
        List<String> categorias = new ArrayList<>();
        List<Double[]> valores = new ArrayList<>();
        for (int i = periodos.size() - 1; i >= 0; i--) { // del más viejo al más nuevo (orden del gráfico)
            PeriodoMarcha p = periodos.get(i);
            CostoCombustible cc = c(p);
            if (cc == null) continue;
            cargados++;
            galones += cc.getGalones();
            costoCargados += cc.costo();
            horasCargados += p.horas();
            if (p.kwhGenerado() != null) {
                double[] r = rendimiento.computeIfAbsent(p.generador(), k -> new double[2]);
                r[0] += p.kwhGenerado();
                r[1] += cc.getGalones();
            }
            Double aprovechado = p.generadorUsado();
            if (p.costoRed() == null || aprovechado == null || aprovechado <= 0) continue;
            comparados++;
            costoDiesel += cc.costo();
            costoRed += p.costoRed();
            kwhAprovechado += aprovechado;
            kwhGenerado += p.kwhGenerado();
            kwhEntregado += p.kwhRedExportada() == null ? 0 : p.kwhRedExportada();
            categorias.add(p.generador() + " " + p.inicio().format(DateTimeFormatter.ofPattern("dd-MM HH:mm")));
            valores.add(new Double[]{cc.costo() / aprovechado, p.costoRed() / aprovechado});
        }

        tarjetasCostos.removeAll();
        if (cargados == 0) {
            tarjetasCostos.add(tarjeta("Comparacion con la red", "-", "ningun arranque del periodo tiene el diesel cargado"
                    + (lineaAccessService.esAdmin() ? " (boton Cargar en la tabla)" : "")));
        } else if (comparados == 0) {
            tarjetasCostos.add(tarjeta("Comparacion con la red", "sin tarifa", "cargar la tarifa en Consumo por franja horaria > Tarifas"));
        } else {
            tarjetasCostos.add(tarjeta("Costo de generar (diesel)", dinero(costoDiesel), precioKwh(costoDiesel / kwhAprovechado)
                    + " por kWh consumido en planta; " + comparados + " de " + periodos.size() + " arranques con diesel cargado"));
            tarjetasCostos.add(tarjeta("Costo equivalente en la red", dinero(costoRed), precioKwh(costoRed / kwhAprovechado)
                    + " por kWh, con el precio de la franja de cada hora"));
            double diferencia = costoDiesel - costoRed;
            tarjetasCostos.add(diferencia > 0
                    ? tarjetaKpi(String.format(Locale.ROOT, "Generar sale %.1f veces la red", costoDiesel / costoRed),
                    dinero(diferencia) + " de mas", String.format(Locale.ROOT, "%,.0f kWh consumidos en planta", kwhAprovechado), Estado.FUERA)
                    : tarjetaKpi("Generar sale mas barato que la red", dinero(-diferencia) + " de ahorro",
                    String.format(Locale.ROOT, "%,.0f kWh consumidos en planta", kwhAprovechado), Estado.OK));
            double costoKwhGenerado = kwhGenerado > 0 ? costoDiesel / kwhGenerado : 0;
            tarjetasCostos.add(tarjeta("Exportada a la red sin pago", kwh(kwhEntregado), kwhEntregado < 0.5 ? null
                    : dinero(kwhEntregado * costoKwhGenerado) + " de diesel, a " + precioKwh(costoKwhGenerado) + " por kWh generado"));
        }
        if (cargados > 0) {
            double kwhRend = 0, galRend = 0;
            List<String> porGenerador = new ArrayList<>();
            for (Map.Entry<String, double[]> e : rendimiento.entrySet()) {
                kwhRend += e.getValue()[0];
                galRend += e.getValue()[1];
                if (e.getValue()[1] > 0) {
                    porGenerador.add(e.getKey() + String.format(Locale.ROOT, " %.1f", e.getValue()[0] / e.getValue()[1]));
                }
            }
            tarjetasCostos.add(tarjeta("Rendimiento", galRend <= 0 ? "-" : String.format(Locale.ROOT, "%.1f kWh/gal", kwhRend / galRend),
                    porGenerador.size() > 1 ? String.join(" · ", porGenerador) + " kWh/gal" : null));
            tarjetasCostos.add(tarjeta("Diesel usado", String.format(Locale.ROOT, "%,.1f gal", galones),
                    (horasCargados > 0 ? String.format(Locale.ROOT, "%.1f gal/h; ", galones / horasCargados) : "")
                            + (galones > 0 ? "precio medio " + dinero(costoCargados / galones) + " por galon" : "")));
        }

        if (comparados == 0) {
            scriptCostos = null;
            chartCostos.setVisible(false);
            return;
        }
        chartCostos.setVisible(true);
        scriptCostos = GraficaModel.getBarrasAgrupadasScript(CHART_COSTOS, categorias,
                new String[]{"Diesel $/kWh consumido en planta", "Red $/kWh (franja de cada hora)"}, new String[]{NARANJA, AZUL},
                valores, "$/kWh", "#.000");
        if (tabs.getSelectedTab() == tabCostos) getElement().executeJs(scriptCostos);
    }

    private void abrirDialogoCosto(PeriodoMarcha p) {
        CostoCombustible actual = c(p);
        Dialog d = new Dialog();
        d.setHeaderTitle("Combustible del arranque " + (p.nro() == null ? "" : "Nro " + p.nro() + " ") + "de " + p.generador());
        Span detalle = new Span("Del " + p.inicio().format(CORTA) + (p.enCurso() ? " (sigue en marcha)" : " al " + p.fin().format(CORTA))
                + ", " + duracion(p.horas()) + ", " + kwh(p.kwhGenerado()) + " generados.");
        detalle.getStyle().set("font-size", "13px").set("color", "#555");
        NumberField galones = new NumberField("Galones de diesel usados");
        galones.setMin(0);
        galones.setMax(CostoCombustibleService.GALONES_MAXIMO);
        galones.setStep(0.1);
        galones.setWidthFull();
        NumberField precio = new NumberField("Precio del galon ($)");
        precio.setMin(0.01);
        precio.setMax(CostoCombustibleService.PRECIO_MAXIMO);
        precio.setStep(0.001);
        precio.setWidthFull();
        TextField observacion = new TextField("Observacion (opcional)");
        observacion.setMaxLength(255);
        observacion.setWidthFull();
        if (actual != null) {
            galones.setValue(actual.getGalones());
            precio.setValue(actual.getPrecioGalon());
            observacion.setValue(actual.getObservacion() == null ? "" : actual.getObservacion());
        } else {
            costos.ultimoPrecio().ifPresent(precio::setValue);
        }
        VerticalLayout contenido = new VerticalLayout(detalle, galones, precio, observacion);
        contenido.setPadding(false);
        contenido.setWidth("380px");
        d.add(contenido);

        Button guardar = new Button("Guardar", e -> {
            if (galones.getValue() == null || precio.getValue() == null || galones.isInvalid() || precio.isInvalid()) {
                Notification.show("Ingrese los galones y el precio del galon", 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            try {
                costos.guardar(p, galones.getValue(), precio.getValue(), observacion.getValue(), lineaAccessService.usuarioActual());
                d.close();
                verCostos();
                Notification.show("Combustible guardado", 2500, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } catch (IllegalArgumentException ex) {
                Notification.show(ex.getMessage(), 4000, Notification.Position.MIDDLE).addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        guardar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancelar = new Button("Cancelar", e -> d.close());
        d.getFooter().add(cancelar, guardar);
        if (actual != null) {
            Button borrar = new Button("Borrar", VaadinIcon.TRASH.create(), e -> {
                costos.borrar(p);
                d.close();
                verCostos();
            });
            borrar.addThemeVariants(ButtonVariant.LUMO_ERROR);
            d.getFooter().addComponentAsFirst(borrar);
        }
        d.open();
    }

    // ================= Máquinas de un período en marcha =================

    private VerticalLayout crearDetalleMaquinas() {
        maquinasTitulo.getStyle().set("margin", "16px 0 0 0");
        maquinasResumen.getStyle().set("font-size", "14px").set("display", "block");
        maquinasGrid.addColumn(ConsumoMedidor::medidor).setHeader("Maquina").setAutoWidth(true).setFlexGrow(0);
        maquinasGrid.addColumn(c -> c.dentroDe() == null ? "" : "dentro de " + c.dentroDe()).setHeader("Submedidor")
                .setAutoWidth(true).setFlexGrow(0);
        maquinasGrid.addColumn(ConsumoMedidor::zona).setHeader("Zona / grupo").setAutoWidth(true).setFlexGrow(0);
        maquinasGrid.addComponentColumn(GeneradorAnalisisView::badgeEstado).setHeader("Estado").setAutoWidth(true).setFlexGrow(0);
        columna(maquinasGrid, "Energia consumida (kWh)", c -> c.kwh() == null ? "-" : String.format(Locale.ROOT, "%,.1f", c.kwh()));
        columna(maquinasGrid, "Sin produccion (desperdicio kWh)", c -> c.kwhEnEspera() == null || c.kwhEnEspera() < 0.05 ? "-"
                : String.format(Locale.ROOT, "%,.1f", c.kwhEnEspera()));
        columna(maquinasGrid, "Potencia media (kW)", c -> c.kwMedio() == null ? "-" : String.format(Locale.ROOT, "%,.1f", c.kwMedio()));
        columna(maquinasGrid, "Tiempo encendido", c -> c.estado() == ConsumoMedidoresService.Estado.SIN_DATOS ? "-" : duracion(c.horasEncendida()));
        columna(maquinasGrid, "Tiempo sin produccion", c -> c.estado() == ConsumoMedidoresService.Estado.SIN_DATOS ? "-" : duracion(c.horasSinProduccion()));
        columna(maquinasGrid, "Umbral de encendido", c -> String.format(Locale.ROOT, "%.0f", c.umbral()));
        columna(maquinasGrid, "Sin produccion tras", c -> c.lecturasConfirmacion() + " min bajo el umbral");
        maquinasGrid.setWidthFull();
        maquinasGrid.setAllRowsVisible(true);
        Span nota = nota("No se mide un porcentaje de carga: la carga de una linea depende del producto. Sin produccion = paso los "
                + "minutos indicados seguidos por debajo de su umbral de encendido, el mismo del horometro (Horometro > Ajustar "
                + "umbrales): 5 minutos para las lineas y demas maquinas. Los compresores paran y arrancan solos en su ciclo normal "
                + "(paradas de 10 a 30 minutos), asi que cuentan sin produccion recien despues de "
                + ConsumoMedidoresService.MINUTOS_COMPRESOR_APAGADO + " minutos seguidos sin carga; una parada del ciclo cuenta como "
                + "encendido. Encendido = todo el arranque. Energizado y sin produccion = encendido una parte del arranque y sin "
                + "produccion otra (ver los tiempos). Apagado, consumo minimo = no produjo en todo el arranque pero consumio (ej. "
                + "Linea05: su transformador de aislamiento, ~1,7 kW). Apagado (en cero) = todas sus lecturas fueron cero y su "
                + "contador no subio. Lo que se consume sin produccion es desperdicio: lo entrega igual el generador o la red. Sin "
                + "datos = su medidor no tiene lecturas en ese horario. Se listan solo las maquinas del transformador del generador "
                + "(Configuracion de hardware); no entran los transformadores ni el medidor general (bajan su carga porque el "
                + "generador esta en marcha, no es desperdicio), los sensores ni MotorL3 / MotorL4. Los submedidores (por ejemplo "
                + "GA752 dentro de Inyeccion) se muestran pero no se suman dos veces en el balance ni en el desperdicio.");
        kpisDetalle.setWidthFull();
        kpisDetalle.getStyle().set("flex-wrap", "wrap");
        balance.setWidthFull();
        chartMinutos.setId(CHART_MINUTOS);
        chartMinutos.setWidthFull();
        chartMinutos.setHeight("340px");
        VerticalLayout v = new VerticalLayout(maquinasTitulo, kpisDetalle, balance, chartMinutos, maquinasResumen, maquinasGrid, nota);
        v.setPadding(false);
        v.setWidthFull();
        limpiarDetalleMaquinas();
        return v;
    }

    private void limpiarDetalleMaquinas() {
        maquinasTitulo.setText("Detalle del arranque");
        maquinasResumen.setText("Haga clic en un arranque de la lista para ver el detalle minuto a minuto y con que maquinas "
                + "trabajo el generador.");
        maquinasGrid.setItems(List.of());
        maquinasGrid.setVisible(false);
        kpisDetalle.removeAll();
        balance.removeAll();
        balance.setVisible(false);
        chartMinutos.setVisible(false);
    }

    /**
     * Balance simple del arranque (pedido del usuario: de lo simple a lo complejo): generado, consumido
     * por las máquinas de su transformador (suma de sus medidores, sin contar los submedidores dos veces) y
     * su parte de lo exportado a la red (medidor principal). Debajo, la barra de 100% de dónde salió el consumo de la
     * planta 1. El desperdicio queda para una etapa siguiente.
     */
    private void mostrarBalance(PeriodoMarcha p, List<ConsumoMedidor> lista) {
        balance.removeAll();
        Double consumo = p.consumo(), gen = p.generadorUsado(), red = p.kwhRedImportada();
        if (consumo == null || consumo <= 0 || gen == null || red == null) {
            balance.setVisible(false);
            return;
        }
        double generado = p.kwhGenerado() == null ? 0 : p.kwhGenerado();
        double entregado = p.kwhRedExportada() == null ? 0 : p.kwhRedExportada();
        Double consumido = ConsumoMedidoresService.sumaBalance(lista);

        Span titulo = new Span("Balance del arranque");
        titulo.getStyle().set("font-size", "14px").set("font-weight", "700").set("color", "#0b0b0b");
        double max = Math.max(generado, Math.max(entregado, consumido == null ? 0 : consumido));
        Div filas = new Div();
        filas.getStyle().set("display", "grid").set("grid-template-columns", "minmax(200px, auto) 1fr auto")
                .set("column-gap", "12px").set("row-gap", "6px").set("align-items", "center").set("font-size", "13px")
                .set("margin", "4px 0");
        filaBalance(filas, "Generado por " + p.generador(), generado, max, "#eb6834");
        filaBalance(filas, "Consumido por las maquinas " + GeneradorView.deRed(p.red()), consumido, max, "#52514e");
        filaBalance(filas, "Exportada a la red", entregado, max, "#1baf7a");
        balance.add(titulo, filas);
        if (consumido != null) {
            double diferencia = generado - consumido - entregado;
            Span nota = new Span(String.format(Locale.ROOT, "Generado - consumido - exportado = %,.0f kWh. Si es positivo, el generador "
                    + "tambien alimento cargas sin medidor (alumbrado y otras), maquinas del otro transformador y perdidas; si es "
                    + "negativo, esas maquinas tomaron ademas de la red (la planta importo %,.0f kWh en el arranque).", diferencia, red));
            nota.getStyle().set("font-size", "12px").set("color", "#52514e");
            balance.add(nota);
        }

        double pctGen = Math.max(0, Math.min(100, gen / consumo * 100)), pctRed = 100 - pctGen;
        Span subtitulo = new Span(String.format(Locale.ROOT, "De donde salio el consumo de la planta 1 (medidor principal; 100%% = %,.0f kWh)",
                consumo));
        subtitulo.getStyle().set("font-size", "13px").set("font-weight", "600").set("color", "#0b0b0b").set("display", "block")
                .set("margin-top", "10px");
        Div barra = new Div();
        barra.getStyle().set("display", "flex").set("width", "100%").set("height", "30px").set("border-radius", "6px")
                .set("overflow", "hidden").set("margin", "4px 0");
        barra.add(segmento(pctGen, "#eb6834", String.format(Locale.ROOT, "Generador %.1f%%", pctGen)),
                segmento(pctRed, "#2a78d6", String.format(Locale.ROOT, "Red %.1f%%", pctRed)));
        Span leyenda = new Span(String.format(Locale.ROOT, "Generador %.1f%% (%,.0f kWh)   |   Red %.1f%% (%,.0f kWh), meta 0%%"
                + "   |   Exportada a la red: %,.0f kWh (excedente, no es parte del consumo)", pctGen, gen, pctRed, red, entregado));
        leyenda.getStyle().set("font-size", "12px").set("color", "#52514e");
        balance.add(subtitulo, barra, leyenda);
        balance.setVisible(true);
    }

    /** Una fila del balance: concepto, barra proporcional al mayor de los tres y kWh. */
    private static void filaBalance(Div filas, String concepto, Double kwh, double max, String color) {
        Span c = new Span(concepto);
        Div fondo = new Div();
        fondo.getStyle().set("height", "18px").set("background-color", "#f0f0f0").set("border-radius", "4px");
        Div barra = new Div();
        double pct = kwh == null || max <= 0 ? 0 : Math.max(0, kwh) / max * 100;
        barra.getStyle().set("height", "100%").set("width", String.format(Locale.ROOT, "%.2f%%", pct))
                .set("background-color", color).set("border-radius", "4px");
        fondo.add(barra);
        Span v = new Span(kwh == null ? "sin datos" : String.format(Locale.ROOT, "%,.0f kWh", kwh));
        v.getStyle().set("font-weight", "700").set("text-align", "right");
        filas.add(c, fondo, v);
    }

    private static Div segmento(double pct, String color, String texto) {
        Div d = new Div();
        d.getStyle().set("flex", "0 0 " + String.format(Locale.ROOT, "%.2f", pct) + "%").set("background-color", color)
                .set("color", "#ffffff").set("font-size", "12px").set("font-weight", "600").set("display", "flex")
                .set("align-items", "center").set("justify-content", "center").set("white-space", "nowrap").set("overflow", "hidden");
        if (pct >= 12) d.setText(texto);
        return d;
    }

    /**
     * Gráfico minuto a minuto del arranque: el generador contra su transformador (pedido del usuario el 2026-10-21): su
     * potencia, el consumo del transformador (generador + medidor del transformador) y la red por ese transformador
     * (+ importada / - exportada). Las cifras del detalle siguen siendo las de la planta 1 (medidor principal).
     */
    private void mostrarGraficoMinutos(PeriodoMarcha p) {
        List<GeneradorAnalisisService.MinutoMarcha> minutos = analisis.minutosMarchaTransformador(p);
        if (minutos.isEmpty()) {
            chartMinutos.setVisible(false);
            return;
        }
        List<Long> t = new ArrayList<>();
        List<Double> gen = new ArrayList<>(), consumo = new ArrayList<>(), red = new ArrayList<>();
        for (GeneradorAnalisisService.MinutoMarcha m : minutos) {
            t.add(m.fecha().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            gen.add(m.generadorKw());
            consumo.add(m.consumoKw());
            red.add(m.redKw());
        }
        chartMinutos.setVisible(true);
        getElement().executeJs(GraficaModel.conTooltipsTransparentes(GraficaModel.getMarchaMinutoScript(CHART_MINUTOS, t, gen, consumo, red,
                "Consumo " + GeneradorView.deRed(p.red()), NARANJA, AZUL, AQUA)));
    }

    private void mostrarMaquinas(PeriodoMarcha p) {
        if (p == null) {
            limpiarDetalleMaquinas();
            return;
        }
        List<ConsumoMedidor> lista = consumoMedidores.calcular(p.inicio(), p.fin(), p.red());
        maquinasGrid.setItems(lista);
        maquinasGrid.setVisible(true);
        maquinasTitulo.setText(p.generador() + " trabajo desde el " + p.inicio().format(CORTA)
                + (p.enCurso() ? " hasta ahora (sigue en marcha)" : " hasta el " + p.fin().format(CORTA))
                + " (" + duracion(p.horas()) + ") con las maquinas " + GeneradorView.deRed(p.red()));
        kpisDetalle.removeAll();
        kpisDetalle.add(kpiTomadoDeRed(p.porcentajeDeRed(), p.kwhRedImportada(), p.consumo()));
        kpisDetalle.add(kpiBalance(p.balanceNeto(), p.consumo()));
        kpisDetalle.add(tarjeta("Tiempo importando de la red", p.porcentajeTiempoTomando() == null ? "-"
                        : String.format(Locale.ROOT, "%.0f %%", p.porcentajeTiempoTomando()),
                p.lecturasTomando() + " de " + p.lecturasRed() + " minutos"
                        + (p.picoTomadoKw() == null ? "" : String.format(Locale.ROOT, "; pico %.0f kW", p.picoTomadoKw()))));
        kpisDetalle.add(tarjeta("Exportada a la red", kwh(p.kwhRedExportada()), "excedente"));
        kpisDetalle.add(tarjeta("Generado", kwh(p.kwhGenerado()), p.kwMedio() == null ? null
                : String.format(Locale.ROOT, "%.0f kW medio", p.kwMedio())));
        mostrarBalance(p, lista);
        mostrarGraficoMinutos(p);

        Double consumo = p.consumo();
        StringBuilder r = new StringBuilder();
        if (consumo != null && p.porcentajeDeRed() != null) {
            r.append(String.format(Locale.ROOT, "La planta 1 consumio %,.0f kWh: el generador aporto el %.1f%% y de la red se importo el %.1f%% "
                    + "(%,.0f kWh)", consumo, p.aporteGenerador(), p.porcentajeDeRed(), p.kwhRedImportada()));
            if (p.porcentajeTiempoTomando() != null && p.kwhRedImportada() >= 0.5) {
                r.append(String.format(Locale.ROOT, ", en el %.0f%% del tiempo", p.porcentajeTiempoTomando()));
            }
            if (p.kwhRedExportada() != null && p.kwhRedExportada() >= 0.5) {
                r.append(String.format(Locale.ROOT, ". Ademas se exportaron %,.0f kWh de excedente a la red", p.kwhRedExportada()));
                if (p.balanceNeto() != null) {
                    r.append(p.balanceNeto() <= 0
                            ? String.format(Locale.ROOT, ": en neto se exportaron %,.0f kWh mas de los que se importaron", -p.balanceNeto())
                            : String.format(Locale.ROOT, "; en neto se importaron %,.0f kWh de la red", p.balanceNeto()));
                }
            }
            r.append(". ");
        }
        r.append(grupo(lista, ConsumoMedidoresService.Estado.ENCENDIDO, "Encendido todo el arranque", false));
        r.append(grupo(lista, ConsumoMedidoresService.Estado.ENERGIZADO_Y_SIN_PRODUCCION, "Energizado y sin produccion", true));
        r.append(grupo(lista, ConsumoMedidoresService.Estado.APAGADO_CONSUMO_MINIMO, "Apagado, consumo minimo", true));
        r.append(grupo(lista, ConsumoMedidoresService.Estado.APAGADO_CERO, "Apagado (en cero)", true));
        r.append(grupo(lista, ConsumoMedidoresService.Estado.SIN_DATOS, "Sin datos", true));
        double desperdicio = ConsumoMedidoresService.sumaEnEspera(lista);
        if (desperdicio >= 0.05) {
            List<String> detalle = lista.stream().filter(c -> c.dentroDe() == null && c.kwhEnEspera() != null && c.kwhEnEspera() >= 0.5)
                    .sorted((x, y) -> Double.compare(y.kwhEnEspera(), x.kwhEnEspera()))
                    .map(c -> String.format(Locale.ROOT, "%s %,.1f", c.medidor(), c.kwhEnEspera())).toList();
            r.append(String.format(Locale.ROOT, "Desperdicio: las maquinas sin produccion consumieron %,.1f kWh mientras el generador "
                    + "estaba en marcha, energia que aporto el generador o la red sin producir%s.", desperdicio,
                    detalle.isEmpty() ? "" : " (" + String.join(", ", detalle) + " kWh)"));
        }
        maquinasResumen.setText(r.toString().trim());
    }

    /** "Apagado, consumo minimo: 2 (Linea05, Mixer01). " o vacío si no hay. */
    private static String grupo(List<ConsumoMedidor> lista, ConsumoMedidoresService.Estado estado, String titulo, boolean nombres) {
        List<String> m = lista.stream().filter(c -> c.estado() == estado).map(ConsumoMedidor::medidor).toList();
        if (m.isEmpty()) return "";
        return titulo + ": " + m.size() + (nombres ? " (" + String.join(", ", m) + ")" : " maquinas") + ". ";
    }

    private static Span badgeEstado(ConsumoMedidor c) {
        Span s = new Span();
        Estado e = switch (c.estado()) {
            case ENCENDIDO -> Estado.OK;
            case ENERGIZADO_Y_SIN_PRODUCCION -> Estado.AVISO;
            case APAGADO_CONSUMO_MINIMO -> Estado.FUERA;
            case APAGADO_CERO, SIN_DATOS -> Estado.SIN_DATO;
        };
        GeneradorView.badge(s, c.estado().etiqueta(), e);
        return s;
    }

    // ================= Utilidades =================

    private static <T> Grid.Column<T> columna(Grid<T> g, String titulo, Function<T, String> valor) {
        return g.addColumn(valor::apply).setHeader(titulo).setAutoWidth(true).setFlexGrow(0).setTextAlign(ColumnTextAlign.END);
    }

    private static Component tarjeta(String titulo, String valor, String detalle) {
        Span t = new Span(titulo);
        t.getStyle().set("font-size", "12px").set("color", "#52514e");
        Span v = new Span(valor);
        v.getStyle().set("font-size", "22px").set("font-weight", "700").set("color", "#0b0b0b");
        VerticalLayout c = new VerticalLayout(t, v);
        if (detalle != null) {
            Span d = new Span(detalle);
            d.getStyle().set("font-size", "11px").set("color", "#777");
            c.add(d);
        }
        c.setSpacing(false);
        c.setPadding(false);
        c.setWidth("200px");
        c.getStyle().set("border", "1px solid #ddd").set("border-radius", "8px").set("padding", "10px 14px");
        return c;
    }

    /** Tarjeta de indicador con semáforo (fondo y borde del color del estado). */
    private static Component tarjetaKpi(String titulo, String valor, String detalle, Estado e) {
        String[] c = switch (e) {
            case OK -> new String[]{"#d4edda", "#155724"};
            case AVISO -> new String[]{"#fff3cd", "#856404"};
            case FUERA -> new String[]{"#f8d7da", "#721c24"};
            case SIN_DATO -> new String[]{"#ffffff", "#383d41"};
        };
        Span t = new Span(titulo);
        t.getStyle().set("font-size", "12px").set("color", c[1]).set("font-weight", "600");
        Span v = new Span(valor);
        v.getStyle().set("font-size", "26px").set("font-weight", "700").set("color", c[1]);
        VerticalLayout k = new VerticalLayout(t, v);
        if (detalle != null) {
            Span d = new Span(detalle);
            d.getStyle().set("font-size", "11px").set("color", c[1]);
            k.add(d);
        }
        k.setSpacing(false);
        k.setPadding(false);
        k.setWidth("220px");
        k.getStyle().set("border", "2px solid " + c[1]).set("background-color", c[0]).set("border-radius", "8px")
                .set("padding", "10px 14px");
        return k;
    }

    private static Span nota(String texto) {
        Span s = new Span(texto);
        s.getStyle().set("font-size", "12px").set("color", "#555");
        return s;
    }

    /** Qué muestra la pestaña, en una línea, arriba de todo. */
    private static Span descripcion(String texto) {
        Span s = new Span(texto);
        s.getStyle().set("font-size", "14px").set("color", "#0b0b0b").set("background-color", "#f3f4f6")
                .set("padding", "8px 12px").set("border-radius", "6px").set("display", "block");
        return s;
    }

    private static Double suma(Double a, Double b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + b;
    }

    private static String kwh(Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%,.0f kWh", v);
    }

    private static String kwhNum(Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%,.0f", v);
    }

    private static String horas(Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%.1f h", v);
    }

    private static String horometro(Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String duracion(double horas) {
        long min = Math.round(horas * 60);
        return min < 60 ? min + " min" : String.format(Locale.ROOT, "%d h %02d min", min / 60, min % 60);
    }

    private static String minutos(double m) {
        return m <= 0 ? "-" : String.format(Locale.ROOT, "%.0f", m);
    }

    private static String porcentaje(Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%.1f %%", v);
    }

    private static String dinero(double v) {
        return String.format(Locale.ROOT, "$ %,.2f", v);
    }
}
