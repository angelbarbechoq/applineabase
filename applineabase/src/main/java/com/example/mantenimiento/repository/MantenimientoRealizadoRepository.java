package com.example.mantenimiento.repository;

import com.example.mantenimiento.model.MantenimientoRealizado;
import com.example.mantenimiento.model.PlanMantenimiento;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MantenimientoRealizadoRepository extends JpaRepository<MantenimientoRealizado, Long> {

    Optional<MantenimientoRealizado> findFirstByPlanMantenimientoOrderByFechaRealizadoDesc(PlanMantenimiento planMantenimiento);

    List<MantenimientoRealizado> findByPlanMantenimientoOrderByFechaRealizadoDesc(PlanMantenimiento planMantenimiento);

    List<MantenimientoRealizado> findAllByOrderByFechaRealizadoDesc();
}
