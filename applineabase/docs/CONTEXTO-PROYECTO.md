# Contexto del proyecto LineaBaseX

Documento de referencia para sesiones de Claude Code: resume que existe hoy en la
aplicacion, como esta organizada y que decisiones ya se tomaron, para no tener que leer
todo el codigo cada vez que se agrega un modulo. Se importa desde `CLAUDE.md`, asi que se
carga solo al abrir una sesion.

**Mantenimiento de este archivo:** al cerrar cada modulo o cambio importante, actualizar la
seccion correspondiente (y la seccion 12 de pendientes). Si algo de aqui contradice el
codigo, manda el codigo y hay que corregir este archivo.

Ultima actualizacion: 2026-10-22. Las secciones marcadas **[resumen]** son una vista de alto
nivel que no se reviso a fondo: leer el codigo antes de modificar esa parte.
Se eliminaron `ARCHITECTURE.md` y `README.md` de la raiz (obsoletos: plantilla de Vaadin y
clases que ya no existen); este archivo los reemplaza. Los problemas ya resueltos estan en
`docs/REGISTRO-DE-FALLAS.md`.

---

## 1. Que es

"LineaBaseX - Monitorizacion energetica de planta". Aplicacion web para una planta con
lineas de extrusion, mezcla y casa de fuerza. Lee PLCs Siemens S7-200 y medidores Schneider
PAS600L por Modbus TCP, guarda historicos, y ofrece: graficas en tiempo real e historicas,
consulta de datos, horometro (horas de funcionamiento por maquina), alarmas, y mantenimiento
preventivo por horas con stock de repuestos.

Un solo desarrollador/propietario, que es quien prueba todo (no se exigen tests
automatizados propios). La app corre como .exe portable en una PC de planta y se usa por
navegador.

## 2. Stack y ejecucion

- Java 21 (JDK 25 instalado en `C:\Program Files\Java\jdk-25`), Spring Boot 4.0.5, Vaadin Flow
  25.1.3 con tema **Aura** (no Lumo), Hibernate 7 / Spring Data JPA.
- H2 en archivo para usuarios, alarmas, horometro y mantenimiento. SQLite para datos crudos
  de energia. amCharts5 en el frontend (ver `GraficaModel`). Cliente Modbus:
  j2mod (Maven), siempre a traves de `ModbusTcpConexion` (excepcion del equipo vs sin respuesta).
  EasyModbus se elimino: no detectaba excepciones Modbus y las devolvia como ceros.
- `vaadin.productionMode=true`: el frontend sale de `src/main/bundles/prod.bundle`, que **esta
  versionado en git** (ver seccion 10).
- `spring.jpa.hibernate.ddl-auto=update`: tablas y columnas nuevas se crean solas al arrancar.
  `schema.sql` solo tiene parches puntuales.
- Sesion: 12 h para USUARIO; ADMIN se cierra tras 1 h de inactividad (`AdminSessionTimeoutFilter`).
- Compilar para verificar: `./mvnw.cmd -q -DskipTests compile`. No correr ni agregar tests.
- Los tests (los corre `mvn package`) usan H2 en memoria, config en `target/` y
  `app.adquisicion.habilitada=false` (`src/test/resources/application.properties`): sin la lectura
  automatica, porque en esta PC llega a la planta, escribia en los SQLite reales y disparaba alarmas reales.
- CI: `.github/workflows/e2e.yml` (mvnw package + Playwright E2E) corre despues de cada push.
- Empaquetado: perfil `windows-app-image` (jpackage), se corre local. GraalVM native se
  descarto, no reintroducir.
- **Puerto:** la app usa 8080 (`server.port=${PORT:8080}`). En la PC del usuario el 8080 lo
  ocupa su propia instancia corriendo desde IntelliJ, contra la base real. Ver seccion 11
  para el flujo de pruebas.

## 3. Datos en disco (`C:\LineaBaseX`)

