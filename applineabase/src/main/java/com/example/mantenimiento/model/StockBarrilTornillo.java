package com.example.mantenimiento.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Stock de repuestos de barril y tornillo, por modelo + sistema de refrigeracion. La geometria
 * del tornillo (doble conico / doble paralelo) no se guarda aca -- se deriva del modelo, ver
 * {@link #geometriaTornillo()} -- porque es un dato fijo del modelo, no algo que varie por
 * unidad ni que el usuario deba cargar a mano.
 */
@Entity
@Table(name = "stock_barril_tornillo")
public class StockBarrilTornillo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String modelo;

    @Column(nullable = false)
    private String sistemaRefrigeracion;

    @Column(nullable = false)
    private int cantidad;

    @Column(length = 1000)
    private String observacion;

    public StockBarrilTornillo() {
    }

    public Long getId() {
        return id;
    }

    public String getModelo() {
        return modelo;
    }

    public void setModelo(String modelo) {
        this.modelo = modelo;
    }

    public String getSistemaRefrigeracion() {
        return sistemaRefrigeracion;
    }

    public void setSistemaRefrigeracion(String sistemaRefrigeracion) {
        this.sistemaRefrigeracion = sistemaRefrigeracion;
    }

    public int getCantidad() {
        return cantidad;
    }

    public void setCantidad(int cantidad) {
        this.cantidad = cantidad;
    }

    public String getObservacion() {
        return observacion;
    }

    public void setObservacion(String observacion) {
        this.observacion = observacion;
    }

    /** LSDP es doble paralelo; el resto de los modelos (LSE, CM) son doble conico. */
    public String geometriaTornillo() {
        return modelo != null && modelo.toUpperCase().startsWith("LSDP") ? "Doble paralelo" : "Doble conico";
    }
}
