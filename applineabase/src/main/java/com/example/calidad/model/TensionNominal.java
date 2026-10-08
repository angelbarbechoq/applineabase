package com.example.calidad.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Tensión nominal fase-fase del tablero de cada máquina (para evaluar la tensión medida). */
@Entity
@Table(name = "tension_nominal", uniqueConstraints = @UniqueConstraint(columnNames = "linea_maquina"))
public class TensionNominal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "linea_maquina", nullable = false)
    private String lineaMaquina;

    @Column(nullable = false)
    private double voltiosFaseFase;

    public TensionNominal() {
    }

    public TensionNominal(String lineaMaquina, double voltiosFaseFase) {
        this.lineaMaquina = lineaMaquina;
        this.voltiosFaseFase = voltiosFaseFase;
    }

    public Long getId() { return id; }
    public String getLineaMaquina() { return lineaMaquina; }
    public double getVoltiosFaseFase() { return voltiosFaseFase; }
    public void setVoltiosFaseFase(double voltiosFaseFase) { this.voltiosFaseFase = voltiosFaseFase; }
}
