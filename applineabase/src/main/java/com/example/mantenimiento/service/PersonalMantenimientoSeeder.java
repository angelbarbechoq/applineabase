package com.example.mantenimiento.service;

import com.example.mantenimiento.model.TecnicoMantenimiento;
import com.example.mantenimiento.repository.TecnicoMantenimientoRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Carga el personal de mantenimiento real (CI, nombre, especialidad) la primera vez que
 * arranca la aplicacion. No sobrescribe si ya hay datos (el catalogo es editable desde
 * "Personal de Mantenimiento" y puede haberse ampliado o corregido a mano).
 */
@Component
public class PersonalMantenimientoSeeder implements CommandLineRunner {

    private static final List<TecnicoMantenimiento> PERSONAL_DEFAULT = List.of(
            new TecnicoMantenimiento("0104234315", "AVILA VASQUEZ JUAN", "Eléctrico"),
            new TecnicoMantenimiento("0104090204", "BANEGAS PRIETO VICTOR", "Tornero"),
            new TecnicoMantenimiento("0103312195", "BARBECHO QUICHIMBO ANGEL", "Eléctrico"),
            new TecnicoMantenimiento("0104995675", "BRITO ORELLANA PAULINO", "Mecánico"),
            new TecnicoMantenimiento("0102556966", "GUACHICHULCA HEREDIA VICTOR", "Mecánico"),
            new TecnicoMantenimiento("010777065", "LARGO CRIOLLO JONNATHAN", "Mecánico"),
            new TecnicoMantenimiento("716739261", "LLANGARI PINCHAO GUSTAVO", "Mecánico"),
            new TecnicoMantenimiento("0105259618", "MONSERRATE SALDANA VINICIO", null),
            new TecnicoMantenimiento("0105746663", "PINTADO GARATE ISMAEL EDUARDO", "Eléctrico"),
            new TecnicoMantenimiento("0103916771", "PRIETO AUCAPINA CARLOS", "Eléctrico"),
            new TecnicoMantenimiento("704329119", "RAMON GUANGA CARLOS LUIS", "Eléctrico"),
            new TecnicoMantenimiento("0104792767", "RODAS DURAN ISRAEL", "Matricero"),
            new TecnicoMantenimiento("752036317", "RUIZ LINARES GENARO", "Matricero"),
            new TecnicoMantenimiento("0103549473", "SIMBAÑA QUINDE GALO", "Eléctrico"),
            new TecnicoMantenimiento("0105101588", "URDIALES BRITO LAURO", "Eléctrico"),
            new TecnicoMantenimiento("0102946167", "VISCAINO SANCHEZ JOHNNY", "Eléctrico")
    );

    private final TecnicoMantenimientoRepository repository;

    public PersonalMantenimientoSeeder(TecnicoMantenimientoRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        if (repository.count() > 0) {
            return;
        }
        repository.saveAll(PERSONAL_DEFAULT);
    }
}
