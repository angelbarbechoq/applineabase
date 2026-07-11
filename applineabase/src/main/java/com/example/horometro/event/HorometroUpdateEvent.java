package com.example.horometro.event;

import org.springframework.context.ApplicationEvent;

/**
 * Snapshot en vivo del horómetro de una máquina, publicado por HorometroService para
 * que el controller SSE lo reenvíe al frontend (mismo mecanismo que KWhDifferenceEvent).
 */
public class HorometroUpdateEvent extends ApplicationEvent {

    private final String nombreMaquina;
    private final boolean encendida;
    private final double horasHoy;
    private final double horasMes;
    private final double horasTotal;

    public HorometroUpdateEvent(Object source, String nombreMaquina, boolean encendida,
                                 double horasHoy, double horasMes, double horasTotal) {
        super(source);
        this.nombreMaquina = nombreMaquina;
        this.encendida = encendida;
        this.horasHoy = horasHoy;
        this.horasMes = horasMes;
        this.horasTotal = horasTotal;
    }

    public String getNombreMaquina() {
        return nombreMaquina;
    }

    public boolean isEncendida() {
        return encendida;
    }

    public double getHorasHoy() {
        return horasHoy;
    }

    public double getHorasMes() {
        return horasMes;
    }

    public double getHorasTotal() {
        return horasTotal;
    }
}
