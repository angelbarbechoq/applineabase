# Instrucciones de trabajo para Claude Code en este repositorio

## Flujo de git: una sola línea activa (trunk-based)

El dueño de este proyecto trabaja solo, no quiere ramas de feature de larga
vida ni un historial de git ramificado. La única rama que importa es
`master`. No acumules ramas `claude/*` sin fusionar entre sesiones — eso es
justamente lo que generó semanas de acumulación y confusión (ver commits
"Unificar código duplicado" / "Fusionar proyecto-json..." del
2026-07-31 para contexto de cómo se limpió esa vez).

Si esta sesión trabaja en una rama separada (comportamiento por defecto del
entorno, ej. `claude/<slug>`), **antes de terminar la tarea**:

1. Verificar que compila y pasan los tests existentes.
2. Fusionar la rama de la sesión a `master` (merge normal, no hace falta
   squash) y pushear `master`.
3. Intentar borrar la rama ya fusionada en el remoto:
   `git push origin --delete claude/<slug>`.
   Si el entorno no tiene permiso para borrar ramas remotas (error 403 al
   pushear un delete), **no lo dejes implícito**: decile explícitamente al
   usuario qué rama(s) borrar a mano en GitHub (Settings → Branches, o la
   pestaña Branches del repo) y por qué es seguro hacerlo.
4. No dejes una rama sin fusionar "por las dudas" si el trabajo está
   terminado. Si el trabajo es exploratorio, riesgoso, o rompe algo, decilo
   explícitamente en vez de dejarla colgada para que se acumule.

## Ramas sin relación con `master`

Antes de fusionar cualquier rama, comprobar que comparte historia real con
`master`:

```
git merge-base origin/master origin/<rama>
```

Si no devuelve nada, es una **historia no relacionada** (no un simple
atraso) — probablemente una versión vieja/abandonada del proyecto. NO
fusionar con `--allow-unrelated-histories` sin preguntar antes. Explicar
la situación y pedir confirmación explícita sobre qué hacer con esa rama
(borrarla, archivarla, o revisar contenido primero).

## Trabajo local en IntelliJ

El usuario también trabaja localmente en IntelliJ además de usar Claude
Code Web. Si detectás que `master` local del usuario divergió del `master`
remoto (commits propios sin pushear), no asumas que son duplicados de algo
que ya fusionaste — comparalos por hash/mensaje antes de dar por hecho que
hay conflicto.
