# ComAp InteliGen 500 (generador Caterpillar, junto a TR1) - mapa de registros Modbus

Controlador: InteliGen 500. IP 192.168.0.201, Modbus TCP puerto 502, **Unit ID 1**. Holding registers
(funcion 03). Red asociada: `Trafo1` (TR1). **Solo lectura**: la app nunca le escribe.

Conectado el 2026-10-09. El mapa del InteliGen 200 **no sirve** (solo coincide RPM): este controlador
tiene otro orden. Se armo con un barrido de 41001-41400 (pestana Registros / scratch `Cat500`), las fotos
de la pantalla (Home, Power, Generator, Mains, Statistics) y 4 lecturas seguidas cada 20 s. Esta en
`C:\LineaBaseX\config\generador-modelos.json` (modelo `InteliGen500`, `confirmado: true`).
Particularidades: contesta **0x04** (no 0x02) en los registros que no existen, y 41003, 41015, 41017-41019,
41043 y 41051 no existen: `maxHueco 0`. 0x8000 = no disponible. 32 bits = palabra alta primero.

En ese momento el generador estaba en marcha, en paralelo con la red ("ParalOper"), con unos 600 kW y
PF 1.000.

## Confirmados
| Registro | Dato | Tipo | Escala | Evidencia (pantalla / lectura) |
|---|---|---|---|---|
| 41001 | RPM | UInt16 | 1 | 1800 / 1798-1800 |
| 41002 | Generador kW total | Int16 | 1 | 609 y 599 / 594-611 |
| 41004-41006 | Generador kW L1, L2, L3 | Int16 | 1 | 197/202/210 / 194-199, 197-202, 204-210 |
| 41007 | Generador kVAr total | Int16 | 1 | -5 / -6..9 |
| 41008-41010 | Generador kVAr L1, L2, L3 (no se guarda) | Int16 | 1 | 1/-9/3 / 0..5, -9..-4, 3..8 |
| 41011 | Generador kVA total | UInt16 | 1 | 610 / 596-613 |
| 41012-41014 | Generador kVA L1, L2, L3 (no se guarda) | UInt16 | 1 | 197/202/210 |
| 41020 | Frecuencia generador | UInt16 | 0.1 | 60.046 Hz / 600 |
| 41021-41023 | Generador V L1-N, L2-N, L3-N | UInt16 | 1 | 267/268/268 / 264-266 |
| 41024-41026 | Generador V L1-L2, L2-L3, L3-L1 | UInt16 | 1 | 464/464/462 / 459-461 |
| 41027-41029 | Generador corriente L1, L2, L3 | UInt16 | 1 | 724/735/766 / 740-794 |
| 41033 | Frecuencia red | UInt16 | 0.1 | 59.952 Hz / 600 |
| 41034-41036 | Red V L1-N, L2-N, L3-N | UInt16 | 1 | 266/268/267 / 264-267 |
| 41037-41039 | Red V L1-L2, L2-L3, L3-L1 | UInt16 | 1 | 464/464/462 / 459-462 |
| 41052 | Tension de bateria (Ubat) | UInt16 | 0.1 | Analog Inputs 27.9 V / 279 |
| 41053 | Entrada D+ (no se guarda) | UInt16 | 0.1 | 0.0 V / 0 |
| 41280 | Carga del tablero (Load P) segun el controlador | Int16 | 1 | 471 / 476-499; = generador + red del controlador |
| 41284-41285 | Genset kWh | UInt32 | 1 | 52964 / 53014-53078, sube ~600 kW |
| 41286-41287 | Genset kVArh | UInt32 | 1 | 13338 / 13338 |
| 41288-41289 | Red (Mains) kWh | UInt32 | 1 | 5153051 / 5153055-5153060 |
| 41290-41291 | Red (Mains) kVArh | UInt32 | 1 | 1245847 / 1245861-1245878 |
| 41292-41293 | Horas de marcha | UInt32 | 0.1 | 124.9-125.0 h / 1250-1251 |
| 41294 | Cantidad de arranques | UInt16 | 1 | 121 / 121 |

## Resueltos sin registro propio
- **PF del generador:** en paralelo el controlador regula a PF 1.000, asi que 41281 y 41383 valen 1000 fijo y
  no se puede saber cual es. La app lo **calcula** con kW / kVA (dos registros confirmados): 599 / 601 = 0.997.
  41016 = 82 = letra "R" (caracter de la carga que la pantalla muestra junto al PF: R, L o C).
- **Aceite y temperatura del motor:** la pantalla "Analog Inputs" del controlador solo tiene Ubat y D+: el
  InteliGen 500 no los mide (en el Caterpillar los mide su propio panel o su computadora de motor). Para
  tenerlos haria falta conectar la computadora del motor al InteliGen 500 por CAN J1939; si se hace, se agregan
  al mapa sin recompilar. La pantalla del generador muestra "no lo mide este controlador".

## Lista de alarmas (comun a InteliGen 200 y 500, guia IG200)
Cantidad de alarmas activas en 44215 (direccion 4214) y cada alarma en un bloque de 27 registros desde 44216
(direccion 4215), solo lectura. Verificado el 2026-10-10 en los dos controladores (cantidad 0). Falta ver como viene
el texto con una alarma real: la app lo decodifica como ASCII. El historial interno de eventos del controlador no se
publica por Modbus (en ComAp anteriores habia que escribir un indice): la app arma su propio historial.

## Pendientes
- **Potencia de red del controlador:** 41298 = -126 fijo durante un minuto mientras carga - generador iba
  de -107 a -125 (pantalla "Mains Import P" -139). No se usa.
- **Diferencia con TR1:** el controlador indica exportacion (~120 kW) y mide la corriente de red en una sola
  fase (181 A); el medidor de TR1 (PLC) marcaba importacion de 99-106 kW con PF 0,29-0,40 y 282/348/273 A.
  El TC de red del controlador mide otro punto o una sola fase: revisar con el tecnico. La app usa el medidor
  de TR1 para la red.
- Otros con valor: 41016=82, 41030-41032, 41040-41042 (41042 ~ corriente de red de una fase, 151-195 A),
  41044-41046, 41047-41050 (32 bits), 41052-41061, 41271-41279, 41295-41297 = 10000 (probables contadores de
  mantenimiento), 41377 (~kW), 41391-41404 (probables palabras de estado).
