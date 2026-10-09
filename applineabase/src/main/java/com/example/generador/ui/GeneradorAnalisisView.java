package com.example.generador.ui;

import com.example.base.model.GraficaModel;
import com.example.base.ui.ChartsView;
import com.example.base.ui.MainLayout;
import com.example.calidad.service.CalidadEnergiaService.Estado;
import com.example.generador.model.CostoCombustible;
import com.example.generador.model.Generador;
import com.example.generador.model.TarifaRed;
import com.example.generador.service.ConsumoMedidoresService;
import com.example.generador.service.ConsumoMedidoresService.ConsumoMedidor;
import com.example.generador.service.CostoCombustibleService;
import com.example.generador.service.GeneradorAnalisisService;
import com.example.generador.service.GeneradorAnalisisService.Agrupacion;
import com.example.generador.service.GeneradorAnalisisService.Fila;
import com.example.generador.service.GeneradorAnalisisService.PeriodoMarcha;
import com.example.generador.service.GeneradorService;
import com.example.generador.service.TarifaRedService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
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

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Análisis del grupo electrógeno, con el mismo filtro (generador y período) para todas las pestañas:
 * - Energía: importada de la red, retornada a la red (solo con el generador en marcha), generada y consumo del tablero por
 *   día, semana o mes; horas en paralelo / isla / vacío; arranques.
 * - Períodos en marcha: por cada arranque, lo que trabajó el generador y lo que se tomó de la red y
 *   se retornó a la red en ese mismo intervalo.
 * - Costos: galones y precio del galón cargados a mano por período en marcha; costo, costo por kWh
 *   y kWh por galón calculados.
 *   Al elegir un arranque se ve con qué máquinas trabajó el generador (trabajando / a ratos / paradas).
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
    private final TarifaRedService tarifaService;
    private final LineaAccessService lineaAccessService;
    private List<TarifaRed> tarifas = List.of();
    private final Grid<TarifaRed> tarifasGrid = new Grid<>();

    private final ComboBox<String> generadorCombo = new ComboBox<>("Generador");
    private final ComboBox<Agrupacion> agrupacionCombo = new ComboBox<>("Ver por");
    private final ComboBox<YearMonth> mesCombo = new ComboBox<>("Mes");
    private final ComboBox<Integer> semanasCombo = new ComboBox<>("Semanas");
    private final ComboBox<Integer> anioCombo = new ComboBox<>("Anio");
    private final Span mensaje = new Span();
    private final TabSheet tabs = new TabSheet();
    private Tab tabEnergia;
    private String scriptGrafico;

    // Energía
    private final HorizontalLayout tarjetasEnergia = new HorizontalLayout();
    private final Grid<Fila> grid = new Grid<>();
    // Períodos en marcha
    private final HorizontalLayout tarjetasPeriodos = new HorizontalLayout();
    private final Grid<PeriodoMarcha> periodosGrid = new Grid<>();
    // Costos
    private final HorizontalLayout tarjetasCostos = new HorizontalLayout();
    private final Grid<PeriodoMarcha> costosGrid = new Grid<>();
    private Map<String, CostoCombustible> combustible = Map.of();
    private List<PeriodoMarcha> periodos = List.of();
    // Máquinas del período en marcha elegido
    private final H4 maquinasTitulo = new H4();
    private final Span maquinasResumen = new Span();
    private final Grid<ConsumoMedidor> maquinasGrid = new Grid<>();

    private LocalDate desde, hasta;

    public GeneradorAnalisisView(GeneradorService generadorService, GeneradorAnalisisService analisis,
                                 ConsumoMedidoresService consumoMedidores, CostoCombustibleService costos,
                                 TarifaRedService tarifaService, LineaAccessService lineaAccessService) {
        this.generadorService = generadorService;
        this.analisis = analisis;
        this.consumoMedidores = consumoMedidores;
        this.costos = costos;
        this.tarifaService = tarifaService;
        this.lineaAccessService = lineaAccessService;
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
        HorizontalLayout filtros = new HorizontalLayout(generadorCombo, agrupacionCombo, mesCombo, semanasCombo, anioCombo, ver);
        filtros.setAlignItems(Alignment.END);
        filtros.getStyle().set("flex-wrap", "wrap");
        mensaje.getStyle().set("font-size", "12px").set("color", "#555");

        tabs.setWidthFull();
        tabEnergia = tabs.add("Resumen de energia", crearEnergia());
        tabs.add("Periodos en marcha", crearPeriodos());
        tabs.add("Costos", crearCostos());
        // El gráfico se arma con la pestaña visible (con display:none amCharts lo dibuja sin tamaño).
        tabs.addSelectedChangeListener(e -> {
            if (e.getSelectedTab() == tabEnergia && scriptGrafico != null) getElement().executeJs(scriptGrafico);
        });

        add(filtros, mensaje, tabs);
        ver();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!lineaAccessService.puedeVerAlarmas()) {
            Notification.show("No tienes permiso para ver el grupo electrogeno", 3000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            event.forwardTo(ChartsView.class);
        }
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
        verEnergia(gens, a);
        periodos = analisis.periodosEnMarcha(gens, desde, hasta);
        verPeriodos();
        verCostos();
        limpiarDetalleMaquinas();

        String redes = gens.stream().map(Generador::redAsociada).filter(r -> r != null).distinct()
                .reduce((x, y) -> x + " + " + y).orElse("sin red asociada");
        mensaje.setText(gens.stream().map(Generador::nombre).reduce((x, y) -> x + " + " + y).orElse("") + " con " + redes
                + ", del " + desde.format(DIA) + " al " + hasta.format(DIA) + " (" + (System.currentTimeMillis() - t0) + " ms).");
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
        columna(grid, "Importado red (kWh)", f -> kwhNum(f.redImportada()));
        columna(grid, "Retornado en marcha (kWh)", f -> kwhNum(f.redExportada()));
        columna(grid, "Generado (kWh)", f -> kwhNum(f.generado()));
        columna(grid, "En paralelo (kWh)", f -> kwhNum(f.generadoParalelo()));
        columna(grid, "En isla (kWh)", f -> kwhNum(f.generadoIsla()));
        columna(grid, "Consumo tablero (kWh)", f -> kwhNum(f.consumo()));
        columna(grid, "Generado / consumo", f -> porcentaje(f.generadoSobreConsumo()));
        columna(grid, "Cubierto por el generador", f -> porcentaje(f.aporteGenerador()));
        columna(grid, "Horas marcha", f -> horas(f.horasMarcha()));
        columna(grid, "En paralelo (h)", f -> horas(f.horasParalelo()));
        columna(grid, "En isla (h)", f -> horas(f.horasIsla()));
        columna(grid, "En vacio (h)", f -> horas(f.horasVacio()));
        columna(grid, "Arranques", f -> f.arranques() == null ? "-" : String.valueOf(f.arranques()));
        columna(grid, "Carga max (kW)", f -> f.kwMax() == null ? "-" : String.format(Locale.ROOT, "%.0f", f.kwMax()));
        grid.setWidthFull();
        grid.setHeight("420px");

        Span nota = nota("Importado y retornado = contadores de energia del medidor del transformador asociado (PLC). El "
                + "importado es de todo el dia; el retornado solo cuenta mientras el generador estaba en marcha (desde cada arranque "
                + "hasta su parada). Generado = contador de energia del controlador del generador. Consumo del tablero = importado + "
                + "generado - retornado. En paralelo = el generador tenia carga y habia tension de red; en isla = con carga y "
                + "sin tension de red; en vacio = en marcha con menos de " + (int) GeneradorAnalisisService.KW_VACIO + " kW. "
                + "Datos del generador: GenPower desde el 08-10-2026, Caterpillar desde el 09-10-2026 09:19. "
                + "La separacion paralelo / isla existe desde el 09-10-2026. El retorno se dibuja por debajo de cero. \"-\" = sin datos.");
        VerticalLayout v = new VerticalLayout(descripcion("Todo el periodo elegido: por cada dia, semana o mes, cuanta energia entro "
                + "al tablero desde la red (transformador), cuanta puso el generador, cuanta volvio a la red mientras el generador estaba "
                + "en marcha, y cuanto consumio el tablero en total."), tarjetasEnergia, chart, grid, nota);
        v.setPadding(false);
        v.setWidthFull();
        return v;
    }

    private void verEnergia(List<Generador> gens, Agrupacion a) {
        List<Fila> filas = analisis.analizar(gens, a, desde, hasta);
        Fila t = analisis.total(filas);
        grid.setItems(filas);
        tarjetasEnergia.removeAll();
        tarjetasEnergia.add(tarjeta("Importado de la red", kwh(t.redImportada()), null));
        tarjetasEnergia.add(tarjeta("Retornado a la red", kwh(t.redExportada()), "con el generador en marcha"));
        tarjetasEnergia.add(tarjeta("Generado", kwh(t.generado()),
                t.generadoParalelo() == null && t.generadoIsla() == null ? null
                        : "en paralelo " + kwh(t.generadoParalelo()) + " / en isla " + kwh(t.generadoIsla())));
        tarjetasEnergia.add(tarjeta("Consumo del tablero", kwh(t.consumo()), "importado + generado - retornado"));
        tarjetasEnergia.add(tarjeta("Generado / consumo", porcentaje(t.generadoSobreConsumo()),
                "mas de 100% = sobro energia y volvio a la red"));
        tarjetasEnergia.add(tarjeta("Cubierto por el generador", porcentaje(t.aporteGenerador()), "del consumo del tablero"));
        tarjetasEnergia.add(tarjeta("Horas de marcha", horas(t.horasMarcha()),
                "paralelo " + horas(t.horasParalelo()) + " / isla " + horas(t.horasIsla()) + " / vacio " + horas(t.horasVacio())));
        tarjetasEnergia.add(tarjeta("Arranques", t.arranques() == null ? "-" : String.valueOf(t.arranques()), null));
        tarjetasEnergia.add(tarjeta("Carga maxima", t.kwMax() == null ? "-" : String.format(Locale.ROOT, "%.0f kW", t.kwMax()), null));

        List<String> categorias = new ArrayList<>();
        List<Double[]> valores = new ArrayList<>();
        for (Fila f : filas) {
            categorias.add(a == Agrupacion.DIA ? f.periodo().substring(0, 5) : f.periodo());
            valores.add(new Double[]{f.redImportada(), f.generado(),
                    f.redExportada() == null || f.redExportada() == 0 ? null : -f.redExportada()});
        }
        scriptGrafico = GraficaModel.getBarrasApiladasScript(CHART_ID, categorias,
                new String[]{"Importado de la red", "Generado", "Retornado a la red (generador en marcha)"},
                new String[]{AZUL, NARANJA, AQUA}, valores, "kWh");
        if (tabs.getSelectedTab() == tabEnergia) getElement().executeJs(scriptGrafico);
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
        columna(periodosGrid, "Generado (kWh)", p -> kwhNum(p.kwhGenerado()));
        columna(periodosGrid, "Tomado de la red (kWh)", p -> kwhNum(p.kwhRedImportada()));
        columna(periodosGrid, "Retornado a la red (kWh)", p -> kwhNum(p.kwhRedExportada()));
        columna(periodosGrid, "Consumo tablero (kWh)", p -> kwhNum(p.consumo()));
        columna(periodosGrid, "Generado / consumo", p -> porcentaje(p.generadoSobreConsumo()));
        columna(periodosGrid, "Cubierto por el generador", p -> porcentaje(p.aporteGenerador()));
        columna(periodosGrid, "Tiempo con excedente", p -> p.porcentajeExcedente() == null ? "-"
                : String.format(Locale.ROOT, "%.0f %%", p.porcentajeExcedente()));
        columna(periodosGrid, "Carga media (kW)", p -> p.kwMedio() == null ? "-" : String.format(Locale.ROOT, "%.0f", p.kwMedio()));
        columna(periodosGrid, "Carga max (kW)", p -> p.kwMax() == null ? "-" : String.format(Locale.ROOT, "%.0f", p.kwMax()));
        columna(periodosGrid, "Paralelo (min)", p -> minutos(p.minParalelo()));
        columna(periodosGrid, "Isla (min)", p -> minutos(p.minIsla()));
        columna(periodosGrid, "Vacio (min)", p -> minutos(p.minVacio()));
        periodosGrid.addColumn(p -> p.red() == null ? "-" : p.red()).setHeader("Red").setAutoWidth(true).setFlexGrow(0);
        periodosGrid.setWidthFull();
        periodosGrid.setHeight("360px");
        periodosGrid.setSelectionMode(Grid.SelectionMode.SINGLE);
        periodosGrid.asSingleSelect().addValueChangeListener(e -> mostrarMaquinas(e.getValue()));
        Span nota = nota("Cada fila es una vez que el generador estuvo encendido (arranque registrado). Tomado y retornado = "
                + "contadores del medidor del transformador asociado en ese mismo intervalo; las lecturas son de cada minuto y "
                + "el minuto que cruza el inicio o el fin se reparte en proporcion. Consumo del tablero = tomado + generado - "
                + "retornado. Generado / consumo: mas de 100% = el generador produjo mas de lo que consumio el tablero y lo que sobro "
                + "volvio a la red. Cubierto por el generador = parte del consumo que puso el generador (nunca pasa de 100%); lo que "
                + "falta hasta 100% se tomo de la red en los minutos en que el generador no alcanzaba. Tiempo con excedente = minutos "
                + "en que el generador cubrio todo el consumo y ademas entrego a la red (el medidor del transformador marco potencia "
                + "negativa). Paralelo / isla / vacio: minutos segun la tension de red y la carga (desde el 09-10-2026).");
        VerticalLayout v = new VerticalLayout(descripcion("Solo los momentos en que el generador estuvo encendido: una fila por "
                + "cada vez que arranco, con lo que trabajo y lo que se tomo y se retorno a la red mientras tanto. "
                + "Haga clic en un arranque para ver abajo con que maquinas trabajo."),
                tarjetasPeriodos, periodosGrid, nota, crearDetalleMaquinas());
        v.setPadding(false);
        v.setWidthFull();
        return v;
    }

    private void verPeriodos() {
        periodosGrid.setItems(periodos);
        double horas = 0;
        Double gen = null, imp = null, exp = null, cons = null;
        for (PeriodoMarcha p : periodos) {
            horas += p.horas();
            gen = suma(gen, p.kwhGenerado());
            imp = suma(imp, p.kwhRedImportada());
            exp = suma(exp, p.kwhRedExportada());
            cons = suma(cons, p.consumo());
        }
        tarjetasPeriodos.removeAll();
        tarjetasPeriodos.add(tarjeta("Periodos en marcha", String.valueOf(periodos.size()), null));
        tarjetasPeriodos.add(tarjeta("Tiempo en marcha", duracion(horas), null));
        tarjetasPeriodos.add(tarjeta("Generado", kwh(gen), horas > 0 && gen != null
                ? String.format(Locale.ROOT, "carga media %.0f kW", gen / horas) : null));
        tarjetasPeriodos.add(tarjeta("Tomado de la red", kwh(imp), "mientras el generador estaba encendido"));
        tarjetasPeriodos.add(tarjeta("Retornado a la red", kwh(exp), "mientras el generador estaba encendido"));
        tarjetasPeriodos.add(tarjeta("Consumo del tablero", kwh(cons), "tomado + generado - retornado"));
        if (gen != null && cons != null && cons > 0) {
            tarjetasPeriodos.add(tarjeta("Generado / consumo", porcentaje(gen / cons * 100),
                    "mas de 100% = sobro energia y volvio a la red"));
            tarjetasPeriodos.add(tarjeta("Cubierto por el generador",
                    porcentaje(Math.max(0, Math.min(100, (gen - (exp == null ? 0 : exp)) / cons * 100))),
                    "el resto se tomo de la red"));
        }
    }

    // ================= Costos =================

    private VerticalLayout crearCostos() {
        tarjetasCostos.setWidthFull();
        tarjetasCostos.getStyle().set("flex-wrap", "wrap");
        costosGrid.addColumn(PeriodoMarcha::generador).setHeader("Generador").setAutoWidth(true).setFlexGrow(0).setFrozen(true);
        costosGrid.addColumn(p -> p.nro() == null ? "-" : String.valueOf(p.nro())).setHeader("Nro").setAutoWidth(true).setFlexGrow(0);
        costosGrid.addColumn(p -> p.inicio().format(CORTA)).setHeader("Inicio").setAutoWidth(true).setFlexGrow(0);
        columna(costosGrid, "Duracion", p -> duracion(p.horas()) + (p.enCurso() ? " (en marcha)" : ""));
        columna(costosGrid, "Generado (kWh)", p -> kwhNum(p.kwhGenerado()));
        columna(costosGrid, "Galones", p -> c(p) == null ? "-" : String.format(Locale.ROOT, "%,.2f", c(p).getGalones()));
        columna(costosGrid, "Precio galon", p -> c(p) == null ? "-" : dinero(c(p).getPrecioGalon()));
        columna(costosGrid, "Costo", p -> c(p) == null ? "-" : dinero(c(p).costo()));
        columna(costosGrid, "Costo por kWh", p -> c(p) == null || p.kwhGenerado() == null || p.kwhGenerado() <= 0 ? "-"
                : String.format(Locale.ROOT, "$ %.3f", c(p).costo() / p.kwhGenerado()));
        columna(costosGrid, "kWh por galon", p -> c(p) == null || c(p).getGalones() <= 0 || p.kwhGenerado() == null ? "-"
                : String.format(Locale.ROOT, "%.1f", p.kwhGenerado() / c(p).getGalones()));
        columna(costosGrid, "Galones por hora", p -> c(p) == null || p.horas() <= 0 ? "-"
                : String.format(Locale.ROOT, "%.1f", c(p).getGalones() / p.horas()));
        columna(costosGrid, "Aprovechado (kWh)", p -> kwhNum(aprovechado(p)));
        columna(costosGrid, "Tarifa red", p -> tarifa(p) == null ? "sin tarifa"
                : String.format(Locale.ROOT, "$ %.4f", tarifa(p).getPrecioKwh()));
        columna(costosGrid, "Costo en la red", p -> costoRed(p) == null ? "-" : dinero(costoRed(p)));
        costosGrid.addComponentColumn(p -> ahorroSpan(ahorro(p))).setHeader("Ahorro").setAutoWidth(true).setFlexGrow(0)
                .setTextAlign(ColumnTextAlign.END);
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
        Span nota = nota("Por cada periodo en marcha se cargan a mano los galones de diesel usados y el precio del galon"
                + (lineaAccessService.esAdmin() ? " (boton Cargar / Editar)" : " (solo el administrador)")
                + ". El costo, el costo por kWh generado, los kWh por galon y los galones por hora se calculan. "
                + "Aprovechado = generado - retornado a la red (lo que volvio a la red no se ahorro: se asume que el distribuidor "
                + "no lo paga). Costo en la red = aprovechado x tarifa vigente al inicio del periodo. Ahorro = costo en la red - "
                + "costo del combustible; negativo (rojo) = el generador costo mas que la red. "
                + "Los totales de arriba solo cuentan los periodos con combustible cargado.");

        H4 tituloTarifas = new H4("Tarifas de la red");
        tituloTarifas.getStyle().set("margin", "16px 0 0 0");
        tarifasGrid.addColumn(t -> MESES[t.getVigenteDesde().getMonthValue() - 1] + " " + t.getVigenteDesde().getYear())
                .setHeader("Vigente desde").setAutoWidth(true).setFlexGrow(0);
        tarifasGrid.addColumn(t -> String.format(Locale.ROOT, "$ %.4f", t.getPrecioKwh())).setHeader("Precio por kWh")
                .setAutoWidth(true).setFlexGrow(0).setTextAlign(ColumnTextAlign.END);
        tarifasGrid.addColumn(t -> t.getObservacion() == null ? "" : t.getObservacion()).setHeader("Observacion").setAutoWidth(true);
        tarifasGrid.addColumn(t -> (t.getUsuario() == null ? "" : t.getUsuario()) + " " + t.getFechaRegistro().format(CORTA))
                .setHeader("Cargada por").setAutoWidth(true).setFlexGrow(0);
        tarifasGrid.setWidth("760px");
        tarifasGrid.setAllRowsVisible(true);
        VerticalLayout seccionTarifas = new VerticalLayout(tituloTarifas, nota("Precio de la energia de la red por mes de vigencia "
                + "(por ejemplo, el valor medio por kWh de la factura). Cada periodo usa la tarifa vigente en su inicio; rige hasta "
                + "que se cargue otra." + (lineaAccessService.esAdmin() ? " Clic en una fila para editarla." : "")));
        seccionTarifas.setPadding(false);
        if (lineaAccessService.esAdmin()) {
            Button nueva = new Button("Nueva tarifa", VaadinIcon.PLUS.create(), e -> abrirDialogoTarifa(null));
            seccionTarifas.add(nueva);
            tarifasGrid.addItemClickListener(e -> abrirDialogoTarifa(e.getItem()));
        }
        seccionTarifas.add(tarifasGrid);

        VerticalLayout v = new VerticalLayout(descripcion("Lo que costo el combustible de cada periodo en marcha (cargado a mano) y la "
                + "comparacion con lo que habria costado tomar esa energia de la red."), tarjetasCostos, costosGrid, nota, seccionTarifas);
        v.setPadding(false);
        v.setWidthFull();
        return v;
    }

    private TarifaRed tarifa(PeriodoMarcha p) {
        return TarifaRedService.vigente(tarifas, p.inicio().toLocalDate()).orElse(null);
    }

    /** Energía del generador que se usó en la planta: generada - retornada a la red. */
    private static Double aprovechado(PeriodoMarcha p) {
        return p.kwhGenerado() == null ? null : Math.max(0, p.kwhGenerado() - (p.kwhRedExportada() == null ? 0 : p.kwhRedExportada()));
    }

    private Double costoRed(PeriodoMarcha p) {
        Double a = aprovechado(p);
        TarifaRed t = tarifa(p);
        return a == null || t == null ? null : a * t.getPrecioKwh();
    }

    private Double ahorro(PeriodoMarcha p) {
        Double red = costoRed(p);
        return red == null || c(p) == null ? null : red - c(p).costo();
    }

    private static Span ahorroSpan(Double v) {
        Span s = new Span(v == null ? "-" : dinero(v));
        if (v != null) s.getStyle().set("color", v >= 0 ? "#155724" : "#721c24").set("font-weight", "600");
        return s;
    }

    private void abrirDialogoTarifa(TarifaRed actual) {
        Dialog d = new Dialog();
        d.setHeaderTitle(actual == null ? "Nueva tarifa de la red" : "Editar tarifa de la red");
        ComboBox<YearMonth> mes = new ComboBox<>("Vigente desde");
        List<YearMonth> meses = new ArrayList<>();
        for (YearMonth m = YearMonth.now().plusMonths(12); meses.size() < 60; m = m.minusMonths(1)) meses.add(m);
        mes.setItems(meses);
        mes.setItemLabelGenerator(m -> MESES[m.getMonthValue() - 1] + " " + m.getYear());
        mes.setWidthFull();
        NumberField precio = new NumberField("Precio por kWh ($)");
        precio.setMin(0.0001);
        precio.setMax(TarifaRedService.PRECIO_MAXIMO);
        precio.setStep(0.0001);
        precio.setWidthFull();
        TextField observacion = new TextField("Observacion (opcional)");
        observacion.setMaxLength(255);
        observacion.setPlaceholder("ej. factura de septiembre, tarifa industrial");
        observacion.setWidthFull();
        if (actual != null) {
            mes.setValue(YearMonth.from(actual.getVigenteDesde()));
            mes.setReadOnly(true);
            precio.setValue(actual.getPrecioKwh());
            observacion.setValue(actual.getObservacion() == null ? "" : actual.getObservacion());
        } else {
            mes.setValue(YearMonth.now());
        }
        VerticalLayout contenido = new VerticalLayout(mes, precio, observacion);
        contenido.setPadding(false);
        contenido.setWidth("360px");
        d.add(contenido);
        Button guardar = new Button("Guardar", e -> {
            if (mes.getValue() == null || precio.getValue() == null || precio.isInvalid()) {
                Notification.show("Elija el mes e ingrese el precio por kWh", 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            try {
                tarifaService.guardar(mes.getValue(), precio.getValue(), observacion.getValue(), lineaAccessService.usuarioActual());
                d.close();
                verCostos();
            } catch (IllegalArgumentException ex) {
                Notification.show(ex.getMessage(), 4000, Notification.Position.MIDDLE).addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        guardar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        d.getFooter().add(new Button("Cancelar", e -> d.close()), guardar);
        if (actual != null) {
            Button borrar = new Button("Borrar", VaadinIcon.TRASH.create(), e -> {
                tarifaService.borrar(actual);
                d.close();
                verCostos();
            });
            borrar.addThemeVariants(ButtonVariant.LUMO_ERROR);
            d.getFooter().addComponentAsFirst(borrar);
        }
        d.open();
    }

    private CostoCombustible c(PeriodoMarcha p) {
        return combustible.get(CostoCombustibleService.clave(p.generador(), p.arranqueId()));
    }

    private void verCostos() {
        combustible = costos.porPeriodo(periodos);
        tarifas = tarifaService.listar();
        tarifasGrid.setItems(tarifas);
        costosGrid.setItems(periodos);
        double galones = 0, costo = 0, kwhConCombustible = 0, horasConCombustible = 0;
        double costoRed = 0, costoComparado = 0, kwhAprovechado = 0;
        int cargados = 0, comparados = 0;
        for (PeriodoMarcha p : periodos) {
            CostoCombustible cc = c(p);
            if (cc == null) continue;
            cargados++;
            galones += cc.getGalones();
            costo += cc.costo();
            horasConCombustible += p.horas();
            if (p.kwhGenerado() != null) kwhConCombustible += p.kwhGenerado();
            Double red = costoRed(p);
            if (red != null) {
                comparados++;
                costoRed += red;
                costoComparado += cc.costo();
                kwhAprovechado += aprovechado(p);
            }
        }
        tarjetasCostos.removeAll();
        tarjetasCostos.add(tarjeta("Galones usados", cargados == 0 ? "-" : String.format(Locale.ROOT, "%,.1f gal", galones),
                cargados + " de " + periodos.size() + " periodos cargados"));
        tarjetasCostos.add(tarjeta("Costo de combustible", cargados == 0 ? "-" : dinero(costo),
                cargados == 0 || galones <= 0 ? null : "precio medio " + dinero(costo / galones) + " por galon"));
        tarjetasCostos.add(tarjeta("Generado", cargados == 0 ? "-" : kwh(kwhConCombustible), "en los periodos cargados"));
        tarjetasCostos.add(tarjeta("Costo por kWh generado", cargados == 0 || kwhConCombustible <= 0 ? "-"
                : String.format(Locale.ROOT, "$ %.3f", costo / kwhConCombustible), null));
        tarjetasCostos.add(tarjeta("Rendimiento", cargados == 0 || galones <= 0 ? "-"
                : String.format(Locale.ROOT, "%.1f kWh/gal", kwhConCombustible / galones), null));
        tarjetasCostos.add(tarjeta("Consumo por hora", cargados == 0 || horasConCombustible <= 0 ? "-"
                : String.format(Locale.ROOT, "%.1f gal/h", galones / horasConCombustible), null));
        if (cargados > 0 && comparados == 0) {
            tarjetasCostos.add(tarjeta("Comparacion con la red", "sin tarifa", "cargue la tarifa de la red (abajo)"));
            return;
        }
        if (comparados == 0) return;
        tarjetasCostos.add(tarjeta("Costo en la red", dinero(costoRed), String.format(Locale.ROOT,
                "%,.0f kWh aprovechados a %s/kWh medio", kwhAprovechado,
                kwhAprovechado > 0 ? String.format(Locale.ROOT, "$ %.4f", costoRed / kwhAprovechado) : "-")));
        double ahorro = costoRed - costoComparado;
        Component t = tarjeta(ahorro >= 0 ? "Ahorro con el generador" : "Sobrecosto del generador", dinero(Math.abs(ahorro)),
                comparados + " periodos comparados");
        t.getElement().getStyle().set("border-color", ahorro >= 0 ? "#155724" : "#721c24")
                .set("background-color", ahorro >= 0 ? "#d4edda" : "#f8d7da");
        tarjetasCostos.add(t);
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
        columna(maquinasGrid, "Parada consumiendo (desperdicio kWh)", c -> c.kwhEnEspera() == null || c.kwhEnEspera() < 0.05 ? "-"
                : String.format(Locale.ROOT, "%,.1f", c.kwhEnEspera()));
        columna(maquinasGrid, "Potencia media (kW)", c -> c.kwMedio() == null ? "-" : String.format(Locale.ROOT, "%,.1f", c.kwMedio()));
        columna(maquinasGrid, "Tiempo trabajando", c -> porcentaje0(c.porcentajeTrabajando()));
        columna(maquinasGrid, "Umbral de encendido", c -> String.format(Locale.ROOT, "%.0f", c.umbral()));
        maquinasGrid.setWidthFull();
        maquinasGrid.setAllRowsVisible(true);
        Span nota = nota("Trabajando = por encima de su umbral de encendido todo el arranque (95% del tiempo o mas). El umbral es el "
                + "mismo del horometro (Horometro > Ajustar umbrales). Trabajando a ratos = paraba y arrancaba, por ejemplo los "
                + "compresores de aire. Parada, consumo en espera = nunca supero su umbral pero consumio (ej. Linea05: su transformador "
                + "de aislamiento, ~1,7 kW). Lo que consume una maquina parada es desperdicio: lo entrega igual el generador o la red. "
                + "Parada (en cero) = todas sus lecturas del arranque fueron cero y su contador no subio (se revisan todas, no un "
                + "promedio). Sin datos = su medidor no tiene lecturas en ese horario. Se listan solo las maquinas del "
                + "transformador del generador (Configuracion de hardware); no entran el transformador, el medidor general, los "
                + "sensores ni MotorL3 / MotorL4. Los submedidores (por ejemplo GA752 dentro de Inyeccion) se muestran pero no se "
                + "suman dos veces en el balance ni en el desperdicio.");
        VerticalLayout v = new VerticalLayout(maquinasTitulo, maquinasResumen, maquinasGrid, nota);
        v.setPadding(false);
        v.setWidthFull();
        limpiarDetalleMaquinas();
        return v;
    }

    private void limpiarDetalleMaquinas() {
        maquinasTitulo.setText("Maquinas durante el arranque");
        maquinasResumen.setText("Haga clic en un arranque de la lista para ver con que maquinas trabajo el generador.");
        maquinasGrid.setItems(List.of());
        maquinasGrid.setVisible(false);
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
                + " (" + duracion(p.horas()) + ") con las maquinas de " + (p.red() == null ? "toda la planta" : p.red()));
        StringBuilder r = new StringBuilder("Genero " + kwh(p.kwhGenerado()));
        Double consumo = p.consumo();
        if (p.generadoSobreConsumo() != null && consumo != null) {
            r.append(String.format(Locale.ROOT, ", el %.0f%% de los %,.0f kWh que consumio el tablero: cubrio el %.1f%% del consumo",
                    p.generadoSobreConsumo(), consumo, p.aporteGenerador()));
            if (p.kwhRedExportada() != null && p.kwhRedExportada() >= 0.5) {
                r.append(String.format(Locale.ROOT, " y le sobraron %,.0f kWh que volvieron a la red", p.kwhRedExportada()));
            }
            if (p.kwhRedImportada() != null && p.kwhRedImportada() >= 0.5) {
                r.append(String.format(Locale.ROOT, "; se tomaron %,.0f kWh de la red en los minutos en que no alcanzaba", p.kwhRedImportada()));
            }
            if (p.porcentajeExcedente() != null) {
                r.append(String.format(Locale.ROOT, ". Cubrio todo el consumo y entrego a la red el %.0f%% del tiempo", p.porcentajeExcedente()));
            }
        }
        r.append(". ");
        Double medido = ConsumoMedidoresService.sumaBalance(lista);
        if (medido != null && consumo != null && consumo > 0) {
            r.append(String.format(Locale.ROOT, "Las maquinas de %s sumaron %,.0f kWh de los %,.0f kWh que consumio el tablero "
                    + "(%.0f%% medido; el resto son cargas sin medidor y perdidas). ", p.red(), medido, consumo, medido / consumo * 100));
        }
        r.append(grupo(lista, ConsumoMedidoresService.Estado.CONSUMIO, "Trabajando", false));
        r.append(grupo(lista, ConsumoMedidoresService.Estado.INTERMITENTE, "Trabajando a ratos", true));
        r.append(grupo(lista, ConsumoMedidoresService.Estado.EN_ESPERA, "Paradas consumiendo en espera", true));
        r.append(grupo(lista, ConsumoMedidoresService.Estado.EN_CERO, "Paradas (en cero)", true));
        r.append(grupo(lista, ConsumoMedidoresService.Estado.SIN_DATOS, "Sin datos", true));
        double desperdicio = ConsumoMedidoresService.sumaEnEspera(lista);
        if (desperdicio >= 0.05) {
            List<String> detalle = lista.stream().filter(c -> c.dentroDe() == null && c.kwhEnEspera() != null && c.kwhEnEspera() >= 0.5)
                    .sorted((x, y) -> Double.compare(y.kwhEnEspera(), x.kwhEnEspera()))
                    .map(c -> String.format(Locale.ROOT, "%s %,.1f", c.medidor(), c.kwhEnEspera())).toList();
            r.append(String.format(Locale.ROOT, "Desperdicio: las maquinas paradas consumieron %,.1f kWh mientras el generador estaba "
                    + "en marcha, energia que entrego el generador o la red sin producir%s.", desperdicio,
                    detalle.isEmpty() ? "" : " (" + String.join(", ", detalle) + " kWh)"));
        }
        maquinasResumen.setText(r.toString().trim());
    }

    /** "Trabajando a ratos: 3 (Linea17, Linea31, CabezalXTR2). " o vacío si no hay. */
    private static String grupo(List<ConsumoMedidor> lista, ConsumoMedidoresService.Estado estado, String titulo, boolean nombres) {
        List<String> m = lista.stream().filter(c -> c.estado() == estado).map(ConsumoMedidor::medidor).toList();
        if (m.isEmpty()) return "";
        return titulo + ": " + m.size() + (nombres ? " (" + String.join(", ", m) + ")" : " maquinas") + ". ";
    }

    private static Span badgeEstado(ConsumoMedidor c) {
        Span s = new Span();
        Estado e = switch (c.estado()) {
            case CONSUMIO -> Estado.OK;
            case INTERMITENTE -> Estado.AVISO;
            case EN_ESPERA -> Estado.FUERA;
            case EN_CERO, SIN_DATOS -> Estado.SIN_DATO;
        };
        String texto = c.estado() == ConsumoMedidoresService.Estado.INTERMITENTE && c.porcentajeTrabajando() != null
                ? String.format(Locale.ROOT, "Trabajando a ratos (%.0f%%)", c.porcentajeTrabajando()) : c.estado().etiqueta();
        GeneradorView.badge(s, texto, e);
        return s;
    }

    // ================= Utilidades =================

    private static <T> void columna(Grid<T> g, String titulo, Function<T, String> valor) {
        g.addColumn(valor::apply).setHeader(titulo).setAutoWidth(true).setFlexGrow(0).setTextAlign(ColumnTextAlign.END);
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

    private static String porcentaje0(Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%.0f %%", v);
    }

    private static String dinero(double v) {
        return String.format(Locale.ROOT, "$ %,.2f", v);
    }
}
