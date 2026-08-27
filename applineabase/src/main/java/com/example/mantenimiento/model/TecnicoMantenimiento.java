package com.example.mantenimiento.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Catalogo de personal de mantenimiento (CI, nombre, especialidad), para poblar el combo
 * "Tecnico" del formulario de tareas ya ejecutadas con los nombres registrados. Ampliable
 * desde la pantalla "Personal de Mantenimiento".
 */
@Entity
@Table(name = "tecnico_mantenimiento", uniqueConstraints = @UniqueConstraint(columnNames = "ci"))
public class TecnicoMantenimiento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String ci;

    @Column(nullable = false)
    private String nombre;

    private String especialidad;

    public TecnicoMantenimiento() {
    }

    public TecnicoMantenimiento(String ci, String nombre, String especialidad) {
        this.ci = ci;
        this.nombre = nombre;
        this.especialidad = especialidad;
    }

    public Long getId() {
        return id;
    }

    public String getCi() {
        return ci;
    }

    public void setCi(String ci) {
        this.ci = ci;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getEspecialidad() {
        return especialidad;
    }

    public void setEspecialidad(String especialidad) {
        this.especialidad = especialidad;
    }
}
