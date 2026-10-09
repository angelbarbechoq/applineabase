# ComAp InteliGen 500 (generador Caterpillar, junto a TR1) - mapa de registros Modbus

Controlador: InteliGen 500. IP 192.168.0.201, Modbus TCP puerto 502, Unit ID 1 (a confirmar).
Red asociada: `Trafo1` (TR1). **Solo lectura**: la app nunca le escribe (funcion 03 unicamente).

Estado al 2026-10-09: cargado en `C:\LineaBaseX\config\generador-config.json` (nombre `Caterpillar`,
modelo `InteliGen500`). El mapa en `C:\LineaBaseX\config\generador-modelos.json` es una **copia del
InteliGen 200** (misma familia de ComAp) marcada `"confirmado": false`: la pantalla lo muestra en vivo
con un aviso amarillo, pero no se guarda historial ni arranques hasta confirmarlo. No se supone nada:
cada registro se confirma contra la pantalla del controlador o contra la lista del fabricante.

## Pasos para conectarlo
1. **Red:** cable de red del controlador a la red de planta. En el controlador (con la clave del
   tecnico), grupo de ajustes de comunicacion (Comms Settings / Ethernet): IP fija 192.168.0.201,
   mascara y puerta de enlace iguales a las del InteliGen 200.
2. **Modbus TCP:** en el mismo grupo, servidor Modbus = habilitado, puerto 502. La direccion del
   controlador (Controller Address) es el Unit ID: si no es 1, cambiar `unitId` en
   `generador-config.json`. Los nombres exactos de los ajustes dependen del firmware.
3. **Mejor opcion para el mapa:** si el tecnico se conecta con InteliConfig, pedirle que exporte la
   lista de registros Modbus del controlador (Generate Cfg Image > Modbus registers). Con esa lista
   se carga el mapa directo, sin comparar a mano.
4. **Verificar desde la app:** Grupo Electrogeno > Generadores > elegir Caterpillar.
   - "SIN COMUNICACION": falta red o Modbus (pasos 1 y 2).
   - Con valores: comparar con la pantalla del controlador RPM, frecuencia, tensiones, corrientes,
     kW, PF, bateria, aceite, temperatura, horas, arranques y kWh. Lo que no coincida o figure en
     "Sin respuesta del controlador en: ..." se corrige en el mapa.
5. **Fotos de registros** (pestana Registros, solo ADMIN): leer 41001 a 41300 (de a 100) con el
   generador parado, en marcha sin carga y con carga; "Guardar foto" en cada estado y sacar una
   foto de la pantalla del controlador en el mismo momento. Quedan en `C:\LineaBaseX\generador\fotos`.
6. **Confirmar:** con el mapa corregido, `"confirmado": true` en `generador-modelos.json`. Se guarda
   desde el minuto siguiente, sin reiniciar ni recompilar.

## Confirmados
(ninguno todavia)
