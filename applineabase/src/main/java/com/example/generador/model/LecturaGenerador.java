package com.example.generador.model;

import java.time.LocalDateTime;

/**
 * Una lectura del controlador del generador (ComAp InteliGen 200). Solo los valores confirmados
 * contra la pantalla del controlador (docs/ig200/MAPA-REGISTROS.md). null = el controlador no
 * lo da en ese momento (0x8000).
 */
public record LecturaGenerador(
        LocalDateTime fecha,
        Double rpm,
        Double frecuencia,
        Double vL1N, Double vL2N, Double vL3N,
        Double vL1L2, Double vL2L3, Double vL3L1,
        Double bateria,
        Double presionAceite,
        Double tempRefrigerante,
        Long kwh,
        Long kvarh,
        Double horasMarcha,
        Integer arranques) {

    /** En marcha si el motor gira. */
    public boolean enMarcha() {
        return rpm != null && rpm > 0;
    }
}