| Ruta | Contenido |
|---|---|
| `data\lineabase` | Base H2: `jdbc:h2:file:C:/LineaBaseX/data/lineabase;AUTO_SERVER=TRUE`, usuario y clave **vacios**. `AUTO_SERVER` permite abrirla por JDBC (jar `h2-2.4.240` en `~/.m2`) con la app corriendo. |
| `config\` | `plc-config.json`, `linea-id-config.json`, `mezcladores-config.json`, `extrusion-tag-config.json`, `generador-config.json` (generadores y su transformador), `generador-modelos.json` (mapa Modbus por modelo de controlador). Se siembran desde `src/main/resources` en el primer arranque (`ConfigLoaderService`) y despues se editan a mano ahi, sin recompilar. |
| `{anio}\{mes}\...` | SQLite de energia: un archivo por mes, una tabla por linea/maquina, PK `fecha` (texto). Ruta armada en `RutaArchivosEnergia` (`BASE_PATH`). Ademas `{mes}Calidad` (solo mensual, medidores por pasarela, ver `com.example.calidad`). Muestreo cada 60 s (`DataAcquisitionTask.CYCLE_INTERVAL`; el comentario del codigo dice 6 s y esta mal). Nunca se purgan. |

`docs/REGISTRO-DE-FALLAS.md` documenta las fallas ya resueltas (fuente de verdad, se importa en
`CLAUDE.md`); `reportes/reporte-de-fallas.html` es su version para el navegador.

Trampa: la base H2 es la real. Cualquier prueba en la instancia del usuario deja datos
reales. Si hace falta limpiar, se hace por JDBC (script en el scratchpad, credenciales vacias).

## 4. Mapa de paquetes (`com.example`)

| Paquete | Para que sirve | Clases clave |
|---|---|---|
| `base/ui`, `base/model` | Layout y vistas transversales | `MainLayout` (menu lateral y permisos), `LoginView`, `ChartsView`, `HistoricoView`, `DataQueryView`, `ConfiguracionView` (hardware), `UsuariosView`, `NotificacionesUtil`, `CsvUtil`, `PanelGraficoUtil`, `TarjetasEstadoActual`, `GraficaModel` (JS de amCharts5) |
| `dataacquisition` | Adquisicion de datos **[resumen]** | `DataAcquisitionTask` (scheduler), `PLCReaderService` (S7-200), `PASReaderService` (pasarelas en paralelo, un hilo virtual por pasarela, registros segun el catalogo de `medidores`; si un medidor falla queda hueco, nunca 0), `ModbusTcpConexion` (unico acceso Modbus, j2mod), `MezcladorReaderService` (DTB48), `PLCDataAcquisitionService`, `PLCDataQueryService` (lee SQLite), `ConfigLoaderService` (JSON externos), `RutaArchivosEnergia`, `MaquinasVirtuales` (`TemperaturaAgua`, `TemperaturaAmbiente`, `KWhPlanta1`), eventos (`SensorDataUpdateEvent`, `MaquinaEstadoCambioEvent`, ...), controladores SSE (`/api/plc/stream/...`) |
| `horometro` | Horas de funcionamiento | ver seccion 6 |
| `alarmas` | Alarmas por umbral | ver seccion 7 |
| `mezcladores` | Temperatura de mezcladores (DTB48) | ver seccion 8 |
| `mantenimiento` | Mantenimiento preventivo y stock | ver seccion 9 |
| `generador` | Grupo electrogeno: generadores con controlador ComAp (fase G, **solo lectura**, funcion 03) | `GeneradorView` (ruta `generador`, lo que pasa ahora: desde el 2026-10-26 una pestana por generador con su tablero (antes Estado actual), Balance energetico P1, Historial de eventos, Registros solo ADMIN; sin selector de generador), `GeneradorAnalisisView` (ruta `generador/analisis`, lo calculado sobre lo guardado, siempre con todos los generadores (el selector de generador se quito el 2026-10-23): Resumen de energia, Periodos en marcha por periodos de la planta (el horometro de Arranques va en la tarjeta de cada generador), Tendencias (pasada desde Generadores el 2026-10-22) y Costos; cada "Ver" usa una `GeneradorAnalisisService.Consulta`, que lee cada archivo y detecta la isla una sola vez: con "Todos" y un mes, de 2,0-2,4 s a 0,6-0,9 s en IntelliJ, mismas cifras; desde el 2026-10-23 boton "Actualizar" (mismo periodo, sin perder el periodo elegido en la lista) y revision cada 15 s (desde el 2026-10-25 solo con la pestana Periodos en marcha abierta y algun generador en marcha = su arranque abierto en `GeneradorAlmacen.hayArranqueAbierto`, mas una revision al parar el ultimo) que recalcula solo si el rango incluye hoy y cambiaron los archivos de datos del mes (`modificacionDatos`, tambien los -wal); en esa actualizacion los graficos sin cambios no se redibujan y los que cambiaron solo reciben los datos (soloDatos de `getConsumoPlantaScript` y `getMarchaMinutoScript`), asi no se pierden el zoom ni los marcadores; el detalle de un periodo ya terminado no se recalcula), `GeneradoresConfigService` (lee `generador-config.json` y `generador-modelos.json` en cada llamada), `LectorControlador` (lector generico por mapa + exploracion de registros), `GeneradorReaderService` (ciclo de 1 min: marcha = cada minuto, parado = cada 15 min, abre/cierra arranques; mapa sin confirmar = no guarda), `GeneradorAlmacen` (`{mes}Generador`, una columna por `ParametroGenerador`, y `C:/LineaBaseX/generador/arranques`; si la app encuentra el generador ya en marcha, `corregirInicioEstimado` recupera el comienzo con el horometro y el contador de energia guardados en la parada anterior: inicio = primera lectura - avance del horometro (+-6 min, va de a 0,1 h), energia exacta; solo si el contador de arranques del controlador subio en 1; columna `inicio_corregido`; el primer arranque de cada generador no se corrige porque no hay parada anterior. Corregidos asi GenPower #9 y Caterpillar #8 del 17-10, respaldo en `C:\LineaBaseX\backup\2026-10-20-arranques-antes-de-corregir-inicio`), `HistorialEventosService` (historial propio de eventos tipo ComAp: arranque/parada, carga, red, alarmas de la lista de alarmas del controlador, comunicacion; `C:/LineaBaseX/generador/eventos`, ordenado por fecha; ademas los tipos "Controlador: ..." copiados a mano del historial interno, que no se lee por Modbus: 27 eventos del Caterpillar del 08 al 12-10 en `docs/ig500/historial-controlador-2026-10-12.csv`), reloj del controlador (44201-44204 BCD, campo `reloj` del modelo: la pantalla muestra cuanto va adelantado), `IslaService` (isla con el interruptor de la planta abierto y el controlador viendo red, que no sale de sus lecturas ni de su lista de alarmas: generador con carga + medidor de su transformador en 0 tres lecturas seguidas o mas + confirmacion del generador (carga +-5 %, kVAr +-100 o frecuencia fuera de 59,9-60,1 Hz); del medidor del transformador solo se usa si marca 0; comprobado con todo octubre: solo las islas del 11-10 (61 min) y 21-10 (16 min); revisa lo guardado despues de cada ciclo de lectura (3 h; la primera vez 62 dias) y registra "Paso a isla" / "Vuelve a paralelo" sin repetir; Estado actual muestra "En isla desde ..."; Analisis cuenta ese tiempo como isla; en modelos con `red` (Caterpillar), desde la primera lectura guardada con el estado de los interruptores, la isla es la que dice el controlador y no se usa el medidor del transformador), `SenalesRed` (campo `red` del modelo en generador-modelos.json: codigos del estado de los interruptores para paralelo / isla / falla de red / retorno de red con sus nombres ComAp, y bits de MCB, GCB, falla de red, proteccion de red, ROCOF y salto de vector; los dos generadores desde el
2026-10-26, el GenPower sin ROCOF ni salto de vector porque su controlador no los mide), modo del controlador
(`MODO_CONTROLADOR` + campo `modos` del modelo: OFF, MAN, AUTO, TEST; GenPower 41344, Caterpillar 41324; desde el
2026-10-26 etiqueta "Modo ..." junto a EN MARCHA/PARADO en Estado actual, verde AUTO, amarillo MAN y TEST, rojo OFF, y
eventos "Cambio de modo del controlador" / "Vuelve a AUTO" en el historial), listo para AMF (Ready To AMF de ComAp, Log Bout 2 bit 5: GenPower 41299, Caterpillar 41392; campo `listoAmf` del modelo; desde el 2026-10-26 etiqueta "LISTO / NO LISTO PARA AMF" en la barra del tablero y eventos "Deja de estar listo para AMF" / "Vuelve a estar listo para AMF"; contadores de paradas de emergencia y por falla en el tablero), `RedControladorService` (desde el 2026-10-22, cada lectura de fondo de un modelo con `red`: eventos falla de red, paso a isla, retorno de red, vuelve a paralelo, se abre / se cierra el interruptor de la planta, proteccion de red vista activa y suba de la ROCOF maxima o del salto de vector maximo; alarma urgente `PERDIDA_RED` abierta mientras el controlador este sin red, se cierra sola; Estado actual toma de aca el "Modo"), `GeneradorAnalisisService` (energia importada/exportada/generada por dia/semana/mes y por periodo en marcha; la red es siempre el medidor principal `PRINCIPAL` = KWhPlanta1, `RedPrincipal` reparte cada tramo del contador entre los generadores en un arranque segun su potencia; `Fila.generadoOtros`/`redExportadaOtros` para que con un solo generador elegido el consumo sea el de la planta; los medidores de los transformadores no se usan en Grupo electrogeno salvo en Estado actual), `ConsumoMedidoresService` (con que maquinas trabajo el generador en un arranque: Encendido / Energizado y sin produccion / Apagado, consumo minimo / Apagado (en cero), con el umbral y la ventana del horometro, compresores 60 min; desperdicio = consumo sin produccion; en pantalla se dice "Transformador 1/2" o "la planta", nunca "el tablero"), `CostoCombustibleService` + entidad H2 `CostoCombustible` (galones y precio por arranque, enlazado por generador + id del arranque), costo en la red por arranque en `GeneradorAnalisisService.periodosEnMarcha` (`PeriodoMarcha.aprovechadoFranja` y `costoRed`: generado y entregado repartidos por la franja de cada hora con las tarifas y feriados de `franjahoraria`; la tarifa unica `TarifaRed` se elimino el 2026-10-17), `GeneradorService`, `ReporteGeneradoresService` (boton "Reporte de generadores" en Analisis: HTML de un solo archivo, con Chart.js adentro y sin internet, abierto en pestana nueva con los arranques del periodo del ultimo "Ver"; ahi se eligen los arranques y se imprime o se guarda como PDF; plantilla y Chart.js en `src/main/resources/reportes/`; primero lo global (horas, energia, balance entradas = salidas, meta de cero importacion de la red, combustible y costo) y despues el detalle (tabla por periodo, importacion por periodo, balance diario; opcionales el costo de lo exportado y el perfil de potencia; con arranques de mas de un generador, seccion "Balance planta 1 (Transformadores 1 y 2)": toda la planta mientras alguno estaba en marcha, el aporte de cada generador y la red, y su grafico, de puntos de 15 min del medidor principal que manda el servidor en `PLANTA`; los puntos del borde de cada arranque cuentan en proporcion, asi coincide con el balance de los arranques); titulos tecnicos: generada / consumida / importada / exportada), `MetaImportacionRed` (semaforo de la meta "cero importacion de la red", % del consumo importado: `metaImportacionRed.verdeHasta` / `amarilloHasta` en generador-config.json, 2 y 10 si falta; lo usan Analisis y el reporte; desde el 2026-10-23 el ADMIN lo cambia en Analisis > Periodos en marcha > "Limites del semaforo"). Generadores: GenPower (InteliGen 200, Trafo2) y Caterpillar (InteliGen 500 G2, Trafo1, mapa cotejado con la lista Modbus de InteliConfig). Mapas en `docs/ig200/` y `docs/ig500/` |
| `calidad` | calidad de energia (fase e del plan) | `CalidadEnergiaView` (ruta `calidad`), `CalidadEnergiaService` (lectura, bloques de 10 min, cumplimiento, limites), `ConfiguracionCalidad` y `TensionNominal` (H2), `Indicador`, `CalidadEnergiaAlmacen` (archivo SQLite mensual `{mes}Calidad`, una tabla por maquina, columnas nulables), `CalculoCalidad` (PF 4Q decodificado, desbalance calculado si el medidor no lo da), `LecturaCalidad`. Lo llena `PASReaderService` (solo medidores por pasarela) |
| `medidores` | Catalogo de modelos de medidor y lector generico; topologia electrica | `TopologiaMedidores` + `TipoMedidor` (campos `tipo` MAQUINA/TRANSFORMADOR/GENERAL/SENSOR/NO_CONTAR, `transformador` y `dentroDe` de linea-id-config.json; solo se suman las MAQUINA que no estan dentro de otra), `ModeloMedidor`/`RegistroModelo` (H2), `ParametroMedidor` (lista cerrada, 9 basicos + 27 de calidad, incluye KWH_RETORNO = columna KWhR), `ModeloMedidorService`, `DefinicionModelo` (copia inmutable por ciclo), `LectorMedidorService` (lectura en bloques, decodificacion, PF 4Q), `ModeloMedidorSeeder`, `ModelosMedidorView`. Detalle en `docs/PLAN-CALIDAD-ENERGIA.md` (fase B) |
| `franjahoraria` | Consumo y costo por franja horaria de la tarifa de la empresa electrica (A L-V 08-18, B L-V 18-22, C L-V 22-08 y S/D/F 22-18, D S/D/F 18-22; F = feriado cargado) | `ConsumoFranjaView` (ruta `energia/franja-horaria`), `Franja` (horarios fijos en el codigo), `ConsumoFranjaService` (contador kWh de KWhPlanta1, el medidor general: cada tramo entre lecturas se reparte por tiempo entre las franjas que cruza; tramo > 5 min = horas estimadas; lee los dias del borde en los archivos de los meses vecinos; cache en memoria de los meses cerrados, que el boton
Recalcular descarta, util en el servidor tras copiar archivos historicos con la app abierta; los kWh no se guardan
ni viajan, cada PC calcula con sus archivos), entidades H2 `TarifaFranja` (4 precios por mes, clave `mes`; un mes sin fila usa la del ultimo mes cargado antes, "heredada") y `Feriado` (clave `fecha`), `TarifaFranjaService`, `FeriadoService`. Contrastado con la planilla de la empresa: marzo, abril y junio 2026 a menos de 0,3 % por franja sin feriados; mayo coincide con solo el 1 de mayo como feriado; abril NO lleva el Viernes Santo. Las tarifas y feriados tambien valorizan la energia del generador en Analisis > Costos y en el reporte de generadores |
| `security` | Usuarios, roles, permisos | `Usuario`, `UsuarioRepository`, `UsuarioPrincipal`, `SecurityConfig`, `LineaAccessService`, `DataSeeder` (crea el admin inicial), `AdminSessionTimeoutFilter` |
| `tools` | Reparaciones puntuales | `ReparacionVipView`, `MergeVipMensualTool` |
| `traspaso` | Pasar la configuracion de la PC de diseno al servidor (ver seccion 11) | `TraspasoConfiguracionService` (exportar ZIP; revisar sin escribir; importar con copia previa en `C:\LineaBaseX\backup\...-antes-de-importar-configuracion`: el ZIP de la configuracion anterior, `BACKUP TO` de la H2 y el archivo de eventos), `TraspasoConfiguracionView` |
| `config` | `JacksonConfiguration` | |

`Application.java` lleva `@EnableScheduling`, `@StyleSheet(Aura.STYLESHEET)`, `styles.css`, y
las anotaciones `@Uses` (seccion 10). Scheduler de 2 hilos (`ThreadPoolTaskScheduler`).

## 5. Seguridad, rutas y menu

**Usuario:** username, clave (hash), rol `ADMIN` o `USUARIO`, `zona` (ignorada si es ADMIN),
`habilitado`, y cuatro flags independientes de la zona: `verMezcladores`, `verMantenimiento`,
`verGeneradores` (Grupo Electrogeno y sus alarmas) y `verFranjaHoraria` (Consumo por franja horaria);
los flags valen desde el proximo inicio de sesion.
Usuarios en la base real a la fecha: `admin`, `mezcla`, `mantenimiento`, `produccion`, `LabMP`.
La clave del admin no se guarda en este archivo.

**`LineaAccessService`:** `getLineasPermitidas()` filtra por zona (ADMIN y zona Mantenimiento
ven todo), `puedeVerAlarmas()` (ADMIN o zona Mantenimiento), `puedeVerMezcladores()`,
`puedeVerMantenimiento()`, `puedeVerGeneradores()`, `puedeVerFranjaHoraria()` (ADMIN o flag), `esAdmin()`,
`usuarioActual()`.

**Patrones de acceso:**
- Solo admin: `@RolesAllowed("ADMIN")` en la ruta.
- Acceso por flag o zona: ruta `@PermitAll` + `BeforeEnterObserver` + clase `AccesoXxx.verificar(...)`
  (package-private, avisa con notificacion y hace `forwardTo(ChartsView.class)`). Existen
  `AccesoAlarmas`, `AccesoMezcladores`, `AccesoMantenimiento`, `AccesoGeneradores`, `AccesoFranjaHoraria`.
- El checkbox del flag se agrega en `UsuariosView` (con tooltip: `Tooltip.forComponent(...)`,
  `withHoverDelay(200)`, `withHideDelay(5000)`; `Checkbox` no implementa `HasTooltip`).

| Ruta | Vista | Acceso |
|---|---|---|
| `login` | `LoginView` | anonimo |
| `grafica` | `ChartsView` | todos; pestanas segun zona/permisos (kWh, Temperatura, PF general, Mezcladores) |
| `historico` | `HistoricoView` | todos; boton "Exportar CSV de todas las maquinas" solo ADMIN (`ExportacionCsvDialog` + `ExportacionCsvService`: ZIP con carpetas `Energia/` y `Voltaje-Corriente-Potencia-PF/`, dos CSV por tabla con todo el rango, elegido por meses o entre fechas: `tabla_mes_anio[_a_mes_anio].csv` o `tabla_dd-MM-yyyy[_a_dd-MM-yyyy].csv` (kWh, base normal) y `tablaVIP_...csv` (voltajes, corrientes, PW, PF, base VIP); no se juntan porque las dos bases graban en segundos distintos. `resumen.txt` separa con datos / en cero / sin datos) |
| `query` | `DataQueryView` | todos |
| `horometro` | `HorometroView` | todos (acciones extra solo ADMIN) |
| `alarmas` | `AlarmasHistorialView` (Alarmas Activas; las de los generadores solo con `puedeVerGeneradores()`) | gate `puedeVerAlarmas()` |
| `alarmas/historial` | `AlarmasHistorialCompletoView` | ADMIN |
| `alarmas/config` | `AlarmasConfigView` | ADMIN |
| `configuracion` | `ConfiguracionView` (hardware) | ADMIN |
| `configuracion/medidores` | `ModelosMedidorView` (catalogo de modelos + Probar lectura) | ADMIN |
| `calidad` | `CalidadEnergiaView` (Estado actual, Historico, Cumplimiento; Limites solo ADMIN) | gate `puedeVerAlarmas()` |
| `generador` | `GeneradorView` (solo lo en vivo y la bitacora; Arranques y Tendencias pasaron a Analisis el 2026-10-22; desde el 2026-10-26 sin selector ni opcion Todos: una pestana por generador (Transformador 1 primero; nombre, transformador y modelo, punto verde si tiene un arranque abierto y cantidad de alarmas de su controlador en rojo; IP al pasar el mouse) con su tablero, que reemplaza a Estado actual; despues Balance energetico P1 (siempre todos), Historial de eventos (siempre todos, columna Generador) y Registros (solo ADMIN, con su propio selector de generador); solo se lee en vivo el generador de la pestana elegida; el aviso rojo de alarmas queda solo para el generador de la pestana sin comunicacion (tablero al estilo de la pantalla ComAp, maqueta aprobada por el usuario: modo OFF/MAN/AUTO/TEST, esquema red - MCB - planta - GCB - generador con kW (red = medidor del transformador, planta = generador + red; torre, planta y G en verde ok o en marcha, rojo falla (sin tension o falla de red, planta sin energia, motor Shutdown/NotReady/SDVentil) y gris sin dato o parado listo), lista de alarmas, barras de tensiones y corrientes con los limites de Calidad de Energia (desde el 2026-10-26 tambien las corrientes y el PF del medidor del transformador), estado del motor y de los interruptores, entradas y salidas binarias, aceite y temperatura, indicador de kW, contadores; con 1500 px de ancho o mas (container query) se acomoda en dos filas para que cada generador entre en una pantalla, mas angosto queda como la maqueta; desde el 2026-10-26 se achica solo (zoom CSS de a 5 %, minimo 75 %) hasta que un generador entre en el alto de la ventana, por ejemplo con Windows al 125 %, y se reajusta al cambiar el tamano o el zoom; lo dibuja `src/main/resources/tablero/tablero-generadores.js` con los datos que manda `GeneradorView.datosTablero` cada 15 s; nombres de estados, entradas y salidas en `estadosMotor`, `entradas` y `salidas` del modelo; Transformador 1 primero), colores por generador `GeneradorView.COLORES_GEN` (naranja, violeta) en barras y graficos; aviso rojo con las alarmas del controlador arriba de las pestanas; primera pestana "Balance energetico P1" (planta 1 en vivo; antes "Planta en vivo") con todos los generadores a la vez (`PlantaEnVivoService`: consumo de la planta = generadores + red por el medidor principal (sin lectura reciente del principal no muestra consumo ni red, con aviso); exportada repartida entre los generadores segun lo que genera cada uno; cuanto pone cada generador y cuanto se importa de la red, en kW y %, con el semaforo de la meta; exportada aparte; "Antes del arranque" = promedio del medidor principal en los 10 min previos al arranque en curso o al ultimo; maquinas de Extrusion y Mezcla trabajando / paradas con el estado del horometro en memoria (`HorometroService.estaEncendida`) e Inyeccion neta = Inyeccion menos sus submedidores (`dentroDe`: GA752, HornoL3, OrientadoraL2) contra el umbral de Inyeccion; cada 15 s; tabla por generador: aporte en planta y % del consumo de la planta mas una fila "Red" (medidor principal, con el semaforo), que suman 100 %, y el transformador de cada generador solo como nombre; a pedido del usuario se quitaron el grafico de consumo (2026-10-20) y el bloque "Consumo por transformador" con la comparacion contra el principal (2026-10-21), por confusos); Estado actual en vivo, Historial de eventos; Registros solo ADMIN; solo lectura del controlador) | gate `puedeVerGeneradores()` |
| `generador/analisis` | `GeneradorAnalisisView`, pestanas (desde el 2026-10-25 Periodos en marcha primero) Resumen de energia (rediseno del 2026-10-25 sobre la maqueta aprobada, opcion B: tarjetas "Todo el periodo" (consumo de la planta, aporte de los generadores, de la red de todo el dia, exportada) y "Con generadores en marcha" (los periodos de planta del rango, los mismos del gauge de todos los periodos: consumo, aporte, de la red con su % y meta 0), barra de reparto del consumo, columnas apiladas por dia/semana/mes (aporte de cada generador + red = consumo) y tabla del detalle (cada generador, de la red, consumo planta, exportada y horas de cada generador; kWh redondeados y consumo y totales como suma de lo redondeado; dias sin generador en gris; debajo de cada consumo, en chico, "G x % + R y %" = generadores + red; lineas de los graficos de Analisis de 1 px desde el 2026-10-25; la tarjeta Exportada y los textos de los dos gauges de Periodos en marcha dicen, solo como informacion (sin meta ni semaforo, pedido del usuario), el % de lo generado y el diesel y costo aproximados con el rendimiento y el precio medio de los arranques del rango con diesel cargado); se quitaron la grilla vieja, paralelo/isla/vacio, el grafico del consumo de toda la planta y la nota larga), Periodos en marcha (desde el 2026-10-23, todo en una pantalla, diseno aprobado por el usuario sobre una maqueta, idea de la pantalla ComAp: periodos de la planta = el tiempo con uno o mas generadores en marcha, los arranques que se pisan juntos (`GeneradorAnalisisService.periodosPlanta` / `PeriodoPlanta`: importada y exportada = suma de las partes de cada arranque, consumo de toda la planta); arriba "Meta: cero importacion de la red" con el gauge de todos los periodos del rango (cuantos en verde y en rojo), el gauge del periodo seleccionado (al abrir, el que esta en marcha o el ultimo, ya marcado en la lista; si el rango no tiene, el ultimo que haya habido) y una tarjeta chica por generador (Transformador 1 arriba) con su % del consumo y los kWh que aporto a la planta (sin lo exportado, desde el 2026-10-25; asi generadores + red suman el consumo), su carga media y las horas trabajadas con el horometro al arrancar y al parar (reemplaza a la pestana Arranques de Generadores; en gris si no trabajo), mas la barra de reparto del consumo (cada generador + red = 100 %); abajo la lista de periodos (Inicio, Fin, Generadores, Duracion, Consumo planta, De la red, Exportada) y el minuto a minuto de la planta del seleccionado (desde el 2026-10-25 una sola linea "Aporte de los generadores" = generado - exportado, consumo de la planta y red por el medidor principal, con los marcadores de clic y los tooltips semitransparentes); gauges = `GraficaModel.getGaugeMetaScript` al estilo ComAp (carga `radar.js` de amCharts del mismo CDN), boton "Limite de indicador" solo ADMIN (antes "Limites del semaforo"; guarda en generador-config.json con `ConfigLoaderService.saveGeneradorMetaImportacionRed`); abajo de todo, un recuadro por generador (Transformador 1 a la izquierda) con su minuto a minuto contra su transformador (`GeneradorAnalisisService.minutosTransformador` + `GraficaModel.getMarchaMinutoScript`: generado, red importada / exportada y consumo total del transformador, con marcadores) y debajo sus maquinas con su estado, mismas palabras que Balance P1: segun el umbral de encendido del horometro y sin explicar el color en el texto: verde "trabajando" (paso el umbral) con la potencia media (sin el tiempo que paro, pedido del usuario); amarillo "parada" (bajo el umbral con consumo minimo) con sus kW; gris "parada" (consumo cero) o "sin dato"; con el conteo arriba (`ConsumoMedidoresService`); al final de todo, tabla "Resumen del periodo seleccionado" (desde el 2026-10-25: una fila por arranque, Transformador 1 primero, con Inicio, Fin, Generador, Duracion h:mm, Aporte del generador, De la red, Consumo planta y Exportada, mas el Total; por fila aporte + red = consumo; con dos generadores la red y el consumo se reparten segun lo que generaba cada uno; el total es la suma de las filas redondeadas; al lado de Aporte, De la red y Consumo planta, su % del consumo de toda la planta); el minuto a minuto y los recuadros se calculan recien con la pestana visible; se quitaron las 6 tarjetas de resumen, el grafico de barras por arranque, la tabla de maquinas y el balance del arranque), Tendencias (una variable del controlador, ultimas 24 h a 90 dias, con su propio periodo y boton Ver; usa el generador del filtro, con "Todos" una linea por generador; con esta pestana abierta se ocultan el periodo de arriba, "Ver" y el reporte) y Costos (galones y precio por arranque, cargar/editar solo ADMIN; comparativo diesel contra la red: costo en la red de cada arranque con el precio de la franja horaria de cada hora, $/kWh de cada uno, cuantas veces la red, $ de mas, diesel entregado a la red sin pago, rendimiento por generador y barras $/kWh diesel vs red; las tarjetas suman solo los arranques con diesel cargado). En Periodos en marcha, la barra de la meta "cero importacion de la red" con el semaforo. Boton "Reporte de generadores" junto a "Ver". Desde el 2026-10-23, toda la ventana Analisis usa la paleta que eligio el usuario de sus ejemplos (estilo StarAdmin): generador `#F29F67`, segundo generador `#6F42C1`, consumo y texto `#1E1E2C`, red `#3B8FF3`, exportada `#34B1AA`; semaforo verde `#3BB273`, amarillo `#E0B50F`, rojo `#E8505B` (con fondos suaves para etiquetas y tarjetas); tarjetas blancas con borde `#E4E6EC` y esquinas de 12 px. Generadores y el reporte siguen con sus colores de antes hasta que el usuario lo confirme | gate `puedeVerGeneradores()` |
| `energia/franja-horaria` | `ConsumoFranjaView`, pestanas Detalle del mes (tarjetas por franja, dona CSS de kWh, barras por dia, tabla por dia con costo, horas estimadas y sin datos), Historico (tabla por anio con el formato de la planilla de la empresa: kWh, precio facturado y $/kWh por franja; solo consulta, el clic en un mes no lleva al detalle, pedido del usuario; CSV), Tarifas (4 precios por mes; verde propia, amarillo heredada, gris sin tarifa; cargar/editar/copiar del mes anterior solo ADMIN) y Feriados (opcional, cargar solo ADMIN) | gate `puedeVerFranjaHoraria()` |
| `mezcladores/config` | `MezcladoresConfigView` | gate `puedeVerMezcladores()` |
| `mantenimiento` | `MantenimientoView` | gate `puedeVerMantenimiento()`; el formulario solo lo ve ADMIN |
| `mantenimiento/personal` | `PersonalMantenimientoView` | ADMIN |
| `mantenimiento/config` | `MantenimientoConfigView` | ADMIN |
| `reportes/mantenimiento` | `EstadoMantenimientoView` | gate `puedeVerMantenimiento()`; escribir en Stock solo ADMIN |
| `usuarios` | `UsuariosView` | ADMIN |
| `configuracion/traspaso` | `TraspasoConfiguracionView` (Pasar configuracion: exportar / revisar / importar) | ADMIN |
| `admin/reparar-vip` | `ReparacionVipView` | ADMIN |

