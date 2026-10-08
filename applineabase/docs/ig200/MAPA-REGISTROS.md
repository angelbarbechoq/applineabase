# ComAp InteliGen 200 (generador Gen Power, junto a TR2) - mapa de registros Modbus

Controlador: InteliGen 200, SN 18040805, aplicacion "Standard GC". IP 192.168.0.254, Modbus TCP
puerto 502, **Unit ID 1**. Holding registers (funcion 03). **Solo lectura**: la app nunca le
escribe (decidido con el usuario).

Sin acceso a InteliConfig, el mapa se armo el 2026-10-08 comparando lecturas Modbus con la
pantalla del controlador y de la PC (fotos del usuario) y con TR2. Fotos completas de registros:
`foto-generador-parado.csv` (12:30, ya estaba arrancando) y `foto-generador-marcha.csv` (12:32).
Los valores de 32 bits hay que pedirlos enteros (2 registros juntos): pedir la mitad da 0x02.
32768 (0x8000) = dato no disponible.

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

## Pendientes
- 41047 (base 0 1046): cambia mucho entre lecturas (980, 64399, 65423...); quiza angulo de
  sincronismo. 41055-41057: cambian con la carga de la red (1022-1250 / 777-921 / 207-267) pero
  no coinciden con corrientes ni potencia de TR2. 41058 = 96-97 (¿PF de red x100?). 41082 = -104.
  41242-41244 = 10000 (probables contadores de mantenimiento; pantalla "Maintenance Timer 1 = 10000 h").
  41083 = 494-503. Sin evidencia: no usar.
- Kilovatios de red (pantalla 645-687 kW): no se encontraron en 16 ni 32 bits.
- Estado del motor/interruptores y alarmas: probablemente 41002-41003 y registros de bits; falta
  evidencia.
