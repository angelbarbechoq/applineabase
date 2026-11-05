package com.example.generador.service;

import com.example.generador.model.ConsumoMensualGenerador;
import com.example.generador.repository.ConsumoMensualGeneradorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Histórico de consumo de diésel y energía de enero a septiembre de 2026, calculado el 2026-11-05 de la planilla MF-13
 * de los operadores (MF-13.xlsx oficial hasta el 19-jun para el GenPower y MF13_Aux.xlsx para el resto), antes de que
 * existiera el Registro MF-13 en la app. Solo agrega los meses que falten (idempotente). Octubre en adelante sale del
 * Registro MF-13.
 */
@Component
public class ConsumoMensualSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConsumoMensualSeeder.class);

    private static final String NOTA_CAT = "Calculado de la planilla MF-13: contador de diésel del motor y contador de energía "
            + "del controlador (último registro de cada mes).";
    private static final String NOTA_GP = "Calculado de la planilla MF-13: nivel del tanque (4,757 gal por cm) más las cargas "
            + "anotadas, y contador de energía del controlador.";
    private static final String NOTA_KOHLER = "Sin medición de diésel ni de energía: horas de marcha del horómetro × 2,7 gal/h.";

    private final ConsumoMensualGeneradorRepository repo;

    public ConsumoMensualSeeder(ConsumoMensualGeneradorRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> existentes = new HashSet<>();
        repo.findAll().forEach(c -> existentes.add(c.getGenerador() + "|" + c.getMes()));
        List<ConsumoMensualGenerador> datos = List.of(
                m("Caterpillar", 1, 53.0, 677.0, 3.1, NOTA_CAT),
                m("Caterpillar", 2, 112.0, 1465.0, 5.1, NOTA_CAT),
                m("Caterpillar", 3, 57.0, 682.0, 3.5, NOTA_CAT),
                m("Caterpillar", 4, 212.0, 2916.0, 7.2, NOTA_CAT),
                m("Caterpillar", 5, 63.0, 740.0, 3.9, NOTA_CAT),
                m("Caterpillar", 6, 177.0, 2357.0, 7.8, NOTA_CAT),
                m("Caterpillar", 7, 12.0, 132.0, 0.2, NOTA_CAT),
                m("Caterpillar", 8, 19.0, 155.0, 1.9, NOTA_CAT),
                m("Caterpillar", 9, 1228.0, 17439.0, 36.5, NOTA_CAT),
                m("GenPower", 1, 0.0, 0.0, 0.1, NOTA_GP + " El 16-ene no arrancó."),
                m("GenPower", 2, 4.8, 0.0, 0.7, NOTA_GP + " Prueba del 5-feb y marcha del 26-feb."),
                m("GenPower", 3, 32.9, 431.0, 2.1, NOTA_GP + " Incluye 6,7 gal estimados (1 carga sin cantidad anotada, 22 al 27-mar)."),
                m("GenPower", 4, 154.6, 2265.0, 5.1, NOTA_GP),
                m("GenPower", 5, 46.1, 632.0, 1.8, NOTA_GP),
                m("GenPower", 6, 190.8, 3158.0, 6.8, NOTA_GP),
                m("GenPower", 7, 0.0, 0.0, 0.0, NOTA_GP + " Sin marchas registradas."),
                m("GenPower", 8, 140.3, 1302.0, 5.0, NOTA_GP + " El nivel baja 25 cm entre el 19-jun y el 20-ago: revisar si la "
                        + "lectura de 75 cm del 20-ago es del inicio de la marcha."),
                m("GenPower", 9, 925.4, 14998.0, 34.9, NOTA_GP + " Incluye 90,5 gal estimados (3 cargas sin cantidad anotada)."),
                m(RegistroMf13Service.KOHLER, 1, 7.83, null, 2.9, NOTA_KOHLER),
                m(RegistroMf13Service.KOHLER, 2, 5.94, null, 2.2, NOTA_KOHLER),
                m(RegistroMf13Service.KOHLER, 3, 11.88, null, 4.4, NOTA_KOHLER),
                m(RegistroMf13Service.KOHLER, 4, 5.40, null, 2.0, NOTA_KOHLER + " Funcionó hasta el 10-abr."));
        List<ConsumoMensualGenerador> nuevos = datos.stream()
                .filter(c -> !existentes.contains(c.getGenerador() + "|" + c.getMes())).toList();
        if (!nuevos.isEmpty()) {
            repo.saveAll(nuevos);
            log.info("Consumo mensual de generadores: {} meses históricos agregados", nuevos.size());
        }
    }

    private static ConsumoMensualGenerador m(String gen, int mes, double gal, Double kwh, Double horas, String nota) {
        return new ConsumoMensualGenerador(gen, LocalDate.of(2026, mes, 1), gal, kwh, horas, nota);
    }
}
