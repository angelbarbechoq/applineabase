package com.example.horometro.controller;

import com.example.horometro.event.HorometroUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Un solo stream SSE que difunde el horómetro de TODAS las máquinas a la vez, a
 * diferencia de KWhStreamController (que es por-máquina). HorometroView muestra un
 * dashboard con todas las líneas simultáneamente, así que abrir una conexión SSE por
 * máquina (como hace ChartsView) sería ~40 conexiones concurrentes por pestaña.
 */
@Controller
@RequestMapping("/api/horometro/stream")
public class HorometroStreamController {

    private static final Logger logger = LoggerFactory.getLogger(HorometroStreamController.class);

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @GetMapping
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(300000L); // 5 minutos timeout, igual que KWhStreamController
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        logger.info("Cliente conectado al stream de horómetro. Total clientes: {}", emitters.size());
        return emitter;
    }

    @EventListener
    public void onHorometroUpdateEvent(HorometroUpdateEvent event) {
        if (emitters.isEmpty()) {
            return;
        }
        Map<String, Object> datos = new HashMap<>();
        datos.put("maquina", event.getNombreMaquina());
        datos.put("encendida", event.isEncendida());
        datos.put("horasHoy", event.getHorasHoy());
        datos.put("horasMes", event.getHorasMes());
        datos.put("horasTotal", event.getHorasTotal());

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .id(System.currentTimeMillis() + "")
                        .name("horometroUpdate")
                        .data(datos));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }
}
