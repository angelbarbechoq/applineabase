package com.example.medidores.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Dónde y cómo leer un parámetro en un modelo de medidor. Si el parámetro no tiene fila, el
 * modelo no lo ofrece ("No disponible").
 */
@Entity
@Table(name = "registro_modelo_medidor",
        uniqueConstraints = @UniqueConstraint(columnNames = {"modelo_id", "parametro"}))
public class RegistroModelo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "modelo_id")
    private ModeloMedidor modelo;

    // varchar y no ENUM nativo de H2: un ENUM no acepta valores nuevos con ddl-auto=update (ver schema.sql).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private ParametroMedidor parametro;

    /** Registro como figura en el manual (ver {@link ModeloMedidor#isNumeracionManual()}). */
    @Column(nullable = false)
    private int registro;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private TipoDato tipoDato = TipoDato.FLOAT32;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private OrdenPalabras ordenPalabras = OrdenPalabras.NORMAL;

    /** Multiplicador aplicado al valor crudo (ej. 0.001 para pasar Wh a kWh). */
    @Column(columnDefinition = "double default 1")
    private double escala = 1.0;

    /** El factor de potencia viene codificado en 4 cuadrantes (Schneider PM5xxx). */
    @Column(columnDefinition = "boolean default false")
    private boolean pf4Cuadrantes;

    public RegistroModelo() {
    }

    public RegistroModelo(ParametroMedidor parametro, int registro, TipoDato tipoDato) {
        this.parametro = parametro;
        this.registro = registro;
        this.tipoDato = tipoDato;
    }

    public Long getId() {
        return id;
    }

    public ModeloMedidor getModelo() {
        return modelo;
    }

    public void setModelo(ModeloMedidor modelo) {
        this.modelo = modelo;
    }

    public ParametroMedidor getParametro() {
        return parametro;
    }

    public void setParametro(ParametroMedidor parametro) {
        this.parametro = parametro;
    }

    public int getRegistro() {
        return registro;
    }

    public void setRegistro(int registro) {
        this.registro = registro;
    }

    public TipoDato getTipoDato() {
        return tipoDato;
    }

    public void setTipoDato(TipoDato tipoDato) {
        this.tipoDato = tipoDato;
    }

    public OrdenPalabras getOrdenPalabras() {
        return ordenPalabras;
    }

    public void setOrdenPalabras(OrdenPalabras ordenPalabras) {
        this.ordenPalabras = ordenPalabras;
    }

    public double getEscala() {
        return escala;
    }

    public void setEscala(double escala) {
        this.escala = escala;
    }

    public boolean isPf4Cuadrantes() {
        return pf4Cuadrantes;
    }

    public void setPf4Cuadrantes(boolean pf4Cuadrantes) {
        this.pf4Cuadrantes = pf4Cuadrantes;
    }

    /** Copia sin id ni modelo, para duplicar un modelo. */
    public RegistroModelo copia() {
        RegistroModelo c = new RegistroModelo(parametro, registro, tipoDato);
        c.ordenPalabras = ordenPalabras;
        c.escala = escala;
        c.pf4Cuadrantes = pf4Cuadrantes;
        return c;
    }
}
