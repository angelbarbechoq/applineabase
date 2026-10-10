package com.example.generador.model;

import java.time.LocalDateTime;
import java.util.Map;

import static com.example.generador.model.ParametroGenerador.*;

/**
 * Una lectura del controlador del generador, según el mapa de su modelo (generador-modelos.json).
 * null = el modelo no mapea ese valor, o el controlador no lo da en ese momento (0x8000).
 *
 * @param errores parámetros cuyo pedido falló (motivo), para mostrarlos al verificar un mapa.
 * @param alarmas texto de cada alarma de la lista de alarmas del controlador (vacía = sin alarmas;
 *                null = el modelo no la tiene o no se pudo leer).
 */
public record LecturaGenerador(LocalDateTime fecha, Map<ParametroGenerador, Double> valores,
                               Map<ParametroGenerador, String> errores, java.util.List<String> alarmas) {

    public Double valor(ParametroGenerador p) {
        return valores.get(p);
    }

    /** En marcha si el motor gira. */
    public boolean enMarcha() {
        Double rpm = rpm();
        return rpm != null && rpm > 0;
    }

    public Double rpm() { return valor(RPM); }
    public Double frecuencia() { return valor(FRECUENCIA); }
    public Double vL1N() { return valor(V_L1N); }
    public Double vL2N() { return valor(V_L2N); }
    public Double vL3N() { return valor(V_L3N); }
    public Double vL1L2() { return valor(V_L1L2); }
    public Double vL2L3() { return valor(V_L2L3); }
    public Double vL3L1() { return valor(V_L3L1); }
    public Double iL1() { return valor(I_L1); }
    public Double iL2() { return valor(I_L2); }
    public Double iL3() { return valor(I_L3); }
    public Double kw() { return valor(KW); }
    public Double kvar() { return valor(KVAR); }
    public Double kva() { return valor(KVA); }
    public Double pf() { return valor(PF); }
    public Double redFrecuencia() { return valor(RED_FRECUENCIA); }
    public Double redVL1L2() { return valor(RED_V_L1L2); }
    public Double redVL2L3() { return valor(RED_V_L2L3); }
    public Double redVL3L1() { return valor(RED_V_L3L1); }
    public Double redKw() { return valor(RED_KW); }
    public Double cargaKw() { return valor(CARGA_KW); }
    public Double bateria() { return valor(BATERIA); }
    public Double presionAceite() { return valor(PRESION_ACEITE); }
    public Double tempRefrigerante() { return valor(TEMP_REFRIGERANTE); }
    public Double tempAceite() { return valor(TEMP_ACEITE); }
    public Double nivelCombustible() { return valor(NIVEL_COMBUSTIBLE); }
    public Double consumoCombustible() { return valor(CONSUMO_COMBUSTIBLE); }
    public Double horasMarcha() { return valor(HORAS_MARCHA); }
    public Long kwh() { return largo(KWH); }
    public Long kvarh() { return largo(KVARH); }
    public Long redKwh() { return largo(RED_KWH); }

    public Integer arranques() {
        Double v = valor(ARRANQUES);
        return v == null ? null : (int) Math.round(v);
    }

    private Long largo(ParametroGenerador p) {
        Double v = valor(p);
        return v == null ? null : Math.round(v);
    }
}
