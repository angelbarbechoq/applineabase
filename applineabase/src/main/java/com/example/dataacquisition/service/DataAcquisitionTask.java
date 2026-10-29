package com.example.dataacquisition.service;

import com.example.generador.service.CapturaSegundoService;
import com.example.generador.service.GeneradorReaderService;
import com.example.generador.service.SirenaService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *
 * Se apaga con app.adquisicion.habilitada=false (lo hacen los tests): sin eso, "mvn package" en una
 * PC que llega a los PLC levantaba la app de prueba leyendo la planta real, escribía esas lecturas en
 * los SQLite reales de C:\LineaBaseX y disparaba alarmas reales que hacían fallar los tests.
 */
@Component
@ConditionalOnProperty(name = "app.adquisicion.habilitada", havingValue = "true", matchIfMissing = true)
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
    private final GeneradorReaderService generadorReaderService;
    private final CapturaSegundoService capturaSegundoService;
    private final SirenaService sirenaService;

    public DataAcquisitionTask(PLCDataAcquisitionService plcDataAcquisitionService,
                               PASReaderService pasReaderService,
                               MezcladorReaderService mezcladorReaderService,
                               DatabaseInitializationService databaseInitializationService,
                               GeneradorReaderService generadorReaderService,
                               CapturaSegundoService capturaSegundoService,
                               SirenaService sirenaService) {
        this.generadorReaderService = generadorReaderService;
        this.capturaSegundoService = capturaSegundoService;
        this.sirenaService = sirenaService;
        this.plcDataAcquisitionService = plcDataAcquisitionService;
        this.pasReaderService = pasReaderService;
        this.mezcladorReaderService = mezcladorReaderService;
        this.databaseInitializationService = databaseInitializationService;
        logger.info("DataAcquisitionTask initialized - reading cycle every {} seconds", CYCLE_INTERVAL);
    }

    public int getSecondCounter() {
        return secondCounter;
    }

    /**
     * Captura al segundo de los generadores (CapturaSegundoService): un tic aparte del de acquire, porque el ciclo de 60 s
     * corre dentro de acquire y mientras dura no hay tics. Solo lanza las lecturas en sus propios hilos y vuelve enseguida:
     * no demora el ciclo de 60 s.
     */
    @Scheduled(fixedRate = 1000)
    public void capturaAlSegundo() {
        try {
            capturaSegundoService.tic();
        } catch (Exception e) {
            logger.error("Error en la captura al segundo de los generadores", e);
        }
        try {
            sirenaService.tic(); // sirena física: el ciclo corre en su propio hilo
        } catch (Exception e) {
            logger.error("Error en la sirena", e);
        }
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

                    // Generadores (ComAp InteliGen 200), SOLO LECTURA; un error no corta el ciclo.
                    t0 = System.currentTimeMillis();
                    try {
                        generadorReaderService.leerGeneradores();
                    } catch (Exception e) {
                        logger.error("Error leyendo generadores", e);
                    }
                    long msGenerador = System.currentTimeMillis() - t0;

                    long msTotal = System.currentTimeMillis() - inicioCiclo;
                    logger.info(">>> END READING CYCLE - PLC: {} ms | PAS600L: {} ms | Mezcladores: {} ms | Generador: {} ms | Total: {} ms <<<",
                            msPLC, msPAS, msMezcladores, msGenerador, msTotal);
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
