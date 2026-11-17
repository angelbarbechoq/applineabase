# Guia: app + backend recolector, pruebas y paso al servidor

Banderas y carpetas para correr la app (applineabase, puerto 8080) con el backend recolector (backendMySQLSGEn,
puerto 8081). Revisado en el codigo el 2026-11-17. Plan general en `PLAN-COLECTOR-MYSQL.md`.

## 1. Banderas

En la app las dos banderas de abajo tambien estan en `src/main/resources/application.properties` (cambiar ahi y reiniciar; dejar `true` antes de commitear o empaquetar). Una bandera se escribe al final del comando (o en IntelliJ, en **Run > Edit Configurations > Program arguments**),
separadas por espacio. Lo que no se escribe toma el valor de `application.properties`.

### App (applineabase)

| Bandera | Valor por defecto | Que hace |
|---|---|---|
| `--app.adquisicion.habilitada=false` | `true` | Apaga la lectura de equipos de la app (`DataAcquisitionTask`: lectura de 60 s, captura al segundo y sirena) y enciende el vigia de archivos (`VigiaArchivosService`), que toma lo en vivo de los archivos que escribe el backend. Sin la bandera, la app lee los equipos como siempre. |
| `--app.backend.url=http://localhost:8081` | `http://localhost:8081` | Solo con la lectura apagada: donde la app pregunta cada 5 s si el backend trabaja (`/api/estado`). Etiqueta arriba a la derecha: "Datos del backend: en línea" (verde), "sin leer" (amarillo), "fuera de línea" (rojo; con 30 s sin respuesta, alarma urgente que se cierra sola). Sin la bandera dice "La app lee los equipos" (gris). La ve quien ve las alarmas. |
| `--server.port=8080` | `8080` | Puerto de la app. |

Comprobado el 2026-11-17: el ejecutable empaquetado `applineabase.exe` recibe las banderas escritas despues de el.

### Backend (backendMySQLSGEn)

| Bandera | Valor por defecto | Que hace |
|---|---|---|
| `--colector.sqlite.carpeta=C:\LineaBaseX` | `C:\LineaBaseX-prueba` | Donde escribe los archivos SQLite y el archivo de estado `estado\equipos`. |
| `--colector.lectura.habilitada=false` | `true` | Apaga la lectura de equipos del backend (la pagina sigue abierta). |
| `--colector.almacen=sqlite` | `sqlite` | `sqlite` = mismos archivos que la app; `memoria` = no guarda nada. |
| `--colector.ciclo.segundo=30` | `30` | Segundo de cada minuto en que lee (por el reloj de la PC). Al arrancar, si faltan 30 s o mas para esa lectura, hace una lectura de prueba que no guarda nada (se ve en su pagina y en la etiqueta de la app); si faltan menos, espera la del reloj. |
| `--colector.comparar.carpeta=C:\LineaBaseX` | `C:\LineaBaseX` | Carpeta de la app, solo para "Comparar con la app" (solo lectura). |
| `--server.port=8081` | `8081` | Puerto de la pagina del backend. |

Fijo, sin bandera: la configuracion de equipos se lee de `C:\LineaBaseX\config` y el catalogo de medidores de la base
H2 de la app (`C:\LineaBaseX\data\lineabase`, solo lectura).

## 2. Carpetas

| Carpeta | Quien escribe | Que hay |
|---|---|---|
| `C:\LineaBaseX\config` | App (pantallas de configuracion) | JSON de equipos; el backend solo lee. |
| `C:\LineaBaseX\data\lineabase` | App | Base H2; el backend solo lee el catalogo de medidores. |
| `C:\LineaBaseX\{anio}\{mes}` | La app **o** el backend, nunca los dos | SQLite de energia, VIP, calidad y generador. |
| `C:\LineaBaseX\estado\equipos` | Backend (si escribe en `C:\LineaBaseX`) | Comunicacion de cada linea; la app lo lee con la bandera puesta. |
| `C:\LineaBaseX\generador` | App | Arranques, eventos y capturas de los generadores (pasan al backend en el paso 3). |
| `C:\LineaBaseX-prueba` | Backend (valor por defecto) | Copia de prueba; se puede borrar cuando no se use. |
| `C:\LineaBaseX\backend` | (solo servidor) | Programa del backend: `backendMySQLSGEn.exe` e `iniciar-backend.bat`. |

