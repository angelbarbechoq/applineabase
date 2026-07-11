package com.example.horometro.repository;

import com.example.horometro.model.HorometroSemanal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HorometroSemanalRepository extends JpaRepository<HorometroSemanal, Long> {
    Optional<HorometroSemanal> findByLineaMaquinaAndSemanaId(String lineaMaquina, String semanaId);
}
