package com.example.base.ui;

import com.example.base.model.GraficaModel;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.DetachEvent;
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
import jakarta.annotation.security.PermitAll;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@PageTitle("Historico de Graficas | LineaBase")
@Route(value = "historico", layout = MainLayout.class)
@PermitAll
public class HistoricoView extends VerticalLayout {

    // Pisos por defecto del eje Y: el eje se amplía si los datos reales los superan
    private static final double KWH_MAX_Y_DEFAULT = 50.0;
    private static final double VOLTAJES_MAX_Y_DEFAULT = 500.0;
    private static final double CORRIENTES_MAX_Y_DEFAULT = 300.0;
    private static final double PW_MAX_Y_DEFAULT = 200.0;
    // El medidor reporta el factor de potencia en escala de porcentaje (ej. -95.96), no
    // como fracción 0-1: el piso debe cubrir esa escala real, igual que las demás variables.
    private static final double PF_MAX_Y_DEFAULT = 100.0;

    private final GraficaModel graficaKWh = new GraficaModel(1);
    private final GraficaModel graficaVoltajes = new GraficaModel(3);
    private final GraficaModel graficaCorrientes = new GraficaModel(3);
    private final GraficaModel graficaPW = new GraficaModel(1);
    private final GraficaModel graficaPF = new GraficaModel(1);

    private GraficaModel graficaActiva;

    private final ConfigLoaderService configLoaderService;
    private final LineaAccessService lineaAccessService;
    private final PLCDataQueryService plcDataQueryService;

    private ComboBox<String> maquinaCombo;
    private DatePicker desdeDate;
    private DatePicker hastaDate;
    private ComboBox<String> variableCombo;
    private Button consultarBtn;
    private Span mensajeSpan;
    private Div chartContainer;
    private Div datosActualesCard;

    public HistoricoView(ConfigLoaderService configLoaderService, LineaAccessService lineaAccessService,
                          PLCDataQueryService plcDataQueryService) {
        this.configLoaderService = configLoaderService;
        this.lineaAccessService = lineaAccessService;
        this.plcDataQueryService = plcDataQueryService;
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        graficaKWh.setMinY(0.0);
        graficaKWh.setMaxY(KWH_MAX_Y_DEFAULT);
        graficaVoltajes.setMinY(0.0);
        graficaVoltajes.setMaxY(VOLTAJES_MAX_Y_DEFAULT);
        graficaCorrientes.setMinY(0.0);
        graficaCorrientes.setMaxY(CORRIENTES_MAX_Y_DEFAULT);
        graficaPW.setMinY(0.0);
        graficaPW.setMaxY(PW_MAX_Y_DEFAULT);
        graficaPF.setMinY(0.0);
        graficaPF.setMaxY(PF_MAX_Y_DEFAULT);

        graficaActiva = graficaKWh;

        datosActualesCard = new Div();
        datosActualesCard.setVisible(false);

        HorizontalLayout encabezado = new HorizontalLayout(new H3("Historico de Graficas"), datosActualesCard);
        encabezado.setAlignItems(FlexComponent.Alignment.CENTER);
        encabezado.getStyle().set("flex-wrap", "wrap");
        add(encabezado);

        add(buildFiltrosLayout());

        mensajeSpan = new Span("Seleccione los filtros y presione Consultar");
        add(mensajeSpan);

        chartContainer = new Div();
        chartContainer.setId("chartdiv_historico");
        chartContainer.setWidthFull();
        chartContainer.setHeight("500px");
        add(chartContainer);
        setFlexGrow(1, chartContainer);

        if (maquinaCombo.getValue() != null) {
            cargarDatosActuales(maquinaCombo.getValue());
        }
    }

