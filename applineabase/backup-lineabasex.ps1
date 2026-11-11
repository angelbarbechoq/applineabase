# Backup de la base H2 (usuarios/alarmas/horometro) y de la configuracion (plc-config.json,
# linea-id-config.json, mezcladores-config.json). NO incluye el SQLite de energia/mezcladores
# (esa carpeta por año/mes queda afuera a proposito).
#
# Uso manual (una vez, ahora mismo):
#   powershell -ExecutionPolicy Bypass -File backup-lineabasex.ps1
#
# Uso programado (Programador de Tareas de Windows), diario de madrugada:
#   Accion: powershell.exe
#   Argumentos: -ExecutionPolicy Bypass -File "C:\ruta\completa\backup-lineabasex.ps1"

$origenData   = "C:\LineaBaseX\data"
$origenConfig = "C:\LineaBaseX\config"
$destinoBase  = "C:\Backups\LineaBaseX"   # <-- AJUSTAR: disco externo o carpeta de red real

$fecha = Get-Date -Format "yyyy-MM-dd_HHmm"
$destino = Join-Path $destinoBase $fecha
New-Item -ItemType Directory -Force -Path $destino | Out-Null

robocopy $origenData   (Join-Path $destino "data")   /E /R:2 /W:5 /NP
robocopy $origenConfig (Join-Path $destino "config") /E /R:2 /W:5 /NP

Write-Host ""
Write-Host "Backup completo en: $destino"
