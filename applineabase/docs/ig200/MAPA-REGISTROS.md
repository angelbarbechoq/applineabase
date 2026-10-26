# ComAp InteliGen 200 (generador Gen Power, junto a TR2) - mapa de registros Modbus

Controlador: InteliGen 200, SN 18040805, aplicacion "Standard GC". IP 192.168.0.254, Modbus TCP
puerto 502, **Unit ID 1**. Holding registers (funcion 03). **Solo lectura**: la app nunca le
escribe (decidido con el usuario).

Sin acceso a InteliConfig, el mapa se armo el 2026-10-08 comparando lecturas Modbus con la
pantalla del controlador y de la PC (fotos del usuario) y con TR2. Fotos completas de registros:
`foto-generador-parado.csv` (12:30, ya estaba arrancando) y `foto-generador-marcha.csv` (12:32).
Los valores de 32 bits hay que pedirlos enteros (2 registros juntos): pedir la mitad da 0x02.
32768 (0x8000) = dato no disponible.

Desde 2026-10-09 la app no tiene este mapa en el codigo: lo lee de
`C:\LineaBaseX\config\generador-modelos.json` (modelo `InteliGen200`), que se edita sin recompilar.

## Confirmados (lectura = pantalla)
| Registro | Base 0 | Dato | Tipo | Escala | Unidad | Evidencia |
|---|---|---|---|---|---|---|
| 41001 | 1000 | RPM motor | UInt16 | 1 | rpm | 1802 = 1802 |
| 41036 | 1035 | Frecuencia generador | UInt16 | 0.1 | Hz | 601 = 60,1 Hz (y 579 con 1735 rpm) |
| 41037-41039 | 1036-1038 | Generador V L1-N, L2-N, L3-N | UInt16 | 1 | V | 266/266/266 |
| 41040-41042 | 1039-1041 | Generador V L1-L2, L2-L3, L3-L1 | UInt16 | 1 | V | 461/460/460 |
| 41048 | 1047 | Frecuencia red | UInt16 | 0.1 | Hz | 600 = 60,0 Hz |
| 41049-41051 | 1048-1050 | Red V L1-N, L2-N, L3-N | UInt16 | 1 | V | 259/258/259 vs 260/260/259 |
| 41052-41054 | 1051-1053 | Red V L1-L2, L2-L3, L3-L1 | UInt16 | 1 | V | 449/447/448 vs 451/449/450; vs TR2 444-447 |
| 41084 | 1083 | Tension de bateria | UInt16 | 0.1 | V | 272 = 27,2 V |
| 41086 | 1085 | Presion de aceite | UInt16 | 0.1 | bar | 66 = 6,6 bar |
| 41087 | 1086 | Temperatura refrigerante | Int16 | 1 | °C | 51 vs 49 (subiendo 45-47-51) |
| 41231-41232 | 1230-1231 | Genset kWh | UInt32 | 1 | kWh | 525297 = 525.297,0 |
| 41233-41234 | 1232-1233 | Genset kVArh | UInt32 | 1 | kVArh | 203922 = 203.922,0 |
| 41235-41236 | 1234-1235 | Red (Mains) kWh | UInt32 | 1 | kWh | 38580072 (pantalla Statistics); sube ~580 kW, coherente con la red |
| 41237-41238 | 1236-1237 | Red (Mains) kVArh | UInt32 | 1 | kVArh | 9857303 (pantalla) vs 9857326 leido minutos despues |
| 41239-41240 | 1238-1239 | Horas de marcha | UInt32 | 0.1 | h | 11322 = 1.132,2 h |
| 41241 | 1240 | Cantidad de arranques | UInt16 | 1 | - | 708 = 708 (12:32); 710 a las 12:46 (arranques de prueba del tecnico, confirmado por el usuario) |

## Confirmados con carga (2026-10-08, `foto-generador-carga.csv`): por suma de fases, contra TR2 (cuando el
## generador toma ~610 kW, TR2 baja de ~850 a ~215 kW; corrientes igual) y contra el contador (13:14-13:53:
## +389 kWh con ~600 kW durante 39 min = 390 kWh)
| Registro | Base 0 | Dato | Tipo | Escala | Unidad | Evidencia |
|---|---|---|---|---|---|---|
| 41020 | 1019 | Generador kW total | Int16 | 1 | kW | 604 = 200+209+195 |
| 41021-41023 | 1020-1022 | Generador kW L1, L2, L3 | Int16 | 1 | kW | 200/209/195 |
| 41024 | 1023 | Generador kVAr total | Int16 | 1 | kVAr | 202 = 61+72+69 |
| 41025-41027 | 1024-1026 | Generador kVAr L1, L2, L3 | Int16 | 1 | kVAr | 61/72/69 |
| 41028 | 1027 | Generador kVA total | UInt16 | 1 | kVA | 639 = 210+222+207 |
| 41029-41031 | 1028-1030 | Generador kVA L1, L2, L3 | UInt16 | 1 | kVA | 210/222/207 |
| 41032 | 1031 | Generador PF total | Int16 | 0.01 | - | 95 = 604/639 = 0,945 |
| 41033-41035 | 1032-1034 | Generador PF L1, L2, L3 | Int16 | 0.01 | - | 95/94/94 |
| 41043-41045 | 1042-1044 | Generador corriente L1, L2, L3 | UInt16 | 1 | A | 753/800/746 (en vacio 0) |
Con carga: RPM 1800, 60,0 Hz, V L-L 458/456/458, kWh +2 en el momento de la foto.

