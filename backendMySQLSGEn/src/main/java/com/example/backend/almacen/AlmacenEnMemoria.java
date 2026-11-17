package com.example.backend.almacen;

import com.example.backend.modelo.Fila;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Almacén provisorio mientras no esté MySQL: no escribe en ningún lado, solo cuenta las filas que habría guardado.
 * Las últimas filas se ven en la pantalla (las guarda {@link com.example.backend.ciclo.UltimoCiclo}).
 */
@Component
@ConditionalOnProperty(name = "colector.almacen", havingValue = "memoria", matchIfMissing = true)
public class AlmacenEnMemoria implements AlmacenLecturas {

    private final AtomicLong total = new AtomicLong();

    @Override
    public String nombre() {
        return "Memoria (sin guardar; MySQL pendiente)";
    }

    @Override
    public ResultadoGuardado guardar(List<Fila> filas) {
        total.addAndGet(filas.size());
        return new ResultadoGuardado(filas.size(), null);
    }

    public long total() {
        return total.get();
    }
}
