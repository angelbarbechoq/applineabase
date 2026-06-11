package com.example.dataacquisition.service;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Map;

/**
 * Service for reading data from Siemens S7-200 PLCs via Modbus TCP/IP.
 */
@Service
public class PLCReaderService {

    private static final Logger logger = LoggerFactory.getLogger(PLCReaderService.class);

    public void readAllPLCs(List<Map<String, Object>> plcList) {
        logger.info("=== Reading ALL PLCs (Total: {}) ===", plcList.size());

        for (Map<String, Object> plc : plcList) {
            String nombre = (String) plc.get("nombre");
            String ipAddress = (String) plc.get("ipAddress");

            logger.info("Reading PLC: {} (IP: {})", nombre, ipAddress);
            readPLC(nombre, ipAddress);
        }

        logger.info("=== PLC Reading cycle completed ===");
    }

    private void readPLC(String nombre, String ipAddress) {
        logger.debug("Connecting to {} at {}", nombre, ipAddress);
        // TODO: Implement actual Modbus TCP/IP communication
    }
}
