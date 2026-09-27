# Plan: modulo de Mantenimiento Correctivo (MF21 + AMEF)

Estado: **diseno acordado, sin implementar**. Falta el AMEF del usuario (ver seccion 13).
Creado: 2026-09-25. Ultima actualizacion: 2026-09-27.

Este documento resume lo acordado con el usuario para digitalizar el formato fisico **MF21**
(reporte de fallas) y conectar la estadistica de fallas con el **AMEF** y una taxonomia de
equipos editable. Al implementar, trasladar lo que quede vigente a
`docs/CONTEXTO-PROYECTO.md` (seccion nueva del modulo, rutas y pendientes).

---

## 1. Objetivo

- Reemplazar el MF21 en papel por un registro en la app, manteniendo su logica de dos partes
  (Clientes internos / Mantenimiento) y agregando la aceptacion o rechazo de produccion.
- Que la estadistica de fallas se base en **modos de falla por equipo y por tipo de equipo**,
  con listas tomadas del AMEF, no en texto libre.
- Medir: MTBF, MTTR, tiempo de respuesta, tiempo improductivo, retrabajo (rechazos), Pareto de
  modos de falla, carga de trabajo de mantenimiento.

## 2. Normas de referencia

| Norma | Para que se usa aqui |
|---|---|
| SAE JA1011 / JA1012 (RCM) | Cadena funcion > falla funcional > modo de falla > efecto > consecuencia |
| IEC 60812 | Metodo AMEF para equipos (SAE J1739 es automotriz de diseno/proceso, no aplica) |
| ISO 14224 | Taxonomia y estructura del registro de fallas: modo, mecanismo, causa y actividad como campos separados; solo cuentan como falla los eventos en que el equipo pierde su funcion por si mismo; exige identificar quien ejecuto, no un usuario por persona |
| ISO 9001 (informacion documentada) | Registros identificables y trazables; las siglas del MF21 cumplen si salen de un catalogo |

Citadas de memoria; confirmar definiciones exactas contra las copias del usuario.

## 3. Lo que muestra el MF21 actual (diagnostico)

Columnas: Hora aviso, Maquina, Linea, Supervisor, Descripcion de la falla, Improductivo S/N,
Tipo, Hora inicio, Hora fin, Trabajo realizado, Realizado por, Recibi conforme.

- La estructura de dos momentos (aviso / trabajo de mantenimiento) ya es la correcta: **se conserva**.
- "Trabajo realizado" mezcla en una frase elemento, modo, causa y accion
  (ej. "Disco defectuoso, se cambia discos de corte"). En la app se separan en campos.
- "Maquina" + "Linea" se convierten en TAG (`Acamp` + 4 = Acampanador de `EXT-L04`).
- Hora inicio / Hora fin normalmente quedan vacias: sin eso no hay MTTR. En la app son obligatorias.
- Fallas no reparadas en el momento (ej. sello mecanico de bomba, "se tramita OT") hoy son
  texto: en la app son un estado **Pendiente** con OT.
- El operador marca casi todo como improductivo: **Improductivo y Tipo los define mantenimiento**.
- Siglas de supervisor y tecnico escritas a mano: en la app salen de catalogos.

## 4. Decisiones acordadas

1. Aviso y trabajo separados. Produccion solo describe lo que ve; mantenimiento clasifica.
2. **Tipo** e **Improductivo (consecuencia)** los define mantenimiento, no el operador.
3. Categorias del papel **separadas** (ver seccion 5).
4. Solo el tipo **D (Dano)** entra en la estadistica de modos de falla, MTBF y AMEF. Los demas
   tipos se registran igual (carga de trabajo, tiempo perdido) pero no cuentan como falla del equipo.
5. **Regla general del proyecto:** los campos que afectan a la estadistica y/o KPI nunca se
   llenan a mano; salen de listas, catalogos o se calculan. Texto libre solo para descripciones.
6. Siempre existe la opcion **"No previsto"** + texto, para fallas que el AMEF no contempla.
   Esos casos se revisan y alimentan el AMEF (AMEF vivo).
