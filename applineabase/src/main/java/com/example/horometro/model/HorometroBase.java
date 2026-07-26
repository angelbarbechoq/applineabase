package com.example.horometro.model;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * id/lineaMaquina/horas: campos idénticos en HorometroDiario/Semanal/Mensual, que solo
 * difieren en el nombre/tipo de su campo de período (fecha/semanaId/anioMes). MappedSuperclass
 * no crea tabla propia ni cambia el esquema de las tres tablas existentes — cada subclase
 * sigue mapeando sus columnas heredadas igual que antes.
 */
@MappedSuperclass
public abstract class HorometroBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String lineaMaquina;

    @Column(nullable = false)
    private double horas;

    protected HorometroBase() {
    }

    protected HorometroBase(String lineaMaquina, double horas) {
        this.lineaMaquina = lineaMaquina;
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

    public double getHoras() {
        return horas;
    }

    public void setHoras(double horas) {
        this.horas = horas;
    }
}
