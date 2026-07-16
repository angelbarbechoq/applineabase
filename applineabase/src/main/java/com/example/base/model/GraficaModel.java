package com.example.base.model;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class GraficaModel {

    private int nGraficas = 1;
    private Double maxY = 100.0, minY = 0.0;
    private List<Long> tiemposMarcadores = new ArrayList<>();
    private SimpleDateFormat houra = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
    private String[] seriesNames = {"Valor"};
    private int posY = 0;
    private static final int DIVISIONES_Y = 10;
    private String unidad = "";

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
        String colors = "var colors = [am5.color(0xc83830), am5.color(0x4472c4), am5.color(0x70ad47)];";
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
                        "var chart = root.container.children.push(am5xy.XYChart.new(root, { panX: false, panY: false, wheelX: 'none', wheelY: 'zoomY', pinchZoomX: true, pinchZoomY: true, maxTooltipDistance: 0 }));" +

                        // PASO 3: Scrollbar
                        "var scrollbarX = am5xy.XYChartScrollbar.new(root, { orientation: 'horizontal', height: 15});" +
                        "chart.set('scrollbarX', scrollbarX);" +
                        "chart.bottomAxesContainer.children.push(scrollbarX);" +

                        // PASO 4: Crear EJES PRIMERO
                        "var xAxis = chart.xAxes.push(am5xy.DateAxis.new(root, { maxDeviation: 0.2, baseInterval: { timeUnit: 'second', count: 1 }, renderer: am5xy.AxisRendererX.new(root, { minGridDistance: 50 }) }));" +
                        "xAxis.set('tooltip', am5.Tooltip.new(root, {}));" +
                        "console.log('✓ Eje X creado con tooltip');" +

                        "var yAxis = chart.yAxes.push(am5xy.ValueAxis.new(root, { renderer: am5xy.AxisRendererY.new(root, {}) }));" +
                        "yAxis.set('tooltip', am5.Tooltip.new(root, {}));" +
                        "console.log('✓ Eje Y creado con tooltip. Zoom inicial: " + minY + " - " + maxY + "');" +
                        // PASO 5: Crear CURSOR con ejes (pero sin snapToSeries aún)
                        "var cursor = chart.set('cursor', am5xy.XYCursor.new(root, { yAxis: yAxis, xAxis: xAxis, maxTooltipDistance: 0, behavior: 'zoomXY' }));" +
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
                        "  series.strokes.template.setAll({ stroke: colors[i % colors.length] });" +
                        "  var tooltip = series.set('tooltip', am5.Tooltip.new(root, { pointerOrientation: 'vertical' }));" +
                        //"  tooltip.label.setAll({ text: '[bold]' + seriesNames[i] + ':[/] {valueY.formatNumber(\\u0022#.##\\u0022)}\\n{valueX.formatDate(\\u0022dd-MM-yyyy HH:mm:ss\\u0022)}' });" +
                        //"  tooltip.set(\"getFillFromSprite\", false); tooltip.get(\"background\").setAll({ fillOpacity: 0, strokeOpacity: 0 }); tooltip.label.setAll({ text: '[bold]' + seriesNames[i] + ':[/] {valueY.formatNumber(\\u0022#.##\\u0022)}' });" +
                        //"  tooltip.setAll({ autoTextColor: false, getFillFromSprite: false }); tooltip.get(\"background\").setAll({ fillOpacity: 0, strokeOpacity: 0 }); tooltip.label.setAll({ fill: am5.color(0x999999), text: '[bold]' + seriesNames[i] + ':[/] {valueY.formatNumber(\\u0022#.##\\u0022)}' });" +
                        "  tooltip.setAll({ autoTextColor: false, getFillFromSprite: false }); tooltip.get(\"background\").setAll({ fillOpacity: 0, strokeOpacity: 0 }); tooltip.label.setAll({ fill: am5.color(0x999999), text: '{valueY.formatNumber(\\u0022#.##\\u0022)} ' + unidad });" +
                        "  seriesList.push(series);" +
                        "  console.log('    ✅ Serie ' + i + ' agregada. Total: ' + seriesList.length);" +
                        "}" +
                        "console.log('✅ Loop finalizado. Total de series: ' + seriesList.length);" +

                        // PASO 8: ASIGNAR snapToSeries al cursor DESPUÉS de tener series
                        "console.log('🔴 ANTES, snapToSeries:', cursor.get('snapToSeries'));" +
                        "cursor.setAll({ snapToSeries: seriesList, snapToSeriesBy: 'x' });" +
                        "console.log('🟢 DESPUÉS, snapToSeries.length:', cursor.get('snapToSeries').length);" +

                        // PASO 8.1: Zoom inicial al rango calculado (piso/percentil), sin fijar
                        // min/max del eje — así el eje sigue auto-ajustable y el zoom manual
                        // con el mouse (zoomXY del cursor) funciona con normalidad después.
                        "if (seriesList.length > 0) {" +
                        "  seriesList[0].events.once('datavalidated', function() {" +
                        "    yAxis.zoomToValues(" + minY + ", " + maxY + ");" +
                        "  });" +
                        "}" +

                        // PASO 9: AGREGAR tooltip separado del cursor (para mostrar fecha/hora al pasar el ratón)
//                        "var cursorTooltip = cursor.set('tooltip', am5.Tooltip.new(root, { pointerOrientation: 'vertical' }));" +
//                        "cursorTooltip.label.setAll({ text: '{valueX.formatDate(\\u0022dd-MM-yyyy HH:mm:ss\\u0022)}' });" +
//                        "console.log('✓ Cursor tooltip configurado');" +

                        // PASO 10: Almacenar referencias globales
                        "window.am5Charts[id] = { root: root, chart: chart, xAxis: xAxis, yAxis: yAxis, seriesList: seriesList, cursor: cursor, tiemposMarcadores: [], posY: 0, lastClickTime: 0, containerId: '" + containerId + "', marcadores: [] };" +
                        "console.log('✓ am5Charts inicializado');" +

                        // PASO 11: Event listener para clicks
                        "chart.plotContainer.events.on('click', function(ev) {" +
                        "  var inst = window.am5Charts['" + containerId + "'];" +
                        "  if (!inst) return;" +
                        "  var nowTime = new Date().getTime();" +
                        "  var isDoubleClick = (nowTime - inst.lastClickTime) < 300;" +
                        "  inst.lastClickTime = nowTime;" +
                        "  if (isDoubleClick) {" +
                        "    inst.tiemposMarcadores = [];" +
                        "    inst.posY = 0;" +
                        "    inst.marcadores.forEach(function(marker) { marker.dispose(); });" +
                        "    inst.marcadores = [];" +
                        "    inst.xAxis.zoom(0, 1);" +
                        "    inst.yAxis.zoomToValues(" + minY + ", " + maxY + ");" +
                        "    if ($0.$server && $0.$server.limpiarTarjetas) { $0.$server.limpiarTarjetas(); }"+
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

    public void registrarClick(long timestamp) {
        this.tiemposMarcadores.add(timestamp);
        System.out.println("Click en: " + houra.format(new Date(timestamp)));
        if (tiemposMarcadores.size() > 1) {
            long tiempoAnterior = tiemposMarcadores.get(tiemposMarcadores.size() - 2);
            long diferencia = timestamp - tiempoAnterior;
            long horas = diferencia / (1000 * 60 * 60);
            long minutos = (diferencia % (1000 * 60 * 60)) / (1000 * 60);
            long segundos = (diferencia % (1000 * 60)) / 1000;
            System.out.println("Tiempo transcurrido: " +
                String.format("%02d:%02d:%02d", horas, minutos, segundos));
        }
    }

    public void resetearMarcadores() {
        this.tiemposMarcadores.clear();
        this.posY = 0;
        System.out.println("Marcadores reset");
    }

    public List<Long> obtenerMarcadores() {
        return new ArrayList<>(tiemposMarcadores);
    }

    public String obtenerTiempoTranscurrido(int indice1, int indice2) {
        if (indice1 < 0 || indice2 < 0 || indice1 >= tiemposMarcadores.size() || indice2 >= tiemposMarcadores.size()) {
            return "00:00:00";
        }
        long diferencia = Math.abs(tiemposMarcadores.get(indice2) - tiemposMarcadores.get(indice1));
        long horas = diferencia / (1000 * 60 * 60);
        long minutos = (diferencia % (1000 * 60 * 60)) / (1000 * 60);
        long segundos = (diferencia % (1000 * 60)) / 1000;
        return String.format("%02d:%02d:%02d", horas, minutos, segundos);
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
        List<Float> ordenado = new ArrayList<>(valores);
        Collections.sort(ordenado);
        int idx = (int) Math.ceil(p * ordenado.size()) - 1;
        idx = Math.max(0, Math.min(idx, ordenado.size() - 1));
        return ordenado.get(idx);
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
}
