package com.example.alarmas.service;

import com.example.alarmas.model.AlarmaConfig;
import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaConfigRepository;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.dataacquisition.event.MaquinaDataUpdateEvent;
import com.example.dataacquisition.event.MaquinaEstadoCambioEvent;
import com.example.dataacquisition.event.SensorDataUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Evalúa las reglas de alarma cada vez que llega un dato nuevo del pipeline de
 * adquisición, y abre/cierra filas en el historial (AlarmaEvento) según corresponda.
 *
 * Reglas:
 * - DETENCION: PW por debajo del umbral mínimo (kW) durante N ciclos consecutivos
 *   confirma que la máquina está apagada. La confirmación se reporta retroactivamente
 *   desde el primer ciclo de la racha (no desde el momento en que se confirma), para
 *   que el horómetro no pierda ni gane minutos por la ventana de confirmación.
 * - CICLO_COMPRESOR: PW por encima del umbral mínimo (encendido) de forma continua
 *   por más del máximo de minutos esperado (Sauer, CompAP).
 * - TEMPERATURA_ALTA: valor de sensor por encima del máximo (TemperaturaAgua).
 * - FACTOR_POTENCIA_BAJO: |PF| bajo el mínimo (KWhPlanta1, Trafo1, Trafo2).
 *
 * Cada confirmación/resolución de DETENCION publica un MaquinaEstadoCambioEvent,
 * que es lo que consume HorometroService para acumular horas de funcionamiento.
 */
@Service
public class AlarmaEvaluatorService {

    private static final Logger logger = LoggerFactory.getLogger(AlarmaEvaluatorService.class);

    private static final double UMBRAL_MINIMO_KW_DEFAULT = 0.5;
    private static final int VENTANA_CICLOS_DEFAULT = 5;
    private static final int MINUTOS_MAX_DEFAULT = 15;
    private static final double TEMPERATURA_MAX_DEFAULT = 13.0;
    private static final double FACTOR_POTENCIA_MIN_DEFAULT = 0.94;
    private static final DateTimeFormatter FECHA_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private final AlarmaConfigRepository configRepository;
    private final AlarmaEventoRepository eventoRepository;
    private final ApplicationEventPublisher eventPublisher;

    private final ConcurrentHashMap<String, AtomicInteger> ciclosBajoUmbral = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDateTime> inicioRachaBajoUmbral = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDateTime> inicioEncendido = new ConcurrentHashMap<>();

