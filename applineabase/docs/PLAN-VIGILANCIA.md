# Plan: vigilancia progresiva de la aplicacion (tendencias, redes neuronales e IA)

Estado al 2026-10-30: **plan aprobado en lo general, sin implementar.** Faltan las decisiones del punto 7.

## 1. Idea y camino progresivo (decidido el 30-10)

Se avanza por etapas. Cada una se apoya en la anterior y ninguna la reemplaza:

1. **Etapa 1, programa de tendencias (estadistica):** aprende lo normal de cada medidor y variable y avisa cuando algo
   se aparta o viene empeorando dia a dia. Es lo primero que se implementa. Funciona en el servidor, que no tiene
   internet, no necesita entrenamiento y explica por que avisa ("subio 12 % contra sus ultimos 28 dias").
2. **Etapa 2, redes neuronales (con el tiempo):** ven lo que la estadistica no ve (combinaciones raras de varias
   variables, pronostico de demanda, riesgo de falla). Entran en "modo sombra" y solo se activan si aciertan mas que
   la etapa 1.
3. **Etapa 3, IA para decisiones (al final):** modelo de lenguaje que lee el paquete de cada aviso y recomienda que
   hacer. Asesora: la persona decide y se registra que decidio y que resulto.

Lo unico que no se puede dejar para despues es la **base de datos comun (punto 5.1)**: desde el dia 1 se guarda todo lo
que las etapas 2 y 3 van a necesitar, aunque todavia nadie lo use. Lo que no se guarda hoy no se recupera despues.

La IA de lenguaje no vigila directamente: son ~700 mil datos por dia (48 medidores x ~10 variables x 1440 min); seria
lenta, cobra por consulta, necesita internet y se equivoca con los numeros. Lee resumenes que ya calculo la etapa 1.

La app **no maneja ningun equipo**: solo avisa y recomienda. Los controladores de los generadores siguen solo lectura
(unica escritura autorizada: el LOGO! de la sirena). Las tendencias no hacen sonar la sirena (decidido el 29-10).

## 2. Que vigila (todo lo que se guarda)

| Grupo | Datos | Cifra diaria | Ejemplo de aviso |
|---|---|---|---|
| Energia | contador kwh de 48 medidores (base normal) | kWh del dia, kWh por hora de marcha (horometro), consumo con la maquina parada | "Linea05 consume 12 % mas por hora de marcha desde hace 5 dias" |
| Tension | VAB, VAC, VBC (VIP, cada minuto) | media, minima, maxima, desbalance | "Trafo2 baja 0,3 %/dia; sale del limite el 12-11" |
| Corriente | IA, IB, IC | desbalance y corriente media en marcha | "Molino: desbalance 8 % (normal 2 %)" |
| Potencia y PF | PW, PF | potencia media y PF en marcha; PF general | "PF de Chiller4 bajo de 0,79 a 0,70" |
| Calidad | `{mes}Calidad`: GA752, HornoL3, OrientadoraL2 | THD V/I, desbalance, frecuencia | "THD de corriente de HornoL3 sube" |
| Sensores | TemperaturaAgua, TemperaturaAmbiente, PsiAgua, PsiAireP1, BarCompHP (valor en la base normal; no tienen VIP) | media y extremos | "Temperatura del agua sube dia a dia" |
| Mezcladores | `MixerNN_Calentamiento/Enfriamiento` (PV, SV) | desvio PV-SV, tiempo en llegar | "Mixer02 tarda 20 % mas en llegar" |
| Generadores | `{mes}Generador`, arranques, eventos, diesel | reglas propias (abajo) | "GenPower: 4 de 6 arranques fallaron el primer intento" |
| Datos raros | todos | medidor que deja de guardar, contador que retrocede o salta, valor congelado, valores imposibles | "PF 1,04 en MotorL3 y 1,03 en ServicioAux" |

Reglas de generadores: arranque dificil (INTENTO_ARRANQUE, Sd Start Fail, Wrn Stop fail en los ultimos arranques),
bateria (mediana parado por dia), refrigerante y presion de aceite contra lo normal para la misma carga (solo GenPower:
el Caterpillar no los entrega por Modbus), rendimiento kWh/gal por arranque (planilla MF13), estabilidad de red
(maximos de ROCOF y salto de vector como % del ajuste), carga en isla contra la nominal (1000 kW los dos), cortes de
comunicacion, horas sin estar listo para AMF, proximo servicio por horas al ritmo actual.

Ejemplos con datos reales de octubre (piloto `reportes/analisis-generadores-2026-10.html`, no versionado): GenPower 4 de 6
arranques con falla desde el 21-10; ROCOF 155 % del ajuste el 27-10; Caterpillar 897 kW en isla el 21-10; los dos
fijos en 600 kW (carga base) con 25-35 % de importacion en la tarde.

## 3. Como decide la etapa 1

- **Cada noche (propuesto 00:30):** resumen del dia cerrado, una cifra por medidor/variable/dia (~500 por dia), en un
  archivo SQLite aparte (`C:\LineaBaseX\vigilancia\...`), no en la H2. La primera vez arma todo lo que haya en los
  archivos mensuales (como minimo sep y oct) en segundo plano.
