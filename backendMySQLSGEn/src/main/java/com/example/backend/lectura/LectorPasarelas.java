package com.example.backend.lectura;

import com.example.backend.modelo.EstadoDispositivo;
import com.example.backend.modelo.Fila;
import com.example.backend.modelo.ResultadoLectura;
import com.example.backend.modelo.TipoTabla;
import com.example.calidad.model.LecturaCalidad;
import com.example.calidad.service.CalculoCalidad;
import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.dataacquisition.service.ModbusTcpConexion;
import com.example.dataacquisition.service.ModbusUtil;
import com.example.dataacquisition.service.PASGatewayConfigService;
import com.example.medidores.model.ParametroMedidor;
import com.example.medidores.service.DefinicionModelo;
import com.example.medidores.service.LectorMedidorService;
import com.example.medidores.service.ModeloMedidorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Lectura de los medidores por pasarela PAS600L. Misma lógica que PASReaderService de applineabase: cada pasarela en
 * su hilo virtual, medidores uno tras otro con una sola conexión, registros según el catálogo de modelos (de la H2 de
 * la app), y si falta un parámetro básico el medidor queda sin dato (hueco, nunca 0). Arma las filas de energía, VIP
 * y calidad con las mismas columnas y unidades que el SQLite. Solo lectura.
 */
@Service
public class LectorPasarelas {

    private static final Logger logger = LoggerFactory.getLogger(LectorPasarelas.class);
    private static final int TIMEOUT_MS = 3000;
    private static final int MAX_FALLAS_RED_SEGUIDAS = 2;
    private static final long LIMITE_CICLO_MS = 45_000;

    /** Columnas de calidad: todos los parámetros salvo las energías, como CalidadEnergiaAlmacen. */
    public static final List<ParametroMedidor> PARAMETROS_CALIDAD = Arrays.stream(ParametroMedidor.values())
            .filter(p -> p != ParametroMedidor.KWH && p != ParametroMedidor.KWH_RETORNO)
            .toList();
    public static final String COL_DESB_I_CALC = "DESBALANCE_I_CALCULADO";
    public static final String COL_DESB_V_CALC = "DESBALANCE_V_CALCULADO";

    private final ConfigLoaderService config;
    private final PASGatewayConfigService gatewayConfig;
    private final ModeloMedidorService modeloMedidorService;
    private final LectorMedidorService lectorMedidorService;

    public LectorPasarelas(ConfigLoaderService config, PASGatewayConfigService gatewayConfig,
                           ModeloMedidorService modeloMedidorService, LectorMedidorService lectorMedidorService) {
        this.config = config;
        this.gatewayConfig = gatewayConfig;
        this.modeloMedidorService = modeloMedidorService;
        this.lectorMedidorService = lectorMedidorService;
    }

    /** Valores de un medidor leído, ya en las unidades del histórico. */
    private record Medidor(String tabla, BigDecimal kwh, BigDecimal[] vip, LecturaCalidad calidad) {
    }

    private record Pasarela(String nombre, String ip, int total, List<Medidor> leidos, String motivo, long ms) {
    }

