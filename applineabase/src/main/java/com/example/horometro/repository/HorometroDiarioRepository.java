package com.example.horometro.repository;

import com.example.horometro.model.HorometroDiario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface HorometroDiarioRepository extends JpaRepository<HorometroDiario, Long> {
    Optional<HorometroDiario> findByLineaMaquinaAndFecha(String lineaMaquina, LocalDate fecha);
    boolean existsByLineaMaquinaAndFecha(String lineaMaquina, LocalDate fecha);
    List<HorometroDiario> findByLineaMaquinaOrderByFechaDesc(String lineaMaquina);
}
