package com.example.dataacquisition;

/**
 * Nombres de "máquina virtual" (sensores/plantas agregadas que no son una línea de producción
 * real, pero se guardan y consultan con el mismo esquema que cualquier otra línea) — estaban
 * repetidos como string literal en ChartsView, PLCDataAcquisitionService, AlarmaConfigSeeder y
 * TarjetasEstadoActual; centralizados acá para que un cambio de nombre no requiera buscarlos
 * uno por uno en cada archivo.
 */
public final class MaquinasVirtuales {

    public static final String TEMPERATURA_AGUA = "TemperaturaAgua";
    public static final String TEMPERATURA_AMBIENTE = "TemperaturaAmbiente";
    public static final String KWH_PLANTA_1 = "KWhPlanta1";

    private MaquinasVirtuales() {
    }
}