- **Cada hora:** la ultima hora contra lo normal de esa misma hora (consumo de noche con la planta parada, tension
  baja ahora), para avisar en el dia de algo brusco.
- **Normal** = ultimos 28 dias del mismo medidor, laborables y fines de semana por separado; la energia por hora de
  marcha.
- **Abre aviso:**
  - Desvio sostenido: 3 dias seguidos fuera de la banda del medidor (mediana +- k x MAD) y ademas un cambio minimo
    por variable (tension 1,5 %, PF 0,03, kWh/h 8 %, desbalance 2 puntos).
  - Tendencia: la pendiente de 14 dias cruza un limite (Calidad de Energia o alarma) en menos de 30 dias.
  - Generadores: tras cada arranque y cada hora.
- **Cierra solo** con 3 dias seguidos dentro de lo normal.
- **Contra la inundacion:** tope de avisos nuevos por noche (propuesto 5, los mas importantes primero), "Falsa alarma"
  ensancha la banda de ese medidor/variable, "Correcto" la mantiene, y se puede silenciar un medidor o una variable
  (Linea31 y Linea32 paradas).
- Lo de cada minuto sigue con las alarmas actuales (detencion, PF bajo, temperatura, generadores).
- Cada aviso lleva una explicacion fija por tipo (causas probables y acciones), escrita una vez con el usuario; sirve
  sin IA y despues es el punto de partida de la etapa 3.

## 4. Como se ve

- Pantalla nueva **"Vigilancia"**: avisos abiertos por importancia, filtros zona/medidor/variable; detalle con
  grafico del valor diario, banda normal y linea de tendencia; que cambio, desde cuando, cuanto, cuando llegaria al
  limite; en la etapa 3 el texto de la IA.
- Botones **Correcto / Falsa alarma** (solo ADMIN), con motivo y causa de listas cerradas (punto 5.1).
- Cada aviso tambien en Alarmas Activas (amarillo, "Tendencia") y en el aviso del navegador. No urgente, sin globo de
  Windows, sin sirena.
- Permiso: flag nuevo **"Ver Vigilancia"** en Usuarios (propuesto); los avisos de generadores piden ademas
  "Ver Generadores".

## 5. Etapas y fases

### 5.1 Base de datos comun (se guarda desde el dia 1)

Cada etapa necesita datos distintos; si se empieza a guardarlos cuando se necesitan, faltan meses de historia.

| Se guarda | Para que etapa | Estado hoy |
|---|---|---|
| Datos de cada minuto (kwh, VIP, Calidad, mezcladores, generadores) | todas | Ya se guarda; nunca se purga |
| Resumen diario por medidor/variable (y por hora en las variables clave) con horas de marcha, dia laborable / fin de semana / feriado, temperatura ambiente y cuantas lecturas faltaron | 1, 2, 3 | Nuevo; se arma con la etapa 1 y no se purga |
| Cada aviso con su "foto": variable, cifras que lo dispararon, ventana, version de la regla | 1, 2, 3 | Nuevo |
| Respuesta del ADMIN a cada aviso: Correcto / Falsa alarma + motivo y causa real (listas cerradas, con "No previsto" que agrega al catalogo) + accion tomada + resultado | 2 (etiquetas para aprender), 3 (ejemplos) | Nuevo |
| Fallas reales con modo de falla de lista cerrada (AMEF), equipo y fecha | 2: sin fallas etiquetadas la red no puede aprender a reconocerlas | **Hay historial en Excel** (carpeta `MNTTO2020\MF25`: `MntoFallas2022`, `MntoFallas2023_x`, `Modos de falla2023`, `MF21_2023P2\MantenimientoFallasMF21_2024`; sin abrir todavia), que se puede importar. Falta el modulo de Mantenimiento Correctivo (MF21 + AMEF, diseno acordado en `docs/PLAN-MANTENIMIENTO-CORRECTIVO.md`, sin implementar) para seguir registrando con lista cerrada |
| Mantenimientos hechos (fecha, TAG, horometro) | 2, 3 | Ya existe (preventivo) |
| Paradas planificadas con motivo (lista cerrada: mantenimiento, sin materia prima, feriado, otra) | 1, 2 (separar "no produce" de "falla") | **Falta** |
| Produccion en kg por linea y producto o receta | 2 (kWh por kg), 3, ISO 50001 | **Hay un Excel de ordenes de produccion** (ver 5.4): kg, producto, kWh inicial y final, kWh/kg, kg/h, desde enero 2026 como minimo. Falta recibirlo; es la pieza que mas valor agrega |
| Calendario de feriados | 1, 2 | Ya existe (tabla de feriados) |
| Cambios de parametros (umbrales, limites, tarifas) con fecha | 2, 3 | Parcial; versionar |
| Paquete de contexto por aviso (JSON: cifras, tendencia, historial del mismo aviso, mantenimientos y eventos cercanos, respuestas anteriores del usuario) | 3 | Nuevo; se arma desde la etapa 1 aunque no lo lea nadie, asi la IA se prueba despues con avisos reales del pasado |
| Registro de decisiones: recomendacion, quien la acepto, que se hizo y resultado medido | 3 (medir si la IA acierta) | Tabla creada desde ya, se usa desde la etapa 3 |

