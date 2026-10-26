package com.example.generador.model;

import java.util.List;
import java.util.Optional;

/**
 * Mapa de registros Modbus de un modelo de controlador de generador, leído de
 * {@code C:\LineaBaseX\config\generador-modelos.json} (se edita sin recompilar).
 *
 * @param confirmado false = mapa sin verificar contra la pantalla del controlador: se muestra en
 *                   vivo con aviso pero no se guarda historial ni arranques.
 * @param maxHueco   registros sin mapear que se aceptan dentro de un mismo pedido. ComAp contesta
 *                   0x02 si el pedido incluye un registro que no existe, así que solo se sube
 *                   cuando se sabe que los del hueco existen (0 = solo registros contiguos).
 * @param noDisponible8000 0x8000 (16 bits) / 0x80000000 (32 bits) = dato no disponible (ComAp).
 * @param listaAlarmas dónde está la lista de alarmas activas del controlador (null = no se lee).
 * @param reloj        primer registro (4xxxx) del reloj del controlador (null = no se lee). ComAp:
 *                     4 registros en BCD desde 44201: hh mm | ss 00 | dd MM | aa 00.
 * @param red          señales de red e interruptores que da el controlador (null = el modelo no las tiene: la isla
 *                     se detecta comparando con el medidor del transformador, ver IslaService).
 * @param modos        nombre de cada código de {@link ParametroGenerador#MODO_CONTROLADOR} (índice = código; ComAp:
 *                     OFF, MAN, AUTO, TEST). Vacía = solo se muestra el código.
 * @param estadosMotor nombre de cada código de {@link ParametroGenerador#ESTADO_MOTOR} (Ready, NotReady, Loaded...)
 * @param entradas     nombre de cada bit de {@link ParametroGenerador#ENTRADAS_BINARIAS} (bit 0 primero), para el tablero
 * @param salidas      ídem de {@link ParametroGenerador#SALIDAS_BINARIAS}
 * @param listoAmf     bit "Ready To AMF" de ComAp: si se corta la red, el generador entra solo (en AUTO y sin alarma que
 *                     le impida arrancar, o ya en marcha en AUTO). null = el modelo no lo tiene.
 */
public record ModeloControlador(String modelo, String descripcion, boolean confirmado, int maxHueco,
                               boolean noDisponible8000, List<Registro> registros, ListaAlarmas listaAlarmas,
                               Integer reloj, SenalesRed red, List<String> modos, List<String> estadosMotor,
                               List<String> entradas, List<String> salidas, SenalesRed.Bit listoAmf) {

    /** Listo para AMF en la lectura; null si el modelo no lo da o no se leyó. */
    public Boolean listoAmf(LecturaGenerador l) {
        return SenalesRed.valor(listoAmf, l);
    }

    /** Modo del controlador en la lectura ("AUTO"); null si el modelo no lo da o no se leyó. */
    public String modo(LecturaGenerador l) {
        return nombre(modos, l.valor(ParametroGenerador.MODO_CONTROLADOR));
    }

    /** Estado del motor en la lectura ("Ready"); null si el modelo no lo da o no se leyó. */
    public String estadoMotor(LecturaGenerador l) {
        return nombre(estadosMotor, l.valor(ParametroGenerador.ESTADO_MOTOR));
    }

    /** Nombre del código en la lista (índice = código); "codigo N" si no tiene nombre; null sin valor. */
    private static String nombre(List<String> nombres, Double v) {
        if (v == null) return null;
        int codigo = (int) Math.round(v);
        return codigo >= 0 && codigo < nombres.size() ? nombres.get(codigo) : "codigo " + codigo;
    }

    /** BITS16: palabra de bits; nunca es "no disponible" (0x8000 = solo el bit 15 activo). */
    public enum Tipo {
        UINT16(1), INT16(1), BITS16(1), UINT32(2), INT32(2), FLOAT32(2);

        private final int cantidad;

        Tipo(int cantidad) {
            this.cantidad = cantidad;
        }

        public int cantidad() {
            return cantidad;
        }
    }

    /**
     * @param registro número como figura en la lista del fabricante (4xxxx); base 0 = registro - 40001.
     * @param divisor  el valor leído se divide por este número (272 / 10 = 27,2 V).
     */
    public record Registro(ParametroGenerador parametro, int registro, Tipo tipo, double divisor,
                           boolean palabraBajaPrimero) {
        public int direccion() {
            return registro - 40001;
        }

        public int cantidad() {
            return tipo.cantidad();
        }
    }

    /**
     * Lista de alarmas del controlador (ComAp: cantidad en 44215 y cada alarma en un bloque de 27
     * registros desde 44216; solo lectura). Registros en numeración 4xxxx.
     */
    public record ListaAlarmas(int registroCantidad, int primerRegistro, int largoRegistro, int maximo) {
    }

    public Optional<Registro> registro(ParametroGenerador p) {
        return registros.stream().filter(r -> r.parametro() == p).findFirst();
    }
}
