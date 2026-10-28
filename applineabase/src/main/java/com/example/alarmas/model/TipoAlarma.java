package com.example.alarmas.model;

public enum TipoAlarma {
    /** KWh casi sin variación (1ra derivada ~0) sostenido: máquina detenida. */
    DETENCION,
    /** Compresores cíclicos (Sauer, CompAP): tiempo encendido continuo excede el máximo esperado. */
    CICLO_COMPRESOR,
    /** Temperatura por encima del máximo configurado. */
    TEMPERATURA_ALTA,
    /** Factor de potencia (valor absoluto) por debajo del mínimo configurado. */
    FACTOR_POTENCIA_BAJO,
    /** El dispositivo (PLC o gateway PAS600L) no responde: falla el ping o la conexión Modbus. */
    DISPOSITIVO_NO_DISPONIBLE,
    /**
     * Alarma de la lista de alarmas del controlador de un generador (ComAp). No se configura: la define el
     * controlador. Un evento por alarma mientras esté en la lista (GeneradorAlarmasService).
     */
    ALARMA_GENERADOR,
    /**
     * Pérdida de red según el controlador de un generador: falla de red, isla o retorno de red
     * (RedControladorService). No se configura; urgente, abierta mientras dure.
     */
    PERDIDA_RED,
    /**
     * Potencia de un generador sobre el límite de generador-config.json ("alarmaPotenciaKw" de cada generador), según
     * su controlador (GeneradorAlarmasService). Abierta mientras dure; no urgente.
     */
    POTENCIA_GENERADOR;

    /** Alarmas de los generadores: no se configuran y las ve quien tiene "Ver Generadores". */
    public boolean deGenerador() {
        return this == ALARMA_GENERADOR || this == PERDIDA_RED || this == POTENCIA_GENERADOR;
    }
}
