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
    DISPOSITIVO_NO_DISPONIBLE
}
