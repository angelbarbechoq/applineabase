package com.example.base.ui;

import com.example.base.model.GraficaModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * HistoricoView necesita ConfigLoaderService/LineaAccessService/PLCDataQueryService reales para
 * instanciarse como vista Vaadin; para verificar solo los pisos del eje Y no hace falta levantar
 * ese contexto, así que se leen por reflexión (son private static final).
 *
 * Bug que cubre: Voltajes/Corrientes/PW usaban un piso fijo (500/300/200) igual para TODAS las
 * máquinas. A diferencia de KWh (que sí tiene un preset por máquina en aplicarRangosPredefinidos),
 * estas tres variables varían muchísimo de una máquina a otra, así que un piso global grande
 * dejaba el eje pegado ahí para cualquier máquina cuya escala real fuera bastante menor.
 */
class HistoricoViewEscalaEjeYTest {

    private static double leerConstante(String nombreCampo) throws Exception {
        Field field = HistoricoView.class.getDeclaredField(nombreCampo);
        field.setAccessible(true);
        return field.getDouble(null);
    }

    @Test
    void pisos_de_voltaje_corriente_y_potencia_son_una_red_de_seguridad_no_un_techo_asumido() throws Exception {
        assertThat(leerConstante("VOLTAJES_MAX_Y_DEFAULT")).isGreaterThan(0.0).isLessThan(10.0);
        assertThat(leerConstante("CORRIENTES_MAX_Y_DEFAULT")).isGreaterThan(0.0).isLessThan(10.0);
        assertThat(leerConstante("PW_MAX_Y_DEFAULT")).isGreaterThan(0.0).isLessThan(10.0);
    }

    @Test
    void con_el_piso_minimo_el_eje_se_ajusta_a_corrientes_reales_de_una_maquina_chica() throws Exception {
        double piso = leerConstante("CORRIENTES_MAX_Y_DEFAULT");
        List<Float> corrientesRealesMaquinaChica = List.of(18f, 20f, 22f, 19f, 25f, 21f, 23f);

        double maxY = GraficaModel.calcularMaxYConMargen(corrientesRealesMaquinaChica, piso);

        // percentil 95 (25) * 1.1 = 27.5: el eje debe ajustarse a esto. Con el viejo piso fijo
        // (300) el resultado hubiera quedado pegado en 300, aplastando la escala real.
        assertThat(maxY).isCloseTo(27.5, within(0.5));
    }

    @Test
    void con_el_piso_viejo_el_eje_se_hubiera_quedado_pegado_ahi_documentando_el_bug_ya_corregido() {
        List<Float> corrientesRealesMaquinaChica = List.of(18f, 20f, 22f, 19f, 25f, 21f, 23f);

        double maxYConPisoViejo = GraficaModel.calcularMaxYConMargen(corrientesRealesMaquinaChica, 300.0);

        assertThat(maxYConPisoViejo).isEqualTo(300.0);
    }
}
