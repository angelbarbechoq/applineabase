package com.example.mantenimiento.model;

/** Ítem mantenible (nivel 8 ISO 14224) de un equipo, ej. "Barril y tornillo" de la Extrusora. */
public record ItemTag(String subunidad, String item, String codigoItem, String posicion, String tagExtendido) {

    /** Texto para mostrar en el selector: nombre + zona/posición si aplica, ej. "Banda de calentamiento (Zona 1)". */
    public String etiqueta() {
        return (posicion == null || posicion.isBlank()) ? item : item + " (" + posicion + ")";
    }
}