Huecos que hoy no se pueden cerrar desde la app (decide el usuario):
- Caterpillar: temperatura del refrigerante y presion de aceite no estan en su Modbus (ya se decidio no tocar el controlador).
- Nivel de combustible de los dos generadores: sale de la planilla manual MF13 (los controladores leen 0).
- Calidad de energia solo en GA752, HornoL3 y OrientadoraL2.
- Datos por minuto en la app: solo 2026 (enero a octubre); no se encontraron anios anteriores por minuto en
  `C:\LineaBaseX` (verificado el 30-10). Los anios anteriores existen como Excel (kWh por orden de produccion, fallas). La etapa 2 necesita al menos un ciclo anual
  para ver estaciones.

### 5.2 Que es posible y que es logico

| Etapa | Posible | Logico | Condicion para pasar |
|---|---|---|---|
| 1. Tendencias | Si, hoy | Si | Ninguna: alcanzan 28 dias de datos |
| 2. Redes neuronales | Si, pero no hoy | Si, como complemento y solo si mejoran lo que ya hay | 6 a 12 meses de resumenes, fallas etiquetadas y **modo sombra**: corre en paralelo con la etapa 1 sin avisar, se compara aviso por aviso y solo se activa si acierta mas o avisa antes |
| 3. IA para decisiones | Si, como asesora | Si, sin tocar equipos | Avisos con respuesta del usuario y decisiones registradas con resultado |

No es posible o no es logico:
- Redes neuronales desde ya: los generadores tienen 3 semanas de datos y no hay fallas etiquetadas con causa.
- Una IA que ejecute acciones sola sobre los equipos (parar un generador, mover cargas): no esta autorizado (controladores
  solo lectura) y no es logico mientras no haya meses de recomendaciones acertadas. Si algun dia se quiere, por pasos:
  recomendar; recomendar y pedir OK; ejecutar solo acciones reversibles y de bajo riesgo, con limites fijos y boton de
  apagado.
- Que la IA mire los datos minuto a minuto.
- Que las redes o la IA reemplacen a la etapa 1: se apoyan en ella, y la etapa 1 sigue funcionando sin internet aunque
  falle lo demas.

Usos de la etapa 2 (cada uno pide datos distintos):
- Anomalias de varias variables a la vez ("esta combinacion de valores no se vio antes"): meses de resumenes.
- Pronostico de la demanda de las 18 a 22 h para decidir cuando arrancar los generadores: un anio.
- Riesgo de falla de arranque de un generador: historial de fallas etiquetado.
Se entrenan aparte (PC de diseno) y la app solo ejecuta el modelo ya entrenado, sin internet (formato ONNX). Python no
esta instalado hoy en la PC de diseno: se instala cuando llegue la etapa.

Usos de la etapa 3: diagnostico y accion recomendada por aviso, resumen diario o semanal, responder preguntas sobre los
datos, recomendar cuando arrancar los generadores segun el pronostico y la tarifa. Selector Ninguno / Ollama / Gemini /
Claude (decidido 29-10). El servidor no tiene internet: alli solo Ollama (~5 GB, PC con ~16 GB de RAM). La clave de los
proveedores en la nube no viaja en Pasar configuracion. Todo texto guardado con fecha, proveedor y modelo.

Los detectores son intercambiables: la regla estadistica y la red devuelven el mismo resultado (variable, valor,
esperado, puntaje, explicacion), asi la pantalla, los avisos, Correcto / Falsa alarma y el registro no cambian al sumar
una etapa.

### 5.3 Orden de implementacion

1. **Etapa 1a:** resumen diario + reglas generales (energia, tension, corriente, PF, datos raros) + pantalla + Correcto /
   Falsa alarma con motivo y causa en listas cerradas + foto de cada aviso + tablas de contexto y de decisiones.
   **Antes de encenderlo:** correrlo sobre los meses que haya y mostrar al usuario la lista de avisos que habria dado,
   para calibrar los minimos.
2. **Etapa 1b:** generadores, calidad, sensores, mezcladores y la revision horaria.
3. **Completar la base comun:** produccion en kg, paradas planificadas y fallas (Mantenimiento Correctivo con AMEF o
   registro minimo). Se empieza a pedir desde la etapa 1a; no espera a la etapa 2.
4. **Etapa 2** cuando haya 6 a 12 meses y fallas etiquetadas: modo sombra, comparar, activar lo que gane.
5. **Etapa 3** cuando haya avisos con respuesta: selector de proveedor, diagnostico por aviso, resumen, recomendaciones con
   decision registrada.
6. **ISO 50001** (linea base, IDEn) sobre la misma base cuando haya produccion en kg.

### 5.4 Excel de ordenes de produccion (30-10, imagen recibida, archivo sin abrir)

