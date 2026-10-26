// Compensa la escala de Windows (125 %, 150 %...) en pantallas grandes para que la app se vea como al 100 %: achica toda
// la página con zoom CSS. El zoom que pone el usuario en el navegador (Ctrl + / Ctrl -) se respeta. En pantallas chicas
// (menos de ANCHO_MIN píxeles reales de ancho) no se toca: ahí la escala de Windows hace falta para poder leer.
// Lo carga Application.configurePage en todas las páginas, también en el login.
(function () {
  var ANCHO_MIN = 1900;
  var NIVELES = [0.25, 0.33, 0.5, 0.67, 0.75, 0.8, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3, 4, 5];

  // Zoom del navegador: en Chromium outerWidth va en píxeles de Windows e innerWidth en píxeles de la página; se
  // redondea al nivel de zoom más cercano para no confundir los bordes de la ventana con zoom.
  function zoomNavegador() {
    if (!window.outerWidth || !window.innerWidth) return 1;
    var r = window.outerWidth / window.innerWidth, nivel = 1;
    for (var i = 0; i < NIVELES.length; i++) {
      if (Math.abs(NIVELES[i] - r) < Math.abs(nivel - r)) nivel = NIVELES[i];
    }
    return nivel;
  }

  function aplicar() {
    var escala = (window.devicePixelRatio || 1) / zoomNavegador();
    var z = escala > 1.05 && window.screen.width * escala >= ANCHO_MIN ? Math.round(1000 / escala) / 1000 : 1;
    document.documentElement.style.zoom = z === 1 ? "" : String(z);
  }

  aplicar();
  // Cambio de escala de Windows, de zoom del navegador o de monitor.
  window.addEventListener("resize", aplicar);

  // Listas desplegables, calendarios y tooltips de Vaadin (son popover): calculan su posición por pasos, sumando a la
  // posición actual la diferencia con su campo, y con la página achicada esa diferencia sale en otra medida, así que
  // quedarían corridos. Repetir el cálculo unas veces los lleva a su lugar (el error se reduce en cada vuelta).
  var mostrar = HTMLElement.prototype.showPopover;
  if (!mostrar) return;
  HTMLElement.prototype.showPopover = function () {
    var r = mostrar.apply(this, arguments);
    corregirPosicion(this);
    return r;
  };

  function corregirPosicion(ov) {
    if (typeof ov._updatePosition !== "function") return;
    var proto = Object.getPrototypeOf(ov);
    while (proto && !Object.prototype.hasOwnProperty.call(proto, "_updatePosition")) proto = Object.getPrototypeOf(proto);
    if (!proto || Object.prototype.hasOwnProperty.call(proto, "__escalaWindows")) return;
    var original = proto._updatePosition;
    proto._updatePosition = function () {
      var zoom = this.currentCSSZoom || parseFloat(document.documentElement.style.zoom) || 1;
      var vueltas = Math.abs(zoom - 1) > 0.001 ? 6 : 1;
      for (var i = 0; i < vueltas; i++) original.apply(this, arguments);
    };
    proto.__escalaWindows = true;
    ov._updatePosition();
  }
})();