7. Funciones y fallas funcionales cuelgan del **tipo de equipo** (Extrusora, Acampanador...).
   Modos de falla cuelgan del **tipo de elemento** mantenible (ej. Banda de calentamiento),
   definidos una sola vez por tipo; el registro guarda la **posicion** concreta (BZ1, BZ2...).
8. Estadistica en dos niveles: **por tipo de equipo** (tendencias, mas volumen de datos) y
   **por maquina** (historial).
9. **La falla la cierra produccion**, no mantenimiento: acepta el trabajo o lo rechaza con motivo
   obligatorio (ver seccion 6).
10. **Usuarios compartidos + personas de catalogo** (ver seccion 8).
11. **Taxonomia dinamica** en base de datos, por tipo de equipo, editable desde la app (ver seccion 9).

## 5. Clasificaciones

**Tipo de intervencion** (lo define mantenimiento):

| Codigo | Nombre | Que es | Cuenta como falla |
|---|---|---|---|
| D | Dano | Problema propio de la maquina | Si |
| A | Arranque | Parada por cambio de produccion | No |
| E | Externa | Causa ajena a la maquina | No |
| C | Calibracion / ajuste | Calibraciones en caliente, ajustes sin dano | No (se sigue aparte: si se repite puede anticipar una falla) |

Si es **E**, se elige el origen: Energia electrica, Agua, Aire comprimido, Materia prima, Otro.

**Consecuencia** (antes "Improductivo S/N", la define mantenimiento):

| Valor | Significado |
|---|---|
| Paro de linea | La linea se detuvo (perdida de disponibilidad) |
| Mala calidad | La linea siguio pero produjo defectuoso (perdida de calidad) |
| Sin efecto | No afecto la produccion |

## 6. Flujo y estados

```
Aviso (produccion)                                   --> ABIERTA
ABIERTA   --(mantenimiento registra una intervencion
             y la da por terminada)                  --> POR ACEPTAR
ABIERTA   --(no se puede reparar ahora, se genera OT)--> PENDIENTE --> (intervencion) --> POR ACEPTAR
POR ACEPTAR --(produccion acepta)                    --> CERRADA
POR ACEPTAR --(produccion rechaza, motivo obligatorio)--> ABIERTA (vuelve a mantenimiento)
```

- Cada paso de mantenimiento es una **intervencion** (hora inicio, hora fin, tecnicos, trabajo
  realizado). Un rechazo abre una nueva intervencion; las anteriores quedan en el historial.
- **Rechazo:** motivo obligatorio (texto) y quien rechaza (persona del catalogo). Queda guardado.
- Tiempo total de reparacion = suma de intervenciones (incluye retrabajo). Numero de rechazos =
  indicador de retrabajo por equipo y por tecnico.
- PENDIENTE y POR ACEPTAR siguen contando como abiertas en los reportes.

## 7. Campos

**Aviso** (produccion)
- Fecha y hora del aviso (por defecto ahora, editable).
- Linea y equipo (cascada de la taxonomia).
- Falla observada: lista de **fallas funcionales** del AMEF filtrada por el tipo de equipo
  (ej. "Campana defectuosa", "No limpia tuberia", "Corte defectuoso") + "No previsto".
- Comentario libre opcional.
- Reportado por: persona del catalogo de produccion (siglas), filtrada por el area del usuario.

**Intervencion** (mantenimiento; puede haber varias por falla)
- Hora inicio y hora fin (obligatorias para darla por terminada).
- Tecnico(s): catalogo `TecnicoMantenimiento` ya existente. # OT.
- Trabajo realizado (texto libre).
- Opcion "Queda pendiente" (con OT) en vez de "Terminada".

**Clasificacion** (mantenimiento; se completa en la intervencion que termina el trabajo, editable
hasta que produccion acepta)
- Tipo (D/A/E/C) y, si es E, origen.
- Consecuencia (Paro de linea / Mala calidad / Sin efecto).
- Solo si Tipo = D:
  - Elemento mantenible y posicion (de la taxonomia), o "No previsto" + texto.
  - Modo de falla (lista del AMEF para ese tipo de elemento, o "No previsto" + texto).
  - Mecanismo y causa (listas; base ISO 14224, ajustables con el AMEF).
  - Accion: Reemplazo, Reparacion, Ajuste, Limpieza, Lubricacion, Otro.
