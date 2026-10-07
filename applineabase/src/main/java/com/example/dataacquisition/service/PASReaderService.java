package com.example.dataacquisition.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.event.DispositivoConectividadEvent;
import com.example.dataacquisition.model.PAS600Lx;
import com.example.dataacquisition.model.PASModbusRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Service for reading data from Schneider PAS600L meters via Modbus TCP/IP.
 *
 * Handles:
 * - Loading gateway configuration (dynamic scaling for multiple gateways)
 * - Maintaining ArrayList of PAS600Lx devices (gateways)
 * - Reading Modbus data from each meter (Unit ID) within each gateway
 * - Filtering data by line using linea-id-config
 * - Storing data in device arrays
 * - Persisting to SQLite (same tables as PLCs)
 * - Publishing KWh difference events for Vaadin UI updates
 */
@Service
public class PASReaderService {

    private static final Logger logger = LoggerFactory.getLogger(PASReaderService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);
    /** Espera máxima por pedido Modbus: un medidor caído no frena más que esto a los demás. */
    private static final int TIMEOUT_MS = 3000;

    private final ArrayList<PAS600Lx> gatewayDevices;
    private final List<Map<String, Object>> lineaIdConfigCache;
    private final PASGatewayConfigService gatewayConfigService;
    private final DatabaseInitializationService databaseInitializationService;
    private final KWhDifferenceService kwhDifferenceService;
    private final PLCDataQueryService plcDataQueryService;
    private final ApplicationEventPublisher eventPublisher;

    public PASReaderService(PASGatewayConfigService gatewayConfigService,
                            ConfigLoaderService configLoaderService,
                            DatabaseInitializationService databaseInitializationService,
                            KWhDifferenceService kwhDifferenceService,
                            PLCDataQueryService plcDataQueryService,
                            ApplicationEventPublisher eventPublisher) {
        this.gatewayConfigService = gatewayConfigService;
        this.databaseInitializationService = databaseInitializationService;
        this.kwhDifferenceService = kwhDifferenceService;
        this.plcDataQueryService = plcDataQueryService;
        this.eventPublisher = eventPublisher;
        this.gatewayDevices = new ArrayList<>();
        this.lineaIdConfigCache = configLoaderService.loadLineaIDConfig();
        initializeGatewayDevices();
    }

    /**
     * Initialize gateway devices from configuration (plc-config.json["gateways"])
     */
    private void initializeGatewayDevices() {
        logger.info("Initializing PAS600L gateway devices from configuration...");

        List<Map<String, Object>> gatewayConfig = gatewayConfigService.loadGatewayConfig();

        gatewayDevices.clear();
        for (Map<String, Object> gateway : gatewayConfig) {
            String nombre = (String) gateway.get("nombre");
            String ipAddress = (String) gateway.get("ipAddress");

            PAS600Lx device = new PAS600Lx(ipAddress, nombre);
            gatewayDevices.add(device);

            logger.info("Initialized gateway device: {} ({})", nombre, ipAddress);
        }

        logger.info("Total gateway devices initialized: {}", gatewayDevices.size());
    }

    /**
     * Main read cycle: iterate over all gateways and read data
     */
    public void readPAS600L() {
        if (gatewayDevices.isEmpty()) {
            logger.debug("No gateway devices configured");
            return;
        }

        logger.info("=== STARTING PAS600L READ CYCLE ===");

        for (PAS600Lx gateway : gatewayDevices) {
            try {
                readSingleGateway(gateway);
            } catch (Exception e) {
                logger.error("Error reading gateway {}: {}", gateway.getNombrex(), e.getMessage(), e);
            }
        }

        logger.info("=== COMPLETED PAS600L READ CYCLE ===");
    }