El usuario tiene un Excel con una fila por orden de produccion: fecha y hora de inicio y fin, kWh inicial y final del
medidor, area, linea, codigo de maquina, codigo y descripcion del producto, cantidad, kg de tubos, scrap y purga util
e inutil, total procesado, kWh consumidos, kWh por tonelada, kWh/kg, kWh por tubo, rendimiento, kg/h, mes y horas de
funcionamiento; desde enero 2026 como minimo. Puede pasarlo o ponerlo en una carpeta de `C:\LineaBaseX` cuando se lo
pidan (propuesto: `C:\LineaBaseX\datos\produccion\`). Falta ver cuantos anios cubre y que significan las filas en rojo
(probables ordenes con problema).

Que aporta:
- Cierra el hueco de la produccion en kg: kWh/kg por linea y producto es mejor referencia que kWh por hora de marcha.
- Aviso por orden: "esta orden consumio 12 % mas por kg que las ultimas 10 del mismo producto en la misma linea".
- La app tiene el contador de cada medidor por minuto: puede calcular sola los kWh de cada orden desde su inicio y fin y
  compararlos con los del Excel (errores de digitacion, filas con division por cero, duplicados).
- Importacion como la de la planilla MF13 (la app lee el .xlsx, muestra lo que entiende y el usuario confirma). A
  largo plazo, cargar la orden en la app y que calcule los kWh y el kWh/kg sin Excel.
- Etapa 2: anios de kWh por orden y producto permiten entrenar el consumo esperado por orden antes que la deteccion de
  fallas.

Linea base energetica ISO 50001 de las lineas de extrusion (idea del usuario, 30-10): el Excel permite calcularla.
- Modelo por linea: kWh de la orden = parte fija + kWh por kg x kg (+ horas, + temperatura ambiente, + familia de
  producto), con R2 y significancia de cada variable. El kWh/kg solo no basta: sube cuando se produce menos por hora
  por la energia fija de tener la linea encendida.
- Periodo base: 12 meses o mas que cubran la operacion normal; documentar y ajustar cuando cambie un factor fijo
  (matriz, motor, linea).
- Alcance: el medidor de la linea; chillers, compresores, bombas, mezcladores y molino tienen medidor aparte (decidir
  si se reparten).
- Limpieza: filas con #DIV/0!, en cero o duplicadas; filas en rojo; ordenes de varios dias con paradas (separar energia
  productiva y en espera con el contador por minuto, disponible desde 2026); kWh escritos a mano contra el contador
  de la app.
- IDEn = consumo real contra esperado por el modelo y su acumulado (CUSUM). Es el mismo "consumo esperado por orden"
  del vigilante: una sola construccion sirve para ISO 50001 y para avisar desvios.
- La aprueba y documenta el equipo de energia; la app calcula y deja la evidencia.

Reglas del Excel de ordenes (explicadas por el usuario el 30-10):
- Filas en cero o con #DIV/0!: son avisos de un producto que solo cambia la campana (U/R contra E/C: mismo tubo, cambia
  el extremo para unir). La hora del cambio no se ve en la energia por minuto (la extrusora no cambia de carga). No
  entran al modelo (0 kg). Los avisos de la Linea03 (08/01) tambien cambian la presion (0,63 y 0,80 MPa):
  aclarado el 30-10, el operador estira o frena con el puller para lograr el espesor de pared sin parar la linea
  (los kg/h los fija la extrusora), por eso tampoco se ve en la energia. La presion no deberia ser variable del modelo
  (si lo son diametro, compuesto y kg/h); se comprueba con los datos y se deja fuera si no mejora el ajuste.
- Relleno rojo: parte de una orden cortada al fin de mes (energia hasta el ultimo minuto del mes); la parte siguiente
  toma desde el primer minuto del mes. Relleno verde: orden completa que empieza en un mes y termina en el siguiente.
  Una orden que cruza el mes puede aparecer tres veces (parte, completa, parte): riesgo de contar doble al sumar.
- Recomendacion: columna "tipo de fila" (Completa / Parte de mes / Aviso) y un ID de orden comun. El modelo usa solo
  Completas; el kWh de un mes se arma con las Partes (o lo calcula la app con el contador por minuto, asi no hace falta
  cortar a mano). Al importar, fusion automatica de partes consecutivas (misma linea y producto, fin de una = inicio
  de la siguiente +-2 min), mostrada al usuario para confirmar. El aviso va como columna "variante" en la misma
  fila, no como fila en cero.
- Grafica del usuario: kWh por minuto = contador de ahora - contador del minuto anterior (derivada discreta, dt = 1 min);
  x 60 da kW. Las lecturas no caen exactamente cada 60 s (01-10 00:00:27 y 00:01:26 = 59 s): para kW dividir por el
  tiempo real entre lecturas; para totales la suma de las restas da final - inicial, exacta. Si el contador sube a
  escalones gruesos usar PW (se guarda cada minuto) o promediar 5 min.
- Entrega del usuario (30-10): el Excel completo del historico en una sola entrega (en `C:\LineaBaseX\datos\produccion\`) y el
  compuesto por orden o por linea y dia (hay un `CONSUMO DE COMPUESTOS` en `MNTTO2020\MF25\2023`, sin abrir).
- Fallas (MF25 / MF-21): redactadas libremente por quien reporta y quien resuelve; el usuario arma el AMEF para
  estandarizar. No se usan como etiquetas hasta entonces. Sirven ya: fecha, codigo de equipo, OT, horas y tipo
  (preventivo / correctivo). Idea: con el AMEF listo, un modelo de lenguaje clasifica el historial en texto libre en
  los modos de falla estandar y una persona valida.
- Indicadores del Excel: kWh/kg (sobre material procesado) y kWh por producto terminado: fijar cual es el oficial.
- Variables que no olvidar en el modelo: compuesto (formulacion), presion o espesor, desgaste de barril y tornillo
  (el modulo de mantenimiento ya guarda Cambio y Recalibracion con horometro), temperatura ambiente y del agua,
  energia de arranque y calentamiento, y cambios de medidor o reinicios de contador.

Hoja de arranque (segunda captura, 30-10; LINEA32 y LINEA34 de mayo 2026 vistas): por orden, tres instantes con su
kWh (inicio de arranque o calentamiento, inicio de purga, inicio de produccion normal) y de ahi tiempo y kWh de
calentamiento, de purga y de arranque total. Ej. LINEA32 01/05: 1:25 y 23,19 kWh + 0:53 y 23,67 kWh = 2:18 y 46,86 kWh
(las restas del contador cuadran). Es la energia de arranque.
- Duda: el kWh inicial del Excel de ordenes, es el del inicio del arranque o el de la produccion normal? (evita contar
  dos veces o dejar afuera el arranque). Y si esta hoja existe para todas las lineas y meses.
- Uso: modelo = energia de arranque (parte fija por arranque) + energia de produccion. La de arranque se explica con
  el tiempo parado antes (arranque en frio o en caliente; sale de la orden anterior de la misma linea), el compuesto,
  el diametro y la temperatura ambiente. Aviso: arranque que tarda o gasta mas que lo normal de la linea.
- Temperatura ambiente y del agua: el dato existe (la app guarda TemperaturaAmbiente y TemperaturaAgua en los archivos
  de 2026; antes no). No es un faltante: es una variable a probar en el modelo. Para las lineas se espera un efecto
  pequeno (se comprueba); pesa mas en los chillers (otra linea base).
- Indicadores (cuentas verificadas con la fila LINEA02 19/01/2026: 1046,22 kWh; 6146 kg procesados = 5600,7 tubo +
  266,6 + 12,2 scrap + 173,6 + 93,1 purga; 90 tubos):
  A) kWh/kg procesado = 0,170: mide la maquina y no cambia con el scrap. Su "kWh x producto terminado" (953,36) es
  solo ese kWh repartido por peso; 953,36 / 5600,7 da 0,170 otra vez, asi que el scrap no se ve.
  B) kWh/kg de tubo bueno = 1046,22 / 5600,7 = 0,187: incluye la perdida por scrap y purga.
  C) kWh por tubo = 10,59: solo se compara dentro del mismo producto.
  Propuesta: oficial = real contra esperado del modelo sobre kg procesados (A); B como segundo indicador para ver el
  efecto del scrap. Decide el usuario con su equipo de energia.
- "Rendimiento kg" del Excel = kg procesados / 24 en las filas revisadas (256,09 = 6146,2 / 24): confirmar que es.

Modelo actual del equipo de energia (capturas del 30-10): linea base 2025 mensual y global (todas las maquinas de
extrusion sumadas, sin distinguir linea ni producto): kWh del mes contra kg procesados, 12 puntos. Regresion: kWh =
4.455 + 0,24076 x kg; R2 0,939 (ajustado 0,933), error tipico 15.801, F 155; la pendiente es significativa (p = 2e-7) y
la interseccion no (p = 0,88, error 29.414). Seguimiento 2026: objetivo de bajar 1 % el IDEn, consumo esperado,
limites, ahorro en kWh y en $ (-284.924 kWh y -$31.450 a septiembre, -6,37 %). El usuario no comparte la decision: es
global y no cuenta la energia desperdiciada (el indicador usa solo el material procesado).
Verificado el 30-10 con la app (enero a septiembre 2026, contadores):
- Las 13 lineas de extrusion suman 364.208 kWh en enero y 363.929 en septiembre; los 18 medidores de la zona, 392.112 y
  416.588. La hoja del equipo da 415.994 y 435.158: 3 a 7 % mas que los 18 medidores y 10 a 20 % mas que las lineas; no
  se pudo reproducir. Pendiente: que medidores suma la hoja (alcance de la linea base).
- Con la linea parada (menos de 15 kW por 5 minutos o mas) las 13 lineas gastan 1.000 a 2.000 kWh al mes (menos de
  0,5 %): la energia desperdiciada esta dentro de las ordenes (arranque, purga, scrap, poca carga), no con la linea
  apagada.
- El "consumo esperado" de 2026 de la hoja no sale de la recta de 2025 de la captura (enero: 417.044 con la recta,
  423.989 en la hoja); la hoja usa aprox. 3.981 + 0,2451 x kg (+1,7 %). Con la recta de 2025 el ahorro de enero a
  septiembre seria ~197.900 kWh (4,5 %); contra el esperado mensual de la hoja (suma de esperado - real de cada mes),
  271.742 kWh (corregido el 3-11: antes decia 239.775 por usar el total de la hoja, que aplica la intercepcion una sola
  vez: 4.427.753 contra 4.459.719 de la suma mensual); la hoja informa 284.924 kWh. Hay que fijar cual es el oficial y
  documentar el ajuste de la linea base.
Decision (30-10): llevar por detras los tres indicadores (A kWh/kg procesado, B kWh/kg de tubo bueno, C kWh por tubo);
oficial por ahora A; el equipo de eficiencia decide despues cual queda. La hoja de arranque existe para todas las
lineas y productos.
Propuesta de tres niveles: 1) planta mensual (lo actual, corregido); 2) por linea y familia de producto con las
ordenes y el arranque, y esperado de planta = suma de esperados (corrige el efecto de la mezcla); 3) indicadores de
desperdicio: B, energia de arranque, energia de scrap y purga.

Revision del libro de linea base (3-11-2026; `C:\LineaBaseX\datos\lineabase\09MedicionSeptiembre26.xlsx`; el de
diciembre 2025 sin abrir; solo lectura sobre copias). Hojas clave: MEDICION_XTR2026 y MEDICION_XTR (ordenes 2026 y 2025),
PRODUCCION (kg por dia, linea y producto), ArrnaquesProduccion, Hoja2 = Tabla8 (kWh medido y calculado por maquina y
mes), EXTRUSION2026LB y (2Sigma) (regresion 2025), EXTRUSION2026 SGEn y (2sigma) (seguimiento 2026).
Correccion: la recta de 2026 (3.996 + 0,2451 x kg) no es un error: es el modelo depurado (MDF2) de la hoja LB, 10 puntos
sin enero y septiembre de 2025 (fuera de la banda de 1 sigma); R2 0,993, error 5.563 kWh, intercepto p = 0,72. La hoja
tambien trae la variante de 2 sigma. Con el modelo de 12 puntos el ahorro de enero a septiembre seria ~197.900 kWh
(4,5 %) y no 284.924 (6,37 %). Los dos meses quitados son los de menor IDEn (0,2175 y 0,2303) y 2026 ya trae IDEn
menores: hay que saber si eran errores de datos o meses buenos.
Coherente (se puede usar):
- kWh de cada orden (Fin - Ini) contra el contador de la app: 1.086 de 1.102 ordenes dentro de 1 %; total +0,2 %.
- Las ordenes empiezan al inicio del arranque (912 de 927), no en produccion normal: su kWh ya incluye calentamiento y
  purga. Hoja de arranque: 938 de 941 filas coherentes; arranque = 2,5 % de la energia (mediana 29,5 kWh de
  calentamiento y 28,6 de purga).
- kg mensuales de PRODUCCION = kg de la hoja de seguimiento (diferencia menor a 0,02 %, salvo mayo: -0,13 %).
- Tabla8: kWh calculado (intensidad de la orden x kg del turno) contra medido por linea y mes: 0,1 a 1,2 % (13 lineas).
- Avisos (76 filas sin fechas): 33 solo cambian la junta, 28 solo la presion, 12 ambas, 3 otros: coincide con lo que
  explico el usuario. Entre ordenes seguidas de la misma linea (menos de 1 h entre una y otra) 311 cambian de diametro, 123 solo la
  presion, 9 solo la junta y 94 ambas: son ordenes nuevas con su arranque (el mismo cambio es orden nueva si paran y
  aviso si no).
Incoherente:
- Filas de ordenes que se pisan: 141 contenidas dentro de otra fila de la misma linea y 145 solapes (13.168 h).
  Sumar todas da 4,26 millones de kWh contra 3,60 medidos (+18 %); sin las contenidas y repartiendo por mes segun el
  tiempo da lo medido a +-1 % (enero -2,9 %). 3,58 millones de kg de PRODUCCION entran en 2 o mas ordenes (misma linea y
  producto con dias compartidos) y 0,85 millones no entran en ninguna: el kg por orden no es confiable cuando hay varias
  ordenes en el mismo dia.
- "Consumo real" de la hoja de seguimiento 2026 (constantes pegadas): 12 a 20 % (media 16 %) sobre las 13 lineas medidas
  (ene 415.994 contra 372.597; sep 435.158 contra 363.929) y 7 a 14 % sobre el area Extrusion de Tabla8. No sale de las
  ordenes ni de Tabla8; falta saber que maquinas suma y si la base 2025 usa las mismas.
- 2025: las ordenes de enero a junio de 2025 cubren solo 63 a 73 % del kWh y 65 % del kg de la base; no sirven para
  reconstruirla. La app no tiene energia por minuto de 2025.
- Casos puntuales: LINEA31 (huecos de lectura en la app en junio y julio) y ordenes que cruzan el mes (31-03, 01-09).
Opciones: A) base mensual por linea (kWh medido por linea y mes + kg por linea y mes de PRODUCCION; 13 x 12 puntos; no
depende de las ordenes; falta el kWh por linea y mes de 2025); B) limpiar la tabla de ordenes (tipo de fila + ID, kg por
turno) y modelar por linea y familia de producto; C) mantener el modelo global del equipo con la misma lista de maquinas
en 2025 y 2026 y la sensibilidad (12 puntos, 10 puntos, 2 sigma) a la vista. Recomendado: A ya, B despues, C mientras tanto.

Composicion del consumo de extrusion (confirmada el 3-11 con la tabla del usuario): 13 lineas (medidor de cada una) +
Cabezal XTR2 + Cabezal XTR10 (sin medidor, estimado como porcentaje del XTR2) + Ribloc + Orientadoras 1, 2 y 3 (OL3 =
OrientadoraL3 + HornoL3). Reproduce la hoja de seguimiento a -0,24 % / +0,75 % cada mes. Medidores calculados del usuario:
Inyeccion neta = Inyeccion - OrientadoraL2 - GA752 - HornoL3; Servicios auxiliares TR2 = TDGeneradorSA - ServicioAux -
ServAuxP3LabCalid; Linea02 + CabezalXTR2; Linea10 + CabezalXTR10. Contra los contadores de la app coinciden en 6 a 8 de 9
meses; los demas tienen huecos o saltos de lectura. Propuesta: tabla en la H2 de medidores calculados (nombre, formula
con medidores, signo y factor, vigencia) y tabla de correcciones por mes con motivo; ambas se agregan a Pasar
configuracion (`TraspasoConfiguracionService.TABLAS`).
- Cabezal XTR10: de enero a abril sigue la relacion de kg Linea10/Linea02; de mayo en adelante no (julio y septiembre
  repiten 5.775,12). Regla por definir.
- Enero y septiembre de 2025 (usuario): segun sus graficos no son meses bajos, asi que el IDEn bajo es error de datos:
  corregir el kWh o el kg, o dejar la causa por escrito antes de excluirlos del modelo.
- Hallazgo: 6 de 9 meses de 2026 superan el maximo de kg de la base 2025 (1,85 M kg); los 4 meses que lo superan por
  20-26 % concentran el ahorro informado (-8,6 a -9,6 %); dentro del rango, -1,7 a -2,7 %. Con la recta de 2026 (fija
  114.752 kWh/mes, 0,148 kWh/kg) producir mas por mes explica ~80 % de la baja de IDEn de enero-marzo (0,2173) a
  abril-septiembre (0,2006); la mezcla de lineas solo ~6 %. Indicativo (9 meses).
- Maqueta de la opcion A: `reportes/maqueta-linea-base-extrusion.html` (no versionada), con datos de enero a septiembre
  de 2026; el script de calculo esta en el scratchpad de la sesion (Xlsx.java, Maqueta.java).

Revision del programa Python del usuario (3-11; `C:\LineaBaseX\datos\ISO50001`: dashboard.py de Streamlit + modules/
datos, calculos, graficas, config; lee `Recursos\Medicion2026DataAnalisisxlsx.xlsx`, version de abril con solo ene-mar;
Recursos trae ISO 50001, ISO 50006 en espanol, guias TUV y ONUDI, SISTEMA_ISO_50001_2022, Newbold y formularios; los
PDF no se han leido todavia).
- Consumo mensual de extrusion = hoja TOTALES (columna D): igual que el Excel. En el Excel, D = suma de los kWh por turno
  (intensidad de la orden x kg) de las 13 lineas + los 6 conceptos extra (verificado: enero 370.927 + 45.067 = 415.994;
  mayo -1.342 y julio +2.726 manual).
- Camino por lote (DIM, L26, R26) NO coincide: suma de lotes contra TOTALES -5,3 % ene, +2,2 feb, -0,5 mar, +12,4 abr,
  +3,4 may, +5,7 jun, +11,9 jul, +0,9 ago, -18,8 sep (con el archivo de abril: -6,1, -9,0, -18,2). Ahorro por modelo de
  linea: 525.546 kWh (ene-sep) contra 271.742 del Excel (Q1: 82.576 contra 25.059). Causas: 141 lotes contenidos en otros
  (+18 %) y lotes sin cabezales, orientado ni Ribloc (-12 %); casi se compensan en el total (+1,8 %), no mes a mes.
- Errores: datos.py `iloc[5:16]` deja fuera diciembre (debe ser [5:17]); meses fijos ene-mar en 4 funciones de datos.py y
  en dashboard.py (:160, :178, :3534); posiciones fijas de TOTALES (filas 6-8, 21-24, 36-39) que no existen en el libro
  actual (kg por area en filas 23-35, desperdicio 41-52, suma 57+, extras 72-84) y `try/except` que vuelve a sumar lotes
  sin avisar; IDEn con kg de tubos (DIM y R26) y regresion con kg procesados (tubos = 92,5-92,7 % del procesado: IDEn 2026
  0,2127 contra 0,1972); lotes que se pisan sin filtrar; LINEA17 con 1 lote en la hoja 2025 (necesita 4): sin modelo;
  intercepciones negativas en LINEA03 (-23) y LINEA31 (-556) del modelo por lote.
- Excel: la columna "Total" de SGEn aplica la intercepcion una sola vez (4.427.753 contra 4.459.719 de la suma mensual).
Cabezal XTR10 (regla de la Hoja3 del libro): XTR10 = Linea10 x XTR2 / (XTR2 + Linea02), con los tres datos del mismo mes
(el cabezal de la linea 10 consume de su linea lo mismo, en proporcion, que el cabezal de la linea 2 de la suya). Bien
aplicada en enero, febrero y abril. Mal: marzo (denominador y Linea10 de febrero; 4.635 contra 5.000), mayo (XTR2 y XTR10
intercambiados en TOTALES; correcto XTR2 6.468,48 y XTR10 5.237,81), junio (Linea02 de mayo y denominador de abril; 4.367
contra 5.373), julio (denominador de abril; 5.775 contra 7.098), agosto (Linea02 de julio y denominador de abril; 5.505
contra 6.708), septiembre (valor de julio copiado; 5.775 contra 6.926). Causa: en Hoja3 el denominador usa
`$E$246:$E$247` (bloque de abril) en casi todos los bloques. Efecto: +5.889 kWh en 9 meses (0,14 % del consumo). El
usuario decidio aplicar la regla desde octubre y dejar los meses anteriores como estan por ahora.

## 6. Que viaja al servidor

- Resumen diario y avisos: cada PC calcula los suyos (como la franja horaria). No viajan.
- Ajustes (minimos, silenciados, hora, proveedor sin clave): JSON nuevo de config -> agregar a
  `ConfigLoaderService.ARCHIVOS` para que viaje.
- Lo aprendido con Correcto / Falsa alarma y las decisiones: quedan en la PC donde se registraron (el servidor) y no se
  purgan. Si hace falta entrenar en la PC de diseno, se copia la carpeta `C:\LineaBaseX\vigilancia` del servidor.

## 7. Decisiones pendientes del usuario

1. Permiso: flag "Ver Vigilancia" (propuesto) o el de Alarmas.
2. Hora de la revision nocturna (propuesto 00:30).
3. Tope de avisos nuevos por noche (propuesto 5).
4. RAM del servidor (solo para Ollama en la etapa 3).
5. Excel de ordenes de produccion: entregarlo (propuesto `C:\LineaBaseX\datos\produccion\`), decir cuantos anios cubre y que
   significan las filas en rojo.
6. Fallas: confirmar que el historial en Excel (MntoFallas 2022 a 2024, Modos de falla2023, MF-21) sirve para importar y
   si se adelanta el modulo de Mantenimiento Correctivo para seguir registrando.
7. Quien registra las paradas planificadas.

## 8. Limites conocidos

- Saldran como "datos raros": descuadre de los medidores de los trafos, MotorL3/MotorL4 (IDs corridos), PF > 1. Avisar
  una vez y que el usuario decida si se silencian.
- Tableros de 220 V (Mixer01, Mixer04, Ribloc, CabezalXTR2, ServicioAux) sin tension nominal cargada: solo desvio de
  su normal, no cercania al limite, hasta cargarla en Calidad.
- Paradas planificadas, mantenimiento y feriados pareceran anomalias al principio: lo corrigen laborable / fin de
  semana, el registro de paradas planificadas y "Falsa alarma".
- Carga: la revision nocturna lee un dia de 48 medidores (1-2 min); el primer armado de todo el historial en segundo plano.
- La normalidad de 28 dias no ve estaciones (verano, lluvias): la etapa 2 con un anio de datos si.

## 9. Notas tecnicas para implementar

- Datos: `{mes}` base normal (`fecha, kwh`, 48 tablas; los sensores guardan su valor en `kwh`), `{mes}VIP` (`fecha, VAB,
  VAC, VBC, IA, IB, IC, PW, PF, KWhR`, 48 tablas), `{mes}Calidad` (3 tablas, 36 columnas), mezcladores en la base normal
  (`PV, SV`), `{mes}Generador`, `C:\LineaBaseX\generador\{arranques,eventos,capturas}`, H2 `COSTO_COMBUSTIBLE_GENERADOR`.
- Unidades: KWhPlanta1 (ION8600) y TDGeneradorSA (PAC1020) guardan PW en W; KWhPlanta1 guarda tensiones fase-neutro
  (~12,7 kV). Trabajar con cambios relativos al normal de cada serie evita depender de la unidad.
- Leer por dia con el filtro de clave primaria de `ConsumoMedidoresService.filtroDias` (`fecha >= 'dd-MM-yyyy' AND
  fecha < 'dd-MM-yyyy~'`) y limitar cada archivo mensual a su mes (el de septiembre repite horas del 1 de octubre).
- Maquina encendida: umbral y ventana de `AlarmaConfig` DETENCION (o CICLO_COMPRESOR), como `ConsumoMedidoresService.criterios()`;
  horas de marcha de `HorometroDiario`. Lista de medidores y tipo: `TopologiaMedidores`.
- Archivo `C:\LineaBaseX\vigilancia`: tablas resumen_dia, resumen_hora, aviso (con la foto en JSON), respuesta (motivo,
  causa, accion, resultado), contexto (JSON por aviso) y decision. Nunca se purga.
- Detectores intercambiables: una interfaz comun (variable, valor, esperado, puntaje, explicacion) que usan la regla
  estadistica ahora y la red neuronal despues; cada detector se puede poner en modo sombra.
- Avisos: `TipoAlarma` nuevo (columna `varchar(40)`, no se rompe con constantes nuevas) en `AlarmaEvento`, no urgente;
  revisar que `SirenaService` y `AlarmaTrayNotifierService` no lo tomen. El de generadores debe contar en
  `TipoAlarma.deGenerador()`.
- Flag nuevo: checklist de la seccion 13 de CONTEXTO-PROYECTO (Usuario, LineaAccessService, UsuariosView, AccesoXxx,
  MainLayout). Componentes Vaadin nuevos: `@Uses` y regenerar el bundle.
- No usar `mvn compile` con la app del usuario arriba en el 8080: compilar con javac a carpeta temporal.
- Regla de commits: cada cinco cambios de codigo (al 29-10 hay uno pendiente de otra sesion: reintento en
  `LectorControlador`).