**Menu lateral (`MainLayout.createSideNav()`):** Graficas (Tiempo Real, Historico), Consulta de
Datos, Horometro, Alarmas (Alarmas Activas; Historial solo admin), Calidad de Energia (mismo alcance
que alarmas), Consumo por franja horaria (`puedeVerFranjaHoraria()`), Grupo Electrogeno (Generadores, Analisis;
`puedeVerGeneradores()`), Mantenimiento Preventivo
(Mantenimiento Barril y Tornillos; Personal de Mantenimiento solo admin), Reportes (Barril y
Tornillo), y solo admin: Usuarios, Reparar VIP Mensual, Configuracion (alarmas, hardware,
Modelos de medidor, Mezcladores, Mantenimiento, Pasar configuracion). Los padres usan `colapsarAlSalirDelMouse(...)`. "Mantenimiento
Preventivo" y "Reportes" comparten el bloque `if (puedeVerMantenimiento())`. `MainLayout` tambien
tiene un poll que muestra avisos de alarmas en el navegador: las de planta con `puedeVerAlarmas()` y las
de los generadores (`ALARMA_GENERADOR`) con `puedeVerGeneradores()`.

## 6. Horometro (`com.example.horometro`)

- **Tablas H2:** `HorometroTotal` (una fila por linea: horas acumuladas y `fechaInicio`),
  `HorometroDiario` (una fila por linea por dia), `HorometroMensual`, `HorometroSemanal`.
