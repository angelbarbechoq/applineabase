# Registro de fallas y soluciones

Historial de inconvenientes tecnicos ya diagnosticados en este proyecto y como se resolvieron.
Se importa desde `CLAUDE.md`, asi que se carga solo en cada sesion.

**Como usarlo**
- Antes de diagnosticar un problema, revisar si ya esta aqui (sintomas parecidos suelen tener la
  misma causa).
- Al resolver una falla nueva, agregar una entrada `F-NN` con el formato de abajo y una fila en la
  tabla resumen. Mantenerlo corto: sintoma, como reconocerlo, causa, resolucion, prevencion.
- Este archivo es la fuente de verdad. `reportes/reporte-de-fallas.html` es la version para leer en
  el navegador; se regenera desde aqui cuando el usuario lo pida (ultima vez: 2026-09-25).

Ultima actualizacion: 2026-09-25.

## Resumen

| # | Falla | Causa principal | Estado |
|---|---|---|---|
| F-01 | Bloqueo de permisos en el asistente | Reglas sensibles a variaciones minimas del comando | Resuelto |
| F-02 | Credenciales desconocidas de H2 | Usuario/clave nunca configurados (por defecto, vacios) | Resuelto |
| F-03 | Componentes de Vaadin sin renderizar en rutas nuevas | Bundle desactualizado + carga diferida por defecto | Resuelto |
| F-04 | Estados sin color (badges) en las grillas | El tema del proyecto es Aura, no Lumo | Resuelto |
| F-05 | Campo Cantidad invisible en Stock | `IntegerField` sin `@Uses` (misma familia que F-03) | Resuelto |
| F-06 | Horometro de una tarea con error de ~8 h | Metodo viejo sumaba el dia completo, no hasta la hora | Resuelto |
| F-07 | Confusion de puerto 8080 / 8081 en las pruebas | Instancia del usuario ocupa el 8080 | Resuelto (flujo) |
| F-08 | Login por automatizacion del navegador no ingresa texto | Un clic simple no da foco al input interno | Resuelto |
| F-09 | Restaurar un commit viejo deja un arbol inconsistente | `git checkout <sha> -- .` no borra archivos nuevos | Resuelto (proceso) |

---

## F-01 Bloqueo de permisos en el asistente
- **Sintoma:** cada comando (git, Maven) pedia aprobacion manual aunque se hubieran autorizado
  comandos similares.
- **Causa:** los permisos comparan el comando como texto; `cd "ruta" &&` delante, tuberias con
  `| tail -N` distintos, o subcomandos de git no cubiertos invalidaban la regla.
- **Resolucion:** `"defaultMode": "bypassPermissions"` en `.claude/settings.local.json` y en
  `~/.claude/settings.json`.
- **Prevencion:** verificar que siga activo si vuelven los avisos. Los hooks de git peligroso
  (force-push, `branch -D`) siguen bloqueando aunque el modo sea bypass.

## F-02 Credenciales reales de la base H2
- **Sintoma:** no se conocian usuario/clave para abrir la base con una herramienta externa; el
  supuesto `sa` sin clave no funcionaba.
- **Causa:** `application.properties` nunca define `spring.datasource.username/password`, y Spring
  Boot usa cadena vacia para ambos.
- **Diagnostico:** un `@PostConstruct` temporal en un bean ya conectado imprimio
  `getMetaData().getUserName()` (vacio); un `CommandLineRunner` no imprimia nada (con `System.out`
  y en un bean ya activo si funciono). Confirmado por JDBC directo con usuario y clave vacios.
- **Resolucion:** usuario y clave **vacios**, URL `jdbc:h2:file:C:/LineaBaseX/data/lineabase;AUTO_SERVER=TRUE`.
  El codigo de diagnostico se retiro. **No** forzar `username=sa` en `application.properties`: rompe
  la conexion real.
- **Prevencion:** `AUTO_SERVER=TRUE` permite abrir la base por JDBC con la app corriendo (jar
  `h2-2.4.240` en `~/.m2`). La base es la real: toda prueba deja datos reales.

## F-03 Componentes de Vaadin sin renderizar en rutas nuevas
- **Sintoma:** en una pantalla nueva, `Grid`, `ComboBox`, `NumberField`, `DateTimePicker` no
  aparecian en la primera visita (campos de texto y botones si). A veces se arreglaba visitando otra
  pantalla y volviendo.
- **Como reconocerlo:** en la consola del navegador `customElements.get('vaadin-grid')` devuelve
  `false` aunque el servidor envio el elemento. Rutas antiguas funcionan (el navegador ya tiene esos
  modulos en cache). Se reproduce con una pantalla minima de un solo `Grid`, o sea no depende del
  contenido de la vista.
- **Causa (dos factores):** (1) `src/main/bundles/prod.bundle` desactualizado; el build incremental
  de Vaadin asumia que seguia valido. (2) Vaadin solo empaqueta de forma eager las rutas `""` y
  `/login`; las demas cargan sus componentes por demanda y, en una ruta nueva con bundle recien
  generado y sin cache, esa descarga puede perder una carrera y el componente nunca se registra.
- **Resolucion:** (1) borrar `src/main/bundles/prod.bundle` y regenerarlo desde cero con
  `./mvnw.cmd -q -DskipTests compile` (el goal `build-frontend` del `vaadin-maven-plugin`). (2) agregar
  `@Uses(Componente.class)` en `Application.java` para cada componente no basico. Verificado con
  varias pruebas consecutivas incluyendo reinicios completos.
