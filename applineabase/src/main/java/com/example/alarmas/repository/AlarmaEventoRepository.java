package com.example.alarmas.repository;

import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.model.TipoAlarma;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AlarmaEventoRepository extends JpaRepository<AlarmaEvento, Long> {
    Optional<AlarmaEvento> findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(String lineaMaquina, TipoAlarma tipoAlarma);
    /** Todas las activas de una máquina y tipo (un generador puede tener varias alarmas a la vez). */
    List<AlarmaEvento> findByLineaMaquinaAndTipoAlarmaAndActivaTrue(String lineaMaquina, TipoAlarma tipoAlarma);
    /** Las activas de este tipo, de cualquier máquina (la sirena sigue a las alarmas de red y de potencia de cada generador). */
    List<AlarmaEvento> findByTipoAlarmaAndActivaTrue(TipoAlarma tipoAlarma);

    List<AlarmaEvento> findAllByOrderByFechaInicioDesc();
    List<AlarmaEvento> findByFechaInicioAfterOrderByFechaInicioDesc(LocalDateTime fechaInicio);
    List<AlarmaEvento> findByActivaTrueOrderByFechaInicioDesc();
    List<AlarmaEvento> findByLineaMaquinaOrderByFechaInicioDesc(String lineaMaquina);

    /** Líneas/máquinas que tienen al menos un evento registrado (activo o resuelto), para no
     * ofrecer en el filtro el catálogo completo de líneas configuradas si nunca tuvieron alarma.
     * Proyectar una sola columna distinta no se puede derivar del nombre del método (Spring Data
     * JPA siempre trae la entidad completa); hace falta esta consulta explícita. */
    @Query("SELECT DISTINCT e.lineaMaquina FROM AlarmaEvento e ORDER BY e.lineaMaquina ASC")
    List<String> findDistinctLineaMaquina();
}
