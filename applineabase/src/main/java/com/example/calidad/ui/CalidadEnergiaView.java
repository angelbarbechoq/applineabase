package com.example.calidad.ui;

import com.example.base.model.GraficaModel;
import com.example.base.ui.ChartsView;
import com.example.base.ui.MainLayout;
import com.example.base.ui.NotificacionesUtil;
import com.example.calidad.model.ConfiguracionCalidad;
import com.example.calidad.model.Indicador;
import com.example.calidad.service.CalidadEnergiaService;
import com.example.calidad.service.CalidadEnergiaService.Bloque;
import com.example.calidad.service.CalidadEnergiaService.Cumplimiento;
import com.example.calidad.service.CalidadEnergiaService.Estado;
import com.example.calidad.service.CalidadEnergiaService.Fila;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ToDoubleFunction;

/**
 * Calidad de Energía (fase E2): estado actual por máquina, histórico con promedios de 10 minutos,
 * cumplimiento de límites por período y edición de límites (ADMIN). Lee los archivos
 * {mes}Calidad (fase E1); hoy solo tienen datos las máquinas que se leen por pasarela.
 */
@PageTitle("Calidad de Energia | LineaBase")
@Route(value = "calidad", layout = MainLayout.class)
@PermitAll
public class CalidadEnergiaView extends VerticalLayout implements BeforeEnterObserver {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final String CHART_ID = "chartdiv_calidad";

    private final CalidadEnergiaService service;
    private final LineaAccessService lineaAccessService;
    private final ConfigLoaderService configLoaderService;

    private final Grid<String> estadoGrid = new Grid<>();
    private final Span estadoNota = new Span();
    private final Grid<String> cumplimientoGrid = new Grid<>();
    private final Span historicoMensaje = new Span();

    private Map<String, Fila> ultimas = Map.of();
    private Map<String, Map<Indicador, Cumplimiento>> cumplimientos = Map.of();

    public CalidadEnergiaView(CalidadEnergiaService service, LineaAccessService lineaAccessService,
                              ConfigLoaderService configLoaderService) {
        this.service = service;
        this.lineaAccessService = lineaAccessService;
        this.configLoaderService = configLoaderService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);
        add(new H3("Calidad de Energia"));

        TabSheet tabs = new TabSheet();
        tabs.setSizeFull();
        tabs.add("Estado actual", crearEstado());
        tabs.add("Historico", crearHistorico());
        tabs.add("Cumplimiento", crearCumplimiento());
        if (lineaAccessService.esAdmin()) {
            tabs.add("Limites", crearLimites());
        }
        add(tabs);
        setFlexGrow(1, tabs);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        // Mismo alcance que alarmas: ADMIN y zona Mantenimiento.
        if (!lineaAccessService.puedeVerAlarmas()) {
            Notification.show("No tienes permiso para ver Calidad de Energia", 3000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            event.forwardTo(ChartsView.class);
        }
    }

    // ================= Estado actual =================

    private VerticalLayout crearEstado() {
        estadoGrid.addColumn(m -> m).setHeader("Maquina").setAutoWidth(true).setFrozen(true);
        estadoGrid.addColumn(m -> ultimas.get(m) == null ? "-" : ultimas.get(m).fecha().format(HORA))
                .setHeader("Ultima lectura").setAutoWidth(true);
        for (Indicador ind : Indicador.values()) {
            estadoGrid.addComponentColumn(m -> celdaEstado(m, ind)).setHeader(ind.getEtiqueta()).setAutoWidth(true);
        }
        estadoGrid.setSizeFull();

        Button actualizar = new Button("Actualizar", VaadinIcon.REFRESH.create(), e -> cargarEstado());
        estadoNota.getStyle().set("font-size", "12px").set("color", "#555");
        estadoNota.setText("Ultimo minuto leido de cada maquina. Verde: dentro del limite; amarillo: cerca; rojo: fuera; "
                + "gris: el medidor no lo mide. * = desbalance calculado por la app. Pase el mouse por un valor para ver el limite.");

        VerticalLayout panel = new VerticalLayout(new HorizontalLayout(actualizar), estadoNota, estadoGrid);
        panel.setSizeFull();
        panel.setPadding(false);
        panel.setFlexGrow(1, estadoGrid);
        cargarEstado();
        return panel;
    }

