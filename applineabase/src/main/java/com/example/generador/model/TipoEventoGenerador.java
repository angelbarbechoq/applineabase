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
    ALARMA_RESUELTA("Alarma resuelta", Nivel.BUENO),
    SIN_COMUNICACION("Sin comunicacion", Nivel.FALLA),
    COMUNICACION_RECUPERADA("Comunicacion recuperada", Nivel.BUENO);

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
