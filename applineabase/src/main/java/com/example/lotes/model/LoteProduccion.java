package com.example.lotes.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Lote de producción marcado en la gráfica (Tiempo Real o Histórico) y validado por el técnico. Inicio y fin con el kWh
 * del contador guardado en ese minuto en la base de energía. Sin fin = lote en curso.
 */
@Entity
@Table(name = "lote_produccion")
public class LoteProduccion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String linea;

    /** Nombre del producto tal como estaba en el catálogo, o "Sin ítem". */
    @Column(nullable = false, length = 250)
    private String producto;

    /** Producto del catálogo; null = "Sin ítem". */
    @Column(name = "producto_id")
    private Long productoId;

    @Column(nullable = false)
    private LocalDateTime inicio;

    @Column(name = "kwh_inicio", nullable = false)
    private double kwhInicio;

    private LocalDateTime fin;

    @Column(name = "kwh_fin")
    private Double kwhFin;

    @Column(name = "usuario_inicio", length = 60)
    private String usuarioInicio;

    @Column(name = "usuario_fin", length = 60)
    private String usuarioFin;

    @Column(name = "fecha_registro", nullable = false)
    private LocalDateTime fechaRegistro;

    public LoteProduccion() {
    }

    public LoteProduccion(String linea, String producto, Long productoId, LocalDateTime inicio, double kwhInicio, String usuario) {
        this.linea = linea;
        this.producto = producto;
        this.productoId = productoId;
        this.inicio = inicio;
        this.kwhInicio = kwhInicio;
        this.usuarioInicio = usuario;
        this.fechaRegistro = LocalDateTime.now();
    }

    public void cerrar(LocalDateTime fin, double kwhFin, String usuario) {
        this.fin = fin;
        this.kwhFin = kwhFin;
        this.usuarioFin = usuario;
    }

    public boolean enCurso() { return fin == null; }

    public Double kwhLote() { return kwhFin == null ? null : kwhFin - kwhInicio; }

    public Double horas() { return fin == null ? null : Duration.between(inicio, fin).toSeconds() / 3600.0; }

    public Long getId() { return id; }
    public String getLinea() { return linea; }
    public String getProducto() { return producto; }
    public Long getProductoId() { return productoId; }
    public LocalDateTime getInicio() { return inicio; }
    public double getKwhInicio() { return kwhInicio; }
    public LocalDateTime getFin() { return fin; }
    public Double getKwhFin() { return kwhFin; }
    public String getUsuarioInicio() { return usuarioInicio; }
    public String getUsuarioFin() { return usuarioFin; }
    public LocalDateTime getFechaRegistro() { return fechaRegistro; }
}