    private void cargarEstado() {
        List<String> maquinas = service.maquinasConDatos();
        Map<String, Fila> mapa = new java.util.HashMap<>();
        for (String m : maquinas) {
            Fila f = service.ultima(m);
            if (f != null) mapa.put(m, f);
        }
        ultimas = mapa;
        estadoGrid.setItems(maquinas);
        if (maquinas.isEmpty()) {
            estadoNota.setText("Todavia no hay datos de calidad: solo se guardan para las maquinas leidas por pasarela.");
        }
    }

    private Component celdaEstado(String maquina, Indicador ind) {
        Fila f = ultimas.get(maquina);
        Double v = f == null ? null : ind.valor(f.valores());
        double nominal = service.tensionNominal(maquina);
        ConfiguracionCalidad cfg = service.configuracion();
        Estado e = service.evaluar(ind, v, nominal, cfg);
        boolean calculado = f != null && ((ind == Indicador.DESBALANCE_CORRIENTE && f.desbICalculado())
                || (ind == Indicador.DESBALANCE_TENSION && f.desbVCalculado()));
        Span s = badge(v == null ? "No disponible" : formato(ind, v) + (calculado ? " *" : ""), e);
        s.setTitle("Limite: " + service.textoLimite(ind, nominal, cfg));
        return s;
    }

    // ================= Historico =================

    private VerticalLayout crearHistorico() {
        ComboBox<String> maquina = new ComboBox<>("Maquina");
        maquina.setItems(service.maquinasConDatos());
        maquina.setWidth("220px");
        ComboBox<Indicador> indicador = new ComboBox<>("Indicador");
        indicador.setItems(Indicador.values());
        indicador.setItemLabelGenerator(Indicador::getEtiqueta);
        indicador.setValue(Indicador.THD_TENSION);
        indicador.setWidth("240px");
        DateTimePicker desde = new DateTimePicker("Desde");
        DateTimePicker hasta = new DateTimePicker("Hasta");
        desde.setValue(LocalDateTime.now().minusDays(1).withSecond(0).withNano(0));
        hasta.setValue(LocalDateTime.now().withSecond(0).withNano(0));

        Button ver = new Button("Ver", VaadinIcon.CHART.create(),
                e -> graficar(maquina.getValue(), indicador.getValue(), desde.getValue(), hasta.getValue()));
        ver.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout filtros = new HorizontalLayout(maquina, indicador, desde, hasta, ver);
        filtros.setAlignItems(Alignment.END);
        filtros.getStyle().set("flex-wrap", "wrap");

        historicoMensaje.getStyle().set("font-size", "12px").set("color", "#555");
        historicoMensaje.setText("Promedios de 10 minutos (como pide la norma), una serie por fase y la linea del limite.");

        Div chart = new Div();
        chart.setId(CHART_ID);
        chart.setWidthFull();
        chart.setHeight("420px");

        VerticalLayout panel = new VerticalLayout(filtros, historicoMensaje, chart);
        panel.setSizeFull();
        panel.setPadding(false);
        return panel;
    }

