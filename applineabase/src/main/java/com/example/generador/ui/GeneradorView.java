package com.example.generador.ui;

import com.example.base.model.GraficaModel;
import com.example.base.ui.ChartsView;
import com.example.base.ui.MainLayout;
import com.example.calidad.model.ConfiguracionCalidad;
import com.example.calidad.model.Indicador;
import com.example.calidad.service.CalidadEnergiaService;
import com.example.calidad.service.CalidadEnergiaService.Estado;
import com.example.dataacquisition.FactorPotenciaUtil;
import com.example.generador.model.Generador;
import com.example.generador.model.LecturaGenerador;
import com.example.generador.model.ModeloControlador;
import com.example.generador.model.ParametroGenerador;
import com.example.generador.service.GeneradorService;
import com.example.generador.service.GeneradorService.Arranque;
import com.example.generador.service.GeneradorService.FilaRegistro;
import com.example.generador.service.GeneradorService.Punto;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
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
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import jakarta.annotation.security.PermitAll;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Generadores (fase G): estado actual leído en vivo del controlador (SOLO LECTURA) con el mapa de
 * su modelo, la red asociada (medidor del transformador, del histórico del PLC), historial de
 * arranques, tendencias y, para ADMIN, exploración de registros para verificar un mapa nuevo.
 * Semáforo de tensión/frecuencia/PF con los límites de Calidad de Energía; los del motor son
 * provisorios hasta la sesión de alarmas (G3).
 */
@PageTitle("Generadores | LineaBase")
@Route(value = "generador", layout = MainLayout.class)
@PermitAll
public class GeneradorView extends VerticalLayout implements BeforeEnterObserver {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private static final DateTimeFormatter CORTA = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final String CHART_ID = "chartdiv_generador";
    private static final int POLL_MS = 15000;
    private static final int POLL_MS_APP = 30000;

    // Umbrales provisorios del motor (sistema de 24 V); se ajustan en la sesión de alarmas (G3).
    private static final double BAT_PARADO_AVISO = 25.0, BAT_PARADO_FUERA = 24.0;
    private static final double BAT_MARCHA_MIN = 26.0, BAT_MARCHA_MAX = 29.5;
    private static final double ACEITE_AVISO = 2.0, ACEITE_FUERA = 1.5;
    private static final double TEMP_AVISO = 90.0, TEMP_FUERA = 98.0;

    private final GeneradorService service;
    private final CalidadEnergiaService calidad;
    private final LineaAccessService lineaAccessService;
    private Generador generador;

    private final Span descripcion = new Span();
    private final Span avisoMapa = new Span();
    private final Span estadoBadge = new Span();
    private final Span estadoHora = new Span();
    private final Span erroresLectura = new Span();
    private final VerticalLayout panelRed = panel();
    private final VerticalLayout panelGen = panel();
    private final VerticalLayout panelMotor = panel();
    private final Grid<Arranque> arranquesGrid = new Grid<>();
    private final Span tendenciaMensaje = new Span();
    private final Grid<FilaRegistro> registrosGrid = new Grid<>();
    private final Span registrosMensaje = new Span();
    private List<FilaRegistro> ultimaExploracion = List.of();
    private Map<Integer, Integer> exploracionAnterior = Map.of();
    private Registration pollRegistration;

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