    public AlarmaEvaluatorService(AlarmaConfigRepository configRepository, AlarmaEventoRepository eventoRepository,
                                   ApplicationEventPublisher eventPublisher) {
        this.configRepository = configRepository;
        this.eventoRepository = eventoRepository;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onMaquinaDataUpdateEvent(MaquinaDataUpdateEvent event) {
        String linea = event.getNombreMaquina();
        Double pw = comoNumero(event.getDatos().get("PW"));
        LocalDateTime fecha = parsearFecha(event.getDatos().get("fecha"));

        if (pw != null) {
            evaluarDetencion(linea, pw, fecha);
            evaluarCicloCompresor(linea, pw, fecha);
        }

        evaluarFactorPotenciaBajo(linea, event, fecha);
    }

    private void evaluarDetencion(String linea, double pw, LocalDateTime fecha) {
        AlarmaConfig config = configRepository.findByLineaMaquinaAndTipoAlarma(linea, TipoAlarma.DETENCION).orElse(null);
        if (config == null || !config.isHabilitada()) {
            return;
        }
        double umbral = config.getUmbralMinimoKw() != null ? config.getUmbralMinimoKw() : UMBRAL_MINIMO_KW_DEFAULT;
        int ventana = config.getVentanaCiclos() != null ? config.getVentanaCiclos() : VENTANA_CICLOS_DEFAULT;

        if (pw < umbral) {
            LocalDateTime inicioRacha = inicioRachaBajoUmbral.computeIfAbsent(linea, k -> fecha);
            int ciclos = ciclosBajoUmbral.computeIfAbsent(linea, k -> new AtomicInteger()).incrementAndGet();
            if (ciclos >= ventana) {
                boolean disparada = dispararAlarma(linea, TipoAlarma.DETENCION, inicioRacha,
                        String.format("%s detenida: PW %.2f kW bajo el umbral %.2f kW durante %d ciclos", linea, pw, umbral, ciclos));
                if (disparada) {
                    eventPublisher.publishEvent(new MaquinaEstadoCambioEvent(this, linea, false, inicioRacha));
                }
            }
        } else {
            ciclosBajoUmbral.remove(linea);
            inicioRachaBajoUmbral.remove(linea);
            boolean resuelta = resolverAlarma(linea, TipoAlarma.DETENCION, fecha);
            if (resuelta) {
                eventPublisher.publishEvent(new MaquinaEstadoCambioEvent(this, linea, true, fecha));
            }
        }
    }

    private void evaluarCicloCompresor(String linea, double pw, LocalDateTime fecha) {
        AlarmaConfig config = configRepository.findByLineaMaquinaAndTipoAlarma(linea, TipoAlarma.CICLO_COMPRESOR).orElse(null);
        if (config == null || !config.isHabilitada()) {
            return;
        }
        double umbral = config.getUmbralMinimoKw() != null ? config.getUmbralMinimoKw() : UMBRAL_MINIMO_KW_DEFAULT;
        int minutosMax = config.getMinutosMaxEncendido() != null ? config.getMinutosMaxEncendido() : MINUTOS_MAX_DEFAULT;

        boolean encendido = pw >= umbral;
        boolean estabaEncendido = inicioEncendido.containsKey(linea);

        if (encendido) {
            LocalDateTime inicio = inicioEncendido.computeIfAbsent(linea, k -> fecha);
            if (!estabaEncendido) {
                // Sauer/CompAP no tienen regla DETENCION (se excluyen a propósito en el seeder
                // porque ciclan seguido); esta es su única fuente de transición para el horómetro.
                eventPublisher.publishEvent(new MaquinaEstadoCambioEvent(this, linea, true, fecha));
            }
            long minutos = Duration.between(inicio, fecha).toMinutes();
            if (minutos >= minutosMax) {
                dispararAlarma(linea, TipoAlarma.CICLO_COMPRESOR, fecha,
                        String.format("%s lleva encendido %d min sin apagarse (máximo esperado %d min)", linea, minutos, minutosMax));
            }
        } else {
            if (estabaEncendido) {
                inicioEncendido.remove(linea);
                eventPublisher.publishEvent(new MaquinaEstadoCambioEvent(this, linea, false, fecha));
            }
            resolverAlarma(linea, TipoAlarma.CICLO_COMPRESOR, fecha);
        }
    }

    private void evaluarFactorPotenciaBajo(String linea, MaquinaDataUpdateEvent event, LocalDateTime fecha) {
        AlarmaConfig config = configRepository.findByLineaMaquinaAndTipoAlarma(linea, TipoAlarma.FACTOR_POTENCIA_BAJO).orElse(null);
        if (config == null || !config.isHabilitada()) {
            return;
        }
        Object pfRaw = event.getDatos().get("PF");
        if (!(pfRaw instanceof Number)) {
            return;
        }
        double pf = Math.abs(((Number) pfRaw).doubleValue());
        double minimo = config.getFactorPotenciaMinimo() != null ? config.getFactorPotenciaMinimo() : FACTOR_POTENCIA_MIN_DEFAULT;

        if (pf < minimo) {
            dispararAlarma(linea, TipoAlarma.FACTOR_POTENCIA_BAJO, fecha,
                    String.format("%s factor de potencia %.3f por debajo del mínimo %.3f", linea, pf, minimo));
        } else {
            resolverAlarma(linea, TipoAlarma.FACTOR_POTENCIA_BAJO, fecha);
        }
    }

    @EventListener
    public void onSensorDataUpdateEvent(SensorDataUpdateEvent event) {
        String sensor = event.getNombreSensor();
        AlarmaConfig config = configRepository.findByLineaMaquinaAndTipoAlarma(sensor, TipoAlarma.TEMPERATURA_ALTA).orElse(null);
        if (config == null || !config.isHabilitada()) {
            return;
        }
        double maximo = config.getTemperaturaMaxima() != null ? config.getTemperaturaMaxima() : TEMPERATURA_MAX_DEFAULT;

        LocalDateTime ahora = LocalDateTime.now();
        if (event.getValor() > maximo) {
            dispararAlarma(sensor, TipoAlarma.TEMPERATURA_ALTA, ahora,
                    String.format("%s en %.1f°C, supera el máximo de %.1f°C", sensor, event.getValor(), maximo));
        } else {
            resolverAlarma(sensor, TipoAlarma.TEMPERATURA_ALTA, ahora);
        }
    }

    private boolean dispararAlarma(String linea, TipoAlarma tipo, LocalDateTime fechaInicio, String mensaje) {
        boolean yaActiva = eventoRepository.findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(linea, tipo).isPresent();
        if (yaActiva) {
            return false;
        }
        AlarmaEvento evento = new AlarmaEvento();
        evento.setLineaMaquina(linea);
        evento.setTipoAlarma(tipo);
        evento.setMensaje(mensaje);
        evento.setFechaInicio(fechaInicio);
        evento.setActiva(true);
        eventoRepository.save(evento);
        logger.warn("🚨 ALARMA disparada: {}", mensaje);
        return true;
    }

    private boolean resolverAlarma(String linea, TipoAlarma tipo, LocalDateTime fechaFin) {
        return eventoRepository.findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(linea, tipo)
                .map(evento -> {
                    evento.setActiva(false);
                    evento.setFechaFin(fechaFin);
                    eventoRepository.save(evento);
                    logger.info("✅ Alarma resuelta: {} ({})", linea, tipo);
                    return true;
                })
                .orElse(false);
    }

    private Double comoNumero(Object valor) {
        return valor instanceof Number ? ((Number) valor).doubleValue() : null;
    }

    private LocalDateTime parsearFecha(Object fechaRaw) {
        if (fechaRaw instanceof String fechaStr) {
            try {
                return LocalDateTime.parse(fechaStr, FECHA_FORMATTER);
            } catch (DateTimeParseException ignored) {
                // cae al valor por defecto
            }
        }
        return LocalDateTime.now();
    }
}
