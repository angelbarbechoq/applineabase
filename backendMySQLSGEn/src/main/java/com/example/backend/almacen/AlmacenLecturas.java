package com.example.backend.almacen;

import com.example.backend.modelo.Fila;

import java.util.List;

/**
 * Dónde se guardan las filas de cada ciclo. Hoy {@link AlmacenEnMemoria} (no guarda nada, solo cuenta); cuando MySQL
 * esté listo se agrega una implementación que inserte las mismas filas y se elige con {@code colector.almacen=mysql}.
 * El resto del backend no cambia.
 */
public interface AlmacenLecturas {

    /** Nombre para mostrar en la pantalla. */
    String nombre();

    /** Guarda las filas de un ciclo. Un error no debe cortar el ciclo: se registra y se informa en el resultado. */
    ResultadoGuardado guardar(List<Fila> filas);

    /**
     * @param guardadas filas guardadas
     * @param error     null si salió bien
     */
    record ResultadoGuardado(int guardadas, String error) {
    }
}
