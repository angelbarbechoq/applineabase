package com.example.backend.modelo;

import com.example.dataacquisition.RutaArchivosEnergia;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Una fila lista para guardar, armada igual que la que la app principal inserta en SQLite: la tabla (nombre de la
 * máquina, mezclador o generador), la fecha del ciclo y las columnas con sus valores en el mismo orden y unidades.
 * Un valor null es un dato que no se pudo leer (nunca se reemplaza por 0).
 *
 * @param tipo    tipo de tabla (decide la tabla de MySQL)
 * @param tabla   nombre de la tabla en SQLite (línea/máquina, canal de mezclador o generador)
 * @param fecha   marca de tiempo del ciclo
 * @param columnas columnas y valores, sin la fecha
 */
public record Fila(TipoTabla tipo, String tabla, LocalDateTime fecha, Map<String, Object> columnas) {

    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);

    public Fila {
        columnas = Collections.unmodifiableMap(new LinkedHashMap<>(columnas));
    }

    /** La fecha con el formato de la columna "fecha" de SQLite (dd-MM-yyyy HH:mm:ss). */
    public String fechaTexto() {
        return fecha.format(FORMATO);
    }

    public String archivoSqlite() {
        return tipo.archivoSqlite(fecha);
    }
}
