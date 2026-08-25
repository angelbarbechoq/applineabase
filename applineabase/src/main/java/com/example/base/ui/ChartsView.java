package com.example.base.ui;

import com.example.alarmas.repository.AlarmaConfigRepository;
import com.example.base.model.GraficaModel;
import com.example.dataacquisition.MaquinasVirtuales;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@PageTitle("Gráficas KWh - LineaBase")
@Route(value = "grafica", layout = MainLayout.class)
@RouteAlias(value = "", layout = MainLayout.class)
@PermitAll
public class ChartsView extends VerticalLayout {
    //modificado para comit
    private final GraficaModel graficaModel;
    private final ConfigLoaderService configLoaderService;
    private final LineaAccessService lineaAccessService;
    private final PLCDataQueryService plcDataQueryService;
    private final AlarmaConfigRepository alarmaConfigRepository;

    private ComboBox<String> maquinaCombo;
    private Span mensajeSpan;
    private Div chartContainer;
    private String maquinaSeleccionada;
    private String eventSourceUrl;
    private List<Map<String, Object>> lineas;
    private Div maquinaInfoCard;
    private Div datosActualesCard;
    private Tab tabKwh;
    private boolean kwhSSECerrado;

    // --- Temperatura (TemperaturaAgua + TemperaturaAmbiente combinadas) ---
    private boolean mostrarTemperatura;
    private GraficaModel graficaTemperatura;
    private Span temperaturaMensajeSpan;
    private Tab tabTemperatura;
    private boolean temperaturaCargada;

    // --- PF general (KWhPlanta1) ---
    private boolean mostrarPFGeneral;
    private GraficaModel graficaPFGeneral;
    private Span pfGeneralMensajeSpan;
    private Tab tabPFGeneral;
    private boolean pfGeneralCargada;

    // --- Mezcladores (DTB48: PV/SV de calentamiento+enfriamiento del mezclador elegido) ---
    private boolean mostrarMezcladores;
    private GraficaModel graficaMezcladores;
    private ComboBox<String> mezcladorCombo;
    private Span mezcladorMensajeSpan;
    private Tab tabMezcladores;
    private boolean mezcladorCargado;
    private String mezcladorSeleccionado;

    public ChartsView(ConfigLoaderService configLoaderService, LineaAccessService lineaAccessService,
                       PLCDataQueryService plcDataQueryService, AlarmaConfigRepository alarmaConfigRepository) {
        this.graficaModel = new GraficaModel(1);
        this.configLoaderService = configLoaderService;
        this.lineaAccessService = lineaAccessService;
        this.plcDataQueryService = plcDataQueryService;
        this.alarmaConfigRepository = alarmaConfigRepository;
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        // Temperatura/PF general viven solo en la zona Mantenimiento (y ADMIN, que ve todo):
        // la misma regla de acceso por zona que ya aplica al resto de la app, no una nueva.
        mostrarTemperatura = lineaAccessService.tieneAccesoAMaquina(MaquinasVirtuales.TEMPERATURA_AGUA);
        mostrarPFGeneral = lineaAccessService.tieneAccesoAMaquina(MaquinasVirtuales.KWH_PLANTA_1);
        // Mezcladores: misma regla de acceso que la pantalla de configuración (ADMIN + zona Mezcla).
        mostrarMezcladores = lineaAccessService.puedeVerMezcladores();

        // crearPanelKwh() deja armado datosActualesCard (las tarjetas KWh/VAB/VAC/etc.); se
        // arma antes para poder ponerlo junto al título, en vez de junto al selector de máquina.
        VerticalLayout panelKwh = crearPanelKwh();
        HorizontalLayout encabezado = new HorizontalLayout(new H3("Gráficas"), datosActualesCard);
        encabezado.setAlignItems(Alignment.CENTER);
        encabezado.getStyle().set("flex-wrap", "wrap");
        add(encabezado);

        TabSheet tabSheet = new TabSheet();
        tabSheet.setSizeFull();
        tabKwh = tabSheet.add("KWh", panelKwh);
        if (mostrarTemperatura) {
            tabTemperatura = tabSheet.add("Temperatura", crearPanelTemperatura());
            // Arranca ya, sin esperar a que se abra la pestaña: la franja de valores en vivo
            // (datosActualesCard, en el encabezado) muestra Temp. Agua/Ambiente y su derivada
            // fuera de esta pestaña, así que necesita el stream corriendo desde el arranque. El
            // gráfico amCharts5 en sí sigue cargándose recién al abrir la pestaña (ver
            // cargarTemperaturaChart) para no inicializarlo en un contenedor oculto sin tamaño.
            iniciarSSETemperatura();
        }
        if (mostrarPFGeneral) {
            tabPFGeneral = tabSheet.add("PF general", crearPanelPFGeneral());
        }
        if (mostrarMezcladores) {
            tabMezcladores = tabSheet.add("Mezcladores", crearPanelMezcladores());
        }
        add(tabSheet);
        setFlexGrow(1, tabSheet);

        // Temperatura/PF general se cargan recién la primera vez que se seleccionan (no al
        // adjuntar la vista): si se inicializa el gráfico amCharts5 con la pestaña todavía
        // oculta, el contenedor queda sin tamaño y el gráfico se ve en blanco hasta el próximo
        // dato. Cada carga deja andando su propio SSE (ver iniciarSSETemperatura/
        // iniciarSSEPFGeneral), así que solo hace falta cargar una vez por pestaña.
        tabSheet.addSelectedChangeListener(event -> {
            Tab seleccionada = event.getSelectedTab();
            if (seleccionada == tabKwh) {
                if (kwhSSECerrado && maquinaSeleccionada != null) {
                    kwhSSECerrado = false;
                    iniciarSSE(maquinaSeleccionada);
                }
            } else if (!kwhSSECerrado && maquinaSeleccionada != null) {
                // El KWh de esta franja de la pestaña KWh no se muestra en el encabezado
                // persistente (a diferencia de Temperatura/PF general), así que no hace falta
                // mantenerlo abierto fuera de esta pestaña — mismo motivo que Mezcladores más
                // abajo, y este ya venía acumulándose sin cerrarse desde antes de mezcladores
                // (con solo 4 streams como máximo nunca llegaba a chocar contra el límite del
                // navegador; con Mezcladores sí).
                kwhSSECerrado = true;
                detenerSSE();
            }
            if (seleccionada == tabTemperatura && !temperaturaCargada) {
                temperaturaCargada = true;
                cargarTemperaturaChart();
            } else if (seleccionada == tabPFGeneral && !pfGeneralCargada) {
                pfGeneralCargada = true;
                cargarPFGeneralChart();
            } else if (seleccionada == tabMezcladores) {
                if (!mezcladorCargado) {
                    mezcladorCargado = true;
                    if (mezcladorSeleccionado != null) {
                        cargarMezcladorChart(mezcladorSeleccionado);
                    }
                } else if (mezcladorSeleccionado != null) {
                    // Ya se cargó una vez; el SSE se había cerrado al salir de la pestaña
                    // (ver más abajo), así que solo hace falta reabrirlo, no recargar el gráfico.
                    reabrirSSEMezclador();
                }
            }
            if (mostrarMezcladores && seleccionada != tabMezcladores) {
                // Mezcladores no alimenta la franja de arriba (a diferencia de Temperatura/PF
                // general), así que no hace falta mantener sus 2 EventSource abiertos fuera de
                // esta pestaña. Cerrarlos acá evita acumular conexiones SSE indefinidamente: el
                // navegador limita a ~6 conexiones simultáneas por origen (HTTP/1.1), y sumadas
                // a Temperatura (2) + PF general (1) + KWh (1), Mezcladores llevaba el total a 6
                // — dejando a Vaadin sin conexión libre para navegar (la barra azul de carga
                // quedaba parpadeando sin poder completar ningún pedido).
                cerrarSSEMezclador();
            }
        });
    }

