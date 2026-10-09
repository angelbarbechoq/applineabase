package com.example.generador.ui;

import com.example.base.model.GraficaModel;
import com.example.base.ui.ChartsView;
import com.example.base.ui.MainLayout;
import com.example.calidad.service.CalidadEnergiaService.Estado;
import com.example.generador.model.Generador;
import com.example.generador.service.ConsumoMedidoresService;
import com.example.generador.service.ConsumoMedidoresService.ConsumoMedidor;
import com.example.generador.service.ConsumoMedidoresService.Ventana;
import com.example.generador.service.GeneradorAnalisisService;
import com.example.generador.service.GeneradorAnalisisService.Agrupacion;
import com.example.generador.service.GeneradorAnalisisService.Fila;
import com.example.generador.service.GeneradorService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
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
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Análisis del grupo electrógeno: energía importada de la red, exportada a la red (retorno),
 * generada y consumo del tablero por día, semana o mes; horas en paralelo con la red, en isla y en
 * vacío; arranques; y qué medidores consumieron y cuáles quedaron en cero (todo el período o solo
 * mientras el generador estuvo en marcha).
 */
@PageTitle("Analisis grupo electrogeno | LineaBase")
@Route(value = "generador/analisis", layout = MainLayout.class)
@PermitAll
public class GeneradorAnalisisView extends VerticalLayout implements BeforeEnterObserver {

