package com.example.generador.repository;

import com.example.generador.model.RegistroMf13;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface RegistroMf13Repository extends JpaRepository<RegistroMf13, Long> {

    List<RegistroMf13> findByGeneradorOrderByFechaHoraAscIdAsc(String generador);

    List<RegistroMf13> findByGeneradorAndFechaHoraBetweenOrderByFechaHoraAscIdAsc(String generador, LocalDateTime desde, LocalDateTime hasta);
}
