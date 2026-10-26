package com.example.generador.ui;

import com.example.base.ui.ChartsView;
import com.example.base.ui.MainLayout;
import com.example.calidad.model.ConfiguracionCalidad;
import com.example.calidad.model.Indicador;
import com.example.calidad.service.CalidadEnergiaService;
import com.example.calidad.service.CalidadEnergiaService.Estado;
import com.example.dataacquisition.MaquinasVirtuales;
import com.example.generador.model.EventoGenerador;
import com.example.generador.model.Generador;
import com.example.generador.model.LecturaGenerador;
import com.example.generador.model.MetaImportacionRed;
import com.example.generador.model.TipoEventoGenerador;
import com.example.generador.service.AlarmaComap;
import com.example.generador.service.HistorialEventosService;
import com.example.generador.service.RedControladorService;
import com.example.generador.model.SenalesRed;
import com.example.generador.service.PlantaEnVivoService;
import com.example.generador.model.ModeloControlador;
import com.example.generador.model.ParametroGenerador;
import com.example.generador.service.GeneradorService;
import com.example.generador.service.GeneradorService.FilaRegistro;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
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
    private Generador generador;
    private final TabSheet tabs = new TabSheet();
    private Tab tabPlanta;
    private final Div plantaContenido = new Div();
    private final Div plantaDetalle = new Div();
    private final Span plantaHora = new Span();

    private final Span descripcion = new Span();
    private final Span avisoMapa = new Span();
    /** Aviso de alarmas del controlador, arriba de las pestañas (se ve en todas). */
    private final Div alertaAlarmas = new Div();
    private final ComboBox<Generador> selector = new ComboBox<>();
    /** Opción "Todos" del selector: los generadores juntos (no es un generador real). */
    private static final Generador TODOS = new Generador("Todos", "", "", 0, null, "");
    private Tab tabRegistros;
    private Tab tabEstado;
    /** Estado actual: el tablero de cada generador elegido (los dos con "Todos", Transformador 1 primero). */
    private final Div estadoContenido = new Div();
    /** Generador de cada fila del Historial de eventos (sus registros no lo traen); se ve con "Todos". */
    private final Map<EventoGenerador, String> generadorDeEvento = new IdentityHashMap<>();
    private Grid.Column<EventoGenerador> colEventoGenerador;
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
                         RedControladorService redControlador) {
        this.service = service;
        this.calidad = calidad;
        this.lineaAccessService = lineaAccessService;
        this.historial = historial;
        this.plantaService = plantaService;
        this.redControlador = redControlador;
        List<Generador> lista = service.generadores();
        this.generador = lista.isEmpty() ? null : lista.get(0);

        setSizeFull();
        setPadding(true);
        setSpacing(true);
        if (generador == null) {
            add(new H3("Generadores"), new Span("No hay generadores en C:\\LineaBaseX\\config\\generador-config.json"));
            return;
        }

        // Con más de un generador, primero "Todos": Estado actual e Historial de eventos de todos juntos.
        List<Generador> opciones = new ArrayList<>();
        if (lista.size() > 1) opciones.add(TODOS);
        opciones.addAll(lista);
        selector.setItems(opciones);
        // El que tiene alarmas en el controlador se marca en la lista, para verlo desde la pantalla de otro.
        selector.setItemLabelGenerator(g -> g == TODOS
                ? (lista.stream().anyMatch(x -> !historial.alarmasActivas(x.nombre()).isEmpty()) ? "Todos (ALARMA)" : "Todos")
                : historial.alarmasActivas(g.nombre()).isEmpty() ? g.nombre() : g.nombre() + " (ALARMA)");
        selector.setValue(generador);
        selector.setWidth("220px");
        selector.addValueChangeListener(e -> {
            if (e.getValue() != null && e.isFromClient()) {
                generador = e.getValue();
                cambiarGenerador();
            }
        });
        H3 titulo = new H3("Generadores");
        titulo.getStyle().set("margin", "0");
        descripcion.getStyle().set("font-size", "12px").set("color", "#555");
        HorizontalLayout cabecera = new HorizontalLayout(titulo, selector, descripcion);
        cabecera.setAlignItems(Alignment.CENTER);
        avisoMapa.getStyle().set("background-color", "#fff3cd").set("color", "#856404").set("padding", "6px 10px")
                .set("border-radius", "6px").set("font-size", "13px");

        tabs.setSizeFull();
        // Primero lo global: la planta completa en vivo (todos los generadores y la red), después cada generador.
        tabPlanta = tabs.add("Balance energetico P1", crearPlanta());
        tabEstado = tabs.add("Estado actual", crearEstado());
        tabs.add("Historial de eventos", crearEventos());
        if (lineaAccessService.esAdmin()) {
            tabRegistros = tabs.add("Registros", crearRegistros());
        }
        tabs.addSelectedChangeListener(e -> {
            if (e.getSelectedTab() == tabPlanta) refrescarPlanta();
            if (e.getSelectedTab() == tabEstado) refrescar();
            mostrarSelector();
        });
        alertaAlarmas.setWidthFull();
        alertaAlarmas.setVisible(false);
        add(cabecera, avisoMapa, alertaAlarmas, tabs);
        setFlexGrow(1, tabs);
        cambiarGenerador();
        refrescarPlanta();
        mostrarSelector();
    }

    /** Balance energético P1 muestra siempre todos los generadores: ahí el selector no cambia nada y se oculta. */
    private void mostrarSelector() {
        boolean visible = tabs.getSelectedTab() != tabPlanta;
        selector.setVisible(visible);
        descripcion.setVisible(visible);
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

    private boolean todos() {
        return generador == TODOS;
    }

    /** El generador elegido, o todos con "Todos". */
    private List<Generador> elegidos() {
        return todos() ? service.generadores() : List.of(generador);
    }

    private void cambiarGenerador() {
        if (todos()) {
            descripcion.setText("Todos los generadores: " + service.generadores().stream().map(g -> g.nombre() + " (" + g.modelo()
                    + (g.redAsociada() == null ? "" : ", red " + g.redAsociada()) + ")").collect(Collectors.joining(", ")) + ".");
        } else {
            descripcion.setText(generador.descripcion() + " IP " + generador.ip() + ", modelo " + generador.modelo()
                    + (generador.redAsociada() == null ? "" : ", red " + generador.redAsociada()) + ".");
        }
        List<String> avisos = new ArrayList<>();
        for (Generador g : elegidos()) {
            ModeloControlador m = service.modelo(g).orElse(null);
            String de = todos() ? g.nombre() + ": " : "";
            if (m == null) {
                avisos.add(de + "el modelo " + g.modelo() + " no esta en C:\\LineaBaseX\\config\\generador-modelos.json: no se lee.");
            } else if (!m.confirmado()) {
                avisos.add(de + "mapa de registros de " + m.modelo() + " sin confirmar: los valores pueden no corresponder. "
                        + "No se guarda historial ni arranques hasta confirmarlo comparando con la pantalla del controlador.");
            }
        }
        avisoMapa.setText(String.join("   |   ", avisos));
        avisoMapa.setVisible(!avisos.isEmpty());
        // Registros (exploración del mapa) es de a un controlador: con "Todos" se oculta.
        if (tabRegistros != null) {
            if (todos() && tabs.getSelectedTab() == tabRegistros) tabs.setSelectedTab(tabPlanta);
            tabRegistros.setVisible(!todos());
        }
        colEventoGenerador.setVisible(todos());
        registrosGrid.setItems(List.of());
        ultimaExploracion = List.of();
        exploracionAnterior = Map.of();
        registrosMensaje.setText("");
        cargarEventos();
        refrescar();
    }

    // ================= Historial de eventos =================

    private VerticalLayout crearEventos() {
        eventosPeriodo.setItems(1, 7, 30, 90, 365);
        eventosPeriodo.setItemLabelGenerator(d -> d == 1 ? "Ultimas 24 h" : d == 365 ? "Ultimo anio" : "Ultimos " + d + " dias");
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
        colEventoGenerador.setVisible(false);
        eventosGrid.addColumn(ev -> ev.id() == null ? "" : String.valueOf(ev.id())).setHeader("Nro").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> ev.fecha().format(HORA)).setHeader("Fecha y hora").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addComponentColumn(ev -> badgeNuevo(ev.tipo().etiqueta(), estadoDe(ev.tipo()))).setHeader("Evento").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> ev.descripcion() == null ? "" : ev.descripcion()).setHeader("Descripcion").setAutoWidth(true);
        eventosGrid.addColumn(ev -> fmt(ev.rpm(), "%.0f")).setHeader("RPM").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.kw(), "%.0f")).setHeader("kW").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.pf(), "%.2f")).setHeader("PF").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.frecuencia(), "%.1f")).setHeader("Hz gen").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.vGen(), "%.0f")).setHeader("V gen").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.iGen(), "%.0f")).setHeader("I gen").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.redFrecuencia(), "%.1f")).setHeader("Hz red").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.vRed(), "%.0f")).setHeader("V red").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.bateria(), "%.1f")).setHeader("Bateria").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.tempRefrigerante(), "%.0f")).setHeader("Temp").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> fmt(ev.horasMarcha(), "%.1f")).setHeader("Horas").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> ev.kwh() == null ? "-" : String.format(Locale.ROOT, "%,d", ev.kwh())).setHeader("kWh").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.addColumn(ev -> ev.arranques() == null ? "-" : String.valueOf(ev.arranques())).setHeader("Arranques").setAutoWidth(true).setFlexGrow(0);
        eventosGrid.setSizeFull();

        eventosMensaje.getStyle().set("font-size", "12px").set("color", "#555");
        Span nota = nota("Historial armado por la app, como el historial del controlador ComAp: cada evento con los valores de ese momento. "
                + "Se detecta leyendo el controlador cada minuto (solo lectura): arranque y parada (un arranque de menos de 1 minuto "
                + "se detecta por el contador de arranques), toma de carga y vacio (" + (int) com.example.generador.service.GeneradorAnalisisService.KW_VACIO
                + " kW), falla y retorno de red (tension de red del controlador), alarmas de la lista de alarmas del controlador, y "
                + "comunicacion perdida o recuperada. \"Inicio de lectura\" marca cuando arranco la app (antes de eso, hueco). "
                + "El historial interno del controlador (350 eventos) no se puede leer por Modbus sin escribirle: se ve en el controlador o con InteliConfig. "
                + "Los eventos \"Controlador: ...\" se copiaron de ese historial (pantalla History): la descripcion lleva el motivo de ComAp y la "
                + "hora del controlador, y la fecha es la hora real (la del controlador corregida por el desfase de su reloj, aprox.). "
                + "Protecciones de red, aperturas de interruptor y la isla quedan ahi aunque duren menos de un segundo.");
        VerticalLayout v = new VerticalLayout(filtros, eventosMensaje, eventosGrid, nota);
        v.setSizeFull();
        v.setPadding(false);
        v.setFlexGrow(1, eventosGrid);
        return v;
    }

    /** Último evento guardado de los generadores elegidos (si cambia, hay eventos nuevos y se recarga la grilla). */
    private long ultimoIdEventos() {
        long suma = 0;
        for (Generador g : elegidos()) suma += historial.ultimoId(g.nombre());
        return suma;
    }

    private void cargarEventos() {
        if (generador == null) return;
        int dias = eventosPeriodo.getValue() == null ? 30 : eventosPeriodo.getValue();
        LocalDateTime hasta = LocalDateTime.now();
        ultimoEventoMostrado = ultimoIdEventos();
        generadorDeEvento.clear();
        List<EventoGenerador> lista = new ArrayList<>();
        for (Generador g : elegidos()) {
            for (EventoGenerador ev : historial.listar(g.nombre(), hasta.minusDays(dias), hasta, eventosTipo.getValue())) {
                lista.add(ev);
                generadorDeEvento.put(ev, g.nombre());
            }
        }
        // Con "Todos", los de todos los generadores juntos, del más nuevo al más viejo.
        if (todos()) lista.sort(java.util.Comparator.comparing(EventoGenerador::fecha).reversed());
        eventos = lista;
        eventosGrid.setItems(eventos);
        eventosMensaje.setText(eventos.size() + " eventos de " + (todos() ? "todos los generadores" : generador.nombre())
                + (eventos.isEmpty() ? ". El historial empieza a llenarse desde que se activo esta version." : "."));
    }

    private java.io.InputStream csvEventos() {
        StringBuilder sb = new StringBuilder("﻿Generador;Nro;Fecha y hora;Evento;Descripcion;RPM;kW;PF;Hz gen;V gen;I gen;Hz red;V red;Bateria;Temp;Horas;kWh;Arranques\n");
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
     * Aviso rojo con las alarmas de la lista del controlador de cada generador elegido (texto tal como lo envía, desde
     * cuándo y qué significa) y, con un solo generador elegido, una línea con los otros que tengan alarmas.
     *
     * @param alarmas por generador; ultimaConocida = los que no tuvieron comunicación (se muestra la última lectura de fondo)
     */
    private void mostrarAlarmas(Map<Generador, List<String>> alarmas, java.util.Set<Generador> ultimaConocida) {
        alertaAlarmas.removeAll();
        selector.getDataProvider().refreshAll();
        boolean hay = false;
        for (Map.Entry<Generador, List<String>> e : alarmas.entrySet()) {
            hay |= agregarAlarmas(e.getKey(), e.getValue(), ultimaConocida.contains(e.getKey()));
        }
        List<String> otros = todos() ? List.of() : service.generadores().stream()
                .filter(g -> !g.nombre().equals(generador.nombre()) && !historial.alarmasActivas(g.nombre()).isEmpty())
                .map(g -> g.nombre() + ": " + historial.alarmasActivas(g.nombre()).stream()
                        .map(AlarmaComap::nombre).collect(Collectors.joining(", "))).toList();
        if (!otros.isEmpty()) {
            Span s = new Span("Otro generador con alarma: " + String.join("   |   ", otros) + " (elijalo en la lista para ver el detalle).");
            s.getStyle().set("background-color", "#fff3cd").set("color", "#856404").set("padding", "6px 10px").set("border-radius", "6px")
                    .set("font-size", "13px").set("display", "block").set("margin-top", hay ? "6px" : "0");
            alertaAlarmas.add(s);
        }
        alertaAlarmas.setVisible(hay || !otros.isEmpty());
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
                + (ultimaConocida ? " - ultima lectura conocida, sin comunicacion ahora" : ""));
        titulo.getStyle().set("font-weight", "700").set("font-size", "15px");
        Div cabeza = new Div(icono, titulo);
        cabeza.getStyle().set("display", "flex").set("align-items", "center");
        caja.add(cabeza);
        for (String a : alarmas) {
            HistorialEventosService.Aparicion ap = historial.aparicion(g.nombre(), a);
            String desde = ap == null ? "" : ap.inicioLectura()
                    ? ", desde antes del " + ap.fecha().format(CORTA) + " (cuando la app empezo a leer)"
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
                + "\"*\" la que todavia no tiene reset. Todo queda en el Historial de eventos: cuando aparece, se activa de nuevo, deja "
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

        Span nota = new Span("Cada generador se lee en vivo de su controlador (solo lectura); la red es el medidor principal de la "
                + "planta 1 (de la fabrica, mide en paralelo con el de la empresa electrica; PLC, ultima lectura de la base de datos), "
                + "asi que entre uno y otro puede haber hasta un minuto. Consumo de la planta = lo que los generadores pusieron en la "
                + "planta + lo importado de la red (suman 100 %); lo exportado a la red es el excedente de los generadores y va aparte, "
                + "repartido entre los generadores segun lo que genera cada uno. En la tabla, aporte y % del consumo de la planta "
                + "(con la fila Red) suman el 100 %. Todo en kW. Se actualiza cada 15 s.");
        nota.getStyle().set("font-size", "12px").set("color", "#555");
        VerticalLayout v = new VerticalLayout(barra, plantaContenido, plantaDetalle, nota);
        v.setPadding(false);
        return v;
    }

    /** La planta ahora: primero el total (consumo, generadores, red) y después una fila por generador. */
    private void refrescarPlanta() {
        PlantaEnVivoService.Planta p = plantaService.leer();
        MetaImportacionRed meta = service.metaImportacionRed();
        plantaHora.setText("Leido " + p.leido().format(HORA));
        StringBuilder h = new StringBuilder("<div style='display:grid;gap:14px;width:100%;max-width:1150px'>");

        String aviso = "<div style='background:#fff3cd;color:#856404;padding:8px 12px;border-radius:6px;font-size:13px'>";
        if (!p.conPrincipal()) {
            h.append(aviso).append("Medidor principal sin lectura reciente")
                    .append(p.principal() == null || p.principal().fecha() == null ? "" : " (ultima: " + p.principal().fecha().format(CORTA) + ")")
                    .append(": sin consumo de la planta ni red hasta la proxima lectura.</div>");
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
                        : "promedio de " + PlantaEnVivoService.MINUTOS_ANTES + " min antes del " + (antes.enCurso() ? "arranque" : "ultimo arranque")
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
            h.append("<div style='display:grid;gap:8px'><div style='font-weight:600'>Maquinas: ").append(trabajando).append(" de ")
                    .append(maquinas.size()).append(" trabajando</div>");
            String[][] grupos = {{PlantaEnVivoService.GRUPO_EXTRUSION, "Extrusion"},
                    {PlantaEnVivoService.GRUPO_MEZCLA, "Mezcla (mezcladores, molino y pulverizador)"},
                    {PlantaEnVivoService.GRUPO_INYECCION, "Inyeccion"}};
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
            h.append("<div style='font-size:12px;color:#555'>Trabajando o parada con el criterio del horometro (umbral de encendido y "
                    + "lecturas seguidas por debajo del umbral). ")
                    .append(subs.isEmpty() ? "" : "Inyeccion (neta) = Inyeccion − " + esc(String.join(" − ", subs))
                            + ", contra el umbral de Inyeccion.")
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

    private VerticalLayout crearEstado() {
        Button actualizar = new Button("Actualizar", VaadinIcon.REFRESH.create(), e -> refrescar());
        estadoContenido.setWidthFull();
        Span nota = new Span("Leido en vivo del controlador cada 15 s (solo lectura: el modo se muestra, no se cambia desde la app). "
                + "Red y planta segun el medidor del transformador (ultimo minuto); planta = generador + red. "
                + "Lineas de limite de tension y color de la frecuencia con los limites de Calidad de Energia; bateria, aceite y "
                + "temperatura con limites provisorios (sistema de 24 V). Con Todos, un tablero por generador.");
        nota.getStyle().set("font-size", "12px").set("color", "#555");
        VerticalLayout v = new VerticalLayout(actualizar, estadoContenido, nota);
        v.setPadding(false);
        return v;
    }

    private void refrescar() {
        if (generador == null) return;
        List<Generador> gens = elegidos();
        // El historial se recarga solo cuando la lectura de fondo (cada minuto) guardó un evento nuevo.
        if (ultimoIdEventos() != ultimoEventoMostrado) cargarEventos();
        Map<Generador, List<String>> alarmas = new LinkedHashMap<>();
        java.util.Set<Generador> sinComunicacion = new java.util.HashSet<>();
        List<Map<String, Object>> tableros = new ArrayList<>();
        // Transformador 1 primero.
        for (Generador g : gens.stream().sorted(java.util.Comparator.comparing((Generador x) -> nombreRed(x.redAsociada()))).toList()) {
            LecturaGenerador l = null;
            String error = null;
            try {
                l = service.leerEnVivo(g);
            } catch (Exception ex) {
                error = ex.getMessage();
                sinComunicacion.add(g);
            }
            alarmas.put(g, l == null ? historial.alarmasActivas(g.nombre()) : l.alarmas());
            tableros.add(datosTablero(g, l, error));
        }
        mostrarAlarmas(alarmas, sinComunicacion);
        // El navegador dibuja el tablero; solo con la pestaña a la vista (al elegirla se dibuja de nuevo).
        if (tabs.getSelectedTab() == tabEstado) {
            try {
                estadoContenido.getElement().executeJs(SCRIPT_TABLERO + "\nwindow.TableroGeneradores.render(this, JSON.parse($0));",
                        JSON.writeValueAsString(tableros));
            } catch (Exception ex) {
                estadoContenido.getElement().setText("No se pudo armar el tablero: " + ex.getMessage());
            }
        }
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
        Map<String, Double> v = new LinkedHashMap<>();
        l.valores().forEach((p, x) -> v.put(p.name(), x));
        d.put("v", v);
        d.put("alarmas", l.alarmas() == null ? List.of() : l.alarmas().stream().map(a -> Map.of("nombre", AlarmaComap.nombre(a),
                "activa", AlarmaComap.activa(a), "sinReset", AlarmaComap.sinConfirmar(a))).toList());
        d.put("redKw", num(service.ultimaRed(g).get("PW")));
        SenalesRed red = m == null ? null : m.red();
        if (red != null) {
            d.put("mcb", SenalesRed.valor(red.mcbCerrado(), l));
            d.put("gcb", SenalesRed.valor(red.gcbCerrado(), l));
            Integer codigo = SenalesRed.codigo(l);
            if (codigo != null) {
                SenalesRed.Estado est = red.estado(codigo);
                d.put("estadoInterruptores", codigo >= 0 && codigo < red.nombres().size() ? red.nombres().get(codigo) : "codigo " + codigo);
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

    private static Span nota(String t) {
        Span s = new Span(t);
        s.getStyle().set("font-size", "11px").set("color", "#777");
        return s;
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
