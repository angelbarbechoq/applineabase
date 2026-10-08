package com.example.calidad.repository;

import com.example.calidad.model.TensionNominal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TensionNominalRepository extends JpaRepository<TensionNominal, Long> {

    Optional<TensionNominal> findByLineaMaquina(String lineaMaquina);
}
