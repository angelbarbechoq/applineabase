# Contexto del proyecto LineaBaseX

Documento de referencia para sesiones de Claude Code: resume que existe hoy en la
aplicacion, como esta organizada y que decisiones ya se tomaron, para no tener que leer
todo el codigo cada vez que se agrega un modulo. Se importa desde `CLAUDE.md`, asi que se
carga solo al abrir una sesion.

**Mantenimiento de este archivo:** al cerrar cada modulo o cambio importante, actualizar la
seccion correspondiente (y la seccion 12 de pendientes). Si algo de aqui contradice el
codigo, manda el codigo y hay que corregir este archivo.

Ultima actualizacion: 2026-09-25. Las secciones marcadas **[resumen]** son una vista de alto
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
  `lib/EasyModbusJavaClient.jar`.
- `vaadin.productionMode=true`: el frontend sale de `src/main/bundles/prod.bundle`, que **esta
  versionado en git** (ver seccion 10).
- `spring.jpa.hibernate.ddl-auto=update`: tablas y columnas nuevas se crean solas al arrancar.
  `schema.sql` solo tiene parches puntuales.
- Sesion: 12 h para USUARIO; ADMIN se cierra tras 1 h de inactividad (`AdminSessionTimeoutFilter`).
- Compilar para verificar: `./mvnw.cmd -q -DskipTests compile`. No correr ni agregar tests.
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
| `config\` | `plc-config.json`, `linea-id-config.json`, `mezcladores-config.json`, `extrusion-tag-config.json`. Se siembran desde `src/main/resources` en el primer arranque (`ConfigLoaderService`) y despues se editan a mano ahi, sin recompilar. |
| `{anio}\{mes}\...` | SQLite de energia: un archivo por mes, una tabla por linea/maquina, PK `fecha` (texto). Ruta armada en `RutaArchivosEnergia` (`BASE_PATH`). Muestreo cada 60 s (`DataAcquisitionTask.CYCLE_INTERVAL`; el comentario del codigo dice 6 s y esta mal). Nunca se purgan. |

`docs/REGISTRO-DE-FALLAS.md` documenta las fallas ya resueltas (fuente de verdad, se importa en
`CLAUDE.md`); `reportes/reporte-de-fallas.html` es su version para el navegador.

Trampa: la base H2 es la real. Cualquier prueba en la instancia del usuario deja datos
reales. Si hace falta limpiar, se hace por JDBC (script en el scratchpad, credenciales vacias).

## 4. Mapa de paquetes (`com.example`)

| Paquete | Para que sirve | Clases clave |
|---|---|---|
| `base/ui`, `base/model` | Layout y vistas transversales | `MainLayout` (menu lateral y permisos), `LoginView`, `ChartsView`, `HistoricoView`, `DataQueryView`, `ConfiguracionView` (hardware), `UsuariosView`, `NotificacionesUtil`, `CsvUtil`, `PanelGraficoUtil`, `TarjetasEstadoActual`, `GraficaModel` (JS de amCharts5) |
| `dataacquisition` | Adquisicion de datos **[resumen]** | `DataAcquisitionTask` (scheduler), `PLCReaderService` (S7-200), `PASReaderService` (PAS600L), `MezcladorReaderService` (DTB48), `PLCDataAcquisitionService`, `PLCDataQueryService` (lee SQLite), `ConfigLoaderService` (JSON externos), `RutaArchivosEnergia`, `MaquinasVirtuales` (`TemperaturaAgua`, `TemperaturaAmbiente`, `KWhPlanta1`), eventos (`SensorDataUpdateEvent`, `MaquinaEstadoCambioEvent`, ...), controladores SSE (`/api/plc/stream/...`) |
| `horometro` | Horas de funcionamiento | ver seccion 6 |
| `alarmas` | Alarmas por umbral | ver seccion 7 |
| `mezcladores` | Temperatura de mezcladores (DTB48) | ver seccion 8 |
| `mantenimiento` | Mantenimiento preventivo y stock | ver seccion 9 |
| `security` | Usuarios, roles, permisos | `Usuario`, `UsuarioRepository`, `UsuarioPrincipal`, `SecurityConfig`, `LineaAccessService`, `DataSeeder` (crea el admin inicial), `AdminSessionTimeoutFilter` |
| `tools` | Reparaciones puntuales | `ReparacionVipView`, `MergeVipMensualTool` |
| `config` | `JacksonConfiguration` | |

`Application.java` lleva `@EnableScheduling`, `@StyleSheet(Aura.STYLESHEET)`, `styles.css`, y
las anotaciones `@Uses` (seccion 10). Scheduler de 2 hilos (`ThreadPoolTaskScheduler`).

## 5. Seguridad, rutas y menu

**Usuario:** username, clave (hash), rol `ADMIN` o `USUARIO`, `zona` (ignorada si es ADMIN),
`habilitado`, y dos flags independientes de la zona: `verMezcladores` y `verMantenimiento`.
Usuarios en la base real a la fecha: `admin`, `mezcla`, `mantenimiento`, `produccion`, `LabMP`.
La clave del admin no se guarda en este archivo.

**`LineaAccessService`:** `getLineasPermitidas()` filtra por zona (ADMIN y zona Mantenimiento
ven todo), `puedeVerAlarmas()` (ADMIN o zona Mantenimiento), `puedeVerMezcladores()`,
`puedeVerMantenimiento()` (ADMIN o flag), `esAdmin()`, `usuarioActual()`.

**Patrones de acceso:**
- Solo admin: `@RolesAllowed("ADMIN")` en la ruta.
- Acceso por flag o zona: ruta `@PermitAll` + `BeforeEnterObserver` + clase `AccesoXxx.verificar(...)`
  (package-private, avisa con notificacion y hace `forwardTo(ChartsView.class)`). Existen
  `AccesoAlarmas`, `AccesoMezcladores`, `AccesoMantenimiento`.
- El checkbox del flag se agrega en `UsuariosView` (con tooltip: `Tooltip.forComponent(...)`,
  `withHoverDelay(200)`, `withHideDelay(5000)`; `Checkbox` no implementa `HasTooltip`).

| Ruta | Vista | Acceso |
|---|---|---|
| `login` | `LoginView` | anonimo |
| `grafica` | `ChartsView` | todos; pestanas segun zona/permisos (kWh, Temperatura, PF general, Mezcladores) |
| `historico` | `HistoricoView` | todos |
| `query` | `DataQueryView` | todos |
| `horometro` | `HorometroView` | todos (acciones extra solo ADMIN) |
| `alarmas` | `AlarmasHistorialView` (Alarmas Activas) | gate `puedeVerAlarmas()` |
| `alarmas/historial` | `AlarmasHistorialCompletoView` | ADMIN |
| `alarmas/config` | `AlarmasConfigView` | ADMIN |
| `configuracion` | `ConfiguracionView` (hardware) | ADMIN |
| `mezcladores/config` | `MezcladoresConfigView` | gate `puedeVerMezcladores()` |
| `mantenimiento` | `MantenimientoView` | gate `puedeVerMantenimiento()`; el formulario solo lo ve ADMIN |
| `mantenimiento/personal` | `PersonalMantenimientoView` | ADMIN |
| `mantenimiento/config` | `MantenimientoConfigView` | ADMIN |
| `reportes/mantenimiento` | `EstadoMantenimientoView` | gate `puedeVerMantenimiento()`; escribir en Stock solo ADMIN |
| `usuarios` | `UsuariosView` | ADMIN |
| `admin/reparar-vip` | `ReparacionVipView` | ADMIN |

**Menu lateral (`MainLayout.createSideNav()`):** Graficas (Tiempo Real, Historico), Consulta de
Datos, Horometro, Alarmas (Alarmas Activas; Historial solo admin), Mantenimiento Preventivo
(Mantenimiento Barril y Tornillos; Personal de Mantenimiento solo admin), Reportes (Barril y
Tornillo), y solo admin: Usuarios, Reparar VIP Mensual, Configuracion (alarmas, hardware,
Mezcladores, Mantenimiento). Los padres usan `colapsarAlSalirDelMouse(...)`. "Mantenimiento
Preventivo" y "Reportes" comparten el bloque `if (puedeVerMantenimiento())`. `MainLayout` tambien
tiene un poll que muestra avisos de alarmas en el navegador (solo `puedeVerAlarmas()`).

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
  default 3).
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
  (hoy: `Grid`, `ComboBox`, `NumberField`, `DateTimePicker`, `IntegerField`), y **borrar
  `src/main/bundles/prod.bundle` y regenerarlo** con `./mvnw.cmd -q -DskipTests compile`
  (el build incremental cree que el bundle viejo sirve). El bundle se commitea.
- Regla practica: si un modulo nuevo usa un componente Vaadin que ninguna vista usaba antes,
  agregar su `@Uses` y regenerar el bundle en el mismo cambio.

**Tema Aura, no Lumo:** los theme variants de Lumo (`badge`, `success`, `error`) no se pintan.
Para estados con color usar `Span` con estilo inline. Paleta usada: verde `#d4edda`/`#155724`,
amarillo `#fff3cd`/`#856404`, rojo `#f8d7da`/`#721c24`, gris `#e2e3e5`/`#383d41`.

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

