package com.example.dataacquisition.service;

import com.example.dataacquisition.model.PLCS7200x;
import de.re.easymodbus.modbusclient.ModbusClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * Consolidated service for reading data from all PLCs.
 *
 * Handles:
 * - Loading PLC configuration
 * - Maintaining ArrayList of PLCS7200x devices
 * - Reading Modbus data from each PLC
 * - Filtering data by line (using linea-id-config)
 * - Storing data in the device arrays
 */
@Service
public class PLCDataAcquisitionService {

    private static final Logger logger = LoggerFactory.getLogger(PLCDataAcquisitionService.class);

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private final ArrayList<PLCS7200x> plcDevices;
    private List<Map<String, Object>> lineaIdConfigCache;
    private final ConfigLoaderService configLoaderService;
    private final ApplicationEventPublisher eventPublisher;
    private final DatabaseInitializationService databaseInitializationService;
    private final KWhDifferenceService kwhDifferenceService;
    private final PLCDataQueryService plcDataQueryService;
    private final Map<String, BigDecimal> lastKWhValues = new HashMap<>();
    private final Map<String, ModbusClient> activeConnections = new ConcurrentHashMap<>();

    public PLCDataAcquisitionService(ConfigLoaderService configLoaderService, ApplicationEventPublisher eventPublisher,
                                    DatabaseInitializationService databaseInitializationService, KWhDifferenceService kwhDifferenceService,
                                    PLCDataQueryService plcDataQueryService) {
        this.configLoaderService = configLoaderService;
        this.eventPublisher = eventPublisher;
        this.databaseInitializationService = databaseInitializationService;
        this.kwhDifferenceService = kwhDifferenceService;
        this.plcDataQueryService = plcDataQueryService;
        this.plcDevices = new ArrayList<>();
        initializePLCDevices();
    }

    /**
     * Initialize PLC devices from configuration
     */
    private void initializePLCDevices() {
        logger.info("Initializing PLC devices from configuration...");

        List<Map<String, Object>> plcConfig = configLoaderService.loadPLCConfig();
        this.lineaIdConfigCache = configLoaderService.loadLineaIDConfig();

        plcDevices.clear();
        for (Map<String, Object> plc : plcConfig) {
            String nombre = (String) plc.get("nombre");
            String ipAddress = (String) plc.get("ipAddress");

            PLCS7200x device = new PLCS7200x(ipAddress, nombre);
            plcDevices.add(device);

            logger.info("Initialized PLC device: {} ({})", nombre, ipAddress);
        }

        logger.info("Total PLC devices initialized: {}", plcDevices.size());
    }

