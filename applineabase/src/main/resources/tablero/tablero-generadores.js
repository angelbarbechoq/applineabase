// Tablero de los generadores (Generadores > Estado actual), al estilo de la pantalla ComAp. Lo dibuja el navegador con
// los datos que manda GeneradorView cada 15 s (TableroGeneradores.render(elemento, lista)). Solo muestra: el modo del
// controlador se ve, nunca se cambia desde la app. Las clases llevan el prefijo tg- para no chocar con las de la app.
if (!window.TableroGeneradores) {
  window.TableroGeneradores = (function () {
    const CSS = `
.tg { display: grid; gap: 28px; color: #1E1E2C; font: 14px/1.4 "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
.tg * { box-sizing: border-box; }
.tg-tablero { display: grid; gap: 10px; }
.tg-card { background: #fff; border: 1px solid #E4E6EC; border-radius: 12px; padding: 12px 14px; min-width: 0; }
.tg-card h3 { margin: 0 0 8px; font-size: 13px; font-weight: 600; color: #6b7080; text-transform: uppercase; letter-spacing: .04em; }
.tg-barra { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 16px; }
.tg-nombre { font-size: 20px; font-weight: 700; }
.tg-sub, .tg-leido { color: #6b7080; font-size: 13px; }
.tg-leido { font-size: 12px; }
.tg-badge { display: inline-block; padding: 3px 10px; border-radius: 6px; font-weight: 600; font-size: 12px; }
.tg-ok { background: #e3f5ea; color: #1d7a47; } .tg-aviso { background: #fbf2cf; color: #7a6205; }
.tg-fuera { background: #fbe1e3; color: #a3222c; } .tg-gris { background: #eceef2; color: #555b69; }
.tg-modos { display: inline-flex; gap: 4px; margin-left: auto; }
.tg-modos span { padding: 5px 14px; border-radius: 6px; border: 1px solid #c9cdd6; background: #f1f2f5; color: #6b7080; font-weight: 600; font-size: 13px; }
.tg-modos span.tg-act.AUTO { background: #3BB273; border-color: #3BB273; color: #fff; }
.tg-modos span.tg-act.MAN, .tg-modos span.tg-act.TEST { background: #E0B50F; border-color: #E0B50F; color: #fff; }
.tg-modos span.tg-act.OFF { background: #E8505B; border-color: #E8505B; color: #fff; }
.tg-esquema { background: #2B2F3A; border-radius: 12px; padding: 12px 14px; display: grid; grid-template-columns: minmax(0, 2.2fr) minmax(220px, 1fr); gap: 14px; color: #fff; }
.tg-esquema > svg { width: 100%; height: auto; display: block; }
.tg-alarmas { background: #20232c; border-radius: 10px; padding: 10px; display: flex; flex-direction: column; gap: 6px; }
.tg-alarmas .tg-tit { font-weight: 700; font-size: 15px; display: flex; align-items: center; gap: 8px; }
.tg-alarma { background: #E8505B; color: #fff; border-radius: 4px; padding: 5px 8px; font-weight: 600; }
.tg-alarma.tg-inactiva { background: #7a6205; }
.tg-alarma small { display: block; font-weight: 400; opacity: .9; }
.tg-sin-alarmas { color: #9fe0bb; font-size: 13px; }
.tg-fila2 { display: grid; grid-template-columns: minmax(0, 2.4fr) minmax(170px, .8fr) minmax(190px, .9fr); gap: 10px; }
.tg-fila3 { display: grid; grid-template-columns: minmax(0, .95fr) minmax(0, 1fr) minmax(0, 1.15fr) minmax(190px, .9fr); gap: 10px; }
@media (max-width: 1000px) { .tg-fila2, .tg-fila3, .tg-esquema { grid-template-columns: minmax(0, 1fr); } }
.tg-barras { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 6px; }
@media (max-width: 640px) { .tg-barras { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
.tg-grupo .tg-gtit { font-size: 12px; font-weight: 600; text-align: center; min-height: 32px; display: flex; flex-direction: column; align-items: center; justify-content: flex-end; gap: 2px; }
.tg-grupo .tg-hz { font-family: Consolas, "Courier New", monospace; }
.tg-grupo svg { width: 100%; height: auto; }
.tg-sep { border-left: 1px solid #E4E6EC; }
.tg-estados { background: #3A3F4C; color: #fff; border-radius: 10px; padding: 10px 12px; display: grid; gap: 10px; }
.tg-estados .tg-et { font-size: 12px; color: #c9ccd6; }
.tg-estados .tg-ev { font-size: 18px; font-weight: 700; text-align: center; }
.tg-estados .tg-eg { font-size: 12px; text-align: center; color: #e2e4ea; }
.tg-estados hr { border: 0; border-top: 1px solid #5a6070; margin: 0; }
table.tg-io { width: 100%; border-collapse: collapse; font-size: 13px; }
table.tg-io td { padding: 3px 6px; border-bottom: 1px solid #eef0f4; }
table.tg-io td:first-child { width: 18px; color: #6b7080; }
table.tg-io td:last-child { width: 26px; text-align: center; font-family: Consolas, "Courier New", monospace; }
table.tg-io tr.tg-on td:last-child { background: #3BB273; color: #fff; }
table.tg-io tr.tg-alerta td { background: #E8505B; color: #fff; }
table.tg-io tr.tg-nousado td { color: #b0b4bf; }
.tg-medida { margin-bottom: 10px; }
.tg-medida .tg-mt { display: flex; justify-content: space-between; font-size: 12px; }
.tg-medida .tg-mt b { font-family: Consolas, "Courier New", monospace; }
.tg-medida svg { width: 100%; height: auto; }
.tg-nota { color: #6b7080; font-size: 12px; }
.tg-kw { display: grid; gap: 6px; }
.tg-kw svg { width: 100%; max-width: 300px; height: auto; margin: 0 auto; display: block; }
.tg-cajas { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px 10px; }
.tg-caja { display: flex; align-items: center; gap: 6px; font-size: 12px; justify-content: flex-end; }
.tg-caja b { font-family: Consolas, "Courier New", monospace; font-size: 14px; border: 1px solid #E4E6EC; border-radius: 4px; padding: 2px 8px; min-width: 60px; text-align: right; }
.tg-caja b.tg-ok { background: #3BB273; color: #fff; border-color: #3BB273; }
.tg-caja b.tg-aviso { background: #E0B50F; color: #fff; border-color: #E0B50F; }
.tg-caja b.tg-fuera { background: #E8505B; color: #fff; border-color: #E8505B; }
.tg-contador { margin-bottom: 8px; }
.tg-contador .tg-ct { font-size: 12px; color: #6b7080; }
.tg-digitos { display: flex; align-items: center; gap: 2px; font-family: Consolas, "Courier New", monospace; font-size: 16px; font-weight: 600; }
.tg-digitos i { font-style: normal; width: 17px; height: 24px; border: 1px solid #cfd3db; border-radius: 3px; display: inline-flex; align-items: center; justify-content: center; background: #fafbfc; }
.tg-digitos i.tg-dec { background: #eceef2; }
.tg-digitos u { text-decoration: none; margin-left: 6px; font-size: 13px; font-weight: 600; color: #6b7080; font-family: "Segoe UI", Roboto, sans-serif; }
`;
    const MOTOR_ES = { Init: "iniciando", Ready: "listo para arrancar", NotReady: "no puede arrancar", Prestart: "prearranque",
      Cranking: "arrancando", Pause: "pausa entre intentos", Starting: "arrancando", Running: "en marcha", Loaded: "en marcha con carga",
      "Soft unld": "soltando carga", Cooling: "enfriando", Stop: "parando", Shutdown: "parado por falla", Ventil: "ventilando",
      EmergMan: "manual de emergencia", "Soft load": "tomando carga", WaitStop: "esperando la parada", SDVentil: "ventilando tras falla",
      Deexcitation: "desexcitando", Warming: "calentando", Offload: "soltando carga" };
    const INTERR_ES = { Init: "iniciando", BrksOff: "interruptores abiertos", IslOper: "en isla", MainsOper: "con red, generador desconectado",
      ParalOper: "en paralelo con la red", RevSync: "sincronizando para volver a la red", Synchro: "sincronizando", MainsFlt: "falla de red",
      ValidFlt: "falla de red confirmada", MainsRet: "retorno de red", MultIslOp: "isla con varios generadores",
      MultParOp: "paralelo con varios generadores", EmergMan: "manual de emergencia", StrUpSync: "sincronizando al arrancar" };
    const VERDE = "#3BB273", APAG = "#d8dbe2", ROJO = "#E8505B", AMARILLO = "#E0B50F";
    const fmt = (v, d = 0) => v == null ? "-" : Number(v).toLocaleString("en-US", { minimumFractionDigits: d, maximumFractionDigits: d });
    const esc = s => String(s == null ? "" : s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

    function css() {
      if (document.getElementById("tg-estilos")) return;
      const s = document.createElement("style");
      s.id = "tg-estilos";
      s.textContent = CSS;
      document.head.appendChild(s);
    }

    function tablero(g) {
      const modos = ["OFF", "MAN", "AUTO", "TEST"].map(m => `<span class="${m}${m === g.modo ? " tg-act" : ""}">${m}</span>`).join("");
      const barra = `<div class="tg-card tg-barra">
        <span class="tg-nombre">${esc(g.nombre)}</span><span class="tg-sub">${esc(g.modelo)} · ${esc(g.trafo)}</span>
        ${g.sinComunicacion != null
          ? `<span class="tg-badge tg-fuera">SIN COMUNICACION</span><span class="tg-leido">${esc(g.sinComunicacion)}</span>`
          : `<span class="tg-badge ${g.marcha ? "tg-ok" : "tg-gris"}">${g.marcha ? "EN MARCHA" : "PARADO"}</span>
             <span class="tg-leido">Leido ${esc(g.leido)}${g.reloj ? ` · reloj del controlador ${esc(g.reloj)} <span class="tg-badge ${g.desfaseAlto ? "tg-aviso" : "tg-ok"}">${esc(g.desfase)}</span>` : ""}</span>`}
        ${g.modo != null ? `<span class="tg-modos">${modos}</span>` : ""}</div>`;
      if (g.sinComunicacion != null) return `<section class="tg-tablero">${barra}</section>`;
      return `<section class="tg-tablero">${barra}
        <div class="tg-esquema">${esquema(g)}${alarmas(g)}</div>
        <div class="tg-fila2">
          <div class="tg-card"><h3>Tensiones, corriente y frecuencia</h3>${barras(g)}</div>
          <div class="tg-card"><h3>Estado</h3>${estados(g)}</div>
          <div class="tg-card"><h3>Entradas binarias</h3>${io(g.entradas, g, true)}</div>
        </div>
        <div class="tg-fila3">
          <div class="tg-card"><h3>Motor</h3>${motor(g)}</div>
          <div class="tg-card"><h3>Potencia del generador</h3>${indicador(g)}</div>
          <div class="tg-card"><h3>Contadores</h3>${contadores(g)}</div>
          <div class="tg-card"><h3>Salidas binarias</h3>${io(g.salidas, g, false)}</div>
        </div></section>`;
    }

    /* Red (medidor del transformador) - interruptor de la planta - planta - interruptor del generador - generador */
    function esquema(g) {
      const v = g.v, red = g.redKw, gen = v.KW || 0;
      const hayRed = (v.RED_V_L1L2 || 0) > 100, genTension = (v.V_L1L2 || 0) > 100;
      const mcb = g.mcb === true, gcb = g.gcb === true;
      const planta = red == null ? null : red + gen;
      const vivoPlanta = (mcb && hayRed) || (gcb && genTension);
      const linea = (x1, x2, vivo) => `<line x1="${x1}" y1="112" x2="${x2}" y2="112" stroke="${vivo ? VERDE : APAG}" stroke-width="12" stroke-linecap="round"/>`;
      const interruptor = (x, cerrado, nombre) => cerrado == null
        ? `<rect x="${x}" y="90" width="44" height="44" rx="6" fill="#555b69"/><text x="${x + 22}" y="117" text-anchor="middle" fill="#fff" font-size="13">?</text>
           <text x="${x + 22}" y="156" text-anchor="middle" fill="#fff" font-size="13" font-weight="700">${nombre}</text>`
        : `<rect x="${x}" y="90" width="44" height="44" rx="6" fill="${cerrado ? VERDE : "#fff"}" stroke="${cerrado ? VERDE : ROJO}" stroke-width="3"/>
        ${cerrado ? `<line x1="${x + 8}" y1="112" x2="${x + 36}" y2="112" stroke="#fff" stroke-width="5" stroke-linecap="round"/>`
                  : `<line x1="${x + 10}" y1="124" x2="${x + 34}" y2="100" stroke="${ROJO}" stroke-width="5" stroke-linecap="round"/>`}
        <text x="${x + 22}" y="156" text-anchor="middle" fill="#fff" font-size="13" font-weight="700">${nombre}</text>
        <text x="${x + 22}" y="172" text-anchor="middle" fill="${cerrado ? "#9fe0bb" : "#ffb3b8"}" font-size="12">${cerrado ? "cerrado" : "abierto"}</text>`;
      const kw = (x, val, color, sub, anc = "middle") => `
        <text x="${x}" y="30" text-anchor="${anc}" fill="${color}" font-size="24" font-weight="700">${val == null ? "-" : fmt(val) + " kW"}</text>
        <text x="${x}" y="48" text-anchor="${anc}" fill="#c9ccd6" font-size="12">${esc(sub)}</text>`;
      const torre = (x, c) => `<g stroke="${c}" stroke-width="4" fill="none" stroke-linejoin="round" stroke-linecap="round">
        <path d="M${x - 18} 150 L${x - 6} 70 L${x + 6} 70 L${x + 18} 150"/><path d="M${x - 24} 88 H${x + 24} M${x - 20} 104 H${x + 20}"/>
        <path d="M${x - 14} 120 L${x + 12} 100 M${x + 14} 120 L${x - 12} 100 M${x - 16} 140 L${x + 14} 122 M${x + 16} 140 L${x - 14} 122"/></g>`;
      const fabrica = (x, c) => `<g fill="${c}"><path d="M${x - 34} 150 V100 L${x - 14} 112 V100 L${x + 6} 112 V100 L${x + 26} 112 V78 H${x + 34} V150 Z"/>
        <rect x="${x - 22}" y="128" width="9" height="9" fill="#2B2F3A"/><rect x="${x - 4}" y="128" width="9" height="9" fill="#2B2F3A"/><rect x="${x + 14}" y="128" width="9" height="9" fill="#2B2F3A"/></g>`;
      const motorG = (x, c) => `<circle cx="${x}" cy="112" r="34" fill="none" stroke="${c}" stroke-width="5"/>
        <text x="${x}" y="126" text-anchor="middle" fill="${c}" font-size="38" font-weight="800">G</text>`;
      const exporta = red != null && red < 0;
      return `<svg viewBox="0 0 760 185" role="img" aria-label="Esquema de red, planta y generador">
        ${kw(14, red == null ? null : Math.abs(red), "#7fb6f7", exporta ? "exportando a la red" : "de la red · " + g.trafo, "start")}
        ${torre(60, hayRed ? VERDE : APAG)}
        ${linea(96, 218, hayRed)}${interruptor(218, g.mcb, "MCB")}${linea(262, 342, vivoPlanta)}
        ${kw(385, planta, "#ffffff", "planta (" + g.trafo.toLowerCase() + ")")}
        ${fabrica(385, vivoPlanta ? VERDE : APAG)}
        ${linea(428, 520, vivoPlanta)}${interruptor(520, g.gcb, "GCB")}${linea(564, 640, genTension && gcb)}
        ${kw(690, gen, "#F29F67", "generador")}
        ${motorG(690, genTension ? VERDE : "#ffffff")}
      </svg>`;
    }

    function alarmas(g) {
      const lista = g.alarmas || [], color = lista.length ? ROJO : "#9fe0bb";
      const fila = a => `<div class="tg-alarma${a.activa ? "" : " tg-inactiva"}">${a.sinReset ? "*" : ""}${esc(a.nombre)}
        <small>${a.activa ? "activa" : "ya no activa"}${a.sinReset ? ", sin reset" : ""}</small></div>`;
      return `<div class="tg-alarmas"><div class="tg-tit"><svg width="22" height="20" viewBox="0 0 22 20"><path d="M11 1 L21 19 H1 Z" fill="none" stroke="${color}" stroke-width="2"/>
        <rect x="10" y="7" width="2" height="6" fill="${color}"/><rect x="10" y="15" width="2" height="2" fill="${color}"/></svg>Lista de alarmas</div>
        ${lista.length ? lista.map(fila).join("") : `<div class="tg-sin-alarmas">Sin alarmas en el controlador</div>`}</div>`;
    }

    /* Grupo de 3 barras con escala, lineas de limite (fuera de rango de Calidad de energia, o la nominal) y valores debajo */
    function grupo(titulo, hz, hzEstado, vals, etiquetas, max, limites, color) {
      const W = 150, H = 150, x0 = 30, y0 = 10, alto = 110;
      const y = val => y0 + alto - Math.min(Math.max(val, 0), max) / max * alto;
      let s = `<svg viewBox="0 0 ${W} ${H}">`;
      for (let i = 0; i <= 4; i++) {
        const val = max / 4 * i, yy = y(val);
        s += `<line x1="${x0}" x2="${W - 4}" y1="${yy}" y2="${yy}" stroke="#eef0f4"/><text x="${x0 - 4}" y="${yy + 3}" text-anchor="end" font-size="8" fill="#8a8f9c">${fmt(val)}</text>`;
      }
      limites.filter(l => l > 0).forEach(l => s += `<line x1="${x0}" x2="${W - 4}" y1="${y(l)}" y2="${y(l)}" stroke="${ROJO}" stroke-width="1.2" stroke-dasharray="4 3"/>`);
      vals.forEach((val, i) => {
        const bx = x0 + 8 + i * 38, top = y(val || 0);
        s += `<rect x="${bx}" y="${top}" width="26" height="${y0 + alto - top}" rx="2" fill="${color}"/>
          <text x="${bx + 13}" y="${y0 + alto + 13}" text-anchor="middle" font-size="10" font-weight="700" fill="#1E1E2C">${fmt(val)}</text>
          <text x="${bx + 13}" y="${y0 + alto + 25}" text-anchor="middle" font-size="8" fill="#8a8f9c">${etiquetas[i]}</text>`;
      });
      s += "</svg>";
      const hzTxt = hz === undefined ? "" : `<span class="tg-hz tg-badge tg-${hzEstado || "gris"}">${fmt(hz, 1)} Hz</span>`;
      return `<div class="tg-grupo"><div class="tg-gtit">${hzTxt}<span>${titulo}</span></div>${s}</div>`;
    }

    function barras(g) {
      const v = g.v, n = g.nom, f = 1 + g.fueraPct / 100, f2 = 1 - g.fueraPct / 100, RED = "#3B8FF3", GEN = "#F29F67";
      const escala = nominal => Math.ceil(nominal * 1.35 / 100) * 100;
      const imax = n.i ? Math.ceil(n.i * 1.25 / 500) * 500 : 2000;
      return `<div class="tg-barras">
        ${grupo("Red L-N [V]", v.RED_FRECUENCIA, g.hzRed, [v.RED_V_L1N, v.RED_V_L2N, v.RED_V_L3N], ["L1-N", "L2-N", "L3-N"], escala(n.redLN), [n.redLN * f, n.redLN * f2], RED)}
        ${grupo("Red L-L [V]", undefined, null, [v.RED_V_L1L2, v.RED_V_L2L3, v.RED_V_L3L1], ["L1-L2", "L2-L3", "L3-L1"], escala(n.redLL), [n.redLL * f, n.redLL * f2], RED)}
        <div class="tg-sep">${grupo("Generador corriente [A]", undefined, null, [v.I_L1, v.I_L2, v.I_L3], ["L1", "L2", "L3"], imax, [n.i || 0], GEN)}</div>
        ${grupo("Generador L-N [V]", v.FRECUENCIA, g.hzGen, [v.V_L1N, v.V_L2N, v.V_L3N], ["L1-N", "L2-N", "L3-N"], escala(n.genLN), [n.genLN * f, n.genLN * f2], GEN)}
        ${grupo("Generador L-L [V]", undefined, null, [v.V_L1L2, v.V_L2L3, v.V_L3L1], ["L1-L2", "L2-L3", "L3-L1"], escala(n.genLL), [n.genLL * f, n.genLL * f2], GEN)}
      </div>`;
    }

    function estados(g) {
      const m = g.estadoMotor, i = g.estadoInterruptores;
      const colorMotor = m === "NotReady" || m === "Shutdown" ? "#ffb3b8" : m === "Ready" || m === "Loaded" || m === "Running" ? "#9fe0bb" : "#fff";
      const bloque = (titulo, valor, glosa, color) => `<div><div class="tg-et">${titulo}</div>
        <div class="tg-ev" style="color:${color}">${valor == null ? "-" : esc(valor)}</div><div class="tg-eg">${esc(glosa || "")}</div></div>`;
      return `<div class="tg-estados">${bloque("Estado del motor", m, MOTOR_ES[m], colorMotor)}<hr>
        ${bloque("Estado de interruptores", i, (INTERR_ES[i] || "") + (g.interruptoresDesde ? " desde las " + g.interruptoresDesde : ""), g.sinRed ? "#ffb3b8" : "#fff")}</div>`;
    }

    function io(lista, g, entradas) {
      if (!lista || !lista.length) return `<div class="tg-nota">El controlador no las informa.</div>`;
      const estop = (g.alarmas || []).some(a => /E-?STOP|Emergency/i.test(a.nombre));
      return `<table class="tg-io">${lista.map((x, i) => {
        const alerta = entradas && x.n === "Emergency Stop" && estop;
        const cls = x.n === "Not Used" ? "tg-nousado" : alerta ? "tg-alerta" : x.v === 1 ? "tg-on" : "";
        return `<tr class="${cls}"><td>${i + 1}</td><td>${esc(x.n)}</td><td>${x.v == null ? "-" : x.v}</td></tr>`;
      }).join("")}</table>`;
    }

    /* Barra horizontal con escala y marcas de aviso (amarillo) y fuera de rango (rojo) */
    function medida(titulo, val, unidad, min, max, aviso, fuera, d) {
      const W = 300, x = v => 8 + (Math.min(Math.max(v, min), max) - min) / (max - min) * (W - 16);
      const ancho = val == null ? 0 : x(val) - 8;
      let s = `<svg viewBox="0 0 ${W} 34"><rect x="8" y="4" width="${W - 16}" height="12" rx="2" fill="#eef0f4"/>
        <rect x="8" y="4" width="${ancho}" height="12" rx="2" fill="${VERDE}"/>
        <line x1="${x(aviso)}" x2="${x(aviso)}" y1="1" y2="19" stroke="${AMARILLO}" stroke-width="2"/>
        <line x1="${x(fuera)}" x2="${x(fuera)}" y1="1" y2="19" stroke="${ROJO}" stroke-width="2"/>`;
      for (let i = 0; i <= 5; i++) { const tv = min + (max - min) / 5 * i; s += `<text x="${x(tv)}" y="31" text-anchor="middle" font-size="9" fill="#8a8f9c">${fmt(tv)}</text>`; }
      s += "</svg>";
      return `<div class="tg-medida"><div class="tg-mt"><span>${titulo}</span><b>${val == null ? "-" : fmt(val, d) + " " + unidad}</b></div>${s}</div>`;
    }

    function motor(g) {
      const v = g.v, u = g.umbrales;
      if (!g.mideAceite && !g.mideTemp) return `<div class="tg-nota">Presion de aceite y temperatura del refrigerante: no las mide este controlador.</div>`;
      return (g.mideAceite ? medida("Presion de aceite", v.PRESION_ACEITE, "bar", 0, 10, u.aceiteAviso, u.aceiteFuera, 1) : `<div class="tg-nota">Presion de aceite: no la mide este controlador.</div>`)
        + (g.mideTemp ? medida("Temperatura refrigerante", v.TEMP_REFRIGERANTE, "C", -10, 120, u.tempAviso, u.tempFuera, 0) : `<div class="tg-nota">Temperatura del refrigerante: no la mide este controlador.</div>`);
    }

    /* Indicador de kW: arco de 240 grados hasta el 150 % de la potencia nominal */
    function indicador(g) {
      const v = g.v, nom = g.nom.kw || 1000, max = nom * 1.5, kw = Math.max(0, v.KW || 0);
      const cx = 130, cy = 120, r = 92, a0 = 210, a1 = -30;
      const ang = val => (a0 - (a0 - a1) * Math.min(val, max) / max) * Math.PI / 180;
      const p = (val, rr) => [cx + rr * Math.cos(ang(val)), cy - rr * Math.sin(ang(val))];
      const arco = (v1, v2, color) => {
        const [x1, y1] = p(v1, r), [x2, y2] = p(v2, r), grande = (a0 - a1) * (v2 - v1) / max > 180 ? 1 : 0;
        return `<path d="M${x1} ${y1} A${r} ${r} 0 ${grande} 1 ${x2} ${y2}" fill="none" stroke="${color}" stroke-width="18"/>`;
      };
      let s = `<svg viewBox="0 0 260 215">${arco(0, nom, VERDE)}${arco(nom, nom * 1.1, AMARILLO)}${arco(nom * 1.1, max, ROJO)}`;
      for (let i = 0; i <= 10; i++) {
        const val = max / 10 * i, [tx, ty] = p(val, r + 22), [l1x, l1y] = p(val, r - 10), [l2x, l2y] = p(val, r + 10);
        s += `<line x1="${l1x}" y1="${l1y}" x2="${l2x}" y2="${l2y}" stroke="#fff" stroke-width="2"/><text x="${tx}" y="${ty + 3}" text-anchor="middle" font-size="9" fill="#555b69">${fmt(val)}</text>`;
      }
      const [nx, ny] = p(kw, r - 16);
      s += `<line x1="${cx}" y1="${cy}" x2="${nx}" y2="${ny}" stroke="#555b69" stroke-width="5" stroke-linecap="round"/><circle cx="${cx}" cy="${cy}" r="9" fill="#555b69"/>
        <text x="${cx}" y="${cy + 52}" text-anchor="middle" font-size="30" font-weight="700" fill="#1E1E2C">${fmt(kw)}</text>
        <text x="${cx}" y="${cy + 68}" text-anchor="middle" font-size="11" fill="#8a8f9c">kW de ${fmt(nom)} kW nominales</text></svg>`;
      return `<div class="tg-kw">${s}<div class="tg-cajas">
        <div class="tg-caja">RPM <b>${fmt(v.RPM)}</b></div>
        <div class="tg-caja">PF <b>${g.pf == null ? "-" : fmt(g.pf, 2)}</b></div>
        <div class="tg-caja">kVAr <b>${fmt(v.KVAR)}</b></div>
        <div class="tg-caja">Bateria <b class="tg-${g.bateria || "gris"}">${v.BATERIA == null ? "-" : fmt(v.BATERIA, 1) + " V"}</b></div>
      </div></div>`;
    }

    function digitos(val, dec, unidad) {
      if (val == null) return `<span class="tg-digitos">-</span>`;
      const [ent, fr] = Number(val).toFixed(dec).split(".");
      const cajas = ent.padStart(7, " ").split("").map(c => `<i>${c.trim()}</i>`).join("")
        + (fr ? `,${fr.split("").map(c => `<i class="tg-dec">${c}</i>`).join("")}` : "");
      return `<span class="tg-digitos">${cajas}<u>${unidad}</u></span>`;
    }

    function contadores(g) {
      const v = g.v;
      return [["Horas de marcha", v.HORAS_MARCHA, 1, "h"], ["Contador de mantenimiento 1", v.MANTENIMIENTO, 0, "h"], ["Arranques", v.ARRANQUES, 0, ""],
        ["Energia generada", v.KWH, 0, "kWh"], ["Energia reactiva", v.KVARH, 0, "kVArh"]]
        .map(([t, val, d, u]) => `<div class="tg-contador"><div class="tg-ct">${t}</div>${digitos(val, d, u)}</div>`).join("");
    }

    return {
      render(el, lista) {
        css();
        el.innerHTML = `<div class="tg">${lista.map(tablero).join("")}</div>`;
      }
    };
  })();
}
