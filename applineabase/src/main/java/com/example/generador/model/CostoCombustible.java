package com.example.generador.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * Combustible usado en un período en marcha (un arranque) de un generador, cargado a mano: galones
 * y precio del galón. El costo, el costo por kWh y los kWh por galón se calculan.
 * El arranque vive en el SQLite de arranques; se lo identifica por generador + id del arranque, y se
 * copia el inicio para poder reconocerlo aunque ese archivo cambie.
 */
@Entity
@Table(name = "costo_combustible_generador", uniqueConstraints = @UniqueConstraint(columnNames = {"generador", "arranque_id"}))
public class CostoCombustible {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String generador;

    @Column(name = "arranque_id", nullable = false)
    private Long arranqueId;

    @Column(name = "inicio_arranque", nullable = false)
    private LocalDateTime inicioArranque;

    @Column(nullable = false)
    private double galones;

    @Column(name = "precio_galon", nullable = false)
    private double precioGalon;

    @Column(length = 255)
    private String observacion;

    @Column(length = 60)
    private String usuario;

    @Column(name = "fecha_registro", nullable = false)
    private LocalDateTime fechaRegistro;

    public CostoCombustible() {
    }

    public CostoCombustible(String generador, Long arranqueId, LocalDateTime inicioArranque) {
        this.generador = generador;
        this.arranqueId = arranqueId;
        this.inicioArranque = inicioArranque;
    }

    public double costo() {
        return galones * precioGalon;
    }

    public Long getId() { return id; }
    public String getGenerador() { return generador; }
    public Long getArranqueId() { return arranqueId; }
    public LocalDateTime getInicioArranque() { return inicioArranque; }
    public double getGalones() { return galones; }
    public void setGalones(double galones) { this.galones = galones; }
    public double getPrecioGalon() { return precioGalon; }
    public void setPrecioGalon(double precioGalon) { this.precioGalon = precioGalon; }
    public String getObservacion() { return observacion; }
    public void setObservacion(String observacion) { this.observacion = observacion; }
    public String getUsuario() { return usuario; }
    public void setUsuario(String usuario) { this.usuario = usuario; }
    public LocalDateTime getFechaRegistro() { return fechaRegistro; }
    public void setFechaRegistro(LocalDateTime fechaRegistro) { this.fechaRegistro = fechaRegistro; }
}
