package com.example.generador.ui;

import com.example.base.ui.ChartsView;
import com.example.base.ui.MainLayout;
import com.example.calidad.model.ConfiguracionCalidad;
import com.example.calidad.model.Indicador;
import com.example.calidad.service.CalidadEnergiaService;
import com.example.calidad.service.CalidadEnergiaService.Estado;
import com.example.dataacquisition.FactorPotenciaUtil;
import com.example.dataacquisition.MaquinasVirtuales;
import com.example.generador.model.EventoGenerador;
import com.example.generador.model.Generador;
import com.example.generador.model.LecturaGenerador;
import com.example.generador.model.MetaImportacionRed;
import com.example.generador.model.TipoEventoGenerador;
import com.example.base.model.GraficaModel;
import com.example.base.ui.CsvUtil;
import com.example.generador.service.AlarmaComap;
import com.example.generador.service.CapturaSegundoService;
import com.example.generador.service.GeneradorAlmacen;
import com.example.generador.service.HistorialEventosService;
import com.example.generador.service.RedControladorService;
import com.example.generador.service.SirenaService;
import com.example.generador.model.SenalesRed;
import com.example.generador.service.PlantaEnVivoService;
import com.example.generador.model.ModeloControlador;
import com.example.generador.model.ParametroGenerador;
import com.example.generador.service.GeneradorService;
import com.example.generador.service.GeneradorService.FilaRegistro;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
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
import com.vaadin.flow.component.tabs.Tab;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Generadores (fase G), lo que pasa ahora: balance energético de la planta 1 en vivo, estado actual leído en vivo del
 * controlador (SOLO LECTURA) con el mapa de su modelo y la red asociada (medidor del transformador, del histórico del
 * PLC), historial de eventos y, para ADMIN, exploración de registros para verificar un mapa nuevo. Lo calculado sobre lo
 * guardado (arranques, tendencias, energía, costos) está en Análisis. Semáforo de tensión/frecuencia/PF con los límites
 * de Calidad de Energía; los del motor son provisorios hasta la sesión de alarmas (G3).
 */
@PageTitle("Generadores | LineaBase")
@Route(value = "generador", layout = MainLayout.class)
@PermitAll
public class GeneradorView extends VerticalLayout implements BeforeEnterObserver {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private static final DateTimeFormatter CORTA = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Dibujo del tablero de Estado actual (se define una sola vez en el navegador) y el JSON de sus datos. */
    private static final String SCRIPT_TABLERO = leerRecurso("/tablero/tablero-generadores.js");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
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
    private final PlantaEnVivoService plantaService;
    private final RedControladorService redControlador;
    private final GeneradorAlmacen almacen;
    private final CapturaSegundoService capturas;
    /** Generadores en el orden de las pestañas: Transformador 1 primero. */
    private List<Generador> generadores = List.of();
    /** Generador de la pestaña elegida (el último elegido si la pestaña es otra sección). */
    private Generador generador;
    private final TabSheet tabs = new TabSheet();
    private Tab tabPlanta;
    private final Div plantaContenido = new Div();
    private final Div plantaDetalle = new Div();
    private final Span plantaHora = new Span();

    private final Span avisoMapa = new Span();
    /** Aviso rojo de alarmas arriba de las pestañas: solo para un generador sin comunicación (su tablero no las muestra). */
    private final Div alertaAlarmas = new Div();
    private final Div avisoRed = new Div();
    /** Una pestaña por generador (su tablero, lo que antes era Estado actual), con sus marcas de marcha y alarmas. */
    private final Map<Tab, Generador> tabGenerador = new LinkedHashMap<>();
    private final Map<Generador, Div> tableros = new HashMap<>();
    private final Map<Generador, Span> marcasTab = new HashMap<>();
    private Tab tabRegistros;
    private Tab tabCapturas;
    private Tab tabSirena;
    private final SirenaService sirena;
    /** Generador de cada fila del Historial de eventos (sus registros no lo traen). */
    private final Map<EventoGenerador, String> generadorDeEvento = new IdentityHashMap<>();
    private Grid.Column<EventoGenerador> colEventoGenerador;
    /** Registros es de a un controlador: lo elige su propio selector. */
    private final ComboBox<Generador> registrosGenerador = new ComboBox<>("Generador");
    private final Grid<FilaRegistro> registrosGrid = new Grid<>();
    private final HistorialEventosService historial;
    private final Grid<EventoGenerador> eventosGrid = new Grid<>();
    private final ComboBox<Integer> eventosPeriodo = new ComboBox<>("Periodo");
    private final ComboBox<TipoEventoGenerador> eventosTipo = new ComboBox<>("Evento");
    private final Span eventosMensaje = new Span();
    private List<EventoGenerador> eventos = List.of();
    /** Id del último evento cargado en la grilla: si el historial tiene uno nuevo, se recarga sola. */
    private long ultimoEventoMostrado;
    private final Span registrosMensaje = new Span();
    private List<FilaRegistro> ultimaExploracion = List.of();
    private Map<Integer, Integer> exploracionAnterior = Map.of();
    private Registration pollRegistration;

    public GeneradorView(GeneradorService service, CalidadEnergiaService calidad, LineaAccessService lineaAccessService,
                         HistorialEventosService historial, PlantaEnVivoService plantaService,
                         RedControladorService redControlador, GeneradorAlmacen almacen, CapturaSegundoService capturas,
                         SirenaService sirena) {
        this.sirena = sirena;
        this.capturas = capturas;
        this.service = service;
        this.calidad = calidad;
        this.lineaAccessService = lineaAccessService;
        this.historial = historial;
        this.plantaService = plantaService;
        this.redControlador = redControlador;
        this.almacen = almacen;
        generadores = service.generadores().stream()
                .sorted(java.util.Comparator.comparing((Generador x) -> nombreRed(x.redAsociada()))).toList();
        this.generador = generadores.isEmpty() ? null : generadores.get(0);

        setSizeFull();
        setPadding(true);
        setSpacing(true);
        if (generador == null) {
            add(new H3("Generadores"), new Span("No hay generadores en C:\\LineaBaseX\\config\\generador-config.json"));
            return;
        }
        avisoMapa.getStyle().set("background-color", "#fff3cd").set("color", "#856404").set("padding", "6px 10px")
                .set("border-radius", "6px").set("font-size", "13px");

        // Sin selector: una pestaña por generador (su tablero), después lo de todos los generadores juntos.
        tabs.setSizeFull();
        for (Generador g : generadores) {
            Tab t = tabs.add(pestanaGenerador(g), crearEstado(g));
            tabGenerador.put(t, g);
        }
        tabPlanta = tabs.add("Balance energético P1", crearPlanta());
        tabPlanta.getStyle().set("margin-left", "14px");
        tabs.add("Historial de eventos", crearEventos());
        if (lineaAccessService.esAdmin()) {
            tabRegistros = tabs.add("Registros", crearRegistros());
            tabCapturas = tabs.add("Capturas al segundo", crearCapturas());
            tabSirena = tabs.add("Sirena", crearSirena());
        }
        tabs.addSelectedChangeListener(e -> {
            Generador g = tabGenerador.get(e.getSelectedTab());
            if (g != null) generador = g;
            if (e.getSelectedTab() == tabPlanta) refrescarPlanta();
            if (tabCapturas != null && e.getSelectedTab() == tabCapturas) cargarCapturas();
            if (tabSirena != null) {
                if (e.getSelectedTab() == tabSirena) {
                    refrescarSirena();
                    // Mientras se ve la pestaña Sirena, el navegador pide el estado cada 2 s: el silencio se anula con una alarma
                    // nueva y el contador tiene que reflejarlo enseguida. Se detiene solo si la vista deja de estar en la página.
                    getElement().executeJs("clearInterval(this._sirenaTick); this._sirenaTick = setInterval(() => {"
                            + " if (!this.isConnected) { clearInterval(this._sirenaTick); return; } this.$server.tickSirena(); }, 2000);");
                } else {
                    getElement().executeJs("clearInterval(this._sirenaTick);");
                }
            }
            refrescar();
        });
        alertaAlarmas.setWidthFull();
        alertaAlarmas.setVisible(false);
        avisoRed.setWidthFull();
        avisoRed.setVisible(false);
        avisoRed.getStyle().set("background-color", "#FDF6DC").set("color", "#7A5E00").set("border", "2px solid #E0B50F")
                .set("border-radius", "6px").set("padding", "8px 12px").set("box-sizing", "border-box");
        add(avisoMapa, avisoRed, alertaAlarmas, tabs);
        setFlexGrow(1, tabs);
        cargarEventos();
        refrescar();
        refrescarPlanta();
    }

    /**
     * Pestaña de un generador: su nombre, debajo su transformador y modelo, y a la derecha un punto verde si está en
     * marcha y la cantidad de alarmas de su controlador en rojo (las actualiza marcarPestanas).
     */
    private Component pestanaGenerador(Generador g) {
        Span nombre = new Span(g.nombre());
        nombre.getStyle().set("display", "block").set("font-size", "16px").set("font-weight", "700");
        Span sub = new Span(nombreRed(g.redAsociada()) + " · " + g.modelo().replaceFirst("(?<=[A-Za-z])(?=\\d)", " "));
        sub.getStyle().set("display", "block").set("font-size", "12px").set("color", "#6b7080").set("font-weight", "400");
        Div textos = new Div(nombre, sub);
        textos.getStyle().set("line-height", "1.25").set("text-align", "left");
        Span marcas = new Span();
        marcas.getStyle().set("display", "inline-flex").set("align-items", "center").set("gap", "8px");
        marcasTab.put(g, marcas);
        Div d = new Div(textos, marcas);
        d.getStyle().set("display", "flex").set("align-items", "center").set("gap", "10px").set("padding", "2px 0");
        d.getElement().setAttribute("title", g.descripcion() + " IP " + g.ip() + ", modelo " + g.modelo() + ".");
        return d;
    }

