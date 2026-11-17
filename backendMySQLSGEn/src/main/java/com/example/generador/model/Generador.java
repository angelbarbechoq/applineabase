package com.example.generador.model;

/**
 * Un generador de generador-config.json.
 *
 * @param redAsociada máquina del medidor de la red con la que trabaja (ej. Trafo2): de ahí salen la
 *                    potencia/corrientes/PF de la red en pantalla y la energía tomada de la red en el
 *                    análisis.
 */
public record Generador(String nombre, String modelo, String ip, int unitId, String redAsociada, String descripcion) {

    @Override
    public String toString() {
        return nombre;
    }
}
