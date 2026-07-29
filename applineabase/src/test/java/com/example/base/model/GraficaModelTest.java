package com.example.base.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Con 2+ series (p.ej. VAB/VAC/VBC), cada una arma su propio tooltip flotante e
 * independiente; si sus valores quedan cerca en pantalla, los tooltips se dibujan
 * superpuestos y solo se alcanza a leer el de la última serie dibujada — el resto
 * queda tapado. getInitScript2 corrige esto con un dy escalonado por índice de
 * serie, para que se apilen en vertical en vez de superponerse.
 */
class GraficaModelTest {

    @Test
    void tooltip_de_cada_serie_lleva_un_corrimiento_vertical_distinto_para_no_superponerse() {
        String script = new GraficaModel(3).getInitScript2("test");

        assertThat(script).contains("dy: -(i * 26)");
    }

    @Test
    void con_una_sola_serie_el_tooltip_no_muestra_el_nombre_de_la_serie() {
        // Con una sola serie el tooltip sigue mostrándose (solo el valor), pero sin el prefijo
        // "{name}: " — no hay nada que desambiguar entre series, y el nombre suele ser genérico
        // (p.ej. "Datos" en KWh), así que mostrarlo no aporta nada y solo ensucia el tooltip.
        String script = new GraficaModel(1).getInitScript2("test");

        assertThat(script)
                .contains("series.set('tooltip'")
                .doesNotContain("{name}: {valueY");
    }

    @Test
    void con_dos_o_mas_series_el_tooltip_si_muestra_el_nombre_de_cada_una() {
        // Con 2+ series sí hace falta saber a cuál corresponde cada valor.
        String script = new GraficaModel(3).getInitScript2("test");

        assertThat(script).contains("{name}: {valueY");
    }

    @Test
    void limpiarCeroAislado_interpola_un_cero_rodeado_de_lecturas_reales() {
        // El PLC respondió pero con una lectura inválida (0), no un apagado real: la máquina
        // estaba claramente encendida (220V) antes y después de ese único cero.
        List<Float> valores = Arrays.asList(220f, 218f, 0f, 219f, 221f);

        List<Float> limpio = GraficaModel.limpiarCeroAislado(valores);

        assertThat(limpio.get(2)).isCloseTo(218.5f, within(0.01f)); // promedio de 218 y 219
        assertThat(limpio.get(0)).isEqualTo(220f);
        assertThat(limpio.get(1)).isEqualTo(218f);
        assertThat(limpio.get(3)).isEqualTo(219f);
        assertThat(limpio.get(4)).isEqualTo(221f);
    }

    @Test
    void limpiarCeroAislado_no_toca_varios_ceros_seguidos_por_ser_un_apagado_real() {
        List<Float> valores = Arrays.asList(15f, 0f, 0f, 0f, 16f);

        List<Float> limpio = GraficaModel.limpiarCeroAislado(valores);

        assertThat(limpio).containsExactlyElementsOf(valores);
    }
}
