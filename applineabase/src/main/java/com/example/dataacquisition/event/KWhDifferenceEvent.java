package com.example.dataacquisition.event;

import org.springframework.context.ApplicationEvent;

public class KWhDifferenceEvent extends ApplicationEvent {

    private final String nombreMaquina;
    private final double diferencia;
    private final String fecha;

    public KWhDifferenceEvent(Object source, String nombreMaquina, double diferencia, String fecha) {
        super(source);
        this.nombreMaquina = nombreMaquina;
        this.diferencia = diferencia;
        this.fecha = fecha;
    }

    public String getNombreMaquina() {
        return nombreMaquina;
    }

    public double getDiferencia() {
        return diferencia;
    }

    public String getFecha() {
        return fecha;
    }
}
