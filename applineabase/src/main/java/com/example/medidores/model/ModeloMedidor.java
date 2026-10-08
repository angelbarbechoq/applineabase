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

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(20) default 'FASE_FASE'")
    private TensionesHistorico tensionesHistorico = TensionesHistorico.FASE_FASE;

    /**
     * El catálogo siempre entrega unidades estándar (kW, PF -1..1). Estas dos marcas solo
     * convierten al guardar en el histórico VIP, para que un medidor que pasa del PLC a pasarela
     * siga guardando como lo hacía el PLC (ION8600: W y PF en %; PAC1020: W).
     */
    @Column(columnDefinition = "boolean default false")
    private boolean historicoPotenciaEnW;

    @Column(columnDefinition = "boolean default false")
    private boolean historicoPfEnPorcentaje;

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

    public TensionesHistorico getTensionesHistorico() {
        return tensionesHistorico == null ? TensionesHistorico.FASE_FASE : tensionesHistorico;
    }

    public void setTensionesHistorico(TensionesHistorico tensionesHistorico) {
        this.tensionesHistorico = tensionesHistorico;
    }

    public boolean isHistoricoPotenciaEnW() {
        return historicoPotenciaEnW;
    }

    public void setHistoricoPotenciaEnW(boolean historicoPotenciaEnW) {
        this.historicoPotenciaEnW = historicoPotenciaEnW;
    }

    public boolean isHistoricoPfEnPorcentaje() {
        return historicoPfEnPorcentaje;
    }

    public void setHistoricoPfEnPorcentaje(boolean historicoPfEnPorcentaje) {
        this.historicoPfEnPorcentaje = historicoPfEnPorcentaje;
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
