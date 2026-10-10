package com.example.generador.model;

import java.time.LocalDateTime;

/**
 * Un evento del historial propio del generador: motivo, descripción y la foto de los valores en ese
 * momento (como las columnas del historial de ComAp). Valores null = no había lectura (por ejemplo,
 * sin comunicación) o el modelo no los mide.
 */
public record EventoGenerador(Long id, LocalDateTime fecha, TipoEventoGenerador tipo, String descripcion,
                              Double rpm, Double kw, Double pf, Double frecuencia, Double vGen, Double iGen,
                              Double redFrecuencia, Double vRed, Double bateria, Double tempRefrigerante,
                              Double horasMarcha, Long kwh, Integer arranques) {

    /** Evento con la foto de una lectura (o sin valores si {@code l} es null). */
    public static EventoGenerador de(LocalDateTime fecha, TipoEventoGenerador tipo, String descripcion, LecturaGenerador l) {
        if (l == null) {
            return new EventoGenerador(null, fecha, tipo, descripcion, null, null, null, null, null, null, null, null,
                    null, null, null, null, null);
        }
        return new EventoGenerador(null, fecha, tipo, descripcion, l.rpm(), l.kw(), l.pf(), l.frecuencia(),
                promedio(l.vL1L2(), l.vL2L3(), l.vL3L1()), promedio(l.iL1(), l.iL2(), l.iL3()), l.redFrecuencia(),
                promedio(l.redVL1L2(), l.redVL2L3(), l.redVL3L1()), l.bateria(), l.tempRefrigerante(), l.horasMarcha(),
                l.kwh(), l.arranques());
    }

    private static Double promedio(Double a, Double b, Double c) {
        return a == null || b == null || c == null ? null : Math.round((a + b + c) / 3.0 * 10) / 10.0;
    }
}
