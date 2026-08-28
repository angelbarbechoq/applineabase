package com.example.mantenimiento.repository;

import com.example.mantenimiento.model.MovimientoStock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MovimientoStockRepository extends JpaRepository<MovimientoStock, Long> {

    List<MovimientoStock> findAllByOrderByFechaDesc();
}
