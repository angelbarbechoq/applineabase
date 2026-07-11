package com.example.horometro.repository;

import com.example.horometro.model.HorometroResetLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HorometroResetLogRepository extends JpaRepository<HorometroResetLog, Long> {
    List<HorometroResetLog> findByLineaMaquinaOrderByFechaResetDesc(String lineaMaquina);
}
