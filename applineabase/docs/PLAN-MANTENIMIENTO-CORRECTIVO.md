# Plan: modulo de Mantenimiento Correctivo (MF21 + AMEF)

Estado: **diseno acordado, sin implementar**. Falta el AMEF del usuario (ver seccion 11).
Fecha: 2026-09-25.

Este documento resume lo acordado con el usuario para digitalizar el formato fisico **MF21**
(reporte de fallas) y conectar la estadistica de fallas con el **AMEF** y la taxonomia de TAG
que ya existe en la app. Al implementar, trasladar lo que quede vigente a
`docs/CONTEXTO-PROYECTO.md` (seccion nueva del modulo, rutas y pendientes).

---

## 1. Objetivo

- Reemplazar el MF21 en papel por un registro en la app, manteniendo su logica de dos partes
  (Clientes internos / Mantenimiento).
- Que la estadistica de fallas se base en **modos de falla por equipo y por clase de equipo**,
  con listas tomadas del AMEF, no en texto libre.
- Medir: MTBF, MTTR, tiempo de respuesta, tiempo improductivo, Pareto de modos de falla, carga
  de trabajo de mantenimiento.

## 2. Normas de referencia

| Norma | Para que se usa aqui |
|---|---|
| SAE JA1011 / JA1012 (RCM) | Cadena funcion > falla funcional > modo de falla > efecto > consecuencia |
| IEC 60812 | Metodo AMEF para equipos (SAE J1739 es automotriz de diseno/proceso, no aplica) |
| ISO 14224 | Taxonomia (ya usada en el TAG) y estructura del registro de fallas: modo, mecanismo, causa y actividad como campos separados; solo cuentan como falla los eventos en que el equipo pierde su funcion por si mismo |

Citadas de memoria; confirmar definiciones exactas contra las copias del usuario.

## 3. Lo que muestra el MF21 actual (diagnostico)

Columnas: Hora aviso, Maquina, Linea, Supervisor, Descripcion de la falla, Improductivo S/N,
Tipo, Hora inicio, Hora fin, Trabajo realizado, Realizado por, Recibi conforme.

- La estructura de dos momentos (aviso / cierre) ya es la correcta: **se conserva**.
- "Trabajo realizado" mezcla en una frase elemento, modo, causa y accion
  (ej. "Disco defectuoso, se cambia discos de corte"). En la app se separan en campos.
- "Maquina" + "Linea" se convierten en TAG (`Acamp` + 4 = Acampanador de `EXT-L04`).
- Hora inicio / Hora fin normalmente quedan vacias: sin eso no hay MTTR. En la app son obligatorias al cerrar.
- Fallas no reparadas en el momento (ej. sello mecanico de bomba, "se tramita OT") hoy son
  texto: en la app son un estado **Pendiente** con OT.
- El operador marca casi todo como improductivo: **Improductivo y Tipo los define mantenimiento**.

## 4. Decisiones acordadas

1. Aviso y cierre separados. El operador solo describe lo que ve; mantenimiento clasifica.
2. **Tipo** e **Improductivo (consecuencia)** los define mantenimiento al cerrar, no el operador.
3. Categorias del papel **separadas** (ver seccion 5).
4. Solo el tipo **D (Dano)** entra en la estadistica de modos de falla, MTBF y AMEF. Los demas
   tipos se registran igual (carga de trabajo, tiempo perdido) pero no cuentan como falla del equipo.
5. Listas cerradas para lo que alimenta calculos/filtros; texto libre para descripciones.
6. Siempre existe la opcion **"No previsto"** + texto, para fallas que el AMEF no contempla.
   Esos casos se revisan y alimentan el AMEF (AMEF vivo).
7. Funciones y fallas funcionales cuelgan del **equipo** (clase de equipo, ej. Extrusora,
   Acampanador). Modos de falla cuelgan del **tipo de elemento** mantenible (ej. Banda de
   calentamiento), definidos una sola vez por tipo; el registro guarda la **posicion** concreta
   (BZ1, BZ2...) via TAG extendido.
