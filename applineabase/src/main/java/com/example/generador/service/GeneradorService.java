package com.example.generador.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.generador.model.Generador;
import com.example.generador.model.LecturaGenerador;
import com.example.generador.model.ModeloControlador;
import com.example.generador.model.ModeloControlador.Registro;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Consultas para la pantalla del generador: lectura en vivo del controlador (SOLO LECTURA),
 * datos de la red asociada (ej. TR1/TR2, del histórico del PLC), historial de arranques,
 * tendencias de lo guardado en {mes}Generador y exploración de registros para verificar un mapa.
 */
@Service
public class GeneradorService {

    private static final Logger logger = LoggerFactory.getLogger(GeneradorService.class);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);
    private static final DateTimeFormatter FECHA_ARCHIVO = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    public record Arranque(LocalDateTime inicio, LocalDateTime fin, Double duracionMin, Long kwhGenerados,
                           Double kwMax, Double horasInicio, Double horasFin, Integer arranqueNro, boolean inicioEstimado) {
    }

    public record Punto(LocalDateTime fecha, Double valor) {
    }

    /** Un registro explorado, con lo que dice el mapa del modelo si ese registro está mapeado. */
    public record FilaRegistro(LectorControlador.Celda celda, String parametro, String valorMapa) {
    }

    private final GeneradoresConfigService config;
    private final PLCDataQueryService plcDataQueryService;

    public GeneradorService(GeneradoresConfigService config, PLCDataQueryService plcDataQueryService) {
        this.config = config;
        this.plcDataQueryService = plcDataQueryService;
    }

    public List<Generador> generadores() {
        return config.generadores();
    }

    public Optional<ModeloControlador> modelo(Generador g) {
        return config.modelo(g.modelo());
    }

    /** Lectura en vivo del controlador (función 03, nunca escribe). */
    public LecturaGenerador leerEnVivo(Generador g) throws IOException {
        ModeloControlador m = modelo(g).orElseThrow(() ->
                new IOException("el modelo " + g.modelo() + " no esta en generador-modelos.json"));
        return LectorControlador.leer(g.ip(), g.unitId(), m);
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
            // Tabla inexistente = el generador todavía no tuvo arranques registrados.
            if (!String.valueOf(e.getMessage()).contains("no such table")) {
                logger.warn("No se pudieron leer los arranques de {}: {}", g.nombre(), e.getMessage());
            }
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
                if (!String.valueOf(e.getMessage()).contains("no such")) {
                    logger.warn("No se pudo leer {} de {} en {}: {}", columna, g.nombre(), ruta, e.getMessage());
                }
            }
        }
        puntos.sort((a, b) -> a.fecha().compareTo(b.fecha()));
        return puntos;
    }

    /**
     * Lee un rango de registros del controlador (función 03, solo lectura) y le pone al lado lo que
     * dice el mapa del modelo, para verificar o armar el mapa contra la pantalla del controlador.
     */
    public List<FilaRegistro> explorar(Generador g, int desde, int cantidad) throws IOException {
        List<LectorControlador.Celda> celdas = LectorControlador.explorar(g.ip(), g.unitId(), desde, cantidad);
        ModeloControlador m = modelo(g).orElse(null);
        List<FilaRegistro> filas = new ArrayList<>();
        for (int i = 0; i < celdas.size(); i++) {
            LectorControlador.Celda c = celdas.get(i);
            String parametro = "";
            String valorMapa = "";
            if (m != null) {
                for (Registro r : m.registros()) {
                    if (r.registro() != c.registro()) continue;
                    parametro = r.parametro().etiqueta();
                    int[] regs = new int[r.cantidad()];
                    boolean completo = true;
                    for (int k = 0; k < r.cantidad(); k++) {
                        Integer v = i + k < celdas.size() ? celdas.get(i + k).valor() : null;
                        if (v == null) completo = false;
                        else regs[k] = v;
                    }
                    Double v = completo ? LectorControlador.decodificar(regs, 0, r, m.noDisponible8000()) : null;
                    valorMapa = v == null ? (completo ? "no disponible" : "-")
                            : String.format(Locale.ROOT, "%.2f %s", v, r.parametro().unidad()).trim();
                }
            }
            filas.add(new FilaRegistro(c, parametro, valorMapa));
        }
        return filas;
    }

    /** Guarda una "foto" de la exploración en C:\LineaBaseX\generador\fotos (para analizarla después). */
    public Path guardarFoto(Generador g, List<FilaRegistro> filas, String comentario) throws IOException {
        Path carpeta = Paths.get(RutaArchivosEnergia.BASE_PATH, "generador", "fotos");
        Files.createDirectories(carpeta);
        Path archivo = carpeta.resolve(g.nombre() + "-" + LocalDateTime.now().format(FECHA_ARCHIVO) + ".csv");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(archivo, StandardCharsets.UTF_8))) {
            w.println("# " + g.nombre() + " (" + g.modelo() + ", " + g.ip() + ", unit " + g.unitId() + ") "
                    + LocalDateTime.now().format(FECHA) + (comentario == null || comentario.isBlank() ? "" : " - " + comentario));
            w.println("registro;base0;uint16;int16;hex;ancho;nota;parametro_mapa;valor_mapa");
            for (FilaRegistro f : filas) {
                LectorControlador.Celda c = f.celda();
                Integer v = c.valor();
                w.println(c.registro() + ";" + (c.registro() - 40001) + ";"
                        + (v == null ? "" : v) + ";" + (v == null ? "" : (short) (int) v) + ";"
                        + (v == null ? "" : String.format("%04X", v)) + ";" + c.ancho() + ";"
                        + (c.nota() == null ? "" : c.nota()) + ";" + f.parametro() + ";" + f.valorMapa());
            }
        }
        logger.info("Foto de registros de {} guardada en {}", g.nombre(), archivo);
        return archivo;
    }

    private static LocalDateTime fecha(String s) {
        return s == null ? null : LocalDateTime.parse(s, FECHA);
    }

    private static Double num(ResultSet r, int i) throws java.sql.SQLException {
        Object o = r.getObject(i);
        return o == null ? null : ((Number) o).doubleValue();
    }
}
