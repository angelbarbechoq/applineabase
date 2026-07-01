package com.example.base.ui;

import com.example.base.model.GraficaModel;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@PageTitle("Historico de Graficas | LineaBase")
@Route(value = "historico", layout = MainLayout.class)
public class HistoricoView extends VerticalLayout {

    private final GraficaModel graficaKWh = new GraficaModel(1);
    private final GraficaModel graficaVoltajes = new GraficaModel(3);
    private final GraficaModel graficaCorrientes = new GraficaModel(3);
    private final GraficaModel graficaPW = new GraficaModel(1);
    private final GraficaModel graficaPF = new GraficaModel(1);

    private GraficaModel graficaActiva;

    private final ConfigLoaderService configLoaderService;
    private final RestTemplate restTemplate = new RestTemplate();

    private ComboBox<String> maquinaCombo;
    private DatePicker desdeDate;
    private DatePicker hastaDate;
    private ComboBox<String> variableCombo;
    private Button consultarBtn;
    private Span mensajeSpan;
    private Div chartContainer;

    public HistoricoView(ConfigLoaderService configLoaderService) {
        this.configLoaderService = configLoaderService;
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        graficaKWh.setMinY(0.0);
        graficaKWh.setMaxY(50.0);
        graficaVoltajes.setMinY(0.0);
        graficaVoltajes.setMaxY(500.0);
        graficaCorrientes.setMinY(0.0);
        graficaCorrientes.setMaxY(300.0);
        graficaPW.setMinY(0.0);
        graficaPW.setMaxY(200.0);
        graficaPF.setMinY(0.0);
        graficaPF.setMaxY(1.0);

        graficaActiva = graficaKWh;

        add(new H3("Historico de Graficas"));
        add(buildFiltrosLayout());

        mensajeSpan = new Span("Seleccione los filtros y presione Consultar");
        add(mensajeSpan);

        chartContainer = new Div();
        chartContainer.setId("chartdiv_historico");
        chartContainer.setWidthFull();
        chartContainer.setHeight("500px");
        add(chartContainer);
        setFlexGrow(1, chartContainer);
    }

    private HorizontalLayout buildFiltrosLayout() {
        List<String> maquinas = configLoaderService.loadLineaIDConfig().stream()
                .map(m -> (String) m.get("lineaMaquina"))
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        maquinaCombo = new ComboBox<>("Maquina");
        maquinaCombo.setItems(maquinas);
        maquinaCombo.setWidth("200px");
        if (!maquinas.isEmpty()) maquinaCombo.setValue(maquinas.get(0));

        desdeDate = new DatePicker("Desde");
        desdeDate.setValue(LocalDate.now().minusDays(7));
        desdeDate.setWidth("160px");

        hastaDate = new DatePicker("Hasta");
        hastaDate.setValue(LocalDate.now());
        hastaDate.setWidth("160px");

        variableCombo = new ComboBox<>("Variable");
        variableCombo.setItems("KWh", "Voltajes", "Corrientes", "PW", "PF");
        variableCombo.setValue("KWh");
        variableCombo.setWidth("160px");

        consultarBtn = new Button("Consultar", e -> consultar());
        consultarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button resetZoomBtn = new Button("Reset Zoom", e -> resetZoom());
        resetZoomBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Button resetMarcadoresBtn = new Button("Reset Marcadores", e -> resetearMarcadores());
        resetMarcadoresBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout layout = new HorizontalLayout(
                maquinaCombo, desdeDate, hastaDate, variableCombo, consultarBtn, resetZoomBtn, resetMarcadoresBtn);
        layout.setAlignItems(FlexComponent.Alignment.END);
        layout.setSpacing(true);
        return layout;
    }

