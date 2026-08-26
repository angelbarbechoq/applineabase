package com.example.mantenimiento.repository;

import com.example.mantenimiento.model.PlanMantenimiento;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlanMantenimientoRepository extends JpaRepository<PlanMantenimiento, Long> {

    List<PlanMantenimiento> findByTag(String tag);

    List<PlanMantenimiento> findByHabilitadoTrue();

    List<PlanMantenimiento> findAllByOrderByTagAsc();

    boolean existsByTagAndTarea(String tag, String tarea);
}
