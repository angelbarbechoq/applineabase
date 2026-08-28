package com.example.mantenimiento.repository;

import com.example.mantenimiento.model.StockBarrilTornillo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockBarrilTornilloRepository extends JpaRepository<StockBarrilTornillo, Long> {

    List<StockBarrilTornillo> findAllByOrderByModeloAscSistemaRefrigeracionAsc();
}