    private void consultar() {
        String maquina = maquinaCombo.getValue();
        LocalDate desde = desdeDate.getValue();
        LocalDate hasta = hastaDate.getValue();
        String variable = variableCombo.getValue();

        if (maquina == null || desde == null || hasta == null || variable == null) {
            mensajeSpan.setText("Complete todos los filtros");
            return;
        }
        if (desde.isAfter(hasta)) {
            mensajeSpan.setText("La fecha Desde debe ser anterior a Hasta");
            return;
        }
        long dias = java.time.temporal.ChronoUnit.DAYS.between(desde, hasta);
        if (dias > 90) {
            mensajeSpan.setText("El rango máximo permitido es 90 días");
            return;
        }

        mensajeSpan.setText("Consultando...");

        switch (variable) {
            case "KWh":
                graficaActiva = graficaKWh;
                consultarKWh(maquina, desde, hasta);
                break;
            case "Voltajes":
                graficaActiva = graficaVoltajes;
                consultarVIP(maquina, desde, hasta, "Voltajes");
                break;
            case "Corrientes":
                graficaActiva = graficaCorrientes;
                consultarVIP(maquina, desde, hasta, "Corrientes");
                break;
            case "PW":
                graficaActiva = graficaPW;
                consultarVIP(maquina, desde, hasta, "PW");
                break;
            case "PF":
                graficaActiva = graficaPF;
                consultarVIP(maquina, desde, hasta, "PF");
                break;
        }
    }
    private void consultarKWh(String maquina, LocalDate desde, LocalDate hasta) {
        try {
            String url = getBaseUrl() + "/api/plc/historico/kwh/" + maquina
                    + "?desde=" + desde + "&hasta=" + hasta;
            ResponseEntity<List> resp = restTemplate.getForEntity(url, List.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> datos = resp.getBody();

                // CONDICIONANTE: elegir si es con diferencia o sin diferencia
                boolean conDiferencia = true;
                if ((maquina.contains("Temperatura") || maquina.contains("Psi") || maquina.contains("BarCompHP"))) {
                    conDiferencia = false;

                    // Setear unidad según máquina
                    if (maquina.contains("Temperatura")) {
                        graficaKWh.setUnidad("°C");
                    } else if (maquina.contains("PSI")) {
                        graficaKWh.setUnidad("PSI");
                    } else if (maquina.contains("BAR")) {
                        graficaKWh.setUnidad("BAR");
                    }
                } else {
                    graficaKWh.setUnidad("KWh");
                }

                if (datos.size() < (conDiferencia ? 2 : 1)) {
                    mensajeSpan.setText(conDiferencia ?
                            "Insuficientes datos para calcular diferencias" :
                            "No hay registros para graficar");
                    graficaKWh.setSeriesNames(new String[]{"Datos"});
                    getElement().executeJs(graficaKWh.getInitScript2("chartdiv_historico"));
                    return;
                }

                graficaKWh.setSeriesNames(new String[]{"Datos"});
                double maxValor = 0;
                SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
                StringBuilder batchScript = new StringBuilder();
                batchScript.append(graficaKWh.getInitScript2("chartdiv_historico"));

                int puntosValidos = 0;

                if (conDiferencia) {
                    // CON DIFERENCIA
                    for (int i = 1; i < datos.size(); i++) {
                        try {
                            double actual = ((Number) datos.get(i).get("kwh")).doubleValue();
                            double anterior = ((Number) datos.get(i - 1).get("kwh")).doubleValue();
                            double dif = actual - anterior;
                            if (dif < 0) dif = 0;
                            maxValor = Math.max(maxValor, dif);

                            long ts = sdf.parse((String) datos.get(i).get("fecha")).getTime();
                            Float[] values = {(float) dif};
                            batchScript.append(graficaKWh.getAddDataScript("chartdiv_historico", ts, values, false));
                            puntosValidos++;
                        } catch (Exception ignored) {}
                    }
                } else {
                    // SIN DIFERENCIA (valor directo)
                    for (Map<String, Object> row : datos) {
                        try {
                            double valor = ((Number) row.get("kwh")).doubleValue();
                            maxValor = Math.max(maxValor, valor);

                            long ts = sdf.parse((String) row.get("fecha")).getTime();
                            Float[] values = {(float) valor};
                            batchScript.append(graficaKWh.getAddDataScript("chartdiv_historico", ts, values, false));
                            puntosValidos++;
                        } catch (Exception ignored) {}
                    }
                }
                graficaKWh.setMinY(0.0);
                //graficaKWh.setMaxY(maxValor * 1.1 == 0 ? 10.0 : maxValor * 1.1);
                //ChartsView.aplicarRangosPredefinidos(maquina);
                getElement().executeJs(batchScript.toString());
                mensajeSpan.setText(puntosValidos + " puntos graficados");
            }
        } catch (Exception e) {
            mensajeSpan.setText("Error: " + e.getMessage());
        }
    }
    private void consultarKWh2(String maquina, LocalDate desde, LocalDate hasta) {
        try {
            String url = getBaseUrl() + "/api/plc/historico/kwh/" + maquina
                    + "?desde=" + desde + "&hasta=" + hasta;
            ResponseEntity<List> resp = restTemplate.getForEntity(url, List.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> datos = resp.getBody();

                if (datos.size() < 2) {
                    mensajeSpan.setText("Insuficientes datos para calcular diferencias");
                    graficaKWh.setSeriesNames(new String[]{"KWh"});
                    getElement().executeJs(graficaKWh.getInitScript2("chartdiv_historico"));
                    return;
                }

                graficaKWh.setSeriesNames(new String[]{"KWh"});
                double maxDif = 0;
                SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
                StringBuilder batchScript = new StringBuilder();
                batchScript.append(graficaKWh.getInitScript2("chartdiv_historico"));

                int puntosValidos = 0;
                for (int i = 1; i < datos.size(); i++) {
                    try {
                        double actual = ((Number) datos.get(i).get("kwh")).doubleValue();
                        double anterior = ((Number) datos.get(i - 1).get("kwh")).doubleValue();
                        double dif = actual - anterior;
                        if (dif < 0) dif = 0;
                        maxDif = Math.max(maxDif, dif);

                        long ts = sdf.parse((String) datos.get(i).get("fecha")).getTime();
                        Float[] values = {(float) dif};
                        batchScript.append(graficaKWh.getAddDataScript("chartdiv_historico", ts, values, false));
                        puntosValidos++;
                    } catch (Exception ignored) {}
                }

                graficaKWh.setMaxY(maxDif * 1.1 == 0 ? 10.0 : maxDif * 1.1);
                getElement().executeJs(batchScript.toString());
                mensajeSpan.setText(puntosValidos + " puntos graficados");
            }
        } catch (Exception e) {
            mensajeSpan.setText("Error: " + e.getMessage());
        }
    }

