# ComAp InteliGen 500 G2 (generador Caterpillar, junto a TR1) - mapa de registros Modbus

Controlador: **InteliGen 500 G2**, firmware **2.4.3.6**, aplicacion GC (leido el 2026-10-22: ID String 41332-41347 =
"InteliGen500 G2-2.4.3.6", FW Version 41348-41355, Application 41356 = 2). No es el InteliGen 500 original: la guia que
corresponde es la "InteliGen 500 G2 Global Guide". IP 192.168.0.201, Modbus TCP puerto 502, **Unit ID 1**. Holding
registers (funcion 03). Red asociada: `Trafo1` (TR1). **Solo lectura**: la app nunca le escribe.

Particularidades: contesta **0x04** (no 0x02) en los registros que no existen, y 41003, 41015, 41017-41019, 41043 y 41051
no existen: `maxHueco 0`. 0x8000 = no disponible. 32 bits = palabra alta primero.

## Lista oficial de InteliConfig (fuente de verdad)
`lista-modbus-inteliconfig-2026-10-22.txt`: la exporto el usuario de InteliConfig el 2026-10-22, con la configuracion
cargada en el controlador. Los registros dependen del firmware y de la configuracion: si se cambia alguno de los dos, hay
que volver a exportarla.
- **Numeracion:** la lista da la direccion de protocolo (base 0); el registro 4xxxx = 40001 + direccion. Ej.: RPM 01000 =
  41001.
