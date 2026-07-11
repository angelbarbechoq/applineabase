package com.example.horometro.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Horas trabajadas por máquina en una semana ISO (lunes 00:00:01 a domingo 23:59:59),
 * identificada como "yyyy-Www" (semanaId). Coincide con la rutina real de la planta:
 * cada lunes se toma el horómetro físico de la semana que acaba de terminar.
 */
@Entity
@Table(name = "horometro_semanal", uniqueConstraints = @UniqueConstraint(columnNames = {"lineaMaquina", "semanaId"}))
public class HorometroSemanal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String lineaMaquina;

    @Column(nullable = false)
    private String semanaId;

    @Column(nullable = false)
    private double horas;

    public HorometroSemanal() {
    }

    public HorometroSemanal(String lineaMaquina, String semanaId, double horas) {
        this.lineaMaquina = lineaMaquina;
        this.semanaId = semanaId;
        this.horas = horas;
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

    public String getSemanaId() {
        return semanaId;
    }

    public void setSemanaId(String semanaId) {
        this.semanaId = semanaId;
    }

    public double getHoras() {
        return horas;
    }

    public void setHoras(double horas) {
        this.horas = horas;
    }
}