8. Estadistica en dos niveles: **por clase** (tendencias, mas volumen de datos) y **por maquina** (historial).

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
Aviso (operador/supervisor) --> ABIERTA
ABIERTA --(mantenimiento atiende y cierra)--> CERRADA
ABIERTA --(no se puede reparar ahora, se genera OT)--> PENDIENTE --> CERRADA
CERRADA --(produccion da el recibi conforme)--> CONFORME
```

- Una falla PENDIENTE sigue contando como abierta en los reportes (no distorsiona el MTTR).
- Pendiente de definir: si produccion puede rechazar el cierre (volver a ABIERTA).

## 7. Campos

**Aviso** (operador o supervisor)
- Fecha y hora del aviso (por defecto ahora, editable).
- Linea y equipo (cascada de TAG, igual que el preventivo).
- Falla observada: lista de **fallas funcionales** del AMEF filtrada por la clase de equipo
  (ej. "Campana defectuosa", "No limpia tuberia", "Corte defectuoso") + "No previsto".
- Comentario libre opcional.
- Supervisor / quien reporta (usuario logueado; supervisor de una lista o texto, a definir).

**Cierre** (mantenimiento)
- Tipo (D/A/E/C) y, si es E, origen.
- Consecuencia (Paro de linea / Mala calidad / Sin efecto).
- Hora inicio y hora fin del trabajo (obligatorias para cerrar).
- Tecnico(s) (catalogo `TecnicoMantenimiento` ya existente), # OT.
- Trabajo realizado (texto libre).
- Solo si Tipo = D:
  - Elemento mantenible y posicion (TAG extendido, de la taxonomia).
  - Modo de falla (lista del AMEF para ese tipo de elemento, o "No previsto" + texto).
  - Mecanismo y causa (listas; base ISO 14224, ajustables con el AMEF).
  - Accion: Reemplazo, Reparacion, Ajuste, Limpieza, Lubricacion, Otro.
- Horometro del equipo al momento de la falla: se guarda solo (calculo exacto ya existente,
  `horasEnFecha`) para calcular MTBF en **horas de funcionamiento**, no en horas calendario.

**Conformidad** (produccion)
- Recibi conforme: usuario y fecha/hora.

## 8. Catalogo AMEF en la app

Estructura propuesta (se ajusta cuando llegue el AMEF real):

| Nivel | Cuelga de | Ejemplo |
|---|---|---|
| Funcion | Clase de equipo (codigo del equipo en la taxonomia, ej. `ACP`) | "Acampanar el extremo del tubo segun norma" |
| Falla funcional | Funcion | "Campana defectuosa" |
| Modo de falla | Tipo de elemento + falla(s) funcional(es) | "Molde de acampanado desgastado", "Sensor de presencia sin senal" |
| Efecto / consecuencia / tarea | Modo de falla | Texto del AMEF (referencia) |

- Tipo de elemento = nombre del item de la taxonomia (BZ1..BZ4 son todas "Banda de calentamiento").
- Carga del catalogo: importacion desde la planilla del AMEF o pantalla de administracion
  (decidir al ver el AMEF).
- Huecos de taxonomia detectados: "Sensor de presencia" no esta como elemento; "Sello mecanico" es
  una parte (nivel 9 ISO 14224) de "Bomba de agua". Definir si se agregan elementos o un campo
  opcional de "parte".

## 9. Reportes

- Fallas por estado (abiertas, pendientes con OT, cerradas sin conformidad).
- MTTR (fin - inicio) y tiempo de respuesta (inicio - aviso), por equipo y por tecnico.
- MTBF en horas de funcionamiento, solo tipo D, por clase de equipo y por maquina.
- Pareto de modos de falla y de elementos, por clase y por maquina.
- Tiempo improductivo por consecuencia (paro / calidad) y por tipo (D/A/E/C).
- Fallas externas por origen; calibraciones repetidas por equipo.
- Descarga CSV (patron `CsvUtil`).

## 10. Pantallas, acceso y menu (propuesta)

| Ruta | Pantalla | Acceso (a confirmar) |
|---|---|---|
| `mantenimiento/correctivo` | Registrar aviso + grilla de fallas abiertas/pendientes; cierre en dialogo desde la fila | Aviso: usuarios con acceso a la linea. Cierre: ADMIN o flag nuevo de mantenimiento |
| `reportes/correctivo` | Pestanas de reportes (seccion 9) | gate de mantenimiento |
| `mantenimiento/correctivo/amef` | Catalogo AMEF (funciones, fallas funcionales, modos) | ADMIN |

- Menu: agregar "Mantenimiento Correctivo" junto al preventivo y "Correctivo" en Reportes.
  Evaluar renombrar el padre "Mantenimiento Preventivo" a "Mantenimiento".
- Recibi conforme: usuarios de produccion de la linea.

## 11. Fases

1. **Fase 1 - Registro basico:** entidades, aviso, cierre, estados, tipos y consecuencias,
   elemento desde la taxonomia, modo de falla como "No previsto" + texto mientras no haya AMEF.
   Permite empezar a reemplazar el papel.
2. **Fase 2 - AMEF:** catalogo (importacion o pantalla), listas filtradas de fallas funcionales
   y modos, reclasificacion de los registros "No previsto" de la fase 1.
3. **Fase 3 - Reportes:** seccion 9.
4. **Fase 4 - Mejoras:** sugerir "Paro de linea" desde los datos de energia (solo sugerencia: una
   falla en acampanador o cortadora no siempre apaga la extrusora), consumo de repuestos (hoy
   solo existe stock de Barril y Tornillo), aviso de fallas abiertas en el navegador.

## 12. Pendientes y decisiones abiertas

- **AMEF del usuario:** estructura (columnas y niveles) para definir el catalogo y la carga.
- Quien reporta: usuarios existentes por zona o usuarios nuevos para operadores/supervisores.
- Flag de acceso para el cierre (nuevo o reutilizar `verMantenimiento`).
- Supervisor: lista cerrada o texto.
- Si produccion puede rechazar un cierre.
- Huecos de taxonomia (sensor de presencia, partes como sello mecanico).
- Listas iniciales de mecanismo y causa.
- Mezcla y Casa Fuerza no tienen taxonomia: definir si entran en el correctivo desde el inicio.

## 13. Notas tecnicas para la implementacion

- Paquete `com.example.correctivo` (o subpaquete de `mantenimiento`) con `model/`, `repository/`,
  `service/`, `ui/`; checklist de la seccion 13 de `CONTEXTO-PROYECTO.md`.
- Tipos y consecuencias como `enum` (tienen logica), no texto libre.
- Reutilizar la cascada de TAG, `resolverLineaMaquina`, `horasEnFecha` y el catalogo de tecnicos
  de `MantenimientoService`.
- Componentes Vaadin nuevos (ej. `TextArea`, `TimePicker`, `MultiSelectComboBox` si se usan):
  `@Uses` en `Application.java` y regenerar `prod.bundle` en el mismo cambio (F-03/F-05).
- Colores de estado con `Span` inline (tema Aura, F-04).
