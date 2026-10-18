package com.example.franjahoraria.model;

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
 * Precio del kWh de cada franja horaria ($/kWh) en un mes. Cada mes guarda la suya: cambiar un mes no
 * toca a los demás. Un mes sin fila usa la del último mes cargado antes que él (ver TarifaFranjaService).
 */
@Entity
@Table(name = "tarifa_franja", uniqueConstraints = @UniqueConstraint(columnNames = "mes"))
public class TarifaFranja {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Primer día del mes. */
    @Column(name = "mes", nullable = false)
    private LocalDate mes;

    @Column(name = "precio_a", nullable = false)
    private double precioA;

    @Column(name = "precio_b", nullable = false)
    private double precioB;

    @Column(name = "precio_c", nullable = false)
    private double precioC;

    @Column(name = "precio_d", nullable = false)
    private double precioD;

    @Column(length = 255)
    private String observacion;

    @Column(length = 60)
    private String usuario;

    @Column(name = "fecha_registro", nullable = false)
    private LocalDateTime fechaRegistro;

    public TarifaFranja() {
    }

    public TarifaFranja(LocalDate mes) {
        this.mes = mes;
    }

    public double precio(Franja f) {
        return switch (f) {
            case A -> precioA;
            case B -> precioB;
            case C -> precioC;
            case D -> precioD;
        };
    }

    public void setPrecio(Franja f, double precio) {
        switch (f) {
            case A -> precioA = precio;
            case B -> precioB = precio;
            case C -> precioC = precio;
            case D -> precioD = precio;
        }
    }

    public Long getId() { return id; }
    public LocalDate getMes() { return mes; }
    public String getObservacion() { return observacion; }
    public void setObservacion(String observacion) { this.observacion = observacion; }
    public String getUsuario() { return usuario; }
    public void setUsuario(String usuario) { this.usuario = usuario; }
    public LocalDateTime getFechaRegistro() { return fechaRegistro; }
    public void setFechaRegistro(LocalDateTime fechaRegistro) { this.fechaRegistro = fechaRegistro; }
}
