package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Backend de recolección de datos de SGEn: lee los equipos de la planta (PLC, pasarelas PAS600L, mezcladores y
 * generadores; solo lectura) cada minuto y prepara las filas igual que la app principal las guarda en SQLite, para
 * mandarlas a MySQL cuando esté lista. Pantalla en http://localhost:8081.
 *
 * Los paquetes copiados de applineabase (dataacquisition, medidores, calidad, generador) conservan el mismo nombre
 * para poder compararlos con la app.
 */
@SpringBootApplication
@EnableScheduling
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
