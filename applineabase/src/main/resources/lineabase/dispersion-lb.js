/*
 * Dispersión kWh contra kg de la línea base (pestaña «Línea base» de SGEn y de Tiempo Real) en amCharts 5. La arma
 * LineaBaseProductoPanel: D = {r: {m, b, sig} o null, valida, xm, texto, pts: [{x, y, c, t}], ley}, con c = base,
 * fuera (fuera de ± 2 sigma, se mantiene), atip (atípico quitado) o comp (lote de este año que se compara).
 */
if (!window.DispersionLB) {
  window.DispersionLB = (function () {
    const G = window.SgenGraf;
    function render(el, D) {
      G.limpiar(el);
      el.innerHTML = '<div class="aj" style="width:100%;height:420px"></div><div style="font-size:12px;color:#6b6f80">' + D.ley + '</div>';
      const g = G.crear(el, el.firstChild, {x: 'kg procesados del lote', y: 'kWh del lote',
        fx: v => G.f(v, 0), fy: v => G.f(v, 0), xOpc: {min: 0}});
      const r = D.r;
      if (r) {
        const recta = k => [{x: 0, y: r.b + k}, {x: D.xm, y: r.m * D.xm + r.b + k}];
        if (D.valida) {
          G.linea(g, recta(r.sig), {color: 0x9aa0ae, ancho: 1.2, guiones: [6, 5]});
          G.linea(g, recta(-r.sig), {color: 0x9aa0ae, ancho: 1.2, guiones: [6, 5]});
        }
        G.linea(g, recta(0), D.valida ? {color: 0x3BB273, ancho: 2} : {color: 0x9aa0ae, ancho: 2, guiones: [6, 5]});
        if (D.texto) G.nota(g, D.texto);
        if (r.b < 0) G.linea(g, [{x: 0, y: r.b, lbl: 'b = ' + G.f(r.b, 0) + ' kWh', t: 'Intercepto b = ' + G.f(r.b, 0) + ' kWh'}],
          {soloPuntos: true, radio: 4, color: 0xE8505B});
      }
      const tipos = [['base', 0x3B8FF3, null], ['fuera', 0x3B8FF3, 0xE0B50F], ['atip', 0xE8505B, null], ['comp', 0xF29F67, null]];
      tipos.forEach(([c, color, borde]) => {
        const pts = D.pts.filter(p => p.c === c);
        if (pts.length) G.linea(g, pts, {soloPuntos: true, radio: 5.5, color: color, borde: borde || 0xffffff, anchoBorde: borde ? 2.5 : 1});
      });
      G.ajustar(el);
      G.alCambiarTamano(el);
      setTimeout(() => G.ajustar(el), 0);
    }
    return {render};
  })();
}
