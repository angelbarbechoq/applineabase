package com.example.lotes.service;

import java.util.List;

/**
 * Regresión lineal por mínimos cuadrados con R², error estándar (sigma), CV y prueba F de la regresión (p = probabilidad
 * de un ajuste así de bueno si kWh no dependiera de kg).
 * <ul>
 *   <li>{@link #de}: y = m x + b. F con 1 y n-2 grados de libertad; pB = p de la prueba t del intercepto (b distinto de 0).</li>
 *   <li>{@link #porOrigen}: y = m x (b = 0), para cuando el intercepto no es significativo. R² centrado (contra la media,
 *   comparable con el del modelo con intercepto); F = t² de la pendiente con 1 y n-1 grados de libertad.</li>
 * </ul>
 * Con menos de 3 puntos no hay sigma, F ni p (null).
 */
public record Regresion(int n, double m, double b, double r2, Double se, Double f, Double p, Double cv, Double pB,
                        boolean origen) {

    /** Puntos {x, y}. Null con menos de 2. */
    public static Regresion de(List<double[]> puntos) {
        int n = puntos.size();
        if (n < 2) return null;
        double sx = 0, sy = 0;
        for (double[] p : puntos) { sx += p[0]; sy += p[1]; }
        double mx = sx / n, my = sy / n, sxx = 0, sxy = 0, syy = 0;
        for (double[] p : puntos) {
            sxx += (p[0] - mx) * (p[0] - mx);
            sxy += (p[0] - mx) * (p[1] - my);
            syy += (p[1] - my) * (p[1] - my);
        }
        double m = sxx == 0 ? 0 : sxy / sxx, b = my - m * mx, sse = 0;
        for (double[] p : puntos) sse += Math.pow(p[1] - (m * p[0] + b), 2);
        double r2 = syy == 0 ? 0 : 1 - sse / syy;
        Double se = null, f = null, pv = null, cv = null, pb = null;
        if (n > 2) {
            se = Math.sqrt(sse / (n - 2));
            f = sse > 0 ? (syy - sse) / (sse / (n - 2)) : Double.POSITIVE_INFINITY;
            pv = pDeF(f, n - 2);
            cv = my == 0 ? null : se / my;
            if (sxx > 0) {
                double seB = se * Math.sqrt(1.0 / n + mx * mx / sxx);
                pb = seB == 0 ? 0.0 : pDeF((b / seB) * (b / seB), n - 2);
            }
        }
        return new Regresion(n, m, b, r2, se, f, pv, cv, pb, false);
    }

    /** y = m x sin intercepto. Null con menos de 2 puntos o x todos en 0. */
    public static Regresion porOrigen(List<double[]> puntos) {
        int n = puntos.size();
        if (n < 2) return null;
        double sxx = 0, sxy = 0, sy = 0;
        for (double[] p : puntos) { sxx += p[0] * p[0]; sxy += p[0] * p[1]; sy += p[1]; }
        if (sxx == 0) return null;
        double m = sxy / sxx, my = sy / n, sse = 0, syy = 0;
        for (double[] p : puntos) {
            sse += Math.pow(p[1] - m * p[0], 2);
            syy += (p[1] - my) * (p[1] - my);
        }
        double r2 = syy == 0 ? 0 : 1 - sse / syy;
        Double se = null, f = null, pv = null, cv = null;
        if (n > 2) {
            se = Math.sqrt(sse / (n - 1));
            double seM = se / Math.sqrt(sxx);
            f = seM == 0 ? Double.POSITIVE_INFINITY : (m / seM) * (m / seM);
            pv = pDeF(f, n - 1);
            cv = my == 0 ? null : se / my;
        }
        return new Regresion(n, m, 0, r2, se, f, pv, cv, null, true);
    }

    /** p de un F con 1 y d grados de libertad (= p de dos colas de una t con d grados de libertad, t² = F). */
    static double pDeF(double f, int d) {
        if (Double.isInfinite(f)) return 0;
        return betaIncompleta(d / 2.0, 0.5, d / (d + f));
    }

    // Función beta incompleta regularizada (Numerical Recipes, fracción continua).
    static double betaIncompleta(double a, double b, double x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        double bt = Math.exp(lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * Math.log(x) + b * Math.log(1 - x));
        return x < (a + 1) / (a + b + 2) ? bt * fraccion(a, b, x) / a : 1 - bt * fraccion(b, a, 1 - x) / b;
    }

    private static double fraccion(double a, double b, double x) {
        double qab = a + b, qap = a + 1, qam = a - 1, c = 1, d = 1 - qab * x / qap;
        if (Math.abs(d) < 1e-30) d = 1e-30;
        d = 1 / d;
        double h = d;
        for (int m = 1; m <= 300; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1 + aa * d; if (Math.abs(d) < 1e-30) d = 1e-30;
            c = 1 + aa / c; if (Math.abs(c) < 1e-30) c = 1e-30;
            d = 1 / d; h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1 + aa * d; if (Math.abs(d) < 1e-30) d = 1e-30;
            c = 1 + aa / c; if (Math.abs(c) < 1e-30) c = 1e-30;
            d = 1 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < 3e-12) break;
        }
        return h;
    }

    private static double lnGamma(double x) {
        double[] g = {76.18009172947146, -86.50532032941677, 24.01409824083091, -1.231739572450155,
                0.1208650973866179e-2, -0.5395239384953e-5};
        double y = x, t = x + 5.5;
        t -= (x + 0.5) * Math.log(t);
        double s = 1.000000000190015;
        for (double c : g) s += c / ++y;
        return -t + Math.log(2.5066282746310005 * s / x);
    }
}
