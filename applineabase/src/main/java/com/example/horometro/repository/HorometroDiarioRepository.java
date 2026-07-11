package com.example.horometro.repository;

import com.example.horometro.model.HorometroDiario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface HorometroDiarioRepository extends JpaRepository<HorometroDiario, Long> {
    Optional<HorometroDiario> findByLineaMaquinaAndFecha(String lineaMaquina, LocalDate fecha);
    boolean existsByLineaMaquinaAndFecha(String lineaMaquina, LocalDate fecha);
    List<HorometroDiario> findByLineaMaquinaOrderByFechaDesc(String lineaMaquina);

    /** Reconstruye el total acumulado tal como estaba al cierre de una fecha pasada. */
    @Query("SELECT COALESCE(SUM(h.horas), 0) FROM HorometroDiario h "
            + "WHERE h.lineaMaquina = :linea AND h.fecha <= :fecha")
    double sumHorasHastaFecha(@Param("linea") String linea, @Param("fecha") LocalDate fecha);
}
