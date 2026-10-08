package com.example.generador.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.dataacquisition.service.ModbusTcpConexion;
import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.generador.model.LecturaGenerador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Consultas para la pantalla del generador: lectura en vivo del controlador (SOLO LECTURA),
 * datos de la red asociada (TR2, del histórico del PLC), historial de arranques y tendencias de
 * lo guardado en {mes}Generador.
 */
@Service
public class GeneradorService {

    private static final Logger logger = LoggerFactory.getLogger(GeneradorService.class);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);

    public record Generador(String nombre, String ip, int unitId, String redAsociada, String descripcion) {
    }

    public record Arranque(LocalDateTime inicio, LocalDateTime fin, Double duracionMin, Long kwhGenerados,
                           Double kwMax, Double horasInicio, Double horasFin, Integer arranqueNro, boolean inicioEstimado) {
    }

    public record Punto(LocalDateTime fecha, Double valor) {
    }

    private final ConfigLoaderService configLoaderService;
    private final PLCDataQueryService plcDataQueryService;

    public GeneradorService(ConfigLoaderService configLoaderService, PLCDataQueryService plcDataQueryService) {
        this.configLoaderService = configLoaderService;
        this.plcDataQueryService = plcDataQueryService;
    }

    public List<Generador> generadores() {
        List<Generador> lista = new ArrayList<>();
        for (Map<String, Object> g : configLoaderService.loadGeneradoresConfig()) {
            lista.add(new Generador(String.valueOf(g.get("nombre")), String.valueOf(g.get("ipAddress")),
                    g.get("unitId") instanceof Number n ? n.intValue() : 1,
                    g.get("redAsociada") == null ? null : String.valueOf(g.get("redAsociada")),
                    g.get("descripcion") == null ? "" : String.valueOf(g.get("descripcion"))));
        }
        return lista;
    }

    /** Lectura en vivo del controlador (función 03, nunca escribe). */
    public LecturaGenerador leerEnVivo(Generador g) throws IOException, ModbusTcpConexion.ExcepcionModbus {
        return GeneradorReaderService.leer(g.ip(), g.unitId());
    }

    /** Última fila VIP de la red asociada (ej. Trafo2, leído por PLC): PW, IA/IB/IC, PF, fecha. */
    public Map<String, Object> ultimaRed(Generador g) {
        if (g.redAsociada() == null) {
            return Map.of();
        }
        try {
            Map<String, Object> m = plcDataQueryService.getLatestVIPDataByMaquina(g.redAsociada());
            return m.containsKey("error") ? Map.of() : m;
        } catch (Exception e) {
            logger.warn("No se pudo leer la red {}: {}", g.redAsociada(), e.getMessage());
            return Map.of();
        }
    }

    public List<Arranque> arranques(Generador g) {
        List<Arranque> lista = new ArrayList<>();
        String ruta = GeneradorAlmacen.rutaArranques();
        if (!new File(ruta).exists()) {
            return lista;
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta.replace('\\', '/') + "?mode=ro");
             ResultSet r = c.createStatement().executeQuery("SELECT inicio, fin, duracion_min, kwh_generados, kw_max, "
                     + "horas_inicio, horas_fin, arranques_contador, inicio_estimado FROM \"" + g.nombre() + "\" ORDER BY id DESC LIMIT 500")) {
            while (r.next()) {
                lista.add(new Arranque(fecha(r.getString(1)), fecha(r.getString(2)), num(r, 3),
                        r.getObject(4) == null ? null : r.getLong(4), num(r, 5), num(r, 6), num(r, 7),
                        r.getObject(8) == null ? null : r.getInt(8), r.getInt(9) == 1));
            }
        } catch (Exception e) {
            logger.warn("No se pudieron leer los arranques de {}: {}", g.nombre(), e.getMessage());
        }
        return lista;
    }

    /** Serie de una columna de {mes}Generador entre dos fechas (lo guardado: 1 min en marcha, 15 min parado). */
    public List<Punto> serie(Generador g, String columna, LocalDateTime desde, LocalDateTime hasta) {
        List<Punto> puntos = new ArrayList<>();
        for (YearMonth m = YearMonth.from(desde); !m.isAfter(YearMonth.from(hasta)); m = m.plusMonths(1)) {
            String ruta = GeneradorAlmacen.rutaLecturas(m);
            if (!new File(ruta).exists()) continue;
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta.replace('\\', '/') + "?mode=ro");
                 ResultSet r = c.createStatement().executeQuery("SELECT fecha, " + columna + " FROM \"" + g.nombre() + "\"")) {
                while (r.next()) {
                    LocalDateTime f = fecha(r.getString(1));
                    if (f != null && !f.isBefore(desde) && !f.isAfter(hasta)) {
                        puntos.add(new Punto(f, num(r, 2)));
                    }
                }
            } catch (Exception e) {
                logger.warn("No se pudo leer {} de {} en {}: {}", columna, g.nombre(), ruta, e.getMessage());
            }
        }
        puntos.sort((a, b) -> a.fecha().compareTo(b.fecha()));
        return puntos;
    }

    private static LocalDateTime fecha(String s) {
        return s == null ? null : LocalDateTime.parse(s, FECHA);
    }

    private static Double num(ResultSet r, int i) throws java.sql.SQLException {
        Object o = r.getObject(i);
        return o == null ? null : ((Number) o).doubleValue();
    }
}