## Reloj del controlador (44201-44204, BCD, solo lectura)
Igual que el InteliGen 500 (ver `docs/ig500/MAPA-REGISTROS.md`): 44201 = hh mm, 44202 = ss 00, 44203 = dd MM,
44204 = aa 00. El 2026-10-13 iba **3 min 53 s adelantado** respecto de la PC. Campo `reloj` del modelo.

## Lista Modbus de InteliConfig (2026-10-26)
`lista-modbus-inteliconfig-2026-10-26.txt` (exportada por el usuario; registro = 40001 + direccion de la lista).
Los 33 registros de arriba coinciden uno a uno. Agregados al modelo (lectura del 26-10, generador parado, red
normal, modo AUTO): 41090 entradas binarias (bit 0 GCB Feedback, bit 1 MCB Feedback; leido 0x122: MCB cerrado, GCB
abierto, presion de aceite), 41260 estado de los interruptores (List#4, mismos codigos que el InteliGen 500; leido
3 = MainsOper), 41301 Log Bout 4 (bit 8 AL Mains Fail), 41344 modo del controlador (0 OFF, 1 MAN, 2 AUTO, 3 TEST;
leido 2). Para el tablero de Estado actual (2026-10-26) tambien se leen 41259 estado del motor (List#3: Ready,
NotReady, Running, Loaded, Cooling, Shutdown...; leido 2 = NotReady, con E-STOP activo), 41092 salidas binarias,
41242 contador de mantenimiento 1, 41228 potencia nominal y 41230 corriente nominal; y desde el mismo dia 41299 Log
Bout 2 (bit 5 Ready To AMF = campo `listoAmf` del modelo; leido 0x2038: Ready, Ready To AMF, Mains Healthy),
41249-41250 paradas de emergencia y 41251-41252 paradas por falla (Shutdowns), UInt32 (leidos 6 y 21).
Disponibles sin usar: resto de Log Bout 1-11 (41298-41308: bit 7 Not In Auto, bit 12 Generator Healthy, bit 13
Mains Healthy de Log Bout 2; alarmas AL ...), 41243-41244 contadores de mantenimiento 2 y 3, 41253-41254 consumo total
de combustible (L; leido 0: el motor no lo informa). Temporizadores (cuenta regresiva de la pantalla del
controlador, en el tablero desde el 2026-10-26 como TEMPORIZADOR y TEMPORIZADOR_VALOR): 41261 Timer text (List#5: 0 No Timer, 4 Cooling, 11 MCB Close, 13 ReturnDelay, 14 Start Delay...) y
41265 Timer Value (s); ajustes 43070 Cooling Time (leido 180 s), 43152 Emergency Start Delay (15 s), 43153 Mains
Return Delay (60 s), 43154 MCB Close Delay (x10, 1,0 s). No tiene medida de ROCOF ni de salto de vector (solo los
ajustes de esas protecciones, 43167-43172).

## Pendientes
- Aclarados con la lista: 41047 = angulo de deslizamiento (Slip Angle); 41055 = corriente de red L1 (una sola
  fase), 41056 = kW importados de la red, 41057 = kVAr de red, 41058 = PF de red (x100); 41082 = salida del
  regulador de tension (V x1000), 41083 = pedido de tension (% x10); 41242-41244 = contadores de mantenimiento.
  La potencia de red del controlador sigue sin usarse (no coincide con TR2).
- Kilovatios de red (pantalla 645-687 kW): no se encontraron en 16 ni 32 bits. 2026-10-16: barrido completo de
  40001-49999 (784 registros con valor: 41001-41095, 41104, 41145-41151, 41160, 41169, 41226-41393, 43001-43283, 43316,
  43373-43380, 43437-43440, 43505, 43522-43723, 44201-44215) con 4 fotos cada 60 s contra TR2 (635-834 kW): ninguno lo
  sigue, asi que el GenPower no tiene "consumo del transformador segun el controlador" (en el Caterpillar se retiro por
  no coincidir). 41055-41057 (repetidos en 41280-41282) cambian con la carga (749/567/174 ... 965/714/207): parecen
  corrientes de red con una fase muy baja, como el TC de red del Caterpillar. 41058 = 96-98.