    public ResultadoLectura leer() {
        ResultadoLectura r = ResultadoLectura.nuevo();
        List<Map<String, Object>> pasarelas = config.loadGatewayConfig();
        if (pasarelas.isEmpty()) return r;

        Map<String, DefinicionModelo> modelos;
        try {
            modelos = modeloMedidorService.definiciones();
        } catch (Exception e) {
            logger.error("No se pudo leer el catalogo de modelos de medidor de la H2: {}", e.getMessage());
            r.estados().add(new EstadoDispositivo("Pasarela", "(todas)", "", 0, 0,
                    "sin catálogo de modelos de medidor (H2): " + e.getMessage(), 0));
            return r;
        }
        if (modelos.isEmpty()) {
            r.estados().add(new EstadoDispositivo("Pasarela", "(todas)", "", 0, 0, "catálogo de modelos de medidor vacío", 0));
            return r;
        }

        LocalDateTime momento = LocalDateTime.now().withNano(0); // misma marca para todas las pasarelas
        List<Map<String, Object>> lineas = config.loadLineaIDConfig();
        List<Callable<Pasarela>> tareas = new ArrayList<>();
        for (Map<String, Object> g : pasarelas) {
            String nombre = String.valueOf(g.get("nombre"));
            String ip = String.valueOf(g.get("ipAddress"));
            List<Map<String, Object>> suyas = gatewayConfig.getLineasForGateway(nombre, lineas);
            if (suyas.isEmpty()) continue; // pasarela sin medidores de energía (por ejemplo la de mezcladores)
            tareas.add(() -> leerPasarela(nombre, ip, suyas, modelos));
        }

        try (ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Pasarela>> futuros = hilos.invokeAll(tareas, LIMITE_CICLO_MS, TimeUnit.MILLISECONDS);
            for (Future<Pasarela> f : futuros) {
                try {
                    Pasarela p = f.get();
                    r.estados().add(new EstadoDispositivo("Pasarela", p.nombre(), p.ip(), p.leidos().size(), p.total(),
                            p.motivo(), p.ms()));
                    for (Medidor m : p.leidos()) agregarFilas(r, m, momento);
                } catch (CancellationException e) {
                    r.estados().add(new EstadoDispositivo("Pasarela", "(una pasarela)", "", 0, 0,
                            "no terminó en " + LIMITE_CICLO_MS / 1000 + " s", LIMITE_CICLO_MS));
                } catch (ExecutionException e) {
                    logger.error("Error leyendo una pasarela: {}", e.getCause().getMessage(), e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return r;
    }

    private static void agregarFilas(ResultadoLectura r, Medidor m, LocalDateTime momento) {
        // Igual que persistGatewayData: el kWh solo si no es 0; el VIP si están todos los valores.
        if (m.kwh() != null && !m.kwh().equals(BigDecimal.ZERO)) {
            r.filas().add(new Fila(TipoTabla.ENERGIA, m.tabla(), momento, Map.of(RutaArchivosEnergia.CAMPOS_NORMAL[0], m.kwh())));
        }
        if (Arrays.stream(m.vip()).allMatch(v -> v != null)) {
            Map<String, Object> cols = new LinkedHashMap<>();
            for (int c = 0; c < RutaArchivosEnergia.CAMPOS_VIP.length; c++) {
                cols.put(RutaArchivosEnergia.CAMPOS_VIP[c], m.vip()[c]);
            }
            r.filas().add(new Fila(TipoTabla.VIP, m.tabla(), momento, cols));
        }
        if (m.calidad() != null) {
            Map<String, Object> cols = new LinkedHashMap<>();
            for (ParametroMedidor p : PARAMETROS_CALIDAD) {
                Double v = m.calidad().valores().get(p);
                cols.put(p.name(), v == null || v.isNaN() || v.isInfinite() ? null : v);
            }
            cols.put(COL_DESB_I_CALC, m.calidad().desbalanceICalculado() ? 1 : 0);
            cols.put(COL_DESB_V_CALC, m.calidad().desbalanceVCalculado() ? 1 : 0);
            r.filas().add(new Fila(TipoTabla.CALIDAD, m.tabla(), momento, cols));
        }
    }

    private Pasarela leerPasarela(String nombre, String ip, List<Map<String, Object>> lineas, Map<String, DefinicionModelo> modelos) {
        long inicio = System.currentTimeMillis();
        List<Medidor> leidos = new ArrayList<>();
        String primerMotivo = null;
        if (!ModbusUtil.isIPAvailable(ip)) {
            return new Pasarela(nombre, ip, lineas.size(), leidos, "pasarela sin respuesta a ping", System.currentTimeMillis() - inicio);
        }
        int fallasRedSeguidas = 0;
        try (ModbusTcpConexion conexion = new ModbusTcpConexion(ip, TIMEOUT_MS)) {
            for (Map<String, Object> linea : lineas) {
                String tabla = String.valueOf(linea.get("lineaMaquina"));
                String motivo;
                if (fallasRedSeguidas >= MAX_FALLAS_RED_SEGUIDAS) {
                    motivo = "pasarela sin respuesta Modbus este ciclo";
                } else {
                    try {
                        Object resultado = leerMedidor(conexion, linea, modelos);
                        if (resultado instanceof Medidor m) {
                            leidos.add(m);
                            motivo = null;
                        } else {
                            motivo = (String) resultado;
                        }
                        fallasRedSeguidas = 0;
                    } catch (ModbusTcpConexion.ExcepcionModbus e) {
                        motivo = e.getMessage();
                        fallasRedSeguidas = 0;
                    } catch (IOException e) {
                        motivo = "error de comunicación Modbus: " + e.getMessage();
                        fallasRedSeguidas++;
                    }
                }
                if (motivo != null) {
                    logger.warn("Medidor {} (Unit ID {}) en {} sin lectura: {}", tabla, linea.get("id"), nombre, motivo);
                    if (primerMotivo == null) primerMotivo = tabla + ": " + motivo;
                }
            }
        }
        return new Pasarela(nombre, ip, lineas.size(), leidos, primerMotivo, System.currentTimeMillis() - inicio);
    }

    /** @return el {@link Medidor} leído, o el motivo (String) por el que no se pudo. */
    private Object leerMedidor(ModbusTcpConexion conexion, Map<String, Object> linea, Map<String, DefinicionModelo> modelos)
            throws IOException, ModbusTcpConexion.ExcepcionModbus {
        String tabla = String.valueOf(linea.get("lineaMaquina"));
        int unitId = ((Number) linea.get("id")).intValue();
        String nombreModelo = String.valueOf(linea.get("modeloMedidor"));
        DefinicionModelo modelo = modelos.get(nombreModelo.toUpperCase());
        if (modelo == null) return "el modelo " + nombreModelo + " no existe en Configuración > Modelos de medidor";
        if (!modelo.completo()) return "el modelo " + nombreModelo + " no tiene cargados los registros básicos " + modelo.faltantesBasicos();

        LectorMedidorService.Lectura lectura = lectorMedidorService.leer(conexion, unitId, modelo, modelo.registros().keySet());
        Map<ParametroMedidor, String> erroresBasicos = new EnumMap<>(ParametroMedidor.class);
        for (ParametroMedidor p : modelo.requeridos()) {
            if (lectura.errores().containsKey(p)) erroresBasicos.put(p, lectura.errores().get(p));
        }
        if (!erroresBasicos.isEmpty()) return "parámetros sin lectura " + erroresBasicos;

        LecturaCalidad calidad = CalculoCalidad.construir(modelo, lectura.valores());
        Map<ParametroMedidor, Double> v = lectura.valores();
        Double retorno = v.get(ParametroMedidor.KWH_RETORNO);
        // Tensiones según su nombre (VAB = A-B, VAC = C-A, VBC = B-C), como PASReaderService desde el 2026-10-08.
        List<ParametroMedidor> tensiones = modelo.tensiones().getParametros();
        double kw = v.get(ParametroMedidor.KW_TOTAL);
        double pf = v.get(ParametroMedidor.PF_TOTAL);
        DefinicionModelo.Registro regPf = modelo.registros().get(ParametroMedidor.PF_TOTAL);
        if (regPf != null && regPf.pf4Cuadrantes()) pf = LectorMedidorService.decodificarPf4Cuadrantes(pf);
        BigDecimal[] vip = {
                decimal(v.get(tensiones.get(0))),                                   // VAB
                decimal(v.get(tensiones.get(2))),                                   // VAC
                decimal(v.get(tensiones.get(1))),                                   // VBC
                decimal(v.get(ParametroMedidor.IA)),
                decimal(v.get(ParametroMedidor.IB)),
                decimal(v.get(ParametroMedidor.IC)),
                decimal(modelo.historicoPotenciaEnW() ? kw * 1000.0 : kw),           // PW
                decimal(modelo.historicoPfEnPorcentaje() ? pf * 100.0 : pf),         // PF
                retorno == null ? BigDecimal.ZERO : decimal(retorno)                // KWhR
        };
        return new Medidor(tabla, decimal(v.get(ParametroMedidor.KWH)), vip, calidad);
    }

    /** Mismo formato que la app (expansión exacta del valor binario). */
    private static BigDecimal decimal(double valor) {
        return new BigDecimal(valor);
    }
}
