# Plan: modulo de Calidad de Energia y migracion de PLC a pasarelas PAS600L

Estado: **plan acordado, sin implementar** (2026-10-03). Se avanza paso a paso, cuando el usuario
lo indique. No tocar el lector actual de PLC ni de pasarelas hasta que el usuario lo pida.

## Restricciones del usuario
- Migracion **un medidor a la vez**, empezando por uno de poco impacto. El usuario avisa cuando seguir.
- En paralelo corre otra aplicacion del usuario (NetBeans) que los tecnicos usan para registrar y
  validar lecturas; cada cambio de lectura tambien lo hace el usuario alla. No romper lo que hoy se
  guarda (kWh y VIP) ni sus formatos.
- Cada modelo nuevo de medidor: **pedir al usuario los registros** de cada parametro, nunca
  suponerlos. La pantalla de modelos exige los registros al crear un modelo.

## Hallazgos (datos de octubre 2026)
- PF de los PM5110 via PLC se guarda **sin decodificar** (formato 4 cuadrantes): hay valores 1.542
  (Linea02) y -1.999 (Trafo1). Decodificar: valor > 1 -> 2 - valor; valor < -1 -> -2 - valor.
  Los reportes deben corregir tambien el historico.
- `PASReaderService` pone en cero los arreglos antes de leer y guarda ceros si un medidor falla
  (VAB minimo 0 en GA752, OrientadoraL2, HornoL3).
- Tableros de ~460-480 V (falta la tension nominal exacta por tablero).
- Linea02 y CabezalXTR2 tienen el mismo numero de serie (540060660837) en `linea-id-config.json`.

## Pasos
1. **Catalogo de modelos de medidor** (H2, pantalla admin en Configuracion): lista cerrada de
   parametros; por modelo, registro + tipo de dato + escala + codificacion (PF 4Q) por parametro;
   basicos obligatorios, de calidad pueden ser "No disponible". Boton "Probar lectura" (pasarela,
   Unit ID, modelo) para comparar con la pantalla del medidor. Semilla PM5110 y PM710 con registros
   confirmados por el usuario.
2. **Lector de pasarelas** usando el catalogo: lectura en bloques (~3 por medidor), pasarelas en
   paralelo, PF decodificado, sin guardar ceros ante falla. Lo basico se sigue guardando igual
   (kWh y VIP); lo de calidad en una tabla nueva por maquina en un archivo mensual aparte.
3. **Migracion por medidor**: cambiar solo `nombrePLC` del medidor en `linea-id-config.json` a la
   pasarela. PLC y pasarela conviven durante la transicion.
4. **Modulo Calidad de Energia** (pantallas y KPI, luego resumen diario precalculado y alarmas de
   desbalance y tension fuera de rango).

## Parametros y registros PM5110 (de memoria, A CONFIRMAR por el usuario)
Direccion como en el manual (la app resta 1). Float32 salvo indicacion.

| Parametro | Registro |
|---|---|
| Corriente A, B, C, N | 3000, 3002, 3004, 3006 |
| Tension AB, BC, CA | 3020, 3022, 3024 |
| Tension AN, BN, CN | 3028, 3030, 3032 |
| Desbalance corriente peor fase / tension L-L peor fase | 3018 / 3044 |
| kW A, B, C, Total | 3054, 3056, 3058, 3060 |
| kVAR Total / kVA Total | 3068 / 3076 |
| PF A, B, C, Total (4Q codificado) | 3078, 3080, 3082, 3084 |
| Frecuencia | 3110 |
| kWh / kVARh / kVAh entregado | 2700 / 2708 / 2716 |
| THD I A, B, C | 21300, 21302, 21304 |
| THD V AB, BC, CA / AN, BN, CN | 21322, 21324, 21326 / 21330, 21332, 21334 |

PM710 (en uso hoy): kWh 1000, I 1034, V 1054, kW 1006, PF 1012. Falta el resto.

## Datos pendientes del usuario
1. Registros del PM710 para los parametros de calidad.
2. Confirmacion de los registros del PM5110.
3. ION8600 (KWhPlanta1) y PAC1020 (TDGeneradorSA): registros si pasan a pasarela.
   Sensores del PLC 192.168.0.3 (TemperaturaAmbiente, TemperaturaAgua, PsiAireP1, PsiAgua,
   BarCompHP): si ese PLC se queda o como se leeran.
4. Pasarelas: cantidad, IP, medidores por pasarela, si se conservan las direcciones (102, 103...),
   velocidad RS-485.
5. Tension nominal por tablero, kVA de transformadores y que cuelga de cada uno, corriente nominal
   por maquina, tarifa (umbral de FP, horario de punta, demanda contratada). Que es `KWhR`.
6. Numero de serie repetido Linea02 / CabezalXTR2.
