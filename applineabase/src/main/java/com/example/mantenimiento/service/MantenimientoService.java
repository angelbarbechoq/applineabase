package com.example.mantenimiento.service;

import com.example.mantenimiento.model.PlanMantenimiento;
import com.example.mantenimiento.repository.PlanMantenimientoRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MantenimientoService {

    private final PlanMantenimientoRepository planRepository;

    public MantenimientoService(PlanMantenimientoRepository planRepository) {
        this.planRepository = planRepository;
    }

    public List<PlanMantenimiento> listarPlanes() {
        return planRepository.findAllByOrderByTagAsc();
    }

    public boolean existePlan(String tag, String tarea) {
        return planRepository.existsByTagAndTarea(tag, tarea);
    }

    public PlanMantenimiento guardar(PlanMantenimiento plan) {
        return planRepository.save(plan);
    }

    public void eliminar(PlanMantenimiento plan) {
        planRepository.delete(plan);
    }
}
