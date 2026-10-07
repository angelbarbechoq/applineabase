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
- [x] A1. No guardar ceros cuando un medidor no responde (ni en VIP ni en kWh); se registra la falla
      y se deja el hueco (decidido con el usuario: hueco, no repetir el valor anterior, para no
      inventar horas de marcha ni consumo). Inconveniente 1. (2026-10-07)
      Causa real de los ceros: EasyModbus no detecta respuestas de excepcion Modbus (compara un
      byte con signo contra 131), asi que "el medidor no respondio a la pasarela" (0x0B) llegaba
      como registros en cero. Resuelto con j2mod (ver A4), que si informa el codigo
      de excepcion del equipo.
- [x] A2. Una conexion por pasarela por ciclo (no una por medidor) y tiempo de espera de 3 s por
      pedido, sin reintentos dentro del ciclo; si un pedido vence se reconecta antes del medidor
      siguiente, y con 2 vencimientos seguidos la pasarela se da por caida en ese ciclo (no espera
      el timeout de cada medidor restante). Inconveniente 5. (2026-10-07)
- [x] A3. **Pasarelas en paralelo** (2026-10-07): cada pasarela se lee en su propio hilo virtual,
      escala a N pasarelas sin configurar nada; tope de 45 s para la fase de red; todas las
      pasarelas del ciclo con la misma marca de tiempo; el log muestra por pasarela leidos/total
      y ms. Detalle: (son equipos y buses
      RS-485 independientes). Dentro de una misma pasarela los medidores siguen uno tras otro (el
      bus atiende de a un pedido, en paralelo no se gana). Las lecturas se juntan en memoria y se
      guardan en SQLite al final, en un solo hilo, porque la escritura por lotes de
      `DatabaseInitializationService` no admite dos hilos a la vez. Mostrar en el log el tiempo
      por pasarela. Inconveniente 5.
- [x] A4. EasyModbus reemplazado por **j2mod 3.4.0** (Maven Central, mantenida: todas las funciones
      Modbus, codigos de excepcion, validacion de transaccion, TCP/RTU/RTU sobre TCP/UDP) en
      pasarelas, mezcladores, PLC y escritura de IDs. `ModbusTcpConexion` es el unico punto de
      acceso: separa "el equipo contesto con excepcion" (la conexion sigue) de "sin respuesta"
      (reconecta). Se borraron los jar de `lib/`. (2026-10-07)

**Fase B - Catalogo de modelos (necesita los registros del usuario)**
- [x] B1. Catalogo de modelos de medidor (2026-10-07): Configuracion > Modelos de medidor
      (`configuracion/medidores`, ADMIN). Lista cerrada de 35 parametros (9 basicos obligatorios,
      26 de calidad que pueden quedar "No disponible"); por parametro: registro como en el manual,
      tipo de dato (Float32, Int16, UInt16, Int32, UInt32, Int64, UInt64), orden de palabras,
      escala y PF 4 cuadrantes; por modelo: funcion 03/04 y numeracion base 1/0. Alta, edicion,
      duplicado y baja (bloqueada si una linea lo usa). El campo "Medidor" de Configuracion de
      hardware pasa a ser lista cerrada del catalogo. Semilla: PM5110 y PM710 con los 9 basicos
      (confirmados con datos reales: GA752, OrientadoraL2 y HornoL3 por GteWay01); ION8600 y
      PAC1020 creados vacios (faltan sus registros). Los cambios rigen en el ciclo siguiente, sin
      recompilar ni reiniciar.
- [x] B2. Pestana "Probar lectura" (2026-10-07): medidor ya configurado o pasarela + Unit ID, con el
      modelo elegido; muestra cada valor (y el PF decodificado si es 4Q) y el error por parametro,
      sin guardar.
- [x] B3. Lector de pasarelas usando el catalogo (2026-10-07): `LectorMedidorService` agrupa
      registros cercanos (hueco <= 40, bloque <= 100): PM710 en 1 pedido, PM5110 en 2 (antes 5). Si
      un bloque cae en registros inexistentes (0x02/0x03) lee ese bloque por parametro y el modelo
      queda sin agrupar hasta reiniciar. Se borro `PASModbusRegistry`. El PF se guarda como lo
      entrega el medidor (igual que por PLC); el decodificado 4Q va al mostrarlo (fase E).
      Pendiente de datos: registros de ION8600 y PAC1020 (hoy por PLC con escalas propias).