    /**
     * Read data from a single gateway
     * - Filter lines for this gateway
     * - Connect via Modbus
     * - Read each meter (Unit ID) and store in device arrays
     * - Persist to SQLite
     */
    private void readSingleGateway(PAS600Lx gateway) {
        String gatewayName = gateway.getNombrex();
        String gatewayIP = gateway.getGatewayIP();

        logger.info("Reading gateway: {} ({})", gatewayName, gatewayIP);

        // Get lines configured for this gateway
        List<Map<String, Object>> lineasDelGateway = gatewayConfigService.getLineasForGateway(gatewayName, lineaIdConfigCache);
        logger.debug("Gateway {} has {} lines", gatewayName, lineasDelGateway.size());

        if (lineasDelGateway.isEmpty()) {
            logger.warn("No lines configured for gateway {}", gatewayName);
            return;
        }

        // Check connectivity via ping
        if (!ModbusUtil.isIPAvailable(gatewayIP)) {
            logger.warn("IP {} not available for gateway {}", gatewayIP, gatewayName);
            publicarConectividad(lineasDelGateway, false, "sin respuesta a ping");
            return;
        }

        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        boolean[] leidos = new boolean[lineasDelGateway.size()];

        // Una sola conexión por pasarela por ciclo; el Unit ID va en cada pedido.
        try (ModbusTcpConexion conexion = new ModbusTcpConexion(gatewayIP, TIMEOUT_MS)) {
            for (int i = 0; i < lineasDelGateway.size(); i++) {
                Map<String, Object> linea = lineasDelGateway.get(i);
                String nombreLinea = (String) linea.get("lineaMaquina");
                String motivoFalla = null;
                try {
                    if (!conexion.estaConectada()) {
                        conexion.conectar();
                    }
                    leidos[i] = readSingleMeter(conexion, gateway, linea, i);
                    if (!leidos[i]) {
                        motivoFalla = "modelo de medidor sin registros configurados";
                    }
                } catch (ModbusTcpConexion.ExcepcionModbus e) {
                    motivoFalla = e.getMessage();
                } catch (IOException e) {
                    motivoFalla = "error de conexión Modbus: " + e.getMessage();
                }
                if (motivoFalla != null) {
                    // Sin dato este ciclo: no se guarda nada (queda el hueco), ni ceros ni el valor anterior.
                    logger.warn("Medidor {} (Unit ID {}) en {} sin lectura: {}", nombreLinea, linea.get("id"), gatewayName, motivoFalla);
                }
                eventPublisher.publishEvent(new DispositivoConectividadEvent(this, nombreLinea, leidos[i], motivoFalla, LocalDateTime.now()));
            }
        }

        databaseInitializationService.beginBatch();
        try {
            persistGatewayData(gateway, lineasDelGateway, leidos, timestamp);
        } finally {
            databaseInitializationService.endBatch();
        }
    }

    /**
     * Lee KWh, tensiones, corrientes, kW y PF de un medidor. Los valores se cargan en el gateway
     * solo si las cinco lecturas salieron bien; si una falla, el medidor queda sin dato este ciclo.
     *
     * @return false si el modelo no tiene registros configurados
     */
    private boolean readSingleMeter(ModbusTcpConexion conexion, PAS600Lx gateway, Map<String, Object> linea, int index)
            throws IOException, ModbusTcpConexion.ExcepcionModbus {
        int unitId = ((Number) linea.get("id")).intValue();
        String modelo = (String) linea.get("modeloMedidor");

        int[] regKWh = leer(conexion, unitId, modelo, "KWh");
        int[] regV = leer(conexion, unitId, modelo, "V");
        int[] regI = leer(conexion, unitId, modelo, "I");
        int[] regKW = leer(conexion, unitId, modelo, "KW");
        int[] regPF = leer(conexion, unitId, modelo, "PF");
        if (regKWh == null || regV == null || regI == null || regKW == null || regPF == null) {
            return false;
        }

        // Tensiones del medidor: [VAB, VBC, VCA]; se guardan en el orden de los PLC (VAB, VAC, VBC).
        gateway.setKWhActx(index, flotante(regKWh, 0));
        gateway.setVABx(index, flotante(regV, 0));
        gateway.setVBCx(index, flotante(regV, 2));
        gateway.setVACx(index, flotante(regV, 4));
        gateway.setIAx(index, flotante(regI, 0));
        gateway.setIBx(index, flotante(regI, 2));
        gateway.setICx(index, flotante(regI, 4));
        gateway.setKWx(index, flotante(regKW, 0));
        gateway.setPFx(index, flotante(regPF, 0));

        logger.debug("Medidor {} (Unit ID {}) leído", linea.get("lineaMaquina"), unitId);
        return true;
    }

