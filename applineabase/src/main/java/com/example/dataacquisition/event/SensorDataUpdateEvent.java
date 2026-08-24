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
    private final Double derivadaPorHora;

    /**
     * derivadaPorHora viene ya calculada por PLCDataAcquisitionService (cuánto varió el valor
     * de este sensor por minuto respecto a la lectura anterior, con el tiempo real transcurrido
     * entre ciclos) para que el frontend no tenga que inferirlo a partir de cada cuánto llegan
     * los eventos SSE. Null en la primera lectura de cada sensor, cuando todavía no hay una
     * lectura anterior con la que compararlo.
     */
    public SensorDataUpdateEvent(Object source, String nombreSensor, double valor, String fecha, Double derivadaPorHora) {
        super(source);
        this.nombreSensor = nombreSensor;
        this.valor = valor;
        this.fecha = fecha;
        this.derivadaPorHora = derivadaPorHora;
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

    public Double getDerivadaPorHora() {
        return derivadaPorHora;
    }
}
