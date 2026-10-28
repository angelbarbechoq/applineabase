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
import com.example.generador.service.GeneradorAnalisisService.PeriodoPlanta;
import com.example.generador.service.GeneradorService;
import com.example.generador.service.PlanillaCombustibleService;
import com.example.generador.service.PlanillaCombustibleService.Calculo;
import com.example.generador.service.GeneradorService.Punto;
import com.example.generador.service.ReporteGeneradoresService;
import com.example.security.LineaAccessService;
import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.generador.service.GeneradorAlmacen;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.shared.Registration;
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

import java.io.File;
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
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Análisis del grupo electrógeno, con el mismo período para todas las pestañas y siempre todos los generadores:
 * - Energía: importada de la red, exportada a la red (solo con algún generador en marcha), generada y consumo de la
 *   planta 1 por día, semana o mes; horas en paralelo / isla / vacío; arranques. La red es la del medidor principal.
 * - Períodos en marcha: períodos de la planta (el tiempo con uno o más generadores en marcha; los arranques que se pisan
 *   van juntos), todo en una pantalla: la meta cero importación de todos los períodos y del seleccionado, lo que puso
 *   cada generador (con su horómetro; reemplaza a la pestaña Arranques que había en Generadores), la lista y el minuto
 *   a minuto de la planta.
 * - Tendencias: una variable guardada del controlador (batería, temperaturas, potencia...) en las últimas 24 h a 90
 *   días; usa solo el generador del filtro, con su propio período (son lecturas de cada minuto).
 * - Costos: galones y precio del galón cargados a mano por período en marcha; comparación del costo del diésel con
 *   lo que habría costado la misma energía en la red, con el precio de la franja horaria de cada hora (tarifas y
 *   feriados de Consumo por franja horaria).
 * En pantalla se nombra el transformador ("Transformador 1"), nunca "el tablero".
 */
@PageTitle("Análisis grupo electrógeno | LineaBase")
@Route(value = "generador/analisis", layout = MainLayout.class)
@PermitAll
public class GeneradorAnalisisView extends VerticalLayout implements BeforeEnterObserver {

