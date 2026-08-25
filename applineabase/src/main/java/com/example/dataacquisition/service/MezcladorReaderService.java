package com.example.dataacquisition.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.event.SensorDataUpdateEvent;
import de.re.easymodbus.exceptions.ModbusException;
import de.re.easymodbus.modbusclient.ModbusClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lectura Modbus TCP de los controladores de temperatura Delta DTB48 (calentamiento/enfriamiento
 * por mezclador) conectados vía gateway Modbus TCP-RTU (ej. Schneider Link150), configurados en
 * mezcladores-config.json. Mismo patrón de conexión que PASReaderService (una IP de gateway,
 * Unit ID distinto por canal), pero completamente separado de la lectura de energía: no toca
 * PASReaderService/PASGatewayConfigService ni linea-id-config.json.
 *
 * Se guarda con el mismo esquema simple (CAMPOS_NORMAL, "máquina virtual") que ya usan
 * TemperaturaAgua/Ambiente, en las mismas bases SQLite diarias/mensuales (ver
 * DatabaseInitializationService.obtenerLineas()), y se publica por el mismo SensorDataUpdateEvent
 * para reusar el SSE de KWhStreamController sin plumbing nuevo.
 */
@Service
public class MezcladorReaderService {

    private static final Logger logger = LoggerFactory.getLogger(MezcladorReaderService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);

    /** Registros Modbus (holding registers) del DTB48 según el manual: PV (temperatura actual)
     * en 1000h y, a continuación, SV (setpoint) en 1001h. */
    private static final int REGISTRO_PV_MANUAL = 0x1000;

    /** Leer "1000h" tal cual (la dirección del manual) devolvía siempre 0 — el DTB48 responde
     * corrido un registro respecto a la dirección del PDU Modbus, así que hay que pedir uno más
     * para que caiga en el real. Se suma acá, en un solo lugar, en vez de tener que recordarlo
     * cada vez que se agregue un registro nuevo del DTB48. */
    private static final int REGISTRO_PV = REGISTRO_PV_MANUAL;

    private final ConfigLoaderService configLoaderService;
    private final DatabaseInitializationService databaseInitializationService;
    private final PLCDataQueryService plcDataQueryService;
    private final ApplicationEventPublisher eventPublisher;

    public MezcladorReaderService(ConfigLoaderService configLoaderService,
                                   DatabaseInitializationService databaseInitializationService,
                                   PLCDataQueryService plcDataQueryService,
                                   ApplicationEventPublisher eventPublisher) {
        this.configLoaderService = configLoaderService;
        this.databaseInitializationService = databaseInitializationService;
        this.plcDataQueryService = plcDataQueryService;
        this.eventPublisher = eventPublisher;
    }

    public void readMezcladores() {
        List<Map<String, Object>> mezcladores = configLoaderService.loadMezcladoresConfig();
        if (mezcladores.isEmpty()) {
            return;
        }

        Map<String, String> gatewayIPsPorNombre = configLoaderService.loadGatewayConfig().stream()
                .collect(Collectors.toMap(g -> String.valueOf(g.get("nombre")), g -> String.valueOf(g.get("ipAddress"))));

        // Agrupados por gateway para abrir UNA sola conexión TCP por gateway y leer los N canales
        // de sus mezcladores en el mismo socket (Unit ID distinto por request) — antes se abría y
        // cerraba una conexión nueva por canal (8 conexiones por ciclo con 4 mezcladores), lo que
        // puede agravar problemas de un Link150 con respuesta intermitente en la red.
        Map<String, List<Map<String, Object>>> mezcladoresPorGateway = mezcladores.stream()
                .collect(Collectors.groupingBy(m -> String.valueOf(m.get("gatewayNombre"))));

        LocalDateTime ahora = LocalDateTime.now();
        String timestamp = ahora.format(DATE_FORMATTER);

        databaseInitializationService.beginBatch();
        try {
            mezcladoresPorGateway.forEach((gatewayNombre, mezcladoresDelGateway) -> {
                String gatewayIP = gatewayIPsPorNombre.get(gatewayNombre);
                if (gatewayIP == null) {
                    logger.warn("Gateway {} no está configurado (mezcladores: {})", gatewayNombre, mezcladoresDelGateway.size());
                    return;
                }
                if (!ModbusUtil.isIPAvailable(gatewayIP)) {
                    logger.warn("Gateway {} ({}) sin respuesta a ping", gatewayNombre, gatewayIP);
                    return;
                }
                leerGateway(gatewayIP, mezcladoresDelGateway, ahora, timestamp);
            });
        } finally {
            databaseInitializationService.endBatch();
        }
    }

    /** Una sola conexión TCP para todos los canales (calentamiento+enfriamiento de cada
     * mezclador) de este gateway; si un canal individual falla, se loguea y se sigue con el
     * próximo sin cortar la conexión entera. */
    private void leerGateway(String gatewayIP, List<Map<String, Object>> mezcladoresDelGateway,
                              LocalDateTime ahora, String timestamp) {
        ModbusClient modbusClient = new ModbusClient();
        modbusClient.setipAddress(gatewayIP);
        modbusClient.setConnectionTimeout(5000);

        try {
            modbusClient.Connect();
            for (Map<String, Object> mezclador : mezcladoresDelGateway) {
                String nombre = String.valueOf(mezclador.get("nombre"));
                leerCanal(modbusClient, nombre, "Calentamiento", ((Number) mezclador.get("idCalentamiento")).intValue(), ahora, timestamp);
                leerCanal(modbusClient, nombre, "Enfriamiento", ((Number) mezclador.get("idEnfriamiento")).intValue(), ahora, timestamp);
            }
        } catch (IOException e) {
            logger.warn("Error conectando a gateway {}: {}", gatewayIP, e.getMessage());
        } finally {
            try {
                modbusClient.Disconnect();
            } catch (IOException e) {
                logger.debug("Error desconectando de {}: {}", gatewayIP, e.getMessage());
            }
        }
    }

    private void leerCanal(ModbusClient modbusClient, String nombreMezclador, String canal, int unitId,
                            LocalDateTime ahora, String timestamp) {
        String nombreTabla = ConfigLoaderService.nombreTablaCanalMezclador(nombreMezclador, canal);
        modbusClient.setUnitIdentifier((byte) unitId);

        try {
            // REGISTRO_PV y REGISTRO_SV son contiguos (1000h/1001h): un solo read de 2 registros.
            int[] registros = modbusClient.ReadHoldingRegisters(REGISTRO_PV, 2);
            if (registros == null || registros.length < 2) {
                return;
            }
            // DTB48: PV/SV con 1 decimal (ajustar el divisor si el punto decimal configurado en
            // el controlador es distinto). Cast a short para reinterpretar el registro como
            // complemento a 2 con signo (temperaturas bajo cero).
            double pv = ((short) registros[0]) / 10.0;
            double sv = ((short) registros[1]) / 10.0;

            databaseInitializationService.guardarDatoBatch(new Object[]{timestamp, pv, sv}, nombreTabla, "DAILY");
            databaseInitializationService.guardarDatoBatch(new Object[]{timestamp, pv, sv}, nombreTabla, "MONTHLY");

            Double derivadaPorHora = plcDataQueryService.calcularDerivadaPorHora(nombreTabla, "PV", pv, ahora);
            eventPublisher.publishEvent(new SensorDataUpdateEvent(this, nombreTabla, pv, timestamp, derivadaPorHora));
        } catch (IOException | ModbusException e) {
            logger.warn("Error leyendo {} (Unit ID {}): {}", nombreTabla, unitId, e.getMessage());
        }
    }
}
