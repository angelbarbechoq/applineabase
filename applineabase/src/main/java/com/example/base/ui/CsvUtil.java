package com.example.base.ui;

/**
 * Escapado de campos CSV (RFC 4180: si el valor contiene el separador ";", comillas o salto de
 * línea, se envuelve entre comillas dobles, duplicando las internas) — antes repetido igual en
 * HorometroView.
 */
public final class CsvUtil {

    private CsvUtil() {
    }

    public static String escape(String valor) {
        if (valor.contains(";") || valor.contains("\"") || valor.contains("\n")) {
            return "\"" + valor.replace("\"", "\"\"") + "\"";
        }
        return valor;
    }
}