- **Tablas:** Values (funciones 03/04, lo que lee la app); Binaries (funciones 01/02: la app no las usa, los mismos bits
  estan en las palabras de Values); Setpoints vacia (no se exporto ninguno; la app nunca escribe). Al final, las listas
  de estados (List#) y los bits de cada palabra (Binary#, bit 0 = el de menor peso).
- **No estan en la lista:** la lista de alarmas (44215 en adelante) y el reloj (44201-44204). Se encontraron con barridos
  (ver abajo).
- **Cotejo del 2026-10-22:** los 30 registros que usa la app (`generador-modelos.json`, modelo `InteliGen500`)
  coinciden en direccion, tipo y escala. La lista da los contadores de energia como Int32 con signo; la app los lee como
  UInt32 (iguales mientras sean positivos). La lista corrigio cinco interpretaciones del barrido (seccion "Corregido con
  la lista").

Historia: conectado el 2026-10-09; el mapa se armo primero con un barrido de 41001-41400, las fotos de la pantalla (Home,
Power, Generator, Mains, Statistics) y 4 lecturas seguidas cada 20 s, con el generador en paralelo ("ParalOper") a unos
600 kW. El mapa del InteliGen 200 **no sirve** (solo coincide RPM).

## Los que usa la app (confirmados)
| Registro | Dato | Tipo | Escala | Evidencia (pantalla / lectura) |
|---|---|---|---|---|
| 41001 | RPM | UInt16 | 1 | 1800 / 1798-1800 |
| 41002 | Generador kW total | Int16 | 1 | 609 y 599 / 594-611 |
| 41004-41006 | Generador kW L1, L2, L3 | Int16 | 1 | 197/202/210 / 194-199, 197-202, 204-210 |
| 41007 | Generador kVAr total | Int16 | 1 | -5 / -6..9 |
| 41008-41010 | Generador kVAr L1, L2, L3 (no se guarda) | Int16 | 1 | 1/-9/3 / 0..5, -9..-4, 3..8 |
| 41011 | Generador kVA total | UInt16 | 1 | 610 / 596-613 |
| 41012-41014 | Generador kVA L1, L2, L3 (no se guarda) | UInt16 | 1 | 197/202/210 |
| 41016 | Caracter de la carga (no se guarda) | Char | - | 82 = "R" (R, L o C junto al PF) |
| 41020 | Frecuencia generador | UInt16 | 0.1 | 60.046 Hz / 600 |
| 41021-41023 | Generador V L1-N, L2-N, L3-N | UInt16 | 1 | 267/268/268 / 264-266 |
| 41024-41026 | Generador V L1-L2, L2-L3, L3-L1 | UInt16 | 1 | 464/464/462 / 459-461 |
| 41027-41029 | Generador corriente L1, L2, L3 | UInt16 | 1 | 724/735/766 / 740-794 |
| 41033 | Frecuencia red | UInt16 | 0.1 | 59.952 Hz / 600 |
| 41034-41036 | Red V L1-N, L2-N, L3-N | UInt16 | 1 | 266/268/267 / 264-267 |
| 41037-41039 | Red V L1-L2, L2-L3, L3-L1 | UInt16 | 1 | 464/464/462 / 459-462 |
| 41052 | Tension de bateria (Ubat) | UInt16 | 0.1 | Analog Inputs 27.9 V / 279 |
| 41053 | Entrada D+ (no se guarda) | UInt16 | 0.1 | 0.0 V / 0 |
| 41284-41285 | Genset kWh | Int32 | 1 | 52964 / 53014-53078, sube ~600 kW |
| 41286-41287 | Genset kVArh | Int32 | 1 | 13338 / 13338 |
| 41288-41289 | Red (Mains) kWh | Int32 | 1 | 5153051 / 5153055-5153060 |
| 41290-41291 | Red (Mains) kVArh | Int32 | 1 | 1245847 / 1245861-1245878 |
| 41292-41293 | Horas de marcha | Int32 | 0.1 | 124.9-125.0 h / 1250-1251 |
| 41294 | Cantidad de arranques | UInt16 | 1 | 121 / 121 |

**Senales de red (desde el 2026-10-22, G22):** ademas la app lee 41327 (estado de los interruptores), 41058 (entradas
binarias, tipo BITS16), 41394 y 41403 (palabras de avisos y protecciones de red, BITS16), 41045 (ROCOF), 41046 (ROCOF
maxima) y 41044 (salto de vector maximo). Que significa cada codigo y bit esta en el campo `red` del modelo en
`generador-modelos.json` (ver la tabla de bits mas abajo). Con eso salen los eventos de red y la alarma "Perdida de red".

**PF del generador:** la lista no tiene registro de PF. La app lo **calcula** con kW / kVA: 599 / 601 = 0.997.

**Aceite y temperatura del motor:** el controlador no los mide (41054-41056 son entradas analogicas sin usar; la pantalla
"Analog Inputs" solo tiene Ubat y D+). En el Caterpillar los mide su propio panel o su computadora de motor. Para tenerlos
haria falta conectar la computadora del motor al controlador por CAN J1939; si se hace, se agregan al mapa sin recompilar.
La pantalla del generador muestra "no lo mide este controlador".

## Otros registros de la lista (la app no los lee, salvo las senales de red de arriba)
Lectura del 2026-10-22 19:13, en paralelo con 595 kW (solo lectura, una vez).

| Registro | Dato | Tipo | Escala | Lectura |
|---|---|---|---|---|
| 41030 | Corriente de falla a tierra (A) | UInt16 | 1 | |
| 41031 | Deslizamiento de frecuencia (Hz) | Int16 | 0.01 | |
| 41032 | Angulo de deslizamiento (grados) | Int16 | 0.1 | |
| 41040 | Corriente de red L1 (A): el controlador tiene un solo TC de red | UInt16 | 1 | 280 |
| 41041 | Potencia de red importada, Mains Import P (kW) | Int16 | 1 | 173 |
| 41042 | Reactiva de red importada, Mains Import Q (kVAr) | Int16 | 1 | 131 |
| 41044 | Salto de vector maximo, Max Vector Shift (grados) | UInt16 | 0.1 | 1.6 |
| 41045 | ROCOF (Hz/s) | Int16 | 0.01 | 0.06 |
| 41046 | ROCOF maxima, Max ROCOF (Hz/s) | Int16 | 0.01 | 1.11 |
| 41047-41048 | Carga segun el controlador, Load P (kW) | Int32 | 1 | 769 (= generador + red: 595 + 173) |
| 41049-41050 | Load Q (kVAr) | Int32 | 1 | 141 |
| 41058 | Entradas binarias (bits abajo) | 16 bits | - | 31 |
| 41059 | E-STOP (bit 0) | 16 bits | - | 1 |
| 41060 | Salidas binarias (bits abajo) | 16 bits | - | 1 |
| 41061 | Estado de la interfaz Ethernet (List#1) | UInt16 | - | |
| 41273-41274 | Required P Target / Required P (kW) | Int16 | 1 | |
| 41275-41277 | Salida del regulador de velocidad (V, 0.001) / Speed Request (%, 0.1) / Requested RPM | Int16/UInt16 | - | |
| 41278-41280 | Required Q (kVAr) / salida del regulador de tension (V, 0.001) / Voltage Request (%, 0.1) | Int16 | - | |
| 41281-41283 | Potencia, tension y corriente nominal (kW, V, A) | Int16/UInt16 | 1 | 1000 kW |
| 41295-41298 | Maintenance Timer 1-4 RunHours (h) | Int16 | 1 | 10000 / 10000 / 10000 / -276 |
| 41299-41306 | Maintenance Timer 1-4 Interval (dias) | Int32 | 1 | |
| 41311-41312 | Paradas de emergencia (Num E-Stops) | UInt32 | 1 | |
| 41313-41314 | Paradas por proteccion (Shutdowns) | UInt32 | 1 | |
| 41392 | Log Bout 2 (bit 5 Ready To AMF, campo listoAmf del modelo; leido 0x1038 el 26-10) | Bits | 1 | |
| 41328 | Timer text (List#12: 0 No Timer, 4 Cooling, 13 ReturnDelay...; el valor del temporizador no esta en la lista exportada) | Lista | 1 | |
| 41315-41316 | Consumo total de combustible (l) | UInt32 | 0.1 | |
| 41324 | Modo del controlador (List#8: 0 OFF, 1 MAN, 2 AUTO, 3 TEST) | UInt16 | - | 2 = AUTO |
| 41325 | Estado del deslastre de carga (List#9) | UInt16 | - | 0 |
| 41326 | Estado del motor, Engine state (List#10) | UInt16 | - | 8 = Loaded |
| 41327 | **Estado de los interruptores, Breaker state (List#11)** | UInt16 | - | 4 = ParalOper |
| 41328 | Temporizador en curso (List#12) | UInt16 | - | |
| 41332-41347 | ID String | Texto | - | InteliGen500 G2-2.4.3.6 |
| 41348-41355 | Version de firmware | Texto | - | 2.4.3.6 |
| 41356 | Aplicacion (List#15: 2 GC, 15 AGC, 36 PV) | UInt16 | - | 2 = GC |
| 41376-41377 | Total Running P (kW) | Int32 | 1 | (41377 ~ kW del generador) |
| 41378-41379 | Total Running Q (kVAr) | Int32 | 1 | |
| 41380-41385 | Potencia nominal en marcha / de todos / disponible (kW) | Int32 | 1 | 41383 = 1000 |
| 41391-41403 | Palabras de estado Log Bout 1-13 (bits abajo) | 16 bits | - | |
| 41404 | Estado de la interfaz Ethernet (List#1) | UInt16 | - | 3 = Connected |
| 41445 | Resultado del ultimo correo | UInt16 | - | 30 |
| 41462 | Modo de la interfaz Ethernet (List#2) | UInt16 | - | 4 = 100-FD |
| 41463 | Estado de AirGate (List#17) | UInt16 | - | 1 |
| 41472-41473 | Genset kVAh | Int32 | 1 | |

**Estado de los interruptores (41327, List#11):** 0 Init, 1 BrksOff, 2 IslOper (isla), 3 MainsOper, 4 ParalOper (en
paralelo), 5 RevSync, 6 Synchro, 7 MainsFlt (falla de red), 8 ValidFlt (falla validada), 9 MainsRet (retorno de red),
10 MultIslOp, 11 MultParOp, 12 EmergMan, 13 StrUpSync. Es el registro mas directo para saber si el generador perdio la
red.

**Estado del motor (41326, List#10):** 0 Init, 1 Ready, 2 NotReady, 3 Prestart, 4 Cranking, 5 Pause, 6 Starting,
7 Running, 8 Loaded, 9 Soft unld, 10 Cooling, 11 Stop, 12 Shutdown, 13 Ventil, 14 EmergMan, 15 Soft load, 16 WaitStop,
17 SDVentil, 18 Deexcitation, 19 Warming, 20 Offload.

### Bits de la red y de los interruptores (bit 0 = el de menor peso)
| Registro | Bits | Lectura 22-10 19:13 |
|---|---|---|
| 41058 entradas binarias | 0 Emergency Stop, 1 **MCB Feedback**, 2 GCB Feedback, 3 GCB CAT FB, 4 Remote Start/Stop, 5 Force Island | 0001 1111: coincide con la pantalla Binary INPUTS (foto del usuario) |
| 41060 salidas binarias | 0 Start CAT, 2 GCB ON Coil, 3 GCB OFF Coil, 4 MCB ON Coil, 5 MCB OFF Coil | 0000 0001: coincide con la pantalla Binary OUTPUTS |
| 41391 Log Bout 1 | 1 Starter 1, 2 Fuel Solenoid, 8 Idle/Nominal, 12 GCB Close/Open, 15 GCB UV Coil | 2, 5, 7, 8, 9, 12 y 15 en 1 |
| 41392 Log Bout 2 | 0 MCB Close/Open, 3 MCB UV Coil, 8 Running, 10 Supplying Load, 12 Mains/Bus Healthy | 3, 5, 6, 8, 10, 11 y 12 en 1 |
| 41393 Log Bout 3 | 3 Heartbeat (cambia entre lecturas), 7 Mode AUTO | 3, 4 y 7 en 1 |
| 41394 Log Bout 4 | 4 Alarm Mains/Bus Fail, 5 Alarm Mains/Bus Frequency, 6 Alarm Mains/Bus Voltage, 15 Common Mains Protection | 0 |
| 41400 Log Bout 10 | 8 Any GCB Closed, 10 **MCB Status**, 11 GCB Status, 12 In Synchronism | 3, 8, 10 y 11 en 1 |
| 41403 Log Bout 13 | 0 **Vector Shift Active**, 1 **ROCOF Active**, 3 Initialized | 3 y 14 en 1 |

El resto de los bits, en la lista (tablas Binary#12 a Binary#24). MCB Close/Open (Log Bout 2, bit 0) esta en 0 con el MCB
cerrado: para saber el estado del MCB se usan MCB Feedback y MCB Status, no esa salida.

## Corregido con la lista (2026-10-22)
- **41281** no es el PF del generador: es la potencia nominal, 1000 kW. **41383** es la palabra baja de "Running Nominal
  Power Of All" (1000 kW). Antes se los tomaba como PF 1.000 fijo.
- **41298** no es la potencia de red del controlador: es el Maintenance Timer 4 RunHours (-126 h el 09-10, -276 h el
  22-10; baja con las horas de marcha). La potencia de red del controlador es **41041** (Mains Import P) y su reactiva
  **41042**.
- **41042** no es la corriente de red de una fase: es Mains Import Q (kVAr). La corriente de red (solo L1) es **41040**.
- **41280** (retirado el 2026-10-16 como "Load P") es Voltage Request (% x0.1: 500 = 50.0 %, fijo con el generador
  parado). La carga segun el controlador es **41047-41048** (Load P, Int32) = generador + red; Load Q en 41049-41050.
- **41391-41404** ("probables palabras de estado"): 41391-41403 son las palabras Log Bout 1-13 y 41404 el estado de la
  interfaz Ethernet. 41295-41297 (10000) son los Maintenance Timer 1-3 RunHours, como se suponia.
- El 22-10 al mediodia se tomaron por error 41445 y 41463 como entradas y salidas binarias (coincidian con la foto por
  casualidad): son el resultado del ultimo correo y el estado de AirGate. Las entradas y salidas son 41058 y 41060.

## Lista de alarmas (comun a InteliGen 200 y 500, guia IG200; no esta en la lista de InteliConfig)
Cantidad de alarmas activas en 44215 (direccion 4214) y cada alarma en un bloque de 27 registros desde 44216
(direccion 4215), solo lectura. Verificado el 2026-10-10 en los dos controladores (cantidad 0) y el 2026-10-12 con una
alarma real en el InteliGen 200 del GenPower: cada bloque empieza con 0000 0201 y sigue el texto ASCII. Al texto se le
anteponen "!" = activa ahora y "*" = sin confirmar (sin reset): "*Emergency Stop" con el pulsador ya suelto y sin reset;
"!*Emergency Stop" + "!*E-STOP" con el pulsador presionado. La app sigue cada alarma por su nombre sin esos signos. El
historial interno de eventos del controlador no se publica por Modbus (en ComAp anteriores habia que escribir un indice):
la app arma su propio historial. Las protecciones de red (ROCOF, VectorShift, baja tension/frecuencia) y las aperturas del
MCB del 11-10 no aparecieron en la lista (duran menos de 1 s): quedaron solo en el historial interno. Los 27 eventos de
las fotos del 12-10 se cargaron a mano en el historial de la app: `historial-controlador-2026-10-12.csv` (tipos
"Controlador: ..."). Con la lista de InteliConfig hay otras senales de red que si se pueden leer: 41327, los bits de
41058, 41394, 41400 y 41403, y los maximos 41044 y 41046.

## Reloj del controlador (44201-44204, BCD, solo lectura; no esta en la lista de InteliConfig)
Encontrado el 2026-10-13 con un barrido de 40001-49999 (registros con valor: 41001-41473 con huecos, 44201-44209,
44213-44215, 45001-45004, 45101-45104, 45201). 44201 = hh mm, 44202 = ss 00, 44203 = dd MM, 44204 = aa 00, todo en BCD
(ej. 1320 2600 1310 2600 = 13-10-2026 13:20:26). Va **27 min 39 s adelantado** (13-10); el 11-10 iba entre 26 min 00 s y
26 min 56 s: se adelanta unos 20-50 s por dia. Campo `reloj` del modelo en generador-modelos.json. 44214 cambia
(048A / 068A): sin identificar.

## Retirados
- **41280** (se habia tomado como "Load P", carga segun el controlador): se quito del mapa el 2026-10-16 porque no
  cuadraba. Es Voltage Request (ver "Corregido con la lista"). Lo ya guardado (`carga_kw` en `octubreGenerador`) no se usa.

## Pendientes
- **Diferencia con TR1:** el 09-10 la pantalla del controlador decia "Mains Import P" -139 kW (exportacion) mientras el
  medidor de TR1 (PLC) marcaba importacion de 99-106 kW con PF 0,29-0,40 y 282/348/273 A. El controlador mide la corriente
  de red en una sola fase (41040), asi que su potencia de red no es confiable con fases desequilibradas: revisar con el
  tecnico. Ahora se puede comparar 41041 contra TR1 minuto a minuto. La app usa el medidor principal para la red.
- **Maximos de ROCOF y salto de vector (41046, 41044):** no se sabe cuando se reinician (reset de fallas, cierre del MCB u
  otro). Si se mantienen hasta un reset, una lectura por minuto podria ver una ROCOF que duro menos de 1 s.
- **Estado de los interruptores en una isla:** confirmar en el proximo corte de red que 41327 pasa a MainsFlt / ValidFlt
  / IslOper / MainsRet y que MCB Feedback (41058 bit 1) y MCB Status (41400 bit 10) quedan en 0.
