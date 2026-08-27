package com.example.mantenimiento.model;

import java.time.LocalDateTime;

/**
 * Estado calculado de un PlanMantenimiento para la grilla: cuándo se hizo la tarea por última
 * vez, cuántas horas pasaron desde entonces, y una fecha estimada de próximo aviso (proyectada
 * con el promedio de horas/día reciente de la línea — no es una fecha exacta, la máquina no
 * corre a ritmo constante, es una referencia aproximada).
 */
public record EstadoPlanDTO(
        PlanMantenimiento plan,
        LocalDateTime ultimaFechaRealizado,
        double horasTranscurridas,
        double horasRestantes,
        LocalDateTime proximoAvisoEstimado
) {
    public boolean vencido() {
        return horasRestantes <= 0;
    }

    /** Sin ningún MantenimientoRealizado todavía — el plan se creó pero nadie cargó desde
     * cuándo contar, así que horasTranscurridas/horasRestantes no son un dato confiable
     * para mostrar (asumirían 0 horas base). */
    public boolean sinRegistro() {
        return ultimaFechaRealizado == null;
    }
}
