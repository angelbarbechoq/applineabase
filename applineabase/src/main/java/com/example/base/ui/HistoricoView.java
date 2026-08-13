package com.example.base.ui;

import com.example.base.model.GraficaModel;
import com.example.dataacquisition.FactorPotenciaUtil;
import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.HeaderRow;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.StreamResource;
import jakarta.annotation.security.PermitAll;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    private IntegerField ventanaMediaMovilField;
    private Button consultarBtn;
    private Span mensajeSpan;
    private Div datosActualesCard;

    // Tabla de análisis (solo ADMIN, ver lineaAccessService.esAdmin()): cada click sobre el
    // gráfico cierra un tramo con el click anterior (Inicio = click anterior, Fin = click
    // actual) y agrega una fila; el click actual queda como "anterior" para la fila siguiente.
    // null/no creada para usuarios no-admin, así registrarClickEnGrafica no hace nada extra.
    private Grid<FilaAnalisis> analisisGrid;
    private final List<FilaAnalisis> filasAnalisis = new ArrayList<>();
    private TarjetasEstadoActual.PuntoClick puntoAnteriorAnalisis;

    /** Una fila de la tabla de análisis: tramo Inicio→Fin entre dos clicks consecutivos. */
    private record FilaAnalisis(String fechaInicio, String horaInicio, String kwhInicio,
                                 String fechaFin, String horaFin, String kwhFin) {
    }

    // Modo "Con arranque": una sola tabla de 12 columnas (Inicio, Fin Purga, Normalizado, Fin)
    // en vez de dos tablas separadas — reemplaza a la tabla de "Sin arranque" en vez de convivir
    // al lado (evita el problema de layout de tener dos grids side-by-side). Se llena progresivo:
    // cada click completa las 3 columnas que le corresponden, las demas quedan vacias hasta que
    // les toque su click.
    private Checkbox modoCheckbox;
    private boolean modoConArranque = false;
    private Grid<FilaArranque> arranqueGrid;
    private final List<FilaArranque> filasArranque = new ArrayList<>();
    // Clicks acumulados del ciclo actual en modo "Con arranque" (0 a 4). Al llegar a 4 el ciclo
    // queda cerrado y clicks siguientes no hacen nada hasta el doble-click (limpiarTarjetas), que
    // lo reabre. Ver registrarClickConArranque.
    private final List<TarjetasEstadoActual.PuntoClick> clicksArranque = new ArrayList<>();

    /**
     * Fila unica de la tabla "Con arranque": mantiene los encabezados de las DOS tablas
     * originales intactos, uno al lado del otro, en vez de fusionarlos en un set nuevo de
     * columnas. Grupo 1 (t1*) = tabla clasica Inicio/Fin: Inicio=click1, Fin=click4. Grupo 2
     * (t2*) = tabla Inicio/Fin Purga/Normalizado: Inicio=click1 (el mismo dato del grupo 1,
     * repetido a proposito porque las dos tablas originales lo tenian cada una por su lado),
     * Fin Purga=click2, Normalizado=click3. Columnas de un click que todavia no paso quedan en "".
     */
    private record FilaArranque(String t1FechaInicio, String t1HoraInicio, String t1KwhInicio,
                                 String t1FechaFin, String t1HoraFin, String t1KwhFin,
                                 String t2FechaInicio, String t2HoraInicio, String t2KwhInicio,
                                 String t2FechaFinPurga, String t2HoraFinPurga, String t2KwhFinPurga,
                                 String t2FechaNormalizado, String t2HoraNormalizado, String t2KwhNormalizado) {
    }

    // Tabla al lado del gráfico "Filtrado" (solo ADMIN): fila por fila, los datos tal como vienen
    // de la base de datos para la variable actualmente consultada — no aplica el filtro de
    // atípicos/media móvil que sí usa el gráfico. Columnas dinámicas (Fecha + los campos de la
    // variable activa), se reconstruyen en cada consulta porque cambian según KWh/Voltajes/etc.
    private Grid<Map<String, Object>> datosGrid;
    // Última consulta exitosa (solo ADMIN): misma lista que muestra datosGrid, guardada acá para
    // que el botón de descarga CSV arme el archivo con exactamente lo mismo que se ve en pantalla,
    // sin tener que volver a consultar la base de datos.
    private List<Map<String, Object>> ultimosDatosConsultados;
    private String ultimaVariableConsultada;
    private String ultimaMaquinaConsultada;

    // Tabla al lado del gráfico "Sin filtrar" (solo ADMIN): la diferencia de KWh de TODAS las
    // máquinas de la base (una fila por máquina, no solo la seleccionada en el combo), para el
    // mismo rango de fechas consultado — no depende de qué Variable esté activa, siempre usa la
    // serie KWh de cada máquina. Solo 4 columnas a propósito (sin fecha/hora de detalle): lo que
    // se pidió ver acá es la diferencia, no el desglose. diferenciaKwh = ultimo valor valido -
    // primer valor valido; si el primero/ultimo dato está en 0 (sin lectura real, ver
    // buscarKWhValido), se camina al siguiente/anterior hasta encontrar un valor aceptable (mismo
    // criterio que renderizarVIP: KWh > 0 es válido). Máquinas sin ningún valor válido en el rango
    // simplemente no aparecen (en vez de una fila con guiones).
    private Grid<FilaEnergia> energiaGrid;
    private final List<FilaEnergia> filasEnergia = new ArrayList<>();
    // A pedido: NO se recalcula en cada Consultar, solo cuando cambia el rango de fechas (Desde/
    // Hasta) respecto de la última vez que se calculó — cambiar de máquina y volver a consultar
    // con el mismo rango deja la tabla tal como está (no depende de la máquina de todos modos,
    // ver actualizarTablaEnergia).
    private LocalDate energiaUltimoDesde;
    private LocalDate energiaUltimoHasta;

    private record FilaEnergia(String maquina, String kwhInicial, String kwhFinal, String diferenciaKwh) {
    }

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
        if (lineaAccessService.esAdmin()) {
            modoCheckbox = new Checkbox("Con arranque");
            modoCheckbox.addValueChangeListener(e -> cambiarModoAnalisis(e.getValue()));
            encabezado.add(modoCheckbox);
        }
        add(encabezado);

        if (lineaAccessService.esAdmin()) {
            add(buildAnalisisSection());
        }

        add(buildFiltrosLayout());

        mensajeSpan = new Span("Seleccione los filtros y presione Consultar");
        add(mensajeSpan);

        TabSheet tabSheetFiltro = new TabSheet();
        tabSheetFiltro.setSizeFull();
        boolean esAdmin = lineaAccessService.esAdmin();
        tabSheetFiltro.add("Sin filtrar", PanelGraficoUtil.crearPanelGrafico(ID_CHART_CRUDO, esAdmin ? buildEnergiaGrid() : null));
        tabSheetFiltro.add("Filtrado", PanelGraficoUtil.crearPanelGrafico(ID_CHART_FILTRADO, esAdmin ? buildDatosGrid() : null));
        // Si la pestaña recién visible estaba oculta (display:none) cuando se cargaron los
        // datos, amCharts no detectó el tamaño real del contenedor — se fuerza un resize en vez
        // de repetir la consulta completa a la base de datos (los datos ya están cargados en el
        // gráfico, no hace falta volver a pedirlos ni volver a calcular atípicos/media móvil).
        tabSheetFiltro.addSelectedChangeListener(e -> {
            if (huboConsultaExitosa && graficaActiva != null) {
                getElement().executeJs(graficaActiva.getResizeScript(ID_CHART_FILTRADO));
                getElement().executeJs(graficaActiva.getResizeScript(ID_CHART_CRUDO));
            }
        });
        add(tabSheetFiltro);
        setFlexGrow(1, tabSheetFiltro);
    }


    /**
     * Tabla junto al gráfico "Filtrado": arranca sin columnas, actualizarTablaDatos las arma cada
     * vez que hay una consulta nueva (dependen de la Variable elegida: KWh, Voltajes, etc.).
     */
    private Grid<Map<String, Object>> buildDatosGrid() {
        datosGrid = new Grid<>();
        datosGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_ROW_STRIPES);
        datosGrid.getStyle().set("font-size", "12px");
        datosGrid.setHeightFull();
        return datosGrid;
    }

    /** Tabla junto al gráfico "Sin filtrar": diferencia de KWh, sin desglose (ver comentario del campo energiaGrid). */
    private Grid<FilaEnergia> buildEnergiaGrid() {
        energiaGrid = new Grid<>(FilaEnergia.class, false);
        energiaGrid.addColumn(FilaEnergia::maquina).setHeader("Maquina").setAutoWidth(true).setFlexGrow(0);
        energiaGrid.addColumn(FilaEnergia::kwhInicial).setHeader("KWh Inicial").setAutoWidth(true).setFlexGrow(0);
        energiaGrid.addColumn(FilaEnergia::kwhFinal).setHeader("KWh Final").setAutoWidth(true).setFlexGrow(0);
        energiaGrid.addColumn(FilaEnergia::diferenciaKwh).setHeader("Diferencia kWh").setAutoWidth(true).setFlexGrow(0);
        energiaGrid.setItems(new ListDataProvider<>(filasEnergia));
        energiaGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_ROW_STRIPES);
        energiaGrid.getStyle().set("font-size", "12px");
        energiaGrid.setHeightFull();
        return energiaGrid;
    }

    /**
     * Reconstruye las columnas de datosGrid para la variable activa (Fecha + los campos de esa
     * variable, mismos nombres que usa extractValues/seriesNamesPorTipo) y carga las filas tal
     * como vinieron de la base — sin limpiarAtipicos/mediaMovil, esos solo se aplican al gráfico.
     */
    private void actualizarTablaDatos(List<Map<String, Object>> datos, String variable, String maquina) {
        ultimosDatosConsultados = datos;
        ultimaVariableConsultada = variable;
        ultimaMaquinaConsultada = maquina;

        if (datosGrid == null) {
            return;
        }
        datosGrid.getColumns().forEach(datosGrid::removeColumn);
        datosGrid.addColumn(fila -> String.valueOf(fila.get("fecha"))).setHeader("Fecha").setAutoWidth(true).setFlexGrow(0);

        for (String campo : camposPorVariable(variable)) {
            datosGrid.addColumn(fila -> formatearValorCelda(fila.get(campo))).setHeader(campo).setAutoWidth(true).setFlexGrow(0);
        }
        datosGrid.setItems(datos);
    }

    /** Campos (ademas de "fecha") que trae cada variable — mismo set que usan datosGrid y la descarga CSV. */
    private static String[] camposPorVariable(String variable) {
        return switch (variable) {
            case "KWh" -> new String[]{"kwh"};
            case "Voltajes" -> new String[]{"VAB", "VAC", "VBC"};
            case "Corrientes" -> new String[]{"IA", "IB", "IC"};
            case "PW" -> new String[]{"PW"};
            case "PF" -> new String[]{"PF"};
            default -> new String[0];
        };
    }

    private static String formatearValorCelda(Object valor) {
        if (valor instanceof Number numero) {
            return GraficaModel.formatearDecimal(numero.doubleValue());
        }
        return valor == null ? "" : valor.toString();
    }

    /**
     * Diferencia de KWh de TODAS las máquinas de la base (ver comentario del campo energiaGrid),
     * para el rango consultado — independiente de la máquina seleccionada en el combo y de la
     * Variable activa. lineaAccessService.getMaquinasPermitidas() para un ADMIN devuelve todas
     * las líneas configuradas (ver LineaAccessService.getLineasPermitidas), así que no hace falta
     * una fuente aparte solo para esta tabla.
     *
     * A pedido, solo se recalcula si el rango de fechas cambió respecto de la última vez (ver
     * energiaUltimoDesde/energiaUltimoHasta) — cambiar de máquina y volver a consultar con el
     * mismo rango no la toca, a diferencia de actualizarTablaDatos, que sí se repite siempre.
     */
    private void actualizarTablaEnergia(LocalDate desde, LocalDate hasta) {
        if (energiaGrid == null || (desde.equals(energiaUltimoDesde) && hasta.equals(energiaUltimoHasta))) {
            return;
        }
        energiaUltimoDesde = desde;
        energiaUltimoHasta = hasta;
        filasEnergia.clear();
        for (String maquina : lineaAccessService.getMaquinasPermitidas()) {
            try {
                List<Map<String, Object>> datosKWh = plcDataQueryService.getHistoricoKWhByRango(maquina, desde, hasta);
                Map<String, Object> primero = buscarKWhValido(datosKWh, false);
                Map<String, Object> ultimo = buscarKWhValido(datosKWh, true);
                if (primero != null && ultimo != null) {
                    double kwhInicio = ((Number) primero.get("kwh")).doubleValue();
                    double kwhFin = ((Number) ultimo.get("kwh")).doubleValue();
                    filasEnergia.add(new FilaEnergia(maquina,
                            GraficaModel.formatearDecimal(kwhInicio), GraficaModel.formatearDecimal(kwhFin),
                            GraficaModel.formatearDecimal(kwhFin - kwhInicio)));
                }
            } catch (Exception ignored) {
                // Sin datos de KWh para esta máquina/rango: se omite esa fila y sigue con las demás.
            }
        }
        energiaGrid.getDataProvider().refreshAll();
    }

    /** Primer (desdeElFinal=false) o último (desdeElFinal=true) registro con KWh > 0 — mismo
     * criterio que renderizarVIP para descartar lecturas inválidas (0 = sin lectura real, no un
     * consumo genuino en cero). Camina al siguiente/anterior hasta encontrar uno aceptable. */
    private static Map<String, Object> buscarKWhValido(List<Map<String, Object>> datos, boolean desdeElFinal) {
        int inicio = desdeElFinal ? datos.size() - 1 : 0;
        int paso = desdeElFinal ? -1 : 1;
        for (int i = inicio; i >= 0 && i < datos.size(); i += paso) {
            Object valor = datos.get(i).get("kwh");
            if (valor instanceof Number numero && numero.doubleValue() > 0) {
                return datos.get(i);
            }
        }
        return null;
    }

    /**
     * Contenedor de la sección de análisis: las dos tablas, pero solo una visible a la vez según
     * el modo (el checkbox "Con arranque" vive en el encabezado, junto al título — ver
     * constructor). Antes convivían las dos lado a lado; ahora "Con arranque" reemplaza a la de
     * "Sin arranque" con su propia tabla de 12 columnas en vez de compartir espacio con ella.
     */
    private VerticalLayout buildAnalisisSection() {
        VerticalLayout seccion = new VerticalLayout();
        seccion.setPadding(false);
        seccion.setSpacing(false);
        seccion.getStyle().set("gap", "6px");
        seccion.getStyle().set("align-self", "flex-start");

        seccion.add(buildAnalisisGrid(), buildArranqueGrid());
        return seccion;
    }

    /** Cambia entre Sin arranque (tabla de 6 columnas, cadena continua) y Con arranque (tabla de
     * 12 columnas, ciclo fijo de 4 clicks) — ver registrarClickEnGrafica. Solo una de las dos
     * tablas queda visible según el modo. Cambiar de modo a mitad de un ciclo no tiene un estado
     * consistente que conservar, así que limpia todo (igual que el doble-click). */
    private void cambiarModoAnalisis(boolean conArranque) {
        modoConArranque = conArranque;
        analisisGrid.setVisible(!conArranque);
        arranqueGrid.setVisible(conArranque);
        limpiarTarjetas();
    }

    /**
     * Tabla de análisis solo-ADMIN: un tramo Inicio→Fin por cada par de clicks consecutivos
     * sobre el gráfico (ver puntoAnteriorAnalisis en registrarClickEnGrafica). Fila más nueva
     * arriba. Se vacía con el doble-click en el gráfico o al cambiar de máquina, igual que la
     * tarjeta de último click (ver limpiarTarjetas).
     *
     * Columnas angostas (autoWidth + flexGrow 0, en vez del ancho parejo por defecto que
     * estiraba cada columna a lo ancho de la pantalla) y tema compacto para que ocupe lo mínimo.
     * setAllRowsVisible en vez de una altura fija: con 1-2 filas no deja hueco vacío debajo, y
     * crece sola a medida que se acumulan clicks.
     */
    private Grid<FilaAnalisis> buildAnalisisGrid() {
        analisisGrid = new Grid<>(FilaAnalisis.class, false);
        analisisGrid.addColumn(FilaAnalisis::fechaInicio).setHeader("Fecha Inicio").setAutoWidth(true).setFlexGrow(0);
        analisisGrid.addColumn(FilaAnalisis::horaInicio).setHeader("Hora Inicio").setAutoWidth(true).setFlexGrow(0);
        analisisGrid.addColumn(FilaAnalisis::kwhInicio).setHeader("KWh Inic").setAutoWidth(true).setFlexGrow(0);
        analisisGrid.addColumn(FilaAnalisis::fechaFin).setHeader("Fecha Fin").setAutoWidth(true).setFlexGrow(0);
        analisisGrid.addColumn(FilaAnalisis::horaFin).setHeader("Hora Fin").setAutoWidth(true).setFlexGrow(0);
        analisisGrid.addColumn(FilaAnalisis::kwhFin).setHeader("KWh Fin").setAutoWidth(true).setFlexGrow(0);
        analisisGrid.setItems(new ListDataProvider<>(filasAnalisis));
        analisisGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_ROW_STRIPES);
        analisisGrid.setAllRowsVisible(true);
        analisisGrid.getStyle().set("font-size", "12px");
        // No setWidthFull(): que se achique al ancho real de las columnas en vez de estirarse
        // a lo ancho de la pantalla (align-self propio, VerticalLayout por defecto estira los hijos).
        analisisGrid.getStyle().set("align-self", "flex-start");
        analisisGrid.setSelectionMode(Grid.SelectionMode.SINGLE);
        // Click en una fila = copiarla al portapapeles como TSV (tabulaciones), para pegar en
        // Excel con cada dato en su propia celda — no hay forma nativa de copiar filas de un
        // vaadin-grid virtualizado con Ctrl+C, así que se arma el texto a mano.
        analisisGrid.addItemClickListener(e -> copiarFilaAlPortapapeles(e.getItem()));
        return analisisGrid;
    }

    private void copiarFilaAlPortapapeles(FilaAnalisis fila) {
        String tsv = String.join("\t", fila.fechaInicio(), fila.horaInicio(), fila.kwhInicio(),
                fila.fechaFin(), fila.horaFin(), fila.kwhFin());
        copiarAlPortapapeles(tsv);
    }

    /**
     * navigator.clipboard.writeText requiere contexto seguro (HTTPS o localhost) — esta app se
     * sirve por HTTP plano en la LAN de planta, así que el navegador la bloquea sin avisar (la
     * promesa se rechaza en silencio). document.execCommand('copy') sobre un textarea temporal sí
     * funciona en HTTP plano, por eso se usa acá en vez de la Clipboard API moderna.
     */
    private void copiarAlPortapapeles(String texto) {
        getElement().executeJs(
                "var ta = document.createElement('textarea');" +
                        "ta.value = $0;" +
                        "ta.style.position = 'fixed'; ta.style.opacity = '0';" +
                        "document.body.appendChild(ta);" +
                        "ta.focus(); ta.select();" +
                        "try { document.execCommand('copy'); } catch (e) { console.error('Error copiando al portapapeles:', e); }" +
                        "document.body.removeChild(ta);",
                texto);
        Notification.show("Copiado — pegar en Excel", 1500, Notification.Position.BOTTOM_END);
    }

    /**
     * Tabla "Con arranque": una sola fila con los 4 clicks del ciclo, llenada progresivamente
     * (ver registrarClickConArranque), pero con las columnas de las DOS tablas originales
     * intactas y agrupadas visualmente con una fila de encabezado superior (Grid.HeaderRow.join)
     * para que se distingan una de la otra en vez de leerse como un solo set de 15 columnas.
     */
    private Grid<FilaArranque> buildArranqueGrid() {
        arranqueGrid = new Grid<>(FilaArranque.class, false);
        Grid.Column<FilaArranque> c1 = arranqueGrid.addColumn(FilaArranque::t1FechaInicio).setHeader("Fecha Inicio").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<FilaArranque> c2 = arranqueGrid.addColumn(FilaArranque::t1HoraInicio).setHeader("Hora Inicio").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<FilaArranque> c3 = arranqueGrid.addColumn(FilaArranque::t1KwhInicio).setHeader("KWh Inic").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<FilaArranque> c4 = arranqueGrid.addColumn(FilaArranque::t1FechaFin).setHeader("Fecha Fin").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<FilaArranque> c5 = arranqueGrid.addColumn(FilaArranque::t1HoraFin).setHeader("Hora Fin").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<FilaArranque> c6 = arranqueGrid.addColumn(FilaArranque::t1KwhFin).setHeader("KWh Fin").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<FilaArranque> c7 = arranqueGrid.addColumn(FilaArranque::t2FechaInicio).setHeader("Fecha Inicio").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<FilaArranque> c8 = arranqueGrid.addColumn(FilaArranque::t2HoraInicio).setHeader("Hora Inicio").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<FilaArranque> c9 = arranqueGrid.addColumn(FilaArranque::t2KwhInicio).setHeader("KWh Inicio").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<FilaArranque> c10 = arranqueGrid.addColumn(FilaArranque::t2FechaFinPurga).setHeader("Fecha Fin Purga").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<FilaArranque> c11 = arranqueGrid.addColumn(FilaArranque::t2HoraFinPurga).setHeader("Hora Fin Purga").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<FilaArranque> c12 = arranqueGrid.addColumn(FilaArranque::t2KwhFinPurga).setHeader("KWh Fin Purga").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<FilaArranque> c13 = arranqueGrid.addColumn(FilaArranque::t2FechaNormalizado).setHeader("Fecha Normalizado").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<FilaArranque> c14 = arranqueGrid.addColumn(FilaArranque::t2HoraNormalizado).setHeader("Hora Normalizado").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<FilaArranque> c15 = arranqueGrid.addColumn(FilaArranque::t2KwhNormalizado).setHeader("KWh Normalizado").setAutoWidth(true).setFlexGrow(0);

        HeaderRow grupos = arranqueGrid.prependHeaderRow();
        grupos.join(c1, c2, c3, c4, c5, c6).setComponent(
                encabezadoConCopiar("Tabla Inicio / Fin", this::copiarGrupoT1AlPortapapeles));
        grupos.join(c7, c8, c9, c10, c11, c12, c13, c14, c15).setComponent(
                encabezadoConCopiar("Tabla Inicio / Fin Purga / Normalizado", this::copiarGrupoT2AlPortapapeles));

        arranqueGrid.setItems(new ListDataProvider<>(filasArranque));
        arranqueGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_ROW_STRIPES);
        arranqueGrid.setAllRowsVisible(true);
        arranqueGrid.getStyle().set("font-size", "12px");
        arranqueGrid.getStyle().set("align-self", "flex-start");
        arranqueGrid.setSelectionMode(Grid.SelectionMode.SINGLE);
        arranqueGrid.addItemClickListener(e -> copiarFilaArranqueAlPortapapeles(e.getItem()));
        arranqueGrid.setVisible(false);
        return arranqueGrid;
    }

    private void copiarFilaArranqueAlPortapapeles(FilaArranque fila) {
        String tsv = String.join("\t", fila.t1FechaInicio(), fila.t1HoraInicio(), fila.t1KwhInicio(),
                fila.t1FechaFin(), fila.t1HoraFin(), fila.t1KwhFin(),
                fila.t2FechaInicio(), fila.t2HoraInicio(), fila.t2KwhInicio(),
                fila.t2FechaFinPurga(), fila.t2HoraFinPurga(), fila.t2KwhFinPurga(),
                fila.t2FechaNormalizado(), fila.t2HoraNormalizado(), fila.t2KwhNormalizado());
        copiarAlPortapapeles(tsv);
    }

    /** Etiqueta del grupo + boton "Copiar" chico, para meter en una celda de encabezado unida
     * (HeaderRow.join(...).setComponent(...)) — cada grupo de la tabla "Con arranque" copia solo
     * sus propias columnas, en vez de las 15 juntas (eso ya lo hace el click en la fila). */
    private HorizontalLayout encabezadoConCopiar(String texto, Runnable alCopiar) {
        Span label = new Span(texto);
        Button copiarBtn = new Button("Copiar", e -> alCopiar.run());
        copiarBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        HorizontalLayout layout = new HorizontalLayout(label, copiarBtn);
        layout.setAlignItems(FlexComponent.Alignment.CENTER);
        layout.setSpacing(true);
        return layout;
    }

    /** Copia solo las columnas del grupo "Inicio / Fin" (t1) de la unica fila de arranqueGrid. */
    private void copiarGrupoT1AlPortapapeles() {
        if (filasArranque.isEmpty()) {
            Notification.show("No hay datos para copiar", 1500, Notification.Position.BOTTOM_END);
            return;
        }
        FilaArranque fila = filasArranque.get(0);
        String tsv = String.join("\t", fila.t1FechaInicio(), fila.t1HoraInicio(), fila.t1KwhInicio(),
                fila.t1FechaFin(), fila.t1HoraFin(), fila.t1KwhFin());
        copiarAlPortapapeles(tsv);
    }

    /** Copia solo las columnas del grupo "Inicio / Fin Purga / Normalizado" (t2) de la unica fila de arranqueGrid. */
    private void copiarGrupoT2AlPortapapeles() {
        if (filasArranque.isEmpty()) {
            Notification.show("No hay datos para copiar", 1500, Notification.Position.BOTTOM_END);
            return;
        }
        FilaArranque fila = filasArranque.get(0);
        String tsv = String.join("\t", fila.t2FechaInicio(), fila.t2HoraInicio(), fila.t2KwhInicio(),
                fila.t2FechaFinPurga(), fila.t2HoraFinPurga(), fila.t2KwhFinPurga(),
                fila.t2FechaNormalizado(), fila.t2HoraNormalizado(), fila.t2KwhNormalizado());
        copiarAlPortapapeles(tsv);
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

        // Ventana de la media móvil de la pestaña "Filtrado", ajustable a pedido: una ventana
        // chica pierde menos detalle (picos/caídas cortas) pero suaviza menos ruido; una grande
        // suaviza más pero puede tapar detalles reales cortos. En muestras (~1 lectura/minuto,
        // igual criterio que GraficaModel.VENTANA_MEDIA_MOVIL), no en minutos, para que el
        // número coincida exactamente con lo que hace mediaMovil.
        ventanaMediaMovilField = new IntegerField("Ventana media móvil");
        ventanaMediaMovilField.setValue(GraficaModel.VENTANA_MEDIA_MOVIL);
        ventanaMediaMovilField.setMin(1);
        ventanaMediaMovilField.setStepButtonsVisible(true);
        ventanaMediaMovilField.setWidth("140px");
        ventanaMediaMovilField.setHelperText("muestras, ~1/min");

        consultarBtn = new Button("Consultar", e -> consultar());
        consultarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button resetZoomBtn = new Button("Reset Zoom", e -> resetZoom());
        resetZoomBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout layout = new HorizontalLayout(
                maquinaCombo, desdeDate, hastaDate, variableCombo, ventanaMediaMovilField, consultarBtn, resetZoomBtn);
        if (lineaAccessService.esAdmin()) {
            layout.add(crearLinkDescargaCsv());
        }
        layout.setAlignItems(FlexComponent.Alignment.END);
        layout.setSpacing(true);
        return layout;
    }

    /**
     * Descarga (solo ADMIN) de la maquina/variable/rango de la ultima consulta exitosa — el mismo
     * contenido que ya se ve en datosGrid (pestaña Filtrado), sin volver a consultar la base.
     * Mismo patron que AlarmasHistorialCompletoView.crearLinkDescargaCsv (StreamResource + Anchor
     * con atributo download), separador ";" y BOM UTF-8 para que Excel lo abra bien.
     */
    private Anchor crearLinkDescargaCsv() {
        StreamResource recurso = new StreamResource("historico.csv", this::generarCsvDatos);
        recurso.setContentType("text/csv; charset=UTF-8");
        Anchor descargarLink = new Anchor(recurso, "CSV");
        descargarLink.getElement().setAttribute("download", true);
        descargarLink.getElement().getThemeList().add("button");
        descargarLink.getElement().getThemeList().add("tertiary");
        return descargarLink;
    }

    private InputStream generarCsvDatos() {
        StringBuilder sb = new StringBuilder();
        sb.append((char) 0xFEFF);
        if (ultimosDatosConsultados == null || ultimosDatosConsultados.isEmpty()) {
            sb.append("Sin datos - presione Consultar primero\r\n");
            return new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        String[] campos = camposPorVariable(ultimaVariableConsultada);
        sb.append("Maquina;").append(CsvUtil.escape(ultimaMaquinaConsultada)).append("\r\n");
        sb.append("Fecha");
        for (String campo : campos) {
            sb.append(';').append(campo);
        }
        sb.append("\r\n");
        for (Map<String, Object> fila : ultimosDatosConsultados) {
            sb.append(CsvUtil.escape(String.valueOf(fila.get("fecha"))));
            for (String campo : campos) {
                sb.append(';').append(formatearValorCelda(fila.get(campo)));
            }
            sb.append("\r\n");
        }
        return new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Ventana de media móvil actualmente configurada — 1 si el campo quedó vacío o inválido. */
    private int ventanaMediaMovilActual() {
        Integer valor = ventanaMediaMovilField.getValue();
        return valor != null && valor >= 1 ? valor : 1;
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
            actualizarTablaDatos(datos, "KWh", maquina);
            actualizarTablaEnergia(desde, hasta);

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
                    true, aplicaMediaMovil(maquina), ventanaMediaMovilActual());
            getElement().executeJs(filtrado.script());
            getElement().executeJs(graficaKWh.getZoomXInicialScript(ID_CHART_FILTRADO, filtrado.puntosGraficados()));

            GraficaModel.ResultadoGrafica crudo = graficaKWh.graficarSerieKWh(
                    ID_CHART_CRUDO, datos, conDiferencia, maquina, new String[]{"Datos"}, false, true, false);
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
            actualizarTablaDatos(datos, tipoVar, maquina);
            actualizarTablaEnergia(desde, hasta);

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
     * Arma y ejecuta el script de un gráfico VIP en un contenedor puntual. El tratamiento de
     * ruido puntual (limpiarCeroAislado + limpiarAtipicos) se aplica SIEMPRE, en las dos
     * pestañas — "Sin filtrar" no quiere decir "datos crudos sin ningún tratamiento", sino
     * "sin el suavizado de media móvil"; un atípico por falla de comunicación no es un dato
     * real que valga la pena mostrar en ninguna de las dos vistas. Lo único que
     * {@code suavizarMediaMovil} decide es si además se aplica la media móvil (solo
     * "Filtrado", y solo si la máquina no está en maquinasSinMediaMovil) — única función para
     * esta orquestación, la usan tanto "Filtrado" como "Sin filtrar" en vez de repetir la
     * lógica de armado del script para cada una.
     *
     * Se limpia el atípico de cada serie por separado (VAB, VAC, VBC, etc. pueden tener picos
     * por falla de comunicación en momentos distintos), reemplazándolo por una interpolación de
     * sus vecinos. Se limpia además el cero aislado (lectura inválida del PLC, no un apagado
     * real) en las cuatro variables — Voltajes, Corrientes, PW y también PF, ya que el mismo
     * glitch de comunicación que da un cero espurio en las otras tres suele darlo también en PF
     * en esa misma lectura.
     */
    private void renderizarVIP(String containerId, String maquina, String tipoVar, List<Long> timestamps,
                                List<Float[]> valoresPorFila, int nSeries, boolean suavizarMediaMovil) {
        List<List<Float>> columnas = new ArrayList<>();
        List<Float> valoresParaEscala = new ArrayList<>();
        for (int s = 0; s < nSeries; s++) {
            List<Float> columna = new ArrayList<>();
            for (Float[] fila : valoresPorFila) columna.add(fila[s]);
            columna = GraficaModel.limpiarCeroAislado(columna);
            columna = GraficaModel.limpiarAtipicos(columna, GraficaModel.FACTOR_ATIPICO);
            if (suavizarMediaMovil && aplicaMediaMovil(maquina)) {
                columna = GraficaModel.mediaMovil(columna, ventanaMediaMovilActual());
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
        puntoAnteriorAnalisis = null;
        if (analisisGrid != null) {
            filasAnalisis.clear();
            analisisGrid.getDataProvider().refreshAll();
        }
        clicksArranque.clear();
        if (arranqueGrid != null) {
            filasArranque.clear();
            arranqueGrid.getDataProvider().refreshAll();
        }
    }

    @ClientCallable
    public void registrarClickEnGrafica(long timestamp) {
        TarjetasEstadoActual.PuntoClick actual = actualizarTarjetaUltimoClick(timestamp);
        if (modoConArranque) {
            registrarClickConArranque(actual);
        } else {
            registrarFilaAnalisis(actual);
        }
    }

    /**
     * Encadena el click actual con el anterior en un tramo Inicio→Fin (ver puntoAnteriorAnalisis
     * arriba). La tabla nunca crece: cada click a partir del segundo REEMPLAZA la única fila
     * existente en vez de agregar una nueva (a pedido — antes se acumulaba una fila por click).
     * analisisGrid es null para usuarios no-admin, así que ahí no hace nada.
     */
    private void registrarFilaAnalisis(TarjetasEstadoActual.PuntoClick actual) {
        if (actual == null || analisisGrid == null) {
            return;
        }
        if (puntoAnteriorAnalisis != null) {
            filasAnalisis.clear();
            filasAnalisis.add(new FilaAnalisis(
                    puntoAnteriorAnalisis.fecha(), puntoAnteriorAnalisis.hora(), puntoAnteriorAnalisis.kwh(),
                    actual.fecha(), actual.hora(), actual.kwh()));
            analisisGrid.getDataProvider().refreshAll();
        }
        puntoAnteriorAnalisis = actual;
    }

    /**
     * Modo "Con arranque": ciclo fijo de 4 clicks (Inicio, Fin Purga, Normalizado, Fin). Cada
     * click actualiza los campos que le corresponden en las DOS mitades de la fila (ver
     * FilaArranque) — click1 (Inicio) va tanto al grupo t1 como al t2, ya que las dos tablas
     * originales tenian su propio "Inicio" con el mismo dato. Campos de un click que todavia no
     * paso quedan en "". Al llegar al 4to el ciclo queda cerrado: clicks siguientes no hacen nada
     * hasta el doble-click (limpiarTarjetas), que lo reabre.
     */
    private void registrarClickConArranque(TarjetasEstadoActual.PuntoClick actual) {
        if (actual == null || arranqueGrid == null || clicksArranque.size() >= 4) {
            return;
        }
        clicksArranque.add(actual);

        TarjetasEstadoActual.PuntoClick inicio = clicksArranque.size() > 0 ? clicksArranque.get(0) : null;
        TarjetasEstadoActual.PuntoClick finPurga = clicksArranque.size() > 1 ? clicksArranque.get(1) : null;
        TarjetasEstadoActual.PuntoClick normalizado = clicksArranque.size() > 2 ? clicksArranque.get(2) : null;
        TarjetasEstadoActual.PuntoClick fin = clicksArranque.size() > 3 ? clicksArranque.get(3) : null;

        filasArranque.clear();
        filasArranque.add(new FilaArranque(
                // Grupo 1: Inicio/Fin.
                campo(inicio, TarjetasEstadoActual.PuntoClick::fecha), campo(inicio, TarjetasEstadoActual.PuntoClick::hora), campo(inicio, TarjetasEstadoActual.PuntoClick::kwh),
                campo(fin, TarjetasEstadoActual.PuntoClick::fecha), campo(fin, TarjetasEstadoActual.PuntoClick::hora), campo(fin, TarjetasEstadoActual.PuntoClick::kwh),
                // Grupo 2: Inicio (mismo click1)/Fin Purga/Normalizado.
                campo(inicio, TarjetasEstadoActual.PuntoClick::fecha), campo(inicio, TarjetasEstadoActual.PuntoClick::hora), campo(inicio, TarjetasEstadoActual.PuntoClick::kwh),
                campo(finPurga, TarjetasEstadoActual.PuntoClick::fecha), campo(finPurga, TarjetasEstadoActual.PuntoClick::hora), campo(finPurga, TarjetasEstadoActual.PuntoClick::kwh),
                campo(normalizado, TarjetasEstadoActual.PuntoClick::fecha), campo(normalizado, TarjetasEstadoActual.PuntoClick::hora), campo(normalizado, TarjetasEstadoActual.PuntoClick::kwh)));
        arranqueGrid.getDataProvider().refreshAll();
    }

    /** "" si el punto todavia no llego (ese click del ciclo no paso), el campo pedido si ya llego. */
    private static String campo(TarjetasEstadoActual.PuntoClick punto,
                                 java.util.function.Function<TarjetasEstadoActual.PuntoClick, String> extractor) {
        return punto == null ? "" : extractor.apply(punto);
    }

    /**
     * Tarjeta compartida (MainLayout) de Fecha/Hora/KWh del último click, y de paso la franja de
     * valores (KWh/VAB/VAC/etc.) pasa a mostrar los valores de ESE momento — a diferencia de
     * ChartsView, acá el punto clickeado puede ser de cualquier día del rango consultado, así
     * que usa la variante "Historico" (busca en el archivo mensual correspondiente).
     */
    private TarjetasEstadoActual.PuntoClick actualizarTarjetaUltimoClick(long timestamp) {
        if (this.getParent().isPresent() && this.getParent().get() instanceof MainLayout) {
            MainLayout layout = (MainLayout) this.getParent().get();
            return TarjetasEstadoActual.actualizarUltimoClickHistorico(lineaAccessService, plcDataQueryService,
                    graficaActiva, maquinaCombo.getValue(), layout, datosActualesCard, timestamp);
        }
        return null;
    }

}