- Horometro del equipo al momento de la falla: se guarda solo (calculo exacto ya existente,
  `horasEnFecha`) para calcular MTBF en **horas de funcionamiento**. Solo aplica a equipos con
  medicion de energia (hoy extrusion).

**Aceptacion** (produccion)
- Acepta o rechaza, persona del catalogo, fecha/hora automatica, motivo si rechaza.

## 8. Usuarios, personas y permisos

- **Un usuario compartido por area:** Extrusion, Mezcla, Inyeccion (produccion) y Mantenimiento
  (ya existe `mantenimiento`). No se crea un usuario por persona.
- **Quien hizo cada cosa se elige de un catalogo, nunca texto libre** (evita "AB", "A.B.", "ab"
  como personas distintas):
  - Mantenimiento: catalogo de tecnicos existente.
  - Produccion: catalogo nuevo de personal (nombre, siglas, area), mismo patron de pantalla.
- La app guarda ademas la cuenta usada y la fecha/hora de cada accion.
- Permisos:
  - Produccion (zonas de produccion): dar aviso, aceptar/rechazar, ver sus fallas.
  - Mantenimiento (zona Mantenimiento) y ADMIN: intervenciones y clasificacion.
  - ADMIN: catalogos (taxonomia, AMEF, personal).
  - No hace falta un flag nuevo: alcanza con la zona del usuario.
- Riesgo aceptado: con usuario compartido cualquiera del area puede aceptar en nombre de otro
  (igual que en papel). Si hay disputas, agregar un PIN personal solo para aceptar/rechazar.
- **Inyeccion no existe hoy en la app** (ni zona, ni lineas, ni taxonomia): hay que darla de alta.

## 9. Taxonomia dinamica

Hoy `extrusion-tag-config.json` repite la lista completa de equipos y elementos en cada una de las
13 lineas y solo cubre extrusion. Se reemplaza por catalogos en base de datos:

| Catalogo | Contenido | Ejemplo |
|---|---|---|
| Tipo de equipo | Codigo, nombre, area | `ACP` Acampanador (Extrusion) |
| Tipo de elemento | Codigo, nombre, tipo de equipo al que pertenece | Molde de acampanado; Banda de calentamiento |
| Equipo de linea | Linea + tipo de equipo (+ numero si se repite) = TAG | `EXT-L04-ACP` |
| Posicion | Equipo de linea + tipo de elemento + posicion = TAG extendido | `EXT-L01-XTR-BZ1` |

- Se define **una vez por tipo**; cada linea solo declara que equipos tiene y sus posiciones.
- Editable desde una pantalla de ADMIN. Si al registrar una falla el elemento no existe,
  mantenimiento usa "No previsto" y el ADMIN lo agrega luego al catalogo.
- **El formato del TAG no cambia**, para que el preventivo siga funcionando. El JSON actual se
  importa una sola vez (seeder idempotente); despues manda la base.
- Un tipo o elemento con registros **no se borra ni se renombra, se desactiva** (historial intacto).
- Permite cargar Mezcla e Inyeccion, que hoy no tienen taxonomia.
- Huecos ya vistos: "Sensor de presencia" no aparece en la copia del repositorio del JSON (la copia
  real en `C:\LineaBaseX\config` no se reviso); "Sello mecanico" es una parte (nivel 9 ISO 14224)
  de "Bomba de agua": definir si se agregan como elementos o un campo opcional de "parte".

## 10. Catalogo AMEF en la app

Estructura propuesta (se ajusta cuando llegue el AMEF real):

| Nivel | Cuelga de | Ejemplo |
|---|---|---|
| Funcion | Tipo de equipo | "Acampanar el extremo del tubo segun norma" |
| Falla funcional | Funcion | "Campana defectuosa" |
| Modo de falla | Tipo de elemento + falla(s) funcional(es) | "Molde de acampanado desgastado", "Sensor de presencia sin senal" |
| Efecto / consecuencia / tarea | Modo de falla | Texto del AMEF (referencia) |

- Carga: importacion desde la planilla del AMEF o pantalla de administracion (decidir al ver el AMEF).