    public GeneradorView(GeneradorService service, CalidadEnergiaService calidad, LineaAccessService lineaAccessService) {
        this.service = service;
        this.calidad = calidad;
        this.lineaAccessService = lineaAccessService;
        List<Generador> lista = service.generadores();
        this.generador = lista.isEmpty() ? null : lista.get(0);

        setSizeFull();
        setPadding(true);
        setSpacing(true);
        if (generador == null) {
            add(new H3("Generadores"), new Span("No hay generadores en C:\\LineaBaseX\\config\\generador-config.json"));
            return;
        }

        ComboBox<Generador> selector = new ComboBox<>();
        selector.setItems(lista);
        selector.setItemLabelGenerator(Generador::nombre);
        selector.setValue(generador);
        selector.setWidth("220px");
        selector.addValueChangeListener(e -> {
            if (e.getValue() != null && e.isFromClient()) {
                generador = e.getValue();
                cambiarGenerador();
            }
        });
        H3 titulo = new H3("Generador");
        titulo.getStyle().set("margin", "0");
        descripcion.getStyle().set("font-size", "12px").set("color", "#555");
        HorizontalLayout cabecera = new HorizontalLayout(titulo, selector, descripcion);
        cabecera.setAlignItems(Alignment.CENTER);
        avisoMapa.getStyle().set("background-color", "#fff3cd").set("color", "#856404").set("padding", "6px 10px")
                .set("border-radius", "6px").set("font-size", "13px");

        TabSheet tabs = new TabSheet();
        tabs.setSizeFull();
        tabs.add("Estado actual", crearEstado());
        tabs.add("Arranques", crearArranques());
        tabs.add("Tendencias", crearTendencias());
        if (lineaAccessService.esAdmin()) {
            tabs.add("Registros", crearRegistros());
        }
        add(cabecera, avisoMapa, tabs);
        setFlexGrow(1, tabs);
        cambiarGenerador();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!lineaAccessService.puedeVerAlarmas()) {
            Notification.show("No tienes permiso para ver el generador", 3000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            event.forwardTo(ChartsView.class);
        }
    }

    @Override
    protected void onAttach(AttachEvent e) {
        UI ui = e.getUI();
        ui.setPollInterval(POLL_MS);
        pollRegistration = ui.addPollListener(p -> refrescar());
    }

    @Override
    protected void onDetach(DetachEvent e) {
        if (pollRegistration != null) {
            pollRegistration.remove();
        }
        e.getUI().setPollInterval(POLL_MS_APP);
    }

    private void cambiarGenerador() {
        descripcion.setText(generador.descripcion() + " IP " + generador.ip() + ", modelo " + generador.modelo()
                + (generador.redAsociada() == null ? "" : ", red " + generador.redAsociada()) + ".");
        ModeloControlador m = service.modelo(generador).orElse(null);
        if (m == null) {
            avisoMapa.setText("El modelo " + generador.modelo() + " no esta en C:\\LineaBaseX\\config\\generador-modelos.json: no se lee.");
            avisoMapa.setVisible(true);
        } else if (!m.confirmado()) {
            avisoMapa.setText("Mapa de registros de " + m.modelo() + " sin confirmar: los valores pueden no corresponder. "
                    + "No se guarda historial ni arranques hasta confirmarlo comparando con la pantalla del controlador.");
            avisoMapa.setVisible(true);
        } else {
            avisoMapa.setVisible(false);
        }
        registrosGrid.setItems(List.of());
        ultimaExploracion = List.of();
        exploracionAnterior = Map.of();
        registrosMensaje.setText("");
        tendenciaMensaje.setText("Lo guardado: cada minuto en marcha, cada 15 min parado. La bateria y la temperatura parado "
                + "muestran si el generador esta listo para arrancar.");
        refrescar();
    }

    // ================= Estado actual =================

    private VerticalLayout crearEstado() {
        Button actualizar = new Button("Actualizar", VaadinIcon.REFRESH.create(), e -> refrescar());
        estadoHora.getStyle().set("font-size", "12px").set("color", "#555");
        HorizontalLayout barra = new HorizontalLayout(estadoBadge, estadoHora, actualizar);
        barra.setAlignItems(Alignment.CENTER);

        HorizontalLayout paneles = new HorizontalLayout(panelRed, panelGen, panelMotor);
        paneles.setWidthFull();
        paneles.getStyle().set("flex-wrap", "wrap");

        erroresLectura.getStyle().set("font-size", "12px").set("color", "#721c24");
        Span nota = new Span("Leido en vivo del controlador cada 15 s (solo lectura). La red es el medidor del transformador "
                + "asociado (PLC, ultimo minuto); potencia negativa = retorno de energia a la red. "
                + "Tension, frecuencia y PF usan los limites de Calidad de Energia; bateria, aceite y temperatura tienen "
                + "limites provisorios (sistema de 24 V) hasta configurar las alarmas.");
        nota.getStyle().set("font-size", "12px").set("color", "#555");
        VerticalLayout v = new VerticalLayout(barra, paneles, erroresLectura, nota);
        v.setPadding(false);
        return v;
    }