    private void consultarVIP(String maquina, LocalDate desde, LocalDate hasta, String tipoVar) {
        try {
            String url = getBaseUrl() + "/api/plc/historico/vip/" + maquina
                    + "?desde=" + desde + "&hasta=" + hasta;
            ResponseEntity<List> resp = restTemplate.getForEntity(url, List.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> datos = resp.getBody();

                if (datos.isEmpty()) {
                    mensajeSpan.setText("No hay datos en el rango seleccionado");
                    getElement().executeJs(graficaActiva.getInitScript2("chartdiv_historico"));
                    return;
                }

                // Setear nombres de series según variable
                if (tipoVar.equals("Voltajes")) {
                    graficaVoltajes.setSeriesNames(new String[]{"VAB", "VAC", "VBC"});
                } else if (tipoVar.equals("Corrientes")) {
                    graficaCorrientes.setSeriesNames(new String[]{"IA", "IB", "IC"});
                } else if (tipoVar.equals("PW")) {
                    graficaPW.setSeriesNames(new String[]{"PW"});
                } else if (tipoVar.equals("PF")) {
                    graficaPF.setSeriesNames(new String[]{"PF"});
                }

                double maxVal = 0;
                SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
                StringBuilder batchScript = new StringBuilder();
                batchScript.append(graficaActiva.getInitScript2("chartdiv_historico"));

                for (Map<String, Object> row : datos) {
                    try {
                        Float[] values = extractValues(row, tipoVar);
                        for (Float v : values) {
                            if (v != null && v > 0) maxVal = Math.max(maxVal, v);
                        }
                        long ts = sdf.parse((String) row.get("fecha")).getTime();
                        batchScript.append(graficaActiva.getAddDataScript("chartdiv_historico", ts, values, false));
                    } catch (Exception ignored) {}
                }

                // Establecer maxY dinámico
                if (tipoVar.equals("Voltajes")) {
                    graficaVoltajes.setMaxY(maxVal * 1.1 > 0 ? maxVal * 1.1 : 500.0);
                } else if (tipoVar.equals("Corrientes")) {
                    graficaCorrientes.setMaxY(maxVal * 1.1 > 0 ? maxVal * 1.1 : 300.0);
                } else if (tipoVar.equals("PW")) {
                    graficaPW.setMaxY(maxVal * 1.1 > 0 ? maxVal * 1.1 : 200.0);
                } else if (tipoVar.equals("PF")) {
                    graficaPF.setMaxY(1.0);
                }

                getElement().executeJs(batchScript.toString());
                mensajeSpan.setText(datos.size() + " puntos graficados para " + tipoVar);
            }
        } catch (Exception e) {
            mensajeSpan.setText("Error: " + e.getMessage());
        }
    }