    private VerticalLayout crearPanelKwh() {
        VerticalLayout panel = new VerticalLayout();
        panel.setSizeFull();
        panel.setPadding(false);

        lineas = lineaAccessService.getLineasPermitidas();
        List<String> maquinas = lineas.stream()
                .map(m -> (String) m.get("lineaMaquina"))
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        maquinaCombo = new ComboBox<>("Máquina");
        maquinaCombo.setItems(maquinas);
        maquinaCombo.setWidth("300px");

        maquinaInfoCard = new Div();
        maquinaInfoCard.setVisible(false);

        datosActualesCard = new Div();
        datosActualesCard.setId("datosActualesCard");
        datosActualesCard.setVisible(false);
        datosActualesCard.getStyle()
            .set("padding", "0px")
            .set("margin-bottom", "0px")
            .set("flex-wrap", "wrap");
        com.vaadin.flow.component.button.Button resetZoomBtn = new com.vaadin.flow.component.button.Button("Reset Zoom", e -> resetZoom());
        resetZoomBtn.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY);

        mensajeSpan = new Span();

        HorizontalLayout selectorLayout = new HorizontalLayout(
                maquinaCombo,
                maquinaInfoCard,
                resetZoomBtn,
                mensajeSpan
        );
        selectorLayout.setAlignItems(Alignment.CENTER);
        selectorLayout.setSpacing(true);
        selectorLayout.getStyle().set("flex-wrap", "wrap");
        panel.add(selectorLayout);

        if (!maquinas.isEmpty()) {
            maquinaCombo.setValue(maquinas.getFirst());//get(0)
            maquinaSeleccionada = maquinas.getFirst();
            mostrarInfoMaquina(maquinaSeleccionada);
            cargarDatosActuales(maquinaSeleccionada);
        }

