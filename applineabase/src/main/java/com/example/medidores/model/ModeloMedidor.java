package com.example.medidores.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.ArrayList;
import java.util.List;

/**
 * Modelo de medidor del catálogo (PM5110, PM710, ION8600...). Su nombre es el que se elige en
 * "modeloMedidor" de cada línea; sus registros dicen dónde leer cada {@link ParametroMedidor}.
 * Se edita desde Configuracion > Modelos de medidor y el lector lo toma en el ciclo siguiente,
 * sin recompilar ni reiniciar.
 */
@Entity
@Table(name = "modelo_medidor", uniqueConstraints = @UniqueConstraint(columnNames = "nombre"))
public class ModeloMedidor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nombre;

    private String descripcion;

    /** true: los registros se cargan como figuran en el manual (base 1) y se resta 1 al pedirlos. */
    @Column(columnDefinition = "boolean default true")
    private boolean numeracionManual = true;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(20) default 'HOLDING'")
    private FuncionLectura funcionLectura = FuncionLectura.HOLDING;

    @OneToMany(mappedBy = "modelo", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<RegistroModelo> registros = new ArrayList<>();

    public ModeloMedidor() {
    }

    public ModeloMedidor(String nombre, String descripcion) {
        this.nombre = nombre;
        this.descripcion = descripcion;
    }

    public Long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public boolean isNumeracionManual() {
        return numeracionManual;
    }

    public void setNumeracionManual(boolean numeracionManual) {
        this.numeracionManual = numeracionManual;
    }

    public FuncionLectura getFuncionLectura() {
        return funcionLectura == null ? FuncionLectura.HOLDING : funcionLectura;
    }

    public void setFuncionLectura(FuncionLectura funcionLectura) {
        this.funcionLectura = funcionLectura;
    }

    public List<RegistroModelo> getRegistros() {
        return registros;
    }

    public RegistroModelo registroDe(ParametroMedidor parametro) {
        return registros.stream().filter(r -> r.getParametro() == parametro).findFirst().orElse(null);
    }

    /** Agrega o reemplaza el registro de un parámetro. */
    public void ponerRegistro(RegistroModelo registro) {
        registros.removeIf(r -> r.getParametro() == registro.getParametro());
        registro.setModelo(this);
        registros.add(registro);
    }

    public void quitarRegistro(ParametroMedidor parametro) {
        registros.removeIf(r -> r.getParametro() == parametro);
    }

    @Override
    public String toString() {
        return nombre;
    }
}
