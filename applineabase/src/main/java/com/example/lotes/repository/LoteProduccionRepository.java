package com.example.lotes.repository;

import com.example.lotes.model.LoteProduccion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoteProduccionRepository extends JpaRepository<LoteProduccion, Long> {

    Optional<LoteProduccion> findFirstByLineaAndFinIsNullOrderByInicioDesc(String linea);

    List<LoteProduccion> findAllByOrderByInicioDesc();

    List<LoteProduccion> findByFinIsNull();
}
