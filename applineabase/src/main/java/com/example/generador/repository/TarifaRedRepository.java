package com.example.generador.repository;

import com.example.generador.model.TarifaRed;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TarifaRedRepository extends JpaRepository<TarifaRed, Long> {

    Optional<TarifaRed> findByVigenteDesde(LocalDate vigenteDesde);

    List<TarifaRed> findAllByOrderByVigenteDesdeDesc();
}