    private void refrescar() {
        if (generador == null) return;
        arranquesGrid.setItems(service.arranques(generador));
        LecturaGenerador l;
        try {
            l = service.leerEnVivo(generador);
        } catch (Exception ex) {
            badge(estadoBadge, "SIN COMUNICACION", Estado.FUERA);
            estadoHora.setText(ex.getMessage());
            erroresLectura.setText("");
            panelRed.removeAll();
            panelGen.removeAll();
            panelMotor.removeAll();
            return;
        }
        boolean marcha = l.enMarcha();
        badge(estadoBadge, l.rpm() == null ? "SIN RPM" : marcha ? "EN MARCHA" : "PARADO",
                l.rpm() == null ? Estado.AVISO : marcha ? Estado.OK : Estado.SIN_DATO);
        estadoHora.setText("Leido " + l.fecha().format(HORA));
        erroresLectura.setText(l.errores().isEmpty() ? "" : "Sin respuesta del controlador en: "
                + l.errores().entrySet().stream().map(e -> e.getKey().etiqueta() + " (" + e.getValue() + ")")
                .collect(Collectors.joining(", ")));
        ConfiguracionCalidad cfg = calidad.configuracion();

        // Red: tensiones y frecuencia del controlador; potencia/corrientes/PF del medidor del transformador (PLC).
        String red = generador.redAsociada();
        panelRed.removeAll();
        panelRed.add(titulo("Red (" + (red == null ? "-" : red) + ")"));
        double nomRed = calidad.tensionNominal(red == null ? "" : red);
        panelRed.add(fila("Frecuencia", valor(l.redFrecuencia(), "%.1f Hz",
                calidad.evaluar(Indicador.FRECUENCIA, l.redFrecuencia(), nomRed, cfg))));
        panelRed.add(fila("Tension L1-L2 / L2-L3 / L3-L1", tres(l.redVL1L2(), l.redVL2L3(), l.redVL3L1(), "V",
                calidad.evaluar(Indicador.TENSION, prom(l.redVL1L2(), l.redVL2L3(), l.redVL3L1()), nomRed, cfg))));
        if (l.redKw() != null) {
            HorizontalLayout p = new HorizontalLayout(texto(String.format(Locale.ROOT, "%.0f kW", l.redKw())));
            if (l.redKw() < 0) p.add(badgeNuevo("Retorno a la red", Estado.AVISO));
            p.setAlignItems(Alignment.CENTER);
            panelRed.add(fila("Importado de la red (controlador)", p));
        }
        if (l.cargaKw() != null) {
            panelRed.add(fila("Carga del tablero (controlador)", texto(String.format(Locale.ROOT, "%.0f kW", l.cargaKw()))));
        }
        Map<String, Object> medidor = service.ultimaRed(generador);
        if (!medidor.isEmpty()) {
            Double pw = num(medidor.get("PW"));
            Double pf = num(medidor.get("PF")) == null ? null : FactorPotenciaUtil.normalizarAbs(Math.abs(num(medidor.get("PF"))));
            if (pw != null && pw < 0) {
                HorizontalLayout p = new HorizontalLayout(grande(String.format(Locale.ROOT, "%.0f kW", pw)),
                        badgeNuevo("Retorno a la red", Estado.AVISO));
                p.setAlignItems(Alignment.CENTER);
                panelRed.add(fila("Potencia", p));
            } else {
                panelRed.add(fila("Potencia", grande(pw == null ? "-" : String.format(Locale.ROOT, "%.0f kW", pw))));
            }
            panelRed.add(fila("Corriente A / B / C", tres(num(medidor.get("IA")), num(medidor.get("IB")), num(medidor.get("IC")), "A", Estado.SIN_DATO)));
            panelRed.add(fila("PF", valor(pf, "%.3f", calidad.evaluar(Indicador.FACTOR_POTENCIA, pf, nomRed, cfg))));
            panelRed.add(nota("Medidor " + red + " (PLC) " + medidor.getOrDefault("fecha", "")));
        } else if (red != null) {
            panelRed.add(nota("Sin datos recientes del medidor " + red));
        }

        // Generador.
        panelGen.removeAll();
        panelGen.add(titulo("Generador"));
        double nomGen = calidad.tensionNominal(generador.nombre());
        panelGen.add(fila("Potencia", grande(l.kw() == null ? "-" : String.format(Locale.ROOT, "%.0f kW", l.kw()))));
        panelGen.add(fila("kVAr / kVA", texto(fmt(l.kvar(), "%.0f") + " kVAr / " + fmt(l.kva(), "%.0f") + " kVA")));
        panelGen.add(fila("PF", marcha ? valor(l.pf(), "%.2f", calidad.evaluar(Indicador.FACTOR_POTENCIA, l.pf() == null ? null : Math.abs(l.pf()), nomGen, cfg))
                : texto(fmt(l.pf(), "%.2f"))));
        panelGen.add(fila("Frecuencia", marcha ? valor(l.frecuencia(), "%.1f Hz", calidad.evaluar(Indicador.FRECUENCIA, l.frecuencia(), nomGen, cfg))
                : texto(fmt(l.frecuencia(), "%.1f") + " Hz")));
        panelGen.add(fila("RPM", texto(fmt(l.rpm(), "%.0f"))));
        panelGen.add(fila("Tension L-N", tres(l.vL1N(), l.vL2N(), l.vL3N(), "V", Estado.SIN_DATO)));
        panelGen.add(fila("Tension L-L", marcha ? tres(l.vL1L2(), l.vL2L3(), l.vL3L1(), "V",
                calidad.evaluar(Indicador.TENSION, prom(l.vL1L2(), l.vL2L3(), l.vL3L1()), nomGen, cfg))
                : tres(l.vL1L2(), l.vL2L3(), l.vL3L1(), "V", Estado.SIN_DATO)));
        panelGen.add(fila("Corriente L1 / L2 / L3", tres(l.iL1(), l.iL2(), l.iL3(), "A", Estado.SIN_DATO)));
        if (marcha && l.kw() != null && l.redVL1L2() != null) {
            boolean conRed = prom(l.redVL1L2(), l.redVL2L3(), l.redVL3L1()) != null
                    && prom(l.redVL1L2(), l.redVL2L3(), l.redVL3L1()) >= com.example.generador.service.GeneradorAnalisisService.V_RED_PRESENTE;
            String modo = l.kw() < com.example.generador.service.GeneradorAnalisisService.KW_VACIO ? "En vacio"
                    : conRed ? "En paralelo con la red" : "En isla (sin red)";
            panelGen.add(fila("Modo", texto(modo)));
        }

        // Motor.
        panelMotor.removeAll();
        panelMotor.add(titulo("Motor"));
        ModeloControlador modelo = service.modelo(generador).orElse(null);
        panelMotor.add(fila("Bateria", valor(l.bateria(), "%.1f V", estadoBateria(l.bateria(), marcha))));
        if (modelo != null && modelo.registro(ParametroGenerador.PRESION_ACEITE).isEmpty()) {
            panelMotor.add(fila("Presion de aceite", nota("no lo mide este controlador")));
        } else {
            panelMotor.add(fila("Presion de aceite", marcha ? valor(l.presionAceite(), "%.1f bar", estadoAceite(l.presionAceite()))
                    : texto(fmt(l.presionAceite(), "%.1f") + " bar")));
        }
        if (modelo != null && modelo.registro(ParametroGenerador.TEMP_REFRIGERANTE).isEmpty()) {
            panelMotor.add(fila("Temperatura refrigerante", nota("no lo mide este controlador")));
        } else {
            panelMotor.add(fila("Temperatura refrigerante", marcha ? valor(l.tempRefrigerante(), "%.0f C", estadoTemp(l.tempRefrigerante()))
                    : texto(fmt(l.tempRefrigerante(), "%.0f") + " C")));
        }
        if (l.tempAceite() != null) panelMotor.add(fila("Temperatura de aceite", texto(fmt(l.tempAceite(), "%.0f") + " C")));
        if (l.nivelCombustible() != null) panelMotor.add(fila("Nivel de combustible", texto(fmt(l.nivelCombustible(), "%.0f") + " %")));
        if (l.consumoCombustible() != null) panelMotor.add(fila("Consumo de combustible", texto(fmt(l.consumoCombustible(), "%.1f") + " L/h")));
        panelMotor.add(fila("Horas de marcha", texto(fmt(l.horasMarcha(), "%.1f") + " h")));
        panelMotor.add(fila("Arranques", texto(l.arranques() == null ? "-" : String.valueOf(l.arranques()))));
        panelMotor.add(fila("Energia generada", texto(l.kwh() == null ? "-" : String.format(Locale.ROOT, "%,d kWh", l.kwh()))));
        panelMotor.add(fila("Energia reactiva", texto(l.kvarh() == null ? "-" : String.format(Locale.ROOT, "%,d kVArh", l.kvarh()))));
    }

