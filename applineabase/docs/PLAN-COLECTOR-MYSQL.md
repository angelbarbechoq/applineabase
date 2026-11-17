# Plan: colector de datos aparte con MySQL

Acordado con el usuario el 2026-11-17. Proyecto nuevo (colector) que lee los equipos y guarda en MySQL; la app
(Vaadin) deja de leer equipos y pasa a leer de MySQL.

## Punto de partida (revisado en el codigo y en disco el 2026-11-17)

- La app lee todo en `DataAcquisitionTask` (cada 60 s: PLC, PAS600L, mezcladores, generadores; tic de 1 s: captura al
  segundo y sirena) y graba en SQLite. Los eventos internos (`SensorDataUpdateEvent`, `MaquinaEstadoCambioEvent`, ...)
  los usan horometro, alarmas, graficas en vivo (SSE) y `ChartsView`.
- SQLite hoy: un archivo por dia y otro por mes (normal y VIP), una tabla por maquina, PK `fecha` texto; ademas
  `{mes}Calidad`, `{mes}Generador`, arranques, eventos y capturas de los generadores. En esta PC: 1.390 archivos,
  13,94 GB en `C:\LineaBaseX\2025`, `2026` y `generador`.
- 15 clases abren `jdbc:sqlite` (39 lugares): escritura en `DatabaseInitializationService`, `PLCDataAcquisitionService`,
  `CalidadEnergiaAlmacen`, `GeneradorAlmacen`, `HistorialEventosService`, `CapturaSegundoService`; lectura en
  `PLCDataQueryService`, `ConsumoFranjaService`, `CalidadEnergiaService`, `ExportacionCsvService`, `GeneradorAnalisisService`,
  `IslaService`, `ConsumoMedidoresService`, `GeneradorService`, `PlanillaCombustibleService`, `MergeVipMensualTool`.
- MySQL no esta instalado en esta PC (sin servicio, sin puerto 3306). Ultima 8.4 publicada: 8.4.13 (2026-10-20, notas
  de version en dev.mysql.com).

## Decisiones tomadas (se pueden revisar)

- H2 (usuarios, configuracion, alarmas, horometro, mantenimiento) se queda como esta en esta etapa: solo se mueven
  las lecturas. Una cosa por vez.
- En MySQL una tabla por tipo de dato con columna `maquina`, no una por maquina ni por mes: `energia`, `vip`,
  `calidad`, `generador_lectura`, `generador_arranque`, `generador_evento`, `captura`, `captura_muestra`. Clave
  (maquina, fecha) con `fecha DATETIME`. Las columnas exactas se sacan de las tablas SQLite actuales antes de crearlas.
- El historico se importa una vez desde los archivos mensuales (no los diarios, que repiten lo mismo), limitando cada
  archivo a su propio mes (los mensuales se pisan en el borde, ver CONTEXTO seccion 12).
- La sirena y la captura al segundo van al colector: tienen que funcionar aunque la pagina web este caida.

## Cambio de rumbo (2026-11-17, pedido del usuario)

Antes de MySQL, el backend guarda en los **mismos archivos SQLite** que la app (`AlmacenSQLite`, `colector.almacen=sqlite`)
y la app sigue leyendolos igual. Orden acordado: (1) backend guardando en `C:\LineaBaseX-prueba` y comparar un dia
contra la app (seccion "Comparar con la app" de la pagina del backend); (2) la app "mira" los archivos cada minuto y
relanza los eventos internos (Tiempo Real, horometro, alarmas de planta), porque hoy salen de la lectura en vivo y no
del SQLite; (3) arranques, eventos, isla, alarmas de los generadores y sirena pasan al backend; (4) cambio: el backend
escribe en `C:\LineaBaseX` y la app arranca con `--app.adquisicion.habilitada=false` (apaga lectura, captura al segundo y
sirena: `DataAcquisitionTask`). La configuracion de hardware pasa al backend (solo administrador); hoy el backend ya lee
`C:\LineaBaseX\config` y el catalogo de medidores en cada ciclo. MySQL despues, como otro almacen.

