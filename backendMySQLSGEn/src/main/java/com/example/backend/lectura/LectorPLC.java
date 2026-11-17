package com.example.backend.lectura;

import com.example.backend.modelo.EstadoDispositivo;
import com.example.backend.modelo.Fila;
import com.example.backend.modelo.ResultadoLectura;
import com.example.backend.modelo.TipoTabla;
import com.example.dataacquisition.MaquinasVirtuales;
import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.dataacquisition.service.ModbusTcpConexion;
import com.example.dataacquisition.service.ModbusUtil;
import com.example.dataacquisition.service.PASGatewayConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lectura de los PLC Siemens S7-200 por Modbus TCP. Misma lectura y mismos cálculos que
 * PLCDataAcquisitionService.readSinglePLC de applineabase (registros, conversiones, TDGeneradorSA en kWh/1000,
 * KWhPlanta1 como float de 32 bits, PF con signo, sensores del PLC 192.168.0.3 en el registro 422); en vez de
 * escribir en SQLite deja las filas armadas. Solo lectura (función 03).
 */
@Service
public class LectorPLC {

    private static final Logger logger = LoggerFactory.getLogger(LectorPLC.class);

    static final int UNIT_ID_PLC = 1;
    static final int TIMEOUT_MS = 3000;
    /** Dispositivos que soporta cada PLC: separación entre bloques de registros. */
    private static final int N_DISPOSITIVOS = 20;
    /** KWh, VAB, VAC, VBC, IA, IB, IC, PW, PF, KWhR. */
    private static final int NUMERO_VALORES = 10;
    private static final String IP_PLC_SENSORES = "192.168.0.3";
    private static final List<String> TABLAS_SENSORES = List.of(MaquinasVirtuales.TEMPERATURA_AMBIENTE, "PsiAireP1",
            MaquinasVirtuales.TEMPERATURA_AGUA, "PsiAgua", "BarCompHP");

    private final ConfigLoaderService config;
    private final PASGatewayConfigService gatewayConfig;

    public LectorPLC(ConfigLoaderService config, PASGatewayConfigService gatewayConfig) {
        this.config = config;
        this.gatewayConfig = gatewayConfig;
    }

    public ResultadoLectura leer() {
        ResultadoLectura r = ResultadoLectura.nuevo();
        List<Map<String, Object>> lineas = config.loadLineaIDConfig();
        for (Map<String, Object> plc : config.loadPLCConfig()) {
            String nombre = String.valueOf(plc.get("nombre"));
            String ip = String.valueOf(plc.get("ipAddress"));
            try {
                leerUno(nombre, ip, gatewayConfig.getLineasForGateway(nombre, lineas), r);
            } catch (Exception e) {
                logger.error("Error leyendo PLC {}: {}", nombre, e.getMessage(), e);
                r.estados().add(new EstadoDispositivo("PLC", nombre, ip, 0, 0, "error: " + e.getMessage(), 0));
            }
        }
        return r;
    }

