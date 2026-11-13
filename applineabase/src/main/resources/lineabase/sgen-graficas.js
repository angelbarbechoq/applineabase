/*
 * Ayudas comunes de amCharts 5 para las gráficas de SGEn (dispersión de la línea base, índice de consumo, CUSUM y
 * Kneedle). Mismos comandos que Tiempo Real: arrastrar = zoom de una zona (X e Y), rueda = zoom vertical, pellizco,
 * barra de desplazamiento abajo y el botón de amCharts para volver. amCharts se carga en index.html (am5, am5xy y el tema).
 * Los números de los ejes y de los tooltips se escriben con el formato local (1.234,5).
 */
if (!window.SgenGraf) {
  window.SgenGraf = (function () {
    const f = (x, d = 0) => x == null || !isFinite(x) ? '—' : x.toLocaleString('es-EC', {minimumFractionDigits: d, maximumFractionDigits: d});
    // amCharts interpreta [ ] como formato: se escapan en los textos.
    const txt = s => String(s == null ? '' : s).replace(/\[/g, '[[').replace(/\]/g, ']]');
    const GRIS = 0x6b6f80;

    /** Libera los gráficos anteriores dibujados dentro de el. */
    function limpiar(el) {
      (el.__roots || []).forEach(r => { try { r.dispose(); } catch (e) { /* ya liberado */ } });
      el.__roots = [];
    }

    /**
     * Crea un gráfico XY de ejes de valor en div. o = {x, y: títulos de los ejes; fx, fy: formato de las etiquetas;
     * xOpc, yOpc: opciones de los ejes}. Devuelve {root, chart, xAxis, yAxis, tooltip}.
     */
    function crear(el, div, o) {
      const root = am5.Root.new(div);
      (el.__roots = el.__roots || []).push(root);
      root.setThemes([am5themes_Animated.new(root)]);
      const chart = root.container.children.push(am5xy.XYChart.new(root, {
        panX: false, panY: false, wheelX: 'none', wheelY: 'zoomY', pinchZoomX: true, pinchZoomY: true, paddingLeft: 0, paddingRight: 24
      }));
      const xAxis = chart.xAxes.push(am5xy.ValueAxis.new(root, Object.assign({
        renderer: am5xy.AxisRendererX.new(root, {minGridDistance: 70})
      }, o.xOpc || {})));
      const yAxis = chart.yAxes.push(am5xy.ValueAxis.new(root, Object.assign({
        renderer: am5xy.AxisRendererY.new(root, {})
      }, o.yOpc || {})));
      [[xAxis, o.fx], [yAxis, o.fy]].forEach(([eje, fmt]) => {
        const r = eje.get('renderer');
        r.labels.template.setAll({fontSize: '11px', fill: am5.color(GRIS)});
        r.grid.template.setAll({stroke: am5.color(0xe4e6ec), strokeOpacity: 1});
        if (fmt) r.labels.template.adapters.add('text', (t, target) => {
          // Las etiquetas de las líneas y zonas (rangos) llevan su propio texto.
          if (target.get('userData') === 'fijo') return t;
          const v = target.dataItem && target.dataItem.get('value');
          return v == null ? t : fmt(v);
        });
      });
      xAxis.children.push(am5.Label.new(root, {text: txt(o.x), x: am5.p50, centerX: am5.p50, fontSize: '12px', fill: am5.color(GRIS)}));
      yAxis.children.unshift(am5.Label.new(root, {text: txt(o.y), rotation: -90, y: am5.p50, centerX: am5.p50, fontSize: '12px', fill: am5.color(GRIS)}));
      const cursor = chart.set('cursor', am5xy.XYCursor.new(root, {xAxis: xAxis, yAxis: yAxis, behavior: 'zoomXY'}));
      cursor.lineX.set('visible', false);
      cursor.lineY.set('visible', false);
      // Barra de desplazamiento abajo, como en Tiempo Real.
      const barra = am5.Scrollbar.new(root, {orientation: 'horizontal', height: 12, marginTop: 6});
      chart.set('scrollbarX', barra);
      chart.bottomAxesContainer.children.push(barra);
      const tooltip = am5.Tooltip.new(root, {});
      tooltip.label.setAll({fontSize: '12px'});
      return {root, chart, xAxis, yAxis, tooltip};
    }

    /**
     * Línea por puntos [{x, y}] (o solo puntos con o.soloPuntos). o = {color, ancho, guiones, opacidad, radio, borde,
     * anchoBorde, relleno (false = círculo vacío)}. Cada punto puede traer t (tooltip), lbl (texto al lado) y lblColor.
     */
    function linea(g, datos, o) {
      const root = g.root, color = am5.color(o.color);
      const s = g.chart.series.push(am5xy.LineSeries.new(root, {
        xAxis: g.xAxis, yAxis: g.yAxis, valueXField: 'x', valueYField: 'y', stroke: color, fill: color
      }));
      s.strokes.template.setAll({
        strokeWidth: o.ancho || 1.5, strokeOpacity: o.soloPuntos ? 0 : (o.opacidad || 1), strokeDasharray: o.guiones || []
      });
      if (o.radio) {
        s.bullets.push(() => am5.Bullet.new(root, {
          sprite: am5.Circle.new(root, {
            radius: o.radio, fill: color, fillOpacity: o.relleno === false ? 0 : 1,
            stroke: am5.color(o.borde || 0xffffff), strokeWidth: o.anchoBorde || 1,
            tooltipText: '{t}', tooltip: g.tooltip, cursorOverStyle: 'pointer'
          })
        }));
      }
      s.bullets.push((r, serie, item) => {
        const d = item.dataContext;
        if (!d || !d.lbl) return undefined;
        return am5.Bullet.new(root, {
          sprite: am5.Label.new(root, {
            text: txt(d.lbl), fontSize: '12px', fill: am5.color(d.lblColor || 0xa3222c),
            centerY: am5.p100, dx: 8, dy: -4, populateText: false
          })
        });
      });
      s.data.setAll(datos.map(d => Object.assign({}, d, {t: d.t == null ? '' : txt(d.t)})));
      return s;
    }

    /** Línea vertical en x (rango del eje X) con su texto abajo, dentro del gráfico. */
    function vertical(g, x, color, texto, ancho) {
      const rg = g.xAxis.createAxisRange(g.xAxis.makeDataItem({value: x}));
      rg.get('grid').setAll({stroke: am5.color(color), strokeWidth: ancho || 1.5, strokeOpacity: 1, strokeDasharray: [], visible: true});
      if (texto) rg.get('label').setAll({userData: 'fijo', text: txt(texto), fill: am5.color(color), inside: true, centerX: am5.p0, centerY: am5.p100,
        dx: 4, dy: -4, fontSize: '11px', visible: true, background: am5.Rectangle.new(g.root, {fill: am5.color(0xffffff), fillOpacity: 0.8})});
    }

    /** Línea horizontal en y (rango del eje Y) con su texto a la izquierda, dentro del gráfico. */
    function horizontal(g, y, color, texto, guiones) {
      const rg = g.yAxis.createAxisRange(g.yAxis.makeDataItem({value: y}));
      rg.get('grid').setAll({stroke: am5.color(color), strokeWidth: 1.2, strokeOpacity: 1, strokeDasharray: guiones || [], visible: true});
      if (texto) rg.get('label').setAll({userData: 'fijo', text: txt(texto), fill: am5.color(0x4a4f5c), inside: true, centerX: am5.p0, centerY: am5.p100, dx: 6,
        fontSize: '11px', visible: true, background: am5.Rectangle.new(g.root, {fill: am5.color(0xffffff), fillOpacity: 0.8})});
    }

    /** Zona sombreada del eje X entre x0 y x1. */
    function zona(g, x0, x1, color, opacidad) {
      const rg = g.xAxis.createAxisRange(g.xAxis.makeDataItem({value: x0, endValue: x1}));
      rg.get('axisFill').setAll({fill: am5.color(color), fillOpacity: opacidad, visible: true});
      rg.get('grid').set('visible', false);
      rg.get('label').set('visible', false); // si no, repite el número del eje (el «0» doble)
    }

    /** Texto fijo arriba a la izquierda del área del gráfico. */
    function nota(g, texto, color) {
      g.chart.plotContainer.children.push(am5.Label.new(g.root, {
        text: txt(texto), x: 8, y: 6, fontSize: '12px', fill: am5.color(color || 0x1E1E2C),
        background: am5.Rectangle.new(g.root, {fill: am5.color(0xffffff), fillOpacity: 0.85})
      }));
    }

    /*
     * Auto ajuste, mismo criterio que el unifilar de Transformadores: cada gráfica (div.aj) toma el alto que queda desde su
     * borde de arriba hasta el de abajo del área visible (la ventana o el panel con barra de desplazamiento que la
     * contiene), sin la leyenda que va debajo; mínimo 380 px. amCharts se redibuja solo al cambiar el tamaño del div.
     */
    function ajustar(el) {
      el.querySelectorAll('div.aj').forEach(div => {
        if (!div.isConnected) return;
        let desplazado = 0, abajo = window.innerHeight;
        for (let n = div; n; n = n.assignedSlot || n.parentNode || n.host) {
          if (!(n instanceof Element)) continue;
          const zn = n.currentCSSZoom || 1;
          desplazado += (n.scrollTop || 0) * zn;
          const st = getComputedStyle(n);
          if (n !== div && n !== document.documentElement && n !== document.body && /auto|scroll/.test(st.overflowY)) {
            abajo = Math.min(abajo, n.getBoundingClientRect().top + (n.clientTop + n.clientHeight - parseFloat(st.paddingBottom || 0)) * zn);
          }
        }
        const r = div.getBoundingClientRect();
        const ley = div.nextElementSibling ? div.nextElementSibling.getBoundingClientRect().height : 0;
        const zoom = div.currentCSSZoom || 1;
        div.style.height = Math.max(380, (abajo - (r.top + desplazado) - ley - 30) / zoom) + 'px';
      });
      if (el.__despuesDeAjustar) el.__despuesDeAjustar();
    }

    function alCambiarTamano(el) {
      if (el.__sgenResize) return;
      el.__sgenResize = true;
      let espera;
      window.addEventListener('resize', () => { clearTimeout(espera); espera = setTimeout(() => ajustar(el), 150); });
    }

    return {f, txt, limpiar, crear, linea, vertical, horizontal, zona, nota, ajustar, alCambiarTamano};
  })();
}
