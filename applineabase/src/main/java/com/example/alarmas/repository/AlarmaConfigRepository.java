package com.example.alarmas.repository;

import com.example.alarmas.model.AlarmaConfig;
import com.example.alarmas.model.TipoAlarma;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlarmaConfigRepository extends JpaRepository<AlarmaConfig, Long> {
    Optional<AlarmaConfig> findByLineaMaquinaAndTipoAlarma(String lineaMaquina, TipoAlarma tipoAlarma);
    List<AlarmaConfig> findAllByOrderByLineaMaquinaAsc();
    boolean existsByLineaMaquinaAndTipoAlarma(String lineaMaquina, TipoAlarma tipoAlarma);
}
