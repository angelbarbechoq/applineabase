package com.example.medidores.model;

/** Formato en que el medidor entrega un valor, y cuántos registros de 16 bits ocupa. */
public enum TipoDato {

    FLOAT32("Float32 (real)", 2),
    INT16("Int16 (entero con signo)", 1),
    UINT16("UInt16 (entero sin signo)", 1),
    INT32("Int32 (entero con signo)", 2),
    UINT32("UInt32 (entero sin signo)", 2),
    INT64("Int64 (entero con signo)", 4),
    UINT64("UInt64 (entero sin signo)", 4),
    /** Medidores ION: valor = alto x 10000 + bajo, las dos palabras con signo. */
    INT32_M10K("Int32 Modulo-10000 (ION, con signo)", 2),
    /** Medidores ION: valor = alto x 10000 + bajo, sin signo. */
    UINT32_M10K("UInt32 Modulo-10000 (ION, sin signo)", 2);

    private final String etiqueta;
    private final int registros;

    TipoDato(String etiqueta, int registros) {
        this.etiqueta = etiqueta;
        this.registros = registros;
    }

    public String getEtiqueta() {
        return etiqueta;
    }

    public int getRegistros() {
        return registros;
    }
}
