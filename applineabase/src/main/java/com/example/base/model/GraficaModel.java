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
                "var yAxis = chart.yAxes.push(am5xy.ValueAxis.new(root, { renderer: am5xy.AxisRendererY.new(root, {}), min: " + minY + ", max: " + maxY + ", strictMinMax: false }));" +
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
                "      var timestamp = null;" +
                "      " +
                "      if (ev.point) {" +
                "        var localPoint = chart.plotContainer.toLocal(ev.point);" +
                "        var axisPosition = inst.xAxis.toAxisPosition(localPoint.x / chart.plotContainer.width());" +
                "        timestamp = inst.xAxis.positionToValue(axisPosition);" +
                "      }" +
                "      " +
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
                "        console.log('  - Timestamp calculado:', timestamp);" +
                "        console.log('  - Fecha/Hora obtenida del cursor21:', new Date(timestamp).toLocaleString('es-ES'));" +
                "        var fechaCompleta = new Date(timestamp).toLocaleDateString('es-ES') + ' ' + new Date(timestamp).toLocaleTimeString('es-ES', {hour: '2-digit', minute: '2-digit', second: '2-digit'});" +
                "        console.log('  ✅ Dibujando línea en X sincronizada con el cursor, Fecha/Hora:', fechaCompleta);" +
                "        " +
                "        var rangeDataItem = inst.xAxis.makeDataItem({ value: timestamp });" +
                "        var range = inst.xAxis.createAxisRange(rangeDataItem);" +
                "        " +
                "        setTimeout(function() {" +
                "          if (range.get('grid')) {" +
                "            range.get('grid').setAll({" +
                "              stroke: am5.color(0xd32f2f)," +
                "              strokeWidth: 1," +
                "              strokeDasharray: [3, 3]," +
                "              visible: true" +
                "            });" +
                "          }" +
                "        }, 0);" +
                "        " +
                "        var offsetUp = inst.tiemposMarcadores.length * -21;" +
                "        " +
                "        inst.marcadores.push(rangeDataItem);" +
                "        var tiempoStr = '';" +
                "        var labelText = 'Click #' + (inst.tiemposMarcadores.length + 1) + ' - ' + fechaCompleta;" +
                "        var textoFinalLabel = \"[bold #d32f2f]\" + fechaCompleta + \"[/]\";" +
                "        " +
                "        if (inst.tiemposMarcadores.length > 0) {" +
                "          var tiempoAnterior = inst.tiemposMarcadores[inst.tiemposMarcadores.length - 1];" +
                "          var diff = Math.floor((timestamp - tiempoAnterior) / 1000);" +
                "          var horas = Math.floor(diff / 3600);" +
                "          var minutos = Math.floor((diff % 3600) / 60);" +
                "          var segundos = diff % 60;" +
                "          tiempoStr = String(horas).padStart(2, '0') + ':' + String(minutos).padStart(2, '0') + ':' + String(segundos).padStart(2, '0');" +
                "          labelText = 'Δ ' + tiempoStr + ' desde anterior';" +
                "          textoFinalLabel += \"\\n[bold #4a6572]Δ \" + tiempoStr + \"[/]\";" +
                "        }" +
                "        " +
                "        range.get('label').setAll({" +
                "          text: textoFinalLabel," +
                "          fontWeight: 'bold'," +
                "          fontSize: '10px'," +
                "          visible: true," +
                "          inside: true," +
                "          centerX: am5.p0," +
                "          centerY: am5.p100," +
                "          dy: offsetUp - 5," +
                "          dx: 5" +
                "        });" +
                "        " +
                "        console.log('✓ Marca #' + (inst.tiemposMarcadores.length + 1) + ':', labelText);" +
                "        inst.tiemposMarcadores.push(timestamp);" +
                "        $0.$server.registrarClickEnGrafica(timestamp);" +
                "      } else {" +
                "        console.log('⚠️ No se pudo determinar una fecha válida en esta posición.');" +
                "      }" +
                "    } catch(e) {" +
                "      console.error('❌ Error en click handler:', e);" +
                "      console.error('Stack:', e.stack);" +
                "    }" +
                "  }" +
                "});" +
                "chart.appear(1000, 100);";
    }
    public String getInitScript7(String containerId) {
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
                "      var timestamp = null;" +
                "      " +
                "      if (ev.point) {" +
                "        var localPoint = chart.plotContainer.toLocal(ev.point);" +
                "        var axisPosition = inst.xAxis.toAxisPosition(localPoint.x / chart.plotContainer.width());" +
                "        timestamp = inst.xAxis.positionToValue(axisPosition);" +
                "      }" +
                "      " +
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
                "        console.log('  - Timestamp calculado:', timestamp);" +
                "        console.log('  - Fecha/Hora obtenida del cursor21:', new Date(timestamp).toLocaleString('es-ES'));" +
                "        var fechaCompleta = new Date(timestamp).toLocaleDateString('es-ES') + ' ' + new Date(timestamp).toLocaleTimeString('es-ES', {hour: '2-digit', minute: '2-digit', second: '2-digit'});" +
                "        console.log('  ✅ Dibujando línea en X sincronizada con el cursor, Fecha/Hora:', fechaCompleta);" +
                "        " +
                "        var rangeDataItem = inst.xAxis.makeDataItem({ value: timestamp });" +
                "        var range = inst.xAxis.createAxisRange(rangeDataItem);" +
                "        " +
                "        range.get('grid').setAll({" +
                "          stroke: am5.color(0xd32f2f)," +
                "          strokeWidth: 1," +
                "          strokeDasharray: [3, 3]," +
                "          visible: true" +
                "        });" +
                "        " +
                "        var offsetUp = inst.tiemposMarcadores.length * -21;" +
                "        " +
                "        inst.marcadores.push(rangeDataItem);" +
                "        var tiempoStr = '';" +
                "        var labelText = 'Click #' + (inst.tiemposMarcadores.length + 1) + ' - ' + fechaCompleta;" +
                "        var textoFinalLabel = \"[bold #d32f2f]\" + fechaCompleta + \"[/]\";" +
                "        " +
                "        if (inst.tiemposMarcadores.length > 0) {" +
                "          var tiempoAnterior = inst.tiemposMarcadores[inst.tiemposMarcadores.length - 1];" +
                "          var diff = Math.floor((timestamp - tiempoAnterior) / 1000);" +
                "          var horas = Math.floor(diff / 3600);" +
                "          var minutos = Math.floor((diff % 3600) / 60);" +
                "          var segundos = diff % 60;" +
                "          tiempoStr = String(horas).padStart(2, '0') + ':' + String(minutos).padStart(2, '0') + ':' + String(segundos).padStart(2, '0');" +
                "          labelText = 'Δ ' + tiempoStr + ' desde anterior';" +
                "          textoFinalLabel += \"\\n[bold #4a6572]Δ \" + tiempoStr + \"[/]\";" +
                "        }" +
                "        " +
                "        range.get('label').setAll({" +
                "          text: textoFinalLabel," +
                "          fontWeight: 'bold'," +
                "          fontSize: '10px'," +
                "          visible: true," +
                "          inside: true," +
                "          centerX: am5.p0," +
                "          centerY: am5.p100," +
                "          dy: offsetUp - 5," +
                "          dx: 5" +
                "        });" +
                "        " +
                "        console.log('✓ Marca #' + (inst.tiemposMarcadores.length + 1) + ':', labelText);" +
                "        inst.tiemposMarcadores.push(timestamp);" +
                "        $0.$server.registrarClickEnGrafica(timestamp);" +
                "      } else {" +
                "        console.log('⚠️ No se pudo determinar una fecha válida en esta posición.');" +
                "      }" +
                "    } catch(e) {" +
                "      console.error('❌ Error en click handler:', e);" +
                "      console.error('Stack:', e.stack);" +
                "    }" +
                "  }" +
                "});" +
                "chart.appear(1000, 100);";
    }
    public String getInitScript3(String containerId) {
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
                "      var timestamp = null;" +
                "      " +
                "      if (ev.point) {" +
                "        var localPoint = chart.plotContainer.toLocal(ev.point);" +
                "        var axisPosition = inst.xAxis.toAxisPosition(localPoint.x / chart.plotContainer.width());" +
                "        timestamp = inst.xAxis.positionToValue(axisPosition);" +
                "      }" +
                "      " +
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
                "        console.log('  - Timestamp calculado:', timestamp);" +
                "        console.log('  - Fecha/Hora obtenida del cursor21:', new Date(timestamp).toLocaleString('es-ES'));" +
                "        var fechaCompleta = new Date(timestamp).toLocaleDateString('es-ES') + ' ' + new Date(timestamp).toLocaleTimeString('es-ES', {hour: '2-digit', minute: '2-digit', second: '2-digit'});" +
                "        console.log('  ✅ Dibujando línea en X sincronizada con el cursor, Fecha/Hora:', fechaCompleta);" +
                "        " +
                "        var rangeDataItem = inst.xAxis.makeDataItem({ value: timestamp });" +
                "        var range = inst.xAxis.createAxisRange(rangeDataItem);" +
                "        " +
                "        range.get('grid').setAll({" +
                "          stroke: am5.color(0x4a6572)," +
                "          strokeWidth: 1," +
                "          strokeDasharray: [3, 3]," +
                "          visible: true" +
                "        });" +
                "        " +
                "        if (range.grid) {" +
                "          range.grid.set('stroke', am5.color(0x4a6572));" +
                "          range.grid.setAll({" +
                "            strokeWidth: 1," +
                "            strokeDasharray: [3, 3]" +
                "          });" +
                "        }" +
                "        " +
                "        var offsetUp = inst.tiemposMarcadores.length * -21;" +
                "        " +
                "        inst.marcadores.push(rangeDataItem);" +
                "        var tiempoStr = '';" +
                "        var labelText = 'Click #' + (inst.tiemposMarcadores.length + 1) + ' - ' + fechaCompleta;" +
                "        var textoFinalLabel = \"[bold #d32f2f]\" + fechaCompleta + \"[/]\";" +
                "        " +
                "        if (inst.tiemposMarcadores.length > 0) {" +
                "          var tiempoAnterior = inst.tiemposMarcadores[inst.tiemposMarcadores.length - 1];" +
                "          var diff = Math.floor((timestamp - tiempoAnterior) / 1000);" +
                "          var horas = Math.floor(diff / 3600);" +
                "          var minutos = Math.floor((diff % 3600) / 60);" +
                "          var segundos = diff % 60;" +
                "          tiempoStr = String(horas).padStart(2, '0') + ':' + String(minutos).padStart(2, '0') + ':' + String(segundos).padStart(2, '0');" +
                "          labelText = 'Δ ' + tiempoStr + ' desde anterior';" +
                "          textoFinalLabel += \"\\n[bold #4a6572]Δ \" + tiempoStr + \"[/]\";" +
                "        }" +
                "        " +
                "        range.get('label').setAll({" +
                "          text: textoFinalLabel," +
                "          fontWeight: 'bold'," +
                "          fontSize: '10px'," +
                "          visible: true," +
                "          inside: true," +
                "          centerX: am5.p0," +
                "          centerY: am5.p100," +
                "          dy: offsetUp - 5," +
                "          dx: 5" +
                "        });" +
                "        " +
                "        console.log('✓ Marca #' + (inst.tiemposMarcadores.length + 1) + ':', labelText);" +
                "        inst.tiemposMarcadores.push(timestamp);" +
                "        $0.$server.registrarClickEnGrafica(timestamp);" +
                "      } else {" +
                "        console.log('⚠️ No se pudo determinar una fecha válida en esta posición.');" +
                "      }" +
                "    } catch(e) {" +
                "      console.error('❌ Error en click handler:', e);" +
                "      console.error('Stack:', e.stack);" +
                "    }" +
                "  }" +
                "});" +
                "chart.appear(1000, 100);";
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
                "      var timestamp = null;" +
                "      " +
                "      if (ev.point) {" +
                "        var localPoint = chart.plotContainer.toLocal(ev.point);" +
                "        var axisPosition = inst.xAxis.toAxisPosition(localPoint.x / chart.plotContainer.width());" +
                "        timestamp = inst.xAxis.positionToValue(axisPosition);" +
                "      }" +
                "      " +
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
                "        console.log('  - Timestamp calculado:', timestamp);" +
                "        console.log('  - Fecha/Hora obtenida del cursor21:', new Date(timestamp).toLocaleString('es-ES'));" +
                "        var fechaCompleta = new Date(timestamp).toLocaleDateString('es-ES') + ' ' + new Date(timestamp).toLocaleTimeString('es-ES', {hour: '2-digit', minute: '2-digit', second: '2-digit'});" +
                "        console.log('  ✅ Dibujando línea en X sincronizada con el cursor, Fecha/Hora:', fechaCompleta);" +
                "        " +
                "        var rangeDataItem = inst.xAxis.makeDataItem({ value: timestamp });" +
                "        var range = inst.xAxis.createAxisRange(rangeDataItem);" +
                "        " +
                "        range.get('grid').setAll({" +
                "          stroke: am5.color(0x4a6572)," +
                "          strokeWidth: 1," +
                "          strokeDasharray: [3, 3]," +
                "          visible: true" +
                "        });" +
                "        " +
                "        if (range.grid) {" +
                "          range.grid.set('stroke', am5.color(0x4a6572));" +
                "          range.grid.setAll({" +
                "            strokeWidth: 1," +
                "            strokeDasharray: [3, 3]" +
                "          });" +
                "        }" +
                "        " +
                "        var offsetUp = inst.tiemposMarcadores.length * -15;" +
                "        " +
                "        range.get('label').setAll({" +
                "          text: fechaCompleta," +
                "          fill: am5.color(0xd32f2f)," +
                "          fontWeight: 'bold'," +
                "          fontSize: '10px'," +
                "          visible: true," +
                "          inside: false," +
                "          centerX: am5.p0," +
                "          centerY: am5.p100," +
                "          dy: offsetUp" +
                "        });" +
                "        " +
                "        inst.marcadores.push(rangeDataItem);" +
                "        var tiempoStr = '';" +
                "        var labelText = 'Click #' + (inst.tiemposMarcadores.length + 1) + ' - ' + fechaCompleta;" +
                "        if (inst.tiemposMarcadores.length > 0) {" +
                "          var tiempoAnterior = inst.tiemposMarcadores[inst.tiemposMarcadores.length - 1];" +
                "          var diff = Math.floor((timestamp - tiempoAnterior) / 1000);" +
                "          var horas = Math.floor(diff / 3600);" +
                "          var minutos = Math.floor((diff % 3600) / 60);" +
                "          var segundos = diff % 60;" +
                "          tiempoStr = String(horas).padStart(2, '0') + ':' + String(minutos).padStart(2, '0') + ':' + String(segundos).padStart(2, '0');" +
                "          labelText = 'Δ ' + tiempoStr + ' desde anterior';" +
                "        }" +
                "        console.log('✓ Marca #' + (inst.tiemposMarcadores.length + 1) + ':', labelText);" +
                "        inst.tiemposMarcadores.push(timestamp);" +
                "        $0.$server.registrarClickEnGrafica(timestamp);" +
                "      } else {" +
                "        console.log('⚠️ No se pudo determinar una fecha válida en esta posición.');" +
                "      }" +
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
