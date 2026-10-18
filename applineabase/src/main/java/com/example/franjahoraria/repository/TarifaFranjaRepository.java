package com.example.franjahoraria.repository;

import com.example.franjahoraria.model.TarifaFranja;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TarifaFranjaRepository extends JpaRepository<TarifaFranja, Long> {

    Optional<TarifaFranja> findByMes(LocalDate mes);

    List<TarifaFranja> findAllByOrderByMesDesc();
}
