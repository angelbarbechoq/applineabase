package com.example.dataacquisition.service;

import de.re.easymodbus.modbusclient.ModbusClient;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Escribe en cada PLC S7-1200 la lista de IDs de linea que tiene asignados en
 * linea-id-config.json, para que el PLC sepa que dispositivos debe exponer.
 * Replica el flujo del programa NetBeans (actualizaID/escriveIdPLC): el payload
 * [99, N, id1..idN] se escribe por WriteMultipleRegisters en el offset OFFSET_IDS.
 * No incluye gateways PAS600L (otro protocolo/mapeo).
 */
@Service
public class PLCIdWriterService {

    private static final Logger logger = LoggerFactory.getLogger(PLCIdWriterService.class);
    private static final int OFFSET_IDS = 400;
    private static final int MARCA_CAMBIO_IDS = 99;

    private final ConfigLoaderService configLoaderService;
    private final PASGatewayConfigService gatewayConfigService;

    public PLCIdWriterService(ConfigLoaderService configLoaderService, PASGatewayConfigService gatewayConfigService) {
        this.configLoaderService = configLoaderService;
        this.gatewayConfigService = gatewayConfigService;
    }

    /** Escribe los IDs de linea en cada PLC (PLC1/2/3, sin gateways). Devuelve el resultado por PLC. */
    public Map<String, String> escribirIdsATodosLosPLCs() {
        List<Map<String, Object>> plcConfig = configLoaderService.loadPLCConfig();
        List<Map<String, Object>> lineaIdConfig = configLoaderService.loadLineaIDConfig();

        Map<String, String> resultados = new LinkedHashMap<>();
        for (Map<String, Object> plc : plcConfig) {
            String nombre = (String) plc.get("nombre");
            String ip = (String) plc.get("ipAddress");
            resultados.put(nombre, escribirIdsEnPLC(nombre, ip, lineaIdConfig));
        }
        return resultados;
    }

    private String escribirIdsEnPLC(String nombre, String ip, List<Map<String, Object>> lineaIdConfig) {
        List<Map<String, Object>> lineasDelPLC = gatewayConfigService.getLineasForGateway(nombre, lineaIdConfig);
        if (lineasDelPLC.isEmpty()) {
            return "sin líneas asignadas, no se escribió";
        }

        int[] payload = new int[lineasDelPLC.size() + 2];
        payload[0] = MARCA_CAMBIO_IDS;
        payload[1] = lineasDelPLC.size();
        for (int i = 0; i < lineasDelPLC.size(); i++) {
            payload[i + 2] = ((Number) lineasDelPLC.get(i).get("id")).intValue();
        }

        if (!ModbusUtil.isIPAvailable(ip)) {
            logger.warn("PLC {} ({}) no responde a ping, no se escriben IDs", nombre, ip);
            return "sin respuesta a ping";
        }

        ModbusClient modbusClient = new ModbusClient();
        modbusClient.setipAddress(ip);
        try {
            modbusClient.Connect();
            modbusClient.WriteMultipleRegisters(OFFSET_IDS, payload);
            logger.info("IDs escritos en PLC {} ({}), offset {}: {} líneas", nombre, ip, OFFSET_IDS, lineasDelPLC.size());
            return "OK (" + lineasDelPLC.size() + " IDs)";
        } catch (Exception e) {
            logger.error("Error escribiendo IDs en PLC {} ({}): {}", nombre, ip, e.getMessage());
            return "error: " + e.getMessage();
        } finally {
            try {
                modbusClient.Disconnect();
            } catch (Exception e) {
                logger.warn("Error desconectando de PLC {}: {}", nombre, e.getMessage());
            }
        }
    }
}