    /** Punto verde (en marcha) y cantidad de alarmas del controlador en cada pestaña de generador. */
    private void marcarPestanas(Map<Generador, List<String>> alarmasEnVivo) {
        for (Generador g : generadores) {
            Span marcas = marcasTab.get(g);
            if (marcas == null) continue;
            marcas.removeAll();
            if (almacen.hayArranqueAbierto(g.nombre())) {
                Span punto = new Span();
                punto.getStyle().set("width", "9px").set("height", "9px").set("border-radius", "50%").set("background", "#3BB273");
                punto.getElement().setAttribute("title", "En marcha");
                marcas.add(punto);
            }
            List<String> alarmas = alarmasEnVivo.getOrDefault(g, historial.alarmasActivas(g.nombre()));
            if (alarmas != null && !alarmas.isEmpty()) {
                Span n = new Span(String.valueOf(alarmas.size()));
                n.getStyle().set("min-width", "20px").set("height", "20px").set("padding", "0 6px").set("border-radius", "10px")
                        .set("background", "#E8505B").set("color", "#fff").set("font-size", "12px").set("font-weight", "700")
                        .set("display", "inline-flex").set("align-items", "center").set("justify-content", "center")
                        .set("box-sizing", "border-box");
                n.getElement().setAttribute("title", alarmas.size() + " alarmas en el controlador");
                marcas.add(n);
            }
            // Interruptor de la planta abierto o sin red: advertencia amarilla también en la pestaña.
            RedControladorService.EstadoActual red = redControlador.estadoActual(g.nombre());
            if (red != null && (red.isla() || SenalesRed.sinRed(red.estado()) || Boolean.FALSE.equals(red.mcb()))) {
                var icono = VaadinIcon.WARNING.create();
                icono.setColor("#E0B50F");
                icono.setSize("18px");
                icono.getElement().setAttribute("title", red.isla() ? "Interruptor de la planta abierto: en isla" : "Sin red según el controlador");
                marcas.add(icono);
            }
        }
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        AccesoGeneradores.verificar(event, lineaAccessService);
    }

    @Override
    protected void onAttach(AttachEvent e) {
        UI ui = e.getUI();
        ui.setPollInterval(POLL_MS);
        pollRegistration = ui.addPollListener(p -> {
            refrescar();
            if (tabPlanta != null && tabs.getSelectedTab() == tabPlanta) refrescarPlanta();
        });
    }

    @Override
    protected void onDetach(DetachEvent e) {
        if (pollRegistration != null) {
            pollRegistration.remove();
        }
        e.getUI().setPollInterval(POLL_MS_APP);
    }

    /** Generador de la pestaña elegida; null si la pestaña es otra sección. */
    private Generador generadorDePestana() {
        return tabGenerador.get(tabs.getSelectedTab());
    }

    /** Aviso amarillo si el modelo de un generador falta o su mapa no está confirmado: el de la pestaña, o de todos. */
    private void avisarMapas() {
        Generador elegido = generadorDePestana();
        List<String> avisos = new ArrayList<>();
        for (Generador g : elegido == null ? generadores : List.of(elegido)) {
            ModeloControlador m = service.modelo(g).orElse(null);
            String de = elegido == null ? g.nombre() + ": " : "";
            if (m == null) {
                avisos.add(de + "el modelo " + g.modelo() + " no está en C:\\LineaBaseX\\config\\generador-modelos.json: no se lee.");
            } else if (!m.confirmado()) {
                avisos.add(de + "mapa de registros de " + m.modelo() + " sin confirmar: los valores pueden no corresponder. "
                        + "No se guarda historial ni arranques hasta confirmarlo comparando con la pantalla del controlador.");
            }
        }
        avisoMapa.setText(String.join("   |   ", avisos));
        avisoMapa.setVisible(!avisos.isEmpty());
    }

    // ================= Sirena (solo ADMIN) =================

    private static final String[] CHIP_VERDE = {"#d4edda", "#155724"}, CHIP_AMARILLO = {"#fff3cd", "#856404"},
            CHIP_ROJO = {"#f8d7da", "#721c24"}, CHIP_GRIS = {"#e2e3e5", "#383d41"};
    private final Checkbox sirenaHabilitada = new Checkbox("Sirena habilitada");
    private final Div sirenaPlc = new Div();
    private final Div sirenaEstado = new Div();
    private final IntegerField sirenaSilencioMin = new IntegerField("Silenciar por (min)");
    private List<SirenaService.CorteProgramado> cortesMostrados = List.of();
    private String estadosCortesMostrados = "";
    /** Cuenta regresiva del silencio en el navegador (mm:ss), a partir de los ms que faltan ($0). */
    private static final String JS_CUENTA = "const el = this, fin = Date.now() + $0; clearInterval(el._cuenta);"
            + "const f = () => { if (!el.isConnected) { clearInterval(el._cuenta); return; }"
            + " const s = Math.max(0, Math.ceil((fin - Date.now()) / 1000));"
            + " el.textContent = String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0'); };"
            + "f(); el._cuenta = setInterval(f, 1000);";
    private final Div sirenaCausaRed = new Div();
    private final Div sirenaCausaPotencia = new Div();
    private final Div sirenaCausaGenerador = new Div();
    private final Button sirenaSilenciar = new Button("Silenciar", VaadinIcon.BELL_SLASH.create());
    private final Grid<SirenaService.CorteProgramado> cortesGrid = new Grid<>();

    /**
     * Sirena física por el LOGO! (pedido del usuario el 28-10-2026): habilitarla, ver si el LOGO! contesta y si suena, qué la
     * hace sonar, silenciarla, probarla y cargar los cortes programados (en los que la alarma de red no la hace sonar).
     */
    private Component crearSirena() {
        sirenaHabilitada.addValueChangeListener(e -> {
            if (!e.isFromClient()) return;
            try {
                sirena.habilitar(e.getValue());
                Notification.show(e.getValue() ? "Sirena habilitada." : "Sirena deshabilitada.", 3000, Notification.Position.MIDDLE);
            } catch (Exception ex) {
                sirenaHabilitada.setValue(!e.getValue());
                avisoError("No se pudo cambiar: " + ex.getMessage());
            }
            refrescarSirena();
        });
        sirenaSilenciar.addClickListener(e -> {
            sirena.silenciar();
            refrescarSirena();
        });
        Button probar = new Button("Probar sirena", VaadinIcon.PLAY.create(), e -> confirmarPrueba());
        Button actualizar = new Button("Actualizar", VaadinIcon.REFRESH.create(), e -> refrescarSirena());
        HorizontalLayout barra = new HorizontalLayout(sirenaHabilitada, sirenaPlc);
        barra.setAlignItems(Alignment.CENTER);
        // Cuánto dura "Silenciar": pasado ese tiempo, si siguen las alarmas, la sirena vuelve a sonar.
        sirenaSilencioMin.setMin(1);
        sirenaSilencioMin.setMax(SirenaService.SILENCIO_MINUTOS_MAXIMO);
        sirenaSilencioMin.setStepButtonsVisible(true);
        sirenaSilencioMin.setWidth("170px");
        sirenaSilencioMin.setValue(sirena.config().silencioMinutos());
        sirenaSilencioMin.addValueChangeListener(e -> {
            if (!e.isFromClient() || e.getValue() == null) return;
            try {
                sirena.silencioMinutos(e.getValue());
                Notification.show("Silenciar durará " + e.getValue() + " min.", 2500, Notification.Position.MIDDLE);
            } catch (Exception ex) {
                if (e.getOldValue() != null) sirenaSilencioMin.setValue(e.getOldValue());
                avisoError(ex.getMessage());
            }
        });
        HorizontalLayout botones = new HorizontalLayout(sirenaSilenciar, sirenaSilencioMin, probar, actualizar);
        botones.setAlignItems(Alignment.END);
        botones.getStyle().set("flex-wrap", "wrap");

        Span tituloCorte = new Span("Corte programado");
        tituloCorte.getStyle().set("font-weight", "700").set("margin-top", "12px");
        DateTimePicker desde = new DateTimePicker("Desde");
        DateTimePicker hasta = new DateTimePicker("Hasta");
        desde.setValue(LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0));
        hasta.setValue(desde.getValue().plusHours(4));
        Button agregar = new Button("Agregar corte", VaadinIcon.PLUS.create(), e -> {
            try {
                sirena.agregarCorte(desde.getValue(), hasta.getValue());
                refrescarSirena();
            } catch (Exception ex) {
                avisoError(ex.getMessage());
            }
        });
        HorizontalLayout filtros = new HorizontalLayout(desde, hasta, agregar);
        filtros.setAlignItems(Alignment.END);
        filtros.getStyle().set("flex-wrap", "wrap");

        cortesGrid.addColumn(c -> c.desde().format(CORTA)).setHeader("Desde").setAutoWidth(true).setFlexGrow(0);
        cortesGrid.addColumn(c -> c.hasta().format(CORTA)).setHeader("Hasta").setAutoWidth(true).setFlexGrow(0);
        cortesGrid.addColumn(c -> c.estado(LocalDateTime.now())).setHeader("Estado").setAutoWidth(true).setFlexGrow(0);
        cortesGrid.addComponentColumn(c -> {
            Button borrar = new Button(VaadinIcon.TRASH.create(), ev -> {
                sirena.borrarCorte(c);
                refrescarSirena();
            });
            borrar.getStyle().set("color", "#E8505B");
            borrar.getElement().setAttribute("aria-label", "Borrar el corte");
            return borrar;
        }).setHeader("").setAutoWidth(true).setFlexGrow(0);
        cortesGrid.setHeight("220px");

