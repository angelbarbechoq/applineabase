package com.example.dataacquisition;

/**
 * Rutas, nombres de mes y esquema de columnas de los archivos SQLite de energía
 * (C:\LineaBaseX\{año}\{mes}\...). Única fuente de esta información: la usan
 * DatabaseInitializationService, PLCDataQueryService y MergeVipMensualTool, para que
 * un cambio de formato de carpetas o de columnas no requiera tocar más de un lugar.
 */
public final class RutaArchivosEnergia {

    public static final String BASE_PATH = "C:\\LineaBaseX";
    public static final String[] CAMPOS_NORMAL = {"kwh"};
    public static final String[] CAMPOS_VIP = {"VAB", "VAC", "VBC", "IA", "IB", "IC", "PW", "PF", "KWhR"};

    private static final String[] NOMBRES_MES = {
            "enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
    };

    private RutaArchivosEnergia() {
    }

    public static String getNombreMes(int mes) {
        return NOMBRES_MES[mes - 1];
    }

    /** Número de mes (1-12) a partir del nombre en español, o -1 si no coincide con ninguno. */
    public static int getMesNumeroDesdeNombre(String nombreMes) {
        for (int i = 0; i < NOMBRES_MES.length; i++) {
            if (NOMBRES_MES[i].equalsIgnoreCase(nombreMes)) {
                return i + 1;
            }
        }
        return -1;
    }
}