    private static final String CHART_ID = "chartdiv_generador_analisis";
    private static final String[] MESES = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
            "agosto", "septiembre", "octubre", "noviembre", "diciembre"};
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter CORTA = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final DateTimeFormatter CORTO = DateTimeFormatter.ofPattern("dd-MM HH:mm");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    // Paleta de la ventana, tomada de los ejemplos del usuario (2026-10-23, estilo StarAdmin): naranja del generador,
    // violeta del segundo generador, azul noche del consumo y del texto, azul de la red y verde azulado de lo exportado.
    // Se distinguen entre sí también con daltonismo (validador de paleta); los claros siempre van con su etiqueta.
    private static final String NARANJA = "0xF29F67", VIOLETA = "0x6F42C1", NOCHE = "0x1E1E2C", AZUL = "0x3B8FF3", AQUA = "0x34B1AA";
    /** Costos: la columna de la red, apilada por franja horaria; tonos del azul de la red (B, la más cara, el más oscuro). */
    private static final String[] NOMBRES_FRANJA = {"Red franja A · L-V 08-18", "Red franja B · L-V 18-22",
            "Red franja C · noche y fin de semana", "Red franja D · S-D-F 18-22"};
    private static final String[] COLORES_FRANJA = {AZUL, "0x1D4F9E", "0xA9CDF9", "0x6E9FE0"};
    /** Color de cada generador en esta ventana (en el orden de generador-config.json). */
    private static final String[] COLORES_GEN = {NARANJA, VIOLETA, "0xE87BA4", "0xEDA100"};
    private static final String GRIS = "0xA0A6B4";
    // Texto, bordes y fondos de la ventana.
    private static final String TEXTO = "#1E1E2C", TEXTO_SUAVE = "#6C7383", BORDE = "#E4E6EC", FONDO_SUAVE = "#F4F5F7";
    private static final String CHART_INDICADOR = "chartdiv_indicador_meta";
    private static final String CHART_INDICADOR_TODOS = "chartdiv_indicador_meta_todos";

    private final GeneradorService generadorService;
    private final GeneradorAnalisisService analisis;
    private final ConsumoMedidoresService consumoMedidores;
    private final CostoCombustibleService costos;
    private final PlanillaCombustibleService planillaCombustible;
    private final ReporteGeneradoresService reporte;
    private final LineaAccessService lineaAccessService;
    /** Semáforo de la meta "consumo cero de la red" (generador-config.json); se relee en cada "Ver". */
    private MetaImportacionRed meta;

    private final ComboBox<Agrupacion> agrupacionCombo = new ComboBox<>("Ver por");
    private final ComboBox<YearMonth> mesCombo = new ComboBox<>("Mes");
    /** Mes del reporte de generadores, independiente del periodo de arriba (se pueden sacar meses pasados). */
    private final ComboBox<YearMonth> mesReporteCombo = new ComboBox<>("Mes del reporte");
    private volatile YearMonth mesReporte = YearMonth.now();
    private final ComboBox<Integer> semanasCombo = new ComboBox<>("Semanas");
    private final ComboBox<Integer> anioCombo = new ComboBox<>("Año");
    private final Span mensaje = new Span();
    private final TabSheet tabs = new TabSheet();
    private Tab tabEnergia, tabPeriodos, tabTendencias, tabCostos;
    private String scriptGrafico, scriptCostos;
    private static final String CHART_TENDENCIAS = "chartdiv_tendencias_generador";
    private static final String CHART_COSTOS = "chartdiv_costos_red";
    private static final String CHART_MINUTOS = "chartdiv_marcha_minutos";

    // Energía (rediseño del 2026-10-25): tarjetas, barra de reparto, columnas y tabla del detalle.
    private final Div tarjetasEnergia = new Div();
    private final Div barraEnergia = new Div();
    private final Span tituloGraficoEnergia = new Span(), tituloDetalleEnergia = new Span();
    private final Div tablaEnergia = new Div();
    // Períodos en marcha (de la planta: los arranques que se pisan van juntos), todo en una pantalla: arriba la meta de todos
    // los períodos, la del seleccionado y lo que puso cada generador; abajo la lista y el minuto a minuto del seleccionado.
    private List<PeriodoPlanta> periodosPlanta = List.of();
    private final Grid<PeriodoPlanta> periodosGrid = new Grid<>();
    private final Div bloqueIndicador = new Div();
    private final Div chartIndicadorTodos = new Div();
    private final Div indicadorTodosTexto = new Div();
    private String scriptIndicadorTodos;
    private final Div chartIndicador = new Div();
    private final Div indicadorTexto = new Div();
    /** Una tarjeta por generador con lo que puso en la planta en el período seleccionado, y la barra de reparto. */
    private final Div generadoresPeriodo = new Div();
    private final Div chartMinutos = new Div();
    private String scriptMinutos;
    /** Abajo de todo, un gráfico por generador contra su transformador y sus máquinas encendidas (Transformador 1 a la izquierda). */
    private final Div generadoresDetalle = new Div();
    /** Al final de todo, la tabla de cuentas del período seleccionado: una fila por arranque y el total. */
    private final Div resumenPeriodo = new Div();
    /** Script de cada generador (por nombre), su gráfico y sus máquinas, para actualizarlos sin rearmar los recuadros. */
    private Map<String, String> scriptsGenerador = Map.of();
    private final Map<String, Div> chartDe = new HashMap<>(), maquinasDe = new HashMap<>();
    /** Recuadros armados: comienzo del período y generadores que trabajaron. */
    private String recuadrosDibujados;
    /**
     * Período cuyo minuto a minuto y gráficos por generador faltan calcular: lo pesado se hace con la pestaña visible (la
     * ventana abre en Resumen de energía, así no se demora).
     */
    private PeriodoPlanta detallePendiente;
    private PeriodoPlanta periodoInicial;
    /** El rango elegido no tiene períodos: el del indicador es el último de antes. */
    private boolean inicialFueraDelPeriodo;
    private String scriptIndicador;
    // Tendencias
    private final Span tendenciaMensaje = new Span();
    /** Columnas de {mes}Generador que se pueden graficar. */
    private static final Map<String, String[]> VARIABLES = new LinkedHashMap<>();
    static {
        VARIABLES.put("Tensión de batería (V)", new String[]{"bateria"});
        VARIABLES.put("Temperatura refrigerante (C)", new String[]{"temp_refrigerante"});
        VARIABLES.put("Presión de aceite (bar)", new String[]{"presion_aceite"});
        VARIABLES.put("Potencia (kW)", new String[]{"kw"});
        VARIABLES.put("Corrientes (A)", new String[]{"i_l1", "i_l2", "i_l3"});
        VARIABLES.put("Tensión fase-fase (V)", new String[]{"v_l1l2", "v_l2l3", "v_l3l1"});
        VARIABLES.put("Frecuencia (Hz)", new String[]{"frecuencia"});
        VARIABLES.put("RPM", new String[]{"rpm"});
        VARIABLES.put("Tensión de red fase-fase (V)", new String[]{"red_v_l1l2", "red_v_l2l3", "red_v_l3l1"});
        VARIABLES.put("Frecuencia de red (Hz)", new String[]{"red_frecuencia"});
        VARIABLES.put("Temperatura de aceite (C)", new String[]{"temp_aceite"});
        VARIABLES.put("Nivel de combustible (%)", new String[]{"nivel_combustible"});
    }
    // Costos
    private final HorizontalLayout tarjetasCostos = new HorizontalLayout();
    private final Div chartCostos = new Div();
    private final Div marcoCostos = new Div();
    private final Grid<PeriodoMarcha> costosGrid = new Grid<>();
    private Map<String, CostoCombustible> combustible = Map.of();
    /** Rendimiento (kWh/gal) y precio medio del galón de los arranques del rango con diesel cargado; 0 = sin datos. */
    private double kwhPorGalon, precioGalon;
    /** Arranques del período elegido, uno por generador (Costos va por arranque: el diésel se carga por generador). */
    private List<PeriodoMarcha> periodos = List.of();

    private LocalDate desde, hasta;
    /** Generadores del último "Ver" (los que usa el reporte, junto con desde y hasta). */
    private List<Generador> gensVistos = List.of();
    // Actualización (pedido del usuario el 2026-10-23): botón Actualizar y, cada 15 s, si el rango incluye hoy y llegaron
    // datos nuevos, lo mismo que el último "Ver", sin perder el período seleccionado ni el zoom de los gráficos.
    private static final int POLL_MS = 15000, POLL_MS_APP = 30000;
    private Registration pollRegistration;
    private final GeneradorAlmacen almacen;
    /** Había algún generador en marcha en la revisión anterior (para hacer una última actualización cuando para). */
    private boolean habiaMarcha;
    /** Agrupación y fin del rango del último "Ver" (sin recortar a hoy: un rango que sigue abierto crece con los días). */
    private Agrupacion agrupacionVista = Agrupacion.DIA;
    private LocalDate hastaRango;
    /** Última modificación de los archivos de datos del mes al calcular: si no cambió, no hay nada nuevo. */
    private long datosCalculados;
    /** En la actualización: los gráficos se redibujan solo si cambiaron, y los que cambiaron solo reciben los datos. */
    private boolean enActualizacion;
    /** Comienzo del período que el usuario eligió en la lista (null = el inicial); se mantiene al actualizar. */
    private LocalDateTime inicioElegido;
    /** Mientras se recarga la lista, la selección que hace el código no repinta. */
    private boolean cargandoLista;
    /** Período cuyo detalle (minuto a minuto, recuadros por generador) está dibujado: inicio, fin y arranques. */
    private String detalleDibujado;

    public GeneradorAnalisisView(GeneradorService generadorService, GeneradorAnalisisService analisis,
                                 ConsumoMedidoresService consumoMedidores, CostoCombustibleService costos,
                                 ReporteGeneradoresService reporte, LineaAccessService lineaAccessService,
                                 GeneradorAlmacen almacen, PlanillaCombustibleService planillaCombustible) {
        this.planillaCombustible = planillaCombustible;
        this.generadorService = generadorService;
        this.almacen = almacen;
        this.analisis = analisis;
        this.consumoMedidores = consumoMedidores;
        this.costos = costos;
        this.reporte = reporte;
        this.lineaAccessService = lineaAccessService;
        this.meta = generadorService.metaImportacionRed();
        setPadding(true);
        setSpacing(true);
        setWidthFull();

        H3 titulo = new H3("Análisis del grupo electrógeno");
        titulo.getStyle().set("margin", "0");
        add(titulo);
        List<Generador> generadores = generadorService.generadores();
        if (generadores.isEmpty()) {
            add(new Span("No hay generadores en C:\\LineaBaseX\\config\\generador-config.json"));
            return;
        }

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
        semanasCombo.setItemLabelGenerator(n -> "Últimas " + n);
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
        Button actualizar = new Button("Actualizar", VaadinIcon.REFRESH.create(), e -> actualizar(false));
        // Reporte mensual de generadores (desde el 2026-10-27): su propio mes, también los pasados; el mes en curso va
        // del 1 hasta hoy. Se arma al pedirlo (datos del momento) y se abre en una pestaña nueva, donde se imprime o se
        // guarda como PDF. Mismo enlace fijo que la exportación de configuración (ver TraspasoConfiguracionView:
        // cambiarlo en cada clic daba "f.txt"); el mes se toma del campo al pedirlo.
        mesReporteCombo.setItems(meses);
        mesReporteCombo.setItemLabelGenerator(m -> MESES[m.getMonthValue() - 1] + " " + m.getYear());
        mesReporteCombo.setValue(YearMonth.now());
        mesReporteCombo.setWidth("170px");
        mesReporteCombo.addValueChangeListener(e -> { if (e.getValue() != null) mesReporte = e.getValue(); });
        Anchor reporteLink = new Anchor();
        reporteLink.setText("Reporte de generadores");
        reporteLink.getElement().getThemeList().add("button");
        reporteLink.getElement().setAttribute("title", "Abre el reporte del mes elegido en una pestaña nueva, "
                + "para imprimirlo o guardarlo como PDF.");
        reporteLink.setTarget("_blank");
        reporteLink.setHref(event -> {
            YearMonth m = mesReporte;
            List<Generador> gens = generadorService.generadores();
            event.setFileName(ReporteGeneradoresService.nombreArchivo(m));
            event.setContentType("text/html; charset=utf-8");
            try (OutputStream out = event.getOutputStream()) {
                reporte.escribir(out, gens, m);
            }
        }, AttachmentType.INLINE);
        HorizontalLayout reporteFiltro = new HorizontalLayout(mesReporteCombo, reporteLink);
        reporteFiltro.setAlignItems(Alignment.END);
        reporteFiltro.getStyle().set("margin-left", "auto");
        HorizontalLayout filtros = new HorizontalLayout(agrupacionCombo, mesCombo, semanasCombo, anioCombo, ver, actualizar, reporteFiltro);
        filtros.setWidthFull();
        filtros.setAlignItems(Alignment.END);
        filtros.getStyle().set("flex-wrap", "wrap");
        mensaje.getStyle().set("font-size", "12px").set("color", "#555");

        tabs.setWidthFull();
        // Períodos en marcha primero (pedido del usuario el 2026-10-25): es la más usada y la que se actualiza sola.
        tabPeriodos = tabs.add("Periodos en marcha", crearPeriodos());
        tabEnergia = tabs.add("Resumen de energía", crearEnergia());
        tabTendencias = tabs.add("Tendencias", crearTendencias());
        tabCostos = tabs.add("Costos", crearCostos());
        // El gráfico se arma con la pestaña visible (con display:none amCharts lo dibuja sin tamaño).
        tabs.addSelectedChangeListener(e -> {
            if (e.getSelectedTab() == tabEnergia && scriptGrafico != null) getElement().executeJs(scriptGrafico);
            if (e.getSelectedTab() == tabPeriodos && scriptIndicadorTodos != null) getElement().executeJs(scriptIndicadorTodos);
            if (e.getSelectedTab() == tabPeriodos && scriptIndicador != null) getElement().executeJs(scriptIndicador);
            if (e.getSelectedTab() == tabPeriodos && detallePendiente != null) {
                pintarDetalle(detallePendiente);
            } else if (e.getSelectedTab() == tabPeriodos) {
                if (scriptMinutos != null) getElement().executeJs(scriptMinutos);
                scriptsGenerador.values().forEach(s -> getElement().executeJs(s));
            }
            if (e.getSelectedTab() == tabCostos && scriptCostos != null) getElement().executeJs(scriptCostos);
            // Tendencias tiene su propio período (lecturas de cada minuto): el de arriba, Ver y el reporte no le aplican.
            boolean tendencias = e.getSelectedTab() == tabTendencias;
            for (Component c : new Component[]{agrupacionCombo, ver, actualizar, reporteFiltro, mensaje}) c.setVisible(!tendencias);
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

    @Override
    protected void onAttach(AttachEvent e) {
        UI ui = e.getUI();
        ui.setPollInterval(POLL_MS);
        pollRegistration = ui.addPollListener(p -> revisarActualizacion());
    }

    /**
     * Revisión de cada 15 s: solo con la pestaña Períodos en marcha abierta y algún generador en marcha (pedido del
     * usuario el 2026-10-25; los datos del generador se toman cada minuto solo en marcha). En marcha = su arranque está
     * abierto, la misma marca que usa la lectura. Cuando para el último se hace una revisión más, para que el período
     * quede cerrado en pantalla.
     */
    private void revisarActualizacion() {
        if (tabs.getSelectedTab() != tabPeriodos) return;
        boolean marcha = generadorService.generadores().stream().anyMatch(g -> almacen.hayArranqueAbierto(g.nombre()));
        if (marcha || habiaMarcha) actualizar(true);
        habiaMarcha = marcha;
    }

    @Override
    protected void onDetach(DetachEvent e) {
        if (pollRegistration != null) pollRegistration.remove();
        e.getUI().setPollInterval(POLL_MS_APP);
    }

    private void visibilidadPeriodo() {
        Agrupacion a = agrupacionCombo.getValue();
        mesCombo.setVisible(a == Agrupacion.DIA);
        semanasCombo.setVisible(a == Agrupacion.SEMANA);
        anioCombo.setVisible(a == Agrupacion.MES);
    }

    /**
     * Siempre todos los generadores: el selector se quitó el 2026-10-23 (pedido del usuario). La meta es de la planta
     * y lo de cada generador se ve en sus tarjetas, columnas y colores.
     */
    private List<Generador> seleccionados() {
        return generadorService.generadores();
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
        hastaRango = hasta;
        if (hasta.isAfter(hoy)) hasta = hoy;
        if (desde.isAfter(hasta)) {
            mensaje.setText("El periodo elegido todavía no empezó.");
            return;
        }
        agrupacionVista = a;
        inicioElegido = null;
        detalleDibujado = null;
        calcular();
    }

    /**
     * Botón Actualizar (soloSiHayNuevos = false) y revisión de cada 15 s (true): lo mismo que el último "Ver", con los datos
     * nuevos y sin perder el período elegido en la lista. La revisión solo calcula si el rango incluye hoy y los archivos
     * de datos del mes cambiaron (se graban cada minuto); un rango pasado no cambia.
     */
    private void actualizar(boolean soloSiHayNuevos) {
        if (desde == null || hastaRango == null) return;
        LocalDate hoy = LocalDate.now();
        if (soloSiHayNuevos && (hastaRango.isBefore(hoy) || modificacionDatos() <= datosCalculados)) return;
        hasta = hastaRango.isAfter(hoy) ? hoy : hastaRango;
        enActualizacion = soloSiHayNuevos;
        try {
            calcular();
        } finally {
            enActualizacion = false;
        }
    }

    /** Última modificación de los archivos de datos del mes (generadores, medidores y arranques; también los -wal). */
    private static long modificacionDatos() {
        YearMonth m = YearMonth.now();
        long max = 0;
        for (String ruta : new String[]{GeneradorAlmacen.rutaLecturas(m), GeneradorAlmacen.rutaArranques(),
                RutaArchivosEnergia.construirRutaMensual(m.getYear(), m.getMonthValue(), true),
                RutaArchivosEnergia.construirRutaMensual(m.getYear(), m.getMonthValue(), false)}) {
            max = Math.max(max, Math.max(new File(ruta).lastModified(), new File(ruta + "-wal").lastModified()));
        }
        return max;
    }

    /** Ejecuta el script de un gráfico; en la actualización automática, solo si cambió (no se pierde el zoom ni se anima de nuevo). */
    private void dibujar(String nuevo, String anterior) {
        dibujar(nuevo, nuevo, anterior);
    }

    /**
     * Igual, para los gráficos que en la actualización solo reciben los datos: se compara el script completo y, si cambió,
     * se ejecuta {@code ejecutar} (el que solo cambia los datos, así no se pierden el zoom ni los marcadores).
     */
    private void dibujar(String ejecutar, String completo, String anterior) {
        if (completo == null || (enActualizacion && completo.equals(anterior))) return;
        getElement().executeJs(enActualizacion ? ejecutar : completo);
    }

    private void calcular() {
        long modificacion = modificacionDatos();
        long t0 = System.currentTimeMillis();
        List<Generador> gens = seleccionados();
        gensVistos = gens;
        meta = generadorService.metaImportacionRed();
        // Una sola consulta para todo el "Ver": cada archivo se lee una vez y la isla se detecta una vez por generador.
        GeneradorAnalisisService.Consulta consulta = new GeneradorAnalisisService.Consulta();
        periodos = analisis.periodosEnMarcha(consulta, gens, desde, hasta);
        periodosPlanta = GeneradorAnalisisService.periodosPlanta(periodos);
        periodoInicial = periodoDelIndicador(consulta, gens);
        // Después de los períodos: sus tarjetas "con generadores en marcha" los usan.
        combustible = costos.porPeriodo(periodos);
        calcularRendimientoDiesel();
        verEnergia(consulta, agrupacionVista);
        verPeriodos();
        verCostos();
        datosCalculados = modificacion;

        mensaje.setText(gens.stream().map(Generador::nombre).reduce((x, y) -> x + " + " + y).orElse("")
                + ", red de la planta 1 (medidor principal), del " + desde.format(DIA) + " al " + hasta.format(DIA)
                + ", actualizado a las " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + " (" + (System.currentTimeMillis() - t0) + " ms).");
    }

    // ================= Energía =================

    /**
     * Resumen de energía (rediseño del 2026-10-25 sobre la maqueta aprobada por el usuario): arriba, todo el período
     * elegido (consumo de la planta, aporte de los generadores, red y exportada) y debajo lo mismo solo con generadores en
     * marcha, que es lo que se compara con la meta de Períodos en marcha; la barra de cómo se cubrió el consumo; columnas
     * por día, semana o mes (aporte de cada generador + red = consumo) y la tabla del detalle, que cuadra con calculadora.
     */
    private VerticalLayout crearEnergia() {
        tarjetasEnergia.setWidthFull();
        tarjetasEnergia.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "6px");
        barraEnergia.setWidthFull();
        Div chart = new Div();
        chart.setId(CHART_ID);
        chart.setWidthFull();
        chart.setHeight("340px");
        tablaEnergia.setWidthFull();
        Div[] cajas = {caja("Cómo se cubrió el consumo de la planta", barraEnergia), caja(tituloGraficoEnergia, chart),
                caja(tituloDetalleEnergia, tablaEnergia)};
        // caja() está pensada para ir una al lado de la otra (flex 1 1 520px); acá van una debajo de la otra, a todo el ancho.
        for (Div c : cajas) c.getStyle().set("flex", "0 0 auto").set("width", "100%");
        VerticalLayout v = new VerticalLayout(tarjetasEnergia, cajas[0], cajas[1], cajas[2]);
        v.setPadding(false);
        v.setSpacing(false);
        v.getStyle().set("gap", "12px");
        v.setWidthFull();
        return v;
    }

    /** Una fila del detalle, en kWh redondeados: el consumo es la suma de lo redondeado, así cuadra a la vista. */
    private record RenglonEnergia(String etiqueta, long[] aporte, double[] horas, long red, long consumo, long exportada,
                                  boolean sinDatos) {
        long aporteGeneradores() {
            return java.util.Arrays.stream(aporte).sum();
        }
    }

    private void verEnergia(GeneradorAnalisisService.Consulta consulta, Agrupacion a) {
        List<Generador> gens = porTransformador();
        int n = gens.size();
        List<Fila> filas = analisis.analizar(consulta, gens, a, desde, hasta);
        // Lo de cada generador (lo que puso en la planta = generado menos su parte de lo exportado, y sus horas) sale de su
        // propio análisis, por período.
        List<Map<String, Fila>> porGenerador = new ArrayList<>();
        for (Generador g : gens) {
            Map<String, Fila> m = new HashMap<>();
            analisis.analizar(consulta, List.of(g), a, desde, hasta).forEach(f -> m.put(f.periodo(), f));
            porGenerador.add(m);
        }
        List<RenglonEnergia> renglones = new ArrayList<>();
        for (Fila f : filas) {
            long[] aporte = new long[n];
            double[] horas = new double[n];
            for (int i = 0; i < n; i++) {
                Fila fg = porGenerador.get(i).get(f.periodo());
                aporte[i] = fg == null ? 0 : redondo(fg.generadorUsado());
                horas[i] = fg == null || fg.horasMarcha() == null ? 0 : fg.horasMarcha();
            }
            long red = redondo(f.redImportada());
            long consumo = red + java.util.Arrays.stream(aporte).sum();
            renglones.add(new RenglonEnergia(a == Agrupacion.DIA ? f.periodo().substring(0, 5) : f.periodo(), aporte, horas, red,
                    consumo, redondo(f.redExportada()), f.consumo() == null));
        }
        long[] totalAporte = new long[n];
        double[] totalHoras = new double[n];
        long totalRed = 0, totalConsumo = 0, totalExp = 0;
        for (RenglonEnergia r : renglones) {
            if (r.sinDatos()) continue;
            for (int i = 0; i < n; i++) {
                totalAporte[i] += r.aporte()[i];
                totalHoras[i] += r.horas()[i];
            }
            totalRed += r.red();
            totalConsumo += r.consumo();
            totalExp += r.exportada();
        }
        RenglonEnergia total = new RenglonEnergia("Total", totalAporte, totalHoras, totalRed, totalConsumo, totalExp, false);

        String unidad = a == Agrupacion.DIA ? "día" : a == Agrupacion.SEMANA ? "semana" : "mes";
        tituloGraficoEnergia.setText("Consumo de la planta por " + unidad);
        tituloDetalleEnergia.setText("Detalle por " + unidad);
        pintarTarjetasEnergia(total);
        pintarBarraEnergia(gens, total);
        tablaEnergia.removeAll();
        tablaEnergia.add(new Html(htmlTablaEnergia(gens, renglones, total, a == Agrupacion.DIA ? "Día" : a == Agrupacion.SEMANA ? "Semana" : "Mes")));

        // Columnas apiladas: lo que puso cada generador + la red; la altura es el consumo de la planta.
        List<String> categorias = new ArrayList<>();
        List<Double[]> valores = new ArrayList<>();
        String[] series = new String[n + 1];
        String[] colores = new String[n + 1];
        for (int i = 0; i < n; i++) {
            series[i] = gens.get(i).nombre();
            colores[i] = colorGenerador(gens.get(i).nombre());
        }
        series[n] = "De la red";
        colores[n] = AZUL;
        for (RenglonEnergia r : renglones) {
            categorias.add(r.etiqueta());
            Double[] v = new Double[n + 1];
            for (int i = 0; i < n; i++) v[i] = r.sinDatos() || r.aporte()[i] == 0 ? null : (double) r.aporte()[i];
            v[n] = r.sinDatos() ? null : (double) r.red();
            valores.add(v);
        }
        String antes = scriptGrafico;
        scriptGrafico = GraficaModel.getBarrasApiladasScript(CHART_ID, categorias, series, colores, valores, "kWh");
        if (tabs.getSelectedTab() == tabEnergia) dibujar(scriptGrafico, antes);
    }

    /**
     * Dos grupos de tarjetas (opción B elegida por el usuario): todo el período y solo con generadores en marcha (los
     * períodos de la planta del rango, los mismos del gauge de todos los períodos).
     */
    private void pintarTarjetasEnergia(RenglonEnergia total) {
        tarjetasEnergia.removeAll();
        long aporte = total.aporteGeneradores();
        tarjetasEnergia.add(grupoEnergia("Todo el periodo", "del " + desde.format(DIA_MES) + " al " + hasta.format(DIA_MES)));
        tarjetasEnergia.add(filaTarjetas(
                tarjetaEnergia("Consumo de la planta", total.consumo(), null, null),
                tarjetaEnergia("Aporte de los generadores", aporte, pctDelConsumo(aporte, total.consumo()), css(NARANJA)),
                tarjetaEnergia("De la red", total.red(), pctDelConsumo(total.red(), total.consumo()), css(AZUL)),
                tarjetaEnergia("Exportada a la red", total.exportada(),
                        informeExportada(total.exportada(), total.aporteGeneradores() + total.exportada()), css(AQUA))));
        Double imp = null, cons = null;
        double horas = 0;
        for (PeriodoPlanta p : periodosPlanta) {
            imp = suma(imp, p.importada());
            cons = suma(cons, p.consumo());
            horas += p.horas();
        }
        if (periodosPlanta.isEmpty() || cons == null) {
            tarjetasEnergia.add(grupoEnergia("Con generadores en marcha", "no hubo en el periodo"));
            return;
        }
        long consumoMarcha = redondo(cons), redMarcha = redondo(imp), aporteMarcha = consumoMarcha - redMarcha;
        tarjetasEnergia.add(grupoEnergia("Con generadores en marcha", periodosPlanta.size()
                + (periodosPlanta.size() == 1 ? " periodo, " : " periodos, ") + String.format(Locale.ROOT, "%,.1f h", horas)));
        String pctRed = pctDelConsumo(redMarcha, consumoMarcha);
        tarjetasEnergia.add(filaTarjetas(
                tarjetaEnergia("Consumo de la planta", consumoMarcha, null, null),
                tarjetaEnergia("Aporte de los generadores", aporteMarcha, pctDelConsumo(aporteMarcha, consumoMarcha), css(NARANJA)),
                tarjetaEnergia("De la red", redMarcha, pctRed == null ? null : pctRed + " · meta 0", css(AZUL)),
                new Div()));
    }

    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd-MM");

    private static String pctDelConsumo(long kwh, long consumo) {
        return consumo <= 0 ? null : String.format(Locale.ROOT, "%.1f %% del consumo", kwh * 100.0 / consumo);
    }

    private static Html grupoEnergia(String titulo, String detalle) {
        return new Html("<div style='font-size:13px;font-weight:600;color:" + TEXTO + ";margin-top:4px'>" + esc(titulo)
                + " <span style='font-weight:400;color:" + TEXTO_SUAVE + "'>· " + esc(detalle) + "</span></div>");
    }

    private static Div filaTarjetas(Component... tarjetas) {
        for (Component t : tarjetas) ((Div) t).getStyle().set("flex", "1 1 190px").set("min-width", "0");
        Div fila = new Div(tarjetas);
        fila.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "12px").set("width", "100%");
        return fila;
    }

    /** Tarjeta blanca con el valor grande en kWh; borde izquierdo de color si se da. */
    private static Div tarjetaEnergia(String titulo, long kwh, String detalle, String borde) {
        Div t = new Div(new Html("<div style='display:grid;gap:2px'><span style='font-size:12px;color:" + TEXTO_SUAVE + "'>"
                + esc(titulo) + "</span><span style='font-size:22px;font-weight:700;color:" + TEXTO + "'>"
                + String.format(Locale.ROOT, "%,d kWh", kwh) + "</span>"
                + (detalle == null ? "" : "<span style='font-size:12px;color:" + TEXTO_SUAVE + "'>" + esc(detalle) + "</span>") + "</div>"));
        t.getStyle().set("border", "1px solid " + BORDE).set("border-radius", "12px").set("padding", "10px 14px")
                .set("background-color", "#ffffff").set("box-sizing", "border-box");
        if (borde != null) t.getStyle().set("border-left", "5px solid " + borde);
        return t;
    }

    /** Barra de cómo se cubrió el consumo de todo el período: cada generador + la red = 100 %. */
    private void pintarBarraEnergia(List<Generador> gens, RenglonEnergia total) {
        barraEnergia.removeAll();
        if (total.consumo() <= 0) {
            barraEnergia.add(new Span("Sin datos en el periodo."));
            return;
        }
        StringBuilder barra = new StringBuilder("<div style='display:flex;height:24px;border-radius:6px;overflow:hidden;font-size:11px;font-weight:600'>");
        for (int i = 0; i < gens.size(); i++) {
            double pct = total.aporte()[i] * 100.0 / total.consumo();
            if (pct > 0) barra.append(segmentoHtml(pct, css(colorGenerador(gens.get(i).nombre())), esc(gens.get(i).nombre()), 8));
        }
        double pctRed = total.red() * 100.0 / total.consumo();
        if (pctRed > 0) barra.append(segmentoHtml(pctRed, css(AZUL), "Red", 8));
        barraEnergia.add(new Html(barra.append("</div>").toString()));
    }

    /**
     * Detalle por día, semana o mes: lo que puso cada generador, la red (con su % del consumo de esa fila), el consumo de la
     * planta, lo exportado y las horas en marcha de cada generador; los días sin generador en gris; la fila Total cuadra.
     */
    private String htmlTablaEnergia(List<Generador> gens, List<RenglonEnergia> renglones, RenglonEnergia total, String primera) {
        String th = "<th style='padding:6px 8px;border-bottom:1px solid " + BORDE + ";font-weight:600;font-size:12px;color:" + TEXTO_SUAVE
                + ";text-align:right;white-space:nowrap'>";
        StringBuilder h = new StringBuilder("<div style='overflow-x:auto'><table style='border-collapse:collapse;width:100%;font-size:13px;color:")
                .append(TEXTO).append("'><tr><th style='padding:6px 8px;border-bottom:1px solid ").append(BORDE)
                .append(";font-weight:600;font-size:12px;color:").append(TEXTO_SUAVE).append(";text-align:left'>").append(primera).append("</th>");
        for (Generador g : gens) h.append(th).append("<span style='display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:5px;background:").append(css(colorGenerador(g.nombre()))).append("'></span>").append(esc(g.nombre())).append(" (kWh)</th>");
        h.append(th).append("De la red (kWh)</th>").append(th).append("Consumo planta (kWh)</th>").append(th).append("Exportada (kWh)</th>");
        for (Generador g : gens) h.append(th).append("Horas ").append(esc(g.nombre())).append("</th>");
        h.append("</tr>");
        for (RenglonEnergia r : renglones) h.append(renglonHtml(r, false));
        h.append(renglonHtml(total, true));
        return h.append("</table></div>").toString();
    }

    /** Debajo del consumo, en chico: de qué está hecho el 100 %, generadores (G) + red (R). */
    private static String composicion(RenglonEnergia r) {
        if (r.consumo() <= 0) return "";
        return "<div style='font-size:10px;font-weight:400;color:" + TEXTO_SUAVE + "'>" + String.format(Locale.ROOT, "G %.1f %% + R %.1f %%",
                r.aporteGeneradores() * 100.0 / r.consumo(), r.red() * 100.0 / r.consumo()) + "</div>";
    }

    private static String renglonHtml(RenglonEnergia r, boolean esTotal) {
        String estilo = esTotal
                ? "padding:6px 8px;font-weight:700;border-top:2px solid " + TEXTO + ";background:" + FONDO_SUAVE
                : "padding:5px 8px;border-bottom:1px solid #EEF0F4" + (r.sinDatos() || r.aporteGeneradores() == 0 ? ";color:#A0A6B4" : "");
        String td = "<td style='" + estilo + ";text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap'>";
        StringBuilder h = new StringBuilder("<tr><td style='").append(estilo).append(";white-space:nowrap'>").append(esc(r.etiqueta())).append("</td>");
        int n = r.aporte().length;
        if (r.sinDatos()) {
            for (int i = 0; i < 2 * n + 3; i++) h.append(td).append("-</td>");
            return h.append("</tr>").toString();
        }
        for (int i = 0; i < n; i++) h.append(td).append(String.format(Locale.ROOT, "%,d", r.aporte()[i])).append(pctDe(r.aporte()[i], r.consumo())).append("</td>");
        h.append(td).append(String.format(Locale.ROOT, "%,d", r.red())).append(pctDe(r.red(), r.consumo())).append("</td>")
                .append(td).append(String.format(Locale.ROOT, "%,d", r.consumo())).append(composicion(r)).append("</td>")
                .append(td).append(String.format(Locale.ROOT, "%,d", r.exportada())).append("</td>");
        for (int i = 0; i < n; i++) h.append(td).append(r.horas()[i] > 0 ? String.format(Locale.ROOT, "%,.1f h", r.horas()[i]) : "-").append("</td>");
        return h.append("</tr>").toString();
    }

    /**
     * Lo exportado a la red, solo como información (pedido del usuario el 2026-10-25: no es una meta ni lleva semáforo):
     * su parte de lo generado y, si hay arranques del rango con diesel cargado, el diesel y el costo aproximados que llevó.
     */
    private void calcularRendimientoDiesel() {
        double kwh = 0, galones = 0, costo = 0;
        for (PeriodoMarcha p : periodos) {
            CostoCombustible cc = c(p);
            if (cc == null || p.kwhGenerado() == null || p.kwhGenerado() <= 0) continue;
            kwh += p.kwhGenerado();
            galones += cc.getGalones();
            costo += cc.costo();
        }
        kwhPorGalon = galones > 0 ? kwh / galones : 0;
        precioGalon = galones > 0 ? costo / galones : 0;
    }

    /** "3.3 % de lo generado · aprox. 530 gal de diesel ($ 1,900.00)"; null si no se exportó nada. */
    private String informeExportada(double kwhExportada, double kwhGenerado) {
        if (kwhExportada < 0.5) return null;
        StringBuilder s = new StringBuilder();
        if (kwhGenerado > 0) s.append(String.format(Locale.ROOT, "%.1f %% de lo generado", kwhExportada * 100 / kwhGenerado));
        if (kwhPorGalon > 0) {
            double gal = kwhExportada / kwhPorGalon;
            if (s.length() > 0) s.append(" · ");
            s.append(String.format(Locale.ROOT, "aprox. %,.0f gal de diésel (%s)", gal, dinero(gal * precioGalon)));
        }
        return s.length() == 0 ? null : s.toString();
    }

    /** Línea chica gris con lo exportado en el texto de un gauge de Períodos en marcha. */
    private void lineaExportada(StringBuilder h, Double exportada, Double generado) {
        if (exportada == null) return;
        // Con los kWh redondeados, como en las tarjetas: así el diesel y el costo coinciden en las dos pestañas.
        String info = informeExportada(Math.round(exportada), generado == null ? 0 : generado);
        h.append("<span style='font-size:12px;color:").append(TEXTO_SUAVE).append("'>Exportada a la red: ").append(kwh(exportada))
                .append(info == null ? "" : " · " + esc(info)).append("</span>");
    }

    /** Color de un generador en esta ventana (según su lugar en generador-config.json). */
    private String colorGenerador(String nombre) {
        List<Generador> todos = generadorService.generadores();
        for (int i = 0; i < todos.size(); i++) {
            if (todos.get(i).nombre().equals(nombre)) return COLORES_GEN[i % COLORES_GEN.length];
        }
        return NARANJA;
    }

    // ================= Períodos en marcha =================

    /**
     * Períodos en marcha de la planta, todo en una pantalla (diseño acordado con el usuario el 2026-10-23, idea de la
     * pantalla del controlador ComAp: lo justo y bien ubicado). Arriba: la meta de todos los períodos, la del período
     * seleccionado y lo que puso cada generador; abajo: la lista de períodos y el minuto a minuto del seleccionado.
     */
    private VerticalLayout crearPeriodos() {
        periodosGrid.addColumn(p -> p.inicio().format(CORTO) + (p.inicioEstimado() ? " (aprox.)" : "")).setHeader("Inicio").setAutoWidth(true).setFlexGrow(0);
        periodosGrid.addComponentColumn(p -> {
            if (!p.enCurso()) return new Span(p.fin().format(p.fin().toLocalDate().equals(p.inicio().toLocalDate()) ? HORA : CORTO));
            Span s = new Span();
            badge(s, "En marcha", Estado.OK);
            return s;
        }).setHeader("Fin").setAutoWidth(true).setFlexGrow(0);
        periodosGrid.addColumn(PeriodoPlanta::generadores).setHeader("Generadores").setAutoWidth(true).setFlexGrow(1);
        columna(periodosGrid, "Duración", p -> duracion(p.horas()));
        columna(periodosGrid, "Consumo planta (kWh)", p -> kwhNum(p.consumo()));
        periodosGrid.addComponentColumn(p -> badgeRed(p.porcentajeDeRed())).setHeader("De la red").setAutoWidth(true).setFlexGrow(0);
        columna(periodosGrid, "Exportada (kWh)", p -> kwhNum(p.exportada()));
        periodosGrid.setWidthFull();
        periodosGrid.setHeight("300px");
        periodosGrid.setSelectionMode(Grid.SelectionMode.SINGLE);
        periodosGrid.asSingleSelect().addValueChangeListener(e -> {
            if (cargandoLista) return;
            PeriodoPlanta p = e.getValue();
            if (e.isFromClient()) inicioElegido = p == null ? null : p.inicio();
            pintarSeleccionado(p != null ? p : periodoInicial, p != null && p.inicio().equals(inicioElegido));
            // Si la parte de arriba no se ve entera, la página sube lo justo para mostrarla.
            if (p != null && e.isFromClient()) {
                bloqueIndicador.getElement().executeJs("this.scrollIntoView({ behavior: 'smooth', block: 'nearest' })");
            }
        });
        chartMinutos.setId(CHART_MINUTOS);
        chartMinutos.setWidthFull();
        chartMinutos.setHeight("300px");
        Div fila2 = new Div(caja("Periodos en marcha", periodosGrid), caja("Minuto a minuto del periodo seleccionado", chartMinutos));
        fila2.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "12px").set("width", "100%");
        generadoresDetalle.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "12px").set("width", "100%");
        resumenPeriodo.setWidthFull();
        VerticalLayout v = new VerticalLayout(crearIndicador(), fila2, generadoresDetalle, resumenPeriodo);
        v.setPadding(false);
        v.setSpacing(false);
        v.getStyle().set("gap", "12px");
        v.setWidthFull();
        return v;
    }

    /** Caja blanca con título chico arriba (lista y minuto a minuto). */
    private static Div caja(String titulo, Component contenido) {
        return caja(new Span(titulo), contenido);
    }

    /** Igual, con el título como Span para cambiarle el texto después (ej. "por dia" / "por mes"). */
    private static Div caja(Span t, Component contenido) {
        t.getStyle().set("font-size", "14px").set("font-weight", "700").set("color", TEXTO);
        Div c = new Div(t, contenido);
        c.getStyle().set("flex", "1 1 520px").set("min-width", "0").set("display", "flex").set("flex-direction", "column")
                .set("gap", "6px").set("border", "1px solid " + BORDE).set("border-radius", "12px").set("padding", "10px 12px")
                .set("background-color", "#ffffff").set("box-shadow", "0 1px 3px rgba(30,30,44,0.06)").set("box-sizing", "border-box");
        return c;
    }

    private void verPeriodos() {
        // El período elegido en la lista se mantiene al actualizar (se lo reconoce por su comienzo; si está en marcha, su
        // fin cambia); si no hay, el período en marcha o el último, que queda marcado en la lista.
        PeriodoPlanta aSeleccionar = inicioElegido == null ? null
                : periodosPlanta.stream().filter(x -> x.inicio().equals(inicioElegido)).findFirst().orElse(null);
        if (aSeleccionar == null) inicioElegido = null;
        if (aSeleccionar == null && periodoInicial != null && periodosPlanta.contains(periodoInicial)) aSeleccionar = periodoInicial;
        cargandoLista = true;
        try {
            periodosGrid.setItems(periodosPlanta);
            if (aSeleccionar != null) periodosGrid.select(aSeleccionar);
        } finally {
            cargandoLista = false;
        }
        Double imp = null, exp = null, cons = null, gen = null;
        for (PeriodoPlanta p : periodosPlanta) {
            imp = suma(imp, p.importada());
            exp = suma(exp, p.exportada());
            cons = suma(cons, p.consumo());
            gen = suma(gen, p.generado());
        }
        Double pctRed = cons == null || cons <= 0 || imp == null ? null : Math.min(100, imp / cons * 100);
        pintarIndicadorTodos(pctRed, imp, cons, exp, gen);
        pintarSeleccionado(aSeleccionar != null ? aSeleccionar : periodoInicial, inicioElegido != null);
    }

    // ================= Meta cero importación: todos los períodos y el seleccionado =================

    /**
     * Meta "cero importación de la red" arriba de la pestaña (pedido del usuario el 2026-10-23): dos tarjetas con su gauge
     * de semáforo, todos los períodos del rango elegido y el período seleccionado en la lista (al abrir, el que está en
     * marcha o el último), y a la derecha una tarjeta chica por generador con lo que puso en ese período y la barra de
     * cómo se repartió el consumo de la planta. Arriba a la derecha, los límites del semáforo (solo el administrador).
     */
    private Div crearIndicador() {
        Span titulo = new Span("Meta: cero importación de la red");
        titulo.getStyle().set("font-size", "16px").set("font-weight", "700").set("color", TEXTO);
        Div cabecera = new Div(titulo);
        cabecera.getStyle().set("display", "flex").set("align-items", "center").set("justify-content", "space-between")
                .set("flex-wrap", "wrap").set("gap", "8px").set("width", "100%");
        // Límites del semáforo: solo el administrador los cambia (se guardan en generador-config.json).
        if (lineaAccessService.esAdmin()) {
            Button limites = new Button("Límite de indicador", VaadinIcon.COG.create(), e -> abrirDialogoLimites());
            limites.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            cabecera.add(limites);
        }
        generadoresPeriodo.getStyle().set("flex", "0 1 300px").set("min-width", "260px").set("display", "grid")
                .set("gap", "8px").set("align-content", "center");
        Div tarjetas = new Div(tarjetaIndicador(chartIndicadorTodos, CHART_INDICADOR_TODOS, indicadorTodosTexto),
                tarjetaIndicador(chartIndicador, CHART_INDICADOR, indicadorTexto), generadoresPeriodo);
        tarjetas.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "12px").set("width", "100%")
                .set("align-items", "stretch");
        bloqueIndicador.add(cabecera, tarjetas);
        bloqueIndicador.setWidthFull();
        bloqueIndicador.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "10px")
                .set("box-sizing", "border-box").set("scroll-margin-top", "12px");
        return bloqueIndicador;
    }

    /** Tarjeta de un indicador: el gauge y, al lado (o debajo si no entra), su texto corto. */
    private static Div tarjetaIndicador(Div chart, String id, Div texto) {
        chart.setId(id);
        chart.setWidth("200px");
        chart.setHeight("190px");
        Div gauge = new Div(chart);
        gauge.getStyle().set("flex", "0 0 200px");
        texto.getStyle().set("flex", "1 1 160px").set("min-width", "160px").set("color", TEXTO);
        Div t = new Div(gauge, texto);
        t.getStyle().set("flex", "1 1 400px").set("min-width", "300px").set("display", "flex").set("flex-wrap", "wrap")
                .set("align-items", "center").set("gap", "4px 14px").set("border", "1px solid " + BORDE)
                .set("border-radius", "12px").set("padding", "10px 14px").set("background-color", "#ffffff")
                .set("box-shadow", "0 1px 3px rgba(30,30,44,0.06)").set("box-sizing", "border-box");
        return t;
    }

    /** Cabecera del texto de un indicador: qué es (en mayúsculas chicas), el título y debajo un detalle. */
    private static void cabeceraIndicador(StringBuilder h, String etiqueta, String titulo, String detalle) {
        h.append("<span style='font-size:11px;font-weight:600;letter-spacing:.06em;text-transform:uppercase;color:")
                .append(TEXTO_SUAVE).append("'>").append(etiqueta).append("</span>")
                .append("<span style='font-size:17px;font-weight:700'>").append(esc(titulo)).append("</span>")
                .append("<span style='font-size:12px;color:").append(TEXTO_SUAVE).append("'>").append(detalle).append("</span>");
    }

    /** Lo justo sobre la meta: la zona, cuánto vino de la red y, si no está en verde, cuántos kWh menos para llegar. */
    private void textoMeta(StringBuilder h, Double pct, Double imp, Double cons) {
        if (pct == null) {
            h.append("<span style='color:").append(TEXTO_SUAVE).append("'>Sin lecturas del medidor principal.</span>");
            return;
        }
        String[] c = tonos(semaforoRed(pct));
        MetaImportacionRed.Zona zona = meta.zona(pct);
        String nombreZona = switch (zona) {
            case VERDE -> "Zona verde: hasta " + pctUmbral(meta.verdeHasta());
            case AMARILLO -> "Zona amarilla: de " + pctUmbral(meta.verdeHasta()) + " a " + pctUmbral(meta.amarilloHasta());
            case ROJO -> "Zona roja: más de " + pctUmbral(meta.amarilloHasta());
        };
        h.append("<span style='padding:2px 9px;border-radius:10px;font-weight:600;font-size:12px;white-space:nowrap;"
                        + "justify-self:start;background:").append(c[1]).append(";color:").append(c[2]).append("'>")
                .append(nombreZona).append("</span>")
                .append("<span>De la red: <b>").append(kwhNum(imp)).append("</b> de ").append(kwh(cons)).append("</span>");
        if (zona != MetaImportacionRed.Zona.VERDE) {
            // Lo que sobró de la red para quedar en verde: lo importado menos el límite verde del consumo.
            double deMas = imp - cons * meta.verdeHasta() / 100;
            h.append("<span>Para verde: <b>").append(kwh(deMas)).append("</b> menos de la red</span>");
        }
    }

    /** Colores del gauge: zonas verde, amarilla y roja, la aguja y el número (del color de su zona). */
    private String[] coloresGauge(Double pct) {
        return new String[]{am(tonos(Estado.OK)[0]), am(tonos(Estado.AVISO)[0]), am(tonos(Estado.FUERA)[0]), NOCHE,
                am(tonos(semaforoRed(pct))[2])};
    }

    /** Gauge de la izquierda: todos los períodos en marcha del rango elegido, con cuántos quedaron en verde y en rojo. */
    private void pintarIndicadorTodos(Double pct, Double imp, Double cons, Double exp, Double gen) {
        indicadorTodosTexto.removeAll();
        StringBuilder h = new StringBuilder("<div style='display:grid;gap:5px;font-size:13px'>");
        int cuantos = periodosPlanta.size();
        cabeceraIndicador(h, "Todos los periodos", cuantos + (cuantos == 1 ? " periodo en marcha" : " periodos en marcha"),
                "Del " + desde.format(DIA) + " al " + hasta.format(DIA));
        if (periodosPlanta.isEmpty()) {
            h.append("<span style='color:").append(TEXTO_SUAVE).append("'>Sin periodos en marcha en el rango elegido.</span></div>");
            indicadorTodosTexto.add(new Html(h.toString()));
            chartIndicadorTodos.setVisible(false);
            scriptIndicadorTodos = null;
            return;
        }
        textoMeta(h, pct, imp, cons);
        lineaExportada(h, exp, gen);
        int verdes = 0, rojos = 0;
        for (PeriodoPlanta p : periodosPlanta) {
            Double x = p.porcentajeDeRed();
            if (x == null) continue;
            if (meta.zona(x) == MetaImportacionRed.Zona.VERDE) verdes++;
            else if (meta.zona(x) == MetaImportacionRed.Zona.ROJO) rojos++;
        }
        String chip = "padding:2px 9px;border-radius:10px;font-weight:600;font-size:12px;white-space:nowrap;";
        String[] verde = tonos(Estado.OK), rojo = tonos(Estado.FUERA);
        h.append("<div style='display:flex;flex-wrap:wrap;gap:6px'>")
                .append("<span style='").append(chip).append("background:").append(verde[1]).append(";color:").append(verde[2]).append("'>")
                .append(verdes).append(" en verde</span>")
                .append("<span style='").append(chip).append("background:").append(rojo[1]).append(";color:").append(rojo[2]).append("'>")
                .append(rojos).append(" en rojo</span></div></div>");
        indicadorTodosTexto.add(new Html(h.toString()));
        chartIndicadorTodos.setVisible(true);
        String antes = scriptIndicadorTodos;
        scriptIndicadorTodos = GraficaModel.getGaugeMetaScript(CHART_INDICADOR_TODOS, pct, meta.verdeHasta(), meta.amarilloHasta(),
                coloresGauge(pct), "importado de la red");
        if (tabs.getSelectedTab() == tabPeriodos) dibujar(scriptIndicadorTodos, antes);
    }

    /**
     * Límites del semáforo de la meta "cero importación de la red" (% del consumo importado): verde hasta el primero,
     * amarillo hasta el segundo, rojo por encima. Solo el administrador; valen para toda la ventana Análisis y el reporte
     * de generadores, y pasan al servidor con "Pasar configuracion" (están en generador-config.json).
     */
    private void abrirDialogoLimites() {
        if (!lineaAccessService.esAdmin()) return;
        MetaImportacionRed actual = generadorService.metaImportacionRed();
        Dialog d = new Dialog();
        d.setHeaderTitle("Límite de indicador: meta cero importación de la red");
        Span explicacion = new Span("En % del consumo de la planta importado de la red. Verde: de 0 hasta el primer límite; "
                + "amarillo: hasta el segundo; rojo: por encima. Valen para Análisis y el reporte de generadores.");
        explicacion.getStyle().set("font-size", "13px").set("color", TEXTO_SUAVE);
        NumberField verde = new NumberField("Verde hasta (%)");
        NumberField amarillo = new NumberField("Amarillo hasta (%)");
        for (NumberField f : new NumberField[]{verde, amarillo}) {
            f.setMin(0);
            f.setMax(100);
            f.setStep(0.5);
            f.setWidthFull();
        }
        verde.setValue(actual.verdeHasta());
        amarillo.setValue(actual.amarilloHasta());
        Span rojo = new Span();
        rojo.getStyle().set("font-size", "13px").set("font-weight", "600");
        Runnable vistaPrevia = () -> {
            Double v = verde.getValue(), a = amarillo.getValue();
            boolean valido = v != null && a != null && v >= 0 && v < a && a <= 100;
            rojo.setText(valido ? "Rojo: más de " + pctUmbral(a) : "El límite verde debe ser menor que el amarillo, entre 0 y 100 %");
            rojo.getStyle().set("color", tonos(valido ? Estado.FUERA : Estado.AVISO)[2]);
        };
        verde.addValueChangeListener(e -> vistaPrevia.run());
        amarillo.addValueChangeListener(e -> vistaPrevia.run());
        vistaPrevia.run();
        VerticalLayout contenido = new VerticalLayout(explicacion, verde, amarillo, rojo);
        contenido.setPadding(false);
        contenido.setWidth("380px");
        d.add(contenido);

        Button guardar = new Button("Guardar", e -> {
            Double v = verde.getValue(), a = amarillo.getValue();
            if (v == null || a == null || !(v >= 0 && v < a && a <= 100)) {
                Notification.show("El límite verde debe ser menor que el amarillo, entre 0 y 100 %", 3500, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            try {
                generadorService.guardarMetaImportacionRed(v, a);
                d.close();
                ver();
                Notification.show("Límites guardados: verde hasta " + pctUmbral(v) + ", amarillo hasta " + pctUmbral(a), 3000,
                        Notification.Position.BOTTOM_START).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } catch (RuntimeException ex) {
                Notification.show("No se pudieron guardar los límites: " + ex.getMessage(), 5000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        guardar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        d.getFooter().add(new Button("Cancelar", e -> d.close()), guardar);
        d.open();
    }

    /**
     * Período que se muestra al abrir o al tocar "Ver": el que está en marcha; si no, el más reciente de la lista; si el
     * rango no tiene períodos, el último que haya habido (ej. a principio de mes, el del mes anterior).
     */
    private PeriodoPlanta periodoDelIndicador(GeneradorAnalisisService.Consulta consulta, List<Generador> gens) {
        inicialFueraDelPeriodo = false;
        for (PeriodoPlanta p : periodosPlanta) if (p.enCurso()) return p;
        if (!periodosPlanta.isEmpty()) return periodosPlanta.get(0);
        GeneradorService.Arranque ultimo = null;
        for (Generador g : gens) {
            for (GeneradorService.Arranque a : generadorService.arranques(g)) {
                if (ultimo == null || a.inicio().isAfter(ultimo.inicio())) ultimo = a;
            }
        }
        if (ultimo == null) return null;
        // Con todos los generadores, para juntarlo con el arranque del otro si se pisaban.
        LocalDate d = ultimo.inicio().toLocalDate(), h = ultimo.fin() == null ? LocalDate.now() : ultimo.fin().toLocalDate();
        List<PeriodoPlanta> deEsosDias = GeneradorAnalisisService.periodosPlanta(analisis.periodosEnMarcha(consulta, gens, d, h));
        if (deEsosDias.isEmpty()) return null;
        inicialFueraDelPeriodo = true;
        return deEsosDias.get(0);
    }

    /**
     * Lo del período seleccionado: su gauge, la tarjeta de cada generador con la barra de reparto y el minuto a minuto.
     * elegido = lo seleccionó el usuario en la lista; si no, es el período inicial (el que está en marcha o el último).
     */
    private void pintarSeleccionado(PeriodoPlanta p, boolean elegido) {
        indicadorTexto.removeAll();
        generadoresPeriodo.removeAll();
        resumenPeriodo.removeAll();
        if (p == null) {
            chartIndicador.setVisible(false);
            chartMinutos.setVisible(false);
            generadoresDetalle.removeAll();
            scriptIndicador = null;
            scriptMinutos = null;
            scriptsGenerador = Map.of();
            detallePendiente = null;
            detalleDibujado = null;
            recuadrosDibujados = null;
            indicadorTexto.add(new Html("<div style='color:" + TEXTO_SUAVE + "'>Todavía no hay periodos en marcha para mostrar.</div>"));
            return;
        }
        Double pct = p.porcentajeDeRed();
        String etiqueta = elegido ? "Periodo seleccionado" : p.enCurso() ? "Periodo en marcha ahora"
                : inicialFueraDelPeriodo ? "Último periodo (antes del rango elegido)" : "Último periodo en marcha";
        String aprox = p.inicioEstimado() ? " (aprox.)" : "";
        String cuando = p.enCurso()
                ? "Desde el " + p.inicio().format(CORTA) + aprox + " (" + duracion(p.horas()) + " hasta las " + p.fin().format(HORA) + ")"
                : p.fin().toLocalDate().equals(p.inicio().toLocalDate())
                ? p.inicio().format(DIA) + " de " + p.inicio().format(HORA) + aprox + " a " + p.fin().format(HORA) + " (" + duracion(p.horas()) + ")"
                : "Del " + p.inicio().format(CORTA) + aprox + " al " + p.fin().format(CORTA) + " (" + duracion(p.horas()) + ")";
        StringBuilder h = new StringBuilder("<div style='display:grid;gap:5px;font-size:13px'>");
        cabeceraIndicador(h, etiqueta, p.generadores(), cuando);
        textoMeta(h, pct, p.importada(), p.consumo());
        lineaExportada(h, p.exportada(), p.generado());
        h.append("</div>");
        indicadorTexto.add(new Html(h.toString()));
        chartIndicador.setVisible(true);
        String antes = scriptIndicador;
        scriptIndicador = GraficaModel.getGaugeMetaScript(CHART_INDICADOR, pct, meta.verdeHasta(), meta.amarilloHasta(),
                coloresGauge(pct), "importado de la red");
        if (tabs.getSelectedTab() == tabPeriodos) dibujar(scriptIndicador, antes);
        pintarGeneradores(p);
        pintarResumen(p);
        // Lo pesado (minuto a minuto de la planta y de cada generador, máquinas) solo con la pestaña visible.
        if (tabs.getSelectedTab() == tabPeriodos) {
            pintarDetalle(p);
        } else {
            detallePendiente = p;
        }
    }

    /** Minuto a minuto de la planta y, abajo, el de cada generador contra su transformador con sus máquinas encendidas. */
    private void pintarDetalle(PeriodoPlanta p) {
        detallePendiente = null;
        // En la actualización, si el período no cambió (ya terminó), no se recalcula ni se redibuja nada.
        String clave = p.inicio() + "|" + p.fin() + "|" + p.arranques().size();
        if (enActualizacion && clave.equals(detalleDibujado)) return;
        mostrarMinutos(p);
        pintarDetalleGeneradores(p);
        detalleDibujado = clave;
    }

    /**
     * Abajo de todo (pedido del usuario el 2026-10-23): un recuadro por generador, Transformador 1 a la izquierda, con su
     * minuto a minuto contra su transformador (lo generado, la red y el consumo total del transformador) y debajo las
     * máquinas de su transformador que estuvieron encendidas, en verde y con su potencia media.
     */
    /** Generadores en el orden de sus transformadores: Transformador 1 primero (pedido del usuario el 2026-10-25). */
    private List<Generador> porTransformador() {
        return generadorService.generadores().stream()
                .sorted(java.util.Comparator.comparing((Generador g) -> g.redAsociada() == null ? "~" : g.redAsociada())).toList();
    }

    private void pintarDetalleGeneradores(PeriodoPlanta p) {
        List<Generador> orden = porTransformador();
        // En la actualización del mismo período (en marcha) los recuadros quedan: el gráfico recibe solo los datos nuevos
        // (no pierde el zoom ni los marcadores) y se reescriben las máquinas.
        String estructura = p.inicio() + "|" + p.generadores();
        boolean enLugar = enActualizacion && estructura.equals(recuadrosDibujados);
        if (!enLugar) {
            generadoresDetalle.removeAll();
            chartDe.clear();
            maquinasDe.clear();
        }
        Map<String, String> anteriores = scriptsGenerador;
        Map<String, String> scripts = new LinkedHashMap<>();
        int i = 0;
        for (Generador g : orden) {
            String titulo = g.nombre() + " · " + GeneradorView.nombreRed(g.redAsociada());
            List<PeriodoMarcha> suyos = p.arranques().stream().filter(a -> a.generador().equals(g.nombre()))
                    .sorted(java.util.Comparator.comparing(PeriodoMarcha::inicio)).toList();
            if (suyos.isEmpty()) {
                if (!enLugar) {
                    Span no = new Span("No trabajo en este periodo");
                    no.getStyle().set("font-size", "13px").set("color", TEXTO_SUAVE);
                    generadoresDetalle.add(caja(titulo, no));
                }
                continue;
            }
            LocalDateTime ini = suyos.get(0).inicio();
            LocalDateTime fin = suyos.stream().map(PeriodoMarcha::fin).max(LocalDateTime::compareTo).orElse(p.fin());
            String id = "chartdiv_generador_transformador_" + i++;
            String completo = null, soloDatos = null;
            List<GeneradorAnalisisService.MinutoMarcha> minutos = analisis.minutosTransformador(g, ini, fin);
            if (!minutos.isEmpty()) {
                List<Long> t = new ArrayList<>();
                List<Double> gen = new ArrayList<>(), consumo = new ArrayList<>(), red = new ArrayList<>();
                for (GeneradorAnalisisService.MinutoMarcha m : minutos) {
                    t.add(m.fecha().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                    gen.add(m.generadorKw());
                    consumo.add(m.consumoKw());
                    red.add(m.redKw());
                }
                String nombreConsumo = "Consumo " + GeneradorView.deRed(g.redAsociada());
                completo = GraficaModel.conTooltipsTransparentes(GraficaModel.getMarchaMinutoScript(id, t, gen, consumo, red,
                        nombreConsumo, colorGenerador(g.nombre()), AZUL, AQUA, NOCHE, false));
                soloDatos = GraficaModel.conTooltipsTransparentes(GraficaModel.getMarchaMinutoScript(id, t, gen, consumo, red,
                        nombreConsumo, colorGenerador(g.nombre()), AZUL, AQUA, NOCHE, true));
                scripts.put(g.nombre(), completo);
            }
            List<Component> debajo = new ArrayList<>();
            Html paso = textoPaso(g, analisis.pasoAOtroTransformador(minutos));
            if (paso != null) debajo.add(paso);
            debajo.add(new Html(textoMaquinas(consumoMedidores.calcular(ini, fin, g.redAsociada()), g.redAsociada())));
            if (enLugar && chartDe.containsKey(g.nombre())) {
                Div chart = chartDe.get(g.nombre());
                maquinasDe.get(g.nombre()).removeAll();
                maquinasDe.get(g.nombre()).add(debajo.toArray(Component[]::new));
                boolean estabaOculto = !chart.isVisible();
                chart.setVisible(completo != null);
                if (completo != null && tabs.getSelectedTab() == tabPeriodos) {
                    if (estabaOculto) getElement().executeJs(completo);
                    else dibujar(soloDatos, completo, anteriores.get(g.nombre()));
                }
                continue;
            }
            Div chart = new Div();
            chart.setId(id);
            chart.setWidthFull();
            chart.setHeight("280px");
            chart.setVisible(completo != null);
            Div lista = new Div(debajo.toArray(Component[]::new));
            chartDe.put(g.nombre(), chart);
            maquinasDe.put(g.nombre(), lista);
            Div contenido = new Div(chart, lista);
            contenido.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "6px");
            generadoresDetalle.add(caja(titulo, contenido));
            if (completo != null && tabs.getSelectedTab() == tabPeriodos) getElement().executeJs(completo);
        }
        scriptsGenerador = scripts;
        recuadrosDibujados = estructura;
    }

    /**
     * Lo que el generador produjo de más, salió por su transformador y lo consumió el otro transformador (pedido del
     * usuario el 2026-10-27): solo informativo, ya está dentro del aporte del generador. Null si no pasó nada (menos de 1 kWh).
     */
    private Html textoPaso(Generador g, double kwh) {
        if (kwh < 0.5) return null;
        List<String> otros = generadorService.generadores().stream().map(Generador::redAsociada)
                .filter(r -> r != null && !r.equals(g.redAsociada())).distinct().map(GeneradorView::nombreRed).toList();
        String destino = otros.size() == 1 ? "al " + otros.get(0) : "a otro transformador";
        return new Html("<div style=\"font-size:13px;color:" + TEXTO_SUAVE + ";margin-bottom:6px\">Paso " + destino
                + ": <b style=\"color:" + css(AQUA) + "\">" + kwh(kwh) + "</b></div>");
    }

    /**
     * Las máquinas del transformador en el tiempo en que trabajó el generador, cada una con su estado según el umbral de
     * encendido del horómetro (acordado con el usuario el 2026-10-23; el color lo dice, sin explicarlo en el texto):
     * verde "trabajando" = pasó el umbral, con su potencia media (sin el tiempo que paró: pedido del usuario); amarillo "parada" = por
     * debajo del umbral con consumo mínimo, con lo que consumió; gris "parada" = consumo cero, y "sin dato".
     */
    private static String textoMaquinas(List<ConsumoMedidor> lista, String transformador) {
        List<ConsumoMedidor> orden = new ArrayList<>(lista);
        // Primero las que trabajaron (de mayor a menor potencia), después las paradas con consumo, las en cero y sin dato.
        orden.sort(java.util.Comparator.comparingInt((ConsumoMedidor c) -> switch (c.estado()) {
            case ENCENDIDO, ENERGIZADO_Y_SIN_PRODUCCION -> 0;
            case APAGADO_CONSUMO_MINIMO -> 1;
            case APAGADO_CERO -> 2;
            case SIN_DATOS -> 3;
        }).thenComparing(c -> -(c.kwMedio() == null ? 0 : c.kwMedio())));
        int trabajando = 0, paradas = 0;
        StringBuilder chips = new StringBuilder();
        for (ConsumoMedidor c : orden) {
            String kw = c.kwMedio() == null ? "" : String.format(Locale.ROOT, " (%,.1f kW)", c.kwMedio());
            String texto;
            Estado e;
            switch (c.estado()) {
                case ENCENDIDO, ENERGIZADO_Y_SIN_PRODUCCION -> {
                    trabajando++;
                    texto = "trabajando" + kw;
                    e = Estado.OK;
                }
                case APAGADO_CONSUMO_MINIMO -> {
                    paradas++;
                    texto = "parada" + kw;
                    e = Estado.AVISO;
                }
                case APAGADO_CERO -> {
                    paradas++;
                    texto = "parada";
                    e = Estado.SIN_DATO;
                }
                default -> {
                    texto = "sin dato";
                    e = Estado.SIN_DATO;
                }
            }
            String[] t = tonos(e);
            chips.append("<span style='padding:2px 9px;border-radius:10px;font-size:12px;white-space:nowrap;background:").append(t[1])
                    .append(";color:").append(t[2]).append("'><b>").append(esc(c.medidor())).append("</b>: ").append(texto).append("</span>");
        }
        StringBuilder resumen = new StringBuilder().append(trabajando).append(" trabajando, ")
                .append(paradas).append(paradas == 1 ? " parada" : " paradas");
        return "<div style='display:grid;gap:6px;font-size:13px'>"
                + "<span style='font-size:12px;color:" + TEXTO_SUAVE + "'>Máquinas " + esc(GeneradorView.deRed(transformador)) + ": "
                + resumen + "</span><div style='display:flex;flex-wrap:wrap;gap:5px'>" + chips + "</div></div>";
    }

    /**
     * Una tarjeta chica por generador con lo que puso en la planta en el período (en % del consumo y en kWh) y su
     * carga media; el que no trabajó, en gris. Debajo, la barra de cómo se repartió el consumo: cada generador y la red.
     */
    private void pintarGeneradores(PeriodoPlanta p) {
        Double consumo = p.consumo();
        StringBuilder barra = new StringBuilder();
        for (Generador g : porTransformador()) {
            String color = css(colorGenerador(g.nombre()));
            Double aporte = p.aporte(g.nombre()), generado = p.generadoPor(g.nombre());
            double horas = p.horasDe(g.nombre());
            Div t = new Div();
            t.getStyle().set("border", "1px solid " + BORDE).set("border-left", "5px solid " + color).set("border-radius", "10px")
                    .set("padding", "8px 12px").set("background-color", "#ffffff").set("color", TEXTO);
            if (generado == null && aporte == null) {
                t.getStyle().set("border-left-color", "#C9CDD6").set("color", TEXTO_SUAVE);
                t.add(new Html("<div style='display:grid;gap:2px'><span style='font-weight:700;font-size:14px'>" + esc(g.nombre())
                        + "</span><span style='font-size:12px'>No trabajo en este periodo</span></div>"));
            } else {
                double pct = consumo == null || consumo <= 0 || aporte == null ? 0 : Math.min(100, aporte / consumo * 100);
                String carga = generado == null || horas <= 0 ? "-" : String.format(Locale.ROOT, "%,.0f kW", generado / horas);
                // Horómetro del controlador al arrancar (su primer arranque del período) y al parar (el último), y las horas
                // que trabajó: lo que avanzó el horómetro en cada arranque (si sigue en marcha, el tiempo transcurrido).
                List<PeriodoMarcha> suyos = p.arranques().stream().filter(a -> a.generador().equals(g.nombre()))
                        .sorted(java.util.Comparator.comparing(PeriodoMarcha::inicio)).toList();
                double trabajadas = 0;
                for (PeriodoMarcha a : suyos) {
                    trabajadas += a.horometroInicio() != null && a.horometroFin() != null
                            ? Math.max(0, a.horometroFin() - a.horometroInicio()) : a.horas();
                }
                String horo = "<b>" + String.format(Locale.ROOT, "%,.1f h", trabajadas) + "</b> trabajadas ("
                        + horometro(suyos.get(0).horometroInicio()) + " -> " + horometro(suyos.get(suyos.size() - 1).horometroFin()) + ")";
                t.add(new Html("<div style='display:grid;gap:3px'>"
                        + "<div style='display:flex;justify-content:space-between;align-items:baseline'><span style='font-weight:700;font-size:14px'>"
                        + esc(g.nombre()) + "</span><span style='font-size:20px;font-weight:700'>" + String.format(Locale.ROOT, "%.1f %%", pct) + "</span></div>"
                        + "<div style='height:6px;border-radius:3px;background:#EEF0F4'><div style='height:6px;border-radius:3px;width:"
                        + String.format(Locale.ROOT, "%.2f%%", pct) + ";background:" + color + "'></div></div>"
                        + "<span style='font-size:12px;color:" + TEXTO_SUAVE + "'>del consumo de la planta</span>"
                        // Lo que puso en la planta (sin lo que salió a la red), el mismo número del %.
                        + "<span style='font-size:12px'>Aportó <b>" + kwh(aporte) + "</b> · carga media <b>" + carga + "</b></span>"
                        + "<span style='font-size:12px;color:" + TEXTO_SUAVE + "'>Horómetro: " + horo + "</span></div>"));
                if (pct > 0) barra.append(segmentoHtml(pct, color, esc(g.nombre())));
            }
            generadoresPeriodo.add(t);
        }
        Double pctRed = p.porcentajeDeRed();
        if (consumo != null && consumo > 0) {
            if (pctRed != null && pctRed > 0) barra.append(segmentoHtml(pctRed, css(AZUL), "Red"));
            generadoresPeriodo.add(new Html("<div style='display:grid;gap:4px'><span style='font-size:12px;color:" + TEXTO_SUAVE
                    + "'>Consumo de la planta en el periodo: <b style='color:" + TEXTO + "'>" + kwh(consumo) + "</b></span>"
                    + "<div style='display:flex;height:22px;border-radius:6px;overflow:hidden;font-size:11px;font-weight:600'>"
                    + barra + "</div></div>"));
        }
    }

    /**
     * Tabla de cuentas del período seleccionado, al final de todo (pedido del usuario el 2026-10-25): una fila por arranque
     * (Transformador 1 primero) y el total. Por fila, aporte del generador + de la red = consumo de la planta; con dos
     * generadores a la vez, el consumo y la red se reparten entre los dos según lo que generaba cada uno, así nada se
     * cuenta dos veces. El total es la suma de los números redondeados de las filas, para que cuadre con calculadora.
     */
    private void pintarResumen(PeriodoPlanta p) {
        List<String> orden = porTransformador().stream().map(Generador::nombre).toList();
        List<PeriodoMarcha> filas = p.arranques().stream()
                .sorted(java.util.Comparator.comparing((PeriodoMarcha a) -> orden.indexOf(a.generador())).thenComparing(PeriodoMarcha::inicio))
                .toList();
        String th = "<th style='padding:6px 8px;border-bottom:1px solid " + BORDE + ";font-weight:600;font-size:12px;color:" + TEXTO_SUAVE;
        String td = "<td style='padding:6px 8px;border-bottom:1px solid #EEF0F4";
        String num = ";text-align:right;font-variant-numeric:tabular-nums'>";
        StringBuilder h = new StringBuilder("<div style='overflow-x:auto'><table style='border-collapse:collapse;width:100%;font-size:13px;color:")
                .append(TEXTO).append("'><tr>")
                .append(th).append(";text-align:left'>Inicio</th>").append(th).append(";text-align:left'>Fin</th>")
                .append(th).append(";text-align:left'>Generador</th>").append(th).append(";text-align:right'>Duración</th>")
                .append(th).append(";text-align:right'>Aporte del generador (kWh)</th>").append(th).append(";text-align:right'>De la red (kWh)</th>")
                .append(th).append(";text-align:right'>Consumo planta (kWh)</th>").append(th).append(";text-align:right'>Exportada (kWh)</th></tr>");
        long aporte = 0, red = 0, consumo = 0, exportada = 0;
        // El % de cada fila es sobre el consumo de toda la planta (el total), el mismo de las tarjetas y del indicador.
        long total = 0;
        for (PeriodoMarcha a : filas) total += redondo(a.generadorUsado()) + redondo(a.kwhRedImportada());
        for (PeriodoMarcha a : filas) {
            long ap = redondo(a.generadorUsado()), r = redondo(a.kwhRedImportada()), e = redondo(a.kwhRedExportada());
            // Consumo de la fila = aporte + red ya redondeados: así la fila cuadra a la vista.
            long c = ap + r;
            aporte += ap;
            red += r;
            consumo += c;
            exportada += e;
            h.append("<tr>").append(td).append("'>").append(a.inicio().format(CORTO)).append("</td>")
                    .append(td).append("'>").append(a.enCurso() ? "en marcha" : a.fin().format(CORTO)).append("</td>")
                    .append(td).append("'><span style='display:inline-flex;align-items:center;gap:6px'><span style='width:10px;height:10px;border-radius:50%;background:")
                    .append(css(colorGenerador(a.generador()))).append("'></span>").append(esc(a.generador())).append("</span></td>")
                    .append(td).append(num).append(horasMinutos(a.inicio(), a.fin())).append("</td>")
                    .append(td).append(num).append(String.format(Locale.ROOT, "%,d", ap)).append(pctDe(ap, total)).append("</td>")
                    .append(td).append(num).append(String.format(Locale.ROOT, "%,d", r)).append(pctDe(r, total)).append("</td>")
                    .append(td).append(num).append(String.format(Locale.ROOT, "%,d", c)).append(pctDe(c, total)).append("</td>")
                    .append(td).append(num).append(String.format(Locale.ROOT, "%,d", e)).append("</td></tr>");
        }
        String tt = "<td style='padding:6px 8px;font-weight:700;border-top:2px solid " + TEXTO + ";background:" + FONDO_SUAVE;
        h.append("<tr>").append(tt).append("'>").append(p.inicio().format(CORTO)).append("</td>")
                .append(tt).append("'>").append(p.enCurso() ? "en marcha" : p.fin().format(CORTO)).append("</td>")
                .append(tt).append("'>Total</td>")
                .append(tt).append(num).append(horasMinutos(p.inicio(), p.fin())).append("</td>")
                .append(tt).append(num).append(String.format(Locale.ROOT, "%,d", aporte)).append(pctDe(aporte, total)).append("</td>")
                .append(tt).append(num).append(String.format(Locale.ROOT, "%,d", red)).append(pctDe(red, total)).append("</td>")
                .append(tt).append(num).append(String.format(Locale.ROOT, "%,d", consumo)).append(pctDe(consumo, total)).append("</td>")
                .append(tt).append(num).append(String.format(Locale.ROOT, "%,d", exportada)).append("</td></tr></table></div>");
        resumenPeriodo.add(caja("Resumen del periodo seleccionado", new Html(h.toString())));
    }

    /** " 39.9 %" chico y en gris al lado del kWh: parte del consumo de toda la planta. */
    private static String pctDe(long kwh, long total) {
        return total <= 0 ? "" : "<span style='display:inline-block;min-width:52px;font-size:11px;font-weight:400;color:" + TEXTO_SUAVE
                + "'>" + String.format(Locale.ROOT, "%.1f %%", kwh * 100.0 / total) + "</span>";
    }

    private static long redondo(Double v) {
        return v == null ? 0 : Math.round(v);
    }

    /** Duración en horas:minutos, ej. "96:03". */
    private static String horasMinutos(LocalDateTime desde, LocalDateTime hasta) {
        long min = Math.max(0, Duration.between(desde, hasta).toMinutes());
        return String.format(Locale.ROOT, "%d:%02d", min / 60, min % 60);
    }

    /** Tramo de la barra de reparto; el texto (nombre y %) solo si entra. Texto oscuro sobre el naranja claro. */
    private static String segmentoHtml(double pct, String color, String nombre) {
        return segmentoHtml(pct, color, nombre, 18);
    }

    /** Igual, con el texto desde {@code minimo} % (la barra de Resumen de energia es ancha: 8). */
    private static String segmentoHtml(double pct, String color, String nombre, double minimo) {
        String texto = color.equalsIgnoreCase(css(NARANJA)) ? TEXTO : "#ffffff";
        return "<div style='width:" + String.format(Locale.ROOT, "%.2f%%", pct) + ";background:" + color + ";color:" + texto
                + ";display:flex;align-items:center;justify-content:center;white-space:nowrap;overflow:hidden'>"
                + (pct >= minimo ? nombre + " " + String.format(Locale.ROOT, "%.1f %%", pct) : "") + "</div>";
    }

    /**
     * Minuto a minuto del período: lo que aportaron entre todos los generadores a la planta (lo generado menos lo
     * exportado; desde el 2026-10-25 una sola línea, pedido del usuario), el consumo de toda la planta y la red por el
     * medidor principal (+ importada / - exportada), con marcadores de clic (fecha, hora y tiempo desde el anterior).
     */
    private void mostrarMinutos(PeriodoPlanta p) {
        List<Generador> gens = generadorService.generadores();
        List<GeneradorAnalisisService.PuntoPlanta> puntos = analisis.consumoPlanta(gens, p.inicio(), p.fin(), 1);
        if (puntos.isEmpty()) {
            chartMinutos.setVisible(false);
            scriptMinutos = null;
            return;
        }
        String[] nombres = {"Aporte de los generadores"};
        String[] colores = {NARANJA};
        List<Long> tiempos = new ArrayList<>();
        List<double[]> valores = new ArrayList<>();
        for (GeneradorAnalisisService.PuntoPlanta pt : puntos) {
            tiempos.add(pt.fecha().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            double generado = 0;
            for (double g : pt.generado()) generado += g;
            valores.add(new double[]{Math.max(0, generado - pt.exportada()), pt.consumo(), pt.importada(), pt.exportada()});
        }
        chartMinutos.setVisible(true);
        // Tooltips semitransparentes en esta pestaña (pedido del usuario: tapaban el gráfico).
        String antes = scriptMinutos;
        scriptMinutos = GraficaModel.conTooltipsTransparentes(GraficaModel.getConsumoPlantaScript(CHART_MINUTOS, tiempos, nombres,
                colores, valores, 1, false, NOCHE, AZUL, AQUA, true));
        if (tabs.getSelectedTab() == tabPeriodos) {
            dibujar(GraficaModel.conTooltipsTransparentes(GraficaModel.getConsumoPlantaScript(CHART_MINUTOS, tiempos, nombres,
                    colores, valores, 1, true, NOCHE, AZUL, AQUA, true)), scriptMinutos, antes);
        }
    }

    /** 2 -> "2 %", 2.5 -> "2.5 %". */
    private static String pctUmbral(double v) {
        return v == Math.rint(v) ? String.format(Locale.ROOT, "%.0f %%", v) : String.format(Locale.ROOT, "%.1f %%", v);
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
        badge(s, pct == null ? "-" : String.format(Locale.ROOT, "%.1f %%", pct), semaforoRed(pct));
        return s;
    }

    // ================= Tendencias =================

    private VerticalLayout crearTendencias() {
        ComboBox<String> variable = new ComboBox<>("Variable");
        variable.setItems(VARIABLES.keySet());
        variable.setValue("Tensión de batería (V)");
        variable.setWidth("260px");
        ComboBox<Integer> rango = new ComboBox<>("Periodo");
        rango.setItems(1, 7, 30, 90);
        rango.setItemLabelGenerator(d -> d == 1 ? "Últimas 24 h" : "Últimos " + d + " días");
        rango.setValue(7);
        Button verTendencia = new Button("Ver", VaadinIcon.CHART.create(), e -> graficarTendencia(variable.getValue(), rango.getValue()));
        verTendencia.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        HorizontalLayout filtros = new HorizontalLayout(variable, rango, verTendencia);
        filtros.setAlignItems(Alignment.END);

        tendenciaMensaje.getStyle().set("font-size", "12px").set("color", "#555");
        tendenciaMensaje.setText("Datos tomados de la base de datos: una lectura por minuto en marcha y una cada 15 min parado. "
                + "La batería y la temperatura parado muestran si el generador está listo para arrancar. Una línea por generador.");
        Div chart = new Div();
        chart.setId(CHART_TENDENCIAS);
        chart.setWidthFull();
        chart.setHeight("420px");
        VerticalLayout v = new VerticalLayout(descripcion("Cómo evolucionó una variable del controlador en las últimas horas o "
                + "días: batería, temperaturas, presión de aceite, potencia, corrientes, tensiones o frecuencia."),
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
        // Colores de la paleta de la ventana: una variable = el color del generador; tres fases = naranja, azul y verde
        // azulado (con Todos, las del segundo generador en línea punteada).
        String[] fases = {NARANJA, AZUL, AQUA};
        if (columnas.length == 1) {
            g.setColoresPersonalizados(colores.toArray(new String[0]));
        } else {
            String[] c = new String[n];
            for (int i = 0; i < n; i++) c[i] = fases[i % columnas.length % fases.length];
            g.setColoresPersonalizados(c);
        }
        if (todos && columnas.length > 1) {
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
                + (todos ? ", una línea por generador." : "."));
        getElement().executeJs(g.getInitScript2(CHART_TENDENCIAS) + g.getSetAllDataScript(CHART_TENDENCIAS, t, v)
                + g.getAplicarZoomInicialScript(CHART_TENDENCIAS));
    }

    // ================= Costos =================

    private VerticalLayout crearCostos() {
        tarjetasCostos.setWidthFull();
        tarjetasCostos.getStyle().set("flex-wrap", "wrap");
        chartCostos.setId(CHART_COSTOS);
        chartCostos.setWidthFull();
        chartCostos.setHeight("460px");
        // Un par de columnas por arranque, con su etiqueta de 4 líneas: con muchos arranques, barra de desplazamiento.
        marcoCostos.add(chartCostos);
        marcoCostos.setWidthFull();
        marcoCostos.getStyle().set("overflow-x", "auto");
        costosGrid.addColumn(PeriodoMarcha::generador).setHeader("Generador").setAutoWidth(true).setFlexGrow(0).setFrozen(true);
        costosGrid.addColumn(p -> p.nro() == null ? "-" : String.valueOf(p.nro())).setHeader("Nro").setAutoWidth(true).setFlexGrow(0);
        costosGrid.addColumn(p -> p.inicio().format(CORTA)).setHeader("Inicio").setAutoWidth(true).setFlexGrow(0);
        columna(costosGrid, "Duración", p -> duracion(p.horas()) + (p.enCurso() ? " (en marcha)" : ""));
        columna(costosGrid, "Generado (kWh)", p -> kwhNum(p.kwhGenerado()));
        columna(costosGrid, "Exportada a la red (kWh)", p -> kwhNum(p.kwhRedExportada()));
        columna(costosGrid, "Consumida en planta (kWh)", p -> kwhNum(p.generadorUsado()));
        costosGrid.addColumn(GeneradorAnalisisView::franjas).setHeader("Consumida en planta por franja (kWh)").setAutoWidth(true).setFlexGrow(0);
        columna(costosGrid, "Galones", p -> c(p) == null ? "-" : String.format(Locale.ROOT, "%,.2f", c(p).getGalones()));
        columna(costosGrid, "Precio galón", p -> c(p) == null ? "-" : dinero(c(p).getPrecioGalon()));
        columna(costosGrid, "Costo diésel", p -> c(p) == null ? "-" : dinero(c(p).costo()));
        columna(costosGrid, "Costo equivalente en la red", p -> p.costoRed() == null ? "-" : dinero(p.costoRed()));
        columna(costosGrid, "Diésel $/kWh", p -> precioKwh(precioDiesel(p)));
        columna(costosGrid, "Red $/kWh", p -> precioKwh(p.precioRedMedio()));
        costosGrid.addComponentColumn(this::vecesSpan).setHeader("Relación diésel / red").setAutoWidth(true).setFlexGrow(0)
                .setTextAlign(ColumnTextAlign.END);
        costosGrid.addComponentColumn(p -> diferenciaSpan(diferencia(p))).setHeader("Costo de más (o ahorro)").setAutoWidth(true).setFlexGrow(0)
                .setTextAlign(ColumnTextAlign.END);
        columna(costosGrid, "kWh por galón", p -> c(p) == null || c(p).getGalones() <= 0 || p.kwhGenerado() == null ? "-"
                : String.format(Locale.ROOT, "%.1f", p.kwhGenerado() / c(p).getGalones()));
        columna(costosGrid, "Galones por hora", p -> c(p) == null || p.horas() <= 0 ? "-"
                : String.format(Locale.ROOT, "%.1f", c(p).getGalones() / p.horas()));
        costosGrid.addColumn(p -> c(p) == null ? "" : (c(p).getUsuario() == null ? "" : c(p).getUsuario())
                + (c(p).getObservacion() == null ? "" : " - " + c(p).getObservacion()))
                .setHeader("Cargado por / observación").setAutoWidth(true);
        if (lineaAccessService.esAdmin()) {
            costosGrid.addComponentColumn(p -> {
                Button b = new Button(c(p) == null ? "Cargar" : "Editar", VaadinIcon.EDIT.create(), e -> abrirDialogoCosto(p));
                b.addThemeVariants(ButtonVariant.LUMO_SMALL);
                return b;
            }).setHeader("").setAutoWidth(true).setFlexGrow(0);
        }
        costosGrid.setWidthFull();
        costosGrid.setHeight("520px");
        Span nota = nota("Por cada arranque se cargan los galones de diésel usados y el precio del galón"
                + (lineaAccessService.esAdmin() ? " (botón Cargar / Editar, o Importar planilla MF13: calcula los galones con la "
                + "planilla de los operadores y los guarda después de confirmar)" : " (solo el administrador)")
                + "; al guardarlos aparece su comparación. Consumida en planta = generado - exportado a la red: lo que salió a la red "
                + "no reemplazó energía de la red ni se paga. Costo equivalente en la red = cada kWh consumido en planta al precio de "
                + "la franja horaria (A, B, C o D) de la hora en que se generó, con la tarifa de su mes y los feriados de Consumo por "
                + "franja horaria. Diésel $/kWh = costo del diésel / kWh consumidos en planta. Relación diésel / red = costo del diésel "
                + "/ costo equivalente en la red: 2.0 veces = generar costó el doble. Solo se compara energía contra combustible: no "
                + "entran el mantenimiento del generador ni los otros cargos de la factura de la red (demanda, comercialización). Las "
                + "tarjetas de arriba suman solo los arranques con diésel cargado. Exportada a la red sin pago = kWh exportados x "
                + "costo del diésel por kWh generado.");

        VerticalLayout v = new VerticalLayout(descripcion("Cuánto cuesta generar con diésel contra comprar la misma energía a la red, "
                + "arranque por arranque. El precio de la red es el de la franja horaria de la hora en que se generó cada kWh."),
                tarjetasCostos, marcoCostos);
        if (lineaAccessService.esAdmin()) v.add(botonPlanilla());
        v.add(costosGrid, nota);
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
        badge(s, String.format(Locale.ROOT, "%.1f veces", veces), veces > 1 ? Estado.FUERA : Estado.OK);
        return s;
    }

    private static Span diferenciaSpan(Double v) {
        Span s = new Span(v == null ? "-" : v >= 0 ? dinero(v) + " de más" : dinero(-v) + " de ahorro");
        if (v != null) s.getStyle().set("color", tonos(v > 0 ? Estado.FUERA : Estado.OK)[2]).set("font-weight", "600");
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
        Map<String, double[]> porHora = new LinkedHashMap<>(); // generador -> {galones, horas en marcha}
        // Comparados: además, con costo en la red y algo aprovechado.
        double costoDiesel = 0, costoRed = 0, kwhAprovechado = 0, kwhEntregado = 0, kwhGenerado = 0;
        int comparados = 0;
        List<GraficaModel.BarraCosto> barras = new ArrayList<>();
        for (int i = periodos.size() - 1; i >= 0; i--) { // del más viejo al más nuevo (orden del gráfico)
            PeriodoMarcha p = periodos.get(i);
            CostoCombustible cc = c(p);
            if (cc == null) continue;
            cargados++;
            galones += cc.getGalones();
            costoCargados += cc.costo();
            horasCargados += p.horas();
            double[] h = porHora.computeIfAbsent(p.generador(), k -> new double[2]);
            h[0] += cc.getGalones();
            h[1] += p.horas();
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
            barras.add(barraCosto(p, cc, aprovechado, css(colorGenerador(p.generador()))));
        }

        tarjetasCostos.removeAll();
        if (cargados == 0) {
            tarjetasCostos.add(tarjeta("Comparación con la red", "-", "ningún arranque del periodo tiene el diésel cargado"
                    + (lineaAccessService.esAdmin() ? " (botón Cargar en la tabla)" : "")));
        } else if (comparados == 0) {
            tarjetasCostos.add(tarjeta("Comparación con la red", "sin tarifa", "cargar la tarifa en Consumo por franja horaria > Tarifas"));
        } else {
            tarjetasCostos.add(tarjeta("Costo de generar (diésel)", dinero(costoDiesel), precioKwh(costoDiesel / kwhAprovechado)
                    + " por kWh consumido en planta; " + comparados + " de " + periodos.size() + " arranques con diésel cargado"));
            tarjetasCostos.add(tarjeta("Costo equivalente en la red", dinero(costoRed), precioKwh(costoRed / kwhAprovechado)
                    + " por kWh, con el precio de la franja de cada hora"));
            double diferencia = costoDiesel - costoRed;
            tarjetasCostos.add(diferencia > 0
                    ? tarjetaKpi(String.format(Locale.ROOT, "Generar sale %.1f veces la red", costoDiesel / costoRed),
                    dinero(diferencia) + " de más", String.format(Locale.ROOT, "%,.0f kWh consumidos en planta", kwhAprovechado), Estado.FUERA)
                    : tarjetaKpi("Generar sale más barato que la red", dinero(-diferencia) + " de ahorro",
                    String.format(Locale.ROOT, "%,.0f kWh consumidos en planta", kwhAprovechado), Estado.OK));
            double costoKwhGenerado = kwhGenerado > 0 ? costoDiesel / kwhGenerado : 0;
            tarjetasCostos.add(tarjeta("Exportada a la red sin pago", kwh(kwhEntregado), kwhEntregado < 0.5 ? null
                    : dinero(kwhEntregado * costoKwhGenerado) + " de diésel, a " + precioKwh(costoKwhGenerado) + " por kWh generado"));
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
            tarjetasCostos.add(tarjeta("Diésel usado", String.format(Locale.ROOT, "%,.1f gal", galones),
                    galones > 0 ? "precio medio " + dinero(costoCargados / galones) + " por galón" : null));
            // Galones por hora de marcha (con diésel cargado): el de todos arriba y el de cada generador en su renglón.
            if (horasCargados > 0) {
                List<String> galHora = new ArrayList<>();
                porHora.forEach((g, h) -> { if (h[1] > 0) galHora.add(g + String.format(Locale.ROOT, " %.1f gal/h", h[0] / h[1])); });
                tarjetasCostos.add(tarjeta("Consumo por hora", String.format(Locale.ROOT, "%.1f gal/h", galones / horasCargados),
                        galHora.size() > 1 ? String.join("\n", galHora) : null));
            }
        }

        if (comparados == 0) {
            scriptCostos = null;
            marcoCostos.setVisible(false);
            return;
        }
        marcoCostos.setVisible(true);
        chartCostos.setWidth("max(100%, " + (barras.size() * 64 + 80) + "px)");
        String antes = scriptCostos;
        scriptCostos = GraficaModel.getCostoFranjasScript(CHART_COSTOS, barras, "Diésel $/kWh consumido en planta", NARANJA,
                NOMBRES_FRANJA, COLORES_FRANJA);
        if (tabs.getSelectedTab() == tabCostos) dibujar(scriptCostos, antes);
    }

    /**
     * Un arranque del gráfico de Costos: diésel por kWh consumido en planta y, al lado, la red apilada por franja (costo de
     * la franja / kWh del arranque: apiladas dan el precio medio de la red), con su detalle en el tooltip.
     */
    private static GraficaModel.BarraCosto barraCosto(PeriodoMarcha p, CostoCombustible cc, double aprovechado, String colorGen) {
        DateTimeFormatter diaHora = DateTimeFormatter.ofPattern("dd-MM HH:mm"), hora = DateTimeFormatter.ofPattern("HH:mm");
        long min = Duration.between(p.inicio(), p.fin()).toMinutes();
        String fin = p.enCurso() ? "en marcha" : "a " + p.fin().format(p.fin().toLocalDate().equals(p.inicio().toLocalDate()) ? hora : diaHora);
        // Debajo del par, corto como en el reporte: la fecha de inicio y el generador (dos arranques pueden empezar juntos),
        // este en su color (el de Transformadores y el resto de Análisis), con el formato de texto de amCharts.
        String categoria = p.inicio().getDayOfMonth() + " " + MESES[p.inicio().getMonthValue() - 1].substring(0, 3) + " "
                + p.inicio().format(hora) + "\n[" + colorGen + " bold]" + p.generador() + "[/]";
        String titulo = p.generador() + " " + p.inicio().format(diaHora) + " " + fin
                + String.format(Locale.ROOT, " (%d:%02d h)", min / 60, min % 60);
        double diesel = cc.costo() / aprovechado, red = p.costoRed() / aprovechado;
        String veces = String.format(Locale.ROOT, "%.1f", diesel / red);
        String pie = "\nDiésel " + veces + " veces la red";
        double[] partes = new double[Franja.values().length];
        String[] tips = new String[partes.length];
        for (Franja f : Franja.values()) {
            int i = f.ordinal();
            double kwh = p.aprovechadoFranja() == null ? 0 : p.aprovechadoFranja()[i];
            double costo = p.costoRedFranja() == null ? 0 : p.costoRedFranja()[i];
            partes[i] = costo / aprovechado;
            tips[i] = titulo + "\nFranja " + f.name() + " (" + f.horario() + "): "
                    + String.format(Locale.ROOT, "%,.0f kWh (%.0f %%)", kwh, 100 * kwh / aprovechado)
                    + "\nCosto en la red: " + dinero(costo)
                    + String.format(Locale.ROOT, "\n%.3f de los %.3f $/kWh de la red", partes[i], red) + pie;
        }
        String tipDiesel = titulo + String.format(Locale.ROOT, "\nDiésel: %.3f $/kWh", diesel)
                + String.format(Locale.ROOT, "\n%,.1f gal x %s = %s / %,.0f kWh", cc.getGalones(), dinero(cc.getPrecioGalon()), dinero(cc.costo()), aprovechado)
                + String.format(Locale.ROOT, "\nRed: %.3f $/kWh", red) + pie;
        return new GraficaModel.BarraCosto(categoria, diesel, partes, tipDiesel, tips, veces + "×", diesel > red);
    }

    private void abrirDialogoCosto(PeriodoMarcha p) {
        CostoCombustible actual = c(p);
        Dialog d = new Dialog();
        d.setHeaderTitle("Combustible del arranque " + (p.nro() == null ? "" : "Nro " + p.nro() + " ") + "de " + p.generador());
        Span detalle = new Span("Del " + p.inicio().format(CORTA) + (p.enCurso() ? " (sigue en marcha)" : " al " + p.fin().format(CORTA))
                + ", " + duracion(p.horas()) + ", " + kwh(p.kwhGenerado()) + " generados.");
        detalle.getStyle().set("font-size", "13px").set("color", "#555");
        NumberField galones = new NumberField("Galones de diésel usados");
        galones.setMin(0);
        galones.setMax(CostoCombustibleService.GALONES_MAXIMO);
        galones.setStep(0.1);
        galones.setWidthFull();
        NumberField precio = new NumberField("Precio del galón ($)");
        precio.setMin(0.01);
        precio.setMax(CostoCombustibleService.PRECIO_MAXIMO);
        precio.setStep(0.001);
        precio.setWidthFull();
        TextField observacion = new TextField("Observación (opcional)");
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
                Notification.show("Ingrese los galones y el precio del galón", 3000, Notification.Position.MIDDLE)
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

    /**
     * Botón que abre el selector de archivos del navegador: el .xlsx se lee en el navegador y llega en base64 a
     * {@link #recibirPlanillaMf13} (sin el componente Upload, que no está en el bundle de producción).
     */
    private Component botonPlanilla() {
        Button b = new Button("Importar planilla MF13", VaadinIcon.FILE_TABLE.create());
        b.setTooltipText("Calcula los galones de cada arranque con la planilla de los operadores (.xlsx) y pide confirmación antes de guardar");
        b.getElement().executeJs("""
                const vista = $0;
                const input = document.createElement('input');
                input.type = 'file';
                input.accept = '.xlsx';
                input.style.display = 'none';
                this.after(input);
                this.addEventListener('click', () => { input.value = ''; input.click(); });
                input.addEventListener('change', () => {
                  const f = input.files[0];
                  if (!f) return;
                  const r = new FileReader();
                  r.onload = () => vista.$server.recibirPlanillaMf13(f.name, String(r.result).split(',')[1] || '');
                  r.readAsDataURL(f);
                });""", getElement());
        return b;
    }

    @ClientCallable
    public void recibirPlanillaMf13(String nombre, String base64) {
        if (!lineaAccessService.esAdmin()) return;
        PlanillaCombustibleService.Resultado r;
        try {
            r = planillaCombustible.calcular(Base64.getDecoder().decode(base64), generadorService.generadores());
        } catch (Exception e) {
            Notification.show("No se pudo leer " + nombre + ": " + e.getMessage(), 6000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }
        abrirDialogoPlanilla(nombre, r);
    }

    private void abrirDialogoPlanilla(String archivo, PlanillaCombustibleService.Resultado r) {
        Dialog d = new Dialog();
        d.setHeaderTitle("Combustible desde la planilla MF13");
        d.setWidth("1150px");
        d.setMaxWidth("95vw");
        Set<Calculo> marcados = new HashSet<>();
        r.arranques().stream().filter(c -> c.galones() != null && c.actual() == null).forEach(marcados::add);

        Span resumen = new Span(archivo + ": " + r.arranques().size() + " arranques con lecturas en la planilla. Quedan marcados "
                + "los que no tienen combustible cargado; marque también los que quiera reemplazar.");
        resumen.getStyle().set("font-size", "13px").set("color", "#555");
        VerticalLayout contenido = new VerticalLayout(resumen);
        contenido.setPadding(false);
        for (String aviso : r.avisos()) {
            Span s = new Span(aviso);
            s.getStyle().set("font-size", "12px").set("color", tonos(Estado.AVISO)[2]);
            contenido.add(s);
        }

        Grid<Calculo> grid = new Grid<>();
        grid.addComponentColumn(c -> {
            Checkbox cb = new Checkbox(marcados.contains(c));
            cb.setEnabled(c.galones() != null);
            cb.addValueChangeListener(e -> {
                if (e.getValue()) marcados.add(c);
                else marcados.remove(c);
            });
            return cb;
        }).setHeader("").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(Calculo::generador).setHeader("Generador").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(c -> c.inicio().format(CORTA)).setHeader("Inicio").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(c -> c.fin().format(CORTA)).setHeader("Fin").setAutoWidth(true).setFlexGrow(0);
        columna(grid, "Generado (kWh)", c -> kwhNum(c.kwh()));
        columna(grid, "Galones", c -> c.galones() == null ? "-" : String.format(Locale.ROOT, "%,.1f", c.galones()));
        columna(grid, "kWh por galón", c -> c.kwhPorGalon() == null ? "-" : String.format(Locale.ROOT, "%.1f", c.kwhPorGalon()));
        columna(grid, "Cargado ahora", c -> c.actual() == null ? "-" : String.format(Locale.ROOT, "%,.1f gal", c.actual().getGalones()));
        grid.addColumn(Calculo::detalle).setHeader("Cómo se calculó").setAutoWidth(true);
        grid.setItems(r.arranques());
        grid.setWidthFull();
        grid.setHeight("420px");

        NumberField precio = new NumberField("Precio del galón ($)");
        precio.setMin(0.01);
        precio.setMax(CostoCombustibleService.PRECIO_MAXIMO);
        precio.setStep(0.001);
        costos.ultimoPrecio().ifPresent(precio::setValue);
        contenido.add(grid, precio);
        d.add(contenido);

        Button guardar = new Button("Guardar marcados", e -> {
            if (precio.getValue() == null || precio.isInvalid()) {
                Notification.show("Ingrese el precio del galón", 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            List<Calculo> lista = marcados.stream().filter(c -> c.galones() != null).toList();
            if (lista.isEmpty()) {
                Notification.show("No hay arranques marcados", 3000, Notification.Position.MIDDLE);
                return;
            }
            try {
                for (Calculo c : lista) {
                    String obs = "Planilla MF13 (" + archivo + "): " + c.detalle();
                    costos.guardar(c.generador(), c.arranqueId(), c.inicio(), c.galones(), precio.getValue(),
                            obs.length() > 255 ? obs.substring(0, 255) : obs, lineaAccessService.usuarioActual());
                }
            } catch (IllegalArgumentException ex) {
                Notification.show(ex.getMessage(), 4000, Notification.Position.MIDDLE).addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            d.close();
            verCostos();
            Notification.show("Combustible guardado en " + lista.size() + " arranques", 3000, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        });
        guardar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        d.getFooter().add(new Button("Cancelar", e -> d.close()), guardar);
        d.open();
    }

    // ================= Utilidades =================

    private static <T> Grid.Column<T> columna(Grid<T> g, String titulo, Function<T, String> valor) {
        return g.addColumn(valor::apply).setHeader(titulo).setAutoWidth(true).setFlexGrow(0).setTextAlign(ColumnTextAlign.END);
    }

    private static Component tarjeta(String titulo, String valor, String detalle) {
        Span t = new Span(titulo);
        t.getStyle().set("font-size", "12px").set("color", TEXTO_SUAVE);
        Span v = new Span(valor);
        v.getStyle().set("font-size", "24px").set("font-weight", "700").set("color", TEXTO);
        VerticalLayout c = new VerticalLayout(t, v);
        if (detalle != null) {
            Span d = new Span(detalle);
            d.getStyle().set("font-size", "11px").set("color", TEXTO_SUAVE).set("white-space", "pre-line"); // \n = otro renglón
            c.add(d);
        }
        c.setSpacing(false);
        c.setPadding(false);
        c.setWidth("200px");
        c.getStyle().set("border", "1px solid " + BORDE).set("border-radius", "12px").set("padding", "12px 16px")
                .set("background-color", "#ffffff").set("box-shadow", "0 1px 3px rgba(30,30,44,0.06)");
        return c;
    }

    /** Tarjeta de indicador con semáforo: fondo suave y franja de arriba del color del estado. */
    private static Component tarjetaKpi(String titulo, String valor, String detalle, Estado e) {
        String[] c = tonos(e);
        Span t = new Span(titulo);
        t.getStyle().set("font-size", "12px").set("color", c[2]).set("font-weight", "600");
        Span v = new Span(valor);
        v.getStyle().set("font-size", "26px").set("font-weight", "700").set("color", c[2]);
        VerticalLayout k = new VerticalLayout(t, v);
        if (detalle != null) {
            Span d = new Span(detalle);
            d.getStyle().set("font-size", "11px").set("color", c[2]);
            k.add(d);
        }
        k.setSpacing(false);
        k.setPadding(false);
        k.setWidth("220px");
        k.getStyle().set("border", "1px solid " + BORDE).set("border-top", "4px solid " + c[0]).set("background-color", c[1])
                .set("border-radius", "12px").set("padding", "12px 16px");
        return k;
    }

    /**
     * Semáforo de la meta en tonos que combinan con la paleta de la ventana: {fuerte, fondo suave, texto}. Verde, amarillo
     * y rojo siempre van con su texto (número o etiqueta), nunca solo el color.
     */
    private static String[] tonos(Estado e) {
        return switch (e) {
            case OK -> new String[]{"#3BB273", "#E3F5EB", "#1E7A4C"};
            case AVISO -> new String[]{"#E0B50F", "#FBF3D3", "#7A5E00"};
            case FUERA -> new String[]{"#E8505B", "#FDE6E8", "#B42330"};
            case SIN_DATO -> new String[]{"#A0A6B4", "#EEF0F4", "#4B5263"};
        };
    }

    /** Etiqueta con el color del estado, en los tonos de esta ventana. */
    private static void badge(Span s, String texto, Estado e) {
        String[] c = tonos(e);
        s.setText(texto);
        s.getStyle().set("background-color", c[1]).set("color", c[2]).set("padding", "2px 10px")
                .set("border-radius", "10px").set("font-weight", "600").set("font-size", "13px").set("white-space", "nowrap");
    }

    /** "0xRRGGBB" (amCharts) a "#RRGGBB" (CSS). */
    private static String css(String color) {
        return "#" + color.substring(2);
    }

    /** "#RRGGBB" (CSS) a "0xRRGGBB" (amCharts). */
    private static String am(String color) {
        return "0x" + color.substring(1);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }

    private static Span nota(String texto) {
        Span s = new Span(texto);
        s.getStyle().set("font-size", "12px").set("color", TEXTO_SUAVE);
        return s;
    }

    /** Qué muestra la pestaña, en una línea, arriba de todo. */
    private static Span descripcion(String texto) {
        Span s = new Span(texto);
        s.getStyle().set("font-size", "14px").set("color", TEXTO).set("background-color", FONDO_SUAVE)
                .set("padding", "8px 12px").set("border-radius", "8px").set("display", "block");
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

    private static String dinero(double v) {
        return String.format(Locale.ROOT, "$ %,.2f", v);
    }
}
