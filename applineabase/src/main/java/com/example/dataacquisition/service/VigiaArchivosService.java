package com.example.dataacquisition.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.event.DispositivoConectividadEvent;
import com.example.dataacquisition.event.SensorDataUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Con la lectura de equipos de la app apagada ({@code app.adquisicion.habilitada=false}), los archivos SQLite los
 * escribe el backend recolector (backendMySQLSGEn). Este vigía revisa cada 10 s los archivos del día y, por cada fila
 * nueva, manda los mismos avisos internos que manda hoy la lectura de la app, así Tiempo Real, las tarjetas en vivo,
 * el horómetro y las alarmas de planta siguen igual:
 * <ul>
 *   <li>Energía de cada máquina: {@link KWhDifferenceService#procesarKWh} (diferencia de kWh para Tiempo Real).</li>
 *   <li>VIP nuevo: {@link KWhDifferenceService#publicarDatosActuales} (tarjetas, alarmas de detención, ciclo de
 *       compresor y PF bajo, horómetro), con lo último del archivo del día, como hace la lectura.</li>
 *   <li>Sensores (tipo SENSOR en linea-id-config.json) y mezcladores (PV): SensorDataUpdateEvent con su derivada.</li>
 *   <li>Comunicación de cada línea: archivo {@code estado\equipos} del backend -> DispositivoConectividadEvent.</li>
 * </ul>
 * Al arrancar no repite lo ya guardado: toma lo último de cada tabla como punto de partida. No cubre lo de los
 * generadores (arranques, eventos, isla, alarmas, sirena), que todavía sale de la lectura de la app.
 */
@Service
@ConditionalOnProperty(name = "app.adquisicion.habilitada", havingValue = "false")
public class VigiaArchivosService {

    private static final Logger logger = LoggerFactory.getLogger(VigiaArchivosService.class);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);
    /** El archivo de estado del backend usa fecha ISO (se ordena igual que el tiempo). */
    private static final DateTimeFormatter FECHA_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConfigLoaderService config;
    private final DatabaseInitializationService rutas;
    private final PLCDataQueryService consultas;
    private final KWhDifferenceService kwh;
    private final ApplicationEventPublisher eventos;

    private LocalDate dia;
    private boolean iniciado;
    /** Última fecha procesada por tabla, en el archivo diario normal y en el VIP (dentro de un día el texto ordena bien). */
    private final Map<String, String> ultimaEnergia = new HashMap<>();
    private final Map<String, String> ultimaVip = new HashMap<>();
    private String ultimaConectividad = "";

    public VigiaArchivosService(ConfigLoaderService config, DatabaseInitializationService rutas, PLCDataQueryService consultas,
                                KWhDifferenceService kwh, ApplicationEventPublisher eventos) {
        this.config = config;
        this.rutas = rutas;
        this.consultas = consultas;
        this.kwh = kwh;
        this.eventos = eventos;
        logger.info("Lectura de equipos de la app APAGADA: los datos en vivo se toman de los archivos que escribe el backend");
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void revisar() {
        try {
            rutas.actualizarRutasDelDia();
            LocalDate hoy = LocalDate.now();
            if (!hoy.equals(dia)) {
                // Día nuevo: los archivos diarios son otros; todo lo que tengan es nuevo.
                ultimaEnergia.clear();
                ultimaVip.clear();
                dia = hoy;
            }
            Map<String, String> tipos = new LinkedHashMap<>();
            for (Map<String, Object> l : config.loadLineaIDConfig()) {
                String nombre = (String) l.get("lineaMaquina");
                if (PLCDataQueryService.esNombreMaquinaValido(nombre)) tipos.put(nombre, String.valueOf(l.get("tipo")));
            }
            List<String> mezcladores = config.listarNombresTablasMezcladores().stream()
                    .filter(PLCDataQueryService::esNombreMaquinaValido).toList();

            revisarEnergia(tipos, mezcladores);
            revisarVip(tipos);
            revisarConectividad();
            iniciado = true;
        } catch (Exception e) {
            logger.error("Error revisando los archivos del backend: {}", e.getMessage(), e);
        }
    }

    private void revisarEnergia(Map<String, String> tipos, List<String> mezcladores) throws SQLException {
        Path archivo = Path.of(rutas.getDailyPath());
        if (!Files.exists(archivo)) return;
        try (Connection c = abrir(archivo)) {
            for (Map.Entry<String, String> e : tipos.entrySet()) {
                boolean sensor = "SENSOR".equalsIgnoreCase(e.getValue());
                for (String[] fila : nuevas(c, e.getKey(), "kwh", ultimaEnergia)) {
                    double valor = Double.parseDouble(fila[1]);
                    if (sensor) {
                        if (iniciado) sensor(e.getKey(), valor, fila[0], null);
                    } else {
                        // Al arrancar solo deja el último valor como base (sin aviso), igual que el primer ciclo de la lectura.
                        kwh.procesarKWh(e.getKey(), BigDecimal.valueOf(valor), fila[0]);
                    }
                }
            }
            for (String tabla : mezcladores) {
                for (String[] fila : nuevas(c, tabla, "PV", ultimaEnergia)) {
                    if (iniciado) sensor(tabla, Double.parseDouble(fila[1]), fila[0], "PV");
                }
            }
        }
    }

    private void revisarVip(Map<String, String> tipos) throws SQLException {
        Path archivo = Path.of(rutas.getDailyVIPPath());
        if (!Files.exists(archivo)) return;
        try (Connection c = abrir(archivo)) {
            for (Map.Entry<String, String> e : tipos.entrySet()) {
                if ("SENSOR".equalsIgnoreCase(e.getValue())) continue;
                if (nuevas(c, e.getKey(), "PW", ultimaVip).isEmpty() || !iniciado) continue;
                // Como la lectura: lo último del VIP y del kWh del día, a las tarjetas, alarmas y horómetro.
                Map<String, Object> vip = consultas.getLatestVIPDataByMaquina(e.getKey());
                Map<String, Object> energia = consultas.getLatestKWhDataByMaquina(e.getKey());
                if (!vip.containsKey("error") && !energia.containsKey("error")) {
                    kwh.publicarDatosActuales(e.getKey(), vip, energia);
                }
            }
        }
    }

    private void revisarConectividad() throws SQLException {
        Path archivo = Path.of(RutaArchivosEnergia.BASE_PATH, "estado", "equipos");
        if (!Files.exists(archivo)) return;
        try (Connection c = abrir(archivo);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT linea, conectado, motivo, fecha FROM conectividad WHERE fecha > ? ORDER BY fecha")) {
            ps.setString(1, ultimaConectividad);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String fecha = rs.getString(4);
                    if (fecha.compareTo(ultimaConectividad) > 0) ultimaConectividad = fecha;
                    if (!iniciado) continue; // al arrancar solo toma el punto de partida
                    eventos.publishEvent(new DispositivoConectividadEvent(this, rs.getString(1), rs.getInt(2) == 1,
                            rs.getString(3), LocalDateTime.parse(fecha, FECHA_ISO)));
                }
            }
        } catch (SQLException e) {
            if (!String.valueOf(e.getMessage()).contains("no such table")) throw e;
        }
    }

    private void sensor(String tabla, double valor, String fecha, String columna) {
        LocalDateTime momento = LocalDateTime.parse(fecha, FECHA);
        Double derivada = columna == null
                ? consultas.calcularDerivadaPorHora(tabla, valor, momento)
                : consultas.calcularDerivadaPorHora(tabla, columna, valor, momento);
        eventos.publishEvent(new SensorDataUpdateEvent(this, tabla, valor, fecha, derivada));
    }

    /**
     * Filas nuevas de una tabla del archivo del día (fecha y valor como texto), en orden, y avanza la última fecha
     * procesada. Antes de la primera vuelta devuelve solo la última fila (punto de partida). Tabla que todavía no
     * existe o valor vacío: nada.
     */
    private List<String[]> nuevas(Connection c, String tabla, String columna, Map<String, String> ultima) {
        String sql = iniciado
                ? "SELECT fecha, " + columna + " FROM " + tabla + " WHERE fecha > ? ORDER BY fecha"
                : "SELECT fecha, " + columna + " FROM " + tabla + " WHERE fecha > ? ORDER BY fecha DESC LIMIT 1";
        List<String[]> filas = new java.util.ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ultima.getOrDefault(tabla, ""));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String fecha = rs.getString(1);
                    String valor = rs.getString(2);
                    ultima.put(tabla, fecha);
                    if (valor != null) filas.add(new String[]{fecha, valor});
                }
            }
        } catch (SQLException e) {
            logger.debug("Tabla {} sin leer: {}", tabla, e.getMessage());
        }
        return filas;
    }

    private static Connection abrir(Path archivo) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:file:" + archivo.toString().replace('\\', '/') + "?mode=ro");
    }
}
