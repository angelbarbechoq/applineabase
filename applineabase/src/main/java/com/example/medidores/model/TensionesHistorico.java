package com.example.medidores.model;

import java.util.List;

/**
 * Qué tensiones del medidor se guardan en las tres columnas de tensión del historico VIP
 * (VAB, VAC, VBC). Las columnas son posicionales, con la convención del PLC: 1a tensión en VAB,
 * 2a en VAC, 3a en VBC (PAC_ADD del PLC: A-B, B-C, C-A; ION_ADD: V1, V2, V3).
 */
public enum TensionesHistorico {

    FASE_FASE("Fase-fase (A-B, B-C, C-A)", List.of(ParametroMedidor.VAB, ParametroMedidor.VBC, ParametroMedidor.VCA)),
    /** Lo que guarda hoy el PLC para el ION8600 (KWhPlanta1): Vln a, b, c. */
    FASE_NEUTRO("Fase-neutro (A-N, B-N, C-N)", List.of(ParametroMedidor.VAN, ParametroMedidor.VBN, ParametroMedidor.VCN));

    private final String etiqueta;
    private final List<ParametroMedidor> parametros;

    TensionesHistorico(String etiqueta, List<ParametroMedidor> parametros) {
        this.etiqueta = etiqueta;
        this.parametros = parametros;
    }

    public String getEtiqueta() {
        return etiqueta;
    }

    /** Las tres tensiones en el orden de las columnas VAB, VAC, VBC. */
    public List<ParametroMedidor> getParametros() {
        return parametros;
    }
}
