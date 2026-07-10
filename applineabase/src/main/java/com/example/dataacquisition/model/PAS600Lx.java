package com.example.dataacquisition.model;

import java.math.BigDecimal;
import java.util.ArrayList;

/**
 * Model for Schneider PAS600L gateway device.
 *
 * Stores electrical measurements from multiple meters connected via Modbus:
 * - KWh (current and last readings)
 * - Voltages (VAB, VAC, VBC) - order consistent with PLCs
 * - Currents (IA, IB, IC)
 * - Power (KW)
 * - Power Factor (PF)
 *
 * Uses ArrayList for dynamic sizing (number of meters can vary).
 *
 * @author MantenimientoX
 */
public class PAS600Lx {

    private String gatewayIP;
    private String gatewayNombre;

    // Arrays of measurements per meter (indexed by order in config, not Unit ID)
    private ArrayList<BigDecimal> KWhAct = new ArrayList<>();   // Current KWh reading
    private ArrayList<BigDecimal> KWhAnt = new ArrayList<>();   // Previous KWh reading
    private ArrayList<BigDecimal> VAB = new ArrayList<>();      // Voltage A-B
    private ArrayList<BigDecimal> VAC = new ArrayList<>();      // Voltage A-C
    private ArrayList<BigDecimal> VBC = new ArrayList<>();      // Voltage B-C
    private ArrayList<BigDecimal> IA = new ArrayList<>();       // Current phase A
    private ArrayList<BigDecimal> IB = new ArrayList<>();       // Current phase B
    private ArrayList<BigDecimal> IC = new ArrayList<>();       // Current phase C
    private ArrayList<BigDecimal> KW = new ArrayList<>();       // Active power
    private ArrayList<BigDecimal> PF = new ArrayList<>();       // Power factor

    public PAS600Lx(String gatewayIP, String gatewayNombre) {
        this.gatewayIP = gatewayIP;
        this.gatewayNombre = gatewayNombre;
    }

    // === Basic Getters/Setters ===

    public String getGatewayIP() {
        return gatewayIP;
    }

    public void setGatewayIP(String gatewayIP) {
        this.gatewayIP = gatewayIP;
    }

    public String getNombrex() {
        return gatewayNombre;
    }

    public void setNombrex(String gatewayNombre) {
        this.gatewayNombre = gatewayNombre;
    }

    // === ArrayList Getters ===

    public ArrayList<BigDecimal> getKWhAct() {
        return KWhAct;
    }

    public ArrayList<BigDecimal> getKWhAnt() {
        return KWhAnt;
    }

    public ArrayList<BigDecimal> getVAB() {
        return VAB;
    }

    public ArrayList<BigDecimal> getVAC() {
        return VAC;
    }

    public ArrayList<BigDecimal> getVBC() {
        return VBC;
    }

    public ArrayList<BigDecimal> getIA() {
        return IA;
    }

    public ArrayList<BigDecimal> getIB() {
        return IB;
    }

    public ArrayList<BigDecimal> getIC() {
        return IC;
    }

    public ArrayList<BigDecimal> getKW() {
        return KW;
    }

    public ArrayList<BigDecimal> getPF() {
        return PF;
    }

    // === Index-based Getters ===

    public BigDecimal getKWhActx(int index) {
        if (index >= 0 && index < KWhAct.size()) {
            return KWhAct.get(index);
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal getKWhAntx(int index) {
        if (index >= 0 && index < KWhAnt.size()) {
            return KWhAnt.get(index);
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal getVABx(int index) {
        if (index >= 0 && index < VAB.size()) {
            return VAB.get(index);
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal getVACx(int index) {
        if (index >= 0 && index < VAC.size()) {
            return VAC.get(index);
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal getVBCx(int index) {
        if (index >= 0 && index < VBC.size()) {
            return VBC.get(index);
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal getIAx(int index) {
        if (index >= 0 && index < IA.size()) {
            return IA.get(index);
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal getIBx(int index) {
        if (index >= 0 && index < IB.size()) {
            return IB.get(index);
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal getICx(int index) {
        if (index >= 0 && index < IC.size()) {
            return IC.get(index);
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal getKWx(int index) {
        if (index >= 0 && index < KW.size()) {
            return KW.get(index);
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal getPFx(int index) {
        if (index >= 0 && index < PF.size()) {
            return PF.get(index);
        }
        return BigDecimal.ZERO;
    }

    // === Index-based Setters ===

    public void setKWhActx(int index, BigDecimal value) {
        ensureCapacity(KWhAct, index);
        KWhAct.set(index, value);
    }

    public void setKWhAntx(int index, BigDecimal value) {
        ensureCapacity(KWhAnt, index);
        KWhAnt.set(index, value);
    }

    public void setVABx(int index, BigDecimal value) {
        ensureCapacity(VAB, index);
        VAB.set(index, value);
    }

    public void setVACx(int index, BigDecimal value) {
        ensureCapacity(VAC, index);
        VAC.set(index, value);
    }

    public void setVBCx(int index, BigDecimal value) {
        ensureCapacity(VBC, index);
        VBC.set(index, value);
    }

    public void setIAx(int index, BigDecimal value) {
        ensureCapacity(IA, index);
        IA.set(index, value);
    }

    public void setIBx(int index, BigDecimal value) {
        ensureCapacity(IB, index);
        IB.set(index, value);
    }

    public void setICx(int index, BigDecimal value) {
        ensureCapacity(IC, index);
        IC.set(index, value);
    }

    public void setKWx(int index, BigDecimal value) {
        ensureCapacity(KW, index);
        KW.set(index, value);
    }

    public void setPFx(int index, BigDecimal value) {
        ensureCapacity(PF, index);
        PF.set(index, value);
    }

    // === Utility Method ===

    private void ensureCapacity(ArrayList<BigDecimal> list, int index) {
        while (list.size() <= index) {
            list.add(BigDecimal.ZERO);
        }
    }
}
