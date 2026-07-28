package com.example.base.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
