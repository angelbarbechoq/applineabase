package com.example.dataacquisition;

/**
 * El factor de potencia (coseno de un ángulo) nunca supera 1 en valor absoluto por definición
 * física. Algunos medidores generales (KWhPlanta1 confirmado; posiblemente Trafo1/Trafo2, que
 * comparten la misma regla de alarma) lo reportan en escala de porcentaje (ej. -85.5), mientras
 * que el resto de las máquinas ya lo reportan directamente como fracción 0-1. Única función para
 * esta normalización — la usan HistoricoView (gráfico), AlarmaEvaluatorService (umbral de
 * alarma) y TarjetasEstadoActual (franja en vivo), para que las tres coincidan siempre en qué
 * escala usar sin tener que saber de antemano qué máquinas vienen en cuál escala.
 */
public final class FactorPotenciaUtil {

    private FactorPotenciaUtil() {
    }

    /** Recibe el valor ya en valor absoluto; lo divide entre 100 solo si supera el límite físico de 1. */
    public static double normalizarAbs(double pfAbs) {
        return pfAbs > 1.0 ? pfAbs / 100.0 : pfAbs;
    }
}
