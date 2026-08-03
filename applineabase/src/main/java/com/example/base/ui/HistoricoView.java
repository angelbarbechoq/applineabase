package com.example.base.ui;

import com.example.base.model.GraficaModel;
import com.example.dataacquisition.FactorPotenciaUtil;
import com.example.dataacquisition.RutaArchivosEnergia;
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
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@PageTitle("Historico de Graficas | LineaBase")
@Route(value = "historico", layout = MainLayout.class)
@PermitAll
public class HistoricoView extends VerticalLayout {

    // Piso por defecto del eje Y para KWh: cada máquina tiene un consumo típico muy distinto,
    // pero graficarSerieKWh ya usa aplicarRangosPredefinidos(maquina) como piso real por máquina;
    // este valor solo se ve en el instante inicial, antes de la primera consulta.
    private static final double KWH_MAX_Y_DEFAULT = 50.0;
    // Voltaje, corriente y potencia activa varían muchísimo entre máquinas (un motor chico vs.
    // el medidor general de planta), así que NO existe un techo típico único válido para todas:
    // a diferencia de KWh, aquí no hay un preset por máquina. Estos pisos son solo una red de
    // seguridad para el caso degenerado (sin datos reales, o todos en cero) donde el eje no
    // puede quedar en [0,0]; con datos reales, el percentil 95 (calcularMaxYConMargen) siempre
    // domina y define la escala real. No subir estos valores "por las dudas": eso es lo que
    // rompía el zoom para cualquier máquina cuyo valor real fuera bastante menor al piso.
    private static final double VOLTAJES_MAX_Y_DEFAULT = 1.0;
    private static final double CORRIENTES_MAX_Y_DEFAULT = 1.0;
    private static final double PW_MAX_Y_DEFAULT = 1.0;
    // El factor de potencia se grafica siempre como fracción 0-1 (ver normalizarPF): algunos
    // medidores (KWhPlanta1) lo reportan en escala de porcentaje (ej. -95.96) y otros ya en
    // fracción (ej. 0.94) — normalizando antes de graficar, el piso es el mismo para todos.
    // Este sí es un techo real (el PF nunca supera 1 en valor absoluto), no una red de seguridad.
    private static final double PF_MAX_Y_DEFAULT = 1.0;

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
    private Div datosActualesCard;

    // Dos pestañas con los mismos datos: "Filtrado" (limpiarAtipicos/limpiarCeroAislado, el
    // comportamiento de siempre) y "Sin filtrar" (la serie cruda, para comparar contra la
    // filtrada cuando hay dudas de si el filtro está ocultando algo real). Cada una vive en su
    // propio contenedor amCharts — un mismo id no puede tener dos gráficos independientes.
    private static final String ID_CHART_FILTRADO = "chartdiv_historico_filtrado";
    private static final String ID_CHART_CRUDO = "chartdiv_historico_crudo";
    // Cuando el usuario cambia de pestaña, si esa pestaña nunca recibió datos porque estaba
    // oculta la última vez que se consultó (amCharts no puede dimensionarse en un contenedor
    // con display:none), se vuelve a correr la última consulta para esa pestaña recién visible.
    private boolean huboConsultaExitosa = false;

    // Mezcladores/Molino/Pulverizador (zona "Mezcla" en linea-id-config.json, mismo campo que ya
    // usa HorometroView para agrupar sus gráficos) trabajan con arranques/paradas más bruscos y
    // frecuentes que Extrusión — a pedido, la media móvil ahí aplana justo esos escalones reales
    // que se quieren ver, así que se excluyen de "Filtrado" (siguen con limpiarCeroAislado y
    // limpiarAtipicos, solo se saltea el suavizado).
    private final Set<String> maquinasSinMediaMovil;