    private static Estado estadoBateria(Double v, boolean marcha) {
        if (v == null) return Estado.SIN_DATO;
        if (marcha) return v < BAT_MARCHA_MIN || v > BAT_MARCHA_MAX ? Estado.AVISO : Estado.OK;
        return v < BAT_PARADO_FUERA ? Estado.FUERA : v < BAT_PARADO_AVISO ? Estado.AVISO : Estado.OK;
    }

    private static Estado estadoAceite(Double v) {
        if (v == null) return Estado.SIN_DATO;
        return v < ACEITE_FUERA ? Estado.FUERA : v < ACEITE_AVISO ? Estado.AVISO : Estado.OK;
    }

    private static Estado estadoTemp(Double v) {
        if (v == null) return Estado.SIN_DATO;
        return v > TEMP_FUERA ? Estado.FUERA : v > TEMP_AVISO ? Estado.AVISO : Estado.OK;
    }

    // ================= Arranques =================

    private VerticalLayout crearArranques() {
        arranquesGrid.addColumn(a -> a.arranqueNro() == null ? "-" : String.valueOf(a.arranqueNro())).setHeader("Nro").setAutoWidth(true);
        arranquesGrid.addColumn(a -> a.inicio().format(CORTA) + (a.inicioEstimado() ? " (aprox.)" : "")).setHeader("Inicio").setAutoWidth(true);
        arranquesGrid.addComponentColumn(a -> a.fin() == null ? badgeNuevo("En marcha", Estado.OK) : new Span(a.fin().format(CORTA)))
                .setHeader("Fin").setAutoWidth(true);
        arranquesGrid.addColumn(a -> a.duracionMin() == null ? "-" : String.format(Locale.ROOT, "%.0f min", a.duracionMin()))
                .setHeader("Duracion").setAutoWidth(true);
        arranquesGrid.addColumn(a -> a.kwhGenerados() == null ? "-" : a.kwhGenerados() + " kWh").setHeader("Energia generada").setAutoWidth(true);
        arranquesGrid.addColumn(a -> a.kwMax() == null ? "-" : String.format(Locale.ROOT, "%.0f kW", a.kwMax())).setHeader("Carga maxima").setAutoWidth(true);
        arranquesGrid.addColumn(a -> fmt(a.horasInicio(), "%.1f") + " -> " + fmt(a.horasFin(), "%.1f") + " h").setHeader("Horometro").setAutoWidth(true);
        arranquesGrid.setSizeFull();
        Span nota = nota("Cada arranque que detecta la app (RPM > 0). \"aprox.\" = la app arranco con el generador ya en marcha.");
        VerticalLayout v = new VerticalLayout(nota, arranquesGrid);
        v.setSizeFull();
        v.setPadding(false);
        v.setFlexGrow(1, arranquesGrid);
        return v;
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
        Button ver = new Button("Ver", VaadinIcon.CHART.create(), e -> graficar(variable.getValue(), rango.getValue()));
        ver.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        HorizontalLayout filtros = new HorizontalLayout(variable, rango, ver);
        filtros.setAlignItems(Alignment.END);

        tendenciaMensaje.getStyle().set("font-size", "12px").set("color", "#555");
        Div chart = new Div();
        chart.setId(CHART_ID);
        chart.setWidthFull();
        chart.setHeight("420px");
        VerticalLayout v = new VerticalLayout(filtros, tendenciaMensaje, chart);
        v.setSizeFull();
        v.setPadding(false);
        return v;
    }

