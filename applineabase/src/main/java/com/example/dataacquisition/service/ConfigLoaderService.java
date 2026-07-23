package com.example.dataacquisition.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carga y persiste la configuración de PLCs, gateways y líneas/máquinas.
 *
 * Los archivos viven fuera del .jar (en {@code app.config.dir}) porque el
 * Dockerfile empaqueta src/main/resources dentro del jar, y un jar en
 * ejecución no se puede reescribir. En el primer arranque se siembran ahí
 * copiando los JSON incluidos en resources; de ahí en adelante toda lectura
 * y escritura ocurre solo sobre el archivo externo.
 */
@Service
public class ConfigLoaderService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigLoaderService.class);
    private static final String PLC_CONFIG_FILE = "plc-config.json";
    private static final String LINEA_CONFIG_FILE = "linea-id-config.json";

    private final ObjectMapper objectMapper;

    @Value("${app.config.dir:C:\\LineaBaseX\\config}")
    private String configDir;

    public ConfigLoaderService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void sembrarArchivosExternos() {
        sembrarSiNoExiste(PLC_CONFIG_FILE);
        sembrarSiNoExiste(LINEA_CONFIG_FILE);
    }

    private void sembrarSiNoExiste(String nombreArchivo) {
        Path destino = resolverPath(nombreArchivo);
        if (Files.exists(destino)) {
            return;
        }
        try {
            Files.createDirectories(destino.getParent());
            try (InputStream semilla = getClass().getResourceAsStream("/" + nombreArchivo)) {
                if (semilla != null) {
                    Files.copy(semilla, destino);
                    logger.info("Config {} sembrado en {}", nombreArchivo, destino.toAbsolutePath());
                }
            }
        } catch (IOException e) {
            logger.error("No se pudo sembrar {} en {}", nombreArchivo, destino, e);
        }
    }

    private Path resolverPath(String nombreArchivo) {
        return Paths.get(configDir, nombreArchivo);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> leerArchivo(String nombreArchivo) {
        Path path = resolverPath(nombreArchivo);
        try (InputStream inputStream = Files.newInputStream(path)) {
            return objectMapper.readValue(inputStream, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            logger.error("Error leyendo {}: {}", path, e.getMessage());
            return Map.of();
        }
    }

    private void escribirArchivo(String nombreArchivo, Map<String, Object> contenido) {
        Path path = resolverPath(nombreArchivo);
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), contenido);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar " + nombreArchivo, e);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> loadPLCConfig() {
        Object plcs = leerArchivo(PLC_CONFIG_FILE).get("plcs");
        return plcs != null ? (List<Map<String, Object>>) plcs : List.of();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> loadLineaIDConfig() {
        Object lineas = leerArchivo(LINEA_CONFIG_FILE).get("lineas");
        return lineas != null ? (List<Map<String, Object>>) lineas : List.of();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> loadGatewayConfig() {
        Object gateways = leerArchivo(PLC_CONFIG_FILE).get("gateways");
        return gateways != null ? (List<Map<String, Object>>) gateways : List.of();
    }

    /**
     * Guarda en conjunto las listas de PLCs y gateways, ya que ambas viven en
     * el mismo archivo plc-config.json.
     */
    public void savePLCsYGateways(List<Map<String, Object>> plcs, List<Map<String, Object>> gateways) {
        Map<String, Object> contenido = new LinkedHashMap<>();
        contenido.put("plcs", plcs);
        contenido.put("gateways", gateways);
        escribirArchivo(PLC_CONFIG_FILE, contenido);
    }

    public void saveLineaIDConfig(List<Map<String, Object>> lineas) {
        Map<String, Object> contenido = new LinkedHashMap<>();
        contenido.put("lineas", lineas);
        escribirArchivo(LINEA_CONFIG_FILE, contenido);
    }
}
