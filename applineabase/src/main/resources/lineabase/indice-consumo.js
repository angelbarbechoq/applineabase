/*
 * Índice de consumo por tamaño de lote (pestaña «Índice de consumo» de SGEn). Copia del cálculo y del dibujo de la maqueta
 * aprobada por el usuario el 2026-11-13 (reportes/maqueta-indice-de-consumo.html). La vista llama
 * window.IndiceConsumo.render(elemento, datos) con datos = {ev:{m,b,pb,bcon,valida,r2,fuera}, base:[[kg,kWh,producto,inicio]],
 * comp:[...], anioBase, anioComp}. Prueba t del intercepto (Newbold ec. 11.20), IC = m + b/kg y CUSUM de −b/kg²
 * (documento P-3-AP-1) y quiebre por el método Kneedle (Satopää et al. 2011) sobre ese CUSUM.
 */
if (!window.IndiceConsumo) {
  window.IndiceConsumo = (function () {
    const CSS = '.ic h2{font-size:16px;margin:0 0 8px}.ic .card{background:#fff;border:1px solid var(--bd);border-radius:12px;padding:14px;margin-bottom:12px}.ic .kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:10px}.ic .kpi{border:1px solid var(--bd);border-radius:10px;padding:8px 10px}.ic .kpi .v{font-size:19px;font-weight:600}.ic .kpi .l{font-size:12px;color:var(--mu)}.ic .grid2{display:grid;grid-template-columns:minmax(0,1.4fr) minmax(0,1fr);gap:12px}.ic .aviso{background:#FBF3D5;color:#7a6205;border-radius:8px;padding:8px 10px;margin-top:8px}.ic .nota{font-size:12px;color:var(--mu)}.ic .mitad{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:12px;align-items:start}@media(max-width:1000px){.ic .grid2,.ic .mitad{grid-template-columns:1fr}}.ic{--tx:#1E1E2C;--mu:#6b6f80;--bd:#E4E6EC;color:var(--tx);font:14px/1.45 "Segoe UI",Roboto,Arial,sans-serif}.ic h2{font-size:16px;margin:0 0 8px}';
    const CUERPO = '<div id="ic-aviso"></div><div class="card"><div class="kpis" id="ic-kpis"></div></div>\n<div class="grid2">\n<div class="card"><h2>Índice de consumo contra kg del lote</h2><div id="ic-g1" class="aj" style="width:100%;height:420px"></div>\n<div class="nota"><span style="color:#3BB273">━</span> IC teórico = m + b / kg &nbsp; <span style="color:#9aa0ae">┅</span> asíntota m &nbsp; <span style="color:#E8505B">┃</span> tamaño crítico &nbsp; <span style="background:#FCE4E6;padding:0 8px"></span> zona de altos IC (lotes bajo el tamaño crítico) &nbsp; <span style="color:#3B8FF3">●</span> lotes <span class="ic-a0"></span> &nbsp; <span style="color:#F29F67">●</span> lotes <span class="ic-a1"></span></div></div>\n<div class="card"><h2>CUSUM de la pendiente (documento P-3-AP-1) con el quiebre</h2><div id="ic-g3" class="aj" style="width:100%;height:420px"></div>\n<div class="nota" id="ic-g3nota"></div></div>\n</div>\n<div class="mitad">\n<div class="card"><h2>Método Kneedle: cómo se encuentra el quiebre</h2><div id="ic-g2" class="aj" style="width:100%;height:420px"></div>\n<div class="nota"><span style="color:#3BB273">━</span> CUSUM de los lotes <span class="ic-a0"></span> escalado a 0-1 (invertido) &nbsp; <span style="color:#9aa0ae">┅</span> diagonal &nbsp; <span style="color:#1E1E2C">━</span> diferencia curva − diagonal; su máximo es el quiebre (máxima curvatura).</div></div>\n<div class="card nota" id="ic-como"></div>\n</div>\n';
    function render(el, D) {
      if (!document.getElementById("ic-estilos")) {
        const st = document.createElement("style"); st.id = "ic-estilos"; st.textContent = CSS; document.head.appendChild(st);
      }
      const G = window.SgenGraf;
      G.limpiar(el);
      el.classList.add("ic");
      el.innerHTML = CUERPO;
      el.querySelectorAll(".ic-a0").forEach(x => x.textContent = D.anioBase);
      el.querySelectorAll(".ic-a1").forEach(x => x.textContent = D.anioComp);
      const A0 = D.anioBase, A1 = D.anioComp;
      const q = id => el.querySelector("#ic-" + id);
      const f=(x,d=0)=>x==null||!isFinite(x)?'—':x.toLocaleString('es-EC',{minimumFractionDigits:d,maximumFractionDigits:d});
function dibujar(){const src=D,e=src.ev;
 if(!e){q('kpis').innerHTML='';q('aviso').innerHTML='<div class="aviso">Sin lotes suficientes en el año base.</div>';q('g1').innerHTML=q('g2').innerHTML=q('g3').innerHTML='';return}
 const base=src.base.filter((p,i)=>!(e.fuera||[]).includes(i)),comp=src.comp,m=e.m,b=e.b;
 let av='';if(!e.valida)av='Línea base no válida (R² '+f(e.r2,3)+'): el cálculo es solo ilustrativo.';
 if(b<=0)av+=(av?' ':'')+'Carga base no significativa (prueba t del intercepto, p = '+(e.pb==null?'—':f(e.pb,3))+' ≥ 0,05): modelo proporcional kWh = m × kg. El kWh/kg es indicador válido directo (NCh-ISO 50006 4.5.1) y no hay tamaño crítico: el IC no depende del tamaño del lote.';
 q('aviso').innerHTML=av?'<div class="aviso">'+av+'</div>':'';
 // Pasos 1-7 del documento P-3-AP-1: lotes '+A0+' ordenados por kg y CUSUM de la pendiente −b/kg².
 // Paso 8 (identificar el quiebre en el gráfico): método Kneedle (Satopää et al. 2011) sobre ese CUSUM. Es decreciente y
 // convexo: se invierte (ymax − y), se escalan los dos ejes a 0-1 y el quiebre es el máximo de la diferencia con la diagonal.
 const ordK=[...base].sort((p,q)=>p[0]-q[0]),xsK=ordK.map(p=>p[0]);let accK=0;const csK=ordK.map(p=>accK+=-b/(p[0]*p[0]));let kn=null;
 if(b>0&&xsK.length>=3){const k0=xsK[0],k1=xsK[xsK.length-1],ymax=Math.max(...csK),yi=csK.map(y=>ymax-y),ymn=Math.min(...yi),ymx=Math.max(...yi);
  const xn=xsK.map(x=>(x-k0)/(k1-k0)),yn=yi.map(y=>(y-ymn)/(ymx-ymn)),dif=yn.map((y,i)=>y-xn[i]);
  let im=0;dif.forEach((d,i)=>{if(d>dif[im])im=i});
  kn={xn,yn,dif,im,P:xsK[im],pend:100*Math.pow(xsK[0]/xsK[im],2),recorrido:100*csK[im]/csK[csK.length-1],kmin:xsK[0]}}
 const Pc=kn?kn.P:null,ICc=Pc?m+b/Pc:null;
 const icProm=base.reduce((a,p)=>a+p[1],0)/base.reduce((a,p)=>a+p[0],0);
 // 2026: lotes bajo el tamaño crítico y kWh de más contra el IC del quiebre (adaptación del ahorro potencial del P-3-AP-1)
 let bajo=0,demas=0;comp.forEach(p=>{if(Pc&&p[0]<Pc){bajo++;demas+=p[0]*((m+b/p[0])-ICc)}});
 const pbTxt=e.pb==null?'—':(e.pb<1e-4?'< 0,0001':f(e.pb,4));
 // Solo los 5 resultados (pedido del usuario 2026-11-13): m y b están en la pestaña Línea base y en la leyenda; la
 // pendiente y el CUSUM recorrido junto al quiebre del CUSUM; el p del intercepto en la nota del CUSUM.
 const k=[['Tamaño crítico (Kneedle sobre el CUSUM)',Pc?f(Pc,0)+' kg':'—'],
  ['IC en el quiebre',ICc?f(ICc,4)+' kWh/kg':'—'],['IC medio '+A0+'',f(icProm,4)+' kWh/kg'],
  ['Lotes '+A1+' bajo el tamaño crítico',Pc?bajo+' de '+comp.length:'—'],['kWh de más por lotes pequeños ('+A1+')',Pc?f(demas,0)+' kWh':'—']];
 q('kpis').innerHTML=k.map(x=>'<div class="kpi"><div class="v">'+x[1]+'</div><div class="l">'+x[0]+'</div></div>').join('');
 g1(base,comp,m,b,Pc);g2(kn);g3(base,b,e.bcon,e.pb,Pc,kn);
 // Mitad y mitad: la explicación del método toma el mismo alto que la tarjeta de Kneedle (auto ajustada) y, si no entra,
 // se desplaza dentro de su tarjeta. Una debajo de la otra (pantalla angosta) queda con su alto natural.
 el.__despuesDeAjustar=()=>{const k=q('g2').closest('.card'),c=q('como');if(!k||!c)return;
  const lado=q('g2').classList.contains('aj')&&getComputedStyle(k.parentElement).gridTemplateColumns.split(' ').length>1;
  c.style.boxSizing='border-box';c.style.overflowY=lado?'auto':'';c.style.height=lado?k.getBoundingClientRect().height/(k.currentCSSZoom||1)+'px':''};
 G.ajustar(el);setTimeout(()=>G.ajustar(el),0)}
// Gráficas en amCharts 5 (ayudas en sgen-graficas.js): mismos comandos de zoom que Tiempo Real.
function g1(base,comp,m,b,Pc){const todos=base.concat(comp);const xm=Math.max(...todos.map(p=>p[0]))*1.05;const ics=todos.map(p=>p[1]/p[0]).sort((a,c)=>a-c);
 // Alto del eje = IC del lote más alto, para que se vean todos los lotes; la curva teórica se corta ahí.
 const ym=ics[ics.length-1]*1.08;
 const g=G.crear(el,q('g1'),{x:'kg del lote',y:'IC (kWh/kg)',fx:u=>f(u,0),fy:v=>f(v,2),xOpc:{min:0,max:xm,strictMinMax:true},yOpc:{min:0,max:ym,strictMinMax:true}});
 if(Pc)G.zona(g,0,Pc,0xFCE4E6,.45);
 G.horizontal(g,m,0x9aa0ae,'asíntota m = '+f(m,4),[6,5]);
 const cur=[];for(let i=1;i<=200;i++){const kg=xm*i/200;cur.push({x:kg,y:m+b/kg})}G.linea(g,cur,{color:0x3BB273,ancho:2});
 if(Pc)G.vertical(g,Pc,0xE8505B,f(Pc,0)+' kg');
 const pt=p=>({x:p[0],y:p[1]/p[0],t:p[3]+' · '+p[2]+'\n'+f(p[0],0)+' kg · '+f(p[1],0)+' kWh · '+f(p[1]/p[0],4)+' kWh/kg'});
 G.linea(g,base.map(pt),{soloPuntos:true,radio:4.5,color:0x3B8FF3});if(comp.length)G.linea(g,comp.map(pt),{soloPuntos:true,radio:4.5,color:0xF29F67})}
function g2(kn){const div=q('g2');
 if(!kn){div.classList.remove('aj');div.style.height='auto';div.innerHTML='<div style="padding:20px;font-size:13px;color:#6b6f80">Sin carga base significativa: no hay quiebre.</div>';return}
 const ymin=Math.min(0,...kn.dif);
 const g=G.crear(el,div,{x:'kg del lote escalado (0 = lote más pequeño '+A0+', 1 = el más grande)',y:'escalado 0-1',fx:u=>f(u,1),fy:v=>f(v,1),xOpc:{min:0,max:1,strictMinMax:true},yOpc:{min:ymin,max:1.05,strictMinMax:true}});
 G.linea(g,[{x:0,y:0},{x:1,y:1}],{color:0x9aa0ae,ancho:1.2,guiones:[6,5]});
 G.linea(g,kn.xn.map((x,i)=>({x:x,y:kn.yn[i]})),{color:0x3BB273,ancho:2});
 G.linea(g,kn.xn.map((x,i)=>({x:x,y:kn.dif[i]})),{color:0x1E1E2C,ancho:1.5});
 // Separación máxima: tramo vertical entre la diagonal y la curva en el quiebre (igual al pico de la diferencia).
 const xi=kn.xn[kn.im],yc=kn.yn[kn.im];
 // El texto va a la mitad del tramo: arriba se cortaba con el borde del gráfico.
 G.linea(g,[{x:xi,y:xi},{x:xi,y:(xi+yc)/2,lbl:'separación máxima = '+f(kn.dif[kn.im],3)+' (en '+f(kn.P,0)+' kg)'},{x:xi,y:yc}],{color:0xE8505B,ancho:4,opacidad:.55});
 G.vertical(g,xi,0xE8505B,'quiebre: '+f(kn.P,0)+' kg');
 G.linea(g,[{x:xi,y:kn.dif[kn.im],t:'Quiebre: '+f(kn.P,0)+' kg\ndiferencia curva − diagonal = '+f(kn.dif[kn.im],3)}],{soloPuntos:true,radio:6,relleno:false,color:0xE8505B,borde:0xE8505B,anchoBorde:2})}
// CUSUM de la pendiente dIC/dkg = −b/kg² con los lotes del año base ordenados por kg (pasos 4 a 8 del documento P-3-AP-1).
// Con carga base: se marca el quiebre de Kneedle y la pendiente ahí en % de la del lote más pequeño. Sin carga base
// significativa: b = 0, la pendiente es cero en todo el rango y el CUSUM es plano; en gris, el que daría el b estimado
// (no significativo), solo como referencia.
function g3(base,b,bcon,pb,Pc,kn){
 const ord=[...base].sort((p,q)=>p[0]-q[0]),xs=ord.map(p=>p[0]);let a1=0,a2=0;
 const cs=ord.map(p=>a1+=-b/(p[0]*p[0])),cg=ord.map(p=>a2+=-(bcon||0)/(p[0]*p[0]));
 const todos=cs.concat(b>0?[]:cg),ymin=Math.min(...todos,-1e-12)*1.05,ym=Math.max(0,...todos)*1.05||1e-12;
 const g=G.crear(el,q('g3'),{x:'kg del lote (lotes '+A0+' ordenados de menor a mayor)',y:'CUSUM de −b/kg²',fx:u=>f(u,0),fy:v=>v===0?'0':v.toExponential(1),
  xOpc:{min:0,max:xs[xs.length-1]*1.03,strictMinMax:true},yOpc:{min:ymin,max:ym,strictMinMax:true}});
 G.horizontal(g,0,0xc9ccd6);
 if(b<=0&&bcon)G.linea(g,xs.map((x,i)=>({x:x,y:cg[i]})),{color:0x9aa0ae,ancho:1.6,guiones:[6,5]});
 G.linea(g,ord.map((p,i)=>({x:p[0],y:cs[i],t:p[3]+' · '+p[2]+'\n'+f(p[0],0)+' kg · CUSUM '+cs[i].toExponential(3)})),{color:0x1E1E2C,ancho:1.6,radio:2.5,borde:0x1E1E2C});
 let nota;
 if(b>0&&Pc){let i=xs.findIndex(x=>x>=Pc);if(i<0)i=xs.length-1;
  const pct=kn.pend;
  G.vertical(g,Pc,0xE8505B,f(Pc,0)+' kg');
  const txQ='quiebre: '+f(Pc,0)+' kg\npendiente '+f(pct,2)+' % de la del lote más pequeño\nCUSUM recorrido '+f(kn.recorrido,1)+' %';
  G.linea(g,[{x:xs[i],y:cs[i],lbl:txQ,t:txQ}],{soloPuntos:true,radio:7,relleno:false,color:0xE8505B,borde:0xE8505B,anchoBorde:2});
  nota='Carga base significativa (prueba t del intercepto, p = '+(pb==null?'—':pb<1e-4?'&lt; 0,0001':f(pb,4))+', menor que 0,05): la energía fija del lote existe y por eso hay quiebre. Cada punto suma la pendiente −b/kg² de un lote de '+A0+'. Antes del quiebre la curva cae fuerte (zona de altos IC); después queda casi horizontal (zona de bajos IC). En el quiebre la pendiente es '+f(pct,2)+' % de la del lote más pequeño ('+f(kn.kmin,0)+' kg), calculada como (kg del lote más pequeño / kg del quiebre)², porque la pendiente −b/kg² tiene la misma b en los dos y se cancela; y el CUSUM ya recorrió el '+f(kn.recorrido,1)+' % de su caída total.';}
 else nota='Carga base no significativa (prueba t del intercepto, p = '+(pb==null?'—':f(pb,3))+'): b = 0, la pendiente −b/kg² es cero en todo el rango y el CUSUM es una recta horizontal en cero: no hay zona de altos IC ni quiebre.'
  +(bcon?' En gris, el CUSUM con el b estimado ('+f(bcon,0)+' kWh), que no es distinto de cero en lo estadístico; se muestra solo como referencia.':'');
 q('g3nota').innerHTML=nota}
G.alCambiarTamano(el);
q('como').innerHTML='<h2 style="color:#1E1E2C">Método</h2>'
 +'<p><b>Qué es.</b> El índice de consumo de un lote (IC, kWh/kg) baja cuando el lote es más grande, porque la energía fija del lote (b: arranque y calentamiento) se reparte entre más kg: IC = m + b / kg, una hipérbola que tiende a m. El tamaño crítico de lote es el punto de quiebre entre la zona de altos IC (lotes pequeños, donde agrandar el lote baja mucho el IC) y la de bajos IC (donde ya casi no cambia). Los documentos lo llaman «línea base de índice de consumo» y «tasa de producción crítica».</p>'
 +'<p><b>Cómo se hace.</b></p><ol>'
 +'<li>Línea base del año anterior con los lotes de '+A0+': kWh = m × kg + b por mínimos cuadrados, con las pruebas de la aplicación (R² ≥ 0,75, prueba F con p &lt; 0,05, m &gt; 0; atípicos a más de 2 sigma solo si R² &lt; 0,75).</li>'
 +'<li>Prueba t del intercepto, hipótesis nula b = 0, α = 0,05. Si no se rechaza, la carga base no se distingue de cero: se quita b, el modelo es kWh = m × kg, el kWh/kg es indicador válido directo y no hay tamaño crítico (el CUSUM queda plano en cero). Si se rechaza, se sigue con el paso 3.</li>'
 +'<li>Pasos 1 a 7 del documento P-3-AP-1: se ordenan los lotes de '+A0+' de menor a mayor kg, se calcula para cada uno la pendiente del IC teórico, dIC/dkg = −b / kg², y se acumula (CUSUM).</li>'
 +'<li>Paso 8 (identificar el quiebre en el gráfico CUSUM): método Kneedle. Como el CUSUM es decreciente y convexo, se invierte (y máximo − y); se escalan los dos ejes a 0-1 (así el resultado no depende de las unidades); se resta la diagonal (y − x) y el quiebre es el lote donde esa diferencia es máxima, que es el punto de máxima curvatura (con todos los lotes del año disponibles, uso fuera de línea, en el que no hace falta el umbral de sensibilidad S; el artículo obtiene ahí el mejor resultado con S = 0). No se suaviza la curva (paso 1 del artículo) porque el CUSUM se calcula con la pendiente del modelo y ya es una curva sin ruido.</li>'
 +'<li>Comprobación: pendiente en el quiebre como % de la del lote más pequeño = (kg del lote más pequeño / kg del quiebre)², y % del CUSUM ya recorrido en el quiebre.</li>'
 +'<li>Lotes de '+A1+': los que quedan bajo el tamaño crítico y sus kWh de más = Σ kg × (IC teórico del lote − IC en el quiebre), adaptación a lotes del «ahorro potencial = P × (IC prom − IC crítico)» del documento P-3-AP-1.</li></ol>'
 +'<p><b>Por qué este método.</b> El documento P-3-AP-1 define el quiebre sobre el gráfico CUSUM, pero lo identifica a la vista (paso 8), y eso depende de quién mira. Kneedle es un algoritmo publicado que define el quiebre como el punto de máxima curvatura y lo encuentra siempre igual: aplicado al mismo gráfico CUSUM del documento, cualquiera que repita el cálculo llega al mismo resultado. La prueba t del intercepto decide con un criterio estadístico, no a juicio, en qué líneas existe la carga base (NCh-ISO 50006: con carga base pequeña sirve un cociente simple; con carga base grande, un modelo).</p>'
 +'<p><b>Límite que se declara.</b> El quiebre depende de los tamaños de lote que hubo en el año base (escala de 0 a 1 entre el lote más pequeño y el más grande, y cuántos lotes hubo de cada tamaño). Se recalcula cada año con la nueva línea base.</p>'
 +'<p><b>Referencias.</b></p><ul>'
 +'<li>Documento «Línea base e indicadores de desempeño» (linea-base-indicadores-de-desempeno-P-3-AP-1.pdf, carpeta ISO50001\\Recursos): línea base de índice de consumo IC = m + Eo/P, pendiente dIC/dP = −Eo/P², determinación de la tasa de producción crítica (pasos 1 a 9) y ahorro potencial.</li>'
 +'<li>NCh-ISO 50006:2015, 4.3.1 (entender la carga base y las cargas variables al elegir un indicador) y 4.5.1 (cociente simple con carga base pequeña; modelo con carga base grande).</li>'
 +'<li>Newbold, P., Carlson, W. y Thorne, B., <i>Statistics for Business and Economics</i>, 8.ª ed., ec. 11.20 (prueba t de un coeficiente de la regresión) y 11.21 (intervalo de confianza).</li>'
 +'<li>Satopää, V., Albrecht, J., Irwin, D. y Raghavan, B. (2011), «Finding a “Kneedle” in a Haystack: Detecting Knee Points in System Behavior», 31st International Conference on Distributed Computing Systems Workshops, pp. 166-171.</li></ul>';

      dibujar();
    }
    return { render };
  })();
}