    private void graficar(String variable, Integer dias) {
        if (variable == null || dias == null) return;
        String[] columnas = VARIABLES.get(variable);
        LocalDateTime hasta = LocalDateTime.now();
        LocalDateTime desde = hasta.minusDays(dias);
        List<List<Punto>> series = new ArrayList<>();
        for (String col : columnas) series.add(service.serie(generador, col, desde, hasta));

        java.util.TreeMap<LocalDateTime, Float[]> filas = new java.util.TreeMap<>();
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int s = 0; s < series.size(); s++) {
            for (Punto p : series.get(s)) {
                if (p.valor() == null) continue;
                filas.computeIfAbsent(p.fecha(), k -> new Float[columnas.length])[s] = p.valor().floatValue();
                min = Math.min(min, p.valor());
                max = Math.max(max, p.valor());
            }
        }
        GraficaModel g = new GraficaModel(columnas.length);
        g.setSeriesNames(columnas.length == 1 ? new String[]{variable} : columnas);
        g.setMostrarLeyenda(columnas.length > 1);
        if (filas.isEmpty()) {
            tendenciaMensaje.setText("Sin datos de " + variable + " en el periodo.");
            getElement().executeJs(g.getInitScript2(CHART_ID));
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
        tendenciaMensaje.setText(variable + ": " + filas.size() + " lecturas.");
        getElement().executeJs(g.getInitScript2(CHART_ID) + g.getSetAllDataScript(CHART_ID, t, v)
                + g.getAplicarZoomInicialScript(CHART_ID));
    }

