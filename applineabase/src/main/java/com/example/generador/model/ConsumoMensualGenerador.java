package com.example.generador.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;

/**
 * Consumo de diésel y energía de un generador en un mes anterior al Registro MF-13 de la app (histórico calculado
 * de la planilla MF-13 de los operadores). Desde el primer mes con registros en la app, el consumo se calcula del
 * registro y esta tabla ya no se usa para ese mes.
 */
@Entity
@Table(name = "consumo_mensual_generador", uniqueConstraints = @UniqueConstraint(columnNames = {"generador", "mes"}))
public class ConsumoMensualGenerador {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String generador;

    /** Primer día del mes. */
    @Column(nullable = false)
    private LocalDate mes;

    @Column(nullable = false)
    private double galones;

    /** null = el generador no tiene medidor de energía. */
    private Double kwh;

    private Double horas;

    /** Cómo se calculó (va a la nota del mes). */
    @Column(length = 300)
    private String nota;

    public ConsumoMensualGenerador() {
    }

    public ConsumoMensualGenerador(String generador, LocalDate mes, double galones, Double kwh, Double horas, String nota) {
        this.generador = generador;
        this.mes = mes;
        this.galones = galones;
        this.kwh = kwh;
        this.horas = horas;
        this.nota = nota;
    }

    public Long getId() { return id; }
    public String getGenerador() { return generador; }
    public LocalDate getMes() { return mes; }
    public double getGalones() { return galones; }
    public Double getKwh() { return kwh; }
    public Double getHoras() { return horas; }
    public String getNota() { return nota; }
}
