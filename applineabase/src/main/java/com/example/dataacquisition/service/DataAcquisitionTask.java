package com.example.dataacquisition.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

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

    // El pool de @Scheduled tiene 2 hilos (ver Application.taskScheduler): fixedRate no espera a
    // que termine una ejecución antes de disparar la siguiente, así que si el ciclo de lectura
    // completo (PLCs + PAS600L + Mezcladores, con sus timeouts Modbus) tarda más de 60s, el
    // próximo disparo puede arrancar en el otro hilo mientras el anterior sigue corriendo. Las
    // conexiones batch de DatabaseInitializationService son campos de instancia compartidos sin
    // sincronización — dos ciclos superpuestos se pisan entre sí ("Connection is null or closed").
    // Este guard evita que un segundo ciclo arranque mientras el anterior no terminó.
    private final AtomicBoolean cicloEnCurso = new AtomicBoolean(false);

    private final PLCDataAcquisitionService plcDataAcquisitionService;
    private final PASReaderService pasReaderService;
    private final MezcladorReaderService mezcladorReaderService;
    private final DatabaseInitializationService databaseInitializationService;

    public DataAcquisitionTask(PLCDataAcquisitionService plcDataAcquisitionService,
                               PASReaderService pasReaderService,
                               MezcladorReaderService mezcladorReaderService,
                               DatabaseInitializationService databaseInitializationService) {
        this.plcDataAcquisitionService = plcDataAcquisitionService;
        this.pasReaderService = pasReaderService;
        this.mezcladorReaderService = mezcladorReaderService;
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
                secondCounter = 0;

                if (!cicloEnCurso.compareAndSet(false, true)) {
                    logger.warn("Ciclo de lectura anterior todavía en curso, se saltea este disparo");
                    return;
                }
                try {
                    logger.info(">>> READING CYCLE (every {} seconds) <<<", CYCLE_INTERVAL);
                    long inicioCiclo = System.currentTimeMillis();

                    // Verify and create databases if needed
                    databaseInitializationService.verifyAndCreate();

                    // Read all PLCs with lines filtering
                    long t0 = System.currentTimeMillis();
                    plcDataAcquisitionService.readAllPLCs();
                    long msPLC = System.currentTimeMillis() - t0;

                    // Read PAS600L
                    t0 = System.currentTimeMillis();
                    pasReaderService.readPAS600L();
                    long msPAS = System.currentTimeMillis() - t0;

                    //Read Mezcladores (DTB48, gateway separado del PAS600L)
                    t0 = System.currentTimeMillis();
                    mezcladorReaderService.readMezcladores();
                    long msMezcladores = System.currentTimeMillis() - t0;

                    long msTotal = System.currentTimeMillis() - inicioCiclo;
                    logger.info(">>> END READING CYCLE - PLC: {} ms | PAS600L: {} ms | Mezcladores: {} ms | Total: {} ms <<<",
                            msPLC, msPAS, msMezcladores, msTotal);
                } finally {
                    cicloEnCurso.set(false);
                }
            } else {
                //logger.debug("⏱️ secondCounter: {}/{}", secondCounter, CYCLE_INTERVAL);
            }
        } catch (Exception e) {
            logger.error("Error during data acquisition cycle", e);
        }
    }
}