    private void graficar(String maquina, Indicador ind, LocalDateTime desde, LocalDateTime hasta) {
        if (maquina == null || ind == null || desde == null || hasta == null || !desde.isBefore(hasta)) {
            NotificacionesUtil.mostrarError("Elija maquina, indicador y un rango de fechas valido");
            return;
        }
        if (desde.plusDays(62).isBefore(hasta)) {
            NotificacionesUtil.mostrarError("Elija un rango de hasta 2 meses");
            return;
        }
        List<Bloque> bloques = service.bloques10(service.filas(maquina, desde, hasta));
        List<String> columnas = ind.columnasGrafico();
        ConfiguracionCalidad cfg = service.configuracion();
        double nominal = service.tensionNominal(maquina);
        Double limite = service.lineaLimite(ind, nominal, cfg);

        List<String> nombres = new ArrayList<>(columnas);
        if (limite != null) nombres.add("Limite");
        GraficaModel grafica = new GraficaModel(nombres.size());
        grafica.setSeriesNames(nombres.toArray(new String[0]));
        grafica.setMostrarLeyenda(true);
        grafica.setUnidad(ind.getUnidad());

        List<Long> tiempos = new ArrayList<>();
        List<Float[]> filas = new ArrayList<>();
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (Bloque b : bloques) {
            Float[] fila = new Float[nombres.size()];
            boolean alguno = false;
            for (int i = 0; i < columnas.size(); i++) {
                Double v = b.promedios().get(columnas.get(i));
                if (v != null) {
                    double vv = ind == Indicador.FACTOR_POTENCIA ? Math.abs(v) : v;
                    fila[i] = (float) vv;
                    min = Math.min(min, vv);
                    max = Math.max(max, vv);
                    alguno = true;
                }
            }
            if (!alguno) continue;
            if (limite != null) fila[columnas.size()] = limite.floatValue();
            tiempos.add(b.inicio().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            filas.add(fila);
        }
        if (tiempos.isEmpty()) {
            historicoMensaje.setText("Sin datos de " + ind.getEtiqueta() + " para " + maquina + " en ese rango "
                    + "(el medidor no lo mide o no hubo lecturas).");
            getElement().executeJs(grafica.getInitScript2(CHART_ID));
            return;
        }
        if (limite != null) {
            min = Math.min(min, limite);
            max = Math.max(max, limite);
        }
        double margen = Math.max((max - min) * 0.1, 0.01);
        grafica.setMinY(Math.max(0, min - margen));
        grafica.setMaxY(max + margen);
        historicoMensaje.setText(String.format(Locale.ROOT, "%s - %s: %d bloques de 10 min. Limite: %s",
                maquina, ind.getEtiqueta(), tiempos.size(), service.textoLimite(ind, nominal, cfg)));
        getElement().executeJs(grafica.getInitScript2(CHART_ID)
                + grafica.getSetAllDataScript(CHART_ID, tiempos, filas)
                + grafica.getAplicarZoomInicialScript(CHART_ID));
    }

    // ================= Cumplimiento =================

    private VerticalLayout crearCumplimiento() {
        ComboBox<YearMonth> mes = new ComboBox<>("Mes");
        List<YearMonth> meses = service.mesesConDatos();
        mes.setItems(meses);
        mes.setItemLabelGenerator(m -> m.getMonthValue() + "/" + m.getYear());
        mes.setWidth("160px");

        cumplimientoGrid.addColumn(m -> m).setHeader("Maquina").setAutoWidth(true).setFrozen(true);
        for (Indicador ind : Indicador.values()) {
            cumplimientoGrid.addComponentColumn(m -> celdaCumplimiento(m, ind)).setHeader(ind.getEtiqueta()).setAutoWidth(true);
        }
        cumplimientoGrid.setSizeFull();

        Span nota = new Span("Porcentaje del tiempo dentro del limite (sobre promedios de 10 minutos, como la norma) "
                + "y, entre parentesis, el peor promedio de 10 minutos del mes.");
        nota.getStyle().set("font-size", "12px").set("color", "#555");

        Button calcular = new Button("Calcular", VaadinIcon.CALC.create(), e -> calcularCumplimiento(mes.getValue()));
        calcular.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        HorizontalLayout filtros = new HorizontalLayout(mes, calcular);
        filtros.setAlignItems(Alignment.END);

        VerticalLayout panel = new VerticalLayout(filtros, nota, cumplimientoGrid);
        panel.setSizeFull();
        panel.setPadding(false);
        panel.setFlexGrow(1, cumplimientoGrid);
        if (!meses.isEmpty()) {
            mes.setValue(meses.get(0));
        }
        return panel;
    }

    private void calcularCumplimiento(YearMonth mes) {
        if (mes == null) {
            NotificacionesUtil.mostrarError("Elija un mes");
            return;
        }
        LocalDateTime desde = mes.atDay(1).atStartOfDay();
        LocalDateTime hasta = mes.atEndOfMonth().atTime(23, 59, 59);
        Map<String, Map<Indicador, Cumplimiento>> mapa = new java.util.LinkedHashMap<>();
        for (String m : service.tablas(mes)) {
            mapa.put(m, service.cumplimiento(m, service.filas(m, desde, hasta)));
        }
        cumplimientos = mapa;
        cumplimientoGrid.setItems(new ArrayList<>(mapa.keySet()));
    }

    private Component celdaCumplimiento(String maquina, Indicador ind) {
        Cumplimiento c = cumplimientos.getOrDefault(maquina, Map.of()).get(ind);
        if (c == null || c.bloques() == 0) {
            return badge("No disponible", Estado.SIN_DATO);
        }
        Estado e = c.pctDentro() >= 95 ? Estado.OK : c.pctDentro() >= 80 ? Estado.AVISO : Estado.FUERA;
        Span s = badge(String.format(Locale.ROOT, "%.1f %% (%s)", c.pctDentro(), formato(ind, c.peor())), e);
        s.setTitle(c.bloques() + " bloques de 10 min. Limite: "
                + service.textoLimite(ind, service.tensionNominal(maquina), service.configuracion()));
        return s;
    }

    // ================= Limites (ADMIN) =================

    private VerticalLayout crearLimites() {
        ConfiguracionCalidad cfg = service.configuracion();
        FormLayout form = new FormLayout();
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("500px", 3));
        List<Runnable> guardar = new ArrayList<>();
        campo(form, guardar, "THD tension hasta 1 kV (%)", cfg, ConfiguracionCalidad::getThdTensionBajaTension, ConfiguracionCalidad::setThdTensionBajaTension);
        campo(form, guardar, "THD tension 1 a 69 kV (%)", cfg, ConfiguracionCalidad::getThdTensionMediaTension, ConfiguracionCalidad::setThdTensionMediaTension);
        campo(form, guardar, "THD corriente (%)", cfg, ConfiguracionCalidad::getThdCorriente, ConfiguracionCalidad::setThdCorriente);
        campo(form, guardar, "Desbalance tension (%)", cfg, ConfiguracionCalidad::getDesbalanceTension, ConfiguracionCalidad::setDesbalanceTension);
        campo(form, guardar, "Desbalance corriente (%)", cfg, ConfiguracionCalidad::getDesbalanceCorriente, ConfiguracionCalidad::setDesbalanceCorriente);
        campo(form, guardar, "PF minimo", cfg, ConfiguracionCalidad::getPfMinimo, ConfiguracionCalidad::setPfMinimo);
        campo(form, guardar, "Tension: aviso (+/- %)", cfg, ConfiguracionCalidad::getTensionAvisoPct, ConfiguracionCalidad::setTensionAvisoPct);
        campo(form, guardar, "Tension: fuera (+/- %)", cfg, ConfiguracionCalidad::getTensionFueraPct, ConfiguracionCalidad::setTensionFueraPct);
        campo(form, guardar, "Tension nominal por defecto (V fase-fase)", cfg, ConfiguracionCalidad::getTensionNominalPorDefecto, ConfiguracionCalidad::setTensionNominalPorDefecto);
        campo(form, guardar, "Frecuencia nominal (Hz)", cfg, ConfiguracionCalidad::getFrecuenciaNominal, ConfiguracionCalidad::setFrecuenciaNominal);
        campo(form, guardar, "Frecuencia (+/- %)", cfg, ConfiguracionCalidad::getFrecuenciaPct, ConfiguracionCalidad::setFrecuenciaPct);

        Button guardarBtn = new Button("Guardar limites", e -> {
            guardar.forEach(Runnable::run);
            service.guardarConfiguracion(cfg);
            exito("Limites guardados");
            cargarEstado();
        });
        guardarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Grid<String> nominales = new Grid<>();
        nominales.addColumn(m -> m).setHeader("Maquina").setAutoWidth(true);
        nominales.addColumn(m -> String.format(Locale.ROOT, "%.0f V", service.tensionNominal(m))
                + (service.tieneNominalPropia(m) ? "" : " (por defecto)")).setHeader("Tension nominal fase-fase").setAutoWidth(true);
        List<String> maquinas = configLoaderService.loadLineaIDConfig().stream()
                .map(l -> String.valueOf(l.get("lineaMaquina"))).sorted().toList();
        nominales.setItems(maquinas);
        nominales.addItemClickListener(e -> editarNominal(e.getItem(), nominales));
        nominales.setHeight("320px");

        Span nota = new Span("Clic en una maquina para cargar la tension nominal de su tablero (ej. 460, 480, 22000).");
        nota.getStyle().set("font-size", "12px").set("color", "#555");
        VerticalLayout panel = new VerticalLayout(form, guardarBtn, nota, nominales);
        panel.setPadding(false);
        return panel;
    }

