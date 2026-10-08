package com.example.medidores.service;

import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.medidores.model.ModeloMedidor;
import com.example.medidores.model.ParametroMedidor;
import com.example.medidores.model.RegistroModelo;
import com.example.medidores.repository.ModeloMedidorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Catálogo de modelos de medidor: alta, edición, duplicado y baja, y la vista de solo lectura
 * ({@link DefinicionModelo}) que usa el lector de pasarelas en cada ciclo.
 */
@Service
public class ModeloMedidorService {

    private final ModeloMedidorRepository repository;
    private final ConfigLoaderService configLoaderService;

    public ModeloMedidorService(ModeloMedidorRepository repository, ConfigLoaderService configLoaderService) {
        this.repository = repository;
        this.configLoaderService = configLoaderService;
    }

    public List<ModeloMedidor> listar() {
        return repository.findAllByOrderByNombreAsc();
    }

    public List<String> listarNombres() {
        return listar().stream().map(ModeloMedidor::getNombre).toList();
    }

    public ModeloMedidor buscar(Long id) {
        return repository.findById(id).orElse(null);
    }

    /** Definiciones de todos los modelos, por nombre en mayúsculas (se arma una vez por ciclo). */
    public Map<String, DefinicionModelo> definiciones() {
        Map<String, DefinicionModelo> mapa = new HashMap<>();
        for (ModeloMedidor m : repository.findAll()) {
            mapa.put(m.getNombre().toUpperCase(), DefinicionModelo.de(m));
        }
        return mapa;
    }

    /** Líneas de linea-id-config.json que usan este modelo. */
    public List<String> lineasQueLoUsan(String nombreModelo) {
        return configLoaderService.loadLineaIDConfig().stream()
                .filter(l -> nombreModelo.equalsIgnoreCase(String.valueOf(l.get("modeloMedidor"))))
                .map(l -> String.valueOf(l.get("lineaMaquina")))
                .toList();
    }

    @Transactional
    public ModeloMedidor guardarModelo(ModeloMedidor modelo) {
        validarNombre(modelo.getNombre(), modelo.getId());
        String nombreAnterior = modelo.getId() == null ? null
                : repository.findById(modelo.getId()).map(ModeloMedidor::getNombre).orElse(null);
        if (nombreAnterior != null && !nombreAnterior.equalsIgnoreCase(modelo.getNombre())
                && !lineasQueLoUsan(nombreAnterior).isEmpty()) {
            throw new IllegalArgumentException("No se puede renombrar: lo usan " + lineasQueLoUsan(nombreAnterior)
                    + ". Cambia primero el medidor de esas lineas.");
        }
        modelo.setNombre(modelo.getNombre().trim());
        return repository.save(modelo);
    }

    @Transactional
    public ModeloMedidor duplicar(ModeloMedidor origen, String nombreNuevo, String descripcion) {
        validarNombre(nombreNuevo, null);
        ModeloMedidor copia = new ModeloMedidor(nombreNuevo.trim(), descripcion);
        copia.setNumeracionManual(origen.isNumeracionManual());
        copia.setFuncionLectura(origen.getFuncionLectura());
        copia.setTensionesHistorico(origen.getTensionesHistorico());
        copia.setHistoricoPotenciaEnW(origen.isHistoricoPotenciaEnW());
        copia.setHistoricoPfEnPorcentaje(origen.isHistoricoPfEnPorcentaje());
        for (RegistroModelo r : origen.getRegistros()) {
            copia.ponerRegistro(r.copia());
        }
        return repository.save(copia);
    }

    @Transactional
    public void eliminar(ModeloMedidor modelo) {
        List<String> usan = lineasQueLoUsan(modelo.getNombre());
        if (!usan.isEmpty()) {
            throw new IllegalArgumentException("No se puede eliminar: lo usan " + usan);
        }
        repository.deleteById(modelo.getId());
    }

    @Transactional
    public ModeloMedidor guardarRegistro(Long modeloId, RegistroModelo registro) {
        ModeloMedidor modelo = repository.findById(modeloId).orElseThrow();
        if (registro.getRegistro() < 0 || registro.getRegistro() > 65535) {
            throw new IllegalArgumentException("El registro debe estar entre 0 y 65535");
        }
        if (registro.getEscala() == 0) {
            throw new IllegalArgumentException("La escala no puede ser 0");
        }
        if (!registro.getParametro().esFactorPotencia()) {
            registro.setPf4Cuadrantes(false);
        }
        modelo.ponerRegistro(registro);
        return repository.save(modelo);
    }

    @Transactional
    public ModeloMedidor quitarRegistro(Long modeloId, ParametroMedidor parametro) {
        ModeloMedidor modelo = repository.findById(modeloId).orElseThrow();
        modelo.quitarRegistro(parametro);
        return repository.save(modelo);
    }

    private void validarNombre(String nombre, Long idPropio) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("El nombre del modelo es obligatorio");
        }
        repository.findByNombreIgnoreCase(nombre.trim())
                .filter(m -> !Objects.equals(m.getId(), idPropio))
                .ifPresent(m -> {
                    throw new IllegalArgumentException("Ya existe el modelo " + m.getNombre());
                });
    }
}
