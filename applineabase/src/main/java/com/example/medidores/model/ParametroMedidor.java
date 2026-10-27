package com.example.medidores.model;

/**
 * Lista cerrada de parámetros que se pueden leer de un medidor. Cada modelo del catálogo dice en
 * qué registro está cada uno (o que no lo tiene). Los básicos son obligatorios para que la
 * pasarela pueda leer el medidor: son los que se guardan hoy (kWh y VIP).
 */
public enum ParametroMedidor {

    KWH("Energía activa", "kWh", true),
    VAB("Tensión A-B", "V", true),
    VBC("Tensión B-C", "V", true),
    VCA("Tensión C-A", "V", true),
    IA("Corriente A", "A", true),
    IB("Corriente B", "A", true),
    IC("Corriente C", "A", true),
    KW_TOTAL("Potencia activa total", "kW", true),
    PF_TOTAL("Factor de potencia total", "", true),

    IN("Corriente neutro", "A", false),
    VAN("Tensión A-N", "V", false),
    VBN("Tensión B-N", "V", false),
    VCN("Tensión C-N", "V", false),
    KW_A("Potencia activa A", "kW", false),
    KW_B("Potencia activa B", "kW", false),
    KW_C("Potencia activa C", "kW", false),
    KVAR_TOTAL("Potencia reactiva total", "kVAR", false),
    KVA_TOTAL("Potencia aparente total", "kVA", false),
    PF_A("Factor de potencia A", "", false),
    PF_B("Factor de potencia B", "", false),
    PF_C("Factor de potencia C", "", false),
    FRECUENCIA("Frecuencia", "Hz", false),
    KVARH("Energía reactiva", "kVARh", false),
    KVAH("Energía aparente", "kVAh", false),
    /** Columna KWhR de las tablas VIP ("KWh Retorno" en el PLC). */
    KWH_RETORNO("Energía activa de retorno (KWhR)", "kWh", false),
    DESBALANCE_I("Desbalance de corriente (peor fase)", "%", false),
    DESBALANCE_V("Desbalance de tensión L-L (peor fase)", "%", false),
    THD_IA("THD corriente A", "%", false),
    THD_IB("THD corriente B", "%", false),
    THD_IC("THD corriente C", "%", false),
    THD_VAB("THD tensión A-B", "%", false),
    THD_VBC("THD tensión B-C", "%", false),
    THD_VCA("THD tensión C-A", "%", false),
    THD_VAN("THD tensión A-N", "%", false),
    THD_VBN("THD tensión B-N", "%", false),
    THD_VCN("THD tensión C-N", "%", false);

    private final String etiqueta;
    private final String unidad;
    private final boolean basico;

    ParametroMedidor(String etiqueta, String unidad, boolean basico) {
        this.etiqueta = etiqueta;
        this.unidad = unidad;
        this.basico = basico;
    }

    public String getEtiqueta() {
        return etiqueta;
    }

    public String getUnidad() {
        return unidad;
    }

    public boolean isBasico() {
        return basico;
    }

    /** Factores de potencia: los únicos que pueden venir en formato de 4 cuadrantes. */
    public boolean esFactorPotencia() {
        return this == PF_TOTAL || this == PF_A || this == PF_B || this == PF_C;
    }
}
