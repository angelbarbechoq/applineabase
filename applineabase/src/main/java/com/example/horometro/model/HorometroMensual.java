package com.example.horometro.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Horas trabajadas por máquina en un mes calendario (anioMes en formato "yyyy-MM").
 * Se reinicia solo (fila nueva) con el rollover de mes.
 */
@Entity
@Table(name = "horometro_mensual", uniqueConstraints = @UniqueConstraint(columnNames = {"lineaMaquina", "anioMes"}))
public class HorometroMensual {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String lineaMaquina;

    @Column(nullable = false)
    private String anioMes;

    @Column(nullable = false)
    private double horas;

    public HorometroMensual() {
    }

    public HorometroMensual(String lineaMaquina, String anioMes, double horas) {
        this.lineaMaquina = lineaMaquina;
        this.anioMes = anioMes;
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

    public String getAnioMes() {
        return anioMes;
    }

    public void setAnioMes(String anioMes) {
        this.anioMes = anioMes;
    }

    public double getHoras() {
        return horas;
    }

    public void setHoras(double horas) {
        this.horas = horas;
    }
}