    private static final String CHART_ID = "chartdiv_generador_analisis";
    private static final String TODOS = "Todos";
    private static final String[] MESES = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
            "agosto", "septiembre", "octubre", "noviembre", "diciembre"};
    // Paleta categórica de referencia (validada para daltonismo): azul, naranja, aqua.
    private static final String AZUL = "0x2a78d6", NARANJA = "0xeb6834", AQUA = "0x1baf7a";

    private final GeneradorService generadorService;
    private final GeneradorAnalisisService analisis;
    private final ConsumoMedidoresService consumoMedidores;
    private final LineaAccessService lineaAccessService;

    private final ComboBox<String> generadorCombo = new ComboBox<>("Generador");
    private final ComboBox<Agrupacion> agrupacionCombo = new ComboBox<>("Ver por");
    private final ComboBox<YearMonth> mesCombo = new ComboBox<>("Mes");
    private final ComboBox<Integer> semanasCombo = new ComboBox<>("Semanas");
    private final ComboBox<Integer> anioCombo = new ComboBox<>("Anio");
    private final HorizontalLayout tarjetas = new HorizontalLayout();
    private final Grid<Fila> grid = new Grid<>();
    private final Span mensaje = new Span();
    private final ComboBox<String> modoMedidores = new ComboBox<>("Calcular sobre");
    private final Grid<ConsumoMedidor> medidoresGrid = new Grid<>();
    private final Span medidoresMensaje = new Span();
    private LocalDate desde, hasta;

    public GeneradorAnalisisView(GeneradorService generadorService, GeneradorAnalisisService analisis,
                                 ConsumoMedidoresService consumoMedidores, LineaAccessService lineaAccessService) {
        this.generadorService = generadorService;
        this.analisis = analisis;
        this.consumoMedidores = consumoMedidores;
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

        tarjetas.setWidthFull();
        tarjetas.getStyle().set("flex-wrap", "wrap");
        mensaje.getStyle().set("font-size", "12px").set("color", "#555");

        Div chart = new Div();
        chart.setId(CHART_ID);
        chart.setWidthFull();
        chart.setHeight("380px");

        configurarGrid();
        Span nota = new Span("Importado y exportado (retorno) = contadores de energia del medidor del transformador asociado "
                + "(PLC). Generado = contador de energia del controlador del generador. Consumo del tablero = importado + "
                + "generado - exportado. En paralelo = el generador tenia carga y habia tension de red; en isla = con carga y "
                + "sin tension de red; en vacio = en marcha con menos de " + (int) GeneradorAnalisisService.KW_VACIO + " kW. "
                + "La separacion paralelo / isla existe desde el 09-10-2026 (antes no se guardaba la tension de red). "
                + "\"-\" = sin datos.");
        nota.getStyle().set("font-size", "12px").set("color", "#555");

        add(filtros, tarjetas, mensaje, chart, grid, nota, crearSeccionMedidores());
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
        List<Fila> filas = analisis.analizar(gens, a, desde, hasta);
        Fila total = analisis.total(filas);
        grid.setItems(filas);
        mostrarTarjetas(total);

        List<String> categorias = new ArrayList<>();
        List<Double[]> valores = new ArrayList<>();
        for (Fila f : filas) {
            categorias.add(a == Agrupacion.DIA ? f.periodo().substring(0, 5) : f.periodo());
            valores.add(new Double[]{f.redImportada(), f.generado(), f.redExportada() == null || f.redExportada() == 0 ? null : -f.redExportada()});
        }
        getElement().executeJs(GraficaModel.getBarrasApiladasScript(CHART_ID, categorias,
                new String[]{"Importado de la red", "Generado", "Exportado a la red (retorno)"},
                new String[]{AZUL, NARANJA, AQUA}, valores, "kWh"));
        String redes = gens.stream().map(Generador::redAsociada).filter(r -> r != null).distinct()
                .reduce((x, y) -> x + " + " + y).orElse("sin red asociada");
        mensaje.setText(gens.stream().map(Generador::nombre).reduce((x, y) -> x + " + " + y).orElse("") + " con " + redes
                + ", del " + desde.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"))
                + " al " + hasta.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"))
                + " (" + (System.currentTimeMillis() - t0) + " ms). El retorno se dibuja por debajo de cero.");
        medidoresGrid.setItems(List.of());
        medidoresMensaje.setText("Elija sobre que calcular y presione Calcular.");
    }

    private void mostrarTarjetas(Fila t) {
        tarjetas.removeAll();
        tarjetas.add(tarjeta("Importado de la red", kwh(t.redImportada()), null));
        tarjetas.add(tarjeta("Exportado a la red (retorno)", kwh(t.redExportada()), null));
        tarjetas.add(tarjeta("Generado", kwh(t.generado()),
                t.generadoParalelo() == null && t.generadoIsla() == null ? null
                        : "en paralelo " + kwh(t.generadoParalelo()) + " / en isla " + kwh(t.generadoIsla())));
        tarjetas.add(tarjeta("Consumo del tablero", kwh(t.consumo()), "importado + generado - exportado"));
        tarjetas.add(tarjeta("Aporte del generador", t.aporteGenerador() == null ? "-"
                : String.format(Locale.ROOT, "%.1f %%", t.aporteGenerador()), "del consumo del tablero"));
        tarjetas.add(tarjeta("Horas de marcha", horas(t.horasMarcha()),
                "paralelo " + horas(t.horasParalelo()) + " / isla " + horas(t.horasIsla()) + " / vacio " + horas(t.horasVacio())));
        tarjetas.add(tarjeta("Arranques", t.arranques() == null ? "-" : String.valueOf(t.arranques()), null));
        tarjetas.add(tarjeta("Carga maxima", t.kwMax() == null ? "-" : String.format(Locale.ROOT, "%.0f kW", t.kwMax()), null));
    }

    private void configurarGrid() {
        grid.addColumn(Fila::periodo).setHeader("Periodo").setAutoWidth(true).setFlexGrow(0).setFrozen(true);
        columna("Importado red (kWh)", f -> kwhNum(f.redImportada()));
        columna("Exportado red (kWh)", f -> kwhNum(f.redExportada()));
        columna("Generado (kWh)", f -> kwhNum(f.generado()));
        columna("En paralelo (kWh)", f -> kwhNum(f.generadoParalelo()));
        columna("En isla (kWh)", f -> kwhNum(f.generadoIsla()));
        columna("Consumo tablero (kWh)", f -> kwhNum(f.consumo()));
        columna("Aporte generador", f -> f.aporteGenerador() == null ? "-" : String.format(Locale.ROOT, "%.1f %%", f.aporteGenerador()));
        columna("Horas marcha", f -> horas(f.horasMarcha()));
        columna("En paralelo (h)", f -> horas(f.horasParalelo()));
        columna("En isla (h)", f -> horas(f.horasIsla()));
        columna("En vacio (h)", f -> horas(f.horasVacio()));
        columna("Arranques", f -> f.arranques() == null ? "-" : String.valueOf(f.arranques()));
        columna("Carga max (kW)", f -> f.kwMax() == null ? "-" : String.format(Locale.ROOT, "%.0f", f.kwMax()));
        grid.setWidthFull();
        grid.setHeight("420px");
    }

    private void columna(String titulo, Function<Fila, String> valor) {
        grid.addColumn(valor::apply).setHeader(titulo).setAutoWidth(true).setFlexGrow(0)
                .setTextAlign(com.vaadin.flow.component.grid.ColumnTextAlign.END);
    }

    // ================= Consumo por medidor =================

    private VerticalLayout crearSeccionMedidores() {
        H4 titulo = new H4("Consumo por medidor");
        titulo.getStyle().set("margin", "12px 0 0 0");
        modoMedidores.setItems("Todo el periodo elegido", "Solo mientras el generador estuvo en marcha");
        modoMedidores.setValue("Solo mientras el generador estuvo en marcha");
        modoMedidores.setWidth("340px");
        Button calcular = new Button("Calcular", VaadinIcon.CALC.create(), e -> calcularMedidores());
        HorizontalLayout barra = new HorizontalLayout(modoMedidores, calcular);
        barra.setAlignItems(Alignment.END);

        medidoresGrid.addColumn(ConsumoMedidor::medidor).setHeader("Medidor").setAutoWidth(true).setFlexGrow(0);
        medidoresGrid.addColumn(ConsumoMedidor::zona).setHeader("Zona / grupo").setAutoWidth(true).setFlexGrow(0);
        medidoresGrid.addComponentColumn(c -> badgeEstado(c)).setHeader("Estado").setAutoWidth(true).setFlexGrow(0);
        medidoresGrid.addColumn(c -> c.kwh() == null ? "-" : String.format(Locale.ROOT, "%,.1f", c.kwh()))
                .setHeader("Energia (kWh)").setAutoWidth(true).setFlexGrow(0).setTextAlign(com.vaadin.flow.component.grid.ColumnTextAlign.END);
        medidoresGrid.addColumn(c -> c.kwMedio() == null ? "-" : String.format(Locale.ROOT, "%,.1f", c.kwMedio()))
                .setHeader("Potencia media (kW)").setAutoWidth(true).setFlexGrow(0).setTextAlign(com.vaadin.flow.component.grid.ColumnTextAlign.END);
        medidoresGrid.addColumn(c -> c.porcentajeConCarga() == null ? "-" : String.format(Locale.ROOT, "%.0f %%", c.porcentajeConCarga()))
                .setHeader("Tiempo con potencia").setAutoWidth(true).setFlexGrow(0).setTextAlign(com.vaadin.flow.component.grid.ColumnTextAlign.END);
        medidoresGrid.addColumn(c -> c.lecturasConCarga() + " de " + c.lecturas())
                .setHeader("Lecturas con potencia").setAutoWidth(true).setFlexGrow(0);
        medidoresGrid.setWidthFull();
        medidoresGrid.setHeight("520px");

        medidoresMensaje.getStyle().set("font-size", "12px").set("color", "#555");
        Span nota = new Span("En cero = el contador de energia no subio y todas las lecturas de potencia del intervalo fueron cero "
                + "(se revisan todas, no un promedio). Consumo minimo = hubo potencia pero la potencia media fue menor a "
                + (int) ConsumoMedidoresService.KW_MEDIO_MINIMO + " kW (por ejemplo solo el tablero de control con la maquina parada). "
                + "Intermitente = con potencia menos del 95% del tiempo, por ejemplo compresores que paran y arrancan. "
                + "En los transformadores la potencia negativa (retorno a la red) cuenta como potencia. "
                + "Sin datos = el medidor no tiene lecturas en ese intervalo.");
        nota.getStyle().set("font-size", "12px").set("color", "#555");
        VerticalLayout v = new VerticalLayout(titulo, barra, medidoresMensaje, medidoresGrid, nota);
        v.setPadding(false);
        v.setWidthFull();
        return v;
    }

    private void calcularMedidores() {
        if (desde == null || hasta == null) return;
        long t0 = System.currentTimeMillis();
        LocalDateTime ini = desde.atStartOfDay();
        LocalDateTime fin = hasta.atTime(23, 59, 59);
        if (fin.isAfter(LocalDateTime.now())) fin = LocalDateTime.now();
        List<Ventana> ventanas;
        boolean soloMarcha = modoMedidores.getValue() != null && modoMedidores.getValue().startsWith("Solo");
        if (soloMarcha) {
            ventanas = consumoMedidores.ventanasEnMarcha(seleccionados(), ini, fin);
            if (ventanas.isEmpty()) {
                medidoresGrid.setItems(List.of());
                medidoresMensaje.setText("El generador no tuvo arranques registrados en el periodo elegido.");
                return;
            }
        } else {
            ventanas = List.of(new Ventana(ini, fin));
        }
        List<ConsumoMedidor> lista = consumoMedidores.calcular(ventanas);
        medidoresGrid.setItems(lista);
        double horas = ventanas.stream().mapToDouble(v -> java.time.Duration.between(v.desde(), v.hasta()).toSeconds() / 3600.0).sum();
        long consumio = lista.stream().filter(c -> c.estado() == ConsumoMedidoresService.Estado.CONSUMIO).count();
        long intermitente = lista.stream().filter(c -> c.estado() == ConsumoMedidoresService.Estado.INTERMITENTE).count();
        long minimo = lista.stream().filter(c -> c.estado() == ConsumoMedidoresService.Estado.CONSUMO_MINIMO).count();
        long cero = lista.stream().filter(c -> c.estado() == ConsumoMedidoresService.Estado.EN_CERO).count();
        long sinDatos = lista.stream().filter(c -> c.estado() == ConsumoMedidoresService.Estado.SIN_DATOS).count();
        medidoresMensaje.setText((soloMarcha ? ventanas.size() + " intervalos en marcha, " : "")
                + String.format(Locale.ROOT, "%.1f h", horas) + ": " + consumio + " consumieron, " + intermitente
                + " intermitentes, " + minimo + " con consumo minimo, " + cero + " en cero, " + sinDatos + " sin datos ("
                + (System.currentTimeMillis() - t0) + " ms).");
    }

    private static Span badgeEstado(ConsumoMedidor c) {
        Span s = new Span();
        Estado e = switch (c.estado()) {
            case CONSUMIO -> Estado.OK;
            case INTERMITENTE, CONSUMO_MINIMO -> Estado.AVISO;
            case EN_CERO -> Estado.FUERA;
            case SIN_DATOS -> Estado.SIN_DATO;
        };
        String texto = c.estado() == ConsumoMedidoresService.Estado.INTERMITENTE && c.porcentajeConCarga() != null
                ? String.format(Locale.ROOT, "Intermitente (%.0f%%)", c.porcentajeConCarga()) : c.estado().etiqueta();
        GeneradorView.badge(s, texto, e);
        return s;
    }

    // ================= Utilidades =================

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

    private static String kwh(Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%,.0f kWh", v);
    }

    private static String kwhNum(Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%,.0f", v);
    }

    private static String horas(Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%.1f h", v);
    }
}
