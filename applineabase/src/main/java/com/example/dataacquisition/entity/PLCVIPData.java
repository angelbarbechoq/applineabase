package com.example.dataacquisition.entity;

import java.math.BigDecimal;

public class PLCVIPData {
    private BigDecimal VAB;
    private BigDecimal VAC;
    private BigDecimal VBC;
    private BigDecimal IA;
    private BigDecimal IB;
    private BigDecimal IC;
    private BigDecimal PW;
    private BigDecimal PF;

    public PLCVIPData() {
    }

    // ===== GETTERS =====
    public BigDecimal getVAB() { return VAB; }
    public BigDecimal getVAC() { return VAC; }
    public BigDecimal getVBC() { return VBC; }
    public BigDecimal getIA() { return IA; }
    public BigDecimal getIB() { return IB; }
    public BigDecimal getIC() { return IC; }
    public BigDecimal getPW() { return PW; }
    public BigDecimal getPF() { return PF; }

    // ===== SETTERS =====
    public void setVAB(BigDecimal VAB) { this.VAB = VAB; }
    public void setVAC(BigDecimal VAC) { this.VAC = VAC; }
    public void setVBC(BigDecimal VBC) { this.VBC = VBC; }
    public void setIA(BigDecimal IA) { this.IA = IA; }
    public void setIB(BigDecimal IB) { this.IB = IB; }
    public void setIC(BigDecimal IC) { this.IC = IC; }
    public void setPW(BigDecimal PW) { this.PW = PW; }
    public void setPF(BigDecimal PF) { this.PF = PF; }

    // ===== TOSTRING INDIVIDUALES =====
    // ===== TOSTRING INDIVIDUALES (2 decimales) =====
    public String VABtoString() {
        return VAB != null ? String.format("%.2f", VAB) : "0.00";
    }

    public String VACtoString() {
        return VAC != null ? String.format("%.2f", VAC) : "0.00";
    }

    public String VBCtoString() {
        return VBC != null ? String.format("%.2f", VBC) : "0.00";
    }

    public String IAtoString() {
        return IA != null ? String.format("%.2f", IA) : "0.00";
    }

    public String IBtoString() {
        return IB != null ? String.format("%.2f", IB) : "0.00";
    }

    public String ICtoString() {
        return IC != null ? String.format("%.2f", IC) : "0.00";
    }

    public String PWtoString() {
        return PW != null ? String.format("%.2f", PW) : "0.00";
    }

    public String PFtoString() {
        return PF != null ? String.format("%.2f", PF) : "0.00";
    }
}