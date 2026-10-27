// Diagrama unifilar de la planta 1 (menu Transformadores). Lo dibuja el navegador con los datos que manda
// TransformadoresView cada 15 s (UnifilarTransformadores.render(elemento, datos)). Los kW van sin palabras: el sentido lo
// da la flecha y el origen el color (azul de la red, verde agua hacia la red, el color de cada generador, gris maquinas).
// Las clases llevan el prefijo ut- para no chocar con las de la app.
if (!window.UnifilarTransformadores) {
  window.UnifilarTransformadores = (function () {
    const CSS = `
.ut { display: grid; gap: 14px; color: #1E1E2C; font: 14px/1.4 "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
.ut * { box-sizing: border-box; }
.ut-tarjetas { display: flex; flex-wrap: wrap; gap: 12px; }
.ut-tarjeta { flex: 1 1 180px; background: #fff; border: 1px solid #E4E6EC; border-left-width: 5px; border-radius: 12px; padding: 10px 14px; }
.ut-tarjeta .t { font-size: 12px; color: #6C7383; }
.ut-tarjeta .v { font-size: 22px; font-weight: 700; margin: 2px 0; font-variant-numeric: tabular-nums; }
.ut-tarjeta .d { font-size: 12px; color: #6C7383; min-height: 17px; }
.ut-caja { background: #fff; border: 1px solid #E4E6EC; border-radius: 12px; padding: 12px 16px; min-width: 0; }
.ut-caja h2 { font-size: 15px; margin: 0 0 6px; }
.ut-esquema { overflow-x: auto; }
.ut-esquema svg { display: block; width: 100%; min-width: 900px; max-width: 1400px; margin: 0 auto; height: auto; }
.ut svg text { font-family: "Segoe UI", Roboto, Helvetica, Arial, sans-serif; fill: #1E1E2C; }
.ut svg .chico { font-size: 12px; fill: #6C7383; }
.ut svg .valor { font-size: 15px; font-weight: 600; font-variant-numeric: tabular-nums; }
.ut-cable { stroke: #A9AFBB; stroke-width: 3; fill: none; }
.ut-flujo { stroke-width: 4; fill: none; stroke-dasharray: 8 10; animation: ut-corre 1.1s linear infinite; }
@keyframes ut-corre { to { stroke-dashoffset: -18; } }
@media (prefers-reduced-motion: reduce) { .ut-flujo { animation: none; } }
.ut-leyenda { display: flex; flex-wrap: wrap; justify-content: center; gap: 18px; font-size: 12px; color: #6C7383; margin-top: 4px; }
.ut-leyenda span::before { content: ""; display: inline-block; width: 0; height: 0; margin-right: 6px; vertical-align: middle;
  border-left: 6px solid transparent; border-right: 6px solid transparent; border-top: 10px solid var(--c); }
`;
    const RED = "#3B8FF3", EXPORTA = "#34B1AA", MAQUINAS = "#6C7383", GRIS = "#C3C7D0", VERDE = "#3BB273", ROJO = "#E8505B";
    const SEMAFORO = { verde: ["#3BB273", "#E7F6EE"], amarillo: ["#B8920A", "#FDF6DC"], rojo: ["#E8505B", "#FDECEE"] };
    const W = 1100, X_RED = 550, Y_NODO = 200;

    const n0 = v => Math.round(v).toLocaleString("en-US");
    const kw = v => v == null ? "sin dato" : n0(Math.abs(v)) + " kW";
    const pct = v => v == null ? "" : (Math.round(v * 10) / 10).toFixed(1) + " %";
    const esc = s => String(s == null ? "" : s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
    const texto = (x, y, t, extra) => `<text x="${x}" y="${y}" ${extra || ""}>${esc(t)}</text>`;

    // Tramo de energia de (x1,y1) a (x2,y2) en el sentido del flujo: cable animado del color del origen y una flecha.
    function tramo(x1, y1, x2, y2, color, activo, flecha) {
      if (!activo) return "";
      if (typeof activo === "number" && Math.abs(activo) < 1) return "";
      const [fx, fy] = flecha || [(x1 + x2) / 2, (y1 + y2) / 2];
      const giro = Math.atan2(y2 - y1, x2 - x1) * 180 / Math.PI - 90;
      return `<path class="ut-flujo" stroke="${color}" d="M${x1} ${y1} L${x2} ${y2}"/>`
        + `<path d="M-11,-9 L11,-9 L0,10 Z" transform="translate(${fx} ${fy}) rotate(${giro})" fill="${color}" stroke="#fff" stroke-width="2" stroke-linejoin="round"/>`;
    }

    // Tension entre fases: en kV la de media tension, en V la de baja. Solo informativa.
    const voltios = v => v >= 1000 ? (v / 1000).toFixed(2) + " kV" : n0(v) + " V";
    // Linea chica de un medidor: factor de potencia, corriente y tension (lo que haya).
    const medidas = (pf, a, v) => [pf == null ? null : "FP " + pf.toFixed(2), a == null ? null : (a < 100 ? a.toFixed(1) : n0(a)) + " A",
      v == null ? null : voltios(v)].filter(x => x).join(" · ");

    // Estado de un generador, sin detalle: en marcha, listo para arrancar, no listo (falla) o sin comunicacion.
    function estadoGenerador(g) {
      if (g.estado === "EN_MARCHA") return { texto: "EN MARCHA", falla: false };
      if (g.estado === "PARADO") return g.listo ? { texto: "LISTO PARA ARRANCAR", falla: false } : { texto: "NO LISTO PARA ARRANCAR", falla: true };
      return { texto: "SIN COMUNICACION", falla: true };
    }

    function iconoAlarma(x, y) {
      return `<g transform="translate(${x} ${y})"><path d="M11 1 L21 19 H1 Z" fill="#fff" stroke="${ROJO}" stroke-width="2" stroke-linejoin="round"/>`
        + `<rect x="10" y="7" width="2" height="6" fill="${ROJO}"/><rect x="10" y="15" width="2" height="2" fill="${ROJO}"/></g>`;
    }

    function tarjeta(titulo, valor, detalle, color) {
      return `<div class="ut-tarjeta" style="border-left-color:${color}"><div class="t">${esc(titulo)}</div><div class="v">${esc(valor)}</div><div class="d">${esc(detalle)}</div></div>`;
    }

    function tarjetas(d) {
      let h = tarjeta("Consumo de la planta", d.consumo == null ? "sin dato" : n0(d.consumo) + " kW", "medidor principal + generadores", "#1E1E2C");
      d.transformadores.forEach(t => t.generadores.forEach(g => {
        const e = estadoGenerador(g);
        const valor = g.estado === "EN_MARCHA" ? (g.aporte == null ? kw(g.kw) : n0(g.aporte) + " kW")
          : e.texto.charAt(0) + e.texto.slice(1).toLowerCase();
        h += tarjeta(g.nombre + " · " + t.nombre, valor, g.pct == null ? "" : pct(g.pct) + " del consumo", g.color);
      }));
      const colorRed = d.meta ? SEMAFORO[d.meta][0] : RED;
      h += tarjeta("De la red", d.conPrincipal ? n0(d.importada) + " kW" : "sin dato",
        d.pctRed == null ? "" : pct(d.pctRed) + " del consumo" + (d.meta ? " · meta 0" : ""), colorRed);
      h += tarjeta("Exportada a la red", d.conPrincipal ? n0(d.exportada) + " kW" : "sin dato", "", EXPORTA);
      return `<div class="ut-tarjetas">${h}</div>`;
    }

    function esquema(d) {
      const trafos = d.transformadores, n = trafos.length;
      const cx = trafos.map((t, i) => n === 1 ? X_RED : 300 + i * 500 / (n - 1));
      let s = "";
      // Red y medidor principal
      const p = d.principal, importa = p.kw != null && p.kw >= 0;
      // Empresa electrica en verde con red, en rojo sin red (segun los controladores de los generadores), gris sin dato.
      const [bordeRed, fondoRed, textoRed] = d.red === "ok" ? [VERDE, "#E7F6EE", "#1F7A4D"]
        : d.red === "falla" ? [ROJO, "#FDECEE", ROJO] : ["#A9AFBB", "#fff", "#1E1E2C"];
      s += `<rect x="${X_RED - 80}" y="10" width="160" height="38" rx="6" fill="${fondoRed}" stroke="${bordeRed}" stroke-width="2.5"/>`;
      s += texto(X_RED, 34, "Empresa electrica", `text-anchor="middle" font-weight="600" fill="${textoRed}"`);
      s += `<path class="ut-cable" d="M${X_RED} 48 V${Y_NODO}"/>`;
      s += importa ? tramo(X_RED, 48, X_RED, Y_NODO, RED, p.kw, [X_RED, 160])
        : tramo(X_RED, Y_NODO, X_RED, 48, EXPORTA, p.kw, [X_RED, 160]);
      s += `<circle cx="${X_RED}" cy="100" r="17" fill="#fff" stroke="#1E1E2C" stroke-width="2"/>`;
      s += texto(X_RED, 105, "M", 'text-anchor="middle" font-weight="700"');
      s += texto(X_RED + 26, 96, "Medidor principal", 'font-weight="600" font-size="13"');
      s += texto(X_RED + 26, 113, medidas(p.pf, p.amperes, p.voltios), 'class="chico"');
      s += texto(X_RED - 12, 160, kw(p.kw), `text-anchor="end" class="valor" fill="${p.kw == null ? MAQUINAS : importa ? RED : EXPORTA}"`);

      // Balance del nodo
      if (d.nodo) {
        const b = d.nodo, [color, fondo] = SEMAFORO[b.estado];
        s += `<rect x="90" y="96" width="340" height="80" rx="10" fill="#fff" stroke="#E4E6EC" stroke-width="1.5"/>`;
        s += texto(106, 118, "Medidor principal", 'class="chico"') + texto(414, 118, n0(b.principal) + " kW", 'text-anchor="end" font-weight="600"');
        s += texto(106, 138, trafos.map(t => t.nombre).join(" + "), 'class="chico"')
          + texto(414, 138, n0(b.transformadores) + " kW", 'text-anchor="end" font-weight="600"');
        s += `<rect x="98" y="146" width="324" height="24" rx="6" fill="${fondo}"/>`;
        s += texto(106, 163, "Diferencia de medicion", `font-size="13" font-weight="600" fill="${color}"`);
        s += texto(414, 163, (b.diferencia >= 0 ? "+" : "-") + n0(Math.abs(b.diferencia)) + " kW (" + (b.porcentaje >= 0 ? "+" : "")
          + b.porcentaje.toFixed(1) + " %)", `text-anchor="end" font-weight="700" fill="${color}"`);
      }

      // Barra de media tension y nodo
      s += `<path class="ut-cable" d="M${Math.min(...cx, X_RED) - 40} ${Y_NODO} H${Math.max(...cx, X_RED) + 40}"/>`;
      trafos.forEach((t, i) => {
        const k = t.medidor.kw, x = cx[i];
        if (k == null || Math.abs(x - X_RED) < 1) return;
        const baja = k >= 0, color = baja ? RED : t.color;
        s += baja ? tramo(X_RED, Y_NODO, x, Y_NODO, color, k) : tramo(x, Y_NODO, X_RED, Y_NODO, color, k);
        if (t.paso >= 0.5) s += texto((x + X_RED) / 2, Y_NODO - 10, n0(t.paso) + " kW", `text-anchor="middle" class="valor" fill="${t.color}"`);
      });
      s += `<circle cx="${X_RED}" cy="${Y_NODO}" r="16" fill="none" stroke="#1E1E2C" stroke-width="1.5" stroke-dasharray="3 3"/>`;
      s += `<circle cx="${X_RED}" cy="${Y_NODO}" r="7" fill="#1E1E2C"/>`;
      s += texto(X_RED, Y_NODO + 30, "media tension", 'text-anchor="middle" class="chico"');

      // Cada transformador con su baja tension
      trafos.forEach((t, i) => {
        const x = cx[i], m = t.medidor, izq = x < X_RED, baja = m.kw != null && m.kw >= 0;
        const colorT = m.kw == null ? MAQUINAS : baja ? RED : t.color;
        // Alimentador de media tension hasta el transformador (la flecha da el sentido de lo que pasa por el)
        s += `<path class="ut-cable" d="M${x} ${Y_NODO} V254"/>`;
        s += baja ? tramo(x, Y_NODO, x, 254, colorT, m.kw, [x, 228]) : tramo(x, 254, x, Y_NODO, colorT, m.kw, [x, 228]);
        s += `<circle cx="${x}" cy="270" r="16" fill="#fff" stroke="#1E1E2C" stroke-width="2"/><circle cx="${x}" cy="292" r="16" fill="#fff" stroke="#1E1E2C" stroke-width="2"/>`;
        s += texto(x + 26, 286, t.nombre, 'font-weight="700" font-size="15"');
        // El medidor del transformador esta en baja tension: entre el transformador y su barra, con sus valores al lado.
        s += `<path class="ut-cable" d="M${x} 308 V390"/>`;
        s += baja ? tramo(x, 308, x, 390, colorT, m.kw, [x, 377]) : tramo(x, 390, x, 308, colorT, m.kw, [x, 377]);
        s += `<circle cx="${x}" cy="340" r="15" fill="#fff" stroke="#1E1E2C" stroke-width="2"/>`;
        s += texto(x, 345, "M", 'text-anchor="middle" font-weight="700" font-size="13"');
        const lx = izq ? x - 24 : x + 24, anc = izq ? 'text-anchor="end" ' : "";
        s += texto(lx, 338, kw(m.kw), `${anc}class="valor" fill="${colorT}"`);
        s += texto(lx, 356, medidas(m.pf, m.amperes, m.voltios), `${anc}class="chico"`);
        s += `<path class="ut-cable" d="M${x - 160} 390 H${x + 180}" stroke-width="6"/>`;
        s += texto(x - 160, 381, "baja tension", 'class="chico"');

        // Generadores
        t.generadores.forEach((g, k) => {
          const gx = x - 100 - k * 110, e = estadoGenerador(g), marcha = g.estado === "EN_MARCHA";
          // G en rojo con alarmas en el controlador o sin poder arrancar; si no, el color del generador en marcha o gris.
          const conAlarma = g.estado !== "SIN_COMUNICACION" && g.alarmas > 0;
          const color = conAlarma || e.falla && g.estado === "PARADO" ? ROJO : marcha ? g.color : GRIS;
          s += `<path class="ut-cable" d="M${gx} 390 V451"/>`;
          s += tramo(gx, 451, gx, 390, g.color, marcha ? g.kw : 0, [gx, 420]);
          s += `<circle cx="${gx}" cy="475" r="24" fill="#fff" stroke="${color}" stroke-width="3"/>`;
          s += texto(gx, 482, "G", `text-anchor="middle" font-weight="700" font-size="18" fill="${color}"`);
          // Con alarmas en el controlador, solo el icono de advertencia en rojo (sin detallar cuales).
          if (conAlarma) s += iconoAlarma(gx + 30, 446);
          s += texto(gx, 520, g.nombre, 'text-anchor="middle" font-weight="700"');
          const estado = e.texto + (marcha && g.pf != null ? " · FP " + g.pf.toFixed(2) : "") + (marcha && g.voltios != null ? " · " + voltios(g.voltios) : "");
          s += texto(gx, 538, estado, `text-anchor="middle" font-size="12" font-weight="600" fill="${e.falla ? ROJO : "#1F7A4D"}"`);
          if (marcha) {
            s += texto(gx, 560, "aporta " + (g.aporte == null ? kw(g.kw) : n0(g.aporte) + " kW"), `text-anchor="middle" class="valor" fill="${g.color}"`);
            if (g.pct != null) s += texto(gx, 578, pct(g.pct) + " de la planta", 'text-anchor="middle" class="chico"');
          }
        });

        // Maquinas con medidor
        const mx = x + 100;
        s += `<path class="ut-cable" d="M${mx} 390 V444"/>`;
        s += tramo(mx, 390, mx, 444, MAQUINAS, t.maquinas, [mx, 418]);
        s += `<rect x="${mx - 75}" y="444" width="150" height="80" rx="8" fill="#fff" stroke="#A9AFBB" stroke-width="2"/>`;
        s += texto(mx, 464, "Maquinas con medidor", 'text-anchor="middle" class="chico"');
        s += texto(mx, 486, n0(t.maquinas) + " kW", 'text-anchor="middle" class="valor"');
        if (t.resto != null) {
          s += texto(mx, 510, (t.resto >= 0 ? "sin medidor " : "diferencia ") + (t.resto >= 0 ? "" : "-") + n0(Math.abs(t.resto)) + " kW",
            `text-anchor="middle" font-size="12" fill="${t.resto >= 0 ? "#6C7383" : "#B8920A"}"`);
        }
        if (t.maquinasConDato < t.maquinasTotal) {
          s += texto(mx, 540, "sin dato de " + (t.maquinasTotal - t.maquinasConDato) + " de " + t.maquinasTotal, 'text-anchor="middle" font-size="12" fill="#B8920A"');
        }
      });
      return `<svg viewBox="0 0 ${W} 600" role="img" aria-label="Diagrama unifilar de la planta 1">${s}</svg>`;
    }

    function leyenda(d) {
      let h = `<span style="--c:${RED}">De la red</span><span style="--c:${EXPORTA}">Hacia la red</span>`;
      d.transformadores.forEach(t => t.generadores.forEach(g => { h += `<span style="--c:${g.color}">${esc(g.nombre)}</span>`; }));
      return `<div class="ut-leyenda">${h}<span style="--c:${MAQUINAS}">Maquinas</span></div>`;
    }

    function render(el, d) {
      if (!document.getElementById("ut-estilos")) {
        const st = document.createElement("style");
        st.id = "ut-estilos";
        st.textContent = CSS;
        document.head.appendChild(st);
      }
      // Al cambiar el tamano de la ventana, la escala de Windows o el zoom del navegador, se vuelve a ajustar.
      if (!el.utAjuste) {
        el.utAjuste = true;
        let espera;
        window.addEventListener("resize", () => { clearTimeout(espera); espera = setTimeout(() => ajustar(el), 150); });
      }
      el.innerHTML = `<div class="ut">${tarjetas(d)}<section class="ut-caja"><h2>Diagrama unifilar</h2>`
        + `<div class="ut-esquema">${esquema(d)}</div>${leyenda(d)}</section></div>`;
      ajustar(el);
    }

    /*
     * Achica el diagrama lo justo para que la pantalla entre entera en lo que se ve (mismo criterio que el tablero de
     * Generadores): el alto del dibujo se limita a lo que queda desde su borde de arriba hasta el de abajo del area visible
     * (la ventana o el panel con barra de desplazamiento que lo contiene), sin la leyenda. No baja de ALTO_MIN px: por
     * debajo queda la barra de desplazamiento.
     */
    const ALTO_MIN = 380;
    function ajustar(el) {
      const svg = el.querySelector(".ut-esquema svg");
      if (!svg || !el.isConnected) return;
      svg.style.height = "";
      let desplazado = 0, abajo = window.innerHeight;
      for (let n = svg; n; n = n.assignedSlot || n.parentNode || n.host) {
        if (!(n instanceof Element)) continue;
        // scrollTop, clientHeight y el relleno van en la medida propia del elemento; con la pagina achicada (escala de
        // Windows) se pasan a la medida de la pantalla con su zoom.
        const zn = n.currentCSSZoom || 1;
        desplazado += (n.scrollTop || 0) * zn;
        const st = getComputedStyle(n);
        if (n !== svg && n !== document.documentElement && n !== document.body && /auto|scroll/.test(st.overflowY)
            && !n.classList.contains("ut-esquema")) {
          abajo = Math.min(abajo, n.getBoundingClientRect().top + (n.clientTop + n.clientHeight - parseFloat(st.paddingBottom || 0)) * zn);
        }
      }
      // Debajo del dibujo van la leyenda y el borde de la caja.
      const caja = svg.closest(".ut-caja"), r = svg.getBoundingClientRect();
      const debajo = caja.getBoundingClientRect().bottom - r.bottom;
      const disponible = abajo - (r.top + desplazado) - debajo - 8;
      const zoom = svg.currentCSSZoom || 1;
      if (disponible > 0 && disponible < r.height) svg.style.height = Math.max(ALTO_MIN, disponible / zoom) + "px";
    }

    return { render };
  })();
}