    public HistoricoView(ConfigLoaderService configLoaderService, LineaAccessService lineaAccessService,
                          PLCDataQueryService plcDataQueryService) {
        this.configLoaderService = configLoaderService;
        this.lineaAccessService = lineaAccessService;
        this.plcDataQueryService = plcDataQueryService;
        this.maquinasSinMediaMovil = lineaAccessService.getLineasPermitidas().stream()
                .filter(l -> "Mezcla".equalsIgnoreCase(String.valueOf(l.get("zona"))))
                .map(l -> (String) l.get("lineaMaquina"))
                .collect(Collectors.toSet());
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        graficaKWh.setMinY(0.0);
        graficaKWh.setMaxY(KWH_MAX_Y_DEFAULT);
        graficaVoltajes.setMinY(0.0);
        graficaVoltajes.setMaxY(VOLTAJES_MAX_Y_DEFAULT);
        // Sin colores propios, amCharts5 asigna su color automático a cada serie — para VAB/VAC/VBC
        // (3 series) eso caía en tonos de azul difíciles de distinguir entre sí. Colores fijos y
        // bien separados por serie (VAB=azul, VAC=rojo, VBC=verde bosque; más oscuro que el verde
        // puro para que se lea bien sobre el fondo claro de la tarjeta).
        graficaVoltajes.setColoresPersonalizados(new String[]{"0x0000ff", "0xff0000", "0x008000"});
        graficaVoltajes.setMostrarLeyenda(true);
        graficaCorrientes.setMinY(0.0);
        graficaCorrientes.setMaxY(CORRIENTES_MAX_Y_DEFAULT);
        // Mismo problema que Voltajes para IA/IB/IC: colores fijos, bien diferenciados entre sí y
        // de los de Voltajes, para no confundir ambos gráficos entre sí tampoco.
        graficaCorrientes.setColoresPersonalizados(new String[]{"0xff8c00", "0xd119a8", "0xa0522d"});
        graficaCorrientes.setMostrarLeyenda(true);
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

        TabSheet tabSheetFiltro = new TabSheet();
        tabSheetFiltro.setSizeFull();
        tabSheetFiltro.add("Filtrado", crearPanelGrafico(ID_CHART_FILTRADO));
        tabSheetFiltro.add("Sin filtrar", crearPanelGrafico(ID_CHART_CRUDO));
        // Si la pestaña recién visible nunca se renderizó con datos reales (estaba oculta la
        // última vez que se consultó), se repite esa misma consulta ahora que ya se puede
        // dimensionar correctamente.
        tabSheetFiltro.addSelectedChangeListener(e -> {
            if (huboConsultaExitosa) {
                consultar();
            }
        });
        add(tabSheetFiltro);
        setFlexGrow(1, tabSheetFiltro);
    }

    /**
     * Envuelve el Div del gráfico en un VerticalLayout con altura 100% + flexGrow (mismo patrón
     * que HorometroView.crearPanelGrupo) en vez de una altura fija en px — con un tamaño fijo,
     * si la ventana del navegador es más baja que ese valor el contenido se pasa del alto
     * disponible del TabSheet y aparece la barra de scroll vertical de la página, en vez de que
     * el gráfico simplemente se achique junto con la ventana.
     */
    private VerticalLayout crearPanelGrafico(String containerId) {
        VerticalLayout panel = new VerticalLayout();
        panel.setSizeFull();
        panel.setPadding(false);

        Div chartDiv = new Div();
        chartDiv.setId(containerId);
        chartDiv.setWidthFull();
        chartDiv.setHeight("100%");
        panel.add(chartDiv);
        panel.setFlexGrow(1, chartDiv);

        return panel;
    }

    private HorizontalLayout buildFiltrosLayout() {
        List<String> maquinas = lineaAccessService.getMaquinasPermitidas();

        maquinaCombo = new ComboBox<>("Maquina");
        maquinaCombo.setItems(maquinas);
        maquinaCombo.setWidth("200px");
        if (!maquinas.isEmpty()) maquinaCombo.setValue(maquinas.get(0));
        // Al cambiar de máquina, la tarjeta de último click y la franja de valores quedan
        // referidas a la máquina anterior: se limpian en vez de mostrar datos de otra máquina
        // o "los últimos valores", ya que en Histórico esos valores solo deben venir de un click.
        maquinaCombo.addValueChangeListener(e -> limpiarTarjetas());

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
                getElement().executeJs(graficaKWh.getInitScript2(ID_CHART_FILTRADO));
                getElement().executeJs(graficaKWh.getInitScript2(ID_CHART_CRUDO));
                return;
            }

