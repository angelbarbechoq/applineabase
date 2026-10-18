package com.example.generador.model;

/**
 * Semáforo del indicador de la meta "consumo cero de la red": parte del consumo que se importó de la red (%),
 * con meta 0. Verde hasta {@code verdeHasta}, amarillo hasta {@code amarilloHasta}, rojo por encima.
 * Se configura en generador-config.json (clave "metaImportacionRed"), sin recompilar; lo usan la pantalla de
 * Análisis y el reporte de generadores.
 */
public record MetaImportacionRed(double verdeHasta, double amarilloHasta) {

    public static final MetaImportacionRed POR_DEFECTO = new MetaImportacionRed(2.0, 10.0);

    public enum Zona { VERDE, AMARILLO, ROJO }

    public Zona zona(double porcentaje) {
        if (porcentaje <= verdeHasta) return Zona.VERDE;
        return porcentaje <= amarilloHasta ? Zona.AMARILLO : Zona.ROJO;
    }
}