    private Float[] extractValues(Map<String, Object> row, String tipoVar) {
        return switch (tipoVar) {
            case "Voltajes" -> new Float[]{
                toFloat(row.get("VAB")), toFloat(row.get("VAC")), toFloat(row.get("VBC"))
            };
            case "Corrientes" -> new Float[]{
                toFloat(row.get("IA")), toFloat(row.get("IB")), toFloat(row.get("IC"))
            };
            case "PW" -> new Float[]{toFloat(row.get("PW"))};
            case "PF" -> new Float[]{toFloat(row.get("PF"))};
            default -> new Float[]{0f};
        };
    }

    private Float toFloat(Object v) {
        if (v == null) return 0f;
        try {
            return ((Number) v).floatValue();
        } catch (Exception e) {
            return 0f;
        }
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        getElement().executeJs(graficaActiva.getInitScript2("chartdiv_historico"));
    }

    private void resetZoom() {
        getElement().executeJs("if(window.am5Charts && window.am5Charts['chartdiv_historico']) {" +
                "  var inst = window.am5Charts['chartdiv_historico'];" +
                "  inst.chart.zoomOut();" +
                "  console.log('🔄 Zoom reseteado');" +
                "}");
    }

    private void resetearMarcadores() {
        graficaActiva.resetearMarcadores();
        getElement().executeJs("if(window.am5Charts && window.am5Charts['chartdiv_historico']) {" +
                "  var inst = window.am5Charts['chartdiv_historico'];" +
                "  inst.tiemposMarcadores = [];" +
                "  inst.posY = 0;" +
                "  if(inst.seriesList && inst.seriesList[0]) {" +
                "    while(inst.seriesList[0].bullets.length > 0) {" +
                "      inst.seriesList[0].bullets.pop().dispose();" +
                "    }" +
                "  }" +
                "  console.log('🗑️ Marcadores eliminados');" +
                "}");
    }

    private String getBaseUrl() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest req = attrs.getRequest();
            String scheme = req.getScheme();
            String host = req.getServerName();
            int port = req.getServerPort();
            String ctx = req.getContextPath();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443))
                return scheme + "://" + host + ctx;
            return scheme + "://" + host + ":" + port + ctx;
        }
        return "http://localhost:8080";
    }
}