            GraficaModel.ResultadoGrafica filtrado = graficaKWh.graficarSerieKWh(
                    ID_CHART_FILTRADO, datos, conDiferencia, maquina, new String[]{"Datos"}, false,
                    true, aplicaMediaMovil(maquina));
            getElement().executeJs(filtrado.script());
            getElement().executeJs(graficaKWh.getZoomXInicialScript(ID_CHART_FILTRADO, filtrado.puntosGraficados()));

            GraficaModel.ResultadoGrafica crudo = graficaKWh.graficarSerieKWh(
                    ID_CHART_CRUDO, datos, conDiferencia, maquina, new String[]{"Datos"}, false, false);
            getElement().executeJs(crudo.script());
            getElement().executeJs(graficaKWh.getZoomXInicialScript(ID_CHART_CRUDO, crudo.puntosGraficados()));

            mensajeSpan.setText("");
            huboConsultaExitosa = true;
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
                getElement().executeJs(graficaActiva.getInitScript2(ID_CHART_FILTRADO));
                getElement().executeJs(graficaActiva.getInitScript2(ID_CHART_CRUDO));
                return;
            }

            graficaActiva.setSeriesNames(seriesNamesPorTipo(tipoVar));

            SimpleDateFormat sdf = new SimpleDateFormat(RutaArchivosEnergia.FORMATO_FECHA_HORA);
            List<Long> timestamps = new ArrayList<>();
            List<Float[]> valoresPorFila = new ArrayList<>();

            for (Map<String, Object> row : datos) {
                try {
                    Float[] values = extractValues(row, tipoVar);
                    long ts = sdf.parse((String) row.get("fecha")).getTime();
                    timestamps.add(ts);
                    valoresPorFila.add(values);
                } catch (Exception ignored) {}
            }

            int nSeries = graficaActiva.getnGraficas();
            renderizarVIP(ID_CHART_FILTRADO, maquina, tipoVar, timestamps, valoresPorFila, nSeries, true);
            renderizarVIP(ID_CHART_CRUDO, maquina, tipoVar, timestamps, valoresPorFila, nSeries, false);

            mensajeSpan.setText("");
            huboConsultaExitosa = true;
        } catch (Exception e) {
            mensajeSpan.setText("Error: " + e.getMessage());
        }
    }

    /**
     * Arma y ejecuta el script de un gráfico VIP en un contenedor puntual, aplicando o no el
     * filtro de ruido (limpiarCeroAislado + limpiarAtipicos + mediaMovil) según
     * {@code aplicarFiltro} — única función para esta orquestación, la usan tanto la pestaña
     * "Filtrado" como "Sin filtrar" en vez de repetir la lógica de armado del script para cada
     * una.
     *
     * Se limpia el atípico de cada serie por separado (VAB, VAC, VBC, etc. pueden tener picos
     * por falla de comunicación en momentos distintos), reemplazándolo por una interpolación de
     * sus vecinos. Se limpia además el cero aislado (lectura inválida del PLC, no un apagado
     * real) en las cuatro variables — Voltajes, Corrientes, PW y también PF, ya que el mismo
     * glitch de comunicación que da un cero espurio en las otras tres suele darlo también en PF
     * en esa misma lectura. Por último, si la máquina no está en maquinasSinMediaMovil, la media
     * móvil suaviza el ruido normal punto a punto que queda incluso sin ningún atípico — mismo
     * tratamiento que ya se le da a KWh.
     */
    private void renderizarVIP(String containerId, String maquina, String tipoVar, List<Long> timestamps,
                                List<Float[]> valoresPorFila, int nSeries, boolean aplicarFiltro) {
        List<List<Float>> columnas = new ArrayList<>();
        List<Float> valoresParaEscala = new ArrayList<>();
        for (int s = 0; s < nSeries; s++) {
            List<Float> columna = new ArrayList<>();
            for (Float[] fila : valoresPorFila) columna.add(fila[s]);
            if (aplicarFiltro) {
                columna = GraficaModel.limpiarCeroAislado(columna);
                columna = GraficaModel.limpiarAtipicos(columna, GraficaModel.FACTOR_ATIPICO);
                if (aplicaMediaMovil(maquina)) {
                    columna = GraficaModel.mediaMovil(columna, GraficaModel.VENTANA_MEDIA_MOVIL);
                }
            }
            columnas.add(columna);
            for (Float v : columna) {
                if (v != null && v > 0) valoresParaEscala.add(v);
            }
        }
        List<Float[]> valoresPorFilaFinal = new ArrayList<>();
        for (int i = 0; i < valoresPorFila.size(); i++) {
            Float[] fila = new Float[nSeries];
            for (int s = 0; s < nSeries; s++) fila[s] = columnas.get(s).get(i);
            valoresPorFilaFinal.add(fila);
        }

        // Establecer maxY dinámico ANTES de generar el script de inicialización: el piso por
        // defecto se amplía si los datos reales lo superan. Se calcula por separado en cada
        // contenedor porque "Sin filtrar" puede tener atípicos que inflen la escala más que
        // "Filtrado" — cada pestaña debe verse bien con sus propios datos, no compartir eje.
        graficaActiva.setMaxY(GraficaModel.calcularMaxYConMargen(valoresParaEscala, maxYDefaultPorTipo(tipoVar)));

        StringBuilder script = new StringBuilder();
        script.append(graficaActiva.getInitScript2(containerId));
        script.append(graficaActiva.getSetAllDataScript(containerId, timestamps, valoresPorFilaFinal));
        script.append(graficaActiva.getAplicarZoomInicialScript(containerId));
        // No recorta ni descarta puntos: solo cambia qué ventana del rango completo se ve
        // primero (el resto queda navegable con el scrollbar horizontal).
        script.append(graficaActiva.getZoomXInicialScript(containerId, timestamps.size()));
        getElement().executeJs(script.toString());
    }

    /** Ver comentario de maquinasSinMediaMovil: Mezcladores/Molino/Pulverizador quedan afuera del suavizado. */
    private boolean aplicaMediaMovil(String maquina) {
        return !maquinasSinMediaMovil.contains(maquina);
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
            // toma en valor absoluto igual que el resto, y se normaliza a fracción 0-1.
            case "PF" -> new Float[]{normalizarPF(GraficaModel.toFloatAbs(row.get("PF")))};
            default -> new Float[]{0f};
        };
    }

    /** Ver FactorPotenciaUtil: normaliza a fracción 0-1 solo si el dato viene en escala de
     * porcentaje (como KWhPlanta1); las máquinas que ya reportan la fracción quedan sin cambios. */
    private static Float normalizarPF(Float pf) {
        if (pf == null) {
            return null;
        }
        return (float) FactorPotenciaUtil.normalizarAbs(Math.abs(pf));
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        getElement().executeJs(graficaActiva.getInitScript2(ID_CHART_FILTRADO));
        getElement().executeJs(graficaActiva.getInitScript2(ID_CHART_CRUDO));

        this.getUI().ifPresent(ui -> TarjetasEstadoActual.mostrarUltimoClickCard(ui, true));
        limpiarTarjetas();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        this.getUI().ifPresent(ui -> TarjetasEstadoActual.mostrarUltimoClickCard(ui, false));
    }

    private void resetZoom() {
        getElement().executeJs(graficaActiva.getResetZoomScript(ID_CHART_FILTRADO));
        getElement().executeJs(graficaActiva.getResetZoomScript(ID_CHART_CRUDO));
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
