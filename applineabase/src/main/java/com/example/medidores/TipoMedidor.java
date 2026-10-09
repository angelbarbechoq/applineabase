package com.example.medidores;

/**
 * Qué mide cada entrada de linea-id-config.json (campo "tipo"). Decide qué se suma en los balances
 * por transformador: solo las MAQUINA que no están dentro de otro medidor.
 */
public enum TipoMedidor {
    MAQUINA("Maquina"),
    TRANSFORMADOR("Transformador"),
    GENERAL("Medidor general"),
    SENSOR("Sensor (no mide energia)"),
    NO_CONTAR("No se cuenta");

    private final String etiqueta;

    TipoMedidor(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String etiqueta() {
        return etiqueta;
    }

    /** Sin campo "tipo" = máquina (configs anteriores a este campo). */
    public static TipoMedidor de(Object valor) {
        if (valor == null) return MAQUINA;
        try {
            return valueOf(String.valueOf(valor).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MAQUINA;
        }
    }
}
