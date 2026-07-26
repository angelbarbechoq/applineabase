package com.example.horometro.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Horas trabajadas por máquina en un mes calendario (anioMes en formato "yyyy-MM").
 * Se reinicia solo (fila nueva) con el rollover de mes.
 */
@Entity
@Table(name = "horometro_mensual", uniqueConstraints = @UniqueConstraint(columnNames = {"lineaMaquina", "anioMes"}))
public class HorometroMensual extends HorometroBase {

    @Column(nullable = false)
    private String anioMes;

    public HorometroMensual() {
    }

    public HorometroMensual(String lineaMaquina, String anioMes, double horas) {
        super(lineaMaquina, horas);
        this.anioMes = anioMes;
    }

    public String getAnioMes() {
        return anioMes;
    }

    public void setAnioMes(String anioMes) {
        this.anioMes = anioMes;
    }
}
