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
- [x] B7. **Revision 2026-10-08 (decidido con el usuario): la migracion guarda lo correcto segun
      los manuales, no los errores del PLC.** Reemplaza a B6:
      - VIP por pasarela: VAB = A-B, VAC = A-C (C-A), VBC = B-C (por nombre de columna). El PLC
        guarda B-C en VAC y C-A en VBC: al migrar un medidor del PLC esas columnas pasan a su
        significado correcto desde esa fecha. El historico de GA752/OrientadoraL2/HornoL3 ya estaba
        bien por nombre; solo quedaron cruzadas las filas del 2026-10-07 17:21 al reinicio con este
        cambio (corregir con permiso).
      - ION8600: VIP con tensiones fase-fase (40178-40182), kW y PF -1..1 (el PLC: Vln, W y %).
        PAC1020: kW; KWhR vacio (0) porque no mide energia de retorno (2805 es reactiva).
      - PF en VIP por pasarela: valor real (4Q decodificado). `FactorPotenciaUtil` interpreta los
        tres formatos del historico: 0-1, 4Q (1-2 -> 2 - v) y % (> 2 -> /100). Antes dividia por
        100 todo valor > 1: los PM5110 por PLC en zona capacitiva (ej. Linea03 1.055) salian con PF
        0.01 en graficos y en la alarma de PF bajo.
      - Las marcas "historico en W / PF en %" y "tensiones fase-neutro" quedan como opcion para un
        caso especial; ningun modelo las usa.
- [x] B6. (Reemplazado por B7) Continuidad del historico al migrar (2026-10-07, decidido: manda el historico del PLC):
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
- [x] E1. Archivo de calidad (2026-10-08): `C:\LineaBaseX\{anio}\{mes}\{mes}Calidad` (SQLite), solo
      mensual, una tabla por maquina, una fila por minuto con la misma marca de tiempo que el VIP.
      Columnas: los 34 parametros del catalogo salvo las energias, en unidades estandar (kW, kVAR,
      kVA, PF real -1..1 ya decodificado de 4Q, %), nulables (vacio = el modelo no lo tiene o fallo,
      nunca 0), + `DESBALANCE_I_CALCULADO` / `DESBALANCE_V_CALCULADO` (1 = calculado por la app:
      "peor fase" como el PM5110, vacio si la maquina esta parada). Se lee en los mismos pedidos
      agrupados que el VIP. Decisiones con el usuario: cada minuto completo (~640 MB/mes con ~45
      medidores, 245 GB libres), resumenes de norma (10 min, maximos, % en limites) al leer, archivo
      aparte para no tocar lo que lee NetBeans. THD no calculable sin el medidor (ION8600 sin THD
      instantaneo en su mapa por defecto; PAC1020 no mide THD).
      Catalogo en unidades estandar para todos; marcas por modelo "historico VIP en W" y "PF en %"
      (ION8600 ambas, PAC1020 W) para que el VIP siga igual que por PLC.
- [x] E2. Pantalla **Calidad de Energia** (2026-10-08, ruta `calidad`, menu propio, alcance como
      alarmas: ADMIN y zona Mantenimiento). Pestanas: **Estado actual** (ultimo minuto por maquina,
      7 indicadores con color y limite al pasar el mouse; * = desbalance calculado), **Historico**
      (maquina + indicador + rango hasta 2 meses: promedios de 10 min por fase y linea del limite),
      **Cumplimiento** (por mes: % de bloques de 10 min dentro del limite y peor bloque) y **Limites**
      (ADMIN: limites editables en H2 y tension nominal fase-fase por maquina; 460 V por defecto).
      Indicadores: THD tension (max de fases; limite 8 % hasta 1 kV, 5 % de 1 a 69 kV), THD
      corriente (20 %), desbalance tension (2 %) y corriente (10 %), tension (+/-5 aviso, +/-10 fuera),
      frecuencia (+/-1 %), PF (>= 0.92). Aviso = 80 % del limite. Defaults propuestos por normas; el
      usuario no los confirmo todavia (se editan en Limites).
