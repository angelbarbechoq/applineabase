package com.example.calidad.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Indicadores de calidad de energía que se evalúan contra los límites. Cada uno se obtiene de las
 * columnas del archivo de calidad de una fila (un minuto) o de un promedio de 10 minutos.
 */
public enum Indicador {

    THD_TENSION("THD tension", "%"),
    THD_CORRIENTE("THD corriente", "%"),
    DESBALANCE_TENSION("Desbalance tension", "%"),
    DESBALANCE_CORRIENTE("Desbalance corriente", "%"),
    TENSION("Tension (promedio fase-fase)", "V"),
    FRECUENCIA("Frecuencia", "Hz"),
    FACTOR_POTENCIA("Factor de potencia", "");

    private final String etiqueta;
    private final String unidad;

    Indicador(String etiqueta, String unidad) {
        this.etiqueta = etiqueta;
        this.unidad = unidad;
    }

    public String getEtiqueta() {
        return etiqueta;
    }

    public String getUnidad() {
        return unidad;
    }

    /** Valor del indicador a partir de las columnas de calidad (null si el medidor no lo da). */
    public Double valor(Map<String, Double> c) {
        return switch (this) {
            case THD_TENSION -> {
                Double ll = max(c, "THD_VAB", "THD_VBC", "THD_VCA");
                yield ll != null ? ll : max(c, "THD_VAN", "THD_VBN", "THD_VCN");
            }
            case THD_CORRIENTE -> max(c, "THD_IA", "THD_IB", "THD_IC");
            case DESBALANCE_TENSION -> c.get("DESBALANCE_V");
            case DESBALANCE_CORRIENTE -> c.get("DESBALANCE_I");
            case TENSION -> promedio(c, "VAB", "VBC", "VCA");
            case FRECUENCIA -> c.get("FRECUENCIA");
            case FACTOR_POTENCIA -> c.get("PF_TOTAL") == null ? null : Math.abs(c.get("PF_TOTAL"));
        };
    }

    /** Columnas del archivo de calidad que se grafican para este indicador (una serie por fase). */
    public List<String> columnasGrafico() {
        return switch (this) {
            case THD_TENSION -> List.of("THD_VAB", "THD_VBC", "THD_VCA");
            case THD_CORRIENTE -> List.of("THD_IA", "THD_IB", "THD_IC");
            case DESBALANCE_TENSION -> List.of("DESBALANCE_V");
            case DESBALANCE_CORRIENTE -> List.of("DESBALANCE_I");
            case TENSION -> List.of("VAB", "VBC", "VCA");
            case FRECUENCIA -> List.of("FRECUENCIA");
            case FACTOR_POTENCIA -> List.of("PF_TOTAL");
        };
    }

    private static Double max(Map<String, Double> c, String... cols) {
        return List.of(cols).stream().map(c::get).filter(Objects::nonNull).max(Double::compare).orElse(null);
    }

    private static Double promedio(Map<String, Double> c, String... cols) {
        List<Double> v = List.of(cols).stream().map(c::get).filter(Objects::nonNull).toList();
        return v.size() == cols.length ? v.stream().mapToDouble(Double::doubleValue).average().orElse(0) : null;
    }
}
