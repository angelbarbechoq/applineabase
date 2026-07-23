package com.example.base.ui;

import com.example.base.model.GraficaModel;
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
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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

    private ComboBox<String> maquinaCombo;
    private Span mensajeSpan;
    private Div chartContainer;
    private String maquinaSeleccionada;
    private String eventSourceUrl;
    private List<Map<String, Object>> lineas;
    private Div maquinaInfoCard;
    private Div datosActualesCard;
    private Div ultimoClickCard;

    // --- Temperatura (TemperaturaAgua + TemperaturaAmbiente combinadas) ---
    private boolean mostrarTemperatura;
    private GraficaModel graficaTemperatura;
    private Span temperaturaMensajeSpan;

    // --- PF general (KWhPlanta1) ---
    private boolean mostrarPFGeneral;
    private GraficaModel graficaPFGeneral;
    private Span pfGeneralMensajeSpan;

    public ChartsView(ConfigLoaderService configLoaderService, LineaAccessService lineaAccessService,
                       PLCDataQueryService plcDataQueryService) {
        this.graficaModel = new GraficaModel(1);
        this.configLoaderService = configLoaderService;
        this.lineaAccessService = lineaAccessService;
        this.plcDataQueryService = plcDataQueryService;
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Gráficas"));

        // Temperatura/PF general viven solo en la zona Mantenimiento (y ADMIN, que ve todo):
        // la misma regla de acceso por zona que ya aplica al resto de la app, no una nueva.
        mostrarTemperatura = lineaAccessService.tieneAccesoAMaquina("TemperaturaAgua");
        mostrarPFGeneral = lineaAccessService.tieneAccesoAMaquina("KWhPlanta1");

        TabSheet tabSheet = new TabSheet();
        tabSheet.setSizeFull();
        tabSheet.add("KWh", crearPanelKwh());
        if (mostrarTemperatura) {
            tabSheet.add("Temperatura", crearPanelTemperatura());
        }
        if (mostrarPFGeneral) {
            tabSheet.add("PF general", crearPanelPFGeneral());
        }
        add(tabSheet);
        setFlexGrow(1, tabSheet);

        addAttachListener(event -> {
            // Cada carga inicial deja andando su propio SSE (ver iniciarSSETemperatura/
            // iniciarSSEPFGeneral): no hace falta un poll que reconstruya todo el gráfico
            // cada tanto, los puntos nuevos llegan solos y se agregan sin recargar nada.
            if (mostrarTemperatura) {
                cargarTemperaturaChart();
            }
            if (mostrarPFGeneral) {
                cargarPFGeneralChart();
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
        datosActualesCard.setVisible(false);
        datosActualesCard.getStyle()
            .set("padding", "0px")
            .set("margin-bottom", "0px")
            .set("flex-wrap", "wrap");
        //para ver los datos al hacer click.
        ultimoClickCard = new Div();
        ultimoClickCard.setVisible(true);
        ultimoClickCard.getStyle()
                .set("padding", "0px")
                .set("margin-bottom", "0px")
                .set("margin-left", "16px")
                .set("margin-right", "auto");

        com.vaadin.flow.component.button.Button resetZoomBtn = new com.vaadin.flow.component.button.Button("Reset Zoom", e -> resetZoom());
        resetZoomBtn.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout selectorLayout = new HorizontalLayout(
                maquinaCombo,
                maquinaInfoCard,
                datosActualesCard,
                resetZoomBtn
        );
        selectorLayout.setAlignItems(Alignment.CENTER);
        selectorLayout.setSpacing(true);
        selectorLayout.getStyle().set("flex-wrap", "wrap");
        panel.add(selectorLayout);

        mensajeSpan = new Span();
        panel.add(mensajeSpan);

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

        chartContainer = new Div();
        chartContainer.setId("chartdiv_industrial");
        chartContainer.setWidthFull();
        chartContainer.setHeight("500px");

        panel.add(chartContainer);
        panel.setFlexGrow(1, chartContainer);
        this.addDetachListener(event -> {
            this.getUI().ifPresent(ui -> {
                ui.getChildren()
                        .filter(c -> c instanceof MainLayout)
                        .findFirst()
                        .ifPresent(layout -> {
                            ((MainLayout) layout).getUltimoClickCard().setVisible(false);
                        });
            });
        });
        this.addAttachListener(event -> {
            // Hacer visible la tarjeta
            this.getUI().ifPresent(ui -> {
                ui.getChildren()
                        .filter(c -> c instanceof MainLayout)
                        .findFirst()
                        .ifPresent(layout -> {
                            ((MainLayout) layout).getUltimoClickCard().setVisible(true);
                        });
            });

            long ahora = System.currentTimeMillis();
            actualizarTarjetaUltimoClick(ahora);
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

        temperaturaMensajeSpan = new Span();
        panel.add(temperaturaMensajeSpan);

        Div temperaturaChartContainer = new Div();
        temperaturaChartContainer.setId("chartdiv_temperatura");
        temperaturaChartContainer.setWidthFull();
        temperaturaChartContainer.setHeight("500px");
        panel.add(temperaturaChartContainer);
        panel.setFlexGrow(1, temperaturaChartContainer);

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
            List<Map<String, Object>> datosAgua = plcDataQueryService.getTodayKWhDataByMaquina("TemperaturaAgua");
            List<Map<String, Object>> datosAmbiente = plcDataQueryService.getTodayKWhDataByMaquina("TemperaturaAmbiente");

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
            iniciarSSETemperatura();
        } catch (Exception e) {
            temperaturaMensajeSpan.setText("Error: " + e.getMessage());
        }
    }

    /** Streams independientes por sensor (cada uno actualiza su propia serie del mismo gráfico). */
    private void iniciarSSETemperatura() {
        String baseUrl = getBaseUrl();
        getElement().executeJs(construirScriptSSESerie(
                baseUrl + "/api/plc/stream/TemperaturaAgua", "sensorUpdate", "chartdiv_temperatura",
                0, "data.valor", "eventSourceTempAgua"));
        getElement().executeJs(construirScriptSSESerie(
                baseUrl + "/api/plc/stream/TemperaturaAmbiente", "sensorUpdate", "chartdiv_temperatura",
                1, "data.valor", "eventSourceTempAmbiente"));
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

        Div pfGeneralChartContainer = new Div();
        pfGeneralChartContainer.setId("chartdiv_pfgeneral");
        pfGeneralChartContainer.setWidthFull();
        pfGeneralChartContainer.setHeight("500px");
        panel.add(pfGeneralChartContainer);
        panel.setFlexGrow(1, pfGeneralChartContainer);

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
            List<Map<String, Object>> datosVip = plcDataQueryService.getTodayDataByMaquina("KWhPlanta1");
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

    /** dataUpdate ya lo publica KWhDifferenceService.publicarDatosActuales para KWhPlanta1 (incluye PF). */
    private void iniciarSSEPFGeneral() {
        String baseUrl = getBaseUrl();
        getElement().executeJs(construirScriptSSESerie(
                baseUrl + "/api/plc/stream/KWhPlanta1", "dataUpdate", "chartdiv_pfgeneral", 0,
                "(data.PF !== undefined && data.PF !== null) ? Math.abs(data.PF) / 100 : null", "eventSourcePF"));
    }

    /**
     * Wire-up genérico de un stream SSE que empuja un punto nuevo a UNA serie de un gráfico ya
     * inicializado (sin recargar nada), reutilizado por Temperatura (2 sensores, una serie cada
     * uno) y PF general (1 serie). expresionValorJs se evalúa contra la variable `data` ya
     * parseada del evento; si da null/NaN, ese punto se descarta sin romper el resto del stream.
     */
    private String construirScriptSSESerie(String streamUrl, String eventoNombre, String containerId,
                                            int indiceSerie, String expresionValorJs, String varGlobal) {
        return
            "if(window." + varGlobal + ") { window." + varGlobal + ".close(); }" +
            "window." + varGlobal + " = new EventSource('" + streamUrl + "');" +
            "window." + varGlobal + ".addEventListener('" + eventoNombre + "', function(event) {" +
            "  try {" +
            "    var data = JSON.parse(event.data);" +
            "    if(window.am5Charts && window.am5Charts['" + containerId + "'] && window.am5Charts['" + containerId + "'].seriesList && window.am5Charts['" + containerId + "'].seriesList[" + indiceSerie + "]) {" +
            "      var inst = window.am5Charts['" + containerId + "'];" +
            "      var dateStr = data.fecha.split(' ')[0]; var timeStr = data.fecha.split(' ')[1];" +
            "      var partesFecha = dateStr.split('-'); var partesHora = timeStr.split(':');" +
            "      var timestamp = new Date(parseInt(partesFecha[2]), parseInt(partesFecha[1]) - 1, parseInt(partesFecha[0]), parseInt(partesHora[0]), parseInt(partesHora[1]), parseInt(partesHora[2])).getTime();" +
            "      var valor = " + expresionValorJs + ";" +
            "      if (valor !== null && valor !== undefined && isFinite(valor)) {" +
            "        inst.seriesList[" + indiceSerie + "].data.push({ date: timestamp, value: valor });" +
            "        inst.seriesList[" + indiceSerie + "].markDirtyValues();" +
            "        inst.aplicarZoomCalculado();" +
            "      }" +
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
        if (mostrarTemperatura || mostrarPFGeneral) {
            getElement().executeJs(
                "if(window.eventSourcePF) { window.eventSourcePF.close(); window.eventSourcePF = null; }" +
                "if(window.eventSourceTempAgua) { window.eventSourceTempAgua.close(); window.eventSourceTempAgua = null; }" +
                "if(window.eventSourceTempAmbiente) { window.eventSourceTempAmbiente.close(); window.eventSourceTempAmbiente = null; }");
        }
    }

    private void detenerSSE() {
        if (eventSourceUrl != null) {
            getElement().executeJs("if(window.eventSource) { window.eventSource.close(); window.eventSource = null; }");
        }
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
            "      var dateStr = data.fecha.split(' ')[0]; var timeStr = data.fecha.split(' ')[1];" +
            "      var [day, month, year] = dateStr.split('-'); var [hour, min, sec] = timeStr.split(':');" +
            "      var timestamp = new Date(parseInt(year), parseInt(month)-1, parseInt(day), parseInt(hour), parseInt(min), parseInt(sec)).getTime();" +
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
            "    var tarjetasDiv = document.querySelector('[id^=\"datosActualesCard\"]') || document.querySelector('div[style*=\"display: flex\"][style*=\"gap: 9px\"]');" +
            "    if(tarjetasDiv) {" +
            "      var tarjetas = tarjetasDiv.querySelectorAll('div[style*=\"background: linear-gradient\"]');" +
            "      if(tarjetas.length > 0) {" +
            "        tarjetas[0].querySelectorAll('div')[1].textContent = (data.KWh || 0).toFixed(2);" +
            "        tarjetas[1].querySelectorAll('div')[1].textContent = (data.VAB || 0).toFixed(2);" +
            "        tarjetas[2].querySelectorAll('div')[1].textContent = (data.VAC || 0).toFixed(2);" +
            "        tarjetas[3].querySelectorAll('div')[1].textContent = (data.VBC || 0).toFixed(2);" +
            "        tarjetas[4].querySelectorAll('div')[1].textContent = (data.IA || 0).toFixed(2);" +
            "        tarjetas[5].querySelectorAll('div')[1].textContent = (data.IB || 0).toFixed(2);" +
            "        tarjetas[6].querySelectorAll('div')[1].textContent = (data.IC || 0).toFixed(2);" +
            "        tarjetas[7].querySelectorAll('div')[1].textContent = (data.PW || 0).toFixed(2);" +
            "        tarjetas[8].querySelectorAll('div')[1].textContent = (data.PF || 0).toFixed(2);" +
            "        console.log('✅ Tarjetas actualizadas en tiempo real');" +
            "      }" +
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
            MainLayout layout = (MainLayout) this.getParent().get();

            String htmlVacio = construirTarjetaHtml("00-00-00", "00:00:00", "0.0");

            layout.getUltimoClickCard().getElement().setProperty("innerHTML", htmlVacio);
            layout.getClickAnteriorCard().getElement().setProperty("innerHTML", htmlVacio);
        }
    }

    /** Tarjeta HTML de Fecha/Hora/KWh, usada tanto para el estado vacío como con datos reales. */
    private String construirTarjetaHtml(String fechaStr, String horaStr, String valorStr) {
        return "<div style='" +
                "background: #4a4a4a; " +
                "border-radius: 8px; " +
                "padding: 10px 14px; " +
                "color: #ffffff; " +
                "display: flex; " +
                "gap: 12px; " +
                "align-items: center; " +
                "font-size: 12px; " +
                "'>" +

                "  <div style='text-align: center;'>" +
                "    <div style='font-size: 9px; opacity: 0.7; color: #b0b0b0;'>Fecha</div>" +
                "    <div style='font-size: 11px; font-weight: bold; color: #ffffff;'>" + fechaStr + "</div>" +
                "  </div>" +

                "  <div style='text-align: center;'>" +
                "    <div style='font-size: 9px; opacity: 0.7; color: #b0b0b0;'>Hora</div>" +
                "    <div style='font-size: 11px; font-weight: bold; color: #ffffff;'>" + horaStr + "</div>" +
                "  </div>" +

                "  <div style='text-align: center;'>" +
                "    <div style='font-size: 9px; opacity: 0.7; color: #b0b0b0;'>KWh</div>" +
                "    <div style='font-size: 11px; font-weight: bold; color: #ffffff;'>" + valorStr + "</div>" +
                "  </div>" +

                "</div>";
    }

    @ClientCallable
    public void registrarClickEnGrafica(long timestamp) {
        graficaModel.registrarClick(timestamp);
        // Actualizar tarjeta con el nuevo click
        actualizarTarjetaUltimoClick(timestamp);
    }

    private void actualizarTarjetaUltimoClick(long timestamp) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
        Date fecha = new Date(timestamp);

        String fechaStr = dateFormat.format(fecha);
        String horaStr = timeFormat.format(fecha);

        // Obtener KWh directamente del servicio
        String valorStr = "";
        try {
            if (lineaAccessService.tieneAccesoAMaquina(maquinaSeleccionada)) {
                Map<String, Object> data = plcDataQueryService.getKWhByFechaExacta(maquinaSeleccionada, fechaStr + " " + horaStr);
                if (data.containsKey("kwh")) {
                    valorStr = String.format("%.2f", data.get("kwh"));
                }
            }
        } catch (Exception e) {
            valorStr = "Error";
        }

        final String html = construirTarjetaHtml(fechaStr, horaStr, valorStr);

        if (this.getParent().isPresent() && this.getParent().get() instanceof MainLayout) {
            MainLayout layout = (MainLayout) this.getParent().get();

            // Guardar HTML anterior
            String htmlAnterior = layout.getUltimoClickCard().getElement().getProperty("innerHTML");

            // Pasar a tarjeta anterior
            layout.getClickAnteriorCard().getElement().setProperty("innerHTML", htmlAnterior);

            // Actualizar tarjeta actual
            layout.getUltimoClickCard().getElement().setProperty("innerHTML", html);
        }
    }

    private void cargarDatosActuales(String maquina) {
        try {
            if (!lineaAccessService.tieneAccesoAMaquina(maquina)) {
                datosActualesCard.setVisible(false);
                return;
            }

            Map<String, Object> datosVIP = plcDataQueryService.getLatestVIPDataByMaquina(maquina);
            Map<String, Object> datosKWh = plcDataQueryService.getLatestKWhDataByMaquina(maquina);

            if (!datosVIP.containsKey("error") && !datosKWh.containsKey("error")) {
                mostrarTarjetaDatos(datosVIP, datosKWh);
            } else {
                datosActualesCard.setVisible(false);
            }
        } catch (Exception e) {
            datosActualesCard.setVisible(false);
        }
    }

    private void mostrarTarjetaDatos(Map<String, Object> datosVIP, Map<String, Object> datosKWh) {
        datosActualesCard.removeAll();

        String[] labels = {"KWh", "VAB", "VAC", "VBC", "IA", "IB", "IC", "PW", "PF"};
        double[] valores = {
            formatearNumero(datosKWh.get("kwh")),
            formatearNumero(datosVIP.get("VAB")),
            formatearNumero(datosVIP.get("VAC")),
            formatearNumero(datosVIP.get("VBC")),
            formatearNumero(datosVIP.get("IA")),
            formatearNumero(datosVIP.get("IB")),
            formatearNumero(datosVIP.get("IC")),
            formatearNumero(datosVIP.get("PW")),
            formatearNumero(datosVIP.get("PF"))
        };

        String[] colores = {
            "linear-gradient(135deg, #5a5a5a 0%, #3d3d3d 100%)",  // Gris oscuro - KWh
            "linear-gradient(135deg, #4a4a4a 0%, #2d2d2d 100%)",  // Gris oscuro - VAB
            "linear-gradient(135deg, #4a4a4a 0%, #2d2d2d 100%)",  // Gris oscuro - VAC
            "linear-gradient(135deg, #4a4a4a 0%, #2d2d2d 100%)",  // Gris oscuro - VBC
            "linear-gradient(135deg, #a8a8a8 0%, #8a8a8a 100%)",  // Gris claro - IA
            "linear-gradient(135deg, #a8a8a8 0%, #8a8a8a 100%)",  // Gris claro - IB
            "linear-gradient(135deg, #a8a8a8 0%, #8a8a8a 100%)",  // Gris claro - IC
            "linear-gradient(135deg, #5a5a5a 0%, #3d3d3d 100%)",  // Gris oscuro - PW
            "linear-gradient(135deg, #5a5a5a 0%, #3d3d3d 100%)"   // Gris oscuro - PF
        };

        StringBuilder html = new StringBuilder();
        html.append("<div style='display: flex; gap: 9px; flex-wrap: wrap; align-items: center;'>");

        for (int i = 0; i < labels.length; i++) {
            html.append("<div style='")
                .append("background: ").append(colores[i]).append("; ")
                .append("border-radius: 7px; ")
                .append("padding: 10px 14px; ")
                .append("color: white; ")
                .append("text-align: center; ")
                .append("box-shadow: 0 2px 4px rgba(0,0,0,0.1); ")
                .append("'>");

            html.append("<div style='font-size: 10px; opacity: 0.9; margin-bottom: 3px;'>")
                .append(labels[i])
                .append("</div>");

            html.append("<div style='font-size: 14px; font-weight: bold;'>")
                .append(String.format("%.2f", valores[i]))
                .append("</div>");

            html.append("</div>");
        }

        html.append("</div>");

        datosActualesCard.getElement().setProperty("innerHTML", html.toString());
        datosActualesCard.setVisible(true);
    }

    private double formatearNumero(Object valor) {
        if (valor == null) return 0.0;
        try {
            return Double.parseDouble(valor.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
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
