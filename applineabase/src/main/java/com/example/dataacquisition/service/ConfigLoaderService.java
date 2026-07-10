package com.example.dataacquisition.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Service for loading configuration from JSON files.
 */
@Service
public class ConfigLoaderService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigLoaderService.class);
    private final ObjectMapper objectMapper;

    public ConfigLoaderService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> loadPLCConfig() {
        try {
            InputStream inputStream = getClass().getResourceAsStream("/plc-config.json");
            Map<String, Object> config = objectMapper.readValue(inputStream, new TypeReference<Map<String, Object>>() {});
            return (List<Map<String, Object>>) config.get("plcs");
        } catch (Exception e) {
            logger.error("Error loading PLC config", e);
            return List.of();
        }
    }

    public List<Map<String, Object>> loadLineaIDConfig() {
        try {
            InputStream inputStream = getClass().getResourceAsStream("/linea-id-config.json");
            Map<String, Object> config = objectMapper.readValue(inputStream, new TypeReference<Map<String, Object>>() {});
            return (List<Map<String, Object>>) config.get("lineas");
        } catch (Exception e) {
            logger.error("Error loading LineaID config", e);
            return List.of();
        }
    }

    public List<Map<String, Object>> loadGatewayConfig() {
        try {
            InputStream inputStream = getClass().getResourceAsStream("/plc-config.json");
            Map<String, Object> config = objectMapper.readValue(inputStream, new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> gateways = (List<Map<String, Object>>) config.get("gateways");
            return gateways != null ? gateways : List.of();
        } catch (Exception e) {
            logger.error("Error loading Gateway config", e);
            return List.of();
        }
    }
}
