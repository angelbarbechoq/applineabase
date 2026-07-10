package com.example.dataacquisition.service;

import com.example.dataacquisition.model.PAS600Lx;
import com.example.dataacquisition.model.PASModbusRegistry;
import de.re.easymodbus.exceptions.ModbusException;
import de.re.easymodbus.modbusclient.ModbusClient;
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
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private final ArrayList<PAS600Lx> gatewayDevices;
    private final List<Map<String, Object>> lineaIdConfigCache;
    private final PASGatewayConfigService gatewayConfigService;
    private final DatabaseInitializationService databaseInitializationService;
    private final KWhDifferenceService kwhDifferenceService;
    private final PLCDataQueryService plcDataQueryService;

    public PASReaderService(PASGatewayConfigService gatewayConfigService,
                            ConfigLoaderService configLoaderService,
                            DatabaseInitializationService databaseInitializationService,
                            KWhDifferenceService kwhDifferenceService,
                            PLCDataQueryService plcDataQueryService) {
        this.gatewayConfigService = gatewayConfigService;
        this.databaseInitializationService = databaseInitializationService;
        this.kwhDifferenceService = kwhDifferenceService;
        this.plcDataQueryService = plcDataQueryService;
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
        if (!isIPAvailable(gatewayIP)) {
            logger.warn("IP {} not available for gateway {}", gatewayIP, gatewayName);
            return;
        }

        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        databaseInitializationService.beginBatch();

        try {
            // Clear previous data for this gateway's meters
            for (Map<String, Object> linea : lineasDelGateway) {
                int lineIndex = lineasDelGateway.indexOf(linea);
                gateway.setKWhActx(lineIndex, BigDecimal.ZERO);
                gateway.setVABx(lineIndex, BigDecimal.ZERO);
                gateway.setVACx(lineIndex, BigDecimal.ZERO);
                gateway.setVBCx(lineIndex, BigDecimal.ZERO);
                gateway.setIAx(lineIndex, BigDecimal.ZERO);
                gateway.setIBx(lineIndex, BigDecimal.ZERO);
                gateway.setICx(lineIndex, BigDecimal.ZERO);
                gateway.setKWx(lineIndex, BigDecimal.ZERO);
                gateway.setPFx(lineIndex, BigDecimal.ZERO);
            }

            // Read each meter
            for (int i = 0; i < lineasDelGateway.size(); i++) {
                try {
                    readSingleMeter(gateway, lineasDelGateway.get(i), i, gatewayIP);
                } catch (Exception e) {
                    logger.error("Error reading meter {} on gateway {}: {}", i, gatewayName, e.getMessage());
                }
            }

            // Persist data to SQLite
            persistGatewayData(gateway, lineasDelGateway, timestamp);

        } finally {
            databaseInitializationService.endBatch();
        }
    }

    /**
     * Read data from a single meter via Modbus
     *
     * @param gateway Gateway device
     * @param linea Line configuration entry
     * @param index Index within this gateway's meter array
     * @param gatewayIP Gateway IP address
     */
    private void readSingleMeter(PAS600Lx gateway, Map<String, Object> linea, int index, String gatewayIP) {
        Integer deviceId = ((Number) linea.get("id")).intValue();  // Unit ID
        String modelo = (String) linea.get("modeloMedidor");

        logger.debug("Reading meter {} (Unit ID {}) model {}", linea.get("lineaMaquina"), deviceId, modelo);

        ModbusClient modbusClient = new ModbusClient();
        modbusClient.setipAddress(gatewayIP);
        modbusClient.setUnitIdentifier(deviceId.byteValue());
        modbusClient.setConnectionTimeout(5000);

        try {
            modbusClient.Connect();

            // Read KWh
            readAndStoreKWh(modbusClient, gateway, modelo, index);

            // Read Voltage, Current, Power, Power Factor
            readAndStoreVoltages(modbusClient, gateway, modelo, index);
            readAndStoreCurrents(modbusClient, gateway, modelo, index);
            readAndStorePower(modbusClient, gateway, modelo, index);
            readAndStorePowerFactor(modbusClient, gateway, modelo, index);

            logger.debug("Successfully read meter {} (Unit ID {})", linea.get("lineaMaquina"), deviceId);

        } catch (IOException | ModbusException e) {
            logger.warn("Failed to read meter {} (Unit ID {}): {}", linea.get("lineaMaquina"), deviceId, e.getMessage());
            // Do NOT update arrays on failure - keep last valid value
        } finally {
            try {
                modbusClient.Disconnect();
            } catch (IOException e) {
                logger.debug("Error disconnecting from gateway {}: {}", gatewayIP, e.getMessage());
            }
        }
    }

    /**
     * Read KWh (Energy) from meter
     */
    private void readAndStoreKWh(ModbusClient client, PAS600Lx gateway, String modelo, int index)
            throws IOException, ModbusException {
        int[] registerInfo = PASModbusRegistry.getRegisterInfo(modelo, "KWh");
        if (registerInfo == null) return;

        int[] kwhRegisters = client.ReadHoldingRegisters(registerInfo[0], registerInfo[1]);
        if (kwhRegisters != null) {
            BigDecimal kwh = registroIntToBigDecimal(kwhRegisters);
            if (kwh != null && !kwh.equals(BigDecimal.ZERO)) {
                gateway.setKWhActx(index, kwh);
            }
        }
    }

    /**
     * Read Voltages (VAB, VBC, VAC) and reorganize to PLCs order (VAB, VAC, VBC)
     */
    private void readAndStoreVoltages(ModbusClient client, PAS600Lx gateway, String modelo, int index)
            throws IOException, ModbusException {
        int[] registerInfo = PASModbusRegistry.getRegisterInfo(modelo, "V");
        if (registerInfo == null) return;

        int[] voltageRegisters = client.ReadHoldingRegisters(registerInfo[0], registerInfo[1]);
        if (voltageRegisters != null && voltageRegisters.length >= 6) {
            // PAS600L returns: [VAB_hi, VAB_lo, VBC_hi, VBC_lo, VAC_hi, VAC_lo]
            BigDecimal vab = registroIntToBigDecimal(new int[]{voltageRegisters[0], voltageRegisters[1]});
            BigDecimal vbc = registroIntToBigDecimal(new int[]{voltageRegisters[2], voltageRegisters[3]});
            BigDecimal vac = registroIntToBigDecimal(new int[]{voltageRegisters[4], voltageRegisters[5]});

            if (vab != null) gateway.setVABx(index, vab);
            if (vac != null) gateway.setVACx(index, vac);  // Reorganize to PLC order
            if (vbc != null) gateway.setVBCx(index, vbc);
        }
    }

    /**
     * Read Currents (IA, IB, IC)
     */
    private void readAndStoreCurrents(ModbusClient client, PAS600Lx gateway, String modelo, int index)
            throws IOException, ModbusException {
        int[] registerInfo = PASModbusRegistry.getRegisterInfo(modelo, "I");
        if (registerInfo == null) return;

        int[] currentRegisters = client.ReadHoldingRegisters(registerInfo[0], registerInfo[1]);
        if (currentRegisters != null && currentRegisters.length >= 6) {
            // Returns: [IA_hi, IA_lo, IB_hi, IB_lo, IC_hi, IC_lo]
            BigDecimal ia = registroIntToBigDecimal(new int[]{currentRegisters[0], currentRegisters[1]});
            BigDecimal ib = registroIntToBigDecimal(new int[]{currentRegisters[2], currentRegisters[3]});
            BigDecimal ic = registroIntToBigDecimal(new int[]{currentRegisters[4], currentRegisters[5]});

            if (ia != null) gateway.setIAx(index, ia);
            if (ib != null) gateway.setIBx(index, ib);
            if (ic != null) gateway.setICx(index, ic);
        }
    }

    /**
     * Read Power (KW)
     */
    private void readAndStorePower(ModbusClient client, PAS600Lx gateway, String modelo, int index)
            throws IOException, ModbusException {
        int[] registerInfo = PASModbusRegistry.getRegisterInfo(modelo, "KW");
        if (registerInfo == null) return;

        int[] powerRegisters = client.ReadHoldingRegisters(registerInfo[0], registerInfo[1]);
        if (powerRegisters != null && powerRegisters.length >= 2) {
            BigDecimal kw = registroIntToBigDecimal(new int[]{powerRegisters[0], powerRegisters[1]});
            if (kw != null) gateway.setKWx(index, kw);
        }
    }

    /**
     * Read Power Factor (PF)
     */
    private void readAndStorePowerFactor(ModbusClient client, PAS600Lx gateway, String modelo, int index)
            throws IOException, ModbusException {
        int[] registerInfo = PASModbusRegistry.getRegisterInfo(modelo, "PF");
        if (registerInfo == null) return;

        int[] pfRegisters = client.ReadHoldingRegisters(registerInfo[0], registerInfo[1]);
        if (pfRegisters != null && pfRegisters.length >= 2) {
            BigDecimal pf = registroIntToBigDecimal(new int[]{pfRegisters[0], pfRegisters[1]});
            if (pf != null) gateway.setPFx(index, pf);
        }
    }

    /**
     * Persist gateway data to SQLite (batch mode)
     */
    private void persistGatewayData(PAS600Lx gateway, List<Map<String, Object>> lineasDelGateway, String timestamp) {
        for (int i = 0; i < lineasDelGateway.size(); i++) {
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

    /**
     * Check if IP is reachable via ping
     */
    private boolean isIPAvailable(String ipAddress) {
        try {
            return java.net.InetAddress.getByName(ipAddress).isReachable(3000);
        } catch (Exception e) {
            logger.debug("Ping failed for IP {}: {}", ipAddress, e.getMessage());
            return false;
        }
    }

    /**
     * Convert two int registers (Modbus) to BigDecimal (IEEE754 float)
     */
    private BigDecimal registroIntToBigDecimal(int[] dataMbTcIp) {
        if (dataMbTcIp == null || dataMbTcIp.length != 2) {
            return BigDecimal.ZERO;
        }

        int combinado = (dataMbTcIp[0] << 16) | (dataMbTcIp[1] & 0xFFFF);
        float floatValue = Float.intBitsToFloat(combinado);

        if (Float.isNaN(floatValue)) {
            return BigDecimal.ZERO;
        }

        return new BigDecimal(floatValue);
    }
}