- [ ] E3. Resumen diario precalculado y alarmas de desbalance y tension fuera de rango.
      (2026-10-08: se hara en una sesion aparte, junto con la fase G. Propuesta ya presentada: alarmas
      de tension fuera de rango y frecuencia (urgentes), desbalances y THD (informativas), evaluadas
      sobre promedios de 10 min con los mismos limites de la pestana Limites. El "resumen diario
      precalculado" se descarta: los calculos al leer son instantaneos.)

**Fase G - Generador Gen Power (InteliGen 200), mantenimiento basado en condicion**
Acordado con el usuario el 2026-10-08. Mapa de registros en `docs/ig200/MAPA-REGISTROS.md`
(solo lectura, funcion 03, Unit ID 1, 192.168.0.254).
- [x] G1. Guardado segun estado del generador (2026-10-08, `com.example.generador`: `GeneradorReaderService`
      en el ciclo de 1 min, `GeneradorAlmacen`; config en `C:ineabasexnfiggenerador-config.json`;
      lecturas en `{mes}generador`, arranques en `c:ineabasexgeneradorrranques`; solo los 17
      valores confirmados, 4 pedidos de lectura funcion 03; nunca escribe):
      - En marcha (RPM > 0): todo cada minuto (tensiones, frecuencia, corrientes, kW, PF, RPM,
        aceite, refrigerante, bateria).
      - Parado: cada 15 min solo lo que dice si esta listo para arrancar: tension de bateria,
        temperatura de refrigerante (precalentador), modo OFF/MAN/AUTO, alarmas y si el controlador
        responde.
      - Un registro por arranque: inicio, fin, duracion, kWh generados, carga maxima, prueba o corte
        de red (contadores del controlador: horas 41239, kWh 41231, arranques 41241).
      - No se guardan las tensiones de red del controlador (ya las mide TR2 por el PLC).
- [ ] G2. Pantalla del generador: estado actual, historial de arranques, tendencia de bateria y
      refrigerante.
- [ ] G3. Alarmas del generador (con E3): bateria baja/en descenso, refrigerante frio con el
      generador parado (precalentador), arranque fallido, controlador sin comunicacion, aceite y
      temperatura fuera de rango en marcha.
- [ ] G4. Confirmar corrientes, kW, kVAr y PF del generador cuando tome carga (hoy 0, sin carga).

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

## Parametros y registros PM5110 (CONFIRMADO 2026-10-07 con "PM51xx_PM53xx_PMC Register List v2011_v2021 R01", sembrado en el catalogo)
Ademas: energia de retorno (columna KWhR) = 2702 "Active Energy Received", Float32. PF A/B/C/total
en formato 4Q_FP_PF. Desbalances "Worst": corriente 3018, tension L-L 3044.
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

## Datos pendientes del usuario (actualizado 2026-10-08)
Resueltos: registros de PM5110, PM710, ION8600 y PAC1020 (manuales), KWhR (= energia de retorno;
en el PAC1020 era reactiva), orden de columnas (se guarda segun manual, no hace falta PM_ADD).
1. PLC: cambiar `PAC_ADD[7]` de 40020 a 40042 (F-12, potencia del generador a un tercio).
2. ION8600: THD instantaneo (programarlo en ION Setup) o dejarlo vacio.
3. Calidad: confirmar limites propuestos y cargar la tension nominal real por tablero (pestana
   Limites). Tarifa: umbral de PF, horario de punta, demanda contratada.
4. Sensores del PLC 192.168.0.3 (TemperaturaAmbiente, TemperaturaAgua, PsiAireP1, PsiAgua,
   BarCompHP): como se leeran si ese PLC se retira.
5. Pasarelas (C3): cantidad, IP, medidores por pasarela, direcciones (102, 103...), velocidad RS-485.
6. `linea-id-config.json` (C1): BarCompHP en PLC1 y PLC3, serie repetida Linea02/CabezalXTR2, lineas
   en PLC5.
7. NetBeans (C2): como se sincroniza su lista de IDs del PLC con esta app.
8. InteliGen 200 (192.168.0.254, generador Gen Power, junto a TR2). Modbus TCP ACTIVO desde 2026-10-08
   (Unit ID 1, 690 registros en 41001-41391, 43001-43723, 44205, 44215; SOLO LECTURA, funcion 03).
   Confirmado contra TR2: 41052/41053/41054 = tensiones fase-fase de red (444/443/444 V vs TR2
   444-447 V); 41048 = 600 (probable frecuencia x10). 32768 = no disponible. Sin InteliConfig: se
   identifican los demas comparando `docs/ig200/foto-generador-parado.csv` con una foto igual con
   el generador en marcha + fotos de la pantalla del controlador que manda el usuario.
   2026-10-08: 13 registros confirmados (RPM, frecuencias, tensiones red/generador, bateria, aceite,
   refrigerante, kWh, kVArh, horas) en `docs/ig200/MAPA-REGISTROS.md`. Faltan corrientes/kW/PF del
   generador: confirmar cuando tome carga.
   (Antes: puerto 502 cerrado; abiertos
   23 y 80) y exportar la lista de registros desde InteliConfig ("Generate Cfg Image" > Modbus).
