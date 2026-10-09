package com.example.generador.repository;

import com.example.generador.model.CostoCombustible;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CostoCombustibleRepository extends JpaRepository<CostoCombustible, Long> {

    Optional<CostoCombustible> findByGeneradorAndArranqueId(String generador, Long arranqueId);

    List<CostoCombustible> findByGenerador(String generador);

    /** El último cargado: su precio se propone para el siguiente. */
    Optional<CostoCombustible> findTopByOrderByFechaRegistroDesc();
}
