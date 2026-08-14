# Instrucciones de trabajo para Claude Code en este repositorio

> Estas reglas se complementan con las reglas globales en
> `~/.claude/CLAUDE.md`, que aplican a todas las sesiones/repos del
> usuario. Force-push a `master`, merge con `--allow-unrelated-histories`
> y `git branch -D` sobre una rama no fusionada están **bloqueados por
> un hook** (`~/.claude/hooks/block-dangerous-git.sh`), no solo
> documentados — no depende de que el modelo se acuerde.

## CI/CD y empaquetado

El build, los tests y el empaquetado ya están automatizados en
`.github/workflows/`:

- [e2e.yml](.github/workflows/e2e.yml): en cada push/PR compila con
  Maven (`./mvnw package`, corre los tests de Java) y además corre la
  suite de regresión E2E con Playwright contra la app levantada.

El empaquetado del ejecutable portable (perfil `windows-app-image` en
`pom.xml`, jpackage) se corre local con `mvn package` en Windows, no
hay CI para eso — no hay release automático. Se descartó el intento de
ejecutable nativo con GraalVM (perfil `native`, `native:compile`): no
daba resultado, se sacó del proyecto (dependencia `svm` y el workflow
`compilar-nativo.yml`). No reintroducir GraalVM native-image sin que el
usuario lo pida explícitamente.

Como el flujo es trunk-based sin PRs, el CI corre *después* del push.
Si un push rompe el CI, la prioridad es arreglarlo hacia adelante o
revertir — no seguir trabajando en otra cosa sin avisar que `master`
quedó roto. No agregues `-DskipTests` al CI existente ni pipelines
nuevos redundantes sin revisar primero los que ya existen.

Esto es infraestructura ya existente, no una exigencia mía: el usuario
es el test, tanto en código como en deploy. No propongo agregar tests
nuevos ni condiciono una entrega a que "pasen los tests" — verifico que
compila/arranca, la validación de comportamiento la hace él.

## Flujo de git: una sola línea activa (trunk-based)

El dueño de este proyecto trabaja solo, no quiere ramas de feature de larga
vida ni un historial de git ramificado. La única rama que importa es
`master`. No acumules ramas `claude/*` sin fusionar entre sesiones — eso es
justamente lo que generó semanas de acumulación y confusión (ver commits
"Unificar código duplicado" / "Fusionar proyecto-json..." del
2026-07-31 para contexto de cómo se limpió esa vez).

Si esta sesión trabaja en una rama separada (comportamiento por defecto del
entorno, ej. `claude/<slug>`), **antes de terminar la tarea**:

1. Verificar que compila (la validación de comportamiento es manual,
   la hace el usuario).
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
