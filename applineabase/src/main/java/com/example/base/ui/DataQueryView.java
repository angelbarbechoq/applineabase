package com.example.base.ui;

import com.example.dataacquisition.service.ConfigLoaderService;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@PageTitle("Consulta de Datos | LineaBase")
@Route(value = "query", layout = MainLayout.class)
public class DataQueryView extends VerticalLayout {

    private final ConfigLoaderService configLoaderService;
    private final RestTemplate restTemplate = new RestTemplate();

    private ComboBox<String> maquinaCombo;
    private Grid<Map<String, Object>> dataGrid;
    private Span mensajeSpan;
    private List<Map<String, Object>> lineas;
    private Div maquinaInfoCard;

    public DataQueryView(ConfigLoaderService configLoaderService) {
        this.configLoaderService = configLoaderService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        H3 title = new H3("Consulta de Datos PLC - Fecha Actual");
        add(title);

        lineas = configLoaderService.loadLineaIDConfig();
        List<String> maquinas = lineas.stream()
                .map(m -> (String) m.get("lineaMaquina"))
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        maquinaCombo = new ComboBox<>("Máquina");
        maquinaCombo.setItems(maquinas);
        maquinaCombo.setWidth("300px");
        maquinaCombo.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                mostrarInfoMaquina(e.getValue());
                cargarDatos(e.getValue());
            }
        });

        maquinaInfoCard = new Div();
        maquinaInfoCard.setVisible(false);

        HorizontalLayout selectorLayout = new HorizontalLayout(maquinaCombo, maquinaInfoCard);
        selectorLayout.setAlignItems(Alignment.CENTER);
        selectorLayout.setSpacing(true);
        add(selectorLayout);

        mensajeSpan = new Span();
        add(mensajeSpan);

        dataGrid = new Grid<>();
        dataGrid.setWidthFull();
        dataGrid.setHeightFull();

        dataGrid.addColumn(row -> row.get("fecha")).setHeader("Fecha").setAutoWidth(true);
        dataGrid.addColumn(row -> formatNumber(row.get("VAB"))).setHeader("VAB (V)").setAutoWidth(true);
        dataGrid.addColumn(row -> formatNumber(row.get("VAC"))).setHeader("VAC (V)").setAutoWidth(true);
        dataGrid.addColumn(row -> formatNumber(row.get("VBC"))).setHeader("VBC (V)").setAutoWidth(true);
        dataGrid.addColumn(row -> formatNumber(row.get("IA"))).setHeader("IA (A)").setAutoWidth(true);
        dataGrid.addColumn(row -> formatNumber(row.get("IB"))).setHeader("IB (A)").setAutoWidth(true);
        dataGrid.addColumn(row -> formatNumber(row.get("IC"))).setHeader("IC (A)").setAutoWidth(true);
        dataGrid.addColumn(row -> formatNumber(row.get("PW"))).setHeader("PW (kW)").setAutoWidth(true);
        dataGrid.addColumn(row -> formatNumber(row.get("PF"))).setHeader("PF").setAutoWidth(true);
        dataGrid.addColumn(row -> formatNumber(row.get("KWhR"))).setHeader("KWhR (kWh)").setAutoWidth(true);

        add(dataGrid);
        setFlexGrow(1, dataGrid);
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

    private void cargarDatos(String maquina) {
        mensajeSpan.setText("Cargando datos...");
        dataGrid.setItems(List.of());

        try {
            String baseUrl = getBaseUrl();
            String url = baseUrl + "/api/plc/today/" + maquina;
            ResponseEntity<List> response = restTemplate.getForEntity(url, List.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> datos = response.getBody();

                if (datos.isEmpty()) {
                    mensajeSpan.setText("No hay datos para esta máquina en la fecha actual");
                    dataGrid.setItems(List.of());
                } else {
                    mensajeSpan.setText("Se encontraron " + datos.size() + " registros");
                    dataGrid.setItems(datos);
                }
            } else {
                mensajeSpan.setText("Error en la consulta al servidor");
            }
        } catch (Exception e) {
            mensajeSpan.setText("Error: " + e.getMessage());
            mensajeSpan.getStyle().set("color", "red");
        }
    }

    private String formatNumber(Object valor) {
        if (valor == null) {
            return "N/A";
        }
        try {
            double numVal = Double.parseDouble(valor.toString());
            return String.format("%.2f", numVal);
        } catch (NumberFormatException e) {
            return valor.toString();
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