## 3. Las tres formas de correrlos

| | App | Backend | Uso |
|---|---|---|---|
| **A. Normal (como hoy)** | sin bandera | apagado (la app lo cierra si esta abierto) | Produccion. |
| **B. Backend recolecta** | `--app.adquisicion.habilitada=false` | `--colector.sqlite.carpeta=C:\LineaBaseX` | Lo nuevo. La app grafica y analiza lo que guarda el backend. |
| **Volver a A** | quitar la bandera y reiniciar | detener, o reiniciar sin la bandera de carpeta | Vuelta atras inmediata. |

**Nunca:** la app sin bandera y el backend en `C:\LineaBaseX` al mismo tiempo. Los dos escribirian los mismos archivos.
Proteccion: la app sin bandera (lee los equipos) le pide al backend que se cierre al arrancar y despues cada minuto
(`POST /api/cerrar` en `app.backend.url`; el backend solo lo acepta desde la misma PC). Si el backend no esta corriendo
no pasa nada. Por eso con la app sin bandera el backend no puede quedar abierto, ni siquiera en `C:\LineaBaseX-prueba`.

**Hasta terminar el paso 3, en la forma B no funcionan** (en la PC donde la app tiene la bandera): arranques, historial
de eventos, isla, alarmas de los generadores, captura al segundo y sirena. Graficas, horometro, alarmas de planta y
calidad si funcionan. Por eso en el servidor se usa la forma A hasta el paso 3.

## 4. Prueba en esta PC (diseno, IntelliJ)

El servidor sigue en la forma A y no se toca.

1. Backend: pull del repositorio `backendMySQLSGEn`. Program arguments: `--colector.sqlite.carpeta=C:\LineaBaseX`. Run.
2. App: pull de `applineabase`. Program arguments: `--app.adquisicion.habilitada=false`. Run.
3. En el registro de la app debe aparecer: `Lectura de equipos de la app APAGADA`.
4. En el registro del backend, cada minuto: `Ciclo ...: N filas, N guardadas`.
5. En la app: Tiempo Real avanza cada minuto, las tarjetas y el horometro se mueven, las alarmas de planta se abren y
   cierran. Pagina del backend: http://localhost:8081.
6. Para terminar: detener el backend y volver a correr la app sin la bandera.

## 5. En el servidor

### Preparar (en esta PC)
1. Backend: en la carpeta `backendMySQLSGEn`, `mvnw.cmd package`. Queda `target\dist\backendMySQLSGEn\` con
   `backendMySQLSGEn.exe` y su propio Java (igual que la app, perfil `windows-app-image`; unos 190 MB). Probado el
   2026-11-17.
2. App: empaquetar como siempre (perfil `windows-app-image`). Queda `target\dist\applineabase\`.

### Instalar (en el servidor)
1. Copiar la carpeta `backendMySQLSGEn` a `C:\LineaBaseX\backend\` (queda `C:\LineaBaseX\backend\backendMySQLSGEn.exe`)
   y dentro `servidor\iniciar-backend.bat` del repositorio del backend. No hace falta instalar Java.
2. App: acceso directo a `applineabase.exe`. Para la forma B, en Propiedades del acceso directo > Destino, agregar al
   final ` --app.adquisicion.habilitada=false`.

### Orden para pasar a la forma B
1. Cerrar la app.
2. Abrir `iniciar-backend.bat` y esperar un ciclo (en su ventana: `Ciclo ...: N filas`).
3. Abrir la app con el acceso directo con la bandera.

Para volver: cerrar las dos ventanas y abrir la app con el acceso directo sin la bandera.
