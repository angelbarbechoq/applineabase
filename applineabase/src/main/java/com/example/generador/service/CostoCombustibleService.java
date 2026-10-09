package com.example.generador.service;

import com.example.generador.model.CostoCombustible;
import com.example.generador.repository.CostoCombustibleRepository;
import com.example.generador.service.GeneradorAnalisisService.PeriodoMarcha;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Combustible cargado a mano por período en marcha (galones y precio del galón). */
@Service
public class CostoCombustibleService {

    public static final double GALONES_MAXIMO = 100000, PRECIO_MAXIMO = 1000;

    private final CostoCombustibleRepository repository;

    public CostoCombustibleService(CostoCombustibleRepository repository) {
        this.repository = repository;
    }

    public static String clave(String generador, long arranqueId) {
        return generador + "|" + arranqueId;
    }

    /** Combustible de cada período (clave generador|arranque). */
    public Map<String, CostoCombustible> porPeriodo(List<PeriodoMarcha> periodos) {
        Map<String, CostoCombustible> mapa = new HashMap<>();
        periodos.stream().map(PeriodoMarcha::generador).distinct().forEach(g ->
                repository.findByGenerador(g).forEach(c -> mapa.put(clave(c.getGenerador(), c.getArranqueId()), c)));
        return mapa;
    }

    /** Precio del último cargado, para proponerlo en el siguiente. */
    public Optional<Double> ultimoPrecio() {
        return repository.findTopByOrderByFechaRegistroDesc().map(CostoCombustible::getPrecioGalon);
    }

    @Transactional
    public CostoCombustible guardar(PeriodoMarcha p, double galones, double precioGalon, String observacion, String usuario) {
        if (galones < 0 || galones > GALONES_MAXIMO) {
            throw new IllegalArgumentException("Los galones deben estar entre 0 y " + (int) GALONES_MAXIMO);
        }
        if (precioGalon <= 0 || precioGalon > PRECIO_MAXIMO) {
            throw new IllegalArgumentException("El precio del galon debe ser mayor que 0 y menor que " + (int) PRECIO_MAXIMO);
        }
        CostoCombustible c = repository.findByGeneradorAndArranqueId(p.generador(), p.arranqueId())
                .orElseGet(() -> new CostoCombustible(p.generador(), p.arranqueId(), p.inicio()));
        c.setGalones(galones);
        c.setPrecioGalon(precioGalon);
        c.setObservacion(observacion == null || observacion.isBlank() ? null : observacion.trim());
        c.setUsuario(usuario);
        c.setFechaRegistro(LocalDateTime.now().withNano(0));
        return repository.save(c);
    }

    @Transactional
    public void borrar(PeriodoMarcha p) {
        repository.findByGeneradorAndArranqueId(p.generador(), p.arranqueId()).ifPresent(repository::delete);
    }
}
