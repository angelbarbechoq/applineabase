package com.example.base.model;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class GraficaModel {

    private int nGraficas = 1;
    private Double maxY = 100.0, minY = 0.0;
    private List<Long> tiemposMarcadores = new ArrayList<>();
    private SimpleDateFormat houra = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
    private String[] seriesNames = {"Valor"};
    private int posY = 0;
    private static final int DIVISIONES_Y = 10;
    private String unidad = "";

    // Por defecto null (se usa la paleta roja/azul/verde de siempre, la que ya usan Histórico y
    // KWh/PF general) y sin leyenda (una sola serie no la necesita). Solo los gráficos que
    // explícitamente pidan otra cosa (ver ChartsView, pestaña Temperatura) llaman estos setters;
    // el resto de las pantallas que reusan GraficaModel/getInitScript2 no se ven afectadas.
    private String[] coloresPersonalizados;
    private boolean mostrarLeyenda = false;

    public void setColoresPersonalizados(String[] coloresPersonalizados) {
        this.coloresPersonalizados = coloresPersonalizados;
    }

    public void setMostrarLeyenda(boolean mostrarLeyenda) {
        this.mostrarLeyenda = mostrarLeyenda;
    }

    public String getUnidad() {
        return unidad;
    }

    public void setUnidad(String unidad) {
        this.unidad = unidad;
    }

    public GraficaModel() {
    }

    public GraficaModel(int nGraficas) {
        this.nGraficas = nGraficas;
    }
    public String getInitScript2(String containerId) {
        String[] hexColores = coloresPersonalizados != null
                ? coloresPersonalizados : new String[]{"0xc83830", "0x4472c4", "0x70ad47"};
        StringBuilder coloresJs = new StringBuilder();
        for (int i = 0; i < hexColores.length; i++) {
            if (i > 0) coloresJs.append(", ");
            coloresJs.append("am5.color(").append(hexColores[i]).append(")");
        }
        String colors = "var colors = [" + coloresJs + "];";
        StringBuilder seriesNamesJs = new StringBuilder("var seriesNames = [");
        String unidadJs = "var unidad = '" + unidad + "';";
        for (int i = 0; i < seriesNames.length; i++) {
            seriesNamesJs.append("'").append(seriesNames[i]).append("'");
            if (i < seriesNames.length - 1) seriesNamesJs.append(", ");
        }
        seriesNamesJs.append("];");

        return
                // PASO 0: Validar/Inicializar contenedor global
                "if (!window.am5Charts) { window.am5Charts = {}; }" +
                        "var id = '" + containerId + "';" +
                        "if (window.am5Charts[id] && window.am5Charts[id].root) {" +
                        "  try { window.am5Charts[id].root.dispose(); } catch(e) {}" +
                        "}" +

                        // PASO 1: Root y temas
                        "var root = am5.Root.new(id);" +
                        "root.setThemes([am5themes_Animated.new(root)]);" +

                        // PASO 2: Chart SIN cursor inicial
                        // maxTooltipDistance NO va en 0: en amCharts5 eso fuerza a mostrar solo
                        // el tooltip mas cercano al cursor y ocultar el resto — justo lo contrario
                        // de lo que queremos con 2+ series (ver el valor de todas a la vez).
                        "var chart = root.container.children.push(am5xy.XYChart.new(root, { panX: false, panY: false, wheelX: 'none', wheelY: 'zoomY', pinchZoomX: true, pinchZoomY: true }));" +

                        // PASO 3: Scrollbar
                        "var scrollbarX = am5xy.XYChartScrollbar.new(root, { orientation: 'horizontal', height: 15});" +
                        "chart.set('scrollbarX', scrollbarX);" +
                        "chart.bottomAxesContainer.children.push(scrollbarX);" +

                        // PASO 4: Crear EJES PRIMERO
                        "var xAxis = chart.xAxes.push(am5xy.DateAxis.new(root, { maxDeviation: 0.2, baseInterval: { timeUnit: 'second', count: 1 }, renderer: am5xy.AxisRendererX.new(root, { minGridDistance: 50 }) }));" +
                        "var xTooltip = xAxis.set('tooltip', am5.Tooltip.new(root, {}));" +
                        "xTooltip.label.setAll({ fontSize: '11px', textAlign: 'center' });" +
                        "xAxis.set('tooltipDateFormat', 'dd-MM-yyyy\\nHH:mm:ss');" +
                        "console.log('✓ Eje X creado con tooltip');" +

                        "var yAxis = chart.yAxes.push(am5xy.ValueAxis.new(root, { renderer: am5xy.AxisRendererY.new(root, {}) }));" +
                        "yAxis.set('tooltip', am5.Tooltip.new(root, {}));" +
                        "console.log('✓ Eje Y creado con tooltip. Zoom inicial: " + minY + " - " + maxY + "');" +
                        // PASO 5: Crear CURSOR con ejes (pero sin snapToSeries aún)
                        "var cursor = chart.set('cursor', am5xy.XYCursor.new(root, { yAxis: yAxis, xAxis: xAxis, behavior: 'zoomXY' }));" +
                        "cursor.lineX.setAll({ visible: true });" +
                        "cursor.lineY.setAll({ visible: true });" +

                        // PASO 6: Definir colores y nombres
                        colors +
                        seriesNamesJs.toString() +
                        unidadJs +
                        // PASO 7: CREAR SERIES con tooltips individuales
                        "var seriesList = [];" +
                        "console.log('🔍 Iniciando loop. nGraficas: " + nGraficas + "');" +
                        "for(var i=0; i < " + nGraficas + "; i++) {" +
                        "  console.log('  📍 Iteración ' + i + ' de " + nGraficas + "');" +
                        "  var series = chart.series.push(am5xy.LineSeries.new(root, { name: seriesNames[i], xAxis: xAxis, yAxis: yAxis, valueYField: 'value', valueXField: 'date', strokeWidth: 2, snapToTooltip: true }));" +
                        // series.set('stroke', ...) es el que manda (color propio de la serie) —
                        // sin esto, amCharts5 puede pisar lo que se puso en strokes.template con su
                        // propio color automático. Pero forzarlo SIEMPRE cambiaba el color de TODOS
                        // los gráficos (KWh, PF general, Histórico) a los rojo/azul/verde por defecto,
                        // no solo el gráfico que pidió colores propios (Temperatura); por eso el forzado
                        // extra solo aplica cuando alguien llamó setColoresPersonalizados — el resto
                        // de las pantallas queda exactamente como estaba antes de este ajuste.
                        (coloresPersonalizados != null ? "  series.set('stroke', colors[i % colors.length]);" : "") +
                        "  series.strokes.template.setAll({ stroke: colors[i % colors.length] });" +
                        "  var tooltip = series.set('tooltip', am5.Tooltip.new(root, { pointerOrientation: 'vertical' }));" +
                        //"  tooltip.label.setAll({ text: '[bold]' + seriesNames[i] + ':[/] {valueY.formatNumber(\\u0022#.##\\u0022)}\\n{valueX.formatDate(\\u0022dd-MM-yyyy HH:mm:ss\\u0022)}' });" +
                        //"  tooltip.set(\"getFillFromSprite\", false); tooltip.get(\"background\").setAll({ fillOpacity: 0, strokeOpacity: 0 }); tooltip.label.setAll({ text: '[bold]' + seriesNames[i] + ':[/] {valueY.formatNumber(\\u0022#.##\\u0022)}' });" +
                        //"  tooltip.setAll({ autoTextColor: false, getFillFromSprite: false }); tooltip.get(\"background\").setAll({ fillOpacity: 0, strokeOpacity: 0 }); tooltip.label.setAll({ fill: am5.color(0x999999), text: '[bold]' + seriesNames[i] + ':[/] {valueY.formatNumber(\\u0022#.##\\u0022)}' });" +
                        "  tooltip.setAll({ autoTextColor: false, getFillFromSprite: false }); tooltip.get(\"background\").setAll({ fillOpacity: 0, strokeOpacity: 0 }); tooltip.label.setAll({ fill: am5.color(0x999999), text: '{name}: {valueY.formatNumber(\\u0022#.##\\u0022)} ' + unidad });" +
                        "  seriesList.push(series);" +
                        "  console.log('    ✅ Serie ' + i + ' agregada. Total: ' + seriesList.length);" +
                        "}" +
                        "console.log('✅ Loop finalizado. Total de series: ' + seriesList.length);" +

                        // Leyenda opcional (ver setMostrarLeyenda): solo la piden gráficos con
                        // 2+ series que necesitan distinguir identidad por color (p.ej.
                        // Temperatura Agua vs Ambiente); una sola serie no la necesita, y el
                        // resto de las pantallas (Histórico, KWh, PF general) no la activan.
                        (mostrarLeyenda ?
                        "var legend = chart.children.push(am5.Legend.new(root, { centerX: am5.p50, x: am5.p50 }));" +
                        "legend.data.setAll(chart.series.values);" : "") +

                        // PASO 8: ASIGNAR snapToSeries al cursor DESPUÉS de tener series
                        "console.log('🔴 ANTES, snapToSeries:', cursor.get('snapToSeries'));" +
                        "cursor.setAll({ snapToSeries: seriesList, snapToSeriesBy: 'x' });" +
                        "console.log('🟢 DESPUÉS, snapToSeries.length:', cursor.get('snapToSeries').length);" +
                        // Con 2+ series, amCharts5 por defecto solo muestra el tooltip de la serie
                        // más cercana al cursor, no el de todas — se fuerza a mostrar el de cada
                        // una en cada movimiento para poder comparar los valores a simple vista.
                        "cursor.events.on('cursormoved', function() {" +
                        "  seriesList.forEach(function(s) { s.showTooltip(); });" +
                        "});" +


                        // PASO 9: AGREGAR tooltip separado del cursor (para mostrar fecha/hora al pasar el ratón)
//                        "var cursorTooltip = cursor.set('tooltip', am5.Tooltip.new(root, { pointerOrientation: 'vertical' }));" +
//                        "cursorTooltip.label.setAll({ text: '{valueX.formatDate(\\u0022dd-MM-yyyy HH:mm:ss\\u0022)}' });" +
//                        "console.log('✓ Cursor tooltip configurado');" +

                        // PASO 10: Almacenar referencias globales.
                        // aplicarZoomCalculado es la ÚNICA función que aplica el zoom piso+percentil
                        // al eje Y. Doble-click, "Reset Zoom" y la carga inicial la llaman a ella
                        // (nunca repiten la lógica de fijar min/max por su cuenta), para que las tres
                        // formas de "resetear el zoom" hagan siempre exactamente lo mismo.
                        // Se fija min/max directamente (no con zoomToValues, que es conocido por
                        // no aplicar nada si el eje aún no calculó su min/max privado la primera vez)
                        // para forzar de forma confiable el piso en cero + techo calculado. Además
                        // de la escala (min/max), se debe resetear la VENTANA de zoom en sí
                        // (yAxis.zoom(0,1)): son dos cosas independientes en amCharts5, y sin este
                        // reset, un zoom manual previo con el ratón (que solo mueve la ventana, no
                        // la escala) dejaba una ventana angosta aplicada sobre la nueva escala, y
                        // el resultado se veía diminuto en vez de mostrar cero-a-techo completo.
                        "window.am5Charts[id] = { root: root, chart: chart, xAxis: xAxis, yAxis: yAxis, seriesList: seriesList, cursor: cursor, tiemposMarcadores: [], posY: 0, lastClickTime: 0, containerId: '" + containerId + "', marcadores: [], aplicarZoomCalculado: function() { yAxis.set('min', " + minY + "); yAxis.set('max', " + maxY + "); yAxis.zoom(0, 1); } };" +
                        "console.log('✓ am5Charts inicializado');" +

                        // PASO 10b: en cuanto el usuario interactúa manualmente con el eje Y
                        // (rueda del ratón o arrastre de zoom), se libera el piso/techo fijado
                        // por aplicarZoomCalculado, para que el eje vuelva a autoajustarse
                        // libremente a los datos visibles (autoZoom nativo, activo por defecto).
                        "chart.plotContainer.events.on('wheel', function() { yAxis.remove('min'); yAxis.remove('max'); });" +
                        "cursor.events.on('selectended', function() { yAxis.remove('min'); yAxis.remove('max'); });" +

                        // PASO 11: Event listener para clicks
                        "chart.plotContainer.events.on('click', function(ev) {" +
                        "  var inst = window.am5Charts['" + containerId + "'];" +
                        "  if (!inst) return;" +
                        "  var nowTime = new Date().getTime();" +
                        "  var isDoubleClick = (nowTime - inst.lastClickTime) < 300;" +
                        "  inst.lastClickTime = nowTime;" +
                        "  if (isDoubleClick) {" +
                        "    try {" +
                        "      inst.tiemposMarcadores = [];" +
                        "      inst.posY = 0;" +
                        "      inst.marcadores.forEach(function(marker) { marker.dispose(); });" +
                        "      inst.marcadores = [];" +
                        // setTimeout en 0: el doble-click también dispara 'selectended' en el
                        // cursor (aunque sea un click sin arrastre), que libera min/max del eje
                        // Y (ver PASO 10b). Se difiere para que aplicarZoomCalculado corra
                        // después de eso en el mismo ciclo, y el cero+techo quede fijado al final.
                        "      setTimeout(function() { inst.aplicarZoomCalculado(); }, 0);" +
                        "      if ($0.$server && $0.$server.limpiarTarjetas) { $0.$server.limpiarTarjetas(); }"+
                        "    } catch(e) {" +
                        "      console.error('Error en doble-click:', e);" +
                        "    }" +
                        "  } else {" +
                        "    try {" +
                        "      var timestamp = null;" +
                        "      if (ev.point) {" +
                        "        var localPoint = chart.plotContainer.toLocal(ev.point);" +
                        "        var axisPosition = inst.xAxis.toAxisPosition(localPoint.x / chart.plotContainer.width());" +
                        "        timestamp = inst.xAxis.positionToValue(axisPosition);" +
                        "      }" +
                        "      if (!timestamp) {" +
                        "        if (inst.seriesList && inst.seriesList.length > 0) {" +
                        "          for (var s = 0; s < inst.seriesList.length; s++) {" +
                        "            var sTooltip = inst.seriesList[s].get('tooltip');" +
                        "            if (sTooltip && sTooltip.get('dataItem')) {" +
                        "              timestamp = sTooltip.get('dataItem').get('valueX');" +
                        "              if (timestamp) break;" +
                        "            }" +
                        "          }" +
                        "        }" +
                        "      }" +
                        "      if (!timestamp) {" +
                        "        var snapData = inst.cursor.getPrivate(\"dataItem\");" +
                        "        if (snapData && snapData.get(\"valueX\")) {" +
                        "          timestamp = snapData.get(\"valueX\");" +
                        "        } else {" +
                        "          timestamp = inst.xAxis.positionToValue(inst.cursor.getPrivate(\"positionX\"));" +
                        "        }" +
                        "      }" +
                        "      if (timestamp) {" +
                        "        var fechaCompleta = new Date(timestamp).toLocaleDateString('es-ES') + ' ' + new Date(timestamp).toLocaleTimeString('es-ES', {hour: '2-digit', minute: '2-digit', second: '2-digit'});" +
                        "        var rangeDataItem = inst.xAxis.makeDataItem({ value: timestamp });" +
                        "        var range = inst.xAxis.createAxisRange(rangeDataItem);" +
                        "        setTimeout(function() {" +
                        "          if (range.get('grid')) {" +
                        "            range.get('grid').setAll({ stroke: am5.color(0xd32f2f), strokeWidth: 1, strokeDasharray: [3, 3], strokeOpacity: 0.6, visible: true });" +
                        "          }" +
                        "        }, 0);" +
                        "        var offsetUp = inst.tiemposMarcadores.length * -21;" +
                        "        inst.marcadores.push(rangeDataItem);" +
                        "        var textoFinalLabel = \"[bold #d32f2f]\" + fechaCompleta + \"[/]\";" +
                        "        if (inst.tiemposMarcadores.length > 0) {" +
                        "          var tiempoAnterior = inst.tiemposMarcadores[inst.tiemposMarcadores.length - 1];" +
                        "          var diff = Math.floor((timestamp - tiempoAnterior) / 1000);" +
                        "          var horas = Math.floor(diff / 3600);" +
                        "          var minutos = Math.floor((diff % 3600) / 60);" +
                        "          var segundos = diff % 60;" +
                        "          var tiempoStr = String(horas).padStart(2, '0') + ':' + String(minutos).padStart(2, '0') + ':' + String(segundos).padStart(2, '0');" +
                        "          textoFinalLabel += \"\\n[bold #4a6572]Δ \" + tiempoStr + \"[/]\";" +
                        "        }" +
                        "        range.get('label').setAll({ text: textoFinalLabel, fontWeight: 'bold', fontSize: '10px', visible: true, inside: true, centerX: am5.p0, centerY: am5.p100, dy: offsetUp - 25, dx: 5 });" +
                        "        inst.tiemposMarcadores.push(timestamp);" +
                        "        if ($0.$server && $0.$server.registrarClickEnGrafica) { $0.$server.registrarClickEnGrafica(timestamp); }" +
                        "      }" +
                        "    } catch(e) {" +
                        "      console.error('Error:', e);" +
                        "    }" +
                        "  }" +
                        "});" +

                        // PASO 12: Animar aparición
                        "chart.appear(1000, 100);";
    }

    /**
     * Aplica el zoom inicial (piso + percentil) al eje Y. Se debe llamar DESPUÉS de haber
     * agregado todos los puntos con getAddDataScript, no antes: como el eje no tiene min/max
     * fijo (para que el zoom manual del usuario funcione), cada dato agregado dispara el
     * auto-ajuste nativo de amCharts, que iría pisando cualquier zoom que se aplique antes de
     * tiempo. El doble requestAnimationFrame espera a que amCharts termine de procesar todos
     * los datos ya cargados antes de forzar este zoom.
     */
    public String getAplicarZoomInicialScript(String containerId) {
        return
                "requestAnimationFrame(function() {" +
                "  requestAnimationFrame(function() {" +
                "    var inst = window.am5Charts['" + containerId + "'];" +
                "    if (inst) { inst.aplicarZoomCalculado(); }" +
                "  });" +
                "});";
    }

    /**
     * Script del botón "Reset Zoom": vuelve el eje de tiempo (X) al rango completo y aplica
     * el zoom calculado (piso + percentil) en Y. Única función para esto: la usan tanto
     * ChartsView como HistoricoView, para que el botón haga siempre lo mismo en las dos vistas.
     */
    public String getResetZoomScript(String containerId) {
        return
                "if (window.am5Charts && window.am5Charts['" + containerId + "']) {" +
                "  var inst = window.am5Charts['" + containerId + "'];" +
                "  inst.xAxis.zoom(0, 1);" +
                "  inst.aplicarZoomCalculado();" +
                "  console.log('🔄 Zoom reseteado');" +
                "}";
    }

    public record ResultadoGrafica(String script, int puntosGraficados) {
    }

    /**
     * Arma el script completo (init + todos los puntos + zoom inicial) para graficar una
     * serie de KWh: calcula la serie (diferencia o valor directo), limpia atípicos, aplica
     * el preset por máquina como piso del eje Y (ampliándolo con el percentil 95 si los
     * datos reales lo superan), y arma el batch de JS. Única función para esta orquestación:
     * la usan tanto la gráfica en vivo (ChartsView) como el histórico (HistoricoView), para
     * que ambas grafiquen siempre igual a partir de los mismos datos.
     */
    public ResultadoGrafica graficarSerieKWh(String containerId, List<Map<String, Object>> datos,
                                              boolean conDiferencia, String maquina,
                                              String[] seriesNames, boolean limitarPuntos) {
        setSeriesNames(seriesNames);
        SerieKWh serie = calcularSerieKWh(datos, conDiferencia);

        // Se reemplazan los atípicos (p.ej. por una falla de comunicación) por una
        // interpolación de sus vecinos, para que ni se grafiquen ni inflen la escala.
        List<Float> valoresLimpios = limpiarAtipicos(serie.valores(), FACTOR_ATIPICO);

        setMinY(0.0);
        aplicarRangosPredefinidos(maquina);
        // El preset actúa como piso; si los datos reales lo superan, se amplía el eje.
        // Se usa el percentil 95 en vez del máximo crudo como margen adicional de seguridad.
        double p95 = percentil(valoresLimpios, 0.95);
        double maxConMargen = p95 * 1.1;
        if (maxConMargen > getMaxY()) {
            setMaxY(maxConMargen);
        }

        StringBuilder script = new StringBuilder();
        script.append(getInitScript2(containerId));
        for (int i = 0; i < serie.timestamps().size(); i++) {
            script.append(getAddDataScript(containerId, serie.timestamps().get(i), new Float[]{valoresLimpios.get(i)}, limitarPuntos));
        }
        script.append(getAplicarZoomInicialScript(containerId));

        return new ResultadoGrafica(script.toString(), serie.timestamps().size());
    }

    /**
     * Arma el script completo (init + todos los puntos + zoom inicial) para graficar N series
     * de valor crudo ya obtenidas por separado (una por máquina/sensor), alineadas por fecha
     * exacta. A diferencia de graficarSerieKWh (que grafica UNA máquina), esta se usa para
     * comparar en un mismo gráfico el valor de varios sensores (p.ej. Temperatura del agua vs
     * Temperatura ambiente). Un dato faltante en una serie para una fecha dada simplemente no
     * agrega ese punto en esa serie (getAddDataScript ya lo maneja), sin romper el resto.
     */
    public ResultadoGrafica graficarSeriesCrudasAlineadas(String containerId, List<List<Map<String, Object>>> datosPorSerie,
                                                           String[] seriesNames, double maxYDefault, boolean limitarPuntos) {
        setSeriesNames(seriesNames);
        int n = datosPorSerie.size();
        SimpleDateFormat formatter = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");

        // Las máquinas que comparten ciclo de lectura (mismo PLC, mismo loop) escriben con el
        // mismo timestamp exacto, por eso alinear por texto de fecha funciona sin interpolar.
        TreeMap<String, Float[]> porFecha = new TreeMap<>();
        for (int s = 0; s < n; s++) {
            for (Map<String, Object> row : datosPorSerie.get(s)) {
                String fecha = (String) row.get("fecha");
                Float valor = toFloat(row.get("kwh"));
                if (fecha == null || valor == null) continue;
                porFecha.computeIfAbsent(fecha, k -> new Float[n])[s] = valor;
            }
        }

        List<String> fechas = new ArrayList<>(porFecha.keySet());
        List<List<Float>> columnas = new ArrayList<>();
        List<Float> valoresParaEscala = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            List<Float> columna = new ArrayList<>();
            for (String fecha : fechas) columna.add(porFecha.get(fecha)[s]);
            List<Float> columnaLimpia = limpiarAtipicos(columna, FACTOR_ATIPICO);
            columnas.add(columnaLimpia);
            for (Float v : columnaLimpia) {
                if (v != null && v > 0) valoresParaEscala.add(v);
            }
        }

        setMinY(0.0);
        double p95 = percentil(valoresParaEscala, 0.95);
        setMaxY(Math.max(maxYDefault, p95 * 1.1));

        StringBuilder script = new StringBuilder();
        script.append(getInitScript2(containerId));
        for (int i = 0; i < fechas.size(); i++) {
            try {
                long ts = formatter.parse(fechas.get(i)).getTime();
                Float[] valores = new Float[n];
                for (int s = 0; s < n; s++) valores[s] = columnas.get(s).get(i);
                script.append(getAddDataScript(containerId, ts, valores, limitarPuntos));
            } catch (Exception ignored) {}
        }
        script.append(getAplicarZoomInicialScript(containerId));

        return new ResultadoGrafica(script.toString(), fechas.size());
    }

    public String getAddDataScript(String containerId, long timestamp, Float[] dato, boolean limit) {
        int maxPoints = 1440; // 24 horas a 1 punto/minuto
        StringBuilder sb = new StringBuilder();

        sb.append("if (window.am5Charts && window.am5Charts['").append(containerId).append("']) {")
          .append("  var inst = window.am5Charts['").append(containerId).append("'];");

        for (int ix = 0; ix < nGraficas; ix++) {
            if (ix < dato.length && dato[ix] != null) {
                sb.append("  if (inst.seriesList[").append(ix).append("]) {")
                  .append("    var s = inst.seriesList[").append(ix).append("];")
                  .append("    s.data.push({ date: ").append(timestamp).append(", value: ").append(dato[ix]).append(" });");

                if (limit) {
                    sb.append("    if (s.data.length > ").append(maxPoints).append(") { s.data.shift(); }");
                }

                sb.append("    s.markDirtyValues();")
                  .append("  }");
            }
        }

        sb.append("}");

        return sb.toString();
    }

    private static final int MAX_MARCADORES_HISTORIAL = 100;

    public void registrarClick(long timestamp) {
        this.tiemposMarcadores.add(timestamp);
        // Ya no hay botón que limpie esta lista (se maneja del lado del cliente con el
        // doble-click); se acota acá para que no crezca sin límite en una sesión larga.
        if (this.tiemposMarcadores.size() > MAX_MARCADORES_HISTORIAL) {
            this.tiemposMarcadores.remove(0);
        }
        System.out.println("Click en: " + houra.format(new Date(timestamp)));
        if (tiemposMarcadores.size() > 1) {
            long tiempoAnterior = tiemposMarcadores.get(tiemposMarcadores.size() - 2);
            System.out.println("Tiempo transcurrido: " + formatearDuracion(timestamp - tiempoAnterior));
        }
    }

    public List<Long> obtenerMarcadores() {
        return new ArrayList<>(tiemposMarcadores);
    }

    public String obtenerTiempoTranscurrido(int indice1, int indice2) {
        if (indice1 < 0 || indice2 < 0 || indice1 >= tiemposMarcadores.size() || indice2 >= tiemposMarcadores.size()) {
            return "00:00:00";
        }
        return formatearDuracion(Math.abs(tiemposMarcadores.get(indice2) - tiemposMarcadores.get(indice1)));
    }

    /** Formatea una duración en milisegundos como HH:mm:ss. */
    private static String formatearDuracion(long milisegundos) {
        long horas = milisegundos / (1000 * 60 * 60);
        long minutos = (milisegundos % (1000 * 60 * 60)) / (1000 * 60);
        long segundos = (milisegundos % (1000 * 60)) / 1000;
        return String.format("%02d:%02d:%02d", horas, minutos, segundos);
    }
    /**
     * Clasifica la máquina y fija la unidad correspondiente en el modelo. Devuelve true si
     * corresponde graficar la diferencia entre lecturas consecutivas de kwh (máquinas de
     * energía), o false si se grafica el valor directo (Temperatura/Psi/Bar).
     *
     * Única función para esta decisión: la usan tanto la gráfica en vivo como el histórico,
     * para que ambas clasifiquen la máquina exactamente igual.
     */
    public boolean clasificarYFijarUnidad(String maquina) {
        if (maquina.contains("Temperatura")) {
            setUnidad("°C");
            return false;
        }
        if (maquina.contains("Psi")) {
            setUnidad("PSI");
            return false;
        }
        if (maquina.contains("Bar")) {
            setUnidad("BAR");
            return false;
        }
        setUnidad("KWh");
        return true;
    }

    public record SerieKWh(List<Long> timestamps, List<Float> valores) {
    }

    /**
     * Calcula la serie de KWh a graficar: diferencia entre lecturas consecutivas si
     * esDiferencia es true, o el valor directo si es false. No recorta diferencias
     * negativas (p.ej. por un reinicio de contador): se grafican tal cual, igual en
     * cualquier pantalla. Una fila con fecha o valor inválido se salta sin interrumpir
     * el resto de la serie.
     *
     * Única función para este cálculo: la usan tanto la gráfica en vivo como el
     * histórico, para que ambas grafiquen exactamente lo mismo a partir de los mismos datos.
     */
    public static SerieKWh calcularSerieKWh(List<Map<String, Object>> datos, boolean esDiferencia) {
        SimpleDateFormat formatter = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
        List<Long> timestamps = new ArrayList<>();
        List<Float> valores = new ArrayList<>();

        if (esDiferencia) {
            for (int i = 1; i < datos.size(); i++) {
                try {
                    double actual = ((Number) datos.get(i).get("kwh")).doubleValue();
                    double anterior = ((Number) datos.get(i - 1).get("kwh")).doubleValue();
                    // La energía nunca es negativa: un reinicio de contador da una resta
                    // negativa que no es un valor real, se toma en valor absoluto.
                    float diferencia = (float) Math.abs(actual - anterior);
                    // Un NaN/Infinito (ej. división por cero en un cálculo previo) no se
                    // grafica: amCharts puede colgarse si recibe un valor no finito.
                    if (!Float.isFinite(diferencia)) continue;
                    long ts = formatter.parse((String) datos.get(i).get("fecha")).getTime();
                    timestamps.add(ts);
                    valores.add(diferencia);
                } catch (Exception ignored) {}
            }
        } else {
            for (Map<String, Object> row : datos) {
                try {
                    float valor = (float) ((Number) row.get("kwh")).doubleValue();
                    if (!Float.isFinite(valor)) continue;
                    long ts = formatter.parse((String) row.get("fecha")).getTime();
                    timestamps.add(ts);
                    valores.add(valor);
                } catch (Exception ignored) {}
            }
        }
        return new SerieKWh(timestamps, valores);
    }

    public void aplicarRangosPredefinidos(String maquina) {
        if (maquina.contains("Linea")) {
            setMinY(0.0);
            this.setMaxY(2.0);
        } else if (maquina.contains("Temperatura")) {
            this.setMinY(0.0);
            this.setMaxY(30.0);
        } else if (maquina.contains("Psi")) {
            this.setMinY(0.0);
            this.setMaxY(140.0);
        } else if (maquina.contains("Bar")) {
            this.setMinY(0.0);
            this.setMaxY(40.0);
        } else if (maquina.contains("Molino")) {
            this.setMinY(0.0);
            this.setMaxY(4.0);
        } else if (maquina.contains("Mixer")) {
            this.setMinY(0.0);
            this.setMaxY(3.0);
        }else if (maquina.contains("Planta")) {
            this.setMinY(0.0);
            this.setMaxY(40.0);
        }else if (maquina.contains("Trafo")) {
            this.setMinY(0.0);
            this.setMaxY(25.0);
        }else if (maquina.contains("GA7")) {
            this.setMinY(0.0);
            this.setMaxY(2.2);
        }else if (maquina.contains("Chiller4")) {
            this.setMinY(0.0);
            this.setMaxY(4.0);
        }else if (maquina.contains("Inyeccion")) {
            this.setMinY(0.0);
            this.setMaxY(6.0);
        }else {
            this.setMinY(0.0);
            this.setMaxY(2.5);
        }
    }

    /**
     * Percentil p (0-1) de una lista de valores. Se usa en vez del máximo crudo
     * para fijar el techo del eje Y, así un solo pico atípico (por ejemplo, por
     * una falla de comunicación) no infla la escala completa del gráfico.
     */
    public static double percentil(List<Float> valores, double p) {
        if (valores == null || valores.isEmpty()) return 0.0;
        List<Float> ordenado = new ArrayList<>();
        for (Float v : valores) {
            if (v != null) ordenado.add(v);
        }
        if (ordenado.isEmpty()) return 0.0;
        Collections.sort(ordenado);
        int idx = (int) Math.ceil(p * ordenado.size()) - 1;
        idx = Math.max(0, Math.min(idx, ordenado.size() - 1));
        return ordenado.get(idx);
    }

    /**
     * Convierte a Float, o null si el dato falta, no se puede convertir, o no es un número
     * finito (NaN/Infinito, típico de una división por cero en el cálculo del PF): un dato
     * faltante o inválido no se grafica (no se muestra como si fuera un 0 real, y amCharts
     * puede colgarse si recibe un NaN/Infinito al calcular la escala del eje).
     *
     * Única función para esta conversión: la usan tanto ChartsView como HistoricoView.
     */
    public static Float toFloat(Object v) {
        if (v == null) return null;
        try {
            float f = ((Number) v).floatValue();
            return Float.isFinite(f) ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Igual que toFloat, pero en valor absoluto (voltaje, corriente, potencia y PF nunca son negativos en la realidad). */
    public static Float toFloatAbs(Object v) {
        Float f = toFloat(v);
        return f == null ? null : Math.abs(f);
    }

    /** Un valor se considera atípico si supera este múltiplo del percentil 90 de la serie. */
    public static final double FACTOR_ATIPICO = 10.0;

    /**
     * Reemplaza los valores atípicos (mayores a FACTOR_ATIPICO veces el percentil 90 de la
     * propia serie) por una interpolación de sus vecinos válidos más cercanos, para que un
     * pico por falla de comunicación no aparezca en el gráfico ni infle el auto-ajuste
     * nativo del eje Y al hacer zoom. Se usa el percentil 90 (no la mediana) como referencia
     * porque en series con muchos ceros legítimos (máquina detenida) la mediana puede ser 0,
     * lo que rompería la detección.
     */
    public static List<Float> limpiarAtipicos(List<Float> valores, double factor) {
        if (valores == null || valores.isEmpty()) return valores;

        double referencia = percentil(valores, 0.9);
        if (referencia <= 0) {
            return new ArrayList<>(valores); // no hay una base confiable para detectar atípicos
        }
        double umbral = referencia * factor;

        int n = valores.size();
        boolean[] esAtipico = new boolean[n];
        for (int i = 0; i < n; i++) {
            Float v = valores.get(i);
            esAtipico[i] = v != null && v > umbral;
        }

        List<Float> limpio = new ArrayList<>(valores);
        for (int i = 0; i < n; i++) {
            if (!esAtipico[i]) continue;

            Float anterior = null;
            for (int j = i - 1; j >= 0; j--) {
                if (!esAtipico[j] && valores.get(j) != null) { anterior = valores.get(j); break; }
            }
            Float siguiente = null;
            for (int j = i + 1; j < n; j++) {
                if (!esAtipico[j] && valores.get(j) != null) { siguiente = valores.get(j); break; }
            }

            float reemplazo;
            if (anterior != null && siguiente != null) reemplazo = (anterior + siguiente) / 2f;
            else if (anterior != null) reemplazo = anterior;
            else if (siguiente != null) reemplazo = siguiente;
            else reemplazo = (float) referencia;

            limpio.set(i, reemplazo);
        }
        return limpio;
    }

    /**
     * Script de un gráfico de barras (Horas trabajadas en el mes) por línea, un solo eje de
     * valor y una sola serie — a pedido, ya no se muestra el KWh en este gráfico. Tono celeste
     * único (una sola serie no necesita leyenda) y el valor sobre cada barra. El orden de las
     * categorías es el que traiga la lista (el llamador ya la ordena de mayor a menor). No es
     * una serie temporal: a diferencia de getInitScript2/getAddDataScript, arma el gráfico
     * completo de una sola vez (no hay streaming de puntos), porque el dato de origen es un
     * snapshot.
     */
    public static String getBarChartScript(String containerId, List<String> categorias,
                                            List<Double> valoresHoras) {
        StringBuilder datos = new StringBuilder("[");
        for (int i = 0; i < categorias.size(); i++) {
            if (i > 0) datos.append(",");
            datos.append("{ categoria: ").append(jsString(categorias.get(i)))
                    .append(", horas: ").append(valoresHoras.get(i)).append(" }");
        }
        datos.append("]");

        return
                "if (!window.am5Charts) { window.am5Charts = {}; }" +
                "var id = '" + containerId + "';" +
                "if (window.am5Charts[id] && window.am5Charts[id].root) {" +
                "  try { window.am5Charts[id].root.dispose(); } catch(e) {}" +
                "}" +
                "var root = am5.Root.new(id);" +
                "root.setThemes([am5themes_Animated.new(root)]);" +
                "var chart = root.container.children.push(am5xy.XYChart.new(root, { panX: false, panY: false, wheelX: 'none', wheelY: 'none', paddingTop: 24 }));" +
                "var datos = " + datos + ";" +
                "var xAxis = chart.xAxes.push(am5xy.CategoryAxis.new(root, { categoryField: 'categoria', renderer: am5xy.AxisRendererX.new(root, { minGridDistance: 20 }) }));" +
                "xAxis.get('renderer').labels.template.setAll({ rotation: -45, centerY: am5.p50, centerX: am5.p100, fontSize: '11px', fill: am5.color(0x898781) });" +
                "xAxis.get('renderer').grid.template.setAll({ stroke: am5.color(0xe1e0d9), strokeWidth: 1 });" +
                "xAxis.data.setAll(datos);" +
                "var yAxis = chart.yAxes.push(am5xy.ValueAxis.new(root, { min: 0, renderer: am5xy.AxisRendererY.new(root, {}) }));" +
                "yAxis.get('renderer').labels.template.setAll({ fill: am5.color(0x898781), fontSize: '11px' });" +
                "yAxis.get('renderer').grid.template.setAll({ stroke: am5.color(0xe1e0d9), strokeWidth: 1 });" +
                "var serieHoras = chart.series.push(am5xy.ColumnSeries.new(root, { name: 'Horas trabajadas en el mes', xAxis: xAxis, yAxis: yAxis, valueYField: 'horas', categoryXField: 'categoria' }));" +
                "serieHoras.columns.template.setAll({ fill: am5.color(0x29b6f6), stroke: am5.color(0x29b6f6), strokeOpacity: 0, width: am5.percent(60), cornerRadiusTL: 4, cornerRadiusTR: 4, tooltipText: '{categoryX}: {valueY.formatNumber(\\u0022#.##\\u0022)} h' });" +
                "serieHoras.bullets.push(function() {" +
                "  return am5.Bullet.new(root, { locationY: 1, sprite: am5.Label.new(root, {" +
                "    text: '{valueY.formatNumber(\\u0022#.##\\u0022)}'," +
                "    centerX: am5.p50, centerY: am5.p100, dy: -8, fontSize: '11px', fill: am5.color(0x52514e), populateText: true" +
                "  }) });" +
                "});" +
                "serieHoras.data.setAll(datos);" +
                "chart.set('cursor', am5xy.XYCursor.new(root, { behavior: 'none', xAxis: xAxis }));" +
                "window.am5Charts[id] = { root: root, chart: chart };" +
                "serieHoras.appear(1000, 100);" +
                "chart.appear(1000, 100);";
    }

    /** Escapa un string para insertarlo como literal JS entre comillas simples. */
    private static String jsString(String valor) {
        String seguro = valor == null ? "" : valor.replace("\\", "\\\\").replace("'", "\\'");
        return "'" + seguro + "'";
    }

    public Double getMinY() { return minY; }
    public Double getMaxY() { return maxY; }
    public void setMinY(Double minY) { this.minY = minY; }
    public void setMaxY(Double maxY) { this.maxY = maxY; }
    public SimpleDateFormat getFormatter() { return houra; }
    public int getnGraficas() { return nGraficas; }
    public void setnGraficas(int nGraficas) { this.nGraficas = nGraficas; }
    public String[] getSeriesNames() { return seriesNames; }
    public void setSeriesNames(String[] seriesNames) { this.seriesNames = seriesNames; }

    /**
     * HTML de texto plano para la franja de valores en vivo (KWh/VAB/VAC/VBC/IA/IB/IC/PW/PF),
     * con la corriente en un color propio. Usado por ChartsView e HistoricoView (mismo texto,
     * misma posición junto al título) para no duplicar el marcado en cada pantalla.
     */
    public static String construirHtmlValoresActuales(Map<String, Object> datosVIP, Map<String, Object> datosKWh) {
        String[] labels = {"KWh", "VAB", "VAC", "VBC", "IA", "IB", "IC", "PW", "PF"};
        double[] valores = {
                toDoubleSeguro(datosKWh.get("kwh")),
                toDoubleSeguro(datosVIP.get("VAB")),
                toDoubleSeguro(datosVIP.get("VAC")),
                toDoubleSeguro(datosVIP.get("VBC")),
                toDoubleSeguro(datosVIP.get("IA")),
                toDoubleSeguro(datosVIP.get("IB")),
                toDoubleSeguro(datosVIP.get("IC")),
                toDoubleSeguro(datosVIP.get("PW")),
                toDoubleSeguro(datosVIP.get("PF"))
        };

        StringBuilder html = new StringBuilder();
        html.append("<div style='display: flex; gap: 14px; flex-wrap: wrap; align-items: baseline;'>");
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                html.append("<span style='color: #c3c2b7;'>|</span>");
            }
            boolean esCorriente = labels[i].equals("IA") || labels[i].equals("IB") || labels[i].equals("IC");
            String colorValor = esCorriente ? "#e34948" : "#0b0b0b";
            html.append("<span>")
                    .append("<span style='font-size: 12px; color: #898781;'>").append(labels[i]).append(": </span>")
                    .append("<span class='dato-valor' style='font-size: 14px; font-weight: 600; color: ").append(colorValor).append(";'>")
                    .append(String.format("%.2f", valores[i]))
                    .append("</span>")
                    .append("</span>");
        }
        html.append("</div>");
        return html.toString();
    }

    private static double toDoubleSeguro(Object valor) {
        if (valor == null) return 0.0;
        try {
            return Double.parseDouble(valor.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** HTML de texto plano "Fecha: ... Hora: ... KWh: ..." de la tarjeta compartida de último click. */
    public static String construirHtmlUltimoClick(String fechaStr, String horaStr, String valorStr) {
        return "<div style='display: flex; gap: 10px; align-items: baseline; font-size: 12px; white-space: nowrap;'>" +
                "<span><span style='color: #898781;'>Fecha: </span><span style='font-weight: 600; color: #0b0b0b;'>" + fechaStr + "</span></span>" +
                "<span><span style='color: #898781;'>Hora: </span><span style='font-weight: 600; color: #0b0b0b;'>" + horaStr + "</span></span>" +
                "<span><span style='color: #898781;'>KWh: </span><span style='font-weight: 600; color: #0b0b0b;'>" + valorStr + "</span></span>" +
                "</div>";
    }
}
