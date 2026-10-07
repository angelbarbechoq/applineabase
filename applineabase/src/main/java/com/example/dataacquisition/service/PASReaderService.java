package com.example.dataacquisition.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.event.DispositivoConectividadEvent;
import com.example.dataacquisition.model.PAS600Lx;
import com.example.medidores.model.ParametroMedidor;
import com.example.medidores.service.DefinicionModelo;
import com.example.medidores.service.LectorMedidorService;
import com.example.medidores.service.ModeloMedidorService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    /** Vencimientos seguidos (sin ninguna respuesta) para dar por caída la pasarela en este ciclo. */
    private static final int MAX_FALLAS_RED_SEGUIDAS = 2;
    /** Tope de la fase de red de todas las pasarelas juntas, dentro del ciclo de 60 s. */
    private static final long LIMITE_CICLO_MS = 45_000;

    private final ArrayList<PAS600Lx> gatewayDevices;
    private final List<Map<String, Object>> lineaIdConfigCache;
    private final PASGatewayConfigService gatewayConfigService;
    private final DatabaseInitializationService databaseInitializationService;
    private final KWhDifferenceService kwhDifferenceService;
    private final PLCDataQueryService plcDataQueryService;
    private final ApplicationEventPublisher eventPublisher;
    private final ModeloMedidorService modeloMedidorService;
    private final LectorMedidorService lectorMedidorService;

    /** Parámetros que se guardan hoy por cada medidor (kWh y VIP). */
    private static final List<ParametroMedidor> PARAMETROS_BASICOS = Arrays.stream(ParametroMedidor.values())
            .filter(ParametroMedidor::isBasico).toList();

    public PASReaderService(PASGatewayConfigService gatewayConfigService,
                            ConfigLoaderService configLoaderService,
                            DatabaseInitializationService databaseInitializationService,
                            KWhDifferenceService kwhDifferenceService,
                            PLCDataQueryService plcDataQueryService,
                            ApplicationEventPublisher eventPublisher,
                            ModeloMedidorService modeloMedidorService,
                            LectorMedidorService lectorMedidorService) {
        this.modeloMedidorService = modeloMedidorService;
        this.lectorMedidorService = lectorMedidorService;
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

    /** Resultado de leer una pasarela en un ciclo: qué medidores se leyeron y el motivo de cada falla. */
    private record ResultadoPasarela(PAS600Lx gateway, List<Map<String, Object>> lineas, boolean[] leidos,
                                     String[] motivos, long ms) {
    }

    /**
     * Ciclo de lectura de todas las pasarelas.
     *
     * Fase 1 (red, en paralelo): cada pasarela se lee en su propio hilo virtual, porque son
     * equipos y buses RS-485 independientes; dentro de una pasarela los medidores van uno tras
     * otro (su bus atiende de a un pedido). El ciclo dura lo que la pasarela más lenta, no la suma.
     * Fase 2 (un solo hilo): eventos de conectividad y guardado en SQLite, porque la escritura por
     * lotes de DatabaseInitializationService no admite dos hilos a la vez.
     */
    public void readPAS600L() {
        if (gatewayDevices.isEmpty()) {
            logger.debug("No gateway devices configured");
            return;
        }

        // Catálogo de modelos leído en cada ciclo: un cambio en la pantalla rige desde el ciclo siguiente.
        Map<String, DefinicionModelo> modelos = modeloMedidorService.definiciones();
        if (modelos.isEmpty()) {
            logger.warn("Catalogo de modelos de medidor vacio (todavia no se cargo); se saltea la lectura de pasarelas");
            return;
        }

        long inicio = System.currentTimeMillis();
        // Misma marca de tiempo para todas las pasarelas del ciclo (filas alineadas entre máquinas).
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);

        List<Callable<ResultadoPasarela>> tareas = new ArrayList<>();
        for (PAS600Lx gateway : gatewayDevices) {
            List<Map<String, Object>> lineas = gatewayConfigService.getLineasForGateway(gateway.getNombrex(), lineaIdConfigCache);
            if (lineas.isEmpty()) {
                logger.debug("Pasarela {} sin medidores de energía asignados", gateway.getNombrex());
                continue;
            }
            tareas.add(() -> leerPasarela(gateway, lineas, modelos));
        }
        if (tareas.isEmpty()) {
            return;
        }

        List<ResultadoPasarela> resultados = new ArrayList<>();
        try (ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ResultadoPasarela>> futuros = hilos.invokeAll(tareas, LIMITE_CICLO_MS, TimeUnit.MILLISECONDS);
            for (int t = 0; t < futuros.size(); t++) {
                Future<ResultadoPasarela> futuro = futuros.get(t);
                try {
                    resultados.add(futuro.get());
                } catch (CancellationException e) {
                    logger.error("Lectura de pasarelas cortada a los {} ms; una pasarela no terminó a tiempo", LIMITE_CICLO_MS);
                } catch (ExecutionException e) {
                    logger.error("Error leyendo una pasarela: {}", e.getCause().getMessage(), e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Lectura de pasarelas interrumpida");
            return;
        }

        LocalDateTime ahora = LocalDateTime.now();
        databaseInitializationService.beginBatch();
        try {
            for (ResultadoPasarela r : resultados) {
                for (int i = 0; i < r.lineas().size(); i++) {
                    String nombreLinea = (String) r.lineas().get(i).get("lineaMaquina");
                    eventPublisher.publishEvent(new DispositivoConectividadEvent(this, nombreLinea, r.leidos()[i], r.motivos()[i], ahora));
                }
                persistGatewayData(r.gateway(), r.lineas(), r.leidos(), timestamp);
            }
        } finally {
            databaseInitializationService.endBatch();
        }
        publicarDatosActuales(resultados);

        StringBuilder detalle = new StringBuilder();
        for (ResultadoPasarela r : resultados) {
            int ok = 0;
            for (boolean b : r.leidos()) {
                if (b) ok++;
            }
            detalle.append(String.format(" | %s: %d/%d en %d ms", r.gateway().getNombrex(), ok, r.lineas().size(), r.ms()));
        }
        logger.info("Pasarelas leídas en {} ms{}", System.currentTimeMillis() - inicio, detalle);
    }

    /**
     * Lee todos los medidores de una pasarela con una sola conexión (el Unit ID va en cada pedido).
     * Corre en un hilo propio: no toca SQLite ni publica eventos. Si un medidor falla queda sin
     * dato este ciclo (hueco), ni ceros ni el valor anterior. Si la pasarela misma deja de
     * contestar ({@link #MAX_FALLAS_RED_SEGUIDAS} vencimientos seguidos), se abandona por este
     * ciclo en vez de esperar el timeout de cada medidor restante.
     */
    private ResultadoPasarela leerPasarela(PAS600Lx gateway, List<Map<String, Object>> lineas,
                                           Map<String, DefinicionModelo> modelos) {
        long inicio = System.currentTimeMillis();
        String gatewayName = gateway.getNombrex();
        String gatewayIP = gateway.getGatewayIP();
        boolean[] leidos = new boolean[lineas.size()];
        String[] motivos = new String[lineas.size()];

        if (!ModbusUtil.isIPAvailable(gatewayIP)) {
            logger.warn("Pasarela {} ({}) sin respuesta a ping", gatewayName, gatewayIP);
            java.util.Arrays.fill(motivos, "pasarela sin respuesta a ping");
            return new ResultadoPasarela(gateway, lineas, leidos, motivos, System.currentTimeMillis() - inicio);
        }

        int fallasRedSeguidas = 0;
        try (ModbusTcpConexion conexion = new ModbusTcpConexion(gatewayIP, TIMEOUT_MS)) {
            for (int i = 0; i < lineas.size(); i++) {
                Map<String, Object> linea = lineas.get(i);
                if (fallasRedSeguidas >= MAX_FALLAS_RED_SEGUIDAS) {
                    motivos[i] = "pasarela sin respuesta Modbus este ciclo";
                    continue;
                }
                try {
                    motivos[i] = readSingleMeter(conexion, gateway, linea, i, modelos);
                    leidos[i] = motivos[i] == null;
                    fallasRedSeguidas = 0;
                } catch (ModbusTcpConexion.ExcepcionModbus e) {
                    motivos[i] = e.getMessage(); // la pasarela contestó: la red está bien
                    fallasRedSeguidas = 0;
                } catch (IOException e) {
                    motivos[i] = "error de comunicacion Modbus: " + e.getMessage();
                    fallasRedSeguidas++;
                }
                if (motivos[i] != null) {
                    logger.warn("Medidor {} (Unit ID {}) en {} sin lectura: {}",
                            linea.get("lineaMaquina"), linea.get("id"), gatewayName, motivos[i]);
                }
            }
        }
        return new ResultadoPasarela(gateway, lineas, leidos, motivos, System.currentTimeMillis() - inicio);
    }

    /**
     * Lee los parámetros básicos (kWh, tensiones, corrientes, kW, PF) de un medidor según su
     * modelo del catálogo. Los valores se cargan en el gateway solo si salieron todos; si falta
     * uno, el medidor queda sin dato este ciclo.
     *
     * @return null si se leyó, o el motivo por el que no se pudo
     */
    private String readSingleMeter(ModbusTcpConexion conexion, PAS600Lx gateway, Map<String, Object> linea, int index,
                                   Map<String, DefinicionModelo> modelos)
            throws IOException, ModbusTcpConexion.ExcepcionModbus {
        int unitId = ((Number) linea.get("id")).intValue();
        String nombreModelo = String.valueOf(linea.get("modeloMedidor"));

        DefinicionModelo modelo = modelos.get(nombreModelo.toUpperCase());
        if (modelo == null) {
            return "el modelo " + nombreModelo + " no existe en Configuracion > Modelos de medidor";
        }
        if (!modelo.completo()) {
            return "el modelo " + nombreModelo + " no tiene cargados los registros basicos " + modelo.faltantesBasicos();
        }

        LectorMedidorService.Lectura lectura = lectorMedidorService.leer(conexion, unitId, modelo, PARAMETROS_BASICOS);
        if (!lectura.errores().isEmpty()) {
            return "parametros sin lectura " + lectura.errores();
        }
        Map<ParametroMedidor, Double> v = lectura.valores();

        // Se guardan en el orden de los PLC: VAB, VAC (= VCA del medidor), VBC.
        gateway.setKWhActx(index, decimal(v.get(ParametroMedidor.KWH)));
        gateway.setVABx(index, decimal(v.get(ParametroMedidor.VAB)));
        gateway.setVBCx(index, decimal(v.get(ParametroMedidor.VBC)));
        gateway.setVACx(index, decimal(v.get(ParametroMedidor.VCA)));
        gateway.setIAx(index, decimal(v.get(ParametroMedidor.IA)));
        gateway.setIBx(index, decimal(v.get(ParametroMedidor.IB)));
        gateway.setICx(index, decimal(v.get(ParametroMedidor.IC)));
        gateway.setKWx(index, decimal(v.get(ParametroMedidor.KW_TOTAL)));
        // PF tal como lo entrega el medidor (igual que por PLC); el decodificado 4 cuadrantes se
        // aplica al mostrarlo (ver docs/PLAN-CALIDAD-ENERGIA.md).
        gateway.setPFx(index, decimal(v.get(ParametroMedidor.PF_TOTAL)));

        logger.debug("Medidor {} (Unit ID {}, {}) leido", linea.get("lineaMaquina"), unitId, nombreModelo);
        return null;
    }

    /** Mismo formato que se guardaba antes (expansión exacta del valor binario). */
    private static BigDecimal decimal(double valor) {
        return new BigDecimal(valor);
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
            }
        }
    }

    /** Publica a la UI (tarjetas en vivo, SSE) los últimos datos de cada medidor leído, ya con el
     * lote guardado, igual que el lector de PLC. */
    private void publicarDatosActuales(List<ResultadoPasarela> resultados) {
        for (ResultadoPasarela r : resultados) {
            for (int i = 0; i < r.lineas().size(); i++) {
                if (!r.leidos()[i]) {
                    continue;
                }
                String nombreTabla = (String) r.lineas().get(i).get("lineaMaquina");
                try {
                    Map<String, Object> datosVIP = plcDataQueryService.getLatestVIPDataByMaquina(nombreTabla);
                    Map<String, Object> datosKWh = plcDataQueryService.getLatestKWhDataByMaquina(nombreTabla);
                    if (!datosVIP.containsKey("error") && !datosKWh.containsKey("error")) {
                        kwhDifferenceService.publicarDatosActuales(nombreTabla, datosVIP, datosKWh);
                    }
                } catch (Exception e) {
                    logger.warn("Error publicando datos de {}: {}", nombreTabla, e.getMessage());
                }
            }
        }
    }
}
