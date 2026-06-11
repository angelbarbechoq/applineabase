package com.example.dataacquisition.controller;

import com.example.dataacquisition.event.KWhDifferenceEvent;
import com.example.dataacquisition.event.MaquinaDataUpdateEvent;
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
import java.util.concurrent.CopyOnWriteArrayList;

@Controller
@RequestMapping("/api/plc/stream")
public class KWhStreamController {

    private static final Logger logger = LoggerFactory.getLogger(KWhStreamController.class);

    // Map de máquina -> lista de clientes conectados (emitters)
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emittersByMaquina = new HashMap<>();

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
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByMaquina.get(maquina);

        logger.info("🔔 Evento recibido para: {} | Clientes conectados: {}",
            maquina, (emitters == null ? 0 : emitters.size()));

        if (emitters != null && !emitters.isEmpty()) {
            logger.info("📡 Enviando evento SSE a {} clientes de {}: diferencia={}",
                emitters.size(), maquina, event.getDiferencia());

            Map<String, Object> eventData = new HashMap<>();
            eventData.put("maquina", maquina);
            eventData.put("fecha", event.getFecha());
            eventData.put("diferencia", event.getDiferencia());

            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                        .id(System.currentTimeMillis() + "")
                        .name("kwhUpdate")
                        .data(eventData)
                        .build());
                    logger.info("✅ Evento SSE enviado correctamente a cliente de {}", maquina);
                } catch (IOException e) {
                    logger.error("❌ Error enviando evento SSE a {}: {}", maquina, e.getMessage());
                    emitters.remove(emitter);
                }
            }
        } else {
            logger.warn("⚠️ No hay clientes conectados para {}", maquina);
        }
    }

    @EventListener
    public void onMaquinaDataUpdateEvent(MaquinaDataUpdateEvent event) {
        String maquina = event.getNombreMaquina();
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByMaquina.get(maquina);

        logger.info("🔔 Evento de datos recibido para: {} | Clientes conectados: {}",
            maquina, (emitters == null ? 0 : emitters.size()));

        if (emitters != null && !emitters.isEmpty()) {
            logger.info("📡 Enviando datos actualizados vía SSE a {} clientes de {}",
                emitters.size(), maquina);

            Map<String, Object> eventData = new HashMap<>(event.getDatos());
            eventData.put("maquina", maquina);

            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                        .id(System.currentTimeMillis() + "")
                        .name("dataUpdate")
                        .data(eventData)
                        .build());
                    logger.info("✅ Evento de datos SSE enviado correctamente a cliente de {}", maquina);
                } catch (IOException e) {
                    logger.error("❌ Error enviando evento SSE a {}: {}", maquina, e.getMessage());
                    emitters.remove(emitter);
                }
            }
        } else {
            logger.warn("⚠️ No hay clientes conectados para {}", maquina);
        }
    }
}
