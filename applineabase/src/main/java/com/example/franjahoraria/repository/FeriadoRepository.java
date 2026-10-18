package com.example.franjahoraria.repository;

import com.example.franjahoraria.model.Feriado;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FeriadoRepository extends JpaRepository<Feriado, Long> {

    Optional<Feriado> findByFecha(LocalDate fecha);

    List<Feriado> findAllByOrderByFechaDesc();

    List<Feriado> findByFechaBetween(LocalDate desde, LocalDate hasta);
}
