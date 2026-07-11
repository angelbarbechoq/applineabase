package com.example.horometro.service;

import com.example.alarmas.model.AlarmaConfig;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaConfigRepository;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.dataacquisition.service.DatabaseInitializationService;
import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.horometro.repository.HorometroDiarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Al arrancar, reconstruye las horas de funcionamiento históricas de cada máquina
 * leyendo el PW guardado en SQLite, aplicando exactamente la misma lógica de
 * confirmación que AlarmaEvaluatorService usa en vivo (ventana de ciclos consecutivos
 * bajo el umbral para confirmar apagado, retroactivo al inicio de la racha).
 *
 * Los días ya calculados (HorometroDiario existente) se saltan — solo "hoy" se
 * recalcula siempre, porque todavía no está cerrado; fijarHorasDia es idempotente,
 * así que recalcularlo en cada arranque no duplica horas.
 *
 * Corre después de AlarmaConfigSeeder (@Order(1)) porque necesita que las reglas de
 * alarma (umbralMinimoKw, ventanaCiclos) ya existan.
 */
@Component
@Order(2)
public class HorometroBackfillRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(HorometroBackfillRunner.class);
    private static final DateTimeFormatter FECHA_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private static final double UMBRAL_MINIMO_KW_DEFAULT = 0.5;
    private static final int VENTANA_CICLOS_DEFAULT = 5;

    private final AlarmaConfigRepository configRepository;
    private final AlarmaEventoRepository eventoRepository;
    private final DatabaseInitializationService databaseInitializationService;
    private final PLCDataQueryService plcDataQueryService;
    private final HorometroService horometroService;
    private final HorometroDiarioRepository diarioRepository;

    public HorometroBackfillRunner(AlarmaConfigRepository configRepository, AlarmaEventoRepository eventoRepository,
                                    DatabaseInitializationService databaseInitializationService,
                                    PLCDataQueryService plcDataQueryService, HorometroService horometroService,
                                    HorometroDiarioRepository diarioRepository) {
        this.configRepository = configRepository;
        this.eventoRepository = eventoRepository;
        this.databaseInitializationService = databaseInitializationService;
        this.plcDataQueryService = plcDataQueryService;
        this.horometroService = horometroService;
        this.diarioRepository = diarioRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, AlarmaConfig> lineasTrackeables = new LinkedHashMap<>();
        for (AlarmaConfig config : configRepository.findAllByOrderByLineaMaquinaAsc()) {
            if (config.getTipoAlarma() == TipoAlarma.DETENCION || config.getTipoAlarma() == TipoAlarma.CICLO_COMPRESOR) {
                lineasTrackeables.put(config.getLineaMaquina(), config);
            }
        }

        List<YearMonth> meses = databaseInitializationService.listarMesesDisponibles();
        LocalDate hasta = LocalDate.now();
        LocalDate desde = meses.isEmpty() ? hasta : meses.get(0).atDay(1);

        logger.info("Backfill de horómetro: {} líneas, rango {} a {}", lineasTrackeables.size(), desde, hasta);
        for (Map.Entry<String, AlarmaConfig> entry : lineasTrackeables.entrySet()) {
            try {
                backfillLinea(entry.getKey(), entry.getValue(), desde, hasta);
            } catch (Exception e) {
                logger.error("Error en backfill de horómetro para {}: {}", entry.getKey(), e.getMessage(), e);
            }
        }
        logger.info("Backfill de horómetro completado");
    }

    private void backfillLinea(String linea, AlarmaConfig config, LocalDate desde, LocalDate hasta) {
        horometroService.obtenerOCrearTotal(linea);
        if (desde.isAfter(hasta)) {
            return;
        }

        boolean esDetencion = config.getTipoAlarma() == TipoAlarma.DETENCION;
        double umbral = config.getUmbralMinimoKw() != null ? config.getUmbralMinimoKw() : UMBRAL_MINIMO_KW_DEFAULT;
        int ventana = esDetencion
                ? (config.getVentanaCiclos() != null ? config.getVentanaCiclos() : VENTANA_CICLOS_DEFAULT)
                : 1; // CICLO_COMPRESOR no tiene ventana de confirmación: es inmediato en ambas direcciones

        List<Map<String, Object>> filas = plcDataQueryService.getHistoricoVIPByRango(linea, desde, hasta);
        Map<LocalDate, List<Muestra>> porDia = agruparPorDia(filas);

        porDia.values().stream().findFirst().flatMap(muestras -> muestras.stream().map(Muestra::fecha).min(LocalDateTime::compareTo))
                .ifPresent(primeraFecha -> horometroService.marcarFechaInicioSiNoExiste(linea, primeraFecha));

        LocalDate hoy = LocalDate.now();
        Boolean encendidaAlCerrarUltimoDiaProcesado = null;
        for (Map.Entry<LocalDate, List<Muestra>> diaEntry : porDia.entrySet()) {
            LocalDate fecha = diaEntry.getKey();
            boolean esHoy = fecha.equals(hoy);
            if (!esHoy && diarioRepository.existsByLineaMaquinaAndFecha(linea, fecha)) {
                continue; // día ya cerrado y calculado en un arranque anterior
            }
            ResultadoDia resultado = calcularHorasEncendidaEnDia(diaEntry.getValue(), umbral, ventana);
            horometroService.fijarHorasDia(linea, fecha, resultado.horas());
            encendidaAlCerrarUltimoDiaProcesado = resultado.terminoEncendida();
        }

        // DETENCION ya no genera AlarmaEvento (es advertencia del horómetro, no alarma), así que
        // el estado actual se deriva del propio cálculo batch, no de la tabla de alarmas.
        // CICLO_COMPRESOR sigue generando AlarmaEvento normalmente, sin cambios.
        boolean apagadaAhora = esDetencion
                ? Boolean.FALSE.equals(encendidaAlCerrarUltimoDiaProcesado)
                : eventoRepository.findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(linea, TipoAlarma.CICLO_COMPRESOR).isPresent();
        horometroService.inicializarEstadoEnVivo(linea, !apagadaAhora, LocalDateTime.now());
    }

    private Map<LocalDate, List<Muestra>> agruparPorDia(List<Map<String, Object>> filas) {
        Map<LocalDate, List<Muestra>> porDia = new TreeMap<>();
        for (Map<String, Object> fila : filas) {
            Object fechaRaw = fila.get("fecha");
            Object pwRaw = fila.get("PW");
            if (!(fechaRaw instanceof String fechaStr) || !(pwRaw instanceof Number)) {
                continue;
            }
            try {
                LocalDateTime fecha = LocalDateTime.parse(fechaStr, FECHA_FORMATTER);
                porDia.computeIfAbsent(fecha.toLocalDate(), k -> new ArrayList<>())
                        .add(new Muestra(fecha, ((Number) pwRaw).doubleValue()));
            } catch (DateTimeParseException ignored) {
                // fila con fecha ilegible: se descarta, no aporta al conteo
            }
        }
        return porDia;
    }

    /**
     * Replica en modo batch la lógica de AlarmaEvaluatorService.evaluarDetencion: una racha
     * de `ventana` muestras consecutivas bajo el umbral confirma apagado, retroactivo al
     * inicio de la racha; cualquier muestra por encima del umbral confirma encendido de
     * inmediato. Así un dato corrupto o un hueco de transmisión seguido de una lectura alta
     * no le resta horas al horómetro, tal como en vivo.
     */
    private ResultadoDia calcularHorasEncendidaEnDia(List<Muestra> muestras, double umbral, int ventana) {
        muestras.sort(Comparator.comparing(Muestra::fecha));

        double horas = 0.0;
        LocalDateTime inicioEncendido = null;
        LocalDateTime inicioRacha = null;
        int ciclosBajoUmbral = 0;

        for (Muestra m : muestras) {
            if (m.pw() >= umbral) {
                ciclosBajoUmbral = 0;
                inicioRacha = null;
                if (inicioEncendido == null) {
                    inicioEncendido = m.fecha();
                }
            } else {
                if (inicioRacha == null) {
                    inicioRacha = m.fecha();
                }
                ciclosBajoUmbral++;
                if (ciclosBajoUmbral >= ventana && inicioEncendido != null) {
                    horas += horasEntre(inicioEncendido, inicioRacha);
                    inicioEncendido = null;
                }
            }
        }
        if (inicioEncendido != null && !muestras.isEmpty()) {
            horas += horasEntre(inicioEncendido, muestras.get(muestras.size() - 1).fecha());
        }
        return new ResultadoDia(horas, inicioEncendido != null);
    }

    private double horasEntre(LocalDateTime desde, LocalDateTime hasta) {
        if (!hasta.isAfter(desde)) {
            return 0.0;
        }
        return Duration.between(desde, hasta).toMillis() / 3_600_000.0;
    }

    private record Muestra(LocalDateTime fecha, double pw) {
    }

    /** horas: total encendida en el día. terminoEncendida: si el día cerró sin confirmar apagado. */
    private record ResultadoDia(double horas, boolean terminoEncendida) {
    }
}