    private void leerUno(String nombre, String ip, List<Map<String, Object>> lineasDelPLC, ResultadoLectura r) {
        long inicio = System.currentTimeMillis();
        int total = lineasDelPLC.size();
        if (lineasDelPLC.isEmpty()) {
            r.estados().add(new EstadoDispositivo("PLC", nombre, ip, 0, 0, "sin líneas configuradas", 0));
            return;
        }
        if (!ModbusUtil.isIPAvailable(ip)) {
            r.estados().add(new EstadoDispositivo("PLC", nombre, ip, 0, total, "sin respuesta a ping",
                    System.currentTimeMillis() - inicio));
            return;
        }

        int[][] registros = new int[NUMERO_VALORES][];
        BigDecimal[][] datos = new BigDecimal[NUMERO_VALORES][];
        int[] parametros = null;
        try (ModbusTcpConexion conexion = new ModbusTcpConexion(ip, TIMEOUT_MS)) {
            conexion.conectar();
            for (int k = 0; k < NUMERO_VALORES; k++) {
                registros[k] = conexion.leerHolding(UNIT_ID_PLC, N_DISPOSITIVOS * 2 * k, total * 2);
                datos[k] = aBigDecimal(registros[k]);
            }
            if (ip.equals(IP_PLC_SENSORES)) {
                parametros = conexion.leerHolding(UNIT_ID_PLC, 422, TABLAS_SENSORES.size());
            }
        } catch (Exception e) {
            logger.error("Fallo la lectura del PLC {} en {}: {}", nombre, ip, e.getMessage());
            r.estados().add(new EstadoDispositivo("PLC", nombre, ip, 0, total, "error de conexión Modbus: " + e.getMessage(),
                    System.currentTimeMillis() - inicio));
            return;
        }

        LocalDateTime ahora = LocalDateTime.now().withNano(0);
        if (parametros != null) {
            for (int i = 0; i < TABLAS_SENSORES.size(); i++) {
                String tabla = TABLAS_SENSORES.get(i);
                double valor = tabla.contains("Temperatura") ? (short) parametros[i] / 10.0 : (double) (short) parametros[i];
                r.filas().add(new Fila(TipoTabla.ENERGIA, tabla, ahora, Map.of(RutaArchivosEnergia.CAMPOS_NORMAL[0], valor)));
            }
        }

        BigDecimal[] kwh = datos[0], vab = datos[1], vac = datos[2], vbc = datos[3], ia = datos[4], ib = datos[5],
                ic = datos[6], pw = datos[7], pf = datos[8], kwhR = datos[9];
        int leidos = 0;
        for (int i = 0; i < total; i++) {
            String tabla = String.valueOf(lineasDelPLC.get(i).get("lineaMaquina"));
            if (tabla.equals("TDGeneradorSA")) {
                kwh[i] = kwh[i].divide(BigDecimal.valueOf(1000.0));
            }
            if (tabla.equals(MaquinasVirtuales.KWH_PLANTA_1)) {
                kwh[i] = float32(registros[0], i);
                vab[i] = float32(registros[1], i);
                vac[i] = float32(registros[2], i);
                vbc[i] = float32(registros[3], i);
                ia[i] = BigDecimal.valueOf((float) (registros[4][i * 2] & 0xFFFF) / (float) 10.0);
                ib[i] = BigDecimal.valueOf((float) (registros[5][i * 2] & 0xFFFF) / (float) 10.0);
                ic[i] = BigDecimal.valueOf((float) (registros[6][i * 2] & 0xFFFF) / (float) 10.0);
                pw[i] = float32(registros[7], i);
                // Entero de 16 bits CON signo (PF negativo, ej. -9551 = -95.51): j2mod entrega sin signo.
                pf[i] = BigDecimal.valueOf((float) (short) registros[8][i * 2] / (float) 100.0);
                kwhR[i] = float32(registros[9], i);
            }
            boolean algo = false;
            if (kwh[i] != null) {
                r.filas().add(new Fila(TipoTabla.ENERGIA, tabla, ahora, Map.of(RutaArchivosEnergia.CAMPOS_NORMAL[0], kwh[i])));
                algo = true;
            }
            if (vab[i] != null && vac[i] != null && vbc[i] != null && ia[i] != null && ib[i] != null && ic[i] != null
                    && pw[i] != null && pf[i] != null && kwhR[i] != null) {
                BigDecimal[] vip = {vab[i], vac[i], vbc[i], ia[i], ib[i], ic[i], pw[i], pf[i], kwhR[i]};
                Map<String, Object> cols = new LinkedHashMap<>();
                for (int c = 0; c < RutaArchivosEnergia.CAMPOS_VIP.length; c++) {
                    cols.put(RutaArchivosEnergia.CAMPOS_VIP[c], vip[c]);
                }
                r.filas().add(new Fila(TipoTabla.VIP, tabla, ahora, cols));
                algo = true;
            }
            if (algo) leidos++;
        }
        r.estados().add(new EstadoDispositivo("PLC", nombre, ip, leidos, total, null, System.currentTimeMillis() - inicio));
    }

    /** Dos registros como float de 32 bits (palabra alta primero), como en la app. */
    private static BigDecimal float32(int[] registros, int i) {
        int bits = ((registros[i * 2] & 0xFFFF) << 16) | (registros[i * 2 + 1] & 0xFFFF);
        return BigDecimal.valueOf((float) bits);
    }

    private static BigDecimal[] aBigDecimal(int[] registros) {
        BigDecimal[] salida = new BigDecimal[registros.length / 2];
        int[] par = {0, 0};
        for (int i = 0; i < registros.length / 2; i++) {
            par[0] = registros[i * 2];
            par[1] = registros[i * 2 + 1];
            salida[i] = ModbusUtil.registroIntToBigDecimal(par);
        }
        return salida;
    }
}