        maquinaCombo.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                detenerSSE();
                maquinaSeleccionada = e.getValue();
                mostrarInfoMaquina(maquinaSeleccionada);
                cargarDatosActuales(maquinaSeleccionada);
                cargarDatos(maquinaSeleccionada);
                this.getUI().ifPresent(ui -> {
                    ui.access(() -> {
                        long ahora = System.currentTimeMillis();
                        actualizarTarjetaUltimoClick(ahora);
                    });
                });
            }
        });

        chartContainer = PanelGraficoUtil.agregarDivGrafico(panel, "chartdiv_industrial");
        this.addDetachListener(event ->
                this.getUI().ifPresent(ui -> TarjetasEstadoActual.mostrarUltimoClickCard(ui, false)));
        this.addAttachListener(event -> {
            this.getUI().ifPresent(ui -> TarjetasEstadoActual.mostrarUltimoClickCard(ui, true));
            actualizarTarjetaUltimoClick(System.currentTimeMillis());
        });

        return panel;
    }

    // ================= Temperatura (Agua + Ambiente) =================

    private VerticalLayout crearPanelTemperatura() {
        VerticalLayout panel = new VerticalLayout();
        panel.setSizeFull();
        panel.setPadding(false);

        graficaTemperatura = new GraficaModel(2);
        graficaTemperatura.setSeriesNames(new String[]{"Temperatura Agua", "Temperatura Ambiente"});
        graficaTemperatura.setUnidad("°C");
        // Ambiente en magenta para distinguirla de Agua a simple vista; con 2 series hace falta
        // leyenda (identidad por color, no solo por tooltip).
        graficaTemperatura.setColoresPersonalizados(new String[]{"0x29b6f6", "0xe87ba4"});
        graficaTemperatura.setMostrarLeyenda(true);

        temperaturaMensajeSpan = new Span();
        panel.add(temperaturaMensajeSpan);

        PanelGraficoUtil.agregarDivGrafico(panel, "chartdiv_temperatura");

        return panel;
    }

    /**
     * TemperaturaAgua y TemperaturaAmbiente se leen en el mismo ciclo de lectura (mismo PLC,
     * mismo loop), por eso comparten timestamp exacto y se pueden alinear directamente con
     * graficarSeriesCrudasAlineadas (misma función que usa la pestaña PF general, con N=2).
     * Se llama una sola vez (carga inicial): los puntos siguientes llegan por SSE (ver
     * iniciarSSETemperatura), sin reconstruir el gráfico de nuevo.
     */
    private void cargarTemperaturaChart() {
        try {
            List<Map<String, Object>> datosAgua = plcDataQueryService.getTodayKWhDataByMaquina(MaquinasVirtuales.TEMPERATURA_AGUA);
            List<Map<String, Object>> datosAmbiente = plcDataQueryService.getTodayKWhDataByMaquina(MaquinasVirtuales.TEMPERATURA_AMBIENTE);

            if (datosAgua.isEmpty() && datosAmbiente.isEmpty()) {
                temperaturaMensajeSpan.setText("No hay datos de temperatura para la fecha actual");
                getElement().executeJs(graficaTemperatura.getInitScript2("chartdiv_temperatura"));
            } else {
                GraficaModel.ResultadoGrafica resultado = graficaTemperatura.graficarSeriesCrudasAlineadas(
                        "chartdiv_temperatura", List.of(datosAgua, datosAmbiente),
                        new String[]{"Temperatura Agua", "Temperatura Ambiente"}, 30.0, true);
                getElement().executeJs(resultado.script());
                temperaturaMensajeSpan.setText("");
            }
        } catch (Exception e) {
            temperaturaMensajeSpan.setText("Error: " + e.getMessage());
        }
    }

    /**
     * Streams independientes por sensor (cada uno actualiza su propia serie del mismo gráfico).
     * También actualizan en vivo el texto de la franja (data-campo="temperaturaAgua"/
     * "temperaturaAmbiente"), que antes solo se pintaba una vez al cargar la vista, y la derivada
     * por minuto (data-campo="derivadaTemperaturaAgua"/"derivadaTemperaturaAmbiente") que ya viene
     * calculada por PLCDataAcquisitionService en cada evento sensorUpdate. Se llama desde el
     * constructor (no desde cargarTemperaturaChart) para que la franja del encabezado se
     * mantenga en vivo aunque el usuario nunca abra la pestaña Temperatura.
     */
    private void iniciarSSETemperatura() {
        String baseUrl = getBaseUrl();
        getElement().executeJs(construirScriptSSESerie(
                baseUrl + "/api/plc/stream/TemperaturaAgua", "sensorUpdate", "chartdiv_temperatura",
                0, "data.valor", "eventSourceTempAgua", "temperaturaAgua", null, "derivadaTemperaturaAgua"));
        getElement().executeJs(construirScriptSSESerie(
                baseUrl + "/api/plc/stream/TemperaturaAmbiente", "sensorUpdate", "chartdiv_temperatura",
                1, "data.valor", "eventSourceTempAmbiente", "temperaturaAmbiente", null, "derivadaTemperaturaAmbiente"));
    }

    // ================= PF general (KWhPlanta1) =================

    private VerticalLayout crearPanelPFGeneral() {
        VerticalLayout panel = new VerticalLayout();
        panel.setSizeFull();
        panel.setPadding(false);

        graficaPFGeneral = new GraficaModel(1);
        graficaPFGeneral.setSeriesNames(new String[]{"PF general"});
        graficaPFGeneral.setUnidad("");

        pfGeneralMensajeSpan = new Span();
        panel.add(pfGeneralMensajeSpan);

        PanelGraficoUtil.agregarDivGrafico(panel, "chartdiv_pfgeneral");

        return panel;
    }

    /**
     * El medidor principal (KWhPlanta1) reporta el PF en negativo y en escala de porcentaje
     * (ej. -85.5). Se toma en valor absoluto (misma conversión que ya usa HistoricoView, via
     * GraficaModel.toFloatAbs) y ademas se divide entre 100 para verlo en escala 0-1: a
     * diferencia de Historico (que deja la escala 0-100 a proposito), esta pestana especifica
     * la quiere en fraccion. Se llama una sola vez (carga inicial): los puntos siguientes
     * llegan por SSE (ver iniciarSSEPFGeneral), sin reconstruir el gráfico de nuevo.
     */
    private void cargarPFGeneralChart() {
        try {
            List<Map<String, Object>> datosVip = plcDataQueryService.getTodayDataByMaquina(MaquinasVirtuales.KWH_PLANTA_1);
            List<Map<String, Object>> datosPf = new ArrayList<>();
            for (Map<String, Object> fila : datosVip) {
                Float pf = GraficaModel.toFloatAbs(fila.get("PF"));
                if (pf == null) continue;
                Map<String, Object> punto = new HashMap<>();
                punto.put("fecha", fila.get("fecha"));
                punto.put("kwh", pf / 100.0);
                datosPf.add(punto);
            }

            if (datosPf.isEmpty()) {
                pfGeneralMensajeSpan.setText("No hay datos de PF para la fecha actual");
                getElement().executeJs(graficaPFGeneral.getInitScript2("chartdiv_pfgeneral"));
            } else {
                GraficaModel.ResultadoGrafica resultado = graficaPFGeneral.graficarSeriesCrudasAlineadas(
                        "chartdiv_pfgeneral", List.of(datosPf), new String[]{"PF general"}, 1.0, true);
                getElement().executeJs(resultado.script());
                pfGeneralMensajeSpan.setText("");
            }
            iniciarSSEPFGeneral();
        } catch (Exception e) {
            pfGeneralMensajeSpan.setText("Error: " + e.getMessage());
        }
    }

    /**
     * dataUpdate ya lo publica KWhDifferenceService.publicarDatosActuales para KWhPlanta1 (incluye PF).
     * También actualiza en vivo el texto "PF general" (data-campo="pfGeneral") de la franja, con
     * el mismo umbral de color que ya usa TarjetasEstadoActual/AlarmaEvaluatorService — el valor
     * que llega acá (Math.abs(data.PF) / 100) ya está en la misma escala 0-1 que ese umbral.
     */
    private void iniciarSSEPFGeneral() {
        String baseUrl = getBaseUrl();
        double umbralPF = TarjetasEstadoActual.umbralPFMinimo(alarmaConfigRepository);
        getElement().executeJs(construirScriptSSESerie(
                baseUrl + "/api/plc/stream/KWhPlanta1", "dataUpdate", "chartdiv_pfgeneral", 0,
                "(data.PF !== undefined && data.PF !== null) ? Math.abs(data.PF) / 100 : null", "eventSourcePF",
                "pfGeneral", umbralPF, null));
    }

    // ================= Mezcladores (DTB48: PV/SV calentamiento+enfriamiento) =================

    private static final String[] SERIES_MEZCLADOR = {
            "Calentamiento (PV)", "Calentamiento (SV)", "Enfriamiento (PV)", "Enfriamiento (SV)"};
    // Calentamiento en rojo oscuro, enfriamiento en celeste (mismo celeste que ya usa la
    // pestaña Temperatura para distinguir series); SV punteado del mismo color que su PV
    // (ver setSeriesDiscontinuas) en vez de un color aparte, para leerlo como "la misma
    // variable, el objetivo vs. el valor real" en lugar de una serie más a identificar.
    private static final String[] COLORES_MEZCLADOR = {"0x8b1e2f", "0x8b1e2f", "0x29b6f6", "0x29b6f6"};
    private static final boolean[] DISCONTINUAS_MEZCLADOR = {false, true, false, true};

    private VerticalLayout crearPanelMezcladores() {
        VerticalLayout panel = new VerticalLayout();
        panel.setSizeFull();
        panel.setPadding(false);

        graficaMezcladores = new GraficaModel(4);
        graficaMezcladores.setSeriesNames(SERIES_MEZCLADOR);
        graficaMezcladores.setUnidad("°C");
        graficaMezcladores.setColoresPersonalizados(COLORES_MEZCLADOR);
        graficaMezcladores.setSeriesDiscontinuas(DISCONTINUAS_MEZCLADOR);
        graficaMezcladores.setMostrarLeyenda(true);

        List<String> mezcladores = configLoaderService.loadMezcladoresConfig().stream()
                .map(m -> String.valueOf(m.get("nombre")))
                .sorted()
                .collect(Collectors.toList());

        mezcladorCombo = new ComboBox<>("Mezclador");
        mezcladorCombo.setItems(mezcladores);
        mezcladorCombo.setWidth("250px");

        mezcladorMensajeSpan = new Span();

        HorizontalLayout selectorLayout = new HorizontalLayout(mezcladorCombo, mezcladorMensajeSpan);
        selectorLayout.setAlignItems(Alignment.CENTER);
        panel.add(selectorLayout);

        if (mezcladores.isEmpty()) {
            mezcladorMensajeSpan.setText("No hay mezcladores configurados");
        } else {
            mezcladorSeleccionado = mezcladores.getFirst();
            mezcladorCombo.setValue(mezcladorSeleccionado);
        }

        mezcladorCombo.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                mezcladorSeleccionado = e.getValue();
                cargarMezcladorChart(mezcladorSeleccionado);
            }
        });

        PanelGraficoUtil.agregarDivGrafico(panel, "chartdiv_mezcladores");

        return panel;
    }

    /**
     * PV/SV de calentamiento y enfriamiento no comparten timestamp exacto con nada más (cada
     * canal del DTB48 se lee con su propia conexión Modbus, ver MezcladorReaderService), pero sí
     * entre sí sí lo suelen compartir aproximado dentro del mismo ciclo — se reusa
     * graficarSeriesCrudasAlineadas iguel que Temperatura. Cada serie se remapea a la clave
     * "kwh" porque esa función lee esa clave a fuego (mismo truco que ya usa PF general con el
     * PF de KWhPlanta1).
     */
    private void cargarMezcladorChart(String nombreMezclador) {
        try {
            String tablaCal = ConfigLoaderService.nombreTablaCanalMezclador(nombreMezclador, "Calentamiento");
            String tablaEnf = ConfigLoaderService.nombreTablaCanalMezclador(nombreMezclador, "Enfriamiento");

            List<Map<String, Object>> calPV = remapAKwh(plcDataQueryService.getTodayValorPorColumna(tablaCal, "PV"), "PV");
            List<Map<String, Object>> calSV = remapAKwh(plcDataQueryService.getTodayValorPorColumna(tablaCal, "SV"), "SV");
            List<Map<String, Object>> enfPV = remapAKwh(plcDataQueryService.getTodayValorPorColumna(tablaEnf, "PV"), "PV");
            List<Map<String, Object>> enfSV = remapAKwh(plcDataQueryService.getTodayValorPorColumna(tablaEnf, "SV"), "SV");

            if (calPV.isEmpty() && calSV.isEmpty() && enfPV.isEmpty() && enfSV.isEmpty()) {
                mezcladorMensajeSpan.setText("No hay datos de " + nombreMezclador + " para la fecha actual");
                getElement().executeJs(graficaMezcladores.getInitScript2("chartdiv_mezcladores"));
            } else {
                GraficaModel.ResultadoGrafica resultado = graficaMezcladores.graficarSeriesCrudasAlineadas(
                        "chartdiv_mezcladores", List.of(calPV, calSV, enfPV, enfSV), SERIES_MEZCLADOR, 100.0, true);
                getElement().executeJs(resultado.script());
                mezcladorMensajeSpan.setText("");
            }
            iniciarSSEMezclador(tablaCal, tablaEnf);
        } catch (Exception e) {
            mezcladorMensajeSpan.setText("Error: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> remapAKwh(List<Map<String, Object>> filas, String columnaOrigen) {
        List<Map<String, Object>> remapeadas = new ArrayList<>();
        for (Map<String, Object> fila : filas) {
            Map<String, Object> punto = new HashMap<>();
            punto.put("fecha", fila.get("fecha"));
            punto.put("kwh", fila.get(columnaOrigen));
            remapeadas.add(punto);
        }
        return remapeadas;
    }

    /**
     * Solo PV se actualiza en vivo (índices 0 y 2, los mismos que graficarSeriesCrudasAlineadas
     * les asignó): MezcladorReaderService publica SensorDataUpdateEvent con el PV de cada canal,
     * no con el SV (el setpoint no cambia solo, no hace falta empujarlo por SSE) — SV se
     * actualiza recién si el usuario vuelve a cambiar de mezclador o recarga la pestaña.
     */
    /** Reabre el SSE de mezcladores al volver a esta pestaña (se había cerrado al salir, ver
     * cerrarSSEMezclador) — no hace falta recargar el gráfico completo, los puntos que se
     * perdieron mientras estaba cerrado ya quedaron guardados y se ven al re-seleccionar el
     * mezclador o recargar la página. */
    private void reabrirSSEMezclador() {
        String tablaCal = ConfigLoaderService.nombreTablaCanalMezclador(mezcladorSeleccionado, "Calentamiento");
        String tablaEnf = ConfigLoaderService.nombreTablaCanalMezclador(mezcladorSeleccionado, "Enfriamiento");
        iniciarSSEMezclador(tablaCal, tablaEnf);
    }

    private void cerrarSSEMezclador() {
        getElement().executeJs(cerrarSSEJs("eventSourceMezcladorCal") + cerrarSSEJs("eventSourceMezcladorEnf"));
    }

    private void iniciarSSEMezclador(String tablaCal, String tablaEnf) {
        String baseUrl = getBaseUrl();
        getElement().executeJs(construirScriptSSESerie(
                baseUrl + "/api/plc/stream/" + tablaCal, "sensorUpdate", "chartdiv_mezcladores",
                0, "data.valor", "eventSourceMezcladorCal", null, null, null));
        getElement().executeJs(construirScriptSSESerie(
                baseUrl + "/api/plc/stream/" + tablaEnf, "sensorUpdate", "chartdiv_mezcladores",
                2, "data.valor", "eventSourceMezcladorEnf", null, null, null));
    }

    /**
     * JS que parsea una fecha "dd-MM-yyyy HH:mm:ss" (el formato que usa todo el proyecto) al
     * timestamp epoch que espera amCharts5, declarando `var timestamp = ...;`. expresionFecha es
     * la expresión JS que resuelve a ese string (p.ej. "data.fecha"). Antes este parseo estaba
     * escrito dos veces con sintaxis distinta en construirScriptSSESerie y en iniciarSSE.
     */
    private static String scriptParsearFechaATimestamp(String expresionFecha) {
        return
            "      var dateStr = " + expresionFecha + ".split(' ')[0]; var timeStr = " + expresionFecha + ".split(' ')[1];" +
            "      var partesFecha = dateStr.split('-'); var partesHora = timeStr.split(':');" +
            "      var timestamp = new Date(parseInt(partesFecha[2]), parseInt(partesFecha[1]) - 1, parseInt(partesFecha[0]), parseInt(partesHora[0]), parseInt(partesHora[1]), parseInt(partesHora[2])).getTime();";
    }

    /**
     * Wire-up genérico de un stream SSE que empuja un punto nuevo a UNA serie de un gráfico ya
     * inicializado (sin recargar nada), reutilizado por Temperatura (2 sensores, una serie cada
     * uno) y PF general (1 serie). expresionValorJs se evalúa contra la variable `data` ya
     * parseada del evento; si da null/NaN, ese punto se descarta sin romper el resto del stream.
     *
     * campoTexto es el data-campo (ver GraficaModel.construirHtmlValoresActuales) del span de la
     * franja de valores en vivo que hay que mantener sincronizado con el mismo punto que recibe
     * el gráfico — antes esos spans se pintaban una sola vez y quedaban desactualizados en cada
     * refresco del gráfico. Null si ese stream no tiene un texto asociado en la franja.
     * umbralPFMinimo solo aplica al campo "pfGeneral" (mismo umbral 0-1 que usa
     * TarjetasEstadoActual/AlarmaEvaluatorService); null para los demás campos, que no cambian de
     * color en vivo.
     * campoDerivada es el data-campo del span secundario de derivada (°C/h, ver
     * GraficaModel.construirHtmlValoresActuales), que lee data.derivadaPorHora — ya calculada en
     * PLCDataQueryService.calcularDerivadaPorHora, así el cliente no tiene que inferir el
     * intervalo real entre lecturas. Null para streams sin derivada asociada (PF general).
     */
    private String construirScriptSSESerie(String streamUrl, String eventoNombre, String containerId,
                                            int indiceSerie, String expresionValorJs, String varGlobal,
                                            String campoTexto, Double umbralPFMinimo, String campoDerivada) {
        String actualizarTexto = "";
        if (campoTexto != null) {
            actualizarTexto =
                "        var spanTexto = document.querySelector('#datosActualesCard [data-campo=\"" + campoTexto + "\"]');" +
                "        if (spanTexto) {" +
                "          spanTexto.textContent = valor.toFixed(2);" +
                (umbralPFMinimo != null
                        ? "          spanTexto.style.color = Math.abs(valor) < " + umbralPFMinimo + " ? '#e34948' : '#1a3c8c';"
                        : "") +
                "        }";
        }
        String actualizarDerivada = "";
        if (campoDerivada != null) {
            actualizarDerivada =
                "        var spanDerivada = document.querySelector('#datosActualesCard [data-campo=\"" + campoDerivada + "\"]');" +
                "        if (spanDerivada) {" +
                "          if (data.derivadaPorHora !== undefined && data.derivadaPorHora !== null && isFinite(data.derivadaPorHora)) {" +
                "            var d = data.derivadaPorHora;" +
                "            var flecha = d > 0 ? ' ▲' : (d < 0 ? ' ▼' : '');" +
                "            var color = d > 0 ? '#8b1e2f' : (d < 0 ? '#1a3c8c' : '#898781');" +
                "            spanDerivada.textContent = Math.abs(d).toFixed(1) + ' °C/h' + flecha;" +
                "            spanDerivada.style.color = color;" +
                "            spanDerivada.style.display = '';" +
                "          } else {" +
                "            spanDerivada.style.display = 'none';" +
                "          }" +
                "        }";
        }
        return
            "if(window." + varGlobal + ") { window." + varGlobal + ".close(); }" +
            "window." + varGlobal + " = new EventSource('" + streamUrl + "');" +
            "window." + varGlobal + ".addEventListener('" + eventoNombre + "', function(event) {" +
            "  try {" +
            "    var data = JSON.parse(event.data);" +
            "    var valor = " + expresionValorJs + ";" +
            // La franja del encabezado (texto + derivada) se actualiza siempre que llegue un valor
            // válido, sin depender de que el gráfico amCharts5 de esta pestaña esté inicializado —
            // antes esto vivía adentro del if de más abajo y por eso no se actualizaba si el
            // usuario nunca abría la pestaña con el gráfico (Temperatura/PF general).
            "    if (valor !== null && valor !== undefined && isFinite(valor)) {" +
            actualizarTexto +
            actualizarDerivada +
            "    }" +
            "    if(valor !== null && valor !== undefined && isFinite(valor) && window.am5Charts && window.am5Charts['" + containerId + "'] && window.am5Charts['" + containerId + "'].seriesList && window.am5Charts['" + containerId + "'].seriesList[" + indiceSerie + "]) {" +
            "      var inst = window.am5Charts['" + containerId + "'];" +
            scriptParsearFechaATimestamp("data.fecha") +
            "      inst.seriesList[" + indiceSerie + "].data.push({ date: timestamp, value: valor });" +
            "      inst.seriesList[" + indiceSerie + "].markDirtyValues();" +
            "      inst.aplicarZoomCalculado();" +
            "    }" +
            "  } catch(e) { console.error('Error procesando SSE:', e); }" +
            "});";
    }

    private void cargarDatos(String maquina) {
        mensajeSpan.setText("Cargando gráfica para " + maquina + "...");

        try {
            if (!lineaAccessService.tieneAccesoAMaquina(maquina)) {
                mensajeSpan.setText("Sin acceso a esta máquina");
                return;
            }
            List<Map<String, Object>> datos = plcDataQueryService.getTodayKWhDataByMaquina(maquina);

            if (datos.isEmpty()) {
                mensajeSpan.setText("No hay datos para " + maquina + " en la fecha actual");
                getElement().executeJs(graficaModel.getInitScript2("chartdiv_industrial"));
            } else {
                boolean conDiferencia = graficaModel.clasificarYFijarUnidad(maquina);
                mostrarGrafica(datos, conDiferencia, maquina);
                iniciarSSE(maquina);
            }
        } catch (Exception e) {
            mensajeSpan.setText("Error: " + e.getMessage());
            mensajeSpan.getStyle().set("color", "red");
        }
    }

    private void mostrarGrafica(List<Map<String, Object>> datos, boolean conDiferencia, String maquina) {
        try {
            if (datos.size() < (conDiferencia ? 2 : 1)) {
                mensajeSpan.setText(conDiferencia ?
                        "Se necesitan al menos 2 registros para graficar la diferencia" :
                        "No hay registros para graficar");
                return;
            }

            GraficaModel.ResultadoGrafica resultado = graficaModel.graficarSerieKWh(
                    "chartdiv_industrial", datos, conDiferencia, maquina, new String[]{"KWh"}, true);
            getElement().executeJs(resultado.script());

        } catch (Exception e) {
            e.printStackTrace();
            mensajeSpan.setText("Error al graficar: " + e.getMessage());
        }
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        // Ensure chart container ID is always fixed to prevent lifecycle issues with dispose()
        if (!chartContainer.getId().orElse("").equals("chartdiv_industrial")) {
            chartContainer.setId("chartdiv_industrial");
        }
        if (maquinaSeleccionada != null) {
            cargarDatos(maquinaSeleccionada);
        }
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        detenerSSE();
        if (mostrarTemperatura || mostrarPFGeneral || mostrarMezcladores) {
            getElement().executeJs(
                cerrarSSEJs("eventSourcePF") + cerrarSSEJs("eventSourceTempAgua") + cerrarSSEJs("eventSourceTempAmbiente") +
                cerrarSSEJs("eventSourceMezcladorCal") + cerrarSSEJs("eventSourceMezcladorEnf"));
        }
    }

    private void detenerSSE() {
        if (eventSourceUrl != null) {
            getElement().executeJs(cerrarSSEJs("eventSource"));
        }
    }

    /** JS que cierra y limpia una variable global de EventSource si existe — antes escrito a
     * mano en cada punto que necesitaba cerrar una (detenerSSE, cerrarSSEMezclador, onDetach). */
    private static String cerrarSSEJs(String varGlobal) {
        return "if(window." + varGlobal + ") { window." + varGlobal + ".close(); window." + varGlobal + " = null; }";
    }

    private String getBaseUrl() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            String scheme = request.getScheme();
            String serverName = request.getServerName();
            int port = request.getServerPort();
            String contextPath = request.getContextPath();

            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
                return scheme + "://" + serverName + contextPath;
            } else {
                return scheme + "://" + serverName + ":" + port + contextPath;
            }
        }
        return "http://localhost:8080";
    }

    private void iniciarSSE(String maquina) {
        String baseUrl = getBaseUrl();
        eventSourceUrl = baseUrl + "/api/plc/stream/" + maquina;

        getElement().executeJs(
            "if(window.eventSource) { window.eventSource.close(); }" +
            "console.log('📡 Abriendo SSE para: " + maquina + " en URL: " + eventSourceUrl + "');" +
            "window.eventSource = new EventSource('" + eventSourceUrl + "');" +
            "window.eventSource.onopen = function() {" +
            "  console.log('✅ SSE conectado para " + maquina + "');" +
            "};" +
            "window.eventSource.addEventListener('kwhUpdate', function(event) {" +
            "  console.log('🎉 Evento SSE recibido para " + maquina + ":', event.data);" +
            "  try {" +
            "    var data = JSON.parse(event.data);" +
            "    console.log('📊 Datos parseados:', data);" +
            "    if(window.am5Charts && window.am5Charts['chartdiv_industrial'] && window.am5Charts['chartdiv_industrial'].seriesList && window.am5Charts['chartdiv_industrial'].seriesList[0]) {" +
            "      var inst = window.am5Charts['chartdiv_industrial'];" +
            scriptParsearFechaATimestamp("data.fecha") +
            "      inst.seriesList[0].data.push({ date: timestamp, value: Math.abs(data.diferencia) });" +
            "      inst.seriesList[0].markDirtyValues();" +
            "      inst.aplicarZoomCalculado();" +
            "      console.log('✅ Punto agregado y renderizado:', { fecha: data.fecha, diferencia: data.diferencia, timestamp: timestamp });" +
            "    } else {" +
            "      console.warn('⚠️ chartdiv_industrial no inicializado');" +
            "    }" +
            "  } catch(e) {" +
            "    console.error('❌ Error procesando evento SSE:', e);" +
            "  }" +
            "});" +
            "window.eventSource.addEventListener('dataUpdate', function(event) {" +
            "  console.log('📊 Evento de actualización de datos recibido para " + maquina + ":', event.data);" +
            "  try {" +
            "    var data = JSON.parse(event.data);" +
            "    console.log('📈 Datos actualizados:', data);" +
            "    var tarjetasDiv = document.getElementById('datosActualesCard');" +
            "    if(tarjetasDiv) {" +
            "        var camposDato = {kwh: data.KWh, vab: data.VAB, vac: data.VAC, vbc: data.VBC, ia: data.IA, ib: data.IB, ic: data.IC, pw: data.PW, pf: data.PF};" +
            "        Object.keys(camposDato).forEach(function(campo) {" +
            "          var span = tarjetasDiv.querySelector('[data-campo=\"' + campo + '\"]');" +
            "          if (span) { span.textContent = (camposDato[campo] || 0).toFixed(2); }" +
            "        });" +
            "        console.log('✅ Tarjetas actualizadas en tiempo real');" +
            "    }" +
            "  } catch(e) {" +
            "    console.error('❌ Error procesando evento de datos:', e);" +
            "  }" +
            "});" +
            "window.eventSource.addEventListener('error', function(event) {" +
            "  console.error('❌ Error en SSE:', event);" +
            "  if(event.eventPhase === EventSource.CLOSED) {" +
            "    console.error('SSE conexión cerrada');" +
            "  }" +
            "});"
        );
    }

    private void resetZoom() {
        getElement().executeJs(graficaModel.getResetZoomScript("chartdiv_industrial"));
    }

    @ClientCallable
    public void limpiarTarjetas() {
        if (this.getParent().isPresent() && this.getParent().get() instanceof MainLayout) {
            TarjetasEstadoActual.limpiarUltimoClick((MainLayout) this.getParent().get());
        }
    }

    @ClientCallable
    public void registrarClickEnGrafica(long timestamp) {
        actualizarTarjetaUltimoClick(timestamp);
    }

    private void actualizarTarjetaUltimoClick(long timestamp) {
        if (this.getParent().isPresent() && this.getParent().get() instanceof MainLayout) {
            MainLayout layout = (MainLayout) this.getParent().get();
            TarjetasEstadoActual.actualizarUltimoClick(lineaAccessService, plcDataQueryService,
                    graficaModel, maquinaSeleccionada, layout, timestamp);
        }
    }

    /**
     * Franja de valores en vivo (KWh/VAB/VAC/etc.) junto al título — HistoricoView usa el mismo
     * helper (mismo texto, misma posición). dataUpdate (ver iniciarSSE) actualiza estos valores
     * en vivo vía la clase "dato-valor" en el orden que arma
     * GraficaModel.construirHtmlValoresActuales.
     */
    private void cargarDatosActuales(String maquina) {
        TarjetasEstadoActual.cargarDatosActuales(lineaAccessService, plcDataQueryService, alarmaConfigRepository, maquina, datosActualesCard);
    }

    private void mostrarInfoMaquina(String maquina) {
        Map<String, Object> info = lineas.stream()
                .filter(l -> maquina.equals(l.get("lineaMaquina")))
                .findFirst()
                .orElse(null);

        if (info != null) {
            maquinaInfoCard.removeAll();

            String id = info.get("id").toString();
            String nombrePLC = info.get("nombrePLC").toString();
            String numeroSerie = info.get("numeroSerie").toString();

            Span infoText = new Span(
                "ID: " + id + " | PLC: " + nombrePLC + " | Serie: " + numeroSerie
            );
            infoText.getStyle().set("font-size", "12px").set("color", "#666");

            maquinaInfoCard.add(infoText);
            maquinaInfoCard.setVisible(true);
        }
    }
}
