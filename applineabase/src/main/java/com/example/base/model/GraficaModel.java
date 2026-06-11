package com.example.base.model;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

    public GraficaModel() {
    }

    public GraficaModel(int nGraficas) {
        this.nGraficas = nGraficas;
    }

    public String getInitScript(String containerId) {
        String colors = "var colors = [am5.color(0xc83830), am5.color(0x4472c4), am5.color(0x70ad47)];";
        StringBuilder seriesNamesJs = new StringBuilder("var seriesNames = [");
        for (int i = 0; i < seriesNames.length; i++) {
            seriesNamesJs.append("'").append(seriesNames[i]).append("'");
            if (i < seriesNames.length - 1) seriesNamesJs.append(", ");
        }
        seriesNamesJs.append("];");

        return "if (!window.am5Charts) { window.am5Charts = {}; }" +
               "var id = '" + containerId + "';" +
               "if (window.am5Charts[id] && window.am5Charts[id].root) {" +
               "  try { window.am5Charts[id].root.dispose(); } catch(e) {}" +
               "}" +
               "var root = am5.Root.new(id);" +
               "root.setThemes([am5themes_Animated.new(root)]);" +
               "var chart = root.container.children.push(am5xy.XYChart.new(root, { panX: false, panY: false, wheelX: 'none', wheelY: 'zoomY', pinchZoomX: true, pinchZoomY: true, maxTooltipDistance: 0, cursor: am5xy.XYCursor.new(root, { behavior: 'zoomXY' }) }));" +
               "var cursor = chart.get('cursor');" +
               "cursor.lineX.setAll({ visible: true });" +
               "cursor.lineY.setAll({ visible: false });" +
               "var xAxis = chart.xAxes.push(am5xy.DateAxis.new(root, { maxDeviation: 0.2, baseInterval: { timeUnit: 'second', count: 1 }, renderer: am5xy.AxisRendererX.new(root, { minGridDistance: 50 }) }));" +
               "var yAxis = chart.yAxes.push(am5xy.ValueAxis.new(root, { renderer: am5xy.AxisRendererY.new(root, {}), min: " + minY + ", max: " + maxY + ", strictMinMax: true }));" +
               colors +
               seriesNamesJs.toString() +
               "var seriesList = [];" +
               "for(var i=0; i < " + nGraficas + "; i++) {" +
               "  var series = chart.series.push(am5xy.LineSeries.new(root, { name: seriesNames[i], xAxis: xAxis, yAxis: yAxis, valueYField: 'value', valueXField: 'date', strokeWidth: 2, snapToTooltip: true }));" +
               "  series.strokes.template.setAll({ stroke: colors[i % colors.length] });" +
               "  var tooltip = series.set('tooltip', am5.Tooltip.new(root, { pointerOrientation: 'vertical' }));" +
               "  tooltip.label.setAll({ text: '[bold]' + seriesNames[i] + ':[/] {valueY.formatNumber(\\u0022#.##\\u0022)}\\n{valueX.formatDate(\\u0022dd-MM-yyyy HH:mm:ss\\u0022)}' });" +
               "  seriesList.push(series);" +
               "}" +
               "var cursorTooltip = cursor.set('tooltip', am5.Tooltip.new(root, { pointerOrientation: 'vertical' }));" +
               "cursorTooltip.label.setAll({ text: '{valueX.formatDate(\\u0022dd-MM-yyyy HH:mm:ss\\u0022)}' });" +
               "if(seriesList.length > 0) { cursor.setAll({ snapToSeries: seriesList, snapToSeriesBy: 'x' }); }" +
               "window.am5Charts[id] = { root: root, chart: chart, xAxis: xAxis, yAxis: yAxis, seriesList: seriesList, cursor: cursor, tiemposMarcadores: [], posY: 0, lastClickTime: 0, containerId: '" + containerId + "', marcadores: [] };" +
               "console.log('✓ am5Charts[' + id + '] inicializado con array de marcadores');" +
               "chart.plotContainer.events.on('click', function(ev) {" +
               "  var inst = window.am5Charts['" + containerId + "'];" +
               "  if (!inst) return;" +
               "  var nowTime = new Date().getTime();" +
               "  var isDoubleClick = (nowTime - inst.lastClickTime) < 300;" +
               "  inst.lastClickTime = nowTime;" +
               "  console.log('Click detectado, isDouble:', isDoubleClick);" +
               "  if (isDoubleClick) {" +
               "    inst.tiemposMarcadores = [];" +
               "    inst.posY = 0;" +
               "    inst.marcadores.forEach(function(marker) { marker.dispose(); });" +
               "    inst.marcadores = [];" +
               "    console.log('🗑️ Chart reset - todas las líneas eliminadas');" +
               "  } else {" +
               "    try {" +
               "      console.log('📍 Procesando click single...');" +
               "      var tooltipContent = '';" +
               "      if (inst.seriesList && inst.seriesList[0]) {" +
               "        var tooltip = inst.seriesList[0].get('tooltip');" +
               "        if (tooltip && tooltip.label) {" +
               "          tooltipContent = tooltip.label.get('text');" +
               "          console.log('📋 Contenido del tooltip:', tooltipContent);" +
               "        }" +
               "      }" +
               "      var xAxisRenderer = inst.xAxis.get('renderer');" +
               "      var relativeX = xAxisRenderer.toAxisPosition(ev.point.x);" +
               "      var date = inst.xAxis.positionToDate(relativeX);" +
               "      var timestamp = date.getTime();" +
               "      console.log('  - Fecha/Hora obtenida:', new Date(timestamp).toLocaleString('es-ES'));" +
               "      var yBounds = { min: inst.yAxis.get('min'), max: inst.yAxis.get('max') };" +
               "      var yPixelMin = inst.yAxis.valueToPosition(yBounds.min);" +
               "      var yPixelMax = inst.yAxis.valueToPosition(yBounds.max);" +
               "      var circleX = ev.svgPoint ? ev.svgPoint.x : ev.point.x;" +
               "      var circleY = ev.svgPoint ? ev.svgPoint.y : ev.point.y;" +
               "      var fechaCompleta = new Date(timestamp).toLocaleDateString('es-ES') + ' ' + new Date(timestamp).toLocaleTimeString('es-ES', {hour: '2-digit', minute: '2-digit', second: '2-digit'});" +
               "      var xPixelAxis = inst.xAxis.dateToPosition(new Date(timestamp));" +
               "      console.log('  ✅ Dibujando círculo en X:', circleX, ' Y:', circleY, ' Fecha/Hora:', fechaCompleta, ' X calculado del eje:', xPixelAxis);" +
               "      var circle = am5.Circle.new(inst.root, {" +
               "        radius: 6," +
               "        fill: am5.color(0xff0000)," +
               "        stroke: am5.color(0xffffff)," +
               "        strokeWidth: 2" +
               "      });" +
               "      circle.setAll({ x: circleX, y: circleY });" +
               "      inst.chart.plotContainer.children.push(circle);" +
               "      inst.marcadores.push(circle);" +
               "      var tiempoStr = '';" +
               "      var label = 'Click #' + (inst.tiemposMarcadores.length + 1) + ' - ' + fechaCompleta;" +
               "      if (inst.tiemposMarcadores.length > 0) {" +
               "        var tiempoAnterior = inst.tiemposMarcadores[inst.tiemposMarcadores.length - 1];" +
               "        var diff = Math.floor((timestamp - tiempoAnterior) / 1000);" +
               "        var horas = Math.floor(diff / 3600);" +
               "        var minutos = Math.floor((diff % 3600) / 60);" +
               "        var segundos = diff % 60;" +
               "        tiempoStr = String(horas).padStart(2, '0') + ':' + String(minutos).padStart(2, '0') + ':' + String(segundos).padStart(2, '0');" +
               "        label = 'Δ ' + tiempoStr + ' desde anterior';" +
               "      }" +
               "      console.log('✓ Marca #' + (inst.tiemposMarcadores.length + 1) + ':', label);" +
               "      inst.tiemposMarcadores.push(timestamp);" +
               "      $0.$server.registrarClickEnGrafica(timestamp);" +
               "    } catch(e) {" +
               "      console.error('❌ Error en click handler:', e);" +
               "      console.error('Stack:', e.stack);" +
               "    }" +
               "  }" +
               "});" +
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
