package com.example.lotes.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * Producto del catálogo de una línea (lo que se elige al marcar un lote). Sale de la hoja PRODUCCION de los Excel
 * de medición (columna ITEM por LINEA); "clave" = nombre en mayúsculas y con un solo espacio, para no repetir el mismo
 * producto escrito distinto. Los lados A y B de L08, L09 y L32 comparten la lista de su línea.
 */
@Entity
@Table(name = "producto_linea", uniqueConstraints = @UniqueConstraint(columnNames = {"linea", "clave"}))
public class ProductoLinea {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nombre de la línea como en la app (lineaMaquina), ej. "Linea09". */
    @Column(nullable = false, length = 40)
    private String linea;

    @Column(nullable = false, length = 250)
    private String nombre;

    @Column(nullable = false, length = 250)
    private String clave;

    @Column(length = 40)
    private String codigo;

    /** Archivo del que se cargó. */
    @Column(length = 120)
    private String origen;

    @Column(name = "fecha_alta", nullable = false)
    private LocalDateTime fechaAlta;

    public ProductoLinea() {
    }

    public ProductoLinea(String linea, String nombre, String clave, String codigo, String origen) {
        this.linea = linea;
        this.nombre = nombre;
        this.clave = clave;
        this.codigo = codigo;
        this.origen = origen;
        this.fechaAlta = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getLinea() { return linea; }
    public String getNombre() { return nombre; }
    public String getClave() { return clave; }
    public String getCodigo() { return codigo; }
    public String getOrigen() { return origen; }
    public LocalDateTime getFechaAlta() { return fechaAlta; }
}