- **En vivo:** `HorometroService` guarda en memoria el inicio del tramo ON en curso de cada
  linea. `obtenerSnapshot(linea)` = total persistido + tramo en curso (incluye "hoy" y "mes").
  Se actualiza por eventos (`MaquinaEstadoCambioEvent`) y publica `HorometroUpdateEvent`
  (SSE: `HorometroStreamController`).
- **Backfill/recalculo:** `HorometroBackfillRunner` reprocesa datos crudos con el mismo
  algoritmo que la alarma (umbral en kW + ventana de confirmacion; usa `AlarmaConfig` de tipo
  DETENCION, con CICLO_COMPRESOR como respaldo). Accesible por los botones "Recalcular".
  - `horasHastaMomento(linea, momento)`: suma exacta de `HorometroDiario` de los dias anteriores
    (un solo `SUM` en SQL, `sumHorasHastaFecha`) + calculo desde datos crudos **solo del dia** de
    `momento`, cortado en la hora exacta. Costo fijo por llamada, no crece con la antiguedad
    (lee el SQLite de ese mes completo y filtra en memoria, unas ~43 mil filas como maximo).
- **`HorometroView`:** pestanas Tabla, Extrusion, Mezcla, Casa Fuerza, Horas por mes (tabla y
  grafico); botones Descargar CSV, Ajustar umbrales de encendido/apagado, Recalcular todas y
  Recalcular por maquina (acciones extra solo ADMIN). "Parada" se muestra como advertencia
  amarilla, ya no es alarma.

