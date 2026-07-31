package com.example.dataacquisition.event;

import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * Publicado en cada ciclo de lectura de un PLC/gateway PAS600L, por línea, para reportar si
 * el dispositivo respondió o no (ping fallido o error de conexión Modbus). Lo consume
 * AlarmaEvaluatorService para la regla DISPOSITIVO_NO_DISPONIBLE.
 */
public class DispositivoConectividadEvent extends ApplicationEvent {

    private final String nombreMaquina;
    private final boolean conectado;
    private final String motivo;
    private final LocalDateTime fecha;

    public DispositivoConectividadEvent(Object source, String nombreMaquina, boolean conectado, String motivo, LocalDateTime fecha) {
        super(source);
        this.nombreMaquina = nombreMaquina;
        this.conectado = conectado;
        this.motivo = motivo;
        this.fecha = fecha;
    }

    public String getNombreMaquina() {
        return nombreMaquina;
    }

    public boolean isConectado() {
        return conectado;
    }

    /** Motivo de la falla ("sin respuesta a ping", "error de conexión Modbus"). Null si conectado=true. */
    public String getMotivo() {
        return motivo;
    }

    public LocalDateTime getFecha() {
        return fecha;
    }
}
