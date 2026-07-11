package com.example.horometro.repository;

import com.example.horometro.model.HorometroMensual;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HorometroMensualRepository extends JpaRepository<HorometroMensual, Long> {
    Optional<HorometroMensual> findByLineaMaquinaAndAnioMes(String lineaMaquina, String anioMes);
    boolean existsByLineaMaquinaAndAnioMes(String lineaMaquina, String anioMes);
    List<HorometroMensual> findByLineaMaquinaOrderByAnioMesDesc(String lineaMaquina);
}
