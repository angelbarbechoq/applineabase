package com.example.calidad.model;

import com.example.medidores.model.ParametroMedidor;

import java.util.Map;

/**
 * Valores de calidad de energía de un medidor en un ciclo, ya en unidades estándar (kW, kVAR,
 * kVA, PF -1..1 decodificado, %). Un parámetro ausente del mapa se guarda vacío (el modelo no
 * lo tiene o esa lectura falló), nunca 0.
 *
 * @param desbalanceICalculado el desbalance de corriente lo calculó la app (el medidor no lo da)
 * @param desbalanceVCalculado ídem tensión
 */
public record LecturaCalidad(Map<ParametroMedidor, Double> valores,
                             boolean desbalanceICalculado,
                             boolean desbalanceVCalculado) {
}
