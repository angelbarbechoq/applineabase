package com.example.medidores.model;

/** Función Modbus con que se piden los registros del modelo. */
public enum FuncionLectura {

    HOLDING("03 - Holding registers"),
    INPUT("04 - Input registers");

    private final String etiqueta;

    FuncionLectura(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String getEtiqueta() {
        return etiqueta;
    }
}
