# Plan: modulo de Calidad de Energia y migracion de PLC a pasarelas PAS600L

Estado: **plan acordado, en curso** (2026-10-07). Se avanza punto por punto del checklist y se
marca [x] al cerrar cada uno. No tocar el lector actual de PLC ni de pasarelas hasta que el usuario lo pida.

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

## Checklist (se marca [x] al cerrar cada punto)

**Fase A - Lector de pasarelas confiable (no depende de datos del usuario)**
- [ ] A1. No guardar ceros cuando un medidor no responde (ni en VIP ni en kWh); se registra la falla
      y se deja el hueco. Inconveniente 1.
- [ ] A2. Una conexion por pasarela por ciclo (no una por medidor) y tiempo de espera corto por
      pedido, para que un medidor caido no frene a los demas. Inconveniente 5.
- [ ] A3. **Pasarelas en paralelo:** cada pasarela se lee en su propio hilo (son equipos y buses
      RS-485 independientes). Dentro de una misma pasarela los medidores siguen uno tras otro (el
      bus atiende de a un pedido, en paralelo no se gana). Las lecturas se juntan en memoria y se
      guardan en SQLite al final, en un solo hilo, porque la escritura por lotes de
      `DatabaseInitializationService` no admite dos hilos a la vez. Mostrar en el log el tiempo
      por pasarela. Inconveniente 5.

**Fase B - Catalogo de modelos (necesita los registros del usuario)**
- [ ] B1. Catalogo de modelos de medidor (H2, pantalla admin en Configuracion): lista cerrada de
      parametros; por modelo, registro + tipo de dato + escala + codificacion (PF 4Q). Basicos
      obligatorios, de calidad pueden ser "No disponible". Semilla PM5110 y PM710 confirmados.
- [ ] B2. Boton "Probar lectura" (pasarela, Unit ID, modelo) para comparar con la pantalla del medidor.
- [ ] B3. Lector de pasarelas usando el catalogo: lectura en bloques (~3 pedidos por medidor) y
      escalas por modelo (reemplaza `PASModbusRegistry` y los casos especiales por nombre).
      Inconvenientes 7 y 8.
- [ ] B4. Definir KWhR (que es, que registro) y llenarlo desde la pasarela. Inconveniente 6.

**Fase C - Preparar la migracion**
- [ ] C1. Ordenar `linea-id-config.json`: BarCompHP duplicado, serie repetida Linea02/CabezalXTR2,
      lineas en PLC5. Inconveniente 9.
- [ ] C2. Confirmar con el usuario como se sincroniza la lista de IDs con NetBeans. Inconvenientes 2 y 3.
- [ ] C3. Datos de pasarelas: cantidad, IP, medidores por pasarela, velocidad RS-485. Inconveniente 4.

**Fase D - Migracion, un medidor a la vez (el usuario avisa cuando seguir)**
- [ ] D1. Primer medidor de poco impacto, con la secuencia de abajo.
- [ ] D2... uno por medidor, se agrega una linea al migrar cada uno.

**Fase E - Modulo Calidad de Energia**
- [ ] E1. Tabla de calidad por maquina en un archivo mensual aparte (lo basico sigue igual).
- [ ] E2. Pantallas y KPI.
- [ ] E3. Resumen diario precalculado y alarmas de desbalance y tension fuera de rango.

## Como se lee hoy (revisado 2026-10-07)
- **PLC** (`PLCDataAcquisitionService`): el PLC es el maestro RS-485 y sondea los medidores cuyos
  IDs le escribe `PLCIdWriterService` ([99, N, id1..idN] en el offset 400, en el **orden** de
  `linea-id-config.json`). La app lee 10 bloques (KWh, VAB, VAC, VBC, IA, IB, IC, PW, PF, KWhR) y
  asigna por **posicion**: el medidor i-esimo de la lista es la ranura i del PLC. Casos especiales
  por nombre: KWhPlanta1 (enteros, I/10, PF/100) y TDGeneradorSA (KWh/1000). PLC3 ademas lee los
  sensores en el registro 422.