    private int[] leer(ModbusTcpConexion conexion, int unitId, String modelo, String variable)
            throws IOException, ModbusTcpConexion.ExcepcionModbus {
        int[] info = PASModbusRegistry.getRegisterInfo(modelo, variable);
        if (info == null) {
            return null;
        }
        return conexion.leerHolding(unitId, info[0], info[1]);
    }

    private static BigDecimal flotante(int[] registros, int desde) {
        return ModbusUtil.registroIntToBigDecimal(new int[]{registros[desde], registros[desde + 1]});
    }

    /**
     * Persist gateway data to SQLite (batch mode). Solo los medidores leídos en este ciclo.
     */
    private void persistGatewayData(PAS600Lx gateway, List<Map<String, Object>> lineasDelGateway, boolean[] leidos, String timestamp) {
        for (int i = 0; i < lineasDelGateway.size(); i++) {
            if (!leidos[i]) {
                continue;
            }
            Map<String, Object> linea = lineasDelGateway.get(i);
            String nombreTabla = (String) linea.get("lineaMaquina");

            BigDecimal kwh = gateway.getKWhActx(i);
            BigDecimal vab = gateway.getVABx(i);
            BigDecimal vac = gateway.getVACx(i);
            BigDecimal vbc = gateway.getVBCx(i);
            BigDecimal ia = gateway.getIAx(i);
            BigDecimal ib = gateway.getIBx(i);
            BigDecimal ic = gateway.getICx(i);
            BigDecimal kw = gateway.getKWx(i);
            BigDecimal pf = gateway.getPFx(i);

            // Save KWh data
            if (kwh != null && !kwh.equals(BigDecimal.ZERO)) {
                Object[] dataDiario = {timestamp, kwh};
                databaseInitializationService.guardarDatoBatch(dataDiario, nombreTabla, "DAILY");
                databaseInitializationService.guardarDatoBatch(dataDiario, nombreTabla, "MONTHLY");

                kwhDifferenceService.procesarKWh(nombreTabla, kwh, timestamp);
            }

            // Save VIP data (Voltage, Current, Power, PF)
            if (vab != null && vac != null && vbc != null && ia != null && ib != null && ic != null && kw != null && pf != null) {
                Object[] dataDiarioVIP = {timestamp, vab, vac, vbc, ia, ib, ic, kw, pf, BigDecimal.ZERO};  // Last 0 is placeholder
                databaseInitializationService.guardarDatoBatch(dataDiarioVIP, nombreTabla, "DAILY_VIP");

                Object[] dataMensualVIP = {timestamp, vab, vac, vbc, ia, ib, ic, kw, pf, BigDecimal.ZERO};
                databaseInitializationService.guardarDatoBatch(dataMensualVIP, nombreTabla, "MONTHLY_VIP");

                logger.debug("Saved VIP data for {}: VAB={}, VAC={}, VBC={}, IA={}, IB={}, IC={}, KW={}, PF={}",
                    nombreTabla, vab, vac, vbc, ia, ib, ic, kw, pf);

                // Publish updated data
                try {
                    Map<String, Object> datosVIP = plcDataQueryService.getLatestVIPDataByMaquina(nombreTabla);
                    Map<String, Object> datosKWh = plcDataQueryService.getLatestKWhDataByMaquina(nombreTabla);

                    if (!datosVIP.containsKey("error") && !datosKWh.containsKey("error")) {
                        kwhDifferenceService.publicarDatosActuales(nombreTabla, datosVIP, datosKWh);
                    }
                } catch (Exception e) {
                    logger.warn("Error publishing data for {}: {}", nombreTabla, e.getMessage());
                }
            }
        }
    }

    /** Reporta a AlarmaEvaluatorService (regla DISPOSITIVO_NO_DISPONIBLE) que ninguna línea de
     * este gateway pudo leerse este ciclo (ping fallido antes de intentar conectar por Modbus). */
    private void publicarConectividad(List<Map<String, Object>> lineas, boolean conectado, String motivo) {
        LocalDateTime ahora = LocalDateTime.now();
        for (Map<String, Object> linea : lineas) {
            String nombreLinea = (String) linea.get("lineaMaquina");
            eventPublisher.publishEvent(new DispositivoConectividadEvent(this, nombreLinea, conectado, motivo, ahora));
        }
    }
}
