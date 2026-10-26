// Tablero de los generadores (Generadores > Estado actual), al estilo de la pantalla ComAp. Lo dibuja el navegador con
// los datos que manda GeneradorView cada 15 s (TableroGeneradores.render(elemento, lista)). Solo muestra: el modo del
// controlador se ve, nunca se cambia desde la app. Las clases llevan el prefijo tg- para no chocar con las de la app.
if (!window.TableroGeneradores) {
  window.TableroGeneradores = (function () {
    const CSS = `
.tg { display: grid; gap: 28px; color: #1E1E2C; font: 14px/1.4 "Segoe UI", Roboto, Helvetica, Arial, sans-serif; container-type: inline-size; }
.tg * { box-sizing: border-box; }
.tg-tablero { display: grid; gap: 8px; }
.tg-card { background: #fff; border: 1px solid #E4E6EC; border-radius: 12px; padding: 10px 12px; min-width: 0; }
.tg-card h3 { margin: 0 0 6px; font-size: 13px; font-weight: 600; color: #6b7080; text-transform: uppercase; letter-spacing: .04em; }
.tg-barra { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 16px; }
.tg-nombre { font: inherit; font-size: 20px; font-weight: 700; color: inherit; background: none; border: 0; border-radius: 8px; padding: 2px 8px 2px 4px;
  margin-left: -4px; display: inline-flex; align-items: center; gap: 8px; cursor: pointer; }
.tg-nombre:hover { background: #eef3fd; }
.tg-nombre svg { width: 20px; height: 20px; color: #3B8FF3; flex: none; }
.tg-nombre.tg-girando svg { animation: tg-girar .8s linear infinite; }
@keyframes tg-girar { to { transform: rotate(360deg); } }
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
.tg-esquema > svg { width: 100%; max-width: 860px; height: auto; display: block; margin: 0 auto; align-self: center; }
.tg-alarmas { background: #20232c; border-radius: 10px; padding: 10px; display: flex; flex-direction: column; gap: 6px; }
.tg-alarmas .tg-tit { font-weight: 700; font-size: 15px; display: flex; align-items: center; gap: 8px; }
.tg-alarma { background: #E8505B; color: #fff; border-radius: 4px; padding: 5px 8px; font-weight: 600; }
.tg-alarma.tg-inactiva { background: #7a6205; }
.tg-alarma small { display: block; font-weight: 400; opacity: .9; }
.tg-sin-alarmas { color: #9fe0bb; font-size: 13px; }
.tg-fila2 { display: grid; grid-template-columns: minmax(0, 2.4fr) minmax(170px, .8fr) minmax(190px, .9fr); gap: 8px; }
.tg-fila3 { display: grid; grid-template-columns: minmax(0, .95fr) minmax(0, 1fr) minmax(0, 1.15fr) minmax(190px, .9fr); gap: 8px; }
/* Pantalla ancha: dos filas (esquema, potencia y contadores; barras, estado, motor, entradas y salidas) para que entre entero. */
@container (min-width: 1500px) {
  .tg-tablero { grid-template-columns: minmax(0, 1fr) 190px 270px 220px 220px;
    grid-template-areas: "top top top top top" "esq esq kw cnt cnt" "bar est mot ent sal"; }
  .tg-fila2, .tg-fila3 { display: contents; }
  .tg-tablero .tg-grupo .tg-gtit { min-height: 40px; }
  .tg-a-top { grid-area: top; } .tg-a-esq { grid-area: esq; } .tg-a-kw { grid-area: kw; } .tg-a-cnt { grid-area: cnt; }
  .tg-a-bar { grid-area: bar; } .tg-a-est { grid-area: est; } .tg-a-mot { grid-area: mot; } .tg-a-ent { grid-area: ent; } .tg-a-sal { grid-area: sal; }
}
@container (max-width: 1000px) { .tg-fila2, .tg-fila3, .tg-esquema { grid-template-columns: minmax(0, 1fr); } }
.tg-barras { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 6px; }
@container (max-width: 640px) { .tg-barras { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
.tg-grupo .tg-gtit { font-size: 12px; font-weight: 600; text-align: center; min-height: 56px; display: flex; flex-direction: column; align-items: center; justify-content: flex-end; gap: 2px; }
.tg-grupo .tg-hz { font-family: Consolas, "Courier New", monospace; }
.tg-grupo svg { width: 100%; max-width: 170px; height: auto; display: block; margin: 0 auto; }
.tg-sep { border-left: 1px solid #E4E6EC; }
.tg-estados { background: #3A3F4C; color: #fff; border-radius: 10px; padding: 10px 12px; display: grid; gap: 10px; }
.tg-estados .tg-et { font-size: 12px; color: #c9ccd6; }
.tg-estados .tg-ev { font-size: 18px; font-weight: 700; text-align: center; }
.tg-estados .tg-eg { font-size: 12px; text-align: center; color: #e2e4ea; }
.tg-estados hr { border: 0; border-top: 1px solid #5a6070; margin: 0; }
table.tg-io { width: 100%; border-collapse: collapse; font-size: 13px; }
table.tg-io td { padding: 2px 6px; border-bottom: 1px solid #eef0f4; }
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
.tg-kw svg { width: 100%; max-width: 260px; height: auto; margin: 0 auto; display: block; }
.tg-cajas { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px 10px; }
.tg-caja { display: flex; align-items: center; gap: 6px; font-size: 12px; justify-content: flex-end; }
.tg-caja b { font-family: Consolas, "Courier New", monospace; font-size: 14px; border: 1px solid #E4E6EC; border-radius: 4px; padding: 2px 8px; min-width: 60px; text-align: right; }
.tg-caja b.tg-ok { background: #3BB273; color: #fff; border-color: #3BB273; }
.tg-caja b.tg-aviso { background: #E0B50F; color: #fff; border-color: #E0B50F; }
.tg-caja b.tg-fuera { background: #E8505B; color: #fff; border-color: #E8505B; }
.tg-contadores { display: grid; grid-template-columns: repeat(auto-fill, minmax(205px, 1fr)); gap: 0 12px; }
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
    const TIMER_ES = { "No Timer": "sin cuenta regresiva", Prestart: "prearranque", Cranking: "arrancando", "Idle Run": "marcha en vacio",
      Cooling: "enfriamiento", "Stop Valve": "valvula de parada", AfterCool: "postenfriamiento", Pause: "pausa entre intentos",
      VentilGas: "ventilacion", MinStabTO: "estabilizacion minima", MaxStabTO: "estabilizacion maxima", "MCB Close": "cierre del MCB",
      TransferDel: "demora de transferencia", ReturnDelay: "espera al volver la red", "Start Delay": "demora de arranque por falla de red",
      "V Detect": "deteccion de tension", "GCB Delay": "demora del GCB", "GCB Open": "apertura del GCB", SyncTOut: "tiempo de sincronizacion",
      "GCB Close": "cierre del GCB", "MCB Open": "apertura del MCB", WaitToStp: "espera para parar", "Syst Start": "arranque del sistema",
      "Syst Stop": "parada del sistema", "Next Start": "proximo arranque", "Next Stop": "proxima parada", Warming: "calentamiento" };
    const VERDE = "#3BB273", APAG = "#d8dbe2", ROJO = "#E8505B", AMARILLO = "#E0B50F";
    // Segundos como m:ss (h:mm:ss desde una hora), igual que la pantalla del controlador.
    const mmss = s => { s = Math.max(0, Math.round(s)); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = String(s % 60).padStart(2, "0");
      return h ? `${h}:${String(m).padStart(2, "0")}:${x}` : `${m}:${x}`; };
    // Estados del motor que son falla (parado por falla o no puede arrancar): la G del esquema en rojo.
    const FALLA_MOTOR = ["Shutdown", "NotReady", "SDVentil"];
    // Lo minimo que se achica el tablero para entrar en la pantalla (ver ajustar).
    const ZOOM_MIN = 0.75;
    const ICONO_ACTUALIZAR = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d="M20 12a8 8 0 1 1-2.34-5.66"/><path d="M20 4v5h-5"/></svg>`;
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
      const barra = `<div class="tg-card tg-barra tg-a-top">
        <button type="button" class="tg-nombre" title="Actualizar (leer de nuevo los controladores)">${ICONO_ACTUALIZAR}${esc(g.nombre)}</button><span class="tg-sub">${esc(g.modelo)} · ${esc(g.trafo)}</span>
        ${g.sinComunicacion != null
          ? `<span class="tg-badge tg-fuera">SIN COMUNICACION</span><span class="tg-leido">${esc(g.sinComunicacion)}</span>`
          : `<span class="tg-badge ${g.marcha ? "tg-ok" : "tg-gris"}">${g.marcha ? "EN MARCHA" : "PARADO"}</span>
             ${g.listoAmf == null ? "" : g.listoAmf
               ? `<span class="tg-badge tg-ok" title="Si se corta la red, el generador entra solo">LISTO PARA AMF</span>`
               : `<span class="tg-badge tg-fuera" title="Si se corta la red, el generador no entraria solo">NO LISTO PARA AMF</span>`}
             <span class="tg-leido">Leido ${esc(g.leido)}${g.reloj ? ` · reloj del controlador ${esc(g.reloj)} <span class="tg-badge ${g.desfaseAlto ? "tg-aviso" : "tg-ok"}">${esc(g.desfase)}</span>` : ""}</span>`}
        ${g.modo != null ? `<span class="tg-modos">${modos}</span>` : ""}</div>`;
      if (g.sinComunicacion != null) return `<section class="tg-tablero">${barra}</section>`;
      return `<section class="tg-tablero">${barra}
        <div class="tg-esquema tg-a-esq">${esquema(g)}${alarmas(g)}</div>
        <div class="tg-fila2">
          <div class="tg-card tg-a-bar"><h3>Tensiones, corrientes y frecuencia</h3>${barras(g)}</div>
          <div class="tg-card tg-a-est"><h3>Estado</h3>${estados(g)}</div>
          <div class="tg-card tg-a-ent"><h3>Entradas binarias</h3>${io(g.entradas, g, true)}</div>
        </div>
        <div class="tg-fila3">
          <div class="tg-card tg-a-mot"><h3>Motor</h3>${motor(g)}</div>
          <div class="tg-card tg-a-kw"><h3>Potencia del generador</h3>${indicador(g)}</div>
          <div class="tg-card tg-a-cnt"><h3>Contadores</h3>${contadores(g)}</div>
          <div class="tg-card tg-a-sal"><h3>Salidas binarias</h3>${io(g.salidas, g, false)}</div>
        </div></section>`;
    }

    /* Red (medidor del transformador) - interruptor de la planta - planta - interruptor del generador - generador */
    function esquema(g) {
      const v = g.v, red = g.redKw, gen = v.KW || 0;
      const hayRed = (v.RED_V_L1L2 || 0) > 100, genTension = (v.V_L1L2 || 0) > 100;
      const mcb = g.mcb === true, gcb = g.gcb === true;
      const planta = red == null ? null : red + gen;
      const vivoPlanta = (mcb && hayRed) || (gcb && genTension);
      // Colores de la torre, la planta y el generador: verde ok / funcionando, rojo falla, gris sin dato o parado listo.
      const colorTorre = v.RED_V_L1L2 == null ? APAG : g.fallaRed || !hayRed ? ROJO : VERDE;
      const colorPlanta = vivoPlanta ? VERDE : g.mcb == null && g.gcb == null ? APAG : ROJO;
      const colorGen = FALLA_MOTOR.includes(g.estadoMotor) ? ROJO : genTension || g.marcha ? VERDE : APAG;
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
      return `<svg viewBox="0 0 780 214" role="img" aria-label="Esquema de red, planta y generador">
        ${kw(14, red == null ? null : Math.abs(red), "#7fb6f7", exporta ? "exportando a la red" : "de la red · " + g.trafo, "start")}
        ${torre(60, colorTorre)}
        ${linea(96, 218, hayRed)}${interruptor(218, g.mcb, "MCB")}${linea(262, 342, vivoPlanta)}
        ${kw(385, planta, "#ffffff", "planta (" + g.trafo.toLowerCase() + ")")}
        ${fabrica(385, colorPlanta)}
        ${linea(428, 520, vivoPlanta)}${interruptor(520, g.gcb, "GCB")}${linea(564, 640, genTension && gcb)}
        ${kw(690, gen, "#F29F67", "generador")}
        ${motorG(690, colorGen)}
        ${relojG(g, 690)}
      </svg>`;
    }

    function alarmas(g) {
      const lista = g.alarmas || [], color = lista.length ? ROJO : "#9fe0bb";
      const fila = a => `<div class="tg-alarma${a.activa ? "" : " tg-inactiva"}">${a.sinReset ? "*" : ""}${esc(a.nombre)}
        <small>${a.activa ? "activa" : "ya no activa"}${a.sinReset ? ", sin reset" : ""}${a.desde ? " · " + esc(a.desde) : ""}</small></div>`;
      return `<div class="tg-alarmas"><div class="tg-tit"><svg width="22" height="20" viewBox="0 0 22 20"><path d="M11 1 L21 19 H1 Z" fill="none" stroke="${color}" stroke-width="2"/>
        <rect x="10" y="7" width="2" height="6" fill="${color}"/><rect x="10" y="15" width="2" height="2" fill="${color}"/></svg>Lista de alarmas</div>
        ${lista.length ? lista.map(fila).join("") : `<div class="tg-sin-alarmas">Sin alarmas en el controlador</div>`}</div>`;
    }

    /* Grupo de 3 barras con escala, lineas de limite (fuera de rango de Calidad de energia, o la nominal) y valores debajo;
       arriba, opcional, la frecuencia o el PF con su color */
    function grupo(titulo, marca, marcaEstado, vals, etiquetas, max, limites, color) {
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
      const marcaTxt = marca === undefined ? "" : `<span class="tg-hz tg-badge tg-${marcaEstado || "gris"}">${esc(marca)}</span>`;
      return `<div class="tg-grupo"><div class="tg-gtit">${marcaTxt}<span>${titulo}</span></div>${s}</div>`;
    }

    function barras(g) {
      const v = g.v, n = g.nom, f = 1 + g.fueraPct / 100, f2 = 1 - g.fueraPct / 100, RED = "#3B8FF3", GEN = "#F29F67";
      const escala = nominal => Math.ceil(nominal * 1.35 / 100) * 100;
      const imax = n.i ? Math.ceil(n.i * 1.25 / 500) * 500 : 2000;
      // Corrientes del transformador (su medidor): misma escala que el generador, o mayor si la pasan.
      const it = g.trafoI || [], imaxTrafo = Math.max(imax, Math.ceil(Math.max(0, ...it.map(x => x || 0)) * 1.15 / 500) * 500);
      const hz = val => fmt(val, 1) + " Hz";
      return `<div class="tg-barras">
        ${grupo("Red L-N [V]", hz(v.RED_FRECUENCIA), g.hzRed, [v.RED_V_L1N, v.RED_V_L2N, v.RED_V_L3N], ["L1-N", "L2-N", "L3-N"], escala(n.redLN), [n.redLN * f, n.redLN * f2], RED)}
        ${grupo("Red L-L [V]", undefined, null, [v.RED_V_L1L2, v.RED_V_L2L3, v.RED_V_L3L1], ["L1-L2", "L2-L3", "L3-L1"], escala(n.redLL), [n.redLL * f, n.redLL * f2], RED)}
        ${grupo("Transformador [A]", "PF " + (g.trafoPf == null ? "-" : fmt(g.trafoPf, 2)), g.trafoPfEstado, it, ["A", "B", "C"], imaxTrafo, [], RED)}
        <div class="tg-sep">${grupo("Generador [A]", undefined, null, [v.I_L1, v.I_L2, v.I_L3], ["L1", "L2", "L3"], imax, [n.i || 0], GEN)}</div>
        ${grupo("Generador L-N [V]", hz(v.FRECUENCIA), g.hzGen, [v.V_L1N, v.V_L2N, v.V_L3N], ["L1-N", "L2-N", "L3-N"], escala(n.genLN), [n.genLN * f, n.genLN * f2], GEN)}
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

    /* Cuenta regresiva del controlador debajo de la G: tiempo que falta (amarillo mientras corre), nombre ComAp y, en
       chico, que es y el total ajustado. Sin cuenta: "0:00 No Timer" en gris, como la pantalla del controlador. Entre
       lecturas (cada 15 s) el navegador la sigue bajando de a un segundo (ver contar). */
    function relojG(g, x) {
      if (g.temporizador == null) return "";
      const t = g.temporizador, seg = g.v.TEMPORIZADOR_VALOR, corre = t !== "No Timer";
      const txt = !corre ? "0:00" : seg == null ? "-:--" : mmss(seg);
      const glosa = (TIMER_ES[t] || "") + (corre && g.temporizadorTotal ? " · de " + mmss(g.temporizadorTotal) : "");
      return `<text x="${x}" y="173" text-anchor="middle" fill="${corre ? AMARILLO : "#8a8f9c"}" font-size="24" font-weight="700"
          font-family="Consolas, Courier New, monospace"${corre && seg > 0 ? ` data-seg="${seg}"` : ""}>${txt}</text>
        <text x="${x}" y="191" text-anchor="middle" fill="${corre ? "#ffffff" : "#8a8f9c"}" font-size="13" font-weight="600">${esc(t)}</text>
        ${corre ? `<text x="${x}" y="207" text-anchor="middle" fill="#c9ccd6" font-size="12">${esc(glosa)}</text>` : ""}`;
    }

    function contar(el) {
      clearInterval(el.tgReloj);
      const relojes = Array.from(el.querySelectorAll("[data-seg]"));
      if (!relojes.length) return;
      const inicio = Date.now();
      el.tgReloj = setInterval(() => {
        const pasados = (Date.now() - inicio) / 1000;
        relojes.forEach(r => r.textContent = mmss(Number(r.dataset.seg) - pasados));
      }, 1000);
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
      let s = `<svg viewBox="0 0 260 198">${arco(0, nom, VERDE)}${arco(nom, nom * 1.1, AMARILLO)}${arco(nom * 1.1, max, ROJO)}`;
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
      const filas = [["Horas de marcha", v.HORAS_MARCHA, 1, "h"], ["Contador de mantenimiento 1", v.MANTENIMIENTO, 0, "h"], ["Arranques", v.ARRANQUES, 0, ""],
        ["Energia generada", v.KWH, 0, "kWh"], ["Energia reactiva", v.KVARH, 0, "kVArh"],
        ["Paradas de emergencia", v.PARADAS_EMERGENCIA, 0, ""], ["Paradas por falla", v.PARADAS_FALLA, 0, ""]]
        .map(([t, val, d, u]) => `<div class="tg-contador"><div class="tg-ct">${t}</div>${digitos(val, d, u)}</div>`).join("");
      return `<div class="tg-contadores">${filas}</div>`;
    }

    return {
      render(el, lista) {
        css();
        // Clic en el nombre de un generador = Actualizar: avisa a la vista (evento tg-actualizar) y el icono gira hasta
        // que llega la lectura nueva (el render siguiente lo reemplaza).
        if (!el.tgActualizar) {
          el.tgActualizar = true;
          el.addEventListener("click", ev => {
            if (!ev.target.closest(".tg-nombre")) return;
            el.querySelectorAll(".tg-nombre").forEach(b => b.classList.add("tg-girando"));
            el.dispatchEvent(new CustomEvent("tg-actualizar"));
          });
          // Al cambiar el tamano de la ventana, la escala de Windows o el zoom del navegador, se vuelve a ajustar.
          let espera;
          window.addEventListener("resize", () => { clearTimeout(espera); espera = setTimeout(() => ajustar(el), 150); });
        }
        el.innerHTML = `<div class="tg-escala"><div class="tg">${lista.map(tablero).join("")}</div></div>`;
        contar(el);
        ajustar(el);
      }
    };

    /*
     * Achica el tablero lo justo para que un generador entre entero en lo que se ve (por ejemplo con Windows al 125 %),
     * de a 2 % y hasta ZOOM_MIN; por debajo de eso queda la barra de desplazamiento. Con zoom el tablero tiene mas
     * ancho util, asi que tambien puede pasar al arreglo en dos filas.
     */
    function ajustar(el) {
      const caja = el.querySelector(".tg-escala");
      if (!caja || !el.isConnected) return;
      caja.style.zoom = "";
      // Desde donde empieza el tablero (sin lo desplazado) hasta el borde de abajo del area visible: la ventana o el
      // panel con barra de desplazamiento que lo contiene (la pestana), sin su relleno de abajo.
      let desplazado = 0, abajo = window.innerHeight;
      for (let n = el; n; n = n.assignedSlot || n.parentNode || n.host) {
        if (!(n instanceof Element)) continue;
        // scrollTop, clientHeight y el relleno van en la medida propia del elemento; con la pagina achicada (escala de
        // Windows) se pasan a la medida de la pantalla con su zoom.
        const zn = n.currentCSSZoom || 1;
        desplazado += (n.scrollTop || 0) * zn;
        const st = getComputedStyle(n);
        if (n !== el && n !== document.documentElement && n !== document.body && /auto|scroll/.test(st.overflowY)) {
          abajo = Math.min(abajo, n.getBoundingClientRect().top + (n.clientTop + n.clientHeight - parseFloat(st.paddingBottom || 0)) * zn);
        }
      }
      const disponible = abajo - (el.getBoundingClientRect().top + desplazado) - 6;
      if (disponible <= 0) return;
      const alto = () => Math.max(0, ...Array.from(caja.querySelectorAll(".tg-tablero"), s => s.getBoundingClientRect().height));
      for (let z = 1; z >= ZOOM_MIN; z = Math.round((z - 0.02) * 100) / 100) {
        caja.style.zoom = z === 1 ? "" : String(z);
        if (alto() <= disponible) return;
      }
    }
  })();
}
