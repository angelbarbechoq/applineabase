package com.example.calidad.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Límites de calidad de energía (una sola fila, id = 1). Valores por defecto propuestos al usuario
 * el 2026-10-08 a partir de las normas; se ajustan desde la pestaña Limites (ADMIN).
 * - THD de tensión: IEEE 519-2014 (8 % hasta 1 kV, 5 % de 1 a 69 kV).
 * - THD de corriente: 20 % como aviso (el límite real depende de Icc/IL de cada tablero).
 * - Desbalance de tensión: 2 % (EN 50160 / NEMA). Desbalance de corriente: 10 % (criterio interno).
 * - Tensión: ±5 % aviso, ±10 % fuera de la nominal (EN 50160). Frecuencia: ±1 % de 60 Hz.
 * - PF mínimo: 0.92 (tarifa).
 */
@Entity
@Table(name = "configuracion_calidad")
public class ConfiguracionCalidad {

    public static final long ID_UNICO = 1L;

    @Id
    private Long id = ID_UNICO;

    @Column(columnDefinition = "double default 8")
    private double thdTensionBajaTension = 8.0;
    @Column(columnDefinition = "double default 5")
    private double thdTensionMediaTension = 5.0;
    @Column(columnDefinition = "double default 20")
    private double thdCorriente = 20.0;
    @Column(columnDefinition = "double default 2")
    private double desbalanceTension = 2.0;
    @Column(columnDefinition = "double default 10")
    private double desbalanceCorriente = 10.0;
    @Column(columnDefinition = "double default 5")
    private double tensionAvisoPct = 5.0;
    @Column(columnDefinition = "double default 10")
    private double tensionFueraPct = 10.0;
    @Column(columnDefinition = "double default 1")
    private double frecuenciaPct = 1.0;
    @Column(columnDefinition = "double default 60")
    private double frecuenciaNominal = 60.0;
    @Column(columnDefinition = "double default 0.92")
    private double pfMinimo = 0.92;
    /** Tensión fase-fase supuesta para las máquinas sin nominal cargada. */
    @Column(columnDefinition = "double default 460")
    private double tensionNominalPorDefecto = 460.0;

    public Long getId() { return id; }
    public double getThdTensionBajaTension() { return thdTensionBajaTension; }
    public void setThdTensionBajaTension(double v) { this.thdTensionBajaTension = v; }
    public double getThdTensionMediaTension() { return thdTensionMediaTension; }
    public void setThdTensionMediaTension(double v) { this.thdTensionMediaTension = v; }
    public double getThdCorriente() { return thdCorriente; }
    public void setThdCorriente(double v) { this.thdCorriente = v; }
    public double getDesbalanceTension() { return desbalanceTension; }
    public void setDesbalanceTension(double v) { this.desbalanceTension = v; }
    public double getDesbalanceCorriente() { return desbalanceCorriente; }
    public void setDesbalanceCorriente(double v) { this.desbalanceCorriente = v; }
    public double getTensionAvisoPct() { return tensionAvisoPct; }
    public void setTensionAvisoPct(double v) { this.tensionAvisoPct = v; }
    public double getTensionFueraPct() { return tensionFueraPct; }
    public void setTensionFueraPct(double v) { this.tensionFueraPct = v; }
    public double getFrecuenciaPct() { return frecuenciaPct; }
    public void setFrecuenciaPct(double v) { this.frecuenciaPct = v; }
    public double getFrecuenciaNominal() { return frecuenciaNominal; }
    public void setFrecuenciaNominal(double v) { this.frecuenciaNominal = v; }
    public double getPfMinimo() { return pfMinimo; }
    public void setPfMinimo(double v) { this.pfMinimo = v; }
    public double getTensionNominalPorDefecto() { return tensionNominalPorDefecto; }
    public void setTensionNominalPorDefecto(double v) { this.tensionNominalPorDefecto = v; }
}