    /**
     * Main read cycle: iterate over all PLCs and read data
     */
    public void readAllPLCs() {
        logger.info("=== STARTING PLC READ CYCLE ===");

        for (PLCS7200x plc : plcDevices) {
            try {
                readSinglePLC(plc);
            } catch (Exception e) {
                logger.error("Error reading PLC {}: {}", plc.getNombre(), e.getMessage(), e);
            }
        }

        logger.info("=== COMPLETED PLC READ CYCLE ===");
    }
    private ModbusClient getConnectedClient(String ipAddress) {
        ModbusClient client = activeConnections.get(ipAddress);
        if (client == null || !client.isConnected()) {
            try {
                client = new ModbusClient();
                client.setipAddress(ipAddress);
                client.Connect();
                activeConnections.put(ipAddress, client);
                logger.info("Conexión persistente establecida con PLC en {}", ipAddress);
            } catch (Exception e) {
                logger.error("Error conectando a PLC {}: {}", ipAddress, e.getMessage());
                return null;
            }
        }
        return client;
    }
    /**
     * Read data from a single PLC
     * - Filter lines for this PLC
     * - Connect via Modbus
     * - Read registers and store in device arrays
     */
    private void readSinglePLC(PLCS7200x plc) {
        String plcName = plc.getNombre();
        String plcIP = plc.getPlcIPx();
        ModbusClient modbusClientPLC = new ModbusClient();
        modbusClientPLC.setipAddress(plc.getPlcIPx());
        int nDispositivos = 20;//numero total de dispositivos que soporta el PLC

        logger.info("Reading PLC: {} ({})", plcName, plcIP);

        // Get lines for this PLC
        List<Map<String, Object>> lineasDelPLC = filterLineasByPLC(plcName);
        logger.debug("PLC {} has {} lines", plcName, lineasDelPLC.size());

        if (lineasDelPLC.isEmpty()) {
            logger.warn("No lines configured for PLC {}", plcName);
            return;
        }
        // Check connectivity via ping
        if (!isIPAvailable(plcIP)) {
            logger.warn("IP {} not available for PLC {}", plcIP, plcName);
            return;
        }
        //{"KWh", "VAB", "VAC", "VBC", "IA", "IB", "IC", "PW", "PF", "KWhR"};
        //el orden y los registros que se estan leyendo desde el plc
        int numeroValores = 10;
        int[][] registrosPLC = new int[numeroValores][];

        try {
            modbusClientPLC.Connect();
            logger.debug("Connected to PLC {} at {}", plcName, plcIP);

            // Arrays to store BigDecimal data
            BigDecimal[][] datosConvertidosBD = new BigDecimal[numeroValores][];

            for (int lineIndex = 0; lineIndex < numeroValores; lineIndex++) {
                registrosPLC[lineIndex] = modbusClientPLC.ReadHoldingRegisters(nDispositivos * 2 * lineIndex, lineasDelPLC.size() * 2);
                datosConvertidosBD[lineIndex] = returnByteToBigDecimal(registrosPLC[lineIndex]);
            }
            //String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
            List<String> tablas = List.of( "TemperaturaAmbiente", "PsiAireP1", "TemperaturaAgua", "PsiAgua", "BarCompHP");
            int[] parametros=null;
            if(plc.getPlcIPx().equals("192.168.0.3"))
            {
                parametros = modbusClientPLC.ReadHoldingRegisters(422,tablas.size()); //readRegister(IpPLC, 422, tablas.size());
            }
            modbusClientPLC.Disconnect();
            // Unpack converted data into named variables
            BigDecimal[] KWh = datosConvertidosBD[0];
            BigDecimal[] VAB = datosConvertidosBD[1];
            BigDecimal[] VAC = datosConvertidosBD[2];
            BigDecimal[] VBC = datosConvertidosBD[3];
            BigDecimal[] IA = datosConvertidosBD[4];
            BigDecimal[] IB = datosConvertidosBD[5];
            BigDecimal[] IC = datosConvertidosBD[6];
            BigDecimal[] PW = datosConvertidosBD[7];
            BigDecimal[] PF = datosConvertidosBD[8];
            BigDecimal[] KWhR = datosConvertidosBD[9];

            String[] nombreTabla = new String[lineasDelPLC.size()];
            for (int i = 0; i < lineasDelPLC.size(); i++) {
                nombreTabla[i] = (String) lineasDelPLC.get(i).get("lineaMaquina");
            }
            String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
            databaseInitializationService.beginBatch();
            if(plc.getPlcIPx().equals("192.168.0.3"))
            {
                if(parametros!=null)
                {
                    final int[] finalParametros = parametros;

                    IntStream.range(0, tablas.size()).forEach(i -> {
                        String nombreTablax = tablas.get(i);
                        // Calculamos el valor ajustado antes de decidir el guardado
                        double valor = nombreTablax.contains("Temperatura")
                                ? finalParametros[i] / 10.0
                                : (double) finalParametros[i];

                        Object[] dataDiario = {timestamp, valor};
                        databaseInitializationService.guardarDatoBatch(dataDiario, nombreTablax, "DAILY");
                        databaseInitializationService.guardarDatoBatch(dataDiario, nombreTablax, "MONTHLY");

                    });
                }
            }

            for (int ixy = 0; ixy < nombreTabla.length; ixy++) {
                if (nombreTabla[ixy].equals("TDGeneradorSA")) {
                    KWh[ixy] = KWh[ixy].divide(BigDecimal.valueOf(1000.0));
                }
                if (nombreTabla[ixy].equals("KWhPlanta1")) {
                    int bits = ((registrosPLC[0][ixy * 2] & 0xFFFF) << 16) | (registrosPLC[0][ixy * 2 + 1] & 0xFFFF);
                    KWh[ixy] = BigDecimal.valueOf((float) bits);
                    bits = ((registrosPLC[1][ixy * 2] & 0xFFFF) << 16) | (registrosPLC[1][ixy * 2 + 1] & 0xFFFF);
                    VAB[ixy] = BigDecimal.valueOf((float) bits);
                    bits = ((registrosPLC[2][ixy * 2] & 0xFFFF) << 16) | (registrosPLC[2][ixy * 2 + 1] & 0xFFFF);
                    VAC[ixy] = BigDecimal.valueOf((float) bits);
                    bits = ((registrosPLC[3][ixy * 2] & 0xFFFF) << 16) | (registrosPLC[3][ixy * 2 + 1] & 0xFFFF);
                    VBC[ixy] = BigDecimal.valueOf((float) bits);
                    bits = ((registrosPLC[4][ixy * 2] & 0xFFFF));
                    IA[ixy] = BigDecimal.valueOf((float) bits / (float) 10.0);
                    bits = ((registrosPLC[5][ixy * 2] & 0xFFFF));
                    IB[ixy] = BigDecimal.valueOf((float) bits / (float) 10.0);
                    bits = ((registrosPLC[6][ixy * 2] & 0xFFFF));
                    IC[ixy] = BigDecimal.valueOf((float) bits / (float) 10.0);
                    bits = ((registrosPLC[7][ixy * 2] & 0xFFFF) << 16) | (registrosPLC[7][ixy * 2 + 1] & 0xFFFF);
                    PW[ixy] = BigDecimal.valueOf((float) bits);
                    bits = ((registrosPLC[8][ixy * 2]));
                    PF[ixy] = BigDecimal.valueOf((float) bits / (float) 100.0);
                    bits = ((registrosPLC[9][ixy * 2] & 0xFFFF) << 16) | (registrosPLC[9][ixy * 2 + 1] & 0xFFFF);
                    KWhR[ixy] = BigDecimal.valueOf((float) bits);
                }

                if (KWh[ixy] != null && KWh[ixy].doubleValue() != Double.NaN) {
                    Object[] dataDiario = {timestamp, KWh[ixy]};
                    databaseInitializationService.guardarDatoBatch(dataDiario, nombreTabla[ixy], "DAILY");

                    Object[] dataMensual = {timestamp, KWh[ixy]};
                    databaseInitializationService.guardarDatoBatch(dataMensual, nombreTabla[ixy], "MONTHLY");

                    kwhDifferenceService.procesarKWh(nombreTabla[ixy], KWh[ixy], timestamp);
                }

                if (VAB[ixy] != null && VAC[ixy] != null && VBC[ixy] != null && IA[ixy] != null && IB[ixy] != null && IC[ixy] != null && PW[ixy] != null && PF[ixy] != null && KWhR[ixy] != null) {
                    Object[] dataDiarioVIP = {timestamp, VAB[ixy], VAC[ixy], VBC[ixy], IA[ixy], IB[ixy], IC[ixy], PW[ixy], PF[ixy], KWhR[ixy]};
                    databaseInitializationService.guardarDatoBatch(dataDiarioVIP, nombreTabla[ixy], "DAILY_VIP");

                    Object[] dataMensualVIP = {timestamp, VAB[ixy], VAC[ixy], VBC[ixy], IA[ixy], IB[ixy], IC[ixy], PW[ixy], PF[ixy], KWhR[ixy]};
                    databaseInitializationService.guardarDatoBatch(dataMensualVIP, nombreTabla[ixy], "MONTHLY_VIP");

                    //logger.debug("Saved VIP data for {}: VAB={}, VAC={}, VBC={}, IA={}, IB={}, IC={}, PW={}, PF={}, KWhR={}",
                    //    nombreTabla[ixy], VAB[ixy], VAC[ixy], VBC[ixy], IA[ixy], IB[ixy], IC[ixy], PW[ixy], PF[ixy], KWhR[ixy]);
                }
            }

            databaseInitializationService.endBatch();

            // Publicar datos actualizados para cada máquina
            for (int ixy = 0; ixy < nombreTabla.length; ixy++) {
                if (nombreTabla[ixy] != null) {
                    try {
                        Map<String, Object> datosVIP = plcDataQueryService.getLatestVIPDataByMaquina(nombreTabla[ixy]);
                        Map<String, Object> datosKWh = plcDataQueryService.getLatestKWhDataByMaquina(nombreTabla[ixy]);

                        if (!datosVIP.containsKey("error") && !datosKWh.containsKey("error")) {
                            kwhDifferenceService.publicarDatosActuales(nombreTabla[ixy], datosVIP, datosKWh);
                        }
                    } catch (Exception e) {
                        logger.warn("Error publicando datos para {}: {}", nombreTabla[ixy], e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Failed to read PLC {} at {}: {}", plcName, plcIP, e.getMessage());
        } finally {
            try {
                modbusClientPLC.Disconnect();
                logger.debug("Disconnected from PLC {}", plcName);
            } catch (Exception e) {
                logger.warn("Error disconnecting from PLC {}: {}", plcName, e.getMessage());
            }
            databaseInitializationService.endBatch();
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

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Converts int array to BigDecimal array
    private BigDecimal[] returnByteToBigDecimal(int[] arrayByte) {
        BigDecimal[] byteToBigDecimal = new BigDecimal[arrayByte.length / 2];
        int[] regIntX = {0, 0};
        for (int ix = 0; ix < arrayByte.length / 2; ix++) {
            regIntX[0] = arrayByte[ix * 2];
            regIntX[1] = arrayByte[ix * 2 + 1];
            byteToBigDecimal[ix] = registroIntToBigDecimal(regIntX);
        }
        return byteToBigDecimal;
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Converts two int registers to BigDecimal
    private BigDecimal registroIntToBigDecimal(int[] dataMbTcIp) {
        if (dataMbTcIp.length != 2) {
            return BigDecimal.ZERO;
        }
        int combinado = (dataMbTcIp[0] << 16) | (dataMbTcIp[1] & 0xFFFF);
        float floatValue = Float.intBitsToFloat(combinado);

        if (Float.isNaN(floatValue)) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(floatValue);
    }

    /**
     * Filter lines from config that belong to a specific PLC
     */
    private List<Map<String, Object>> filterLineasByPLC(String plcName) {
        List<Map<String, Object>> filtered = new ArrayList<>();

        for (Map<String, Object> linea : lineaIdConfigCache) {
            String nombrePLC = (String) linea.get("nombrePLC");
            if (plcName.equals(nombrePLC)) {
                filtered.add(linea);
            }
        }

        return filtered;
    }


}