        VerticalLayout v = new VerticalLayout(barra, sirenaEstado, sirenaCausaRed, sirenaCausaPotencia, sirenaCausaGenerador, botones,
                tituloCorte, filtros, cortesGrid);
        v.setPadding(false);
        v.setSizeFull();
        refrescarSirena();
        return v;
    }

    private void confirmarPrueba() {
        com.vaadin.flow.component.dialog.Dialog d = new com.vaadin.flow.component.dialog.Dialog();
        d.setHeaderTitle("¿Probar la sirena?");
        d.add(new Span("La sirena va a sonar unos 3 segundos."));
        Button si = new Button("Probar", e -> {
            sirena.probar();
            d.close();
            refrescarSirena();
        });
        si.getStyle().set("background-color", "#E8505B").set("color", "#fff");
        d.getFooter().add(new Button("Cancelar", e -> d.close()), si);
        d.open();
    }

    private static void avisoError(String texto) {
        Notification.show(texto, 5000, Notification.Position.MIDDLE).addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    private void refrescarSirena() {
        SirenaService.Estado e = sirena.estado();
        sirenaHabilitada.setValue(e.habilitada());
        sirenaPlc.removeAll();
        String plc = "LOGO! " + e.ip();
        if (!e.plcEnUso()) {
            sirenaPlc.add(chip(plc + ": sin uso", CHIP_GRIS));
        } else if (e.plcOk()) {
            sirenaPlc.add(chip(plc + ": contesta", CHIP_VERDE));
        } else {
            sirenaPlc.add(chip(plc + ": sin comunicación", CHIP_ROJO));
            if (e.errorPlc() != null) {
                Span motivo = new Span(" " + e.errorPlc());
                motivo.getStyle().set("font-size", "12px").set("color", "#6b7080");
                sirenaPlc.add(motivo);
            }
        }
        // La sirena: suena (rojo) o está apagada (verde); el LOGO! se activa con una entrada y se apaga con la otra.
        sirenaEstado.removeAll();
        sirenaEstado.getStyle().set("display", "flex").set("align-items", "center").set("gap", "8px").set("flex-wrap", "wrap");
        Span nombre = new Span("Sirena");
        nombre.getStyle().set("font-weight", "700").set("min-width", "230px");
        long restante = e.silencioHasta() - System.currentTimeMillis();
        sirenaEstado.add(nombre, e.prueba() ? chip("PRUEBA", CHIP_AMARILLO) : e.sonando() ? chip("SUENA", CHIP_ROJO)
                : restante > 0 ? chip("Silenciada", CHIP_AMARILLO) : chip("Apagada", CHIP_VERDE));
        if (restante > 0) {
            // Contador descendente: vuelve a sonar al llegar a cero si siguen las alarmas.
            Span etiqueta = new Span("vuelve a sonar en");
            etiqueta.getStyle().set("font-size", "12px").set("color", "#6b7080");
            Span cuenta = new Span(String.format("%02d:%02d", restante / 60_000, restante / 1000 % 60));
            cuenta.getStyle().set("font-weight", "700").set("font-size", "18px").set("font-variant-numeric", "tabular-nums").set("color", "#856404");
            sirenaEstado.add(etiqueta, cuenta);
            cuenta.getElement().executeJs(JS_CUENTA, restante);
        }
        if (e.plcEnUso() && e.leidoActivar() != null && e.leidoDesactivar() != null
                && (e.leidoActivar() != e.sonando() || e.leidoDesactivar() == e.sonando())) {
            sirenaEstado.add(chip("El LOGO! lee " + e.entradaActivar() + " = " + (e.leidoActivar() ? 1 : 0) + " y "
                    + e.entradaDesactivar() + " = " + (e.leidoDesactivar() ? 1 : 0), CHIP_ROJO));
        }
        mostrarCausa(sirenaCausaRed, "Alarma de pérdida de red", e.condRed(), e.silenciadaRed(), e.corteActivo(), e.habilitada(), e.motivoRed());
        mostrarCausa(sirenaCausaPotencia, "Alarma de potencia", e.condPotencia(), e.silenciadaPotencia(), false, e.habilitada(), e.motivoPotencia());
        mostrarCausa(sirenaCausaGenerador, "Alarmas de los generadores", e.condGenerador(), e.silenciadaGenerador(), false, e.habilitada(),
                e.motivoGenerador());
        sirenaSilenciar.setEnabled(e.sonando() && !e.prueba());
        if (!Objects.equals(sirenaSilencioMin.getValue(), e.silencioMinutos())) sirenaSilencioMin.setValue(e.silencioMinutos());
        // La grilla de cortes solo se vuelve a cargar si cambió algo (con el refresco de 2 s se perdería el clic en Borrar).
        List<SirenaService.CorteProgramado> cortes = sirena.config().cortes();
        LocalDateTime ahora = LocalDateTime.now();
        String estados = cortes.stream().map(c -> c.estado(ahora)).collect(Collectors.joining("|"));
        if (!cortes.equals(cortesMostrados) || !estados.equals(estadosCortesMostrados)) {
            cortesGrid.setItems(cortes);
            cortesMostrados = cortes;
            estadosCortesMostrados = estados;
        }
    }

    /** El navegador la llama cada 2 s mientras se ve la pestaña Sirena (ver el listener de pestañas). */
    @ClientCallable
    public void tickSirena() {
        if (tabSirena != null && tabs.getSelectedTab() == tabSirena) refrescarSirena();
    }

    /** Una línea por causa: en alarma (rojo) o normal (verde), y si está silenciada o en corte programado (amarillo), con el motivo. */
    private static void mostrarCausa(Div linea, String titulo, boolean condicion, boolean silenciada, boolean corte, boolean habilitada,
                                     String motivo) {
        linea.removeAll();
        linea.getStyle().set("display", "flex").set("align-items", "center").set("gap", "8px").set("flex-wrap", "wrap");
        Span nombre = new Span(titulo);
        nombre.getStyle().set("min-width", "230px");
        linea.add(nombre);
        if (!condicion) linea.add(chip("Normal", CHIP_VERDE));
        else if (silenciada) linea.add(chip("En alarma, silenciada", CHIP_AMARILLO));
        else if (corte) linea.add(chip("En alarma, corte programado: no suena", CHIP_AMARILLO));
        else if (!habilitada) linea.add(chip("En alarma, sirena deshabilitada", CHIP_GRIS));
        else linea.add(chip("En alarma", CHIP_ROJO));
        if (condicion && motivo != null && !motivo.isEmpty()) {
            Span m = new Span(motivo);
            m.getStyle().set("font-size", "12px").set("color", "#6b7080");
            linea.add(m);
        }
    }

    private static Span chip(String texto, String[] color) {
        Span s = new Span(texto);
        s.getStyle().set("background", color[0]).set("color", color[1]).set("padding", "3px 10px").set("border-radius", "6px")
                .set("font-weight", "600").set("font-size", "12px");
        return s;
    }

    // ================= Capturas al segundo (solo ADMIN) =================

    private static final String CHART_CAPTURA = "chart-captura-segundo";
    /** Colores de los generadores en el orden de generador-config.json, los mismos de Análisis y Transformadores. */
    private static final String[] COLORES_CAPTURA = {"0xF29F67", "0x6F42C1"};
    private final Grid<CapturaSegundoService.Captura> capturasGrid = new Grid<>();
    private final Span capturasEstado = new Span();
    private final Div capturaDetalle = new Div();
    private final Checkbox capturaEncendida = new Checkbox("Captura al segundo encendida");

    /**
     * Capturas al segundo de los dos controladores alrededor de cada evento (pedido del usuario el 28-10-2026 para ver qué
     * pasa en un disparo por ROCOF). Se guardan solas; aquí se encienden o apagan, se ven y se descargan.
     */
    private Component crearCapturas() {
        capturaEncendida.setValue(capturas.encendida());
        capturaEncendida.addValueChangeListener(e -> {
            if (!e.isFromClient()) return;
            try {
                capturas.encender(e.getValue());
                Notification.show(e.getValue() ? "Captura al segundo encendida." : "Captura al segundo apagada.", 3000, Notification.Position.MIDDLE);
            } catch (Exception ex) {
                capturaEncendida.setValue(!e.getValue());
                Notification.show("No se pudo cambiar: " + ex.getMessage(), 5000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
            cargarCapturas();
        });
        Button guardar = new Button("Guardar ahora", VaadinIcon.DOWNLOAD_ALT.create(), e -> {
            try {
                String usuario = lineaAccessService.usuarioActual();
                long id = capturas.guardarAhora(usuario == null ? "administrador" : usuario);
                Notification.show("Se guardaron los últimos 10 minutos (captura " + id + ").", 3000, Notification.Position.MIDDLE);
                cargarCapturas();
            } catch (Exception ex) {
                Notification.show("No se pudo guardar: " + ex.getMessage(), 5000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        guardar.getElement().setAttribute("title", "Guarda los últimos 10 minutos en memoria, por si se vio algo que no disparó una captura");
        Button actualizar = new Button("Actualizar", VaadinIcon.REFRESH.create(), e -> cargarCapturas());
        HorizontalLayout barra = new HorizontalLayout(capturaEncendida, guardar, actualizar, capturasEstado);
        barra.setAlignItems(Alignment.CENTER);
        barra.setWidthFull();
        capturasEstado.getStyle().set("font-size", "13px").set("color", "#6b7080");

        Span nota = new Span("Lee los dos controladores cada segundo y guarda sola una captura cuando se abre un interruptor, el "
                + "generador sale de paralelo, la ROCOF pasa la mitad del ajuste, sube de golpe su máximo o el del salto de vector, "
                + "la potencia cae más de 200 kW en un segundo o la frecuencia de la red sale de 59,8 a 60,2 Hz: 5 minutos antes y "
                + "5 minutos después de que todo vuelva a la normalidad. Solo lectura de los controladores.");
        nota.getStyle().set("font-size", "12px").set("color", "#6b7080");

        capturasGrid.addColumn(c -> c.disparo().format(HORA)).setHeader("Evento").setAutoWidth(true).setFlexGrow(0);
        capturasGrid.addColumn(c -> c.manual() ? "A mano" : "Automática").setHeader("Tipo").setAutoWidth(true).setFlexGrow(0);
        capturasGrid.addColumn(c -> c.fin() == null ? "-" : duracionMinSeg(java.time.Duration.between(c.inicio(), c.fin()).getSeconds()))
                .setHeader("Duración").setAutoWidth(true).setFlexGrow(0);
        capturasGrid.addColumn(CapturaSegundoService.Captura::motivo).setHeader("Motivo").setFlexGrow(1);
        capturasGrid.setHeight("220px");
        capturasGrid.asSingleSelect().addValueChangeListener(e -> mostrarCaptura(e.getValue()));

        capturaDetalle.setWidthFull();
        VerticalLayout v = new VerticalLayout(barra, nota, capturasGrid, capturaDetalle);
        v.setPadding(false);
        v.setSizeFull();
        return v;
    }

    private static String duracionMinSeg(long s) {
        return s / 60 + " min " + String.format("%02d", s % 60) + " s";
    }

    private void cargarCapturas() {
        capturaEncendida.setValue(capturas.encendida());
        StringBuilder estado = new StringBuilder();
        if (capturas.encendida()) {
            Map<String, Integer> mem = capturas.enMemoria();
            estado.append(mem.isEmpty() ? "Sin lecturas todavía" : "En memoria: " + mem.entrySet().stream()
                    .map(x -> x.getKey() + " " + x.getValue() + " s").collect(Collectors.joining(", ")));
            String curso = capturas.enCurso();
            if (curso != null) estado.append(" · Captura en curso (se guarda al completarse): ").append(curso);
        } else {
            estado.append("Apagada: no se lee al segundo ni se guardan capturas.");
        }
        capturasEstado.setText(estado.toString());
        CapturaSegundoService.Captura elegida = capturasGrid.asSingleSelect().getValue();
        List<CapturaSegundoService.Captura> lista = capturas.capturas();
        capturasGrid.setItems(lista);
        if (elegida != null) lista.stream().filter(c -> c.id() == elegida.id()).findFirst().ifPresent(capturasGrid::select);
    }

    /** Gráfico segundo a segundo de la captura y su CSV. */
    private void mostrarCaptura(CapturaSegundoService.Captura c) {
        capturaDetalle.removeAll();
        if (c == null) return;
        List<CapturaSegundoService.Muestra> muestras = capturas.muestras(c.id());
        if (muestras.isEmpty()) {
            capturaDetalle.add(new Span("La captura no tiene lecturas."));
            return;
        }
        // Generadores en el orden de generador-config.json (sus colores) y, después, cualquier otro que aparezca.
        List<String> nombres = new ArrayList<>(service.generadores().stream().map(Generador::nombre).toList());
        muestras.stream().map(CapturaSegundoService.Muestra::generador).distinct().filter(n -> !nombres.contains(n)).forEach(nombres::add);
        nombres.removeIf(n -> muestras.stream().noneMatch(m -> m.generador().equals(n)));

        // ROCOF y salto de vector, cada uno en su panel con su ajuste de disparo y la mitad (desde la mitad avisa la app); solo
        // si algún generador los publica (el InteliGen 200 del GenPower no).
        List<String> conRocof = nombres.stream().filter(n -> muestras.stream().anyMatch(m -> m.generador().equals(n) && m.v(ParametroGenerador.ROCOF) != null)).toList();
        Double ajusteRocof = null, ajusteSalto = null;
        for (String n : conRocof) {
            SenalesRed red = generadores.stream().filter(g -> g.nombre().equals(n)).findFirst().flatMap(service::modelo).map(ModeloControlador::red).orElse(null);
            if (red != null && ajusteRocof == null) ajusteRocof = red.limiteRocof();
            if (red != null && ajusteSalto == null) ajusteSalto = red.limiteSaltoVector();
        }
        List<String> paneles = new ArrayList<>(List.of("kW / kVAr", "RPM", "Frecuencia (Hz)", "Tensión de red (V)"));
        int pRocof = -1, pSalto = -1;
        if (!conRocof.isEmpty()) {
            pRocof = paneles.size();
            paneles.add("ROCOF (Hz/s)");
            pSalto = paneles.size();
            paneles.add("Salto de vector (°)");
        }
        int pInterruptor = paneles.size();
        paneles.add("Interruptor (1 = cerrado)");
        List<GraficaModel.LineaReferencia> referencias = new ArrayList<>();
        if (ajusteRocof != null) {
            referencias.add(new GraficaModel.LineaReferencia(pRocof, ajusteRocof, "0xE8505B", "Ajuste de disparo " + numero(ajusteRocof) + " Hz/s"));
            referencias.add(new GraficaModel.LineaReferencia(pRocof, ajusteRocof / 2, "0xB8920A", "Mitad del ajuste " + numero(ajusteRocof / 2) + " Hz/s"));
        }
        if (ajusteSalto != null) {
            referencias.add(new GraficaModel.LineaReferencia(pSalto, ajusteSalto, "0xE8505B", "Ajuste de disparo " + numero(ajusteSalto) + "°"));
            referencias.add(new GraficaModel.LineaReferencia(pSalto, ajusteSalto / 2, "0xB8920A", "Mitad del ajuste " + numero(ajusteSalto / 2) + "°"));
        }
        List<GraficaModel.SerieCaptura> series = new ArrayList<>();
        List<java.util.function.Function<CapturaSegundoService.Muestra, Double>> lectores = new ArrayList<>();
        List<String> deGenerador = new ArrayList<>();
        for (int i = 0; i < nombres.size(); i++) {
            String n = nombres.get(i), color = COLORES_CAPTURA[i % COLORES_CAPTURA.length];
            agregar(series, lectores, deGenerador, n, n + " kW", 0, color, false, false, m -> m.v(ParametroGenerador.KW));
            agregar(series, lectores, deGenerador, n, n + " kVAr", 0, color, true, false, m -> m.v(ParametroGenerador.KVAR));
            agregar(series, lectores, deGenerador, n, n + " RPM", 1, color, false, false, m -> m.v(ParametroGenerador.RPM));
            agregar(series, lectores, deGenerador, n, n + " Hz generador", 2, color, false, false, m -> m.v(ParametroGenerador.FRECUENCIA));
            agregar(series, lectores, deGenerador, n, n + " Hz red", 2, color, true, false, m -> m.v(ParametroGenerador.RED_FRECUENCIA));
            agregar(series, lectores, deGenerador, n, n + " V red", 3, color, false, false, m -> promedio(m.v(ParametroGenerador.RED_V_L1L2),
                    m.v(ParametroGenerador.RED_V_L2L3), m.v(ParametroGenerador.RED_V_L3L1)));
            if (conRocof.contains(n)) {
                agregar(series, lectores, deGenerador, n, n + " ROCOF", pRocof, color, false, false, ajusteRocof, m -> m.v(ParametroGenerador.ROCOF));
                agregar(series, lectores, deGenerador, n, n + " ROCOF máxima", pRocof, color, true, true, ajusteRocof, m -> m.v(ParametroGenerador.ROCOF_MAX));
                agregar(series, lectores, deGenerador, n, n + " salto de vector máximo", pSalto, color, true, true, ajusteSalto, m -> m.v(ParametroGenerador.SALTO_VECTOR_MAX));
            }
            agregar(series, lectores, deGenerador, n, n + " MCB", pInterruptor, color, false, true, m -> m.mcb() == null ? null : m.mcb() ? 1.0 : 0.0);
            agregar(series, lectores, deGenerador, n, n + " GCB", pInterruptor, color, true, true, m -> m.gcb() == null ? null : m.gcb() ? 1.0 : 0.0);
        }
        // Una fila por segundo con las lecturas de los dos generadores de ese segundo.
        java.util.TreeMap<Long, Double[]> filas = new java.util.TreeMap<>();
        for (CapturaSegundoService.Muestra m : muestras) {
            Double[] fila = filas.computeIfAbsent(m.t() / 1000 * 1000, k -> new Double[series.size()]);
            for (int s = 0; s < series.size(); s++) {
                if (deGenerador.get(s).equals(m.generador())) fila[s] = lectores.get(s).apply(m);
            }
        }
        long disparo = c.disparo().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        Div chart = new Div();
        chart.setId(CHART_CAPTURA);
        chart.setWidthFull();
        chart.setHeight((180 + paneles.size() * 130) + "px");
        Span titulo = new Span("Captura del " + c.disparo().format(HORA) + " · " + c.motivo());
        titulo.getStyle().set("font-weight", "600");
        Span ayuda = new Span("La línea roja marca el evento. Arrastrar para acercar, rueda para moverse; clic deja una marca con la hora y doble clic las borra. "
                + "La leyenda prende y apaga cada curva. La lectura es cada segundo: el disparo de la protección dura menos."
                + " El GenPower no tiene curva de ROCOF: su controlador no publica esa medición por Modbus (sí tiene la protección).");
        ayuda.getStyle().set("font-size", "12px").set("color", "#6b7080");
        var link = CsvUtil.crearLinkDescarga("captura-" + c.id() + "-" + c.disparo().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".csv",
                () -> new java.io.ByteArrayInputStream(csvCaptura(muestras).getBytes(java.nio.charset.StandardCharsets.UTF_8)), "Descargar CSV");
        Button borrar = new Button("Borrar", VaadinIcon.TRASH.create(), e -> confirmarBorrado(c));
        borrar.getStyle().set("color", "#E8505B");
        HorizontalLayout cab = new HorizontalLayout(titulo, link, borrar);
        cab.setAlignItems(Alignment.CENTER);
        capturaDetalle.add(cab, ayuda, chart);
        // Tooltips semitransparentes, como en Períodos en marcha (pedido del usuario): no tapan las curvas.
        chart.getElement().executeJs(GraficaModel.conTooltipsTransparentes(GraficaModel.getCapturaScript(CHART_CAPTURA,
                new ArrayList<>(filas.keySet()), paneles, series, new ArrayList<>(filas.values()), disparo, referencias)));
    }

    /** Borrar una captura pide confirmación: se pierde del todo (no hay otra copia). */
    private void confirmarBorrado(CapturaSegundoService.Captura c) {
        com.vaadin.flow.component.dialog.Dialog d = new com.vaadin.flow.component.dialog.Dialog();
        d.setHeaderTitle("¿Borrar la captura?");
        d.add(new Span("Captura del " + c.disparo().format(HORA) + ": " + c.motivo() + ". Se borran todas sus lecturas y no se puede deshacer."));
        Button si = new Button("Borrar", e -> {
            try {
                capturas.borrar(c.id());
                d.close();
                capturaDetalle.removeAll();
                capturasGrid.deselectAll();
                cargarCapturas();
                Notification.show("Captura borrada.", 3000, Notification.Position.MIDDLE);
            } catch (Exception ex) {
                Notification.show("No se pudo borrar: " + ex.getMessage(), 5000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        si.getStyle().set("background-color", "#E8505B").set("color", "#fff");
        d.getFooter().add(new Button("Cancelar", e -> d.close()), si);
        d.open();
    }

    private static void agregar(List<GraficaModel.SerieCaptura> series, List<java.util.function.Function<CapturaSegundoService.Muestra, Double>> lectores,
                                List<String> deGenerador, String generador, String nombre, int panel, String color, boolean punteada,
                                boolean escalon, java.util.function.Function<CapturaSegundoService.Muestra, Double> lector) {
        agregar(series, lectores, deGenerador, generador, nombre, panel, color, punteada, escalon, null, lector);
    }

    /** ajuste: el de disparo de la protección, para mostrar en el tooltip el % del ajuste. */
    private static void agregar(List<GraficaModel.SerieCaptura> series, List<java.util.function.Function<CapturaSegundoService.Muestra, Double>> lectores,
                                List<String> deGenerador, String generador, String nombre, int panel, String color, boolean punteada,
                                boolean escalon, Double ajuste, java.util.function.Function<CapturaSegundoService.Muestra, Double> lector) {
        series.add(new GraficaModel.SerieCaptura(nombre, panel, color, punteada, escalon, ajuste));
        lectores.add(lector);
        deGenerador.add(generador);
    }

    /** 1,3 · 0,65 · 10: sin ceros de más, con coma decimal. */
    private static String numero(double v) {
        return java.math.BigDecimal.valueOf(Math.round(v * 100) / 100.0).stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static Double promedio(Double a, Double b, Double c) {
        if (a == null || b == null || c == null) return null;
        return Math.round((a + b + c) / 3 * 10) / 10.0;
    }

    /** CSV de la captura: una fila por segundo y generador, con todas las columnas guardadas. */
    private static String csvCaptura(List<CapturaSegundoService.Muestra> muestras) {
        StringBuilder sb = new StringBuilder("﻿fecha;milisegundo;generador;marcha;selector;estado;mcb;gcb");
        List<ParametroGenerador> cols = CapturaSegundoService.columnas();
        for (ParametroGenerador p : cols) sb.append(';').append(p.columna());
        sb.append('\n');
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
        for (CapturaSegundoService.Muestra m : muestras) {
            LocalDateTime t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(m.t()), java.time.ZoneId.systemDefault());
            sb.append(t.format(f)).append(';').append(m.t() % 1000).append(';').append(CsvUtil.escape(m.generador())).append(';')
                    .append(m.marcha() ? 1 : 0).append(';').append(m.selector() == null ? "" : m.selector().etiqueta()).append(';')
                    .append(m.estado() == null ? "" : m.estado().name()).append(';')
                    .append(m.mcb() == null ? "" : m.mcb() ? 1 : 0).append(';').append(m.gcb() == null ? "" : m.gcb() ? 1 : 0);
            for (ParametroGenerador p : cols) {
                Double v = m.v(p);
                sb.append(';').append(v == null ? "" : String.valueOf(v).replace('.', ','));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ================= Historial de eventos =================

    private VerticalLayout crearEventos() {
        eventosPeriodo.setItems(1, 7, 30, 90, 365);
        eventosPeriodo.setItemLabelGenerator(d -> d == 1 ? "Últimas 24 h" : d == 365 ? "Último año" : "Últimos " + d + " días");
        eventosPeriodo.setValue(30);
        eventosPeriodo.setWidth("170px");
        eventosTipo.setItems(TipoEventoGenerador.values());
        eventosTipo.setItemLabelGenerator(TipoEventoGenerador::etiqueta);
        eventosTipo.setPlaceholder("Todos");
        eventosTipo.setClearButtonVisible(true);
        eventosTipo.setWidth("220px");
        Button ver = new Button("Ver", VaadinIcon.SEARCH.create(), e -> cargarEventos());
        ver.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        var descarga = com.example.base.ui.CsvUtil.crearLinkDescarga("eventos-generador.csv", this::csvEventos, "Descargar CSV");
        HorizontalLayout filtros = new HorizontalLayout(eventosPeriodo, eventosTipo, ver, descarga);
        filtros.setAlignItems(Alignment.END);
        filtros.getStyle().set("flex-wrap", "wrap");

        colEventoGenerador = eventosGrid.addColumn(ev -> generadorDeEvento.getOrDefault(ev, "")).setHeader("Generador")
                .setAutoWidth(true).setFlexGrow(0);
        colEventoGenerador.setVisible(generadores.size() > 1);
        eventosGrid.addColumn(ev -> ev.id() == null ? "" : String.valueOf(ev.id())).setHeader("Nro").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> ev.fecha().format(HORA)).setHeader("Fecha y hora").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addComponentColumn(ev -> badgeNuevo(ev.tipo().etiqueta(), estadoDe(ev.tipo()))).setHeader("Evento").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> ev.descripcion() == null ? "" : ev.descripcion()).setHeader("Descripción").setAutoWidth(true);
        eventosGrid.addColumn(ev -> fmt(ev.rpm(), "%.0f")).setHeader("RPM").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.kw(), "%.0f")).setHeader("kW").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.pf(), "%.2f")).setHeader("PF").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.frecuencia(), "%.1f")).setHeader("Hz gen").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.vGen(), "%.0f")).setHeader("V gen").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.iGen(), "%.0f")).setHeader("I gen").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.redFrecuencia(), "%.1f")).setHeader("Hz red").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.vRed(), "%.0f")).setHeader("V red").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.bateria(), "%.1f")).setHeader("Batería").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.tempRefrigerante(), "%.0f")).setHeader("Temp").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.horasMarcha(), "%.1f")).setHeader("Horas").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> ev.kwh() == null ? "-" : String.format(Locale.ROOT, "%,d", ev.kwh())).setHeader("kWh").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> ev.arranques() == null ? "-" : String.valueOf(ev.arranques())).setHeader("Arranques").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.setSizeFull();

        eventosMensaje.getStyle().set("font-size", "12px").set("color", "#555");
        VerticalLayout v = new VerticalLayout(filtros, eventosMensaje, eventosGrid);
        v.setSizeFull();
        v.setPadding(false);
        v.setFlexGrow(1, eventosGrid);
        return v;
    }

    /** Último evento guardado de los generadores (si cambia, hay eventos nuevos y se recarga la grilla). */
    private long ultimoIdEventos() {
        long suma = 0;
        for (Generador g : generadores) suma += historial.ultimoId(g.nombre());
        return suma;
    }

    /** Los eventos de todos los generadores juntos, del más nuevo al más viejo, con la columna Generador. */
    private void cargarEventos() {
        if (generador == null) return;
        int dias = eventosPeriodo.getValue() == null ? 30 : eventosPeriodo.getValue();
        LocalDateTime hasta = LocalDateTime.now();
        ultimoEventoMostrado = ultimoIdEventos();
        generadorDeEvento.clear();
        List<EventoGenerador> lista = new ArrayList<>();
        for (Generador g : generadores) {
            for (EventoGenerador ev : historial.listar(g.nombre(), hasta.minusDays(dias), hasta, eventosTipo.getValue())) {
                lista.add(ev);
                generadorDeEvento.put(ev, g.nombre());
            }
        }
        if (generadores.size() > 1) lista.sort(java.util.Comparator.comparing(EventoGenerador::fecha).reversed());
        eventos = lista;
        eventosGrid.setItems(eventos);
        eventosMensaje.setText(eventos.size() + " eventos de " + (generadores.size() > 1 ? "todos los generadores" : generador.nombre())
                + (eventos.isEmpty() ? ". El historial empieza a llenarse desde que se activó esta versión." : "."));
    }

    private java.io.InputStream csvEventos() {
        StringBuilder sb = new StringBuilder("﻿Generador;Nro;Fecha y hora;Evento;Descripción;RPM;kW;PF;Hz gen;V gen;I gen;Hz red;V red;Batería;Temp;Horas;kWh;Arranques\n");
        for (EventoGenerador ev : eventos) {
            sb.append(com.example.base.ui.CsvUtil.escape(generadorDeEvento.getOrDefault(ev, ""))).append(';')
                    .append(ev.id()).append(';').append(ev.fecha().format(HORA)).append(';')
                    .append(com.example.base.ui.CsvUtil.escape(ev.tipo().etiqueta())).append(';')
                    .append(com.example.base.ui.CsvUtil.escape(ev.descripcion() == null ? "" : ev.descripcion())).append(';')
                    .append(csv(ev.rpm())).append(';').append(csv(ev.kw())).append(';').append(csv(ev.pf())).append(';')
                    .append(csv(ev.frecuencia())).append(';').append(csv(ev.vGen())).append(';').append(csv(ev.iGen())).append(';')
                    .append(csv(ev.redFrecuencia())).append(';').append(csv(ev.vRed())).append(';').append(csv(ev.bateria())).append(';')
                    .append(csv(ev.tempRefrigerante())).append(';').append(csv(ev.horasMarcha())).append(';')
                    .append(ev.kwh() == null ? "" : ev.kwh()).append(';').append(ev.arranques() == null ? "" : ev.arranques()).append('\n');
        }
        return new java.io.ByteArrayInputStream(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String csv(Double v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static Estado estadoDe(TipoEventoGenerador t) {
        return switch (t.nivel()) {
            case NORMAL -> Estado.SIN_DATO;
            case BUENO -> Estado.OK;
            case AVISO -> Estado.AVISO;
            case FALLA -> Estado.FUERA;
        };
    }

    // ================= Alarmas del controlador =================

    /**
     * Aviso rojo con las alarmas de la lista del controlador (texto tal como lo envía, desde cuándo y qué significa), solo
     * para el generador de la pestaña elegida cuando no hay comunicación: con comunicación su tablero ya tiene la lista de
     * alarmas, y en las otras pestañas la cantidad de alarmas se ve en la pestaña de cada generador.
     *
     * @param g          generador sin comunicación, o null para quitar el aviso
     * @param alarmas    las de la última lectura de fondo
     */
    private void mostrarAlarmas(Generador g, List<String> alarmas) {
        alertaAlarmas.removeAll();
        alertaAlarmas.setVisible(g != null && agregarAlarmas(g, alarmas, true));
    }

    /** Agrega la caja roja con las alarmas de un generador; false si no tiene ninguna. */
    private boolean agregarAlarmas(Generador g, List<String> alarmas, boolean ultimaConocida) {
        if (alarmas == null || alarmas.isEmpty()) return false;
        Div caja = new Div();
        caja.getStyle().set("background-color", "#f8d7da").set("color", "#721c24").set("border", "2px solid #721c24")
                .set("border-radius", "6px").set("padding", "8px 12px");
        var icono = VaadinIcon.WARNING.create();
        icono.getStyle().set("margin-right", "6px");
        Span titulo = new Span("ALARMA EN EL CONTROLADOR DE " + g.nombre().toUpperCase(Locale.ROOT) + " (" + alarmas.size() + ")"
                + (ultimaConocida ? " - última lectura conocida, sin comunicación ahora" : ""));
        titulo.getStyle().set("font-weight", "700").set("font-size", "15px");
        Div cabeza = new Div(icono, titulo);
        cabeza.getStyle().set("display", "flex").set("align-items", "center");
        caja.add(cabeza);
        for (String a : alarmas) {
            HistorialEventosService.Aparicion ap = historial.aparicion(g.nombre(), a);
            String desde = ap == null ? "" : ap.inicioLectura()
                    ? ", desde antes del " + ap.fecha().format(CORTA) + " (cuando la app empezó a leer)"
                    : ", desde el " + ap.fecha().format(CORTA);
            Span nombre = new Span(AlarmaComap.nombre(a));
            nombre.getStyle().set("font-weight", "700");
            boolean activa = AlarmaComap.activa(a);
            Span estado = badgeNuevo(AlarmaComap.estado(a), activa ? Estado.FUERA : Estado.AVISO);
            Span cuando = new Span(desde.isEmpty() ? "" : desde.substring(2));
            cuando.getStyle().set("font-size", "13px");
            Div linea = new Div(nombre, estado, cuando);
            linea.getStyle().set("display", "flex").set("align-items", "center").set("gap", "8px").set("flex-wrap", "wrap")
                    .set("margin-top", "6px");
            Span explicacion = new Span(AlarmaComap.explicacion(a));
            explicacion.getStyle().set("font-size", "13px").set("display", "block");
            caja.add(linea, explicacion);
        }
        Span nota = new Span("Lista de alarmas del controlador (solo lectura). El controlador marca con \"!\" la alarma activa y con "
                + "\"*\" la que todavía no tiene reset. Todo queda en el Historial de eventos: cuando aparece, se activa de nuevo, deja "
                + "de estar activa, se confirma o sale de la lista (reseteada).");
        nota.getStyle().set("font-size", "11px").set("display", "block").set("margin-top", "4px");
        caja.add(nota);
        if (alertaAlarmas.getComponentCount() > 0) caja.getStyle().set("margin-top", "6px");
        alertaAlarmas.add(caja);
        return true;
    }

    // ================= Balance energético P1 (planta 1 en vivo) =================

    /**
     * Color de cada generador (en el orden de generador-config.json), el mismo en la barra y en el gráfico: naranja y
     * violeta de la paleta de referencia, validados para daltonismo junto al azul de la red y el verde agua de lo entregado.
     */
    static final String[] COLORES_GEN = {"#eb6834", "#6250d6", "#e87ba4", "#eda100"};
    private static final String CHIP = "padding:2px 8px;border-radius:10px;font-weight:600;font-size:13px;white-space:nowrap;";

    private VerticalLayout crearPlanta() {
        Button actualizar = new Button("Actualizar", VaadinIcon.REFRESH.create(), e -> refrescarPlanta());
        plantaHora.getStyle().set("font-size", "12px").set("color", "#555");
        HorizontalLayout barra = new HorizontalLayout(plantaHora, actualizar);
        barra.setAlignItems(Alignment.CENTER);
        plantaContenido.setWidthFull();
        plantaDetalle.setWidthFull();
        VerticalLayout v = new VerticalLayout(barra, plantaContenido, plantaDetalle);
        v.setPadding(false);
        return v;
    }

    /** La planta ahora: primero el total (consumo, generadores, red) y después una fila por generador. */
    private void refrescarPlanta() {
        PlantaEnVivoService.Planta p = plantaService.leer();
        MetaImportacionRed meta = service.metaImportacionRed();
        plantaHora.setText("Leído " + p.leido().format(HORA));
        StringBuilder h = new StringBuilder("<div style='display:grid;gap:14px;width:100%;max-width:1150px'>");

        String aviso = "<div style='background:#fff3cd;color:#856404;padding:8px 12px;border-radius:6px;font-size:13px'>";
        if (!p.conPrincipal()) {
            h.append(aviso).append("Medidor principal sin lectura reciente")
                    .append(p.principal() == null || p.principal().fecha() == null ? "" : " (última: " + p.principal().fecha().format(CORTA) + ")")
                    .append(": sin consumo de la planta ni red hasta la próxima lectura.</div>");
        }
        if (!p.completa()) {
            h.append(aviso).append("Cifras parciales: ");
            p.filas().stream().filter(f -> !p.contado(f)).forEach(f -> h.append(esc(f.generador())).append(" (")
                    .append(esc(f.error() == null ? "sin dato" : f.error())).append("). "));
            h.append("El total de arriba no lo incluye.</div>");
        }

        // Total de la planta.
        double consumo = p.consumo();
        Double pctGen = p.porcentaje(p.consumidaDeGeneradores()), pctRed = p.porcentaje(p.importada());
        String[] semaforo = colores(pctRed == null ? Estado.SIN_DATO : switch (meta.zona(pctRed)) {
            case VERDE -> Estado.OK;
            case AMARILLO -> Estado.AVISO;
            case ROJO -> Estado.FUERA;
        });
        if (p.conPrincipal()) {
            h.append("<div style='display:flex;flex-wrap:wrap;gap:12px'>")
                    .append(tarjetaPlanta("Consumo de la planta", kw(consumo), "generadores + red (medidor principal)", "#4a525c"));
            PlantaEnVivoService.AntesDelArranque antes = p.antes();
            if (antes != null) {
                String cuando = antes.inicio().format(CORTA) + (antes.inicioEstimado() ? " (aprox.)" : "");
                String detalle = antes.kw() == null ? "sin lecturas del medidor principal antes del arranque del " + cuando
                        : "promedio de " + PlantaEnVivoService.MINUTOS_ANTES + " min antes del " + (antes.enCurso() ? "arranque" : "último arranque")
                        + " (" + cuando + ")" + (consumo > 0 ? String.format(Locale.ROOT, "; ahora %+,.0f kW", consumo - antes.kw()) : "");
                h.append(tarjetaPlanta("Consumo antes del arranque", antes.kw() == null ? "-" : kw(antes.kw()), detalle, "#9aa3ad"));
            }
            h.append("")
                    .append(tarjetaPlanta("Aporte de los generadores", kw(p.consumidaDeGeneradores()),
                            pctTexto(pctGen) + " del consumo" + (p.generada() > p.consumidaDeGeneradores() + 0.5
                                    ? " (generan " + kw(p.generada()) + ")" : ""), "#eb6834"))
                    .append(tarjetaPlanta("Importada de la red", kw(p.importada()),
                            "<span style='" + CHIP + "background:" + semaforo[0] + ";color:" + semaforo[1] + "'>" + pctTexto(pctRed)
                                    + " del consumo</span> meta 0 %", "#2a78d6"))
                    .append(tarjetaPlanta("Exportada a la red", kw(p.exportada()), "excedente de los generadores", "#1baf7a"))
                    .append("</div>");

            // Barra del consumo de la planta: lo que puso cada generador y lo importado de la red (100 %).
            List<String[]> tramos = new ArrayList<>(); // nombre, kW, color
            int i = 0;
            for (PlantaEnVivoService.FilaGenerador f : p.filas()) {
                if (p.contado(f)) tramos.add(new String[]{f.generador(), String.valueOf(p.aporte(f)), COLORES_GEN[i % COLORES_GEN.length]});
                i++;
            }
            tramos.add(new String[]{"Red", String.valueOf(p.importada()), "#2a78d6"});
            if (consumo > 0) {
                h.append("<div style='display:grid;gap:6px'><div style='font-weight:600'>Consumo de la planta: ").append(kw(consumo)).append("</div>")
                        .append("<div style='display:flex;height:34px;border-radius:4px;overflow:hidden;background:#e2e3e5'>");
                for (String[] t : tramos) {
                    double pct = Double.parseDouble(t[1]) / consumo * 100;
                    if (pct <= 0) continue;
                    h.append("<div style='width:").append(String.format(Locale.ROOT, "%.2f%%", pct)).append(";background:").append(t[2])
                            .append(";color:#fff;font-weight:600;font-size:13px;display:flex;align-items:center;justify-content:center;"
                                    + "white-space:nowrap;overflow:hidden'>")
                            .append(pct >= 12 ? esc(t[0]) + " " + pctTexto(pct) : "").append("</div>");
                }
                h.append("</div><div style='display:flex;flex-wrap:wrap;gap:4px 18px;font-size:13px;color:#555'>");
                for (String[] t : tramos) {
                    double v = Double.parseDouble(t[1]);
                    h.append("<span><i style='display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:6px;background:")
                            .append(t[2]).append("'></i>").append(esc(t[0])).append(": ").append(kw(v)).append(" (")
                            .append(pctTexto(v / consumo * 100)).append(")</span>");
                }
                h.append("</div></div>");
            }
        }

        h.append("</div>");
        plantaContenido.removeAll();
        plantaContenido.add(new Html(h.toString()));

        // Detalle: una fila por generador y una de la red (medidor principal); aporte y % suman el consumo de la planta.
        h.setLength(0);
        h.append("<div style='display:grid;gap:14px;width:100%;max-width:1150px'>");
        String th = "<th style='text-align:right;padding:6px 10px;font-size:12px;color:#555;border-bottom:1px solid #ccc'>";
        String thIzq = "<th style='text-align:left;padding:6px 10px;font-size:12px;color:#555;border-bottom:1px solid #ccc'>";
        String td = "<td style='text-align:right;padding:6px 10px;border-bottom:1px solid #eee;white-space:nowrap'>";
        String tdIzq = "<td style='text-align:left;padding:6px 10px;border-bottom:1px solid #eee;white-space:nowrap'>";
        boolean conRed = p.conPrincipal();
        String chipRed = "<span style='" + CHIP + "background:" + semaforo[0] + ";color:" + semaforo[1] + "'>" + pctTexto(pctRed) + "</span>";
        h.append("<div style='overflow-x:auto'><table style='border-collapse:collapse;width:100%;font-size:14px'><thead><tr>")
                .append(thIzq).append("Generador</th>").append(thIzq).append("Estado</th>").append(thIzq).append("Transformador</th>")
                .append(th).append("Generada (kW)</th>").append(th).append("Aporte en planta (kW)</th>")
                .append(th).append("% del consumo de la planta</th></tr></thead><tbody>");
        for (PlantaEnVivoService.FilaGenerador f : p.filas()) {
            String[] c = colores(switch (f.estado()) {
                case EN_MARCHA -> Estado.OK;
                case PARADO -> Estado.SIN_DATO;
                case SIN_COMUNICACION -> Estado.FUERA;
            });
            Double aporte = conRed ? p.aporte(f) : null;
            h.append("<tr>").append(tdIzq).append("<b>").append(esc(f.generador())).append("</b></td>")
                    .append(tdIzq).append("<span style='").append(CHIP).append("background:").append(c[0]).append(";color:").append(c[1])
                    .append("'>").append(f.estado().etiqueta()).append("</span></td>")
                    .append(tdIzq).append(esc(nombreRed(f.red()))).append("</td>")
                    .append(td).append(num0(f.kwGenerador())).append("</td>")
                    .append(td).append(num0(aporte)).append("</td>")
                    .append(td).append(aporte == null ? "-" : pctTexto(p.porcentaje(aporte))).append("</td></tr>");
        }
        h.append("<tr>").append(tdIzq).append("<b>Red</b> <span style='font-size:12px;color:#555'>(medidor principal)</span></td>")
                .append(tdIzq).append("</td>").append(tdIzq).append("</td>").append(td).append("</td>")
                .append(td).append(conRed ? num0(p.importada()) : "-").append("</td>")
                .append(td).append(conRed ? chipRed : "-").append("</td></tr>");
        String tdTotal = "<td style='text-align:right;padding:6px 10px;border-top:2px solid #333;font-weight:700;white-space:nowrap'>";
        String tdVacio = "<td style='border-top:2px solid #333'></td>";
        h.append("</tbody><tfoot><tr><td style='padding:6px 10px;border-top:2px solid #333;font-weight:700'>Consumo de la planta</td>")
                .append(tdVacio).append(tdVacio)
                .append(tdTotal).append(num0(p.generada())).append("</td>")
                .append(tdTotal).append(conRed ? num0(consumo) : "-").append("</td>")
                .append(tdTotal).append(conRed && consumo > 0 ? "100.0 %" : "-").append("</td></tr></tfoot></table></div>");

        // Máquinas: trabajando o paradas (criterio del horómetro), por grupo.
        List<PlantaEnVivoService.EstadoMaquina> maquinas = p.maquinas();
        if (!maquinas.isEmpty()) {
            long trabajando = maquinas.stream().filter(m -> Boolean.TRUE.equals(m.trabajando())).count();
            h.append("<div style='display:grid;gap:8px'><div style='font-weight:600'>Máquinas: ").append(trabajando).append(" de ")
                    .append(maquinas.size()).append(" trabajando</div>");
            String[][] grupos = {{PlantaEnVivoService.GRUPO_EXTRUSION, "Extrusión"},
                    {PlantaEnVivoService.GRUPO_MEZCLA, "Mezcla (mezcladores, molino y pulverizador)"},
                    {PlantaEnVivoService.GRUPO_INYECCION, "Inyección"}};
            for (String[] g : grupos) {
                List<PlantaEnVivoService.EstadoMaquina> delGrupo = maquinas.stream().filter(m -> g[0].equals(m.grupo())).toList();
                if (delGrupo.isEmpty()) continue;
                h.append("<div style='display:flex;flex-wrap:wrap;align-items:center;gap:6px'><span style='width:100%;font-size:13px;color:#555'>")
                        .append(g[1]).append("</span>");
                for (PlantaEnVivoService.EstadoMaquina m : delGrupo) {
                    String[] c = m.trabajando() == null ? new String[]{"#fff3cd", "#856404"}
                            : m.trabajando() ? colores(Estado.OK) : colores(Estado.SIN_DATO);
                    h.append("<span style='").append(CHIP).append("background:").append(c[0]).append(";color:").append(c[1]).append("'>")
                            .append(esc(m.nombre())).append(": ")
                            .append(m.trabajando() == null ? "sin dato" : (m.trabajando() ? "trabajando" : "parada"))
                            .append(m.kw() == null ? "" : String.format(Locale.ROOT, " (%,.0f kW)", m.kw())).append("</span>");
                }
                h.append("</div>");
            }
            List<String> subs = plantaService.submedidoresInyeccion();
            h.append("<div style='font-size:12px;color:#555'>Trabajando o parada con el criterio del horómetro (umbral de encendido y "
                    + "lecturas seguidas por debajo del umbral). ")
                    .append(subs.isEmpty() ? "" : "Inyección (neta) = Inyección − " + esc(String.join(" − ", subs))
                            + ", contra el umbral de Inyección.")
                    .append("</div></div>");
        }
        h.append("</div>");
        plantaDetalle.removeAll();
        plantaDetalle.add(new Html(h.toString()));
    }

    private static String tarjetaPlanta(String titulo, String valor, String detalle, String color) {
        return "<div style='flex:1 1 200px;border:1px solid #dce1e7;border-top:4px solid " + color + ";border-radius:4px;"
                + "padding:12px 14px;display:grid;gap:4px'>"
                + "<span style='font-size:12px;font-weight:600;letter-spacing:.06em;text-transform:uppercase;color:#555'>" + titulo + "</span>"
                + "<span style='font-size:26px;font-weight:700'>" + valor + "</span>"
                + "<span style='font-size:13px;color:#555'>" + detalle + "</span></div>";
    }

    private static String[] colores(Estado e) {
        return switch (e) {
            case OK -> new String[]{"#d4edda", "#155724"};
            case AVISO -> new String[]{"#fff3cd", "#856404"};
            case FUERA -> new String[]{"#f8d7da", "#721c24"};
            case SIN_DATO -> new String[]{"#e2e3e5", "#383d41"};
        };
    }

    private static String kw(double v) {
        return String.format(Locale.ROOT, "%,.0f kW", v);
    }

    private static String num0(Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%,.0f", v);
    }

    private static String pctTexto(Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%.1f %%", v);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }

    // ================= Estado actual =================

    /** Contenido de la pestaña de un generador: solo su tablero (lo que antes era Estado actual), sin nota al pie. */
    private Div crearEstado(Generador g) {
        Div tablero = new Div();
        tablero.setWidthFull();
        // Actualizar = clic en el nombre del generador, en la barra de su tablero.
        tablero.getElement().addEventListener("tg-actualizar", e -> refrescar());
        tableros.put(g, tablero);
        return tablero;
    }

    /**
     * Cada 15 s y al cambiar de pestaña: lee en vivo solo el generador de la pestaña elegida y dibuja su tablero; las
     * marcas de las pestañas de los demás salen de la lectura de fondo (cada minuto).
     */
    private void refrescar() {
        if (generador == null) return;
        // El historial se recarga solo cuando la lectura de fondo (cada minuto) guardó un evento nuevo.
        if (ultimoIdEventos() != ultimoEventoMostrado) cargarEventos();
        avisarMapas();
        Generador g = generadorDePestana();
        Map<Generador, List<String>> alarmasEnVivo = new HashMap<>();
        if (g == null) {
            mostrarAlarmas(null, null);
        } else {
            LecturaGenerador l = null;
            String error = null;
            try {
                l = service.leerEnVivo(g);
                alarmasEnVivo.put(g, l.alarmas());
            } catch (Exception ex) {
                error = ex.getMessage();
            }
            mostrarAlarmas(l == null ? g : null, historial.alarmasActivas(g.nombre()));
            // El navegador dibuja el tablero.
            Div tablero = tableros.get(g);
            try {
                tablero.getElement().executeJs(SCRIPT_TABLERO + "\nwindow.TableroGeneradores.render(this, JSON.parse($0));",
                        JSON.writeValueAsString(List.of(datosTablero(g, l, error))));
            } catch (Exception ex) {
                tablero.getElement().setText("No se pudo armar el tablero: " + ex.getMessage());
            }
        }
        marcarPestanas(alarmasEnVivo);
        avisarRed();
        if (tabSirena != null && tabs.getSelectedTab() == tabSirena) refrescarSirena();
    }

    /**
     * Advertencia amarilla arriba de las pestañas, para cualquier generador (no solo el de la pestaña): interruptor de la
     * planta abierto (isla) o falla de red según la última lectura de fondo del controlador. Pedido del usuario el
     * 27-10-2026, cuando el Caterpillar abrió por ROCOF y solo se veía en el tablero de su pestaña.
     */
    private void avisarRed() {
        avisoRed.removeAll();
        for (Generador g : generadores) {
            RedControladorService.EstadoActual e = redControlador.estadoActual(g.nombre());
            if (e == null || !(e.isla() || SenalesRed.sinRed(e.estado()) || Boolean.FALSE.equals(e.mcb()))) continue;
            String desde = e.desde() == null ? "" : " desde las " + e.desde().format(HHMM);
            String texto = e.isla() && e.selectorIsla() ? g.nombre() + ": en isla con el selector en Isla" + desde
                    + ". El generador alimenta solo su parte de la planta (" + nombreRed(g.redAsociada()) + ")."
                    : e.isla() ? g.nombre() + ": interruptor de la planta abierto, en isla" + desde
                    + ". El generador alimenta solo su parte de la planta (" + nombreRed(g.redAsociada()) + ")."
                    : SenalesRed.sinRed(e.estado()) ? g.nombre() + ": sin red según el controlador" + desde + " (" + e.nombre() + ")."
                    : g.nombre() + ": interruptor de la planta abierto (" + e.nombre() + ").";
            var icono = VaadinIcon.WARNING.create();
            icono.setColor("#B8920A");
            icono.getStyle().set("flex", "none");
            Span t = new Span(texto);
            t.getStyle().set("font-weight", "600");
            Div fila = new Div(icono, t);
            fila.getStyle().set("display", "flex").set("align-items", "center").set("gap", "8px").set("padding", "2px 0");
            avisoRed.add(fila);
        }
        avisoRed.setVisible(avisoRed.getComponentCount() > 0);
    }

    private static String leerRecurso(String ruta) {
        try (java.io.InputStream in = GeneradorView.class.getResourceAsStream(ruta)) {
            return in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /**
     * Datos de un generador para el tablero de Estado actual (los dibuja tablero-generadores.js). La red y la planta
     * salen del medidor del transformador (planta = generador + red); nombres de estados, entradas y salidas, del modelo.
     * l == null = sin comunicación (error = el motivo).
     */
    private Map<String, Object> datosTablero(Generador g, LecturaGenerador l, String error) {
        Map<String, Object> d = new LinkedHashMap<>();
        ModeloControlador m = service.modelo(g).orElse(null);
        d.put("nombre", g.nombre());
        d.put("modelo", g.modelo().replaceFirst("(?<=[A-Za-z])(?=\\d)", " "));
        d.put("trafo", nombreRed(g.redAsociada()));
        if (l == null) {
            d.put("sinComunicacion", error == null ? "" : error);
            return d;
        }
        boolean marcha = l.enMarcha();
        d.put("marcha", marcha);
        d.put("leido", l.fecha().format(HHMMSS));
        if (l.relojControlador() != null) {
            long desfase = l.desfaseReloj();
            d.put("reloj", l.relojControlador().format(HHMM));
            d.put("desfase", LecturaGenerador.textoDesfase(desfase));
            d.put("desfaseAlto", Math.abs(desfase) >= 60);
        }
        d.put("modo", m == null ? null : m.modo(l));
        d.put("listoAmf", m == null ? null : m.listoAmf(l));
        SenalesRed.Selector selector = m == null || m.red() == null ? null : m.red().selector(l);
        d.put("selector", selector == null ? null : selector.name());
        d.put("temporizador", m == null ? null : m.temporizador(l));
        d.put("temporizadorTotal", m == null ? null : m.tiempoAjustado(m.temporizador(l), l));
        Map<String, Double> v = new LinkedHashMap<>();
        l.valores().forEach((p, x) -> v.put(p.name(), x));
        d.put("v", v);
        d.put("alarmas", l.alarmas() == null ? List.of() : l.alarmas().stream().map(a -> Map.of("nombre", AlarmaComap.nombre(a),
                "activa", AlarmaComap.activa(a), "sinReset", AlarmaComap.sinConfirmar(a), "desde", desdeAlarma(g, a))).toList());
        Map<String, Object> medidor = service.ultimaRed(g);
        d.put("redKw", num(medidor.get("PW")));
        // Corrientes y PF del medidor del transformador (PF con color según los límites de Calidad de Energía).
        Double pfTrafo = num(medidor.get("PF")) == null ? null : FactorPotenciaUtil.normalizarAbs(Math.abs(num(medidor.get("PF"))));
        d.put("trafoI", java.util.Arrays.asList(num(medidor.get("IA")), num(medidor.get("IB")), num(medidor.get("IC"))));
        d.put("trafoPf", pfTrafo);
        d.put("trafoPfEstado", clase(calidad.evaluar(Indicador.FACTOR_POTENCIA, pfTrafo,
                calidad.tensionNominal(g.redAsociada() == null ? "" : g.redAsociada()), calidad.configuracion())));
        SenalesRed red = m == null ? null : m.red();
        if (red != null) {
            d.put("mcb", SenalesRed.valor(red.mcbCerrado(), l));
            d.put("gcb", SenalesRed.valor(red.gcbCerrado(), l));
            // Falla de red según el controlador (estado de los interruptores o su aviso de falla de red): torre en rojo.
            Integer codigo = SenalesRed.codigo(l);
            d.put("fallaRed", (codigo != null && red.estado(codigo) == SenalesRed.Estado.FALLA_RED)
                    || Boolean.TRUE.equals(SenalesRed.valor(red.avisoFallaRed(), l)));
            if (codigo != null) {
                SenalesRed.Estado est = red.estado(codigo);
                d.put("estadoInterruptores", codigo >= 0 && codigo < red.nombres().size() ? red.nombres().get(codigo) : "código " + codigo);
                d.put("sinRed", SenalesRed.sinRed(est));
                RedControladorService.EstadoActual previo = redControlador.estadoActual(g.nombre());
                if (SenalesRed.sinRed(est) && previo != null && previo.estado() == est && previo.desde() != null) {
                    d.put("interruptoresDesde", previo.desde().format(HHMM));
                }
            }
        }
        d.put("estadoMotor", m == null ? null : m.estadoMotor(l));
        d.put("entradas", bits(m == null ? List.of() : m.entradas(), l.valor(ParametroGenerador.ENTRADAS_BINARIAS)));
        d.put("salidas", bits(m == null ? List.of() : m.salidas(), l.valor(ParametroGenerador.SALIDAS_BINARIAS)));
        // Líneas de límite y colores de frecuencia con la tensión nominal y los límites de Calidad de Energía.
        ConfiguracionCalidad cfg = calidad.configuracion();
        double nomRed = calidad.tensionNominal(g.redAsociada() == null ? "" : g.redAsociada()), nomGen = calidad.tensionNominal(g.nombre());
        Map<String, Object> nom = new HashMap<>();
        nom.put("redLL", nomRed);
        nom.put("redLN", nomRed / Math.sqrt(3));
        nom.put("genLL", nomGen);
        nom.put("genLN", nomGen / Math.sqrt(3));
        nom.put("i", l.valor(ParametroGenerador.CORRIENTE_NOMINAL));
        nom.put("kw", l.valor(ParametroGenerador.POTENCIA_NOMINAL));
        d.put("nom", nom);
        d.put("fueraPct", cfg.getTensionFueraPct());
        d.put("hzRed", clase(calidad.evaluar(Indicador.FRECUENCIA, l.redFrecuencia(), nomRed, cfg)));
        d.put("hzGen", marcha ? clase(calidad.evaluar(Indicador.FRECUENCIA, l.frecuencia(), nomGen, cfg)) : "gris");
        d.put("mideAceite", m == null || m.registro(ParametroGenerador.PRESION_ACEITE).isPresent());
        d.put("mideTemp", m == null || m.registro(ParametroGenerador.TEMP_REFRIGERANTE).isPresent());
        d.put("umbrales", Map.of("aceiteAviso", ACEITE_AVISO, "aceiteFuera", ACEITE_FUERA, "tempAviso", TEMP_AVISO, "tempFuera", TEMP_FUERA));
        d.put("bateria", clase(estadoBateria(l.bateria(), marcha)));
        Double pf = l.pf() != null ? Double.valueOf(Math.abs(l.pf()))
                : l.kw() != null && l.kva() != null && l.kva() > 0 ? l.kw() / l.kva() : null;
        d.put("pf", marcha ? pf : null);
        return d;
    }

    /** Desde cuándo está la alarma en la lista del controlador, según el historial ("" si no se sabe). */
    private String desdeAlarma(Generador g, String alarma) {
        HistorialEventosService.Aparicion ap = historial.aparicion(g.nombre(), alarma);
        if (ap == null) return "";
        return (ap.inicioLectura() ? "desde antes del " : "desde el ") + ap.fecha().format(CORTA);
    }

    /** Nombre y valor (0/1) de cada bit de una palabra; valor null si no se leyó. */
    private static List<Map<String, Object>> bits(List<String> nombres, Double palabra) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (int i = 0; i < nombres.size(); i++) {
            Map<String, Object> b = new HashMap<>();
            b.put("n", nombres.get(i));
            b.put("v", palabra == null ? null : (int) ((palabra.longValue() >> i) & 1));
            l.add(b);
        }
        return l;
    }

    /** Clase de color del tablero para un estado de Calidad de Energía. */
    private static String clase(Estado e) {
        return switch (e) {
            case OK -> "ok";
            case AVISO -> "aviso";
            case FUERA -> "fuera";
            case SIN_DATO -> "gris";
        };
    }

    private static Estado estadoBateria(Double v, boolean marcha) {
        if (v == null) return Estado.SIN_DATO;
        if (marcha) return v < BAT_MARCHA_MIN || v > BAT_MARCHA_MAX ? Estado.AVISO : Estado.OK;
        return v < BAT_PARADO_FUERA ? Estado.FUERA : v < BAT_PARADO_AVISO ? Estado.AVISO : Estado.OK;
    }

    // ================= Registros (ADMIN) =================

    private VerticalLayout crearRegistros() {
        registrosGenerador.setItems(generadores);
        registrosGenerador.setItemLabelGenerator(Generador::nombre);
        registrosGenerador.setValue(generadores.get(0));
        registrosGenerador.setWidth("180px");
        registrosGenerador.addValueChangeListener(e -> {
            // Otro controlador: se empieza de cero (sin comparar con la lectura del anterior).
            registrosGrid.setItems(List.of());
            ultimaExploracion = List.of();
            exploracionAnterior = Map.of();
            registrosMensaje.setText("");
        });
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
            Generador g = registrosGenerador.getValue();
            if (g == null) {
                registrosMensaje.setText("Elegir el generador.");
                return;
            }
            if (d == null || n == null || d < 40001 || n < 1 || n > 300) {
                registrosMensaje.setText("Registro desde 40001 y cantidad entre 1 y 300.");
                return;
            }
            try {
                long t0 = System.currentTimeMillis();
                Map<Integer, Integer> previo = new HashMap<>();
                for (FilaRegistro f : ultimaExploracion) previo.put(f.celda().registro(), f.celda().valor());
                exploracionAnterior = previo;
                ultimaExploracion = service.explorar(g, d, n);
                registrosGrid.setItems(ultimaExploracion);
                long sinDato = ultimaExploracion.stream().filter(f -> f.celda().valor() == null).count();
                long cambiaron = ultimaExploracion.stream().filter(this::cambio).count();
                registrosMensaje.setText("Leídos " + (n - sinDato) + " de " + n + " registros en "
                        + (System.currentTimeMillis() - t0) + " ms" + (sinDato > 0 ? "; " + sinDato + " no existen en el controlador" : "")
                        + (exploracionAnterior.isEmpty() ? "" : "; " + cambiaron + " cambiaron desde la lectura anterior (amarillo)") + ".");
                guardar.setEnabled(true);
            } catch (Exception ex) {
                registrosMensaje.setText("Sin comunicación con " + g.ip() + ": " + ex.getMessage());
            }
        });
        leer.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        guardar.addClickListener(e -> {
            try {
                Path p = service.guardarFoto(registrosGenerador.getValue(), ultimaExploracion, comentario.getValue());
                Notification.show("Foto guardada en " + p, 5000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } catch (Exception ex) {
                Notification.show("No se pudo guardar la foto: " + ex.getMessage(), 5000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        HorizontalLayout filtros = new HorizontalLayout(registrosGenerador, desde, cantidad, leer, comentario, guardar);
        filtros.setAlignItems(Alignment.END);
        filtros.getStyle().set("flex-wrap", "wrap");

        registrosGrid.addColumn(f -> String.valueOf(f.celda().registro())).setHeader("Registro").setAutoWidth(true).setFlexGrow(0);
        registrosGrid.addColumn(f -> f.celda().valor() == null ? "-" : String.valueOf(f.celda().valor())).setHeader("Sin signo").setAutoWidth(true).setFlexGrow(0);
        registrosGrid.addColumn(f -> f.celda().valor() == null ? "-" : String.valueOf((short) (int) f.celda().valor())).setHeader("Con signo").setAutoWidth(true).setFlexGrow(0);
        registrosGrid.addColumn(f -> f.celda().valor() == null ? "-" : String.format("%04X", f.celda().valor())).setHeader("Hex").setAutoWidth(true).setFlexGrow(0);
        registrosGrid.addColumn(this::treintaYDos).setHeader("32 bits (con el siguiente)").setAutoWidth(true).setFlexGrow(0);
        registrosGrid.addColumn(f -> f.celda().nota() == null ? "" : f.celda().nota()).setHeader("Nota").setAutoWidth(true);
        registrosGrid.addColumn(FilaRegistro::parametro).setHeader("Parámetro según el mapa").setAutoWidth(true);
        registrosGrid.addColumn(FilaRegistro::valorMapa).setHeader("Valor según el mapa").setAutoWidth(true);
        registrosGrid.addComponentColumn(f -> {
            if (!cambio(f)) return new Span();
            Integer antes = exploracionAnterior.get(f.celda().registro());
            return badgeNuevo("antes " + (antes == null ? "-" : antes), Estado.AVISO);
        }).setHeader("Cambio").setAutoWidth(true);
        registrosGrid.setSizeFull();

        registrosMensaje.getStyle().set("font-size", "12px").set("color", "#555");
        VerticalLayout v = new VerticalLayout(filtros, registrosMensaje, registrosGrid);
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

    /**
     * Nombre para mostrar de la red de un generador: "Trafo1" = "Transformador 1"; el medidor general
     * (o sin red) = "la planta". En pantalla se habla del transformador, no de "el tablero".
     */
    static String nombreRed(String red) {
        if (red == null || red.isBlank() || MaquinasVirtuales.KWH_PLANTA_1.equals(red)) return "la planta";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Trafo(\\d+)").matcher(red);
        return m.matches() ? "Transformador " + m.group(1) : red;
    }

    /** "del Transformador 1" / "de la planta". */
    static String deRed(String red) {
        String n = nombreRed(red);
        return n.startsWith("la ") ? "de " + n : "del " + n;
    }

    /** Varias redes: "del Transformador 1", "de los transformadores 1 y 2" o "de la planta". */
    static String deRedes(List<String> redes) {
        List<String> nombres = redes.stream().map(GeneradorView::nombreRed).distinct().toList();
        if (nombres.isEmpty()) return "de la planta";
        if (nombres.size() == 1) return deRed(redes.get(0));
        if (nombres.stream().allMatch(n -> n.startsWith("Transformador "))) {
            List<String> nros = nombres.stream().map(n -> n.substring("Transformador ".length())).toList();
            return "de los transformadores " + String.join(", ", nros.subList(0, nros.size() - 1)) + " y " + nros.get(nros.size() - 1);
        }
        return "de " + String.join(" y ", nombres);
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
