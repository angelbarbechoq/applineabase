package com.example.mantenimiento.repository;

import com.example.mantenimiento.model.TecnicoMantenimiento;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TecnicoMantenimientoRepository extends JpaRepository<TecnicoMantenimiento, Long> {

    List<TecnicoMantenimiento> findAllByOrderByNombreAsc();

    boolean existsByCi(String ci);
}
