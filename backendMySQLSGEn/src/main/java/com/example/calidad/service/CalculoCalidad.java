package com.example.calidad.service;

import com.example.calidad.model.LecturaCalidad;
import com.example.medidores.model.ParametroMedidor;
import com.example.medidores.service.DefinicionModelo;
import com.example.medidores.service.LectorMedidorService;

import java.util.EnumMap;
import java.util.Map;

/**
 * Arma la lectura de calidad de un medidor a partir de lo leído: PF de 4 cuadrantes al valor
 * real y desbalances calculados cuando el medidor no los entrega (PM710, PAC1020).
 */
public final class CalculoCalidad {

    /** Por debajo de este promedio (máquina parada) el desbalance no tiene sentido y queda vacío. */
    private static final double CORRIENTE_MINIMA_A = 1.0;
    private static final double TENSION_MINIMA_V = 10.0;

    private CalculoCalidad() {
    }

    public static LecturaCalidad construir(DefinicionModelo modelo, Map<ParametroMedidor, Double> leidos) {
        Map<ParametroMedidor, Double> valores = new EnumMap<>(ParametroMedidor.class);
        for (Map.Entry<ParametroMedidor, Double> e : leidos.entrySet()) {
            ParametroMedidor p = e.getKey();
            if (p == ParametroMedidor.KWH || p == ParametroMedidor.KWH_RETORNO) {
                continue; // energías: ya están en los archivos normal y VIP
            }
            double v = e.getValue();
            DefinicionModelo.Registro r = modelo.registros().get(p);
            if (r != null && r.pf4Cuadrantes()) {
                v = LectorMedidorService.decodificarPf4Cuadrantes(v);
            }
            valores.put(p, v);
        }

        boolean iCalculado = false;
        if (!valores.containsKey(ParametroMedidor.DESBALANCE_I)) {
            Double d = desbalance(valores.get(ParametroMedidor.IA), valores.get(ParametroMedidor.IB),
                    valores.get(ParametroMedidor.IC), CORRIENTE_MINIMA_A);
            if (d != null) {
                valores.put(ParametroMedidor.DESBALANCE_I, d);
                iCalculado = true;
            }
        }
        boolean vCalculado = false;
        if (!valores.containsKey(ParametroMedidor.DESBALANCE_V)) {
            Double d = desbalance(valores.get(ParametroMedidor.VAB), valores.get(ParametroMedidor.VBC),
                    valores.get(ParametroMedidor.VCA), TENSION_MINIMA_V);
            if (d == null) {
                d = desbalance(valores.get(ParametroMedidor.VAN), valores.get(ParametroMedidor.VBN),
                        valores.get(ParametroMedidor.VCN), TENSION_MINIMA_V);
            }
            if (d != null) {
                valores.put(ParametroMedidor.DESBALANCE_V, d);
                vCalculado = true;
            }
        }
        return new LecturaCalidad(valores, iCalculado, vCalculado);
    }

    /**
     * Desbalance "peor fase" (misma definición que el PM5110): mayor diferencia respecto del
     * promedio de las tres fases, dividida por el promedio, en %. Null si falta una fase o el
     * promedio está por debajo del mínimo (equipo parado).
     */
    static Double desbalance(Double a, Double b, Double c, double minimo) {
        if (a == null || b == null || c == null) {
            return null;
        }
        double promedio = (a + b + c) / 3.0;
        if (promedio < minimo) {
            return null;
        }
        double peor = Math.max(Math.abs(a - promedio), Math.max(Math.abs(b - promedio), Math.abs(c - promedio)));
        return peor / promedio * 100.0;
    }
}
