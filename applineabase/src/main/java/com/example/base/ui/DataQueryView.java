package com.example.base.ui;

import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.security.LineaAccessService;
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
import jakarta.annotation.security.PermitAll;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@PageTitle("Consulta de Datos | LineaBase")
@Route(value = "query", layout = MainLayout.class)
@PermitAll
public class DataQueryView extends VerticalLayout {

    private final ConfigLoaderService configLoaderService;
    private final LineaAccessService lineaAccessService;
    private final PLCDataQueryService plcDataQueryService;

    private ComboBox<String> maquinaCombo;
    private Grid<Map<String, Object>> dataGrid;
    private Span mensajeSpan;
    private List<Map<String, Object>> lineas;
    private Div maquinaInfoCard;

    public DataQueryView(ConfigLoaderService configLoaderService, LineaAccessService lineaAccessService,
                          PLCDataQueryService plcDataQueryService) {
        this.configLoaderService = configLoaderService;
        this.lineaAccessService = lineaAccessService;
        this.plcDataQueryService = plcDataQueryService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        H3 title = new H3("Consulta de Datos PLC - Fecha Actual");
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

    private void cargarDatos(String maquina) {
        mensajeSpan.setText("Cargando datos...");
        dataGrid.setItems(List.of());

        try {
            if (!lineaAccessService.tieneAccesoAMaquina(maquina)) {
                mensajeSpan.setText("Sin acceso a esta máquina");
                return;
            }
            List<Map<String, Object>> datos = plcDataQueryService.getTodayDataByMaquina(maquina);

            if (datos.isEmpty()) {
                mensajeSpan.setText("No hay datos para esta máquina en la fecha actual");
                dataGrid.setItems(List.of());
            } else {
                mensajeSpan.setText("Se encontraron " + datos.size() + " registros");
                dataGrid.setItems(datos);
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
