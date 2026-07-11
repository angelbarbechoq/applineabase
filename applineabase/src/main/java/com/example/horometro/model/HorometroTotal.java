package com.example.horometro.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Horas acumuladas sin límite de tiempo por máquina. Una fila por línea/máquina.
 * Solo se pone en cero mediante un reinicio manual desde la UI (ver HorometroResetLog).
 */
@Entity
@Table(name = "horometro_total")
public class HorometroTotal {

    @Id
    @Column(name = "linea_maquina")
    private String lineaMaquina;

    @Column(nullable = false)
    private double horasAcumuladas;

    /** Fecha del dato más antiguo incorporado al acumulado (desde cuándo cuenta este total). */
    private LocalDateTime fechaInicio;

    /** Último instante de datos ya incorporado al acumulado (checkpoint del backfill incremental). */
    private LocalDateTime fechaUltimoProcesado;

    private LocalDateTime fechaUltimoReset;

    public HorometroTotal() {
    }

    public HorometroTotal(String lineaMaquina) {
        this.lineaMaquina = lineaMaquina;
        this.horasAcumuladas = 0.0;
    }

    public String getLineaMaquina() {
        return lineaMaquina;
    }

    public void setLineaMaquina(String lineaMaquina) {
        this.lineaMaquina = lineaMaquina;
    }

    public double getHorasAcumuladas() {
        return horasAcumuladas;
    }

    public void setHorasAcumuladas(double horasAcumuladas) {
        this.horasAcumuladas = horasAcumuladas;
    }

    public LocalDateTime getFechaInicio() {
        return fechaInicio;
    }

    public void setFechaInicio(LocalDateTime fechaInicio) {
        this.fechaInicio = fechaInicio;
    }

    public LocalDateTime getFechaUltimoProcesado() {
        return fechaUltimoProcesado;
    }

    public void setFechaUltimoProcesado(LocalDateTime fechaUltimoProcesado) {
        this.fechaUltimoProcesado = fechaUltimoProcesado;
    }

    public LocalDateTime getFechaUltimoReset() {
        return fechaUltimoReset;
    }

    public void setFechaUltimoReset(LocalDateTime fechaUltimoReset) {
        this.fechaUltimoReset = fechaUltimoReset;
    }
}
