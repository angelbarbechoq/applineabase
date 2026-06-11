package com.example.dataacquisition.service;

import com.example.dataacquisition.event.KWhDifferenceEvent;
import com.example.dataacquisition.event.MaquinaDataUpdateEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
public class KWhDifferenceService {

    private static final Logger logger = LoggerFactory.getLogger(KWhDifferenceService.class);

    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, BigDecimal> ultimoKWhPorMaquina = new HashMap<>();

    public KWhDifferenceService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void procesarKWh(String nombreMaquina, BigDecimal kwhActual, String fecha) {
        BigDecimal kwhAnterior = ultimoKWhPorMaquina.get(nombreMaquina);

        if (kwhAnterior != null) {
            double diferencia = kwhActual.subtract(kwhAnterior).doubleValue();

            logger.info("✅ KWh DIFERENCIA {} - Anterior: {} | Actual: {} | Diferencia: {} | Fecha: {}",
                nombreMaquina, kwhAnterior, kwhActual, diferencia, fecha);

            eventPublisher.publishEvent(new KWhDifferenceEvent(this, nombreMaquina, diferencia, fecha));
            logger.info("📤 Evento publicado para: {}", nombreMaquina);
        } else {
            logger.info("⏳ KWh {} - Primer dato (esperando próximo ciclo): {}", nombreMaquina, kwhActual);
        }

        ultimoKWhPorMaquina.put(nombreMaquina, kwhActual);
    }

    public void publicarDatosActuales(String nombreMaquina, Map<String, Object> datosVIP, Map<String, Object> datosKWh) {
        Map<String, Object> datosCompletos = new HashMap<>();

        datosCompletos.put("fecha", datosVIP.get("fecha"));
        datosCompletos.put("KWh", datosKWh.get("kwh"));
        datosCompletos.put("VAB", datosVIP.get("VAB"));
        datosCompletos.put("VAC", datosVIP.get("VAC"));
        datosCompletos.put("VBC", datosVIP.get("VBC"));
        datosCompletos.put("IA", datosVIP.get("IA"));
        datosCompletos.put("IB", datosVIP.get("IB"));
        datosCompletos.put("IC", datosVIP.get("IC"));
        datosCompletos.put("PW", datosVIP.get("PW"));
        datosCompletos.put("PF", datosVIP.get("PF"));

        logger.info("📢 Publicando datos actualizados para {}: {}", nombreMaquina, datosCompletos);
        eventPublisher.publishEvent(new MaquinaDataUpdateEvent(this, nombreMaquina, datosCompletos));
    }
}
