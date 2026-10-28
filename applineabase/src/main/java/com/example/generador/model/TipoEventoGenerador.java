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
    SIN_CARGA("Queda en vacío", Nivel.NORMAL),
    FALLA_RED("Falla de red", Nivel.FALLA),
    RETORNO_RED("Retorno de red", Nivel.BUENO),
    ALARMA("Alarma", Nivel.FALLA),
    /** Sigue en la lista del controlador pero ya no está activa: falta el reset. */
    ALARMA_INACTIVA("Alarma ya no activa, falta reset", Nivel.AVISO),
    /** Confirmada en el controlador (se quitó el "*"); puede seguir activa. */
    ALARMA_CONFIRMADA("Alarma confirmada", Nivel.AVISO),
    ALARMA_RESUELTA("Alarma resuelta", Nivel.BUENO),
    SIN_COMUNICACION("Sin comunicación", Nivel.FALLA),
    COMUNICACION_RECUPERADA("Comunicación recuperada", Nivel.BUENO),
    /**
     * Se abrió el interruptor de la planta (MCB) y el generador quedó solo con su parte de la planta, aunque el
     * controlador siga viendo tensión de red. Con señales de red en el modelo lo dice el controlador
     * (RedControladorService); si no, lo detecta IslaService con lo guardado (medidor del transformador en 0).
     */
    ISLA("Paso a isla", Nivel.FALLA),
    /** Paso a isla con el selector del controlador en Isla (entrada Force Island): a propósito, sin alarma urgente. */
    ISLA_SELECTOR("Paso a isla con el selector", Nivel.AVISO),
    /** Selector de tres posiciones del controlador (AMF / sincronismo con la red / isla), entrada Force Island. */
    SELECTOR_ISLA("Selector en Isla", Nivel.AVISO),
    SELECTOR_FUERA_ISLA("Selector fuera de Isla", Nivel.NORMAL),
    /** Potencia sobre el límite de generador-config.json (alarmaPotenciaKw) y su vuelta por debajo. */
    POTENCIA_ALTA("Potencia sobre el límite", Nivel.AVISO),
    POTENCIA_NORMAL("Potencia vuelve bajo el límite", Nivel.BUENO),
    FIN_ISLA("Vuelve a paralelo", Nivel.BUENO),
    // Señales de red del controlador (modelos con "red" en generador-modelos.json, RedControladorService).
    /** El interruptor de la planta (MCB) según la entrada MCB Feedback del controlador. */
    INTERRUPTOR_RED_ABIERTO("Se abre el interruptor de la planta", Nivel.FALLA),
    INTERRUPTOR_RED_CERRADO("Se cierra el interruptor de la planta", Nivel.BUENO),
    /** ROCOF o salto de vector vistos activos en una lectura (actúan en menos de 1 s: casi nunca se ven). */
    PROTECCION_RED("Protección de red", Nivel.FALLA),
    /**
     * Subió el máximo de ROCOF o de salto de vector que guarda el controlador. Ya no se registra (hasta el 2026-10-27:
     * salían varios en cada marcha, porque el controlador reinicia el máximo al entrar en paralelo).
     */
    MAXIMO_RED("Máximo de ROCOF o salto de vector", Nivel.AVISO),
    /** Al salir de paralelo: máximos de ROCOF y salto de vector de esa marcha y su % del ajuste de disparo. */
    RESUMEN_RED("Máximos de ROCOF y salto de vector", Nivel.NORMAL),
    /** En paralelo, la ROCOF o el salto de vector pasaron la mitad del ajuste de disparo (una vez por marcha). */
    VARIACION_RED_ALTA("ROCOF o salto de vector alto", Nivel.AVISO),
    /** Modo del controlador (OFF, MAN, AUTO, TEST): fuera de AUTO no arranca solo. */
    CAMBIO_MODO("Cambio de modo del controlador", Nivel.AVISO),
    MODO_AUTO("Vuelve a AUTO", Nivel.BUENO),
    /** Ready To AMF de ComAp: si se corta la red, el generador entra solo. */
    NO_LISTO_AMF("Deja de estar listo para AMF", Nivel.FALLA),
    LISTO_AMF("Vuelve a estar listo para AMF", Nivel.BUENO),
    // Copiados del historial interno del controlador (pantalla History; no se lee por Modbus sin escribirle).
    // Fecha = hora real (la del controlador corregida por el desfase de su reloj); la descripción lleva el
    // motivo de ComAp y la hora del controlador. Primera carga: docs/ig500/historial-controlador-2026-10-12.csv.
    /** Protecciones de red (VectorShift, ROCOF, baja/alta tensión o frecuencia de red). */
    CONTROLADOR_PROTECCION("Controlador: protección de red", Nivel.FALLA),
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
