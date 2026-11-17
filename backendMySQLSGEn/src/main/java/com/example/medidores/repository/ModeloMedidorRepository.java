package com.example.medidores.repository;

import com.example.medidores.model.ModeloMedidor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModeloMedidorRepository extends JpaRepository<ModeloMedidor, Long> {

    Optional<ModeloMedidor> findByNombreIgnoreCase(String nombre);

    boolean existsByNombreIgnoreCase(String nombre);

    List<ModeloMedidor> findAllByOrderByNombreAsc();
}
