package com.example.horometro.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
public class HorometroDiario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String lineaMaquina;

    @Column(nullable = false)
    private LocalDate fecha;

    @Column(nullable = false)
    private double horas;

    public HorometroDiario() {
    }

    public HorometroDiario(String lineaMaquina, LocalDate fecha, double horas) {
        this.lineaMaquina = lineaMaquina;
        this.fecha = fecha;
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

    public LocalDate getFecha() {
        return fecha;
    }

    public void setFecha(LocalDate fecha) {
        this.fecha = fecha;
    }

    public double getHoras() {
        return horas;
    }

    public void setHoras(double horas) {
        this.horas = horas;
    }
}
