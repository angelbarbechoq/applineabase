package com.example.generador.model;

/**
 * Lista cerrada de eventos del historial propio del generador (a semejanza del historial de ComAp:
 * motivo + valores del momento). Los detecta la app leyendo el controlador cada minuto; nunca se le
 * escribe al controlador.
 */
public enum TipoEventoGenerador {
    INICIO_LECTURA("Inicio de lectura", Nivel.NORMAL),
    ARRANQUE("Arranque", Nivel.BUENO),
    ARRANQUE_BREVE("Arranque breve", Nivel.AVISO),
    PARADA("Parada", Nivel.NORMAL),
    TOMA_CARGA("Toma carga", Nivel.BUENO),
    SIN_CARGA("Queda en vacio", Nivel.NORMAL),
    FALLA_RED("Falla de red", Nivel.FALLA),
    RETORNO_RED("Retorno de red", Nivel.BUENO),
    ALARMA("Alarma", Nivel.FALLA),
    /** Sigue en la lista del controlador pero ya no está activa: falta el reset. */
    ALARMA_INACTIVA("Alarma ya no activa, falta reset", Nivel.AVISO),
    /** Confirmada en el controlador (se quitó el "*"); puede seguir activa. */
    ALARMA_CONFIRMADA("Alarma confirmada", Nivel.AVISO),
    ALARMA_RESUELTA("Alarma resuelta", Nivel.BUENO),
    SIN_COMUNICACION("Sin comunicacion", Nivel.FALLA),
    COMUNICACION_RECUPERADA("Comunicacion recuperada", Nivel.BUENO),
    /**
     * Se abrió el interruptor de la planta (MCB) y el generador quedó solo con su parte de la planta, aunque el
     * controlador siga viendo tensión de red. Lo detecta IslaService con lo guardado (medidor del transformador en 0).
     */
    ISLA("Paso a isla", Nivel.FALLA),
    FIN_ISLA("Vuelve a paralelo", Nivel.BUENO),
    // Copiados del historial interno del controlador (pantalla History; no se lee por Modbus sin escribirle).
    // Fecha = hora real (la del controlador corregida por el desfase de su reloj); la descripción lleva el
    // motivo de ComAp y la hora del controlador. Primera carga: docs/ig500/historial-controlador-2026-10-12.csv.
    /** Protecciones de red (VectorShift, ROCOF, baja/alta tensión o frecuencia de red). */
    CONTROLADOR_PROTECCION("Controlador: proteccion de red", Nivel.FALLA),
    /** Apertura y cierre de los interruptores de la planta (MCB) y del generador (GCB). */
    CONTROLADOR_INTERRUPTOR("Controlador: interruptor", Nivel.AVISO),
    /** El resto del historial del controlador (Loaded, RevSyncStarted, Cooling, Ready...). */
    CONTROLADOR_EVENTO("Controlador: evento", Nivel.NORMAL);

    /** Para el color en pantalla. */
    public enum Nivel { NORMAL, BUENO, AVISO, FALLA }

    private final String etiqueta;
    private final Nivel nivel;

    TipoEventoGenerador(String etiqueta, Nivel nivel) {
        this.etiqueta = etiqueta;
        this.nivel = nivel;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public Nivel nivel() {
        return nivel;
    }
}
