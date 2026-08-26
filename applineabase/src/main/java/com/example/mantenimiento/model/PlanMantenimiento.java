package com.example.mantenimiento.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Tarea de mantenimiento preventivo por horas de funcionamiento, configurable sobre
 * cualquier nivel del catálogo de TAGs (equipo, ej. EXT-L01-XTR, o ítem mantenible, ej.
 * EXT-L01-XTR-BYT) o sobre un lineaMaquina plano para máquinas sin taxonomía todavía
 * (Mezcla, Casa Fuerza). El campo "tag" no distingue el nivel: es el mismo identificador
 * de texto libre que ya usan AlarmaConfig/HorometroTotal.
 */
@Entity
@Table(name = "plan_mantenimiento", uniqueConstraints = @UniqueConstraint(columnNames = {"tag", "tarea"}))
public class PlanMantenimiento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String tag;

    @Column(nullable = false)
    private String tarea;

    @Column(nullable = false)
    private double intervaloHoras;

    /** Si se configura, el estado pasa a "próximo a vencer" cuando falten menos de estas
     * horas para llegar al intervalo, además del aviso de "vencido". */
    private Double horasAvisoAnticipado;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean habilitado = true;

    /** Evita reavisar en cada poll una vez que ya se notificó el vencimiento; se resetea a
     * false al registrar el mantenimiento como realizado. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean notificado = false;

    public PlanMantenimiento() {
    }

    public Long getId() {
        return id;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getTarea() {
        return tarea;
    }

    public void setTarea(String tarea) {
        this.tarea = tarea;
    }

    public double getIntervaloHoras() {
        return intervaloHoras;
    }

    public void setIntervaloHoras(double intervaloHoras) {
        this.intervaloHoras = intervaloHoras;
    }

    public Double getHorasAvisoAnticipado() {
        return horasAvisoAnticipado;
    }

    public void setHorasAvisoAnticipado(Double horasAvisoAnticipado) {
        this.horasAvisoAnticipado = horasAvisoAnticipado;
    }

    public boolean isHabilitado() {
        return habilitado;
    }

    public void setHabilitado(boolean habilitado) {
        this.habilitado = habilitado;
    }

    public boolean isNotificado() {
        return notificado;
    }

    public void setNotificado(boolean notificado) {
        this.notificado = notificado;
    }
}
