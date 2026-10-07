package com.example.medidores.model;

/**
 * Lista cerrada de parámetros que se pueden leer de un medidor. Cada modelo del catálogo dice en
 * qué registro está cada uno (o que no lo tiene). Los básicos son obligatorios para que la
 * pasarela pueda leer el medidor: son los que se guardan hoy (kWh y VIP).
 */
public enum ParametroMedidor {

    KWH("Energia activa", "kWh", true),
    VAB("Tension A-B", "V", true),
    VBC("Tension B-C", "V", true),
    VCA("Tension C-A", "V", true),
    IA("Corriente A", "A", true),
    IB("Corriente B", "A", true),
    IC("Corriente C", "A", true),
    KW_TOTAL("Potencia activa total", "kW", true),
    PF_TOTAL("Factor de potencia total", "", true),

    IN("Corriente neutro", "A", false),
    VAN("Tension A-N", "V", false),
    VBN("Tension B-N", "V", false),
    VCN("Tension C-N", "V", false),
    KW_A("Potencia activa A", "kW", false),
    KW_B("Potencia activa B", "kW", false),
    KW_C("Potencia activa C", "kW", false),
    KVAR_TOTAL("Potencia reactiva total", "kVAR", false),
    KVA_TOTAL("Potencia aparente total", "kVA", false),
    PF_A("Factor de potencia A", "", false),
    PF_B("Factor de potencia B", "", false),
    PF_C("Factor de potencia C", "", false),
    FRECUENCIA("Frecuencia", "Hz", false),
    KVARH("Energia reactiva", "kVARh", false),
    KVAH("Energia aparente", "kVAh", false),
    /** Columna KWhR de las tablas VIP ("KWh Retorno" en el PLC). */
    KWH_RETORNO("Energia activa de retorno (KWhR)", "kWh", false),
    DESBALANCE_I("Desbalance de corriente (peor fase)", "%", false),
    DESBALANCE_V("Desbalance de tension L-L (peor fase)", "%", false),
    THD_IA("THD corriente A", "%", false),
    THD_IB("THD corriente B", "%", false),
    THD_IC("THD corriente C", "%", false),
    THD_VAB("THD tension A-B", "%", false),
    THD_VBC("THD tension B-C", "%", false),
    THD_VCA("THD tension C-A", "%", false),
    THD_VAN("THD tension A-N", "%", false),
    THD_VBN("THD tension B-N", "%", false),
    THD_VCN("THD tension C-N", "%", false);

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
