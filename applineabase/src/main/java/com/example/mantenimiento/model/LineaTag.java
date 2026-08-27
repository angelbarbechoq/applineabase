package com.example.mantenimiento.model;

import java.util.List;

/** Línea de Extrusión con su catálogo de equipos, tal como viene de extrusion-tag-config.json. */
public record LineaTag(String lineaMaquina, String tagLinea, List<EquipoTag> equipos) {
}
