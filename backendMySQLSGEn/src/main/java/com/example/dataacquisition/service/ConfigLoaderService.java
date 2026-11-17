package com.example.dataacquisition.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Lectura de los JSON de configuración de la app principal ({@code C:\LineaBaseX\config}).
 *
 * Copia de solo lectura del ConfigLoaderService de applineabase: el backend usa la misma configuración que la app
 * (PLCs, pasarelas, líneas, mezcladores, generadores), sin sembrar ni escribir archivos. La configuración se sigue
 * editando desde la app principal.
 */
@Service
public class ConfigLoaderService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigLoaderService.class);
    private static final String PLC_CONFIG_FILE = "plc-config.json";
    private static final String LINEA_CONFIG_FILE = "linea-id-config.json";
    private static final String MEZCLADORES_CONFIG_FILE = "mezcladores-config.json";
    private static final String GENERADOR_CONFIG_FILE = "generador-config.json";
    private static final String GENERADOR_MODELOS_FILE = "generador-modelos.json";

    /** Mismas columnas que las tablas de canal de mezclador en SQLite. */
    public static final String[] CAMPOS_MEZCLADOR = {"PV", "SV"};

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.config.dir:C:\\LineaBaseX\\config}")
    private String configDir;

    @SuppressWarnings("unchecked")
    private Map<String, Object> leerArchivo(String nombreArchivo) {
        Path path = Paths.get(configDir, nombreArchivo);
        try (InputStream in = Files.newInputStream(path)) {
            return objectMapper.readValue(in, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            logger.error("Error leyendo {}: {}", path, e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lista(String archivo, String clave) {
        Object v = leerArchivo(archivo).get(clave);
        return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    public List<Map<String, Object>> loadPLCConfig() {
        return lista(PLC_CONFIG_FILE, "plcs");
    }

    public List<Map<String, Object>> loadGatewayConfig() {
        return lista(PLC_CONFIG_FILE, "gateways");
    }

    public List<Map<String, Object>> loadLineaIDConfig() {
        return lista(LINEA_CONFIG_FILE, "lineas");
    }

    public List<Map<String, Object>> loadMezcladoresConfig() {
        return lista(MEZCLADORES_CONFIG_FILE, "mezcladores");
    }

    public List<Map<String, Object>> loadGeneradoresConfig() {
        return lista(GENERADOR_CONFIG_FILE, "generadores");
    }

    public List<Map<String, Object>> loadGeneradorModelosConfig() {
        return lista(GENERADOR_MODELOS_FILE, "modelos");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> loadGeneradorMetaImportacionRed() {
        Object meta = leerArchivo(GENERADOR_CONFIG_FILE).get("metaImportacionRed");
        return meta instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** Nombre de tabla de un canal de mezclador, igual que en la app ("Mixer01_Calentamiento"). */
    public static String nombreTablaCanalMezclador(String nombreMezclador, String canal) {
        return nombreMezclador + "_" + canal;
    }
}
