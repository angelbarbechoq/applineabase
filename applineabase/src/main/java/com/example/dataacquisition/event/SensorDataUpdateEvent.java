package com.example.dataacquisition.event;

import org.springframework.context.ApplicationEvent;

/**
 * Publicado en cada ciclo de lectura para sensores auxiliares del PLC3
 * (TemperaturaAmbiente, PsiAireP1, TemperaturaAgua, PsiAgua, BarCompHP), que no
 * pasan por el flujo de KWh/VIP.
 */
public class SensorDataUpdateEvent extends ApplicationEvent {

    private final String nombreSensor;
    private final double valor;
    private final String fecha;

    public SensorDataUpdateEvent(Object source, String nombreSensor, double valor, String fecha) {
        super(source);
        this.nombreSensor = nombreSensor;
        this.valor = valor;
        this.fecha = fecha;
    }

    public String getNombreSensor() {
        return nombreSensor;
    }

    public double getValor() {
        return valor;
    }

    public String getFecha() {
        return fecha;
    }
}