## 11. Reportes

- Fallas por estado (abiertas, pendientes con OT, por aceptar).
- MTTR (suma de intervenciones) y tiempo de respuesta (primera intervencion - aviso), por equipo
  y por tecnico.
- MTBF en horas de funcionamiento, solo tipo D, por tipo de equipo y por maquina.
- Pareto de modos de falla y de elementos, por tipo de equipo y por maquina.
- Tiempo improductivo por consecuencia (paro / calidad) y por tipo (D/A/E/C).
- Rechazos (retrabajo) por equipo y por tecnico, con sus motivos.
- Fallas externas por origen; calibraciones repetidas por equipo.
- Descarga CSV (patron `CsvUtil`).

## 12. Pantallas y menu (propuesta)

| Ruta | Pantalla | Acceso |
|---|---|---|
| `mantenimiento/correctivo` | Dar aviso + grilla de fallas; segun zona: registrar intervencion/clasificar (mantenimiento) o aceptar/rechazar (produccion), en dialogos desde la fila | Zonas de produccion, Mantenimiento, ADMIN |
| `reportes/correctivo` | Pestanas de reportes (seccion 11) | Mantenimiento, ADMIN |
| `mantenimiento/taxonomia` | Tipos de equipo, tipos de elemento, equipos por linea | ADMIN |
| `mantenimiento/correctivo/amef` | Funciones, fallas funcionales, modos | ADMIN |
| `mantenimiento/personal` (existente) | Agregar pestana de personal de produccion | ADMIN |

- Menu: "Mantenimiento Correctivo" junto al preventivo y "Correctivo" en Reportes. Evaluar
  renombrar el padre "Mantenimiento Preventivo" a "Mantenimiento".

## 13. Fases

1. **Fase 1 - Taxonomia dinamica:** catalogos en base, importacion del JSON, pantalla ADMIN, el
   preventivo pasa a leer de la base (mismos TAG).
2. **Fase 2 - Registro:** aviso, intervenciones, clasificacion, aceptacion/rechazo, estados,
   personal de produccion, alta de Inyeccion. Modo de falla como "No previsto" + texto mientras
   no haya AMEF. Ya reemplaza el papel.
3. **Fase 3 - AMEF:** catalogo, listas filtradas de fallas funcionales y modos, reclasificacion de
   los registros "No previsto".
4. **Fase 4 - Reportes:** seccion 11.
5. **Fase 5 - Mejoras:** sugerir "Paro de linea" desde los datos de energia (solo sugerencia: una
   falla en acampanador o cortadora no siempre apaga la extrusora), consumo de repuestos (hoy solo
   existe stock de Barril y Tornillo), aviso de fallas abiertas en el navegador, PIN para aceptar.

## 14. Pendientes y decisiones abiertas

- **AMEF del usuario:** estructura (columnas y niveles).
- Inyeccion: lineas, equipos y si tiene medicion de energia.
- Huecos de taxonomia (sensor de presencia, partes como sello mecanico) y revisar la copia real
  del JSON en `C:\LineaBaseX\config`.
- Listas iniciales de mecanismo y causa.

## 15. Notas tecnicas para la implementacion

- Paquete `com.example.correctivo` (y taxonomia en su propio paquete o en `mantenimiento`) con
  `model/`, `repository/`, `service/`, `ui/`; checklist de la seccion 13 de `CONTEXTO-PROYECTO.md`.
- Tipos, consecuencias y estados como `enum` (tienen logica), no texto libre.
- Reutilizar `resolverLineaMaquina`, `horasEnFecha` y el catalogo de tecnicos de
  `MantenimientoService`. Hoy `MantenimientoService` y la cascada del formulario leen el JSON: al
  pasar la taxonomia a base, cambiar esa lectura sin cambiar los TAG.
- Permisos por zona con `LineaAccessService` (zona Mantenimiento ya existe).
- Componentes Vaadin nuevos (ej. `TextArea`, `TimePicker`, `MultiSelectComboBox` si se usan):
  `@Uses` en `Application.java` y regenerar `prod.bundle` en el mismo cambio (F-03/F-05).
- Colores de estado con `Span` inline (tema Aura, F-04).