- [x] B4. KWhR = **"KWh Retorno"** (bloque de direcciones del PLC, captura del usuario 2026-10-07).
      Parametro `KWH_RETORNO` en el catalogo; la pasarela lo guarda en la columna KWhR si el modelo
      lo tiene (si no, 0 como antes; su falla no invalida el medidor). Falta el registro de retorno
      de PM5110 y PM710 (no suponer). (2026-10-07)
- [x] B5. ION8600 y PAC1020 cargados (2026-10-07). ION8600: manual "Modbus Protocol and Register
      Map for ION Devices" 70022-0124-00 (mapa por defecto, 26 parametros) cruzado con ION_ADD del
      PLC. PAC1020: PAC_ADD del PLC (10 parametros, Float32). Unidades iguales al historico (kW en
      W, PF del ION en %). Se agregaron tipos Int32/UInt32 Modulo-10000 (formato ION) y las columnas
      enum del catalogo pasaron a varchar (schema.sql) para aceptar valores nuevos.
- [x] B6. Continuidad del historico al migrar (2026-10-07, decidido: manda el historico del PLC):
      - Cada modelo elige que tensiones van al historico: fase-fase o **fase-neutro**. ION8600 =
        fase-neutro (el PLC guarda Vln a/b/c, 40166-40170, ~12.700 V en red de 22 kV); las
        fase-fase (40178-40182) quedan para Calidad de Energia. Los basicos obligatorios dependen de
        esa eleccion.
      - Columnas de tension **posicionales con la convencion del PLC**: 1a tension en VAB, 2a (B-C o
        B-N) en VAC, 3a (C-A o C-N) en VBC (evidencia: PAC_ADD y ION_ADD). La pasarela guardaba C-A
        en VAC y B-C en VBC; desde este cambio guarda como el PLC.
      - Pendiente de confirmar con captura de PM_ADD (bloque del PLC para los PM5110) y, con permiso,
        cruzar VAC/VBC en el historico de GA752, OrientadoraL2 y HornoL3 (277 archivos VIP, 2,6 M
        filas, con copia previa).

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
  (Estado al 2026-10-07 antes de la fase A; ver A1-A4 para como quedo.)
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

PM710 (confirmado 2026-10-07 con datos reales y con el manual 63230-501-209A1, Float32): kWh
1000, kW 1006, PF 1012 (absoluto, sin 4Q), IA/IB/IC 1034/1036/1038, VAB/VBC/VCA 1054/1056/1058.
Calidad (sembrada): kVAh 1002, kVARh 1004, kVA 1008, kVAR 1010, Hz 1020, IN 1040, VAN/VBN/VCN
1060-1064, kW A/B/C 1066-1070, THD I A/B/C 1084-1088, THD V A-N/B-N/C-N 1092-1096, THD V
A-B/B-C/C-A 1098-1102. No tiene PF por fase, desbalances ni energia de retorno.
ION8600: el mapa por defecto solo expone THD **maximos** (40266-40271), no instantaneos; para
tener THD instantaneo hay que configurarlo en los modulos Modbus Slave del medidor (ION Setup).

## Datos pendientes del usuario
(Todo esto se puede cargar desde Configuracion > Modelos de medidor, sin recompilar.)
1. Registros del PM710 para los parametros de calidad.
2. Confirmacion de los registros de calidad del PM5110 (los basicos ya estan confirmados).
3. ION8600 (KWhPlanta1) y PAC1020 (TDGeneradorSA): registros, tipo de dato y escala de los 9
   basicos (por PLC hoy: KWhPlanta1 con enteros, I/10 y PF/100; TDGeneradorSA con kWh/1000).
   Sensores del PLC 192.168.0.3 (TemperaturaAmbiente, TemperaturaAgua, PsiAireP1, PsiAgua,
   BarCompHP): si ese PLC se queda o como se leeran.
4. Pasarelas: cantidad, IP, medidores por pasarela, si se conservan las direcciones (102, 103...),
   velocidad RS-485.
5. Tension nominal por tablero, kVA de transformadores y que cuelga de cada uno, corriente nominal
   por maquina, tarifa (umbral de FP, horario de punta, demanda contratada). Que es `KWhR`.
6. Numero de serie repetido Linea02 / CabezalXTR2.
