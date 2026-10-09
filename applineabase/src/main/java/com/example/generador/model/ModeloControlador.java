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
 */
public record ModeloControlador(String modelo, String descripcion, boolean confirmado, int maxHueco,
                               boolean noDisponible8000, List<Registro> registros) {

    public enum Tipo {
        UINT16(1), INT16(1), UINT32(2), INT32(2), FLOAT32(2);

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

    public Optional<Registro> registro(ParametroGenerador p) {
        return registros.stream().filter(r -> r.parametro() == p).findFirst();
    }
}
