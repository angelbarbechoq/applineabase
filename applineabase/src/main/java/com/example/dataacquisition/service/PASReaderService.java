package com.example.dataacquisition.service;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for reading data from Schneider PAS600L meter via Modbus TCP/IP.
 *
 * Modbus registers:
 * - 3019: Voltage
 * - 2999: Current
 * - 3059: Power (KW)
 * - 3083: Power Factor
 */
@Service
public class PASReaderService {

    private static final Logger logger = LoggerFactory.getLogger(PASReaderService.class);

    public void readPAS600L() {
        logger.info("Reading PAS600L meter...");
        // TODO: Implement PAS600L reading logic
    }
}
