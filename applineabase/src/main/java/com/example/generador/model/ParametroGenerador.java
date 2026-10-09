package com.example.generador.model;

/**
 * Lista cerrada de valores que se leen de un controlador de generador. Cada modelo de
 * controlador (generador-modelos.json) dice en qué registro está cada uno; los que un modelo no
 * mapea quedan vacíos. La columna es el nombre en {mes}Generador (las primeras coinciden con las
 * columnas que ya existían antes del mapa configurable).
 */
public enum ParametroGenerador {
    RPM("rpm", "RPM", "rpm", false),
    FRECUENCIA("frecuencia", "Frecuencia generador", "Hz", false),
    V_L1N("v_l1n", "Generador V L1-N", "V", false),
    V_L2N("v_l2n", "Generador V L2-N", "V", false),
    V_L3N("v_l3n", "Generador V L3-N", "V", false),
    V_L1L2("v_l1l2", "Generador V L1-L2", "V", false),
    V_L2L3("v_l2l3", "Generador V L2-L3", "V", false),
    V_L3L1("v_l3l1", "Generador V L3-L1", "V", false),
    I_L1("i_l1", "Generador corriente L1", "A", false),
    I_L2("i_l2", "Generador corriente L2", "A", false),
    I_L3("i_l3", "Generador corriente L3", "A", false),
    KW("kw", "Generador kW total", "kW", false),
    KW_L1("kw_l1", "Generador kW L1", "kW", false),
    KW_L2("kw_l2", "Generador kW L2", "kW", false),
    KW_L3("kw_l3", "Generador kW L3", "kW", false),
    KVAR("kvar", "Generador kVAr total", "kVAr", false),
    KVA("kva", "Generador kVA total", "kVA", false),
    PF("pf", "Generador PF total", "", false),
    RED_FRECUENCIA("red_frecuencia", "Frecuencia red", "Hz", false),
    RED_V_L1N("red_v_l1n", "Red V L1-N", "V", false),
    RED_V_L2N("red_v_l2n", "Red V L2-N", "V", false),
    RED_V_L3N("red_v_l3n", "Red V L3-N", "V", false),
    RED_V_L1L2("red_v_l1l2", "Red V L1-L2", "V", false),
    RED_V_L2L3("red_v_l2l3", "Red V L2-L3", "V", false),
    RED_V_L3L1("red_v_l3l1", "Red V L3-L1", "V", false),
    RED_KW("red_kw", "Red kW importados (negativo = retorno a la red)", "kW", false),
    CARGA_KW("carga_kw", "Carga del tablero kW (medida por el controlador)", "kW", false),
    BATERIA("bateria", "Tension de bateria", "V", false),
    PRESION_ACEITE("presion_aceite", "Presion de aceite", "bar", false),
    TEMP_REFRIGERANTE("temp_refrigerante", "Temperatura refrigerante", "C", false),
    TEMP_ACEITE("temp_aceite", "Temperatura de aceite", "C", false),
    NIVEL_COMBUSTIBLE("nivel_combustible", "Nivel de combustible", "%", false),
    CONSUMO_COMBUSTIBLE("consumo_combustible", "Consumo de combustible", "L/h", false),
    KWH("kwh", "Energia generada (contador)", "kWh", true),
    KVARH("kvarh", "Energia reactiva generada (contador)", "kVArh", true),
    RED_KWH("red_kwh", "Energia de red (contador del controlador)", "kWh", true),
    RED_KVARH("red_kvarh", "Energia reactiva de red (contador del controlador)", "kVArh", true),
    HORAS_MARCHA("horas_marcha", "Horas de marcha", "h", false),
    ARRANQUES("arranques", "Cantidad de arranques", "", true);

    private final String columna;
    private final String etiqueta;
    private final String unidad;
    private final boolean entero;

    ParametroGenerador(String columna, String etiqueta, String unidad, boolean entero) {
        this.columna = columna;
        this.etiqueta = etiqueta;
        this.unidad = unidad;
        this.entero = entero;
    }

    public String columna() {
        return columna;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public String unidad() {
        return unidad;
    }

    /** Contadores: se guardan como INTEGER. */
    public boolean entero() {
        return entero;
    }
}
