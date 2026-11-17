package com.example.backend.modelo;

import java.util.ArrayList;
import java.util.List;

/** Filas y estados que deja la lectura de un grupo de equipos en un ciclo. */
public record ResultadoLectura(List<Fila> filas, List<EstadoDispositivo> estados) {

    public static ResultadoLectura nuevo() {
        return new ResultadoLectura(new ArrayList<>(), new ArrayList<>());
    }
}
