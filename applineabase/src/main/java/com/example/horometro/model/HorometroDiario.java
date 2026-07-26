package com.example.horometro.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;

/**
 * Horas trabajadas por máquina en un día calendario. Se reinicia solo (fila nueva)
 * con el rollover de día; una vez cerrado el día, la fila queda fija y el backfill
 * incremental la salta.
 */
@Entity
@Table(name = "horometro_diario", uniqueConstraints = @UniqueConstraint(columnNames = {"lineaMaquina", "fecha"}))
public class HorometroDiario extends HorometroBase {

    @Column(nullable = false)
    private LocalDate fecha;

    public HorometroDiario() {
    }

    public HorometroDiario(String lineaMaquina, LocalDate fecha, double horas) {
        super(lineaMaquina, horas);
        this.fecha = fecha;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public void setFecha(LocalDate fecha) {
        this.fecha = fecha;
    }
}
