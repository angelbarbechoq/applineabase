package com.example.generador.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Precio de la energía de la red ($/kWh) vigente desde un mes, para comparar con el costo del
 * combustible del generador. Cada período en marcha usa la tarifa vigente en su inicio.
 */
@Entity
@Table(name = "tarifa_red", uniqueConstraints = @UniqueConstraint(columnNames = "vigente_desde"))
public class TarifaRed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Primer día del mes desde el que rige. */
    @Column(name = "vigente_desde", nullable = false)
    private LocalDate vigenteDesde;

    @Column(name = "precio_kwh", nullable = false)
    private double precioKwh;

    @Column(length = 255)
    private String observacion;

    @Column(length = 60)
    private String usuario;

    @Column(name = "fecha_registro", nullable = false)
    private LocalDateTime fechaRegistro;

    public TarifaRed() {
    }

    public TarifaRed(LocalDate vigenteDesde) {
        this.vigenteDesde = vigenteDesde;
    }

    public Long getId() { return id; }
    public LocalDate getVigenteDesde() { return vigenteDesde; }
    public double getPrecioKwh() { return precioKwh; }
    public void setPrecioKwh(double precioKwh) { this.precioKwh = precioKwh; }
    public String getObservacion() { return observacion; }
    public void setObservacion(String observacion) { this.observacion = observacion; }
    public String getUsuario() { return usuario; }
    public void setUsuario(String usuario) { this.usuario = usuario; }
    public LocalDateTime getFechaRegistro() { return fechaRegistro; }
    public void setFechaRegistro(LocalDateTime fechaRegistro) { this.fechaRegistro = fechaRegistro; }
}
