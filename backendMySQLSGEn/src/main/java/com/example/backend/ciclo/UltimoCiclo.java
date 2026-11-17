package com.example.backend.ciclo;

import com.example.backend.modelo.EstadoDispositivo;
import com.example.backend.modelo.Fila;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lo que muestra la pantalla: el último ciclo (estados de los equipos y resultado del guardado) y la última fila de cada
 * tabla. Se guarda la última por tabla, y no solo las del último ciclo, porque los generadores parados dejan fila cada
 * 15 min.
 */
@Component
public class UltimoCiclo {

    /**
     * @param inicio    cuándo empezó el ciclo
     * @param ms        cuánto duró
     * @param filas     filas armadas en el ciclo
     * @param guardadas filas que aceptó el almacén
     * @param errorGuardado null si se guardó bien
     */
    public record Resumen(LocalDateTime inicio, long ms, int filas, int guardadas, String errorGuardado,
                          List<EstadoDispositivo> estados) {
    }

    private volatile Resumen resumen;
    private final Map<String, Fila> ultimaPorTabla = new ConcurrentHashMap<>();

    void registrar(Resumen r, List<Fila> filas) {
        for (Fila f : filas) {
            ultimaPorTabla.put(f.tipo().name() + "|" + f.tabla(), f);
        }
        this.resumen = r;
    }

    public Resumen resumen() {
        return resumen;
    }

    public List<Fila> ultimasFilas() {
        return ultimaPorTabla.values().stream().toList();
    }
}