## 12. Decisiones descartadas y pendientes

**Descartado**
- Avisos automaticos de vencimiento por globo de la bandeja de Windows: solo aparece en la PC donde
  corre el .exe, no en quien consulta por navegador. Si se retoma, la alternativa es un aviso dentro
  del navegador con poll, como las alarmas.
- Mostrar el estado de vencimiento dentro de la pantalla de registro (tapaba el formulario): vive
  en Reportes.
- Ejecutable nativo con GraalVM.

**Pendiente / ideas**
- **Modulo de Mantenimiento Correctivo (MF21 + AMEF):** diseno acordado, sin implementar. Ver
  `docs/PLAN-MANTENIMIENTO-CORRECTIVO.md`. Falta el AMEF del usuario.
- Ampliar la lista fija de "Tarea ejecutada" (hoy Cambio y Recalibracion).
- Mensaje amigable al intentar borrar un modelo de stock con movimientos o tareas asociadas.
- Los movimientos de stock no se pueden borrar ni corregir desde pantalla (por diseno, es historial).
- El usuario esta iniciando el CMM: cargara tareas atrasadas de meses anteriores (el calculo exacto
  del horometro se analizo y no es problema de rendimiento).
- Posible: aviso de stock minimo, mas equipos/planes ademas de Barril y Tornillo, mas reportes
  dentro del menu Reportes.

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
7. Compilar, commit, push a `master`, y que el usuario pruebe en el 8080.
8. **Actualizar este archivo** (seccion del modulo, rutas/menu de la seccion 5 y pendientes).
