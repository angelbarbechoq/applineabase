package com.example.dataacquisition;

/**
 * El factor de potencia (coseno de un ángulo) nunca supera 1 en valor absoluto. El histórico
 * guarda tres formatos según el medidor y el camino de lectura:
 * - fracción 0-1 (PM710, PAC1020, y todo lo que se lee por pasarela desde 2026-10-08);
 * - 4 cuadrantes de Schneider PM5xxx leídos por PLC: entre 1 y 2 cuando es capacitivo
 *   (valor real = 2 - valor; ej. 1.055 -> 0.945);
 * - porcentaje (KWhPlanta1 / ION8600 leído por PLC: -95.5).
 * Única función para esta normalización: la usan HistoricoView (gráfico), AlarmaEvaluatorService
 * (umbral de alarma) y TarjetasEstadoActual (franja en vivo), para que las tres coincidan siempre.
 * Antes dividía por 100 todo valor mayor que 1, y un PM5110 capacitivo (1.055) salía como 0.01
 * (PF bajo falso en gráficos y alarmas).
 */
public final class FactorPotenciaUtil {

    private FactorPotenciaUtil() {
    }

    /** Recibe el valor ya en valor absoluto y lo devuelve como fracción 0-1. */
    public static double normalizarAbs(double pfAbs) {
        if (pfAbs > 2.0) {
            return pfAbs / 100.0; // porcentaje (ION8600 por PLC)
        }
        if (pfAbs > 1.0) {
            return 2.0 - pfAbs;   // 4 cuadrantes PM5xxx, zona capacitiva
        }
        return pfAbs;
    }
}
