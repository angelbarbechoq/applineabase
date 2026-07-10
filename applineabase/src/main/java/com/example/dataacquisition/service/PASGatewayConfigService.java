package com.example.dataacquisition.service;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Service for loading and managing PAS600L gateway configuration.
 *
 * Handles:
 * - Loading gateway configuration from plc-config.json["gateways"]
 * - Grouping lines by gateway from linea-id-config
 * - Resolving device metadata
 */
@Service
public class PASGatewayConfigService {

    private static final Logger logger = LoggerFactory.getLogger(PASGatewayConfigService.class);

    private final ConfigLoaderService configLoaderService;

    public PASGatewayConfigService(ConfigLoaderService configLoaderService) {
        this.configLoaderService = configLoaderService;
    }

    /**
     * Load all gateway configurations from plc-config.json["gateways"]
     *
     * @return List of gateway configurations: {nombre, ipAddress, descripcion}
     */
    public List<Map<String, Object>> loadGatewayConfig() {
        return configLoaderService.loadGatewayConfig();
    }

    /**
     * Group lines by gateway name from linea-id-config
     *
     * @param lineasConfig List of all lines from linea-id-config.json
     * @return Map: gatewayNombre -> List of lines under that gateway
     */
    public Map<String, List<Map<String, Object>>> groupLineasByGateway(
            List<Map<String, Object>> lineasConfig) {

        Map<String, List<Map<String, Object>>> grouped = new HashMap<>();

        for (Map<String, Object> linea : lineasConfig) {
            String nombrePLC = (String) linea.get("nombrePLC");

            // Only include lines that point to a gateway (not a traditional PLC)
            if (nombrePLC != null && nombrePLC.startsWith("GteWay")) {
                grouped.computeIfAbsent(nombrePLC, k -> new ArrayList<>()).add(linea);
            }
        }

        return grouped;
    }

    /**
     * Get lines configured for a specific gateway
     *
     * @param gatewayNombre Gateway name (e.g., "GteWay01")
     * @param lineasConfig List of all lines from linea-id-config.json
     * @return List of lines under this gateway
     */
    public List<Map<String, Object>> getLineasForGateway(String gatewayNombre,
                                                         List<Map<String, Object>> lineasConfig) {

        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> linea : lineasConfig) {
            String nombrePLC = (String) linea.get("nombrePLC");
            if (gatewayNombre.equals(nombrePLC)) {
                result.add(linea);
            }
        }

        logger.debug("Gateway {} has {} lines configured", gatewayNombre, result.size());
        return result;
    }
}
