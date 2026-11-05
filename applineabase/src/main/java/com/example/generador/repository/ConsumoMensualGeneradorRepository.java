package com.example.generador.repository;

import com.example.generador.model.ConsumoMensualGenerador;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ConsumoMensualGeneradorRepository extends JpaRepository<ConsumoMensualGenerador, Long> {

    List<ConsumoMensualGenerador> findByMesBetweenOrderByMesAsc(LocalDate desde, LocalDate hasta);
}
