package com.example.alarmas.repository;

import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.model.TipoAlarma;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AlarmaEventoRepository extends JpaRepository<AlarmaEvento, Long> {
    Optional<AlarmaEvento> findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(String lineaMaquina, TipoAlarma tipoAlarma);
    List<AlarmaEvento> findAllByOrderByFechaInicioDesc();
    List<AlarmaEvento> findByFechaInicioAfterOrderByFechaInicioDesc(LocalDateTime fechaInicio);
    List<AlarmaEvento> findByActivaTrueOrderByFechaInicioDesc();
}
