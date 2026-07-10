package com.example.alarmas.service;

import com.example.alarmas.model.AlarmaConfig;
import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaConfigRepository;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.dataacquisition.event.KWhDifferenceEvent;
import com.example.dataacquisition.event.MaquinaDataUpdateEvent;
import com.example.dataacquisition.event.SensorDataUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Evalúa las reglas de alarma cada vez que llega un dato nuevo del pipeline de
 * adquisición, y abre/cierra filas en el historial (AlarmaEvento) según corresponda.
 *
 * Reglas:
 * - DETENCION: |diferencia KWh| bajo epsilon durante N ciclos consecutivos.
 * - CICLO_COMPRESOR: diferencia KWh por encima de epsilon (encendido) de forma
 *   continua por más del máximo de minutos esperado (Sauer, CompAP).
 * - TEMPERATURA_ALTA: valor de sensor por encima del máximo (TemperaturaAgua).
 * - FACTOR_POTENCIA_BAJO: |PF| bajo el mínimo (KWhPlanta1, Trafo1, Trafo2).
 */
@Service
public class AlarmaEvaluatorService {

    private static final Logger logger = LoggerFactory.getLogger(AlarmaEvaluatorService.class);

    private static final double EPSILON_DEFAULT = 0.01;
    private static final int VENTANA_CICLOS_DEFAULT = 5;
    private static final int MINUTOS_MAX_DEFAULT = 15;
    private static final double TEMPERATURA_MAX_DEFAULT = 13.0;
    private static final double FACTOR_POTENCIA_MIN_DEFAULT = 0.94;

    private final AlarmaConfigRepository configRepository;
    private final AlarmaEventoRepository eventoRepository;

    private final ConcurrentHashMap<String, AtomicInteger> ciclosBajoEpsilon = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDateTime> inicioEncendido = new ConcurrentHashMap<>();

    public AlarmaEvaluatorService(AlarmaConfigRepository configRepository, AlarmaEventoRepository eventoRepository) {
        this.configRepository = configRepository;
        this.eventoRepository = eventoRepository;
    }

    @EventListener
    public void onKWhDifferenceEvent(KWhDifferenceEvent event) {
        String linea = event.getNombreMaquina();
        double diferencia = event.getDiferencia();

        evaluarDetencion(linea, diferencia);
        evaluarCicloCompresor(linea, diferencia);
    }

    private void evaluarDetencion(String linea, double diferencia) {
        AlarmaConfig config = configRepository.findByLineaMaquinaAndTipoAlarma(linea, TipoAlarma.DETENCION).orElse(null);
        if (config == null || !config.isHabilitada()) {
            return;
        }
        double epsilon = config.getEpsilonKwh() != null ? config.getEpsilonKwh() : EPSILON_DEFAULT;
        int ventana = config.getVentanaCiclos() != null ? config.getVentanaCiclos() : VENTANA_CICLOS_DEFAULT;

        if (Math.abs(diferencia) < epsilon) {
            int ciclos = ciclosBajoEpsilon.computeIfAbsent(linea, k -> new AtomicInteger()).incrementAndGet();
            if (ciclos >= ventana) {
                dispararAlarma(linea, TipoAlarma.DETENCION,
                        String.format("%s detenida: consumo prácticamente sin variación (~0 kWh) durante %d ciclos", linea, ciclos));
            }
        } else {
            ciclosBajoEpsilon.remove(linea);
            resolverAlarma(linea, TipoAlarma.DETENCION);
        }
    }

    private void evaluarCicloCompresor(String linea, double diferencia) {
        AlarmaConfig config = configRepository.findByLineaMaquinaAndTipoAlarma(linea, TipoAlarma.CICLO_COMPRESOR).orElse(null);
        if (config == null || !config.isHabilitada()) {
            return;
        }
        double epsilon = config.getEpsilonKwh() != null ? config.getEpsilonKwh() : EPSILON_DEFAULT;
        int minutosMax = config.getMinutosMaxEncendido() != null ? config.getMinutosMaxEncendido() : MINUTOS_MAX_DEFAULT;

        boolean encendido = Math.abs(diferencia) >= epsilon;
        if (encendido) {
            LocalDateTime inicio = inicioEncendido.computeIfAbsent(linea, k -> LocalDateTime.now());
            long minutos = Duration.between(inicio, LocalDateTime.now()).toMinutes();
            if (minutos >= minutosMax) {
                dispararAlarma(linea, TipoAlarma.CICLO_COMPRESOR,
                        String.format("%s lleva encendido %d min sin apagarse (máximo esperado %d min)", linea, minutos, minutosMax));
            }
        } else {
            inicioEncendido.remove(linea);
            resolverAlarma(linea, TipoAlarma.CICLO_COMPRESOR);
        }
    }

    @EventListener
    public void onMaquinaDataUpdateEvent(MaquinaDataUpdateEvent event) {
        String linea = event.getNombreMaquina();
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
            dispararAlarma(linea, TipoAlarma.FACTOR_POTENCIA_BAJO,
                    String.format("%s factor de potencia %.3f por debajo del mínimo %.3f", linea, pf, minimo));
        } else {
            resolverAlarma(linea, TipoAlarma.FACTOR_POTENCIA_BAJO);
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

        if (event.getValor() > maximo) {
            dispararAlarma(sensor, TipoAlarma.TEMPERATURA_ALTA,
                    String.format("%s en %.1f°C, supera el máximo de %.1f°C", sensor, event.getValor(), maximo));
        } else {
            resolverAlarma(sensor, TipoAlarma.TEMPERATURA_ALTA);
        }
    }

    private void dispararAlarma(String linea, TipoAlarma tipo, String mensaje) {
        boolean yaActiva = eventoRepository.findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(linea, tipo).isPresent();
        if (yaActiva) {
            return;
        }
        AlarmaEvento evento = new AlarmaEvento();
        evento.setLineaMaquina(linea);
        evento.setTipoAlarma(tipo);
        evento.setMensaje(mensaje);
        evento.setFechaInicio(LocalDateTime.now());
        evento.setActiva(true);
        eventoRepository.save(evento);
        logger.warn("🚨 ALARMA disparada: {}", mensaje);
    }

    private void resolverAlarma(String linea, TipoAlarma tipo) {
        eventoRepository.findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(linea, tipo).ifPresent(evento -> {
            evento.setActiva(false);
            evento.setFechaFin(LocalDateTime.now());
            eventoRepository.save(evento);
            logger.info("✅ Alarma resuelta: {} ({})", linea, tipo);
        });
    }
}
