package com.example.mantenimiento.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Historial de mantenimientos registrados sobre un PlanMantenimiento. horasAcumuladasEnMomento
 * es el snapshot de HorometroTotal.horasAcumuladas de la línea al momento de registrar — es la
 * base sobre la que se calcula "horas desde el último" de ahí en adelante, sin necesitar un
 * horómetro propio por ítem.
 */
@Entity
@Table(name = "mantenimiento_realizado")
public class MantenimientoRealizado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "plan_mantenimiento_id", nullable = false)
    private PlanMantenimiento planMantenimiento;

    @Column(nullable = false)
    private LocalDateTime fechaRealizado;

    @Column(nullable = false)
    private double horasAcumuladasEnMomento;

    @Column(nullable = false)
    private String usuario;

    private String notas;

    public MantenimientoRealizado() {
    }

    public Long getId() {
        return id;
    }

    public PlanMantenimiento getPlanMantenimiento() {
        return planMantenimiento;
    }

    public void setPlanMantenimiento(PlanMantenimiento planMantenimiento) {
        this.planMantenimiento = planMantenimiento;
    }

    public LocalDateTime getFechaRealizado() {
        return fechaRealizado;
    }

    public void setFechaRealizado(LocalDateTime fechaRealizado) {
        this.fechaRealizado = fechaRealizado;
    }

    public double getHorasAcumuladasEnMomento() {
        return horasAcumuladasEnMomento;
    }

    public void setHorasAcumuladasEnMomento(double horasAcumuladasEnMomento) {
        this.horasAcumuladasEnMomento = horasAcumuladasEnMomento;
    }

    public String getUsuario() {
        return usuario;
    }

    public void setUsuario(String usuario) {
        this.usuario = usuario;
    }

    public String getNotas() {
        return notas;
    }

    public void setNotas(String notas) {
        this.notas = notas;
    }
}