    private void campo(FormLayout form, List<Runnable> guardar, String etiqueta, ConfiguracionCalidad cfg,
                       ToDoubleFunction<ConfiguracionCalidad> get, ObjDoubleConsumer<ConfiguracionCalidad> set) {
        NumberField f = new NumberField(etiqueta);
        f.setValue(get.applyAsDouble(cfg));
        form.add(f);
        guardar.add(() -> {
            if (f.getValue() != null) set.accept(cfg, f.getValue());
        });
    }

    private void editarNominal(String maquina, Grid<String> grid) {
        Dialog d = new Dialog();
        d.setHeaderTitle("Tension nominal de " + maquina);
        NumberField v = new NumberField("Voltios fase-fase");
        v.setValue(service.tensionNominal(maquina));
        d.add(v);
        Button cancelar = new Button("Cancelar", e -> d.close());
        Button ok = new Button("Guardar", e -> {
            if (v.getValue() == null || v.getValue() <= 0) {
                NotificacionesUtil.mostrarError("Ingrese una tension mayor que 0");
                return;
            }
            service.guardarTensionNominal(maquina, v.getValue());
            d.close();
            grid.getDataProvider().refreshAll();
            exito("Tension nominal guardada");
            cargarEstado();
        });
        ok.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        d.getFooter().add(cancelar, ok);
        d.open();
    }

    // ================= Utilidades =================

    private static String formato(Indicador ind, Double v) {
        if (v == null) return "-";
        return switch (ind) {
            case FACTOR_POTENCIA -> String.format(Locale.ROOT, "%.3f", v);
            case TENSION -> String.format(Locale.ROOT, "%.1f V", v);
            case FRECUENCIA -> String.format(Locale.ROOT, "%.2f Hz", v);
            default -> String.format(Locale.ROOT, "%.2f %%", v);
        };
    }

    private static Span badge(String texto, Estado e) {
        String[] c = switch (e) {
            case OK -> new String[]{"#d4edda", "#155724"};
            case AVISO -> new String[]{"#fff3cd", "#856404"};
            case FUERA -> new String[]{"#f8d7da", "#721c24"};
            case SIN_DATO -> new String[]{"#e2e3e5", "#383d41"};
        };
        Span s = new Span(texto);
        s.getStyle().set("background-color", c[0]).set("color", c[1]).set("padding", "2px 8px")
                .set("border-radius", "10px").set("font-weight", "600").set("font-size", "12px")
                .set("white-space", "nowrap");
        return s;
    }

    private static void exito(String mensaje) {
        Notification.show(mensaje, 2500, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }
}
