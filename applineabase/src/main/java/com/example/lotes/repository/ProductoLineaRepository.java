package com.example.lotes.repository;

import com.example.lotes.model.ProductoLinea;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductoLineaRepository extends JpaRepository<ProductoLinea, Long> {

    List<ProductoLinea> findByLinea(String linea);
}