## 7. Alarmas (`com.example.alarmas`) **[resumen]**

- `TipoAlarma`: `DETENCION`, `CICLO_COMPRESOR`, `TEMPERATURA_ALTA`, `FACTOR_POTENCIA_BAJO`,
  `DISPOSITIVO_NO_DISPONIBLE` (esta ultima recien dispara tras N lecturas fallidas seguidas,
  default 3), `ALARMA_GENERADOR` (lista de alarmas del controlador ComAp, sin configuracion: un evento por
  alarma mientras este en la lista, urgente si impide arrancar; `GeneradorAlarmasService`, cada minuto), `PERDIDA_RED`
  (falla de red, isla o retorno de red segun el controlador del generador, urgente, sin configuracion;
  `RedControladorService`). `TipoAlarma.deGenerador()` = las dos de los generadores: no se configuran y solo las ve
  quien tiene `puedeVerGeneradores()`.
- `AlarmaConfig` (config por linea + tipo: umbral, ventana, etc.), `AlarmaEvento` (historial,
  con flag urgente), `AlarmaEvaluatorService` (evalua al llegar datos), `AlarmaConfigSeeder`
  (valores por defecto).
- `AlarmaTrayNotifierService`: globo nativo de la bandeja de Windows para alarmas urgentes.
  **Solo se ve en la PC donde corre el .exe**, no en el navegador de quien consulta desde otra
  PC. Se dispara desde el evaluador en el servidor, sin necesitar navegador abierto.

