package com.example.dataacquisition.controller;

import com.example.dataacquisition.event.KWhDifferenceEvent;
import com.example.dataacquisition.event.MaquinaDataUpdateEvent;
import com.example.dataacquisition.event.SensorDataUpdateEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Controller
@RequestMapping("/api/plc/stream")
public class KWhStreamController {

    private static final Logger logger = LoggerFactory.getLogger(KWhStreamController.class);

    // Map de máquina -> lista de clientes conectados (emitters). ConcurrentHashMap porque
    // computeIfAbsent se llama desde el hilo de cada request HTTP: la pestaña Temperatura, por
    // ejemplo, abre dos streams (Agua + Ambiente) casi al mismo tiempo, y un HashMap normal
    // corrompe su estructura interna ante escrituras concurrentes (ConcurrentModificationException).
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emittersByMaquina = new ConcurrentHashMap<>();

    @GetMapping("/{maquina}")
    public SseEmitter streamKWh(@PathVariable String maquina) {
        logger.info("Cliente conectado para stream de KWh: {}", maquina);

        SseEmitter emitter = new SseEmitter(300000L); // 5 minutos timeout
        emittersByMaquina.computeIfAbsent(maquina, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> {
            logger.info("Stream completado para {}", maquina);
            emittersByMaquina.get(maquina).remove(emitter);
        });

        emitter.onTimeout(() -> {
            logger.info("Stream timeout para {}", maquina);
            emittersByMaquina.get(maquina).remove(emitter);
        });

        emitter.onError(e -> {
            logger.error("Error en stream para {}: {}", maquina, e.getMessage());
            emittersByMaquina.get(maquina).remove(emitter);
        });

        return emitter;
    }

    @EventListener
    public void onKWhDifferenceEvent(KWhDifferenceEvent event) {
        String maquina = event.getNombreMaquina();
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("maquina", maquina);
        eventData.put("fecha", event.getFecha());
        eventData.put("diferencia", event.getDiferencia());
        emitirATodos(maquina, "kwhUpdate", eventData);
    }

    @EventListener
    public void onMaquinaDataUpdateEvent(MaquinaDataUpdateEvent event) {
        String maquina = event.getNombreMaquina();
        Map<String, Object> eventData = new HashMap<>(event.getDatos());
        eventData.put("maquina", maquina);
        emitirATodos(maquina, "dataUpdate", eventData);
    }

    /**
     * Sensores auxiliares (TemperaturaAgua, TemperaturaAmbiente, etc.) no pasan por el flujo
     * de KWh/VIP, pero SÍ publican este evento en cada ciclo de lectura (ver
     * PLCDataAcquisitionService). Reutiliza el mismo mapa de emitters por máquina que ya usan
     * kwhUpdate/dataUpdate, para que la pestaña Temperatura de ChartsView pueda recibir el
     * valor nuevo sin recargar el gráfico completo cada vez.
     */
    @EventListener
    public void onSensorDataUpdateEvent(SensorDataUpdateEvent event) {
        String sensor = event.getNombreSensor();
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("maquina", sensor);
        eventData.put("fecha", event.getFecha());
        eventData.put("valor", event.getValor());
        emitirATodos(sensor, "sensorUpdate", eventData);
    }

    /**
     * Envía eventData como evento SSE "eventName" a todos los clientes conectados de
     * maquinaOSensor, descartando los que fallen al enviar. Única función para este broadcast —
     * antes los tres @EventListener de arriba repetían el mismo loop cada uno por su cuenta.
     */
    private void emitirATodos(String maquinaOSensor, String eventName, Map<String, Object> eventData) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByMaquina.get(maquinaOSensor);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .id(System.currentTimeMillis() + "")
                        .name(eventName)
                        .data(eventData)
                        .build());
            } catch (IOException e) {
                logger.error("Error enviando evento SSE '{}' a {}: {}", eventName, maquinaOSensor, e.getMessage());
                emitters.remove(emitter);
            }
        }
    }
}