- **Prevencion:** cada vez que un modulo use un componente Vaadin que ninguna vista usaba antes,
  agregar su `@Uses` y regenerar el bundle **en el mismo cambio**. El bundle se commitea. Lista actual
  de `@Uses`: `Grid`, `ComboBox`, `NumberField`, `DateTimePicker`, `IntegerField`.
- **Referencias:** vaadin/vaadin-grid-flow issue #557; documentacion de Vaadin sobre troubleshooting
  en produccion (`productionMode`).

## F-04 Estados sin color (badges) en las grillas
- **Sintoma:** la columna Estado del reporte se veia como texto plano, sin fondo verde/amarillo/rojo.
- **Causa:** el tema del proyecto es **Aura** (`@StyleSheet(Aura.STYLESHEET)`), no Lumo. Los theme
  variants de Lumo (`"badge"`, `"success"`, `"error"`) no se pintan.
- **Resolucion:** `Span` con estilo inline (`background-color`, `color`, `padding`, `border-radius`,
  `font-weight`). Paleta: verde `#d4edda`/`#155724`, amarillo `#fff3cd`/`#856404`, rojo
  `#f8d7da`/`#721c24`, gris `#e2e3e5`/`#383d41`.
- **Prevencion:** no usar theme variants de Lumo para colores; ver `EstadoMantenimientoView.badge(...)`.

## F-05 Campo Cantidad invisible en la pestana Stock
- **Sintoma:** el formulario de Stock de Barril y Tornillo no mostraba el campo Cantidad.
- **Causa:** `IntegerField` no estaba en la lista de `@Uses`; misma familia que F-03 (componente
  lazy en una ruta con bundle recien generado).
- **Resolucion:** `@Uses(IntegerField.class)` en `Application.java` y regenerar `prod.bundle`.
- **Prevencion:** ver la regla de F-03. Un componente "faltante" en una vista nueva es primero
  sospechoso de esto.

## F-06 Horometro de una tarea con error de ~8 h
- **Sintoma:** el horometro sugerido para una tarea registrada a las 09:54 mostraba 3257.0 h; el valor
  exacto a esa hora era 3249.1 h.
- **Causa:** `sumHorasHastaFecha` suma filas de `HorometroDiario` con `fecha <= :fecha`, o sea incluye
  el dia **completo**; el error de cada registro es lo que la linea trabajo despues de la hora de la
  tarea ese mismo dia. **No se acumula** con el tiempo: los dias anteriores son exactos y cada calculo
  parte de cero. Ademas "horas actuales" leia el total persistido y omitia el tramo ON en curso.
- **Resolucion:** `HorometroBackfillRunner.horasHastaMomento(linea, momento)` (suma exacta de dias
  anteriores + calculo desde datos crudos solo del dia de la tarea, cortado en la hora);
  `MantenimientoService.horasActuales` ahora usa `HorometroService.obtenerSnapshot(...)`.
- **Prevencion / rendimiento:** costo fijo por llamada (un `SUM` + un mes de SQLite filtrado a un dia,
  unas ~43 mil filas como maximo), no crece con la antiguedad; los crudos nunca se purgan. Los
  registros viejos no se recalculan solos: para corregirlos hay que borrarlos y volver a cargarlos.

## F-07 Confusion de puerto 8080 / 8081 en las pruebas
- **Sintoma:** el preview de Claude arrancaba en 8081 mientras el usuario prueba en 8080.
- **Causa:** el 8080 lo ocupa la instancia del propio usuario (IntelliJ, `com.example.Application`,
  base real). No es un rango reservado por Windows (`netsh interface ipv4 show excludedportrange
  protocol=tcp` salio vacio). Dos instancias no pueden compartir puerto.
- **Resolucion (flujo):** compilar, commit y push a `master`; el usuario hace pull, recompila y
  reinicia su instancia en el 8080 y prueba ahi. No levantar preview en el 8081 salvo pedido.
- **Prevencion:** no "corregir" `launch.json` a 8080 sin comprobar con `Get-NetTCPConnection
  -LocalPort 8080`; no matar el proceso que tiene el 8080 sin confirmar con el usuario.

## F-08 Login por automatizacion del navegador no ingresa texto
- **Sintoma:** al escribir usuario/clave con el navegador integrado, los campos quedaban vacios y el
  login fallaba con la clave correcta.
- **Causa:** un clic simple no da foco al `<input>` interno del componente web de Vaadin
  (`document.activeElement` quedaba en `BODY`).
- **Resolucion:** usar `double_click` para enfocar y verificar por JS los `.value` de
  `vaadin-text-field` y `vaadin-password-field` antes de enviar.

## F-09 Restaurar un commit viejo deja un arbol inconsistente
- **Sintoma:** al volver a un commit anterior con `git checkout <sha> -- .`, quedaban archivos que no
  existian en ese commit.
- **Causa:** ese comando solo restaura archivos presentes en el commit, no borra los agregados despues.
- **Resolucion:** para reflejar el arbol completo de un commit usar `git checkout <sha>` (detached
  HEAD) o crear una rama desde ese commit; para traer un arreglo puntual, `git cherry-pick`. Guardar
  antes el trabajo sin commitear con `git stash push -- <rutas>`.
- **Prevencion:** los borrados de rama no fusionada (`git branch -D`) y el force-push estan bloqueados
  por hook; requieren confirmacion explicita del usuario.

---

## Otros registros relacionados
- Carga de amCharts5 (CDN roto, causa raiz de red sin resolver): memoria `project_amcharts5_carga`.
- Estilo de respuesta (sin tildes, sin voseo): memoria `feedback_espanol_sin_acentos`.