## 8. Mezcladores (`com.example.mezcladores`) **[resumen]**

Temperatura de mezcladores con controlador DTB48 (PV/SV de calentamiento y enfriamiento), leida
por `MezcladorReaderService` (una tabla por canal, ver `ConfigLoaderService.nombreTablaCanalMezclador`).
Lista de mezcladores en `mezcladores-config.json`. Se grafica en la pestana Mezcladores de
`ChartsView` (visible con `puedeVerMezcladores()`); `MezcladoresConfigView` es la configuracion.

## 9. Mantenimiento preventivo (`com.example.mantenimiento`)

Modulo mas reciente. Gestiona planes de mantenimiento por horas de funcionamiento, el registro
de tareas ejecutadas, el estado de vencimiento, el personal y el stock de repuestos de
Barril y Tornillo.

### 9.1 Catalogo de TAG
`extrusion-tag-config.json` (taxonomia ISO 14224, 13 lineas: Linea01, 02, 03, 04, 05, 08, 09,
10, 17, 18, 31, 32, 34) se carga como `LineaTag` > `EquipoTag` > `ItemTag`. TAG jerarquico:
`EXT-Lxx-<EQUIPO>[-<ITEM>]`; ejemplo: `EXT-L02-XTR-BYT` = Linea02, Extrusora, Barril y tornillo.
El formulario arma el TAG con la cascada Linea > Equipo > Elemento. Las maquinas sin
taxonomia (Mezcla, Casa Fuerza) usan el nombre de la linea como TAG.
`MantenimientoService.resolverLineaMaquina(tag)` lo convierte al `lineaMaquina` del horometro.

### 9.2 Modelo de datos (H2)
| Entidad | Campos principales |
|---|---|
| `PlanMantenimiento` | `tag`, `tarea`, `intervaloHoras`, `horasAvisoAnticipado` (opcional), `habilitado`. Hoy hay un plan "Recalibracion" para `EXT-Lxx-XTR-BYT` en las 13 lineas. |
| `MantenimientoRealizado` | plan, `fechaRealizado`, `horasAcumuladasEnMomento` (horometro exacto a esa fecha/hora), `usuario` (quien lo cargo), `tareaRealizada`, `numeroOt`, `tecnico`, `notas`, `stockConsumido` (FK al stock, solo si fue Cambio) |
| `TecnicoMantenimiento` | `ci` (unico), `nombre`, `especialidad`; 16 tecnicos reales sembrados por `PersonalMantenimientoSeeder` |
| `StockBarrilTornillo` | `modelo`, `sistemaRefrigeracion` (Agua o Aceite), `cantidad`, `observacion`; `geometriaTornillo()` se deriva del modelo |
| `MovimientoStock` | stock, `tipo` (`INGRESO`, `EGRESO`, `DEVOLUCION`), `cantidad`, `fecha`, `tagEquipo` y `fechaTarea` (copiados, **sin FK** a la tarea para que el movimiento sobreviva si la tarea se borra), `observacion`, `motivo`, `autorizadoPor` |

### 9.3 Reglas de negocio acordadas con el usuario
- **Cambio y Recalibracion de Barril y Tornillo son alternativas que reinician el mismo
  contador** (un solo plan por TAG; el registro dice cual se hizo).
- "Tarea ejecutada" es una **lista fija**: `Cambio` y `Recalibracion` por ahora (constante
  `MantenimientoService.TAREA_CAMBIO`, arreglo `TAREAS_BARRIL_TORNILLO` en `MantenimientoView`).
  Se ampliara mas adelante; los valores con logica no deben ser texto libre.
- Horas transcurridas = horas actuales (snapshot en vivo) - horometro guardado en el registro.
  Horas faltantes = intervalo - transcurridas. El horometro del formulario se sugiere con
  `horasEnFecha` (exacto a la hora, ver seccion 6) y el usuario puede corregirlo.
- **Estado** (`EstadoPlanDTO`): `sinRegistro` (gris), `vencido` (rojo), `proximoAVencer` (amarillo,
  cuando faltan <= `horasAvisoAnticipado`), si no "al dia" (verde).
- **Stock:** modelos LSE-65, LSE-80, LSE-92, LSDP-75, CM-80, CM-92 (lista abierta, se aceptan
  otros). Refrigeracion por **agua** = sellada dentro del tornillo, no circula; por **aceite**
  = circula, con entrada y salida. Geometria: doble conico en todos, excepto LSDP que es doble
  paralelo.
- Registrar un **Cambio** obliga a elegir que barril/tornillo del stock se instalo: descuenta 1
  unidad y crea un `EGRESO`. Si el stock queda en 0 o negativo se avisa pero **no se bloquea**
  (para poder cargar tareas atrasadas del CMM de antes de llevar este control).
- **Recalibracion** no muestra el campo de stock ni toca el stock.
- La `cantidad` de un modelo solo se escribe a mano al **crearlo**; despues solo cambia con
  Ingreso (`registrarIngresoStock`, crea `INGRESO` con observacion) o por consumo/devolucion.
- **Borrar un Cambio** desde el historial exige **motivo y quien autoriza** (dialogo
  obligatorio); recien ahi devuelve la pieza al stock y crea una `DEVOLUCION`. Borrar una
  Recalibracion es una confirmacion simple.

