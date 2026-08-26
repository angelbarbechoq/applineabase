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
import java.util.stream.Collectors;

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
    private static final String MEZCLADORES_CONFIG_FILE = "mezcladores-config.json";
    private static final String EXTRUSION_TAG_CONFIG_FILE = "extrusion-tag-config.json";

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
        sembrarSiNoExiste(MEZCLADORES_CONFIG_FILE);
        sembrarSiNoExiste(EXTRUSION_TAG_CONFIG_FILE);
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

    /**
     * Nombres de línea/máquina configurados, sin vacíos/duplicados y ordenados — para
     * poblar selects de UI que necesitan el catálogo completo de líneas. No es la lista
     * filtrada por zona de acceso del usuario logueado (para eso ver LineaAccessService).
     */
    public List<String> listarNombresLinea() {
        return listarValoresDistintos("lineaMaquina");
    }

    /**
     * Valores distintos, no vacíos y ordenados de un campo de linea-id-config.json (p. ej.
     * "lineaMaquina" o "zona") — mismo pipeline que antes se repetía a mano en cada vista que
     * necesitaba poblar un select con el catálogo completo de un campo de esa configuración.
     */
    public List<String> listarValoresDistintos(String campo) {
        return loadLineaIDConfig().stream()
                .map(l -> (String) l.get(campo))
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
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

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> loadMezcladoresConfig() {
        Object mezcladores = leerArchivo(MEZCLADORES_CONFIG_FILE).get("mezcladores");
        return mezcladores != null ? (List<Map<String, Object>>) mezcladores : List.of();
    }

    public void saveMezcladoresConfig(List<Map<String, Object>> mezcladores) {
        Map<String, Object> contenido = new LinkedHashMap<>();
        contenido.put("mezcladores", mezcladores);
        escribirArchivo(MEZCLADORES_CONFIG_FILE, contenido);
    }

    /** Esquema de columnas de las tablas de canal de mezclador (PV/SV del DTB48), distinto del
     * esquema de energía (CAMPOS_NORMAL/CAMPOS_VIP en RutaArchivosEnergia) — no reutiliza la
     * columna "kwh" porque acá el valor es temperatura, no energía. */
    public static final String[] CAMPOS_MEZCLADOR = {"PV", "SV"};

    /**
     * Nombre de tabla SQLite de un canal de mezclador (misma convención de "máquina virtual" que
     * TemperaturaAgua/Ambiente): el nombre del mezclador (idealmente el mismo que su línea de
     * energía, ej. "Mixer01") + sufijo de canal, para que quede claro a qué máquina física
     * corresponde sin mezclar su config con linea-id-config.json (que es específico de energía).
     */
    public static String nombreTablaCanalMezclador(String nombreMezclador, String canal) {
        return nombreMezclador + "_" + canal;
    }

    /** Nombres de las tablas SQLite (calentamiento + enfriamiento) de todos los mezcladores configurados. */
    public List<String> listarNombresTablasMezcladores() {
        return loadMezcladoresConfig().stream()
                .flatMap(m -> {
                    String nombre = String.valueOf(m.get("nombre"));
                    return java.util.stream.Stream.of(
                            nombreTablaCanalMezclador(nombre, "Calentamiento"),
                            nombreTablaCanalMezclador(nombre, "Enfriamiento"));
                })
                .collect(Collectors.toList());
    }

    /**
     * Catálogo de TAGs ISO 14224 de las líneas de Extrusión (taxonomía RVL-EC-CUE-P1): por
     * línea, sus equipos (nivel 6) y por cada equipo sus ítems mantenibles (nivel 8). A
     * diferencia de los demás catálogos, la raíz del archivo es un array (no un objeto con
     * una clave), por eso no reutiliza {@link #leerArchivo}, que asume un objeto en la raíz.
     * Cada entrada trae "lineaMaquina" (p. ej. "Linea01"), el mismo valor usado en
     * linea-id-config.json/HorometroTotal, para poder resolver horas de cualquiera de sus
     * equipos a partir del horómetro ya existente.
     */
    public List<Map<String, Object>> loadExtrusionTagConfig() {
        Path path = resolverPath(EXTRUSION_TAG_CONFIG_FILE);
        try (InputStream inputStream = Files.newInputStream(path)) {
            return objectMapper.readValue(inputStream, new TypeReference<List<Map<String, Object>>>() {});
        } catch (IOException e) {
            logger.error("Error leyendo {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    /**
     * TAGs de equipo (nivel 6, ej. EXT-L01-XTR) y de ítem mantenible (nivel 8, ej.
     * EXT-L01-XTR-BYT) de todas las líneas de Extrusión, aplanados en una sola lista — para
     * poblar el selector de "a qué TAG le pongo este plan de mantenimiento" sin obligar a
     * elegir un único nivel de granularidad.
     */
    @SuppressWarnings("unchecked")
    public List<String> listarTodosLosTagsExtrusion() {
        List<String> tags = new java.util.ArrayList<>();
        for (Map<String, Object> linea : loadExtrusionTagConfig()) {
            Object equiposObj = linea.get("equipos");
            if (!(equiposObj instanceof List)) {
                continue;
            }
            for (Object eqObj : (List<Object>) equiposObj) {
                if (!(eqObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> equipo = (Map<String, Object>) eqObj;
                Object tag = equipo.get("tag");
                if (tag != null) {
                    tags.add(String.valueOf(tag));
                }
                Object itemsObj = equipo.get("items");
                if (!(itemsObj instanceof List)) {
                    continue;
                }
                for (Object itObj : (List<Object>) itemsObj) {
                    if (!(itObj instanceof Map)) {
                        continue;
                    }
                    Object tagExtendido = ((Map<String, Object>) itObj).get("tagExtendido");
                    if (tagExtendido != null) {
                        tags.add(String.valueOf(tagExtendido));
                    }
                }
            }
        }
        return tags.stream().distinct().sorted().collect(Collectors.toList());
    }
}
