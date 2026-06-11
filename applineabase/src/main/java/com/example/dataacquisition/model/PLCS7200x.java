package com.example.dataacquisition.model;

import java.math.BigDecimal;

/**
 * Model for Siemens S7-200 PLC device.
 *
 * Stores electrical measurements:
 * - KWh (current and last readings)
 * - Voltages (VAB, VBC, VAC)
 * - Currents (IA, IB, IC)
 * - Power (KW)
 *
 * @author MantenimientoX
 */
public class PLCS7200x {

    BigDecimal[] KWhXAct = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    BigDecimal[] KWhXLast = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    BigDecimal[] VAB = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    BigDecimal[] VBC = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    BigDecimal[] VAC = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    BigDecimal[] IA = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    BigDecimal[] IB = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    BigDecimal[] IC = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    BigDecimal[] KW = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    String PlcIPx;
    String Nombre;

    public PLCS7200x(String PlcIPx, String Nombre) {
        this.PlcIPx = PlcIPx;
        this.Nombre = Nombre;
    }

    public String getNombre() {
        return Nombre;
    }

    public void setNombre(String Nombre) {
        this.Nombre = Nombre;
    }

    public BigDecimal getKWhXAct(int indice) {
        return KWhXAct[indice];
    }

    public void setKWhXAct(BigDecimal KWhXAct, int indice) {
        this.KWhXAct[indice] = KWhXAct;
    }

    public BigDecimal getKWhXLast(int indice) {
        return KWhXLast[indice];
    }

    public void setKWhXLast(BigDecimal KWhXLast, int indice) {
        this.KWhXLast[indice] = KWhXLast;
    }

    public String getPlcIPx() {
        return PlcIPx;
    }

    public BigDecimal[] getKWhXAct() {
        return KWhXAct;
    }

    public void setKWhXAct(BigDecimal[] KWhXAct) {
        this.KWhXAct = KWhXAct;
    }

    public BigDecimal[] getKWhXLast() {
        return KWhXLast;
    }

    public void setKWhXLast(BigDecimal[] KWhXLast) {
        this.KWhXLast = KWhXLast;
    }

    public void setPlcIPx(String PlcIPx) {
        this.PlcIPx = PlcIPx;
    }

    public BigDecimal[] getVAB() {
        return VAB;
    }

    public BigDecimal getVAB(int Indice) {
        return VAB[Indice];
    }

    public void setVAB(BigDecimal VAB, int Indice) {
        this.VAB[Indice] = VAB;
    }

    public BigDecimal[] getVBC() {
        return VBC;
    }

    public BigDecimal getVBC(int Indice) {
        return VBC[Indice];
    }

    public void setVBC(BigDecimal VBC, int Indice) {
        this.VBC[Indice] = VBC;
    }

    public BigDecimal[] getVAC() {
        return VAC;
    }

    public BigDecimal getVAC(int Indice) {
        return VAC[Indice];
    }

    public void setVAC(BigDecimal VAC, int Indice) {
        this.VAC[Indice] = VAC;
    }

    public BigDecimal[] getIA() {
        return IA;
    }

    public BigDecimal getIA(int Indice) {
        return IA[Indice];
    }

    public void setIA(BigDecimal IA, int Indice) {
        this.IA[Indice] = IA;
    }

    public BigDecimal[] getIB() {
        return IB;
    }

    public BigDecimal getIB(int Indice) {
        return IB[Indice];
    }

    public void setIB(BigDecimal IB, int Indice) {
        this.IB[Indice] = IB;
    }

    public BigDecimal[] getIC() {
        return IC;
    }

    public BigDecimal getIC(int Indice) {
        return IC[Indice];
    }

    public void setIC(BigDecimal IC, int Indice) {
        this.IC[Indice] = IC;
    }

    public BigDecimal[] getKW() {
        return KW;
    }

    public BigDecimal getKW(int Indice) {
        return KW[Indice];
    }

    public void setKW(BigDecimal KW, int Indice) {
        this.KW[Indice] = KW;
    }
}
