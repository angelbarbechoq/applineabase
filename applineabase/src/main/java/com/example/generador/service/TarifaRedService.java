package com.example.generador.service;

import com.example.generador.model.TarifaRed;
import com.example.generador.repository.TarifaRedRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/** Tarifas de la energía de la red por mes de vigencia. */
@Service
public class TarifaRedService {

    public static final double PRECIO_MAXIMO = 10;

    private final TarifaRedRepository repository;

    public TarifaRedService(TarifaRedRepository repository) {
        this.repository = repository;
    }

    /** De la más nueva a la más vieja. */
    public List<TarifaRed> listar() {
        return repository.findAllByOrderByVigenteDesdeDesc();
    }

    /** Tarifa que rige en una fecha: la última con vigencia desde ese mes o antes. */
    public static Optional<TarifaRed> vigente(List<TarifaRed> tarifas, LocalDate fecha) {
        return tarifas.stream().filter(t -> !t.getVigenteDesde().isAfter(fecha)).findFirst();
    }

    @Transactional
    public TarifaRed guardar(YearMonth desde, double precioKwh, String observacion, String usuario) {
        if (precioKwh <= 0 || precioKwh > PRECIO_MAXIMO) {
            throw new IllegalArgumentException("La tarifa debe ser mayor que 0 y menor que " + (int) PRECIO_MAXIMO + " $/kWh");
        }
        LocalDate inicio = desde.atDay(1);
        TarifaRed t = repository.findByVigenteDesde(inicio).orElseGet(() -> new TarifaRed(inicio));
        t.setPrecioKwh(precioKwh);
        t.setObservacion(observacion == null || observacion.isBlank() ? null : observacion.trim());
        t.setUsuario(usuario);
        t.setFechaRegistro(LocalDateTime.now().withNano(0));
        return repository.save(t);
    }

    @Transactional
    public void borrar(TarifaRed t) {
        repository.deleteById(t.getId());
    }
}
