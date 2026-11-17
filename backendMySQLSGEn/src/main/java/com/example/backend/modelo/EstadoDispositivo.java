package com.example.backend.modelo;

/**
 * Cómo le fue a un equipo en el ciclo: PLC, pasarela, gateway de mezcladores o controlador de generador.
 *
 * @param grupo   PLC, Pasarela, Mezcladores o Generador
 * @param nombre  nombre del equipo en la configuración
 * @param ip      dirección IP
 * @param leidos  medidores, canales o tablas leídos
 * @param total   los que tenía que leer
 * @param motivo  null si se leyó todo; si no, la primera falla
 * @param ms      lo que tardó la lectura
 */
public record EstadoDispositivo(String grupo, String nombre, String ip, int leidos, int total, String motivo, long ms) {

    public boolean ok() {
        return motivo == null && leidos == total;
    }
}
