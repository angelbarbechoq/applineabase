package com.example.mantenimiento.model;

import java.util.List;

/** Equipo (nivel 6 ISO 14224) de una línea, ej. "Extrusora" (EXT-L01-XTR), con sus ítems mantenibles. */
public record EquipoTag(String codigo, String nombre, String salida, String tag, List<ItemTag> items) {

    /** Texto para mostrar en el selector, ej. "Tanque de Vacío (Salida A)". */
    public String etiqueta() {
        return (salida == null || salida.isBlank()) ? nombre : nombre + " (Salida " + salida + ")";
    }
}
