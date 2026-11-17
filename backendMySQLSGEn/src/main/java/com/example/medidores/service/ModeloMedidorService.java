package com.example.medidores.service;

import com.example.medidores.model.ModeloMedidor;
import com.example.medidores.repository.ModeloMedidorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Catálogo de modelos de medidor, SOLO LECTURA: lo lee de la base H2 de la app principal (Configuración > Modelos de
 * medidor), que es donde se edita. Mismo armado de {@link DefinicionModelo} que en applineabase.
 */
@Service
public class ModeloMedidorService {

    private final ModeloMedidorRepository repository;

    public ModeloMedidorService(ModeloMedidorRepository repository) {
        this.repository = repository;
    }

    /** Definiciones de todos los modelos, por nombre en mayúsculas (se arma una vez por ciclo). */
    @Transactional(readOnly = true)
    public Map<String, DefinicionModelo> definiciones() {
        Map<String, DefinicionModelo> mapa = new HashMap<>();
        for (ModeloMedidor m : repository.findAll()) {
            mapa.put(m.getNombre().toUpperCase(), DefinicionModelo.de(m));
        }
        return mapa;
    }
}