### 9.4 Pantallas
- `MantenimientoView` (`/mantenimiento`): formulario (Tarea, Fecha y hora, Linea/Equipo/Elemento,
  # OT, Tecnico, Horometro, Barril/Tornillo instalado si es Cambio) + historial con tacho por
  fila. Los usuarios con solo el flag ven unicamente el historial.
- `EstadoMantenimientoView` (`/reportes/mantenimiento`, menu Reportes > Barril y Tornillo): tres
  pestanas: **Estado** (tabla por plan con badge de color), **Stock** (CRUD + Ingreso, solo
  ADMIN; los demas ven la grilla), **Movimientos** (historial de ingresos/egresos/devoluciones).
- `MantenimientoConfigView` (`/mantenimiento/config`, ADMIN): alta/edicion de planes.
- `PersonalMantenimientoView` (`/mantenimiento/personal`, ADMIN): catalogo de tecnicos.

### 9.5 Servicio
`MantenimientoService` concentra toda la logica: `listarEstadoPlanes`, `calcularEstado`,
`horasEnFecha`, `horasActuales`, `registrarMantenimientoRealizado(..., stockConsumido)`,
`registrarIngresoStock`, `eliminarMantenimientoRealizado(registro, motivo, autorizadoPor)`,
`listarMovimientosStock`, y el CRUD de planes, tecnicos y stock.

## 10. Convenciones y trampas (leer antes de escribir codigo)

**Idioma y estilo**
- Chat, mensajes de commit y **textos de la interfaz** (labels, notificaciones, dialogos): espanol
  neutro **sin tildes, sin enie y sin voseo** (nada de "vos/tenes/queres/podes", ni imperativos
  como "decime/avisame"). Los comentarios de codigo pueden llevar tildes. Es un pedido
  explicito y reiterado del usuario; la memoria `feedback_espanol_sin_acentos` lo detalla.
- Al proponer algo, explicar en lenguaje plano lo que se ve y lo que cambia para el usuario final,
  sin nombres de clases ni metodos (esos solo al implementar).
- Commits terminan con `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`.

**Vaadin: componentes que no se registran en rutas nuevas (bug ya resuelto)**
- Vaadin solo empaqueta de forma eager las rutas `""` y `/login`; el resto carga por demanda.
  Con un bundle recien generado, en la primera visita a una ruta nueva un componente puede no
  registrarse como custom element (queda invisible). Se comprueba con
  `customElements.get('vaadin-grid')` en el navegador.
- Solucion permanente: `@Uses(X.class)` en `Application.java` para cada componente no basico
  (hoy: `Grid`, `ComboBox`, `NumberField`, `DateTimePicker`, `IntegerField`, `Dialog`, `Checkbox`,
  `FormLayout`, `TabSheet`;
  `Dialog` se agrego al abrir un dialogo desde `HistoricoView`, que fallaba con "reading 'overlay'"), y **borrar
  `src/main/bundles/prod.bundle` y regenerarlo** con `./mvnw.cmd -q -DskipTests compile`
  (el build incremental cree que el bundle viejo sirve). El bundle se commitea.
- Regla practica: si un modulo nuevo usa un componente Vaadin que ninguna vista usaba antes,
  agregar su `@Uses` y regenerar el bundle en el mismo cambio.

**Escala de Windows (desde el 2026-10-26):** `src/main/resources/escala/escala-windows.js`, cargado en todas las paginas
por `Application.configurePage` (no toca el bundle), achica toda la pagina con zoom CSS en `html` para compensar la
escala de Windows (125 % -> 0,8) en pantallas de 1900 px reales o mas; el zoom del navegador (Ctrl +/-) se respeta
(estimado con outerWidth/innerWidth). Trampas del zoom CSS: un `100vh` queda al 80 % de la pantalla (usar `100%`); las
listas desplegables, calendarios y tooltips de Vaadin (popover) calculan su posicion por pasos y quedaban corridos, por
eso el mismo script repite `_updatePosition` (interno de Vaadin, revisar si cambia de version). amCharts 5 maneja bien
el zoom (comprobado). Si en una PC se habia puesto el navegador en 80 % a mano, volver a 100 % (Ctrl 0).

**Tema Aura, no Lumo:** los theme variants de Lumo (`badge`, `success`, `error`) no se pintan.
Para estados con color usar `Span` con estilo inline. Paleta usada: verde `#d4edda`/`#155724`,
amarillo `#fff3cd`/`#856404`, rojo `#f8d7da`/`#721c24`, gris `#e2e3e5`/`#383d41`.

**Regla de datos (pedido explicito del usuario):** los campos que afectan directamente a la
estadistica y/o KPI **nunca se llenan a mano**: salen de listas cerradas, catalogos o se calculan.
Texto libre solo para descripciones y comentarios; si falta una opcion, "No previsto" y se agrega
al catalogo. Fechas y horas con selector y validacion. Lo unico que se escribe a mano es el alta
en una ventana de catalogo (agregar maquina, item de AMEF, persona...), y lo agregado pasa a ser
una opcion mas de la lista.

**Patrones de UI del proyecto**
- CRUD de catalogo (solo admin): clic en fila para editar, boton Nuevo/Guardar, tacho por fila con
  `grid.addComponentColumn(...)`, `ComboBox` con `setAllowCustomValue(true)` para catalogos abiertos y
  lista cerrada cuando el valor tiene logica asociada.
- Varias pantallas relacionadas: `TabSheet` (`com.vaadin.flow.component.tabs`) en una sola ruta.
- Dialogos: `Dialog` con `setHeaderTitle`, contenido y `getFooter().add(...)` para los botones.
- Separar "registrar" (formulario) de "mirar" (reportes): se probo mezclarlos y la tabla de estado
  tapaba el formulario.

**Base de datos**
- Las entidades nuevas se crean solas por `ddl-auto=update`. Borrar un `StockBarrilTornillo`
  que ya tiene movimientos o esta referenciado por una tarea falla por integridad referencial
  (hoy sin mensaje amigable; ver pendientes).

## 11. Flujo de trabajo con el usuario

1. Proponer primero en lenguaje plano y esperar el visto bueno cuando la decision es del usuario
   (alcance, reglas de negocio). Resolver con criterio propio lo menor y avisar que se decidio.
2. Implementar, verificar que compila (`./mvnw.cmd -q -DskipTests compile`), commit y push directo a
   `master` (trunk-based, sin ramas de feature).
3. El usuario hace pull, recompila y reinicia **su instancia en el puerto 8080** y prueba ahi.
   No levantar un preview propio en el 8081 (el usuario lo pidio expresamente); `.claude/launch.json`
   todavia lo tiene por si hace falta.
4. Trabajar inline, sin subagentes salvo investigacion realmente amplia.
5. Cuando se debe leer/limpiar la base real, hacerlo por JDBC (H2 con `AUTO_SERVER`), avisando
   antes si el cambio es destructivo.
6. **Esta PC es la de diseno; la app empaquetada corre en otra PC (el servidor).** El paquete solo trae la
   configuracion de fabrica (semillas de `src/main/resources` y los `*Seeder`, que solo crean lo que falta). Lo
   configurado aqui se lleva con Configuracion > Pasar configuracion: exportar (ZIP) aqui, copiar el ZIP a
   `C:\LineaBaseX\importar\` del servidor, Revisar e Importar alli, y reiniciar la app. Viajan: los 6 JSON de
   `C:\LineaBaseX\config`, las tablas de configuracion de la H2 (usuarios, alarma_config, modelos de medidor y sus
   registros, configuracion_calidad, tension_nominal, planes y tecnicos de mantenimiento, tarifa_franja,
   feriado) y los eventos
   "Controlador: ..." de los generadores. Nunca el historial. Cada fila se busca por su clave natural (usuario, linea +
   tipo de alarma, nombre de modelo, tag + tarea, CI...); lo que esta solo en el servidor se deja, salvo los registros
   de un modelo que viene en el archivo; a un usuario existente no se le cambia la clave; `notificado` de los planes no
   viaja. **Toda tabla nueva de configuracion se agrega a `TraspasoConfiguracionService.TABLAS`** (y todo JSON nuevo de
   config a `ConfigLoaderService.ARCHIVOS`), si no, no pasa al servidor.

## 12. Decisiones descartadas y pendientes

**Descartado**
- Avisos automaticos de vencimiento por globo de la bandeja de Windows: solo aparece en la PC donde
  corre el .exe, no en quien consulta por navegador. Si se retoma, la alternativa es un aviso dentro
  del navegador con poll, como las alarmas.
- Mostrar el estado de vencimiento dentro de la pantalla de registro (tapaba el formulario): vive
  en Reportes.
- Ejecutable nativo con GraalVM.

**Pendiente / ideas**
- **Calidad de Energia + migracion de PLC a pasarelas PAS600L:** en curso como checklist en
  `docs/PLAN-CALIDAD-ENERGIA.md` (fases A y B hechas el 2026-10-07; faltan registros de ION8600,
  PAC1020 y los de calidad, que el usuario carga desde la pantalla; luego C, D, E). Incluye el PF
  del PM5110 guardado sin decodificar.
- **Grupo electrogeno:** fase G de `docs/PLAN-CALIDAD-ENERGIA.md`. GenPower (InteliGen 200) completo;
  Caterpillar (InteliGen 500 G2, firmware 2.4.3.6, 192.168.0.201, TR1) completo: mapa cotejado el 2026-10-22 con la
  lista Modbus de InteliConfig (`docs/ig500/lista-modbus-inteliconfig-2026-10-22.txt`, resumen y correcciones en
  `docs/ig500/MAPA-REGISTROS.md`). Con esa lista, desde el 2026-10-22 la app lee el estado de los interruptores
  (41327), el MCB Feedback (41058 bit 1) y la ROCOF (41045, maximo 41046): eventos de red y alarma "Perdida de red"
  (G22). Falta confirmar en el primer corte real los codigos y cuando se reinician los maximos. GenPower: lista
  exportada el 2026-10-26 (`docs/ig200/lista-modbus-inteliconfig-2026-10-26.txt`, resumen en
  `docs/ig200/MAPA-REGISTROS.md`), cargados el estado de los interruptores (41260), MCB y GCB (41090 bits 1 y 0) y
  AL Mains Fail (41301 bit 8). Faltan las alarmas de condicion (G3: bateria, precalentador,
  arranque fallido, sin comunicacion). Solo lectura siempre. Posible: asignar cada medidor a su
  transformador: hecho (G11, campos `tipo`, `transformador`, `dentroDe` en linea-id-config.json).
- Los archivos mensuales de energia se pisan en el borde: el de septiembre tiene las primeras horas
  del 1 de octubre. Quien sume por mes debe limitar cada archivo a su propio mes o descartar repetidos.
- **Medidores de los transformadores contra el principal (revisado 2026-10-20):** en 634 horas sin exportacion (sep-oct)
  Trafo1 + Trafo2 marcan 1,35 % MAS que KWhPlanta1 (+0,3 a +3,3 % segun la carga; ajuste por hora: principal =
  0,996 x T1 + 0,988 x T2 - 7 kW). Deberian marcar 1 a 2 % MENOS (el principal esta en media tension y suma las perdidas
  de los transformadores). El principal (propio de la fabrica, mide en paralelo con el de la empresa electrica) coincide
  con la factura (franja horaria, < 0,3 %), asi que el desvio esta en los PM5110 de los trafos (sobre todo Trafo2):
  revisar la relacion de TC configurada y la clase de los TC. Clases: ION8600 0,2 (IEC 62053-22) y PM5110 0,5S;
  TC clase 0,5 = +-0,5 % de 20 a 120 % de In (IEC 61869-2). Por minuto, 14 dias al 21-10: principal - (T1 + T2) = -34 kW
  sin generadores, -43 kW con uno, +64 kW con los dos (los trafos marcan 5 % menos; 104 kW el 21-10 09:25). El descuadre
  cambia de signo con los generadores (probable: por los trafos pasa casi solo reactiva y los PM5110/TC miden peor, mas
  las perdidas de los trafos); no se corrige en software: revisar en sitio TC y PM5110 o medir con analizador portatil.
- **Red = medidor principal (decidido 2026-10-21):** Balance P1, Analisis y el reporte toman importada y exportada de
  KWhPlanta1 (contadores kwh y KWhR; su PW en W). Lo que sale por un transformador y entra por el otro (100 a 430 kWh por
  dia con generador, del 10 al 18-10) ya no cuenta como exportado ni importado. Cada arranque se compara contra la planta 1
  (pedido del usuario); con dos generadores en marcha, importada y exportada se reparten entre los dos segun lo que
  generaba cada uno, asi la suma de los arranques no cuenta nada dos veces. Ej.: Caterpillar solo el 18-10 = 50,5 %
  importado de la planta; 14-10 18-22 h con los dos = 336 kWh exportados (antes 623 por transformador). Por el descuadre
  de arriba, el usuario pidio quitar de Grupo electrogeno todo lo que salia de los medidores de los transformadores
  (columnas de Balance P1, % de referencia en Periodos en marcha y reporte, fila del balance del arranque); no
  reintroducirlos sin que lo pida. Queda solo Estado actual (potencia, corrientes y PF del medidor de su transformador).
  El grafico minuto a minuto del generador contra su transformador (21-10) volvio el 2026-10-23 como recuadro por generador
  (abajo de todo en Periodos en marcha), ademas del minuto a minuto de la planta del periodo seleccionado. En
  Periodos en marcha (solo ahi) los tooltips de los graficos son semitransparentes (`GraficaModel.conTooltipsTransparentes`)
  y en el minuto a minuto se marcan instantes con clic como en Tiempo Real (desde el 22-10): linea roja punteada con fecha
  y hora, desde el segundo tambien el tiempo desde el anterior (Δ hh:mm:ss), etiqueta con fondo blanco; doble clic borra
  (`GraficaModel.scriptMarcadoresDeClic`, opcion `marcadores` de `getConsumoPlantaScript`).
- **Modulo de Mantenimiento Correctivo (MF21 + AMEF):** diseno acordado, sin implementar. Ver
  `docs/PLAN-MANTENIMIENTO-CORRECTIVO.md`. Falta el AMEF del usuario.
- Campos que hoy violan la regla de datos (corregir sobre la marcha, no ahora): tecnico y
  linea/maquina en el registro preventivo, linea/maquina en config de planes, horometro editable
  del preventivo (se dejo asi por las tareas atrasadas del CMM), modelo y refrigeracion del stock,
  linea en config de alarmas.
- Ampliar la lista fija de "Tarea ejecutada" (hoy Cambio y Recalibracion).
- Mensaje amigable al intentar borrar un modelo de stock con movimientos o tareas asociadas.
- Los movimientos de stock no se pueden borrar ni corregir desde pantalla (por diseno, es historial).
- El usuario esta iniciando el CMM: cargara tareas atrasadas de meses anteriores (el calculo exacto
  del horometro se analizo y no es problema de rendimiento).
- Posible: aviso de stock minimo, mas equipos/planes ademas de Barril y Tornillo, mas reportes
  dentro del menu Reportes.
- Posible (franja horaria): horarios de las franjas editables si la empresa los cambia seguido; columna "kWh
  facturado" para comparar con la planilla.
- Posible (costos del generador): costo de mantenimiento por hora de marcha (aceite, filtros, operador) para que el
  comparativo con la red no sea solo energia contra combustible. Primer comparativo (4 arranques del 08 al 16-10-2026):
  diesel $0,239/kWh aprovechado contra red $0,108/kWh, 2,2 veces; Caterpillar 13,4 kWh/gal, GenPower 16,4 kWh/gal.

## 13. Checklist para un modulo nuevo

1. Paquete `com.example.<modulo>` con `model/`, `repository/`, `service/`, `ui/` (mismo esquema que
   `mantenimiento`).
2. Entidades JPA (se crean solas). Si hay datos maestros, un `*Seeder` idempotente.
3. Vista con `@Route(value = "...", layout = MainLayout.class)`; elegir el acceso: `@RolesAllowed("ADMIN")`,
   o `@PermitAll` + `AccesoXxx` con un flag nuevo en `Usuario` (campo `boolean` con
   `columnDefinition = "boolean default false"`, metodo `puedeVerXxx()` en `LineaAccessService`,
   checkbox con tooltip en `UsuariosView`).
4. Agregar el item al menu en `MainLayout.createSideNav()` respetando la misma condicion de acceso.
5. Componentes Vaadin nuevos: `@Uses` + regenerar `prod.bundle` (seccion 10).
6. Textos de UI sin tildes ni voseo.
7. Si el modulo tiene configuracion propia (tabla de catalogo o JSON), sumarla a "Pasar configuracion" (seccion 11).
8. Compilar, commit, push a `master`, y que el usuario pruebe en el 8080.
9. **Actualizar este archivo** (seccion del modulo, rutas/menu de la seccion 5 y pendientes).