    // ================= Registros (ADMIN) =================

    private VerticalLayout crearRegistros() {
        IntegerField desde = new IntegerField("Desde registro");
        desde.setMin(40001);
        desde.setMax(105536);
        desde.setValue(41001);
        desde.setWidth("150px");
        IntegerField cantidad = new IntegerField("Cantidad");
        cantidad.setMin(1);
        cantidad.setMax(300);
        cantidad.setValue(100);
        cantidad.setWidth("110px");
        TextField comentario = new TextField("Estado del generador en la foto");
        comentario.setPlaceholder("parado / en marcha sin carga / con carga");
        comentario.setWidth("300px");
        Button guardar = new Button("Guardar foto", VaadinIcon.CAMERA.create());
        guardar.setEnabled(false);
        Button leer = new Button("Leer (solo lectura)", VaadinIcon.DOWNLOAD_ALT.create(), e -> {
            Integer d = desde.getValue(), n = cantidad.getValue();
            if (d == null || n == null || d < 40001 || n < 1 || n > 300) {
                registrosMensaje.setText("Registro desde 40001 y cantidad entre 1 y 300.");
                return;
            }
            try {
                long t0 = System.currentTimeMillis();
                Map<Integer, Integer> previo = new HashMap<>();
                for (FilaRegistro f : ultimaExploracion) previo.put(f.celda().registro(), f.celda().valor());
                exploracionAnterior = previo;
                ultimaExploracion = service.explorar(generador, d, n);
                registrosGrid.setItems(ultimaExploracion);
                long sinDato = ultimaExploracion.stream().filter(f -> f.celda().valor() == null).count();
                long cambiaron = ultimaExploracion.stream().filter(this::cambio).count();
                registrosMensaje.setText("Leidos " + (n - sinDato) + " de " + n + " registros en "
                        + (System.currentTimeMillis() - t0) + " ms" + (sinDato > 0 ? "; " + sinDato + " no existen en el controlador" : "")
                        + (exploracionAnterior.isEmpty() ? "" : "; " + cambiaron + " cambiaron desde la lectura anterior (amarillo)") + ".");
                guardar.setEnabled(true);
            } catch (Exception ex) {
                registrosMensaje.setText("Sin comunicacion con " + generador.ip() + ": " + ex.getMessage());
            }
        });
        leer.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        guardar.addClickListener(e -> {
            try {
                Path p = service.guardarFoto(generador, ultimaExploracion, comentario.getValue());
                Notification.show("Foto guardada en " + p, 5000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } catch (Exception ex) {
                Notification.show("No se pudo guardar la foto: " + ex.getMessage(), 5000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        HorizontalLayout filtros = new HorizontalLayout(desde, cantidad, leer, comentario, guardar);
        filtros.setAlignItems(Alignment.END);
        filtros.getStyle().set("flex-wrap", "wrap");

        registrosGrid.addColumn(f -> String.valueOf(f.celda().registro())).setHeader("Registro").setAutoWidth(true).setFlexGrow(0);
        registrosGrid.addColumn(f -> f.celda().valor() == null ? "-" : String.valueOf(f.celda().valor())).setHeader("Sin signo").setAutoWidth(true).setFlexGrow(0);
        registrosGrid.addColumn(f -> f.celda().valor() == null ? "-" : String.valueOf((short) (int) f.celda().valor())).setHeader("Con signo").setAutoWidth(true).setFlexGrow(0);
        registrosGrid.addColumn(f -> f.celda().valor() == null ? "-" : String.format("%04X", f.celda().valor())).setHeader("Hex").setAutoWidth(true).setFlexGrow(0);
        registrosGrid.addColumn(this::treintaYDos).setHeader("32 bits (con el siguiente)").setAutoWidth(true).setFlexGrow(0);
        registrosGrid.addColumn(f -> f.celda().nota() == null ? "" : f.celda().nota()).setHeader("Nota").setAutoWidth(true);
        registrosGrid.addColumn(FilaRegistro::parametro).setHeader("Parametro segun el mapa").setAutoWidth(true);
        registrosGrid.addColumn(FilaRegistro::valorMapa).setHeader("Valor segun el mapa").setAutoWidth(true);
        registrosGrid.addComponentColumn(f -> {
            if (!cambio(f)) return new Span();
            Integer antes = exploracionAnterior.get(f.celda().registro());
            return badgeNuevo("antes " + (antes == null ? "-" : antes), Estado.AVISO);
        }).setHeader("Cambio").setAutoWidth(true);
        registrosGrid.setSizeFull();

        registrosMensaje.getStyle().set("font-size", "12px").set("color", "#555");
        Span nota = nota("Solo lectura (funcion 03). Sirve para verificar o armar el mapa de un controlador nuevo: leer con el "
                + "generador parado, en marcha y con carga, comparar con la pantalla del controlador y guardar una foto de cada estado "
                + "(C:\\LineaBaseX\\generador\\fotos). El mapa se edita en C:\\LineaBaseX\\config\\generador-modelos.json, sin recompilar.");
        VerticalLayout v = new VerticalLayout(filtros, registrosMensaje, nota, registrosGrid);
        v.setSizeFull();
        v.setPadding(false);
        v.setFlexGrow(1, registrosGrid);
        return v;
    }

    private boolean cambio(FilaRegistro f) {
        return !exploracionAnterior.isEmpty() && exploracionAnterior.containsKey(f.celda().registro())
                && !Objects.equals(exploracionAnterior.get(f.celda().registro()), f.celda().valor());
    }

    private String treintaYDos(FilaRegistro f) {
        int i = ultimaExploracion.indexOf(f);
        if (i < 0 || i + 1 >= ultimaExploracion.size()) return "-";
        Integer a = f.celda().valor(), b = ultimaExploracion.get(i + 1).celda().valor();
        if (a == null || b == null) return "-";
        long u = ((long) (a & 0xFFFF) << 16) | (b & 0xFFFF);
        return String.valueOf(u);
    }

    // ================= Utilidades =================

    private static VerticalLayout panel() {
        VerticalLayout p = new VerticalLayout();
        p.setSpacing(false);
        p.setWidth("360px");
        p.getStyle().set("border", "1px solid #ddd").set("border-radius", "8px").set("padding", "10px 14px");
        return p;
    }

    private static H4 titulo(String t) {
        H4 h = new H4(t);
        h.getStyle().set("margin", "0 0 6px 0");
        return h;
    }

    private static HorizontalLayout fila(String etiqueta, Component valor) {
        Span e = new Span(etiqueta);
        e.getStyle().set("color", "#555").set("font-size", "13px");
        HorizontalLayout h = new HorizontalLayout(e, valor);
        h.setWidthFull();
        h.setJustifyContentMode(JustifyContentMode.BETWEEN);
        h.setAlignItems(Alignment.CENTER);
        return h;
    }

    private static Span texto(String t) {
        Span s = new Span(t);
        s.getStyle().set("font-weight", "600");
        return s;
    }

    private static Span grande(String t) {
        Span s = new Span(t);
        s.getStyle().set("font-weight", "700").set("font-size", "22px");
        return s;
    }

    private static Span nota(String t) {
        Span s = new Span(t);
        s.getStyle().set("font-size", "11px").set("color", "#777");
        return s;
    }

    private static Span valor(Double v, String formato, Estado e) {
        return badgeNuevo(v == null ? "-" : String.format(Locale.ROOT, formato, v), v == null ? Estado.SIN_DATO : e);
    }

    private static Span tres(Double a, Double b, Double c, String unidad, Estado e) {
        String t = fmt(a, "%.0f") + " / " + fmt(b, "%.0f") + " / " + fmt(c, "%.0f") + " " + unidad;
        return e == Estado.SIN_DATO ? texto(t) : badgeNuevo(t, e);
    }

    private static Double prom(Double a, Double b, Double c) {
        return a == null || b == null || c == null ? null : (a + b + c) / 3.0;
    }

    private static String fmt(Double v, String f) {
        return v == null ? "-" : String.format(Locale.ROOT, f, v);
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static Span badgeNuevo(String texto, Estado e) {
        Span s = new Span();
        badge(s, texto, e);
        return s;
    }

    static void badge(Span s, String texto, Estado e) {
        String[] c = switch (e) {
            case OK -> new String[]{"#d4edda", "#155724"};
            case AVISO -> new String[]{"#fff3cd", "#856404"};
            case FUERA -> new String[]{"#f8d7da", "#721c24"};
            case SIN_DATO -> new String[]{"#e2e3e5", "#383d41"};
        };
        s.setText(texto);
        s.getStyle().set("background-color", c[0]).set("color", c[1]).set("padding", "2px 8px")
                .set("border-radius", "10px").set("font-weight", "600").set("font-size", "13px").set("white-space", "nowrap");
    }
}