    private HorizontalLayout buildFiltrosLayout() {
        List<String> maquinas = lineaAccessService.getMaquinasPermitidas();

        maquinaCombo = new ComboBox<>("Maquina");
        maquinaCombo.setItems(maquinas);
        maquinaCombo.setWidth("200px");
        if (!maquinas.isEmpty()) maquinaCombo.setValue(maquinas.get(0));
        maquinaCombo.addValueChangeListener(e -> {
            if (e.getValue() != null) cargarDatosActuales(e.getValue());
        });

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

        HorizontalLayout layout = new HorizontalLayout(
                maquinaCombo, desdeDate, hastaDate, variableCombo, consultarBtn, resetZoomBtn);
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
            if (!lineaAccessService.tieneAccesoAMaquina(maquina)) {
                mensajeSpan.setText("Sin acceso a esta máquina");
                return;
            }
            List<Map<String, Object>> datos = plcDataQueryService.getHistoricoKWhByRango(maquina, desde, hasta);

            boolean conDiferencia = graficaKWh.clasificarYFijarUnidad(maquina);

            if (datos.size() < (conDiferencia ? 2 : 1)) {
                mensajeSpan.setText(conDiferencia ?
                        "Insuficientes datos para calcular diferencias" :
                        "No hay registros para graficar");
                graficaKWh.setSeriesNames(new String[]{"Datos"});
                // Resetear al preset de la máquina: si no, el gráfico vacío hereda el zoom
                // que haya quedado de la última consulta exitosa sobre esta misma instancia.
                graficaKWh.setMinY(0.0);
                graficaKWh.aplicarRangosPredefinidos(maquina);
                getElement().executeJs(graficaKWh.getInitScript2("chartdiv_historico"));
                return;
            }

            GraficaModel.ResultadoGrafica resultado = graficaKWh.graficarSerieKWh(
                    "chartdiv_historico", datos, conDiferencia, maquina, new String[]{"Datos"}, false);
            getElement().executeJs(resultado.script());
            mensajeSpan.setText(resultado.puntosGraficados() + " puntos graficados");
        } catch (Exception e) {
            mensajeSpan.setText("Error: " + e.getMessage());
        }
    }

    private void consultarVIP(String maquina, LocalDate desde, LocalDate hasta, String tipoVar) {
        try {
            if (!lineaAccessService.tieneAccesoAMaquina(maquina)) {
                mensajeSpan.setText("Sin acceso a esta máquina");
                return;
            }
            List<Map<String, Object>> datos = plcDataQueryService.getHistoricoVIPByRango(maquina, desde, hasta);

            if (datos.isEmpty()) {
                mensajeSpan.setText("No hay datos en el rango seleccionado");
                // Resetear al piso por defecto: si no, el gráfico vacío hereda el zoom que
                // haya quedado de la última consulta exitosa sobre esta misma instancia.
                graficaActiva.setMaxY(maxYDefaultPorTipo(tipoVar));
                getElement().executeJs(graficaActiva.getInitScript2("chartdiv_historico"));
                return;
            }

            graficaActiva.setSeriesNames(seriesNamesPorTipo(tipoVar));

            SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
            List<Long> timestamps = new java.util.ArrayList<>();
            List<Float[]> valoresPorFila = new java.util.ArrayList<>();

            for (Map<String, Object> row : datos) {
                try {
                    Float[] values = extractValues(row, tipoVar);
                    long ts = sdf.parse((String) row.get("fecha")).getTime();
                    timestamps.add(ts);
                    valoresPorFila.add(values);
                } catch (Exception ignored) {}
            }

            // Se limpia el atípico de cada serie por separado (VAB, VAC, VBC, etc. pueden
            // tener picos por falla de comunicación en momentos distintos), reemplazándolo
            // por una interpolación de sus vecinos.
            int nSeries = graficaActiva.getnGraficas();
            List<List<Float>> columnasLimpias = new java.util.ArrayList<>();
            List<Float> valoresParaEscala = new java.util.ArrayList<>();
            for (int s = 0; s < nSeries; s++) {
                List<Float> columna = new java.util.ArrayList<>();
                for (Float[] fila : valoresPorFila) columna.add(fila[s]);
                List<Float> columnaLimpia = GraficaModel.limpiarAtipicos(columna, GraficaModel.FACTOR_ATIPICO);
                columnasLimpias.add(columnaLimpia);
                for (Float v : columnaLimpia) {
                    if (v != null && v > 0) valoresParaEscala.add(v);
                }
            }
            List<Float[]> valoresPorFilaLimpio = new java.util.ArrayList<>();
            for (int i = 0; i < valoresPorFila.size(); i++) {
                Float[] filaLimpia = new Float[nSeries];
                for (int s = 0; s < nSeries; s++) filaLimpia[s] = columnasLimpias.get(s).get(i);
                valoresPorFilaLimpio.add(filaLimpia);
            }

            // Establecer maxY dinámico ANTES de generar el script de inicialización:
            // el piso por defecto se amplía si los datos reales lo superan.
            graficaActiva.setMaxY(GraficaModel.calcularMaxYConMargen(valoresParaEscala, maxYDefaultPorTipo(tipoVar)));

            StringBuilder batchScript = new StringBuilder();
            batchScript.append(graficaActiva.getInitScript2("chartdiv_historico"));
            for (int i = 0; i < timestamps.size(); i++) {
                batchScript.append(graficaActiva.getAddDataScript(
                        "chartdiv_historico", timestamps.get(i), valoresPorFilaLimpio.get(i), false));
            }
            batchScript.append(graficaActiva.getAplicarZoomInicialScript("chartdiv_historico"));

            getElement().executeJs(batchScript.toString());
            mensajeSpan.setText(datos.size() + " puntos graficados para " + tipoVar);
        } catch (Exception e) {
            mensajeSpan.setText("Error: " + e.getMessage());
        }
    }

    /** Piso por defecto del eje Y para cada variable VIP (se amplía con el percentil 95 si los datos reales lo superan). */
    private double maxYDefaultPorTipo(String tipoVar) {
        return switch (tipoVar) {
            case "Voltajes" -> VOLTAJES_MAX_Y_DEFAULT;
            case "Corrientes" -> CORRIENTES_MAX_Y_DEFAULT;
            case "PW" -> PW_MAX_Y_DEFAULT;
            case "PF" -> PF_MAX_Y_DEFAULT;
            default -> 0.0;
        };
    }

    private String[] seriesNamesPorTipo(String tipoVar) {
        return switch (tipoVar) {
            case "Voltajes" -> new String[]{"VAB", "VAC", "VBC"};
            case "Corrientes" -> new String[]{"IA", "IB", "IC"};
            case "PW" -> new String[]{"PW"};
            case "PF" -> new String[]{"PF"};
            default -> new String[]{"Valor"};
        };
    }

    private Float[] extractValues(Map<String, Object> row, String tipoVar) {
        return switch (tipoVar) {
            // Voltaje, corriente y potencia nunca son negativos: se toman en valor absoluto.
            case "Voltajes" -> new Float[]{
                GraficaModel.toFloatAbs(row.get("VAB")), GraficaModel.toFloatAbs(row.get("VAC")), GraficaModel.toFloatAbs(row.get("VBC"))
            };
            case "Corrientes" -> new Float[]{
                GraficaModel.toFloatAbs(row.get("IA")), GraficaModel.toFloatAbs(row.get("IB")), GraficaModel.toFloatAbs(row.get("IC"))
            };
            case "PW" -> new Float[]{GraficaModel.toFloatAbs(row.get("PW"))};
            // El factor de potencia es el que da negativo en el medidor principal: se
            // toma en valor absoluto igual que el resto.
            case "PF" -> new Float[]{GraficaModel.toFloatAbs(row.get("PF"))};
            default -> new Float[]{0f};
        };
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        getElement().executeJs(graficaActiva.getInitScript2("chartdiv_historico"));

        this.getUI().ifPresent(ui -> TarjetasEstadoActual.mostrarUltimoClickCard(ui, true));
        actualizarTarjetaUltimoClick(System.currentTimeMillis());
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        this.getUI().ifPresent(ui -> TarjetasEstadoActual.mostrarUltimoClickCard(ui, false));
    }

    private void resetZoom() {
        getElement().executeJs(graficaActiva.getResetZoomScript("chartdiv_historico"));
    }

    /**
     * Franja de valores en vivo (KWh/VAB/VAC/etc.) junto al título — misma posición y texto que
     * ChartsView, pero lee del archivo MENSUAL (cargarDatosActualesHistorico) para que toda la
     * vista de Histórico use siempre la misma referencia, sea cual sea el disparador (apertura
     * de la vista, cambio de máquina, o click en el gráfico).
     */
    private void cargarDatosActuales(String maquina) {
        TarjetasEstadoActual.cargarDatosActualesHistorico(lineaAccessService, plcDataQueryService, maquina, datosActualesCard);
    }

    @ClientCallable
    public void limpiarTarjetas() {
        if (this.getParent().isPresent() && this.getParent().get() instanceof MainLayout) {
            TarjetasEstadoActual.limpiarUltimoClickHistorico((MainLayout) this.getParent().get(), datosActualesCard);
        }
    }

    @ClientCallable
    public void registrarClickEnGrafica(long timestamp) {
        actualizarTarjetaUltimoClick(timestamp);
    }

    /**
     * Tarjeta compartida (MainLayout) de Fecha/Hora/KWh del último click, y de paso la franja de
     * valores (KWh/VAB/VAC/etc.) pasa a mostrar los valores de ESE momento — a diferencia de
     * ChartsView, acá el punto clickeado puede ser de cualquier día del rango consultado, así
     * que usa la variante "Historico" (busca en el archivo mensual correspondiente).
     */
    private void actualizarTarjetaUltimoClick(long timestamp) {
        if (this.getParent().isPresent() && this.getParent().get() instanceof MainLayout) {
            MainLayout layout = (MainLayout) this.getParent().get();
            TarjetasEstadoActual.actualizarUltimoClickHistorico(lineaAccessService, plcDataQueryService,
                    graficaActiva, maquinaCombo.getValue(), layout, datosActualesCard, timestamp);
        }
    }

}
