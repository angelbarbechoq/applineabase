package com.example.alarmas.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Umbrales de alarma configurables por línea/máquina. Cada fila representa una
 * regla (línea + tipo) editable únicamente por el ADMIN desde AlarmasConfigView.
 */
@Entity
@Table(name = "alarma_config", uniqueConstraints = @UniqueConstraint(columnNames = {"lineaMaquina", "tipoAlarma"}))
public class AlarmaConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String lineaMaquina;

    // columnDefinition explicito: sin esto, Hibernate mapea @Enumerated(STRING) a un ENUM
    // nativo de H2 con la lista de constantes vigente al crear la columna, y ddl-auto=update
    // no la ensancha cuando se agrega una constante nueva a TipoAlarma (paso justo con
    // DISPOSITIVO_NO_DISPONIBLE). VARCHAR evita que vuelva a pasar con futuros valores.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(40)")
    private TipoAlarma tipoAlarma;

    @Column(nullable = false)
    private boolean habilitada = true;

    /** Potencia mínima en kW para considerar la máquina encendida (DETENCION y CICLO_COMPRESOR). */
    private Double umbralMinimoKw;

    /** Ciclos de lectura consecutivos con diferencia bajo epsilon antes de disparar (DETENCION). */
    private Integer ventanaCiclos;

    /** Minutos máximos de encendido continuo antes de disparar (CICLO_COMPRESOR). */
    private Integer minutosMaxEncendido;

    /** Temperatura máxima permitida en °C (TEMPERATURA_ALTA). */
    private Double temperaturaMaxima;

    /** Factor de potencia mínimo, comparado en valor absoluto (FACTOR_POTENCIA_BAJO). */
    private Double factorPotenciaMinimo;

    /** Lecturas fallidas consecutivas antes de avisar (DISPOSITIVO_NO_DISPONIBLE); una lectura
     * aislada no dispara alarma, solo se cuenta hasta llegar a este umbral. */
    private Integer lecturasConsecutivasUrgente;

    public AlarmaConfig() {
    }

    public Long getId() {
        return id;
    }

    public String getLineaMaquina() {
        return lineaMaquina;
    }

    public void setLineaMaquina(String lineaMaquina) {
        this.lineaMaquina = lineaMaquina;
    }

    public TipoAlarma getTipoAlarma() {
        return tipoAlarma;
    }

    public void setTipoAlarma(TipoAlarma tipoAlarma) {
        this.tipoAlarma = tipoAlarma;
    }

    public boolean isHabilitada() {
        return habilitada;
    }

    public void setHabilitada(boolean habilitada) {
        this.habilitada = habilitada;
    }

    public Double getUmbralMinimoKw() {
        return umbralMinimoKw;
    }

    public void setUmbralMinimoKw(Double umbralMinimoKw) {
        this.umbralMinimoKw = umbralMinimoKw;
    }

    public Integer getVentanaCiclos() {
        return ventanaCiclos;
    }

    public void setVentanaCiclos(Integer ventanaCiclos) {
        this.ventanaCiclos = ventanaCiclos;
    }

    public Integer getMinutosMaxEncendido() {
        return minutosMaxEncendido;
    }

    public void setMinutosMaxEncendido(Integer minutosMaxEncendido) {
        this.minutosMaxEncendido = minutosMaxEncendido;
    }

    public Double getTemperaturaMaxima() {
        return temperaturaMaxima;
    }

    public void setTemperaturaMaxima(Double temperaturaMaxima) {
        this.temperaturaMaxima = temperaturaMaxima;
    }

    public Double getFactorPotenciaMinimo() {
        return factorPotenciaMinimo;
    }

    public void setFactorPotenciaMinimo(Double factorPotenciaMinimo) {
        this.factorPotenciaMinimo = factorPotenciaMinimo;
    }

    public Integer getLecturasConsecutivasUrgente() {
        return lecturasConsecutivasUrgente;
    }

    public void setLecturasConsecutivasUrgente(Integer lecturasConsecutivasUrgente) {
        this.lecturasConsecutivasUrgente = lecturasConsecutivasUrgente;
    }
}