Avance: (1) hecho el 2026-11-17 (dos ciclos reales: mismas tablas, esquema y valores que la app; etiqueta git
`antes-del-recolector` en la app = version anterior a todo esto). (2) hecho el 2026-11-17, sin probar en planta:
`VigiaArchivosService` en la app (solo con `app.adquisicion.habilitada=false`, cada 10 s, filas nuevas del archivo del
dia -> los mismos eventos que la lectura) y archivo `estado\equipos` del backend (comunicacion de cada linea, fecha ISO).
Prueba: ventana corta con los generadores parados, backend con `colector.sqlite.carpeta=C:\\LineaBaseX` y la app con
la lectura apagada.

Fecha como fecha (decidido 2026-11-17): SQLite no tiene tipo fecha; recomienda texto `AAAA-MM-DD HH:MM:SS` (sqlite.org,
Datatypes 2.2) y sus funciones no aceptan `DD-MM-AAAA`. No se convierten los archivos actuales: la correccion se hace en
el paso a MySQL (`DATETIME`), con un solo lugar de la app que lee los datos y el importador que convierte la fecha una vez.
Los archivos nuevos (como `estado\equipos`) ya usan el formato ISO.

## Etapas

0. **Vaadin 25.3.5 + Spring Boot 4.1.2 en la app** (hecho 2026-11-17, falta la prueba del usuario).
1. **MySQL en Docker** (pedido del usuario 2026-11-17): activar WSL 2 (no estaba), instalar Docker Desktop, y
   `docker-compose.yml` en el proyecto colector con la imagen oficial `mysql` en version fija (en Docker Hub el tag
   `lts` apunta a 9.7.4; la 8.4 llega a 8.4.13), datos en un volumen, puerto 3306 solo para esta PC, claves en un
   `.env` fuera de git; base `lineabase` y usuario propio de la app (no root). Ojo: los datos del volumen igual quedan
   en el disco de esta PC (dentro del disco virtual de WSL). Licencia: Docker Desktop es gratis para uso comercial
   solo con menos de 250 empleados Y menos de USD 10 millones de ingresos al ano (docs.docker.com). Servidor de
   planta: no confirmado que Docker Desktop corra sin un usuario con sesion iniciada; decidir antes de la etapa 7.
2. **Proyecto colector**: creado el 2026-11-17 en la carpeta hermana `backendMySQLSGEn` (puerto 8081; repositorio
   git propio, separado del de la app; el de la app lo ignora en `LineaBaseLocal/.gitignore`). Lee PLC, pasarelas (con calidad), mezcladores y generadores (solo la fila `{mes}Generador`, sin arranques
   ni eventos), arma las filas como el SQLite y las entrega a `AlmacenLecturas` (hoy en memoria, falta el de MySQL);
   pantalla en `/` con el ultimo ciclo, los equipos y la ultima fila de cada tabla. Sin escrituras Modbus. Original:: Spring Boot sin Vaadin, Java 21, j2mod,
   driver MySQL. Se pasan los lectores, `ModbusTcpConexion`, la lectura de `C:\LineaBaseX\config`, generadores
   (arranques, eventos, isla, red, captura al segundo) y la sirena. El catalogo de modelos de medidor lo sigue leyendo
   de la H2 (`AUTO_SERVER`).
3. **Importador del historico** SQLite -> MySQL (herramienta del colector, se corre una vez; se compara conteo y
   sumas de kWh por maquina y mes).
4. **Prueba en paralelo**: el colector graba en MySQL mientras la app sigue grabando SQLite; se comparan minuto a
   minuto.
5. **La app lee de MySQL**: se cambian las 15 clases, modulo por modulo (primero `PLCDataQueryService`, que usan
   graficas e historico). La app detecta filas nuevas en MySQL y relanza los mismos eventos internos, asi horometro,
   alarmas y graficas en vivo no cambian.
6. **Cambio**: `app.adquisicion.habilitada=false` en la app; se borra de la app el codigo de lectura.
7. **Servidor de planta**: instalar MySQL alli (lo hace el usuario, Claude no tiene acceso), importar el historico,
   colector como servicio de Windows. Ajustar "Pasar configuracion" si el colector suma configuracion propia.
8. Actualizar `CONTEXTO-PROYECTO.md`.
