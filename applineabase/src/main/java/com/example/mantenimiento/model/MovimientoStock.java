package com.example.mantenimiento.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Historial de movimientos de stock de Barril y Tornillo (entradas y salidas), separado del
 * numero corriente en StockBarrilTornillo.cantidad -- mismo criterio que HorometroTotal (total
 * corriente) + HorometroDiario (detalle) en el modulo de horometro.
 *
 * No referencia a MantenimientoRealizado por FK a proposito: si esa tarea se borra despues, este
 * movimiento (el hecho historico de que se consumio o devolvio una pieza) tiene que seguir
 * existiendo igual -- por eso tag/fechaTarea se copian como datos sueltos, no una relacion.
 */
@Entity
@Table(name = "movimiento_stock")
public class MovimientoStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private StockBarrilTornillo stock;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoMovimientoStock tipo;

    @Column(nullable = false)
    private int cantidad;

    @Column(nullable = false)
    private LocalDateTime fecha;

    /** TAG del equipo y fecha de la tarea que origino el movimiento -- solo para EGRESO/DEVOLUCION. */
    @Column
    private String tagEquipo;

    @Column
    private LocalDateTime fechaTarea;

    /** Notas libres -- para INGRESO, ej. proveedor/factura. */
    @Column(length = 1000)
    private String observacion;

    /** Obligatorio solo para DEVOLUCION: por que se revierte el consumo. */
    @Column(length = 500)
    private String motivo;

    /** Obligatorio solo para DEVOLUCION: quien autoriza que la pieza vuelva al stock. */
    @Column
    private String autorizadoPor;

    public MovimientoStock() {
    }

    public Long getId() {
        return id;
    }

    public StockBarrilTornillo getStock() {
        return stock;
    }

    public void setStock(StockBarrilTornillo stock) {
        this.stock = stock;
    }

    public TipoMovimientoStock getTipo() {
        return tipo;
    }

    public void setTipo(TipoMovimientoStock tipo) {
        this.tipo = tipo;
    }

    public int getCantidad() {
        return cantidad;
    }

    public void setCantidad(int cantidad) {
        this.cantidad = cantidad;
    }

    public LocalDateTime getFecha() {
        return fecha;
    }

    public void setFecha(LocalDateTime fecha) {
        this.fecha = fecha;
    }

    public String getTagEquipo() {
        return tagEquipo;
    }

    public void setTagEquipo(String tagEquipo) {
        this.tagEquipo = tagEquipo;
    }

    public LocalDateTime getFechaTarea() {
        return fechaTarea;
    }

    public void setFechaTarea(LocalDateTime fechaTarea) {
        this.fechaTarea = fechaTarea;
    }

    public String getObservacion() {
        return observacion;
    }

    public void setObservacion(String observacion) {
        this.observacion = observacion;
    }

    public String getMotivo() {
        return motivo;
    }

    public void setMotivo(String motivo) {
        this.motivo = motivo;
    }

    public String getAutorizadoPor() {
        return autorizadoPor;
    }

    public void setAutorizadoPor(String autorizadoPor) {
        this.autorizadoPor = autorizadoPor;
    }
}
