package com.example.medidores.model;

/** Orden de los registros de 16 bits en valores de 32/64 bits (Schneider: palabra alta primero). */
public enum OrdenPalabras {

    NORMAL("Palabra alta primero"),
    INVERTIDO("Palabra baja primero");

    private final String etiqueta;

    OrdenPalabras(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String getEtiqueta() {
        return etiqueta;
    }
}
