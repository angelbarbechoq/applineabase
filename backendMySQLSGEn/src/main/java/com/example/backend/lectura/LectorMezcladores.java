package com.example.backend.lectura;

import com.example.backend.modelo.EstadoDispositivo;
import com.example.backend.modelo.Fila;
import com.example.backend.modelo.ResultadoLectura;
import com.example.backend.modelo.TipoTabla;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.dataacquisition.service.ModbusTcpConexion;
import com.example.dataacquisition.service.ModbusUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Temperatura de los mezcladores (controladores Delta DTB48 por gateway Modbus TCP-RTU). Misma lectura que
 * MezcladorReaderService de applineabase: una conexión por gateway, PV y SV en 1000h/1001h con un decimal y signo.
 * Tabla por canal ("Mixer01_Calentamiento"), columnas PV y SV. Solo lectura.
 */
@Service
public class LectorMezcladores {

    private static final Logger logger = LoggerFactory.getLogger(LectorMezcladores.class);
    private static final int REGISTRO_PV = 0x1000;
    private static final int TIMEOUT_MS = 3000;

    private final ConfigLoaderService config;

    public LectorMezcladores(ConfigLoaderService config) {
        this.config = config;
    }

    private record Canal(String tabla, double pv, double sv) {
    }

    public ResultadoLectura leer() {
        ResultadoLectura r = ResultadoLectura.nuevo();
        List<Map<String, Object>> mezcladores = config.loadMezcladoresConfig();
        if (mezcladores.isEmpty()) return r;

        Map<String, String> ips = config.loadGatewayConfig().stream()
                .collect(Collectors.toMap(g -> String.valueOf(g.get("nombre")), g -> String.valueOf(g.get("ipAddress")), (a, b) -> a));
        Map<String, List<Map<String, Object>>> porGateway = mezcladores.stream()
                .collect(Collectors.groupingBy(m -> String.valueOf(m.get("gatewayNombre")), LinkedHashMap::new, Collectors.toList()));

        List<Canal> canales = new ArrayList<>();
        porGateway.forEach((gateway, suyos) -> {
            long inicio = System.currentTimeMillis();
            int total = suyos.size() * 2;
            String ip = ips.get(gateway);
            if (ip == null) {
                r.estados().add(new EstadoDispositivo("Mezcladores", gateway, "", 0, total, "gateway no configurado en plc-config.json", 0));
                return;
            }
            if (!ModbusUtil.isIPAvailable(ip)) {
                r.estados().add(new EstadoDispositivo("Mezcladores", gateway, ip, 0, total, "sin respuesta a ping",
                        System.currentTimeMillis() - inicio));
                return;
            }
            List<String> fallas = new ArrayList<>();
            int antes = canales.size();
            try (ModbusTcpConexion conexion = new ModbusTcpConexion(ip, TIMEOUT_MS)) {
                for (Map<String, Object> m : suyos) {
                    String nombre = String.valueOf(m.get("nombre"));
                    leerCanal(conexion, nombre, "Calentamiento", ((Number) m.get("idCalentamiento")).intValue(), canales, fallas);
                    leerCanal(conexion, nombre, "Enfriamiento", ((Number) m.get("idEnfriamiento")).intValue(), canales, fallas);
                }
            }
            r.estados().add(new EstadoDispositivo("Mezcladores", gateway, ip, canales.size() - antes, total,
                    fallas.isEmpty() ? null : fallas.get(0), System.currentTimeMillis() - inicio));
        });

        LocalDateTime ahora = LocalDateTime.now().withNano(0);
        for (Canal c : canales) {
            Map<String, Object> cols = new LinkedHashMap<>();
            cols.put(ConfigLoaderService.CAMPOS_MEZCLADOR[0], c.pv());
            cols.put(ConfigLoaderService.CAMPOS_MEZCLADOR[1], c.sv());
            r.filas().add(new Fila(TipoTabla.MEZCLADOR, c.tabla(), ahora, cols));
        }
        return r;
    }

    private static void leerCanal(ModbusTcpConexion conexion, String mezclador, String canal, int unitId,
                                  List<Canal> canales, List<String> fallas) {
        String tabla = ConfigLoaderService.nombreTablaCanalMezclador(mezclador, canal);
        try {
            int[] reg = conexion.leerHolding(unitId, REGISTRO_PV, 2);
            canales.add(new Canal(tabla, ((short) reg[0]) / 10.0, ((short) reg[1]) / 10.0));
        } catch (IOException | ModbusTcpConexion.ExcepcionModbus e) {
            logger.warn("Error leyendo {} (Unit ID {}): {}", tabla, unitId, e.getMessage());
            fallas.add(tabla + ": " + e.getMessage());
        }
    }
}
