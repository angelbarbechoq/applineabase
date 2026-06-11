package com.example.dataacquisition.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled task that runs every second to acquire data from PLCs and PAS600L.
 *
 * Execution logic:
 * - Every second: increment counter
 * - Every 60 seconds: read all PLCs and PAS600L, then persist data to SQLite
 */
@Component
public class DataAcquisitionTask {

    private static final Logger logger = LoggerFactory.getLogger(DataAcquisitionTask.class);
    private static final int CYCLE_INTERVAL = 60; // Read PLCs every 6 seconds

    private volatile int secondCounter = 59;

    private final PLCDataAcquisitionService plcDataAcquisitionService;
    private final PASReaderService pasReaderService;
    private final DatabaseInitializationService databaseInitializationService;

    public DataAcquisitionTask(PLCDataAcquisitionService plcDataAcquisitionService,
                               PASReaderService pasReaderService,
                               DatabaseInitializationService databaseInitializationService) {
        this.plcDataAcquisitionService = plcDataAcquisitionService;
        this.pasReaderService = pasReaderService;
        this.databaseInitializationService = databaseInitializationService;
        logger.info("DataAcquisitionTask initialized - reading cycle every {} seconds", CYCLE_INTERVAL);
    }

    public int getSecondCounter() {
        return secondCounter;
    }

    @Scheduled(fixedRate = 1000) // Executes every 1 second (1000ms)
    public void acquire() {
        try {
            secondCounter++;

            // Every 60 seconds, read PLCs and PAS600L
            if (secondCounter >= CYCLE_INTERVAL) {
                logger.info(">>> READING CYCLE (every {} seconds) <<<", CYCLE_INTERVAL);

                // Verify and create databases if needed
                databaseInitializationService.verifyAndCreate();

                // Read all PLCs with lines filtering
                plcDataAcquisitionService.readAllPLCs();

                // Read PAS600L
                pasReaderService.readPAS600L();

                // Reset counter
                secondCounter = 0;
                //logger.info(">>> END READING CYCLE - secondCounter resetted to 0 <<<");
            } else {
                //logger.debug("⏱️ secondCounter: {}/{}", secondCounter, CYCLE_INTERVAL);
            }
        } catch (Exception e) {
            logger.error("Error during data acquisition cycle", e);
        }
    }
}
