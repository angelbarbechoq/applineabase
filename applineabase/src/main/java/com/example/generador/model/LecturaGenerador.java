package com.example.generador.model;

import java.time.LocalDateTime;

/**
 * Una lectura del controlador del generador (ComAp InteliGen 200). Solo valores confirmados
 * (docs/ig200/MAPA-REGISTROS.md): contra la pantalla del controlador, y los de carga además
 * contra TR2. null = el controlador no lo da en ese momento (0x8000).
 * Los de red (redV*, redFrecuencia) se muestran en pantalla pero no se guardan (ya los mide TR2).
 */
public record LecturaGenerador(
        LocalDateTime fecha,
        Double rpm,
        Double frecuencia,
        Double vL1N, Double vL2N, Double vL3N,
        Double vL1L2, Double vL2L3, Double vL3L1,
        Double iL1, Double iL2, Double iL3,
        Double kw, Double kwL1, Double kwL2, Double kwL3,
        Double kvar, Double kva, Double pf,
        Double redFrecuencia, Double redVL1L2, Double redVL2L3, Double redVL3L1,
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