- **Pasarela** (`PASReaderService` + `PASModbusRegistry`): la app es el maestro; por cada medidor
  abre una conexion TCP con Unit ID = campo `id`, y hace 5 lecturas (KWh, V, I, KW, PF). Solo
  conoce PM5110 y PM710. Las pasarelas se leen una tras otra, y los medidores tambien.
- GA752 (PM5110) ya se lee por GteWay01: los registros basicos del PM5110 por pasarela estan
  probados con datos reales.

## Inconvenientes de la migracion (por orden de gravedad)
1. **Ceros ante falla:** antes de leer se ponen en cero V, I, kW, PF y si el medidor no responde se
   guarda una fila VIP en cero (el comentario del codigo dice lo contrario). Con kW = 0 el
   horometro cuenta la maquina como **parada**, la alarma de Detencion puede dispararse y el
   recalculo de horas hereda el error. Hoy afecta a 3 medidores; con todo migrado, a cualquiera.
   **Hay que corregirlo antes del primer medidor.**
2. **Orden de las ranuras del PLC:** al sacar un medidor de un PLC, las ranuras siguientes se
   corren. Si la app y el PLC no se actualizan juntos, los datos se guardan en la tabla de **otra
   maquina**. Secuencia obligatoria: editar JSON -> escribir IDs al PLC -> reiniciar la app (la
   lista se lee solo al arrancar).
3. **Programa NetBeans:** tambien escribe IDs al PLC (`actualizaID`). Si su lista difiere, pisa la
   de esta app y vuelve el problema 2. Las dos listas deben cambiarse al mismo tiempo.
4. **Cableado:** un bus RS-485 admite un solo maestro. El medidor se mueve fisicamente al bus de la
   pasarela; su direccion Modbus debe ser unica en ese bus y coincidir con `id` (o cambiar `id`),
   y la velocidad/paridad del medidor debe coincidir con la del puerto de la pasarela.
5. **Tiempo de ciclo:** lectura secuencial, conexion nueva por medidor, 5 pedidos cada uno, timeout
   5 s. Cada medidor caido suma ~5 s; con ~40 medidores y varios caidos el ciclo pasa de 60 s y el
   guard saltea lecturas (huecos de 1-2 min). Hace falta lectura en bloque, una conexion por
   pasarela y pasarelas en paralelo (paso 2).
6. **Columna KWhR:** el PLC la llena; la pasarela guarda 0. Se ve en Consulta de Datos, CSV y en el
   programa NetBeans. Definir que es y que registro le corresponde.
7. **Medidores especiales:** KWhPlanta1 (ION8600) y TDGeneradorSA (PAC1020) tienen escalas propias
   escritas en el lector del PLC; el lector de pasarela no las tiene. No migrarlos sin el catalogo
   de modelos (paso 1).
8. **PF:** el PLC y la pasarela entregan el mismo valor crudo 4 cuadrantes del PM5110, asi que no
   cambia al migrar; el decodificado se resuelve aparte (ver Hallazgos). PM710: confirmar formato.
9. **Datos de config:** `BarCompHP` figura como medidor de PLC1 (ocupa una ranura) y ademas como
   sensor de PLC3: dos fuentes escriben la misma tabla. Linea02/CabezalXTR2 con el mismo numero de
   serie. Lineas en `PLC5`, que no existe (se ignoran). Revisar antes de mover ranuras de PLC1.
10. **Cortes en el corte:** el contador kWh es del medidor, no hay salto. Si se pierde un ciclo en el
    cambio, el kWh se recupera solo; el VIP de ese minuto no.

## Secuencia por medidor (cuando el lector este listo)
1. Anotar lectura de pantalla del medidor (kWh, V, I, kW, PF).
2. Recablear al bus de la pasarela; fijar direccion y velocidad.
3. "Probar lectura" desde la pantalla de modelos y comparar con la pantalla del medidor.
4. Cambiar `nombrePLC` en `linea-id-config.json` (y en NetBeans), escribir IDs al PLC de origen,
   reiniciar la app.
5. Verificar 2-3 ciclos: kWh continuo, VIP sin ceros, horometro sin cortes, las demas maquinas de
   ese PLC con sus valores de siempre.
6. Plan de vuelta atras: restaurar el JSON y los IDs del PLC y recablear.

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
