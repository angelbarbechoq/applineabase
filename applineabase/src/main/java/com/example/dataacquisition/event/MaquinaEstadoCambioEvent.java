package com.example.dataacquisition.event;

import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * Publicado por AlarmaEvaluatorService cuando la regla DETENCION de una línea
 * confirma un cambio real de estado (encendida/apagada). {@code desde} es el
 * instante en que el cambio realmente ocurrió (puede ser anterior a la
 * publicación del evento: la confirmación de "apagada" espera una ventana de
 * ciclos consecutivos por debajo del umbral, y se reporta retroactivamente
 * desde el primer ciclo de esa racha).
 */
public class MaquinaEstadoCambioEvent extends ApplicationEvent {

    private final String nombreMaquina;
    private final boolean encendida;
    private final LocalDateTime desde;

    public MaquinaEstadoCambioEvent(Object source, String nombreMaquina, boolean encendida, LocalDateTime desde) {
        super(source);
        this.nombreMaquina = nombreMaquina;
        this.encendida = encendida;
        this.desde = desde;
    }

    public String getNombreMaquina() {
        return nombreMaquina;
    }

    public boolean isEncendida() {
        return encendida;
    }

    public LocalDateTime getDesde() {
        return desde;
    }
}
