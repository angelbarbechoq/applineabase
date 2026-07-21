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
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.text.SimpleDateFormat;
import java.util.Date;
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

    public ChartsView(ConfigLoaderService configLoaderService, LineaAccessService lineaAccessService,
                       PLCDataQueryService plcDataQueryService) {
        this.graficaModel = new GraficaModel(1);
        this.configLoaderService = configLoaderService;
        this.lineaAccessService = lineaAccessService;
        this.plcDataQueryService = plcDataQueryService;
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        H3 title = new H3("Gráfica de KWh - Fecha Actual");
        add(title);

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


//        this.addAttachListener(event -> {
//            com.vaadin.flow.component.UI.getCurrent().getChildren()
//                    .filter(c -> c instanceof com.vaadin.flow.component.applayout.AppLayout)
//                    .findFirst()
//                    .ifPresent(layout -> {
//                        ((com.vaadin.flow.component.applayout.AppLayout) layout).addToNavbar(ultimoClickCard);
//                    });
//        });
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
        add(selectorLayout);

        mensajeSpan = new Span();
        add(mensajeSpan);

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

        add(chartContainer);
        setFlexGrow(1, chartContainer);
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

            GraficaModel.SerieKWh serie = GraficaModel.calcularSerieKWh(datos, conDiferencia);

            // Se reemplazan los atípicos (p.ej. por una falla de comunicación) por una
            // interpolación de sus vecinos, para que ni se grafiquen ni inflen la escala.
            List<Float> valoresLimpios = GraficaModel.limpiarAtipicos(serie.valores(), GraficaModel.FACTOR_ATIPICO);

            // Configuración de la gráfica
            graficaModel.setSeriesNames(new String[]{"KWh"});
            graficaModel.setMinY(0.0);
            graficaModel.aplicarRangosPredefinidos(maquina);
            // El preset actúa como piso; si los datos reales lo superan, se amplía el eje.
            // Se usa el percentil 95 en vez del máximo crudo como margen adicional de seguridad.
            double p95 = GraficaModel.percentil(valoresLimpios, 0.95);
            double maxConMargen = p95 * 1.1;
            if (maxConMargen > graficaModel.getMaxY()) {
                graficaModel.setMaxY(maxConMargen);
            }

            // Construcción del script batch
            StringBuilder jsBuilder = new StringBuilder();
            jsBuilder.append(graficaModel.getInitScript2("chartdiv_industrial"));

            for (int i = 0; i < serie.timestamps().size(); i++) {
                Float[] values = {valoresLimpios.get(i)};
                jsBuilder.append(graficaModel.getAddDataScript(
                        "chartdiv_industrial", serie.timestamps().get(i), values, true));
            }
            jsBuilder.append(graficaModel.getAplicarZoomInicialScript("chartdiv_industrial"));
            // Ejecución en una sola llamada
            getElement().executeJs(jsBuilder.toString());

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
            "      inst.seriesList[0].data.push({ date: timestamp, value: data.diferencia });" +
            "      inst.seriesList[0].markDirtyValues();" +
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

            String htmlVacio = "<div style='" +
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
                    "    <div style='font-size: 11px; font-weight: bold; color: #ffffff;'>00-00-00</div>" +
                    "  </div>" +

                    "  <div style='text-align: center;'>" +
                    "    <div style='font-size: 9px; opacity: 0.7; color: #b0b0b0;'>Hora</div>" +
                    "    <div style='font-size: 11px; font-weight: bold; color: #ffffff;'>00:00:00</div>" +
                    "  </div>" +

                    "  <div style='text-align: center;'>" +
                    "    <div style='font-size: 9px; opacity: 0.7; color: #b0b0b0;'>KWh</div>" +
                    "    <div style='font-size: 11px; font-weight: bold; color: #ffffff;'>0.0</div>" +
                    "  </div>" +

                    "</div>";

            layout.getUltimoClickCard().getElement().setProperty("innerHTML", htmlVacio);
            layout.getClickAnteriorCard().getElement().setProperty("innerHTML", htmlVacio);
        }
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

        List<Long> marcadores = graficaModel.obtenerMarcadores();
        String deltaStr = "";
        if (marcadores.size() > 1) {
            int ultimoIdx = marcadores.size() - 1;
            int penultimoIdx = ultimoIdx - 1;
            deltaStr = graficaModel.obtenerTiempoTranscurrido(penultimoIdx, ultimoIdx);
        }

        StringBuilder htmlBuilder = new StringBuilder();
        htmlBuilder.append("<div style='" +
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
                "  </div>");
/*
        if (!deltaStr.isEmpty()) {
            htmlBuilder.append("  <div style='text-align: center;'>" +
                    "    <div style='font-size: 9px; opacity: 0.7; color: #b0b0b0;'>Δ</div>" +
                    "    <div style='font-size: 11px; font-weight: bold; color: #ffd700;'>" + deltaStr + "</div>" +
                    "  </div>");
        }
*/
        final String html = htmlBuilder.append("</div>").toString();

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
