package com.example.generador.service;

import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.dataacquisition.service.ModbusTcpConexion;
import com.example.generador.model.LecturaGenerador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lectura de los generadores con controlador ComAp InteliGen 200 (fase G1 del plan,
 * mantenimiento basado en condición). **SOLO LECTURA**: únicamente función 03; nunca se le
 * escribe al controlador (pedido explícito del usuario).
 *
 * Registros confirmados contra la pantalla del controlador (docs/ig200/MAPA-REGISTROS.md).
 * Guardado según estado: en marcha cada ciclo (1 min); parado cada {@link #MINUTOS_PARADO} min
 * (batería, refrigerante y contadores: lo que dice si está listo para arrancar); y un registro
 * por arranque (inicio/fin, duración, kWh y horas). Corrientes/kW/PF del generador: pendientes
 * de confirmar con carga (G4), no se leen todavía.
 */
@Service
public class GeneradorReaderService {

    private static final Logger logger = LoggerFactory.getLogger(GeneradorReaderService.class);
    private static final int TIMEOUT_MS = 3000;
    static final int MINUTOS_PARADO = 15;
    private static final int NO_DISPONIBLE = 0x8000;

    private final ConfigLoaderService configLoaderService;
    private final GeneradorAlmacen almacen;

    /** Último estado conocido (true = en marcha) y última lectura guardada estando parado. */
    private final Map<String, Boolean> enMarchaAnterior = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> ultimoGuardadoParado = new ConcurrentHashMap<>();

    public GeneradorReaderService(ConfigLoaderService configLoaderService, GeneradorAlmacen almacen) {
        this.configLoaderService = configLoaderService;
        this.almacen = almacen;
    }

    public void leerGeneradores() {
        List<Map<String, Object>> generadores = configLoaderService.loadGeneradoresConfig();
        for (Map<String, Object> g : generadores) {
            String nombre = String.valueOf(g.get("nombre"));
            String ip = String.valueOf(g.get("ipAddress"));
            int unitId = g.get("unitId") instanceof Number n ? n.intValue() : 1;
            try {
                LecturaGenerador l = leer(ip, unitId);
                procesar(nombre, l);
            } catch (ModbusTcpConexion.ExcepcionModbus e) {
                logger.warn("Generador {} ({}) contesto con {}", nombre, ip, e.getMessage());
            } catch (IOException e) {
                logger.warn("Generador {} ({}) sin comunicacion: {}", nombre, ip, e.getMessage());
            } catch (Exception e) {
                logger.error("Error leyendo generador {}: {}", nombre, e.getMessage(), e);
            }
        }
    }

    /** 4 pedidos de lectura (función 03). Direcciones base 0 = registro 4xxxx - 40001. */
    public static LecturaGenerador leer(String ip, int unitId) throws IOException, ModbusTcpConexion.ExcepcionModbus {
        try (ModbusTcpConexion c = new ModbusTcpConexion(ip, TIMEOUT_MS)) {
            int[] rpm = c.leerHolding(unitId, 1000, 1);    // 41001 RPM
            // 41020..41054 (base 0 1019..1053): kW tot/L1-3, kVAr tot/L1-3, kVA tot/L1-3, PF tot/L1-3,
            // frec gen, V L-N, V L-L, corrientes L1-3, (41046-47 sin uso), frec red, red V L-N, red V L-L.
            int[] e = c.leerHolding(unitId, 1019, 35);
            int[] mot = c.leerHolding(unitId, 1083, 4);    // 41084 bateria x10, 41085 ?, 41086 aceite x10, 41087 refrigerante
            int[] cont = c.leerHolding(unitId, 1230, 12);  // 41231 kWh, 41233 kVArh, 41235/37 red, 41239 horas x10, 41241 arranques
            return new LecturaGenerador(LocalDateTime.now().withNano(0),
                    u16(rpm[0], 1),
                    u16(e[16], 10),                                    // 41036 frecuencia generador
                    u16(e[17], 1), u16(e[18], 1), u16(e[19], 1),       // 41037-39 V L-N
                    u16(e[20], 1), u16(e[21], 1), u16(e[22], 1),       // 41040-42 V L-L
                    u16(e[23], 1), u16(e[24], 1), u16(e[25], 1),       // 41043-45 corrientes
                    s16(e[0]), s16(e[1]), s16(e[2]), s16(e[3]),        // 41020-23 kW total y por fase
                    s16(e[4]),                                         // 41024 kVAr total
                    u16(e[8], 1),                                      // 41028 kVA total
                    e[12] == NO_DISPONIBLE ? null : (short) e[12] / 100.0, // 41032 PF total
                    u16(e[28], 10),                                    // 41048 frecuencia red
                    u16(e[32], 1), u16(e[33], 1), u16(e[34], 1),       // 41052-54 red V L-L
                    u16(mot[0], 10),
                    u16(mot[2], 10),
                    s16(mot[3]),
                    u32(cont, 0),
                    u32(cont, 2),
                    u32(cont, 8) == null ? null : u32(cont, 8) / 10.0,
                    cont[10] == NO_DISPONIBLE ? null : cont[10]);
        }
    }

    void procesar(String nombre, LecturaGenerador l) {
        boolean enMarcha = l.enMarcha();
        Boolean antes = enMarchaAnterior.put(nombre, enMarcha);

        if (enMarcha && !Boolean.TRUE.equals(antes)) {
            // Arranque (o la app empezó con el generador ya en marcha: inicio estimado).
            if (antes == null && almacen.hayArranqueAbierto(nombre)) {
                logger.info("Generador {}: sigue en marcha, se continua el arranque abierto", nombre);
            } else {
                almacen.abrirArranque(nombre, l, antes == null);
            }
        } else if (!enMarcha && Boolean.TRUE.equals(antes)) {
            almacen.cerrarArranque(nombre, l);
            ultimoGuardadoParado.remove(nombre); // guardar la primera lectura ya parado
        } else if (!enMarcha && antes == null) {
            // App recién iniciada con el generador parado: cerrar un arranque que quedó abierto.
            almacen.cerrarArranque(nombre, l);
        }

        if (enMarcha) {
            almacen.guardarLectura(nombre, l);
            almacen.actualizarKwMax(nombre, l.kw());
            return;
        }
        LocalDateTime ultimo = ultimoGuardadoParado.get(nombre);
        if (ultimo == null || Duration.between(ultimo, l.fecha()).toMinutes() >= MINUTOS_PARADO) {
            almacen.guardarLectura(nombre, l);
            ultimoGuardadoParado.put(nombre, l.fecha());
        }
    }

    /** Divide (no multiplica por 0.1) para que 272 salga 27.2 y no 27.200000000000003. */
    private static Double u16(int v, int divisor) {
        return v == NO_DISPONIBLE ? null : v / (double) divisor;
    }

    private static Double s16(int v) {
        return v == NO_DISPONIBLE ? null : (double) (short) v;
    }

    private static Long u32(int[] r, int i) {
        if (r[i] == NO_DISPONIBLE && r[i + 1] == 0) {
            return null;
        }
        return ((long) (r[i] & 0xFFFF) << 16) | (r[i + 1] & 0xFFFF);
    }
}
