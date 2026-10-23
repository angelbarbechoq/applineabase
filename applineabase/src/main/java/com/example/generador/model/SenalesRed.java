package com.example.generador.model;

import java.util.List;
import java.util.Set;

/**
 * Señales de red e interruptores que da el propio controlador (campo "red" del modelo en generador-modelos.json). Con
 * ellas la falla de red, la isla y el retorno de red salen de lo que dice el controlador, sin compararlo con el medidor
 * del transformador (IslaService). InteliGen 500 G2: lista Modbus de InteliConfig en docs/ig500/.
 *
 * @param paralelo   códigos del estado de los interruptores ({@link ParametroGenerador#ESTADO_INTERRUPTORES}) en
 *                   paralelo con la red; {@code isla}, {@code fallaRed} y {@code retornoRed}, los de cada caso sin red
 * @param nombres    nombre que da el controlador a cada código (índice = código), para las descripciones
 * @param mcbCerrado interruptor de la planta (MCB) cerrado; {@code gcbCerrado}, el del generador. Los bits que el
 *                   modelo no tiene quedan en null
 */
public record SenalesRed(Set<Integer> paralelo, Set<Integer> isla, Set<Integer> fallaRed, Set<Integer> retornoRed,
                         List<String> nombres, Bit mcbCerrado, Bit gcbCerrado, Bit avisoFallaRed, Bit proteccionRed,
                         Bit saltoVector, Bit rocof) {

    /** Lo que importa del estado de los interruptores. OTRO: sin generador conectado, sincronizando, etc. */
    public enum Estado { PARALELO, ISLA, FALLA_RED, RETORNO_RED, OTRO }

    /** Un bit de una palabra leída (bit 0 = el de menor peso). */
    public record Bit(ParametroGenerador parametro, int bit) {
    }

    /** Valor del bit en la lectura; null si el modelo no lo tiene o no se leyó la palabra. */
    public static Boolean valor(Bit b, LecturaGenerador l) {
        if (b == null) return null;
        Double v = l.valor(b.parametro());
        return v == null ? null : ((v.longValue() >> b.bit()) & 1) == 1;
    }

    /** Código del estado de los interruptores en la lectura (null = no se leyó). */
    public static Integer codigo(LecturaGenerador l) {
        Double v = l.valor(ParametroGenerador.ESTADO_INTERRUPTORES);
        return v == null ? null : (int) Math.round(v);
    }

    public Estado estado(Integer codigo) {
        if (codigo == null) return null;
        if (paralelo.contains(codigo)) return Estado.PARALELO;
        if (isla.contains(codigo)) return Estado.ISLA;
        if (fallaRed.contains(codigo)) return Estado.FALLA_RED;
        if (retornoRed.contains(codigo)) return Estado.RETORNO_RED;
        return Estado.OTRO;
    }

    /** "MainsFlt (7)": nombre del controlador y código; solo el código si no tiene nombre. */
    public String nombre(Integer codigo) {
        if (codigo == null) return "sin dato";
        return codigo >= 0 && codigo < nombres.size() ? nombres.get(codigo) + " (" + codigo + ")" : "codigo " + codigo;
    }

    /** Falla de red, isla o retorno de red: la planta no está conectada a la red. */
    public static boolean sinRed(Estado e) {
        return e == Estado.ISLA || e == Estado.FALLA_RED || e == Estado.RETORNO_RED;
    }

    /**
     * Sin red según la lectura: por el estado de los interruptores o, si el modelo no lo tiene, con el generador
     * conectado (GCB cerrado) y el interruptor de la planta abierto. null = no se puede saber.
     */
    public Boolean sinRed(LecturaGenerador l) {
        Estado e = estado(codigo(l));
        if (e != null) return sinRed(e);
        Boolean mcb = valor(mcbCerrado, l), gcb = valor(gcbCerrado, l);
        return mcb == null || gcb == null ? null : gcb && !mcb;
    }
}
