package com.example.horometro.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Auditoría de reinicios manuales del horómetro total. Cada reinicio deja una fila
 * con las horas que tenía acumuladas justo antes de ponerse en cero, para no perder
 * esa evidencia (útil para trazabilidad de mantenimiento).
 */
@Entity
@Table(name = "horometro_reset_log")
public class HorometroResetLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String lineaMaquina;

    @Column(nullable = false)
    private LocalDateTime fechaReset;

    @Column(nullable = false)
    private double horasAlMomentoDelReset;

    @Column(nullable = false)
    private String usuario;

    public HorometroResetLog() {
    }

    public HorometroResetLog(String lineaMaquina, LocalDateTime fechaReset, double horasAlMomentoDelReset, String usuario) {
        this.lineaMaquina = lineaMaquina;
        this.fechaReset = fechaReset;
        this.horasAlMomentoDelReset = horasAlMomentoDelReset;
        this.usuario = usuario;
    }

    public Long getId() {
        return id;
    }

    public String getLineaMaquina() {
        return lineaMaquina;
    }

    public void setLineaMaquina(String lineaMaquina) {
        this.lineaMaquina = lineaMaquina;
    }

    public LocalDateTime getFechaReset() {
        return fechaReset;
    }

    public void setFechaReset(LocalDateTime fechaReset) {
        this.fechaReset = fechaReset;
    }

    public double getHorasAlMomentoDelReset() {
        return horasAlMomentoDelReset;
    }

    public void setHorasAlMomentoDelReset(double horasAlMomentoDelReset) {
        this.horasAlMomentoDelReset = horasAlMomentoDelReset;
    }

    public String getUsuario() {
        return usuario;
    }

    public void setUsuario(String usuario) {
        this.usuario = usuario;
    }
}
