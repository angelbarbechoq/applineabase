package com.example.horometro.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Horas trabajadas por máquina en una semana ISO (lunes 00:00:01 a domingo 23:59:59),
 * identificada como "yyyy-Www" (semanaId). Coincide con la rutina real de la planta:
 * cada lunes se toma el horómetro físico de la semana que acaba de terminar.
 */
@Entity
@Table(name = "horometro_semanal", uniqueConstraints = @UniqueConstraint(columnNames = {"lineaMaquina", "semanaId"}))
public class HorometroSemanal extends HorometroBase {

    @Column(nullable = false)
    private String semanaId;

    public HorometroSemanal() {
    }

    public HorometroSemanal(String lineaMaquina, String semanaId, double horas) {
        super(lineaMaquina, horas);
        this.semanaId = semanaId;
    }

    public String getSemanaId() {
        return semanaId;
    }

    public void setSemanaId(String semanaId) {
        this.semanaId = semanaId;
    }
}
