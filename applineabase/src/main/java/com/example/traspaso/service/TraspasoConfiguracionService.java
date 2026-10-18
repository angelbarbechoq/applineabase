package com.example.traspaso.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.generador.model.EventoGenerador;
import com.example.generador.model.TipoEventoGenerador;
import com.example.generador.service.HistorialEventosService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * "Pasar configuracion": lleva lo configurado en el equipo de diseño al servidor sin tocar su historial.
 *
 * Exportar arma un ZIP con los archivos de configuración ({@link ConfigLoaderService#ARCHIVOS}), las tablas
 * de configuración de la base H2 ({@link #TABLAS}) y los eventos copiados a mano del historial de los
 * controladores de generador. Importar compara ese ZIP con lo que tiene el equipo (revisión, sin escribir
 * nada), y al aplicar: guarda antes una copia (la configuración actual en el mismo formato de ZIP, la base
 * H2 completa con BACKUP TO y el archivo de eventos) y recién ahí actualiza.
 *
 * Reglas: cada fila se busca por su clave natural (no por id). Lo que está solo en este equipo se deja,
 * salvo los registros de un modelo de medidor que viene en el archivo (el modelo queda igual al del
 * archivo). Usuarios: se crean los que faltan; en los que existen se actualizan rol, zona y permisos,
 * nunca la clave. Nunca se tocan las tablas de historial (energía, horómetro, alarmas ocurridas,
 * mantenimientos realizados, stock, arranques y eventos propios de los generadores).
 */
@Service
public class TraspasoConfiguracionService {

    private static final Logger logger = LoggerFactory.getLogger(TraspasoConfiguracionService.class);
    private static final int FORMATO = 1;
    private static final DateTimeFormatter FECHA_EVENTO = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);
    private static final DateTimeFormatter SELLO = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    /** Carpeta donde se deja el ZIP en el equipo que lo recibe. */
    public static final String CARPETA_IMPORTAR = RutaArchivosEnergia.BASE_PATH + "\\importar";
    private static final String CARPETA_RESPALDO = RutaArchivosEnergia.BASE_PATH + "\\backup";

    /**
     * Tabla de configuración: clave natural, columnas que no se actualizan en una fila existente y
     * valores fijos al crear una fila nueva.
     */
    private record Tabla(String nombre, String grupo, List<String> clave, Set<String> noActualizar, Map<String, Object> alCrear) {
    }

    private static final String REGISTROS_MODELO = "REGISTRO_MODELO_MEDIDOR";
    private static final String MODELO_NOMBRE = "MODELO_NOMBRE";

    /** En el orden en que se aplican (los modelos antes que sus registros). */
    private static final List<Tabla> TABLAS = List.of(
            new Tabla("USUARIOS", "Usuarios y permisos", List.of("USERNAME"), Set.of("PASSWORD"), Map.of()),
            new Tabla("ALARMA_CONFIG", "Umbrales de alarmas y del horometro", List.of("LINEA_MAQUINA", "TIPO_ALARMA"), Set.of(), Map.of()),
            new Tabla("MODELO_MEDIDOR", "Modelos de medidor", List.of("NOMBRE"), Set.of(), Map.of()),
            new Tabla(REGISTROS_MODELO, "Registros de los modelos de medidor", List.of(MODELO_NOMBRE, "PARAMETRO"), Set.of(), Map.of()),
            new Tabla("CONFIGURACION_CALIDAD", "Limites de calidad de energia", List.of("ID"), Set.of(), Map.of()),
            new Tabla("TENSION_NOMINAL", "Tensiones nominales", List.of("LINEA_MAQUINA"), Set.of(), Map.of()),
            new Tabla("PLAN_MANTENIMIENTO", "Planes de mantenimiento", List.of("TAG", "TAREA"), Set.of("NOTIFICADO"), Map.of("NOTIFICADO", false)),
            new Tabla("TECNICO_MANTENIMIENTO", "Tecnicos de mantenimiento", List.of("CI"), Set.of(), Map.of()),
            new Tabla("TARIFA_RED", "Tarifas de la red", List.of("VIGENTE_DESDE"), Set.of(), Map.of()),
            new Tabla("TARIFA_FRANJA", "Tarifas por franja horaria", List.of("MES"), Set.of(), Map.of()),
            new Tabla("FERIADO", "Feriados", List.of("FECHA"), Set.of(), Map.of()));

    private static final String GRUPO_ARCHIVOS = "Archivos de configuracion";
    private static final String GRUPO_EVENTOS = "Eventos copiados del controlador";

    private final DataSource dataSource;
    private final ConfigLoaderService configLoaderService;
    private final HistorialEventosService historialEventos;
    private final ObjectMapper objectMapper;

    public TraspasoConfiguracionService(DataSource dataSource, ConfigLoaderService configLoaderService,
                                        HistorialEventosService historialEventos, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.configLoaderService = configLoaderService;
        this.historialEventos = historialEventos;
        this.objectMapper = objectMapper;
    }

    // ================= Modelo de la revisión =================

    /** Contenido de un ZIP de configuración ya leído. */
    public record Paquete(String archivo, Map<String, Object> manifiesto, Map<String, byte[]> archivos,
                          Map<String, List<Map<String, Object>>> tablas, Map<String, List<EventoGenerador>> eventos) {
        public String origen() {
            return manifiesto.getOrDefault("equipo", "?") + ", " + manifiesto.getOrDefault("generado", "?")
                    + (manifiesto.get("usuario") == null ? "" : ", por " + manifiesto.get("usuario"));
        }
    }

    /** Totales de un grupo. seQuitan solo aplica a los registros de un modelo que viene en el archivo. */
    public record Grupo(String grupo, int nuevos, int cambian, int iguales, int seDejan, int seQuitan) {
    }

    /** Una diferencia concreta, para mostrar antes de importar. */
    public record Diferencia(String grupo, String elemento, String cambio) {
    }

    public record Revision(Paquete paquete, List<Grupo> grupos, List<Diferencia> diferencias, List<String> avisos) {
        public boolean hayCambios() {
            return grupos.stream().anyMatch(g -> g.nuevos() + g.cambian() + g.seQuitan() > 0);
        }
    }

    /** Resultado de importar: lo aplicado y dónde quedó la copia previa. */
    public record Resultado(Revision revision, Path respaldo) {
    }

    /** Acumula grupos y diferencias mientras se recorre el paquete. */
    private static final class Acumulador {
        final List<Grupo> grupos = new ArrayList<>();
        final List<Diferencia> diferencias = new ArrayList<>();
        final List<String> avisos = new ArrayList<>();
        int nuevos, cambian, iguales, seDejan, seQuitan;

        void cerrar(String grupo) {
            grupos.add(new Grupo(grupo, nuevos, cambian, iguales, seDejan, seQuitan));
            nuevos = cambian = iguales = seDejan = seQuitan = 0;
        }
    }

    // ================= Exportar =================

    /** Escribe el ZIP con la configuración de este equipo. */
    public void exportar(OutputStream out, String usuario) throws IOException {
        Map<String, Object> manifiesto = new LinkedHashMap<>();
        manifiesto.put("formato", FORMATO);
        manifiesto.put("generado", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        manifiesto.put("equipo", equipo());
        manifiesto.put("usuario", usuario);
        Map<String, Object> contenido = new LinkedHashMap<>();
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        for (String nombre : ConfigLoaderService.ARCHIVOS) {
            Path p = configLoaderService.rutaArchivo(nombre);
            if (!Files.exists(p)) continue;
            agregar(zip, "config/" + nombre, Files.readAllBytes(p));
            contenido.put("config/" + nombre, "archivo");
        }
        try (Connection c = dataSource.getConnection()) {
            for (Tabla t : TABLAS) {
                List<Map<String, Object>> filas = leerFilas(c, consultaActual(t));
                agregar(zip, "base/" + t.nombre().toLowerCase(Locale.ROOT) + ".json",
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("tabla", t.nombre(), "filas", filas)));
                contenido.put(t.nombre(), filas.size());
            }
        } catch (SQLException e) {
            throw new IOException("No se pudo leer la base: " + e.getMessage(), e);
        }
        for (Map.Entry<String, List<EventoGenerador>> e : historialEventos.eventosDelControlador().entrySet()) {
            List<Map<String, Object>> lista = new ArrayList<>();
            for (EventoGenerador ev : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("fecha", ev.fecha().format(FECHA_EVENTO));
                m.put("tipo", ev.tipo().name());
                m.put("descripcion", ev.descripcion());
                lista.add(m);
            }
            agregar(zip, "eventos-controlador/" + e.getKey() + ".json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(lista));
            contenido.put("eventos " + e.getKey(), lista.size());
        }
        manifiesto.put("contenido", contenido);
        agregar(zip, "manifiesto.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifiesto));
        zip.finish();
        logger.info("Configuracion exportada por {}: {}", usuario, contenido);
    }

    public static String nombreArchivoExportacion() {
        return "configuracion-lineabasex-" + equipo() + "-" + LocalDateTime.now().format(SELLO) + ".zip";
    }

    private static String equipo() {
        String e = System.getenv("COMPUTERNAME");
        return e == null || e.isBlank() ? "equipo" : e.replaceAll("[^A-Za-z0-9_-]", "");
    }

    private static void agregar(ZipOutputStream zip, String nombre, byte[] datos) throws IOException {
        zip.putNextEntry(new ZipEntry(nombre));
        zip.write(datos);
        zip.closeEntry();
    }

    // ================= Leer un ZIP =================

    /** ZIPs dejados en {@link #CARPETA_IMPORTAR}, el más nuevo primero. */
    public List<Path> archivosParaImportar() {
        Path carpeta = Paths.get(CARPETA_IMPORTAR);
        try {
            Files.createDirectories(carpeta);
            try (var s = Files.list(carpeta)) {
                return s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                        .sorted((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified()))
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            logger.warn("No se pudo leer {}: {}", carpeta, e.getMessage());
            return List.of();
        }
    }

    public Paquete leer(Path zipPath) throws IOException {
        Map<String, Object> manifiesto = null;
        Map<String, byte[]> archivos = new TreeMap<>();
        Map<String, List<Map<String, Object>>> tablas = new LinkedHashMap<>();
        Map<String, List<EventoGenerador>> eventos = new TreeMap<>();
        try (InputStream in = Files.newInputStream(zipPath); ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String nombre = e.getName();
                byte[] datos = leerLimitado(zip);
                if (nombre.equals("manifiesto.json")) {
                    manifiesto = objectMapper.readValue(datos, new TypeReference<Map<String, Object>>() { });
                } else if (nombre.startsWith("config/")) {
                    String archivo = nombre.substring("config/".length());
                    // Solo los archivos conocidos: el nombre que viene en el ZIP nunca arma una ruta.
                    if (ConfigLoaderService.ARCHIVOS.contains(archivo)) archivos.put(archivo, datos);
                } else if (nombre.startsWith("base/")) {
                    Map<String, Object> t = objectMapper.readValue(datos, new TypeReference<Map<String, Object>>() { });
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> filas = (List<Map<String, Object>>) t.get("filas");
                    tablas.put(String.valueOf(t.get("tabla")), filas == null ? List.of() : filas);
                } else if (nombre.startsWith("eventos-controlador/") && nombre.endsWith(".json")) {
                    String generador = nombre.substring("eventos-controlador/".length(), nombre.length() - ".json".length());
                    if (!generador.matches("[A-Za-z0-9_-]{1,40}")) continue;
                    List<Map<String, Object>> lista = objectMapper.readValue(datos, new TypeReference<List<Map<String, Object>>>() { });
                    List<EventoGenerador> evs = new ArrayList<>();
                    for (Map<String, Object> m : lista) {
                        try {
                            TipoEventoGenerador tipo = TipoEventoGenerador.valueOf(String.valueOf(m.get("tipo")));
                            if (!tipo.name().startsWith("CONTROLADOR_")) continue;
                            evs.add(EventoGenerador.de(LocalDateTime.parse(String.valueOf(m.get("fecha")), FECHA_EVENTO), tipo,
                                    String.valueOf(m.get("descripcion")), null));
                        } catch (RuntimeException ex) {
                            logger.warn("Evento ignorado en {}: {}", nombre, m);
                        }
                    }
                    eventos.put(generador, evs);
                }
            }
        }
        if (manifiesto == null) throw new IOException("El archivo no es una configuracion de LineaBaseX (falta manifiesto.json)");
        if (!(manifiesto.get("formato") instanceof Number f) || f.intValue() > FORMATO) {
            throw new IOException("El archivo es de una version mas nueva de la app: actualice primero la app de este equipo");
        }
        return new Paquete(zipPath.getFileName().toString(), manifiesto, archivos, tablas, eventos);
    }

    private static byte[] leerLimitado(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = zip.read(buf)) > 0) {
            b.write(buf, 0, n);
            if (b.size() > 50_000_000) throw new IOException("Archivo demasiado grande dentro del ZIP");
        }
        return b.toByteArray();
    }

    // ================= Revisar e importar =================

    /** Compara el paquete con este equipo, sin escribir nada. */
    public Revision revisar(Paquete p) throws IOException {
        try (Connection c = dataSource.getConnection()) {
            c.setReadOnly(true);
            return procesar(c, p, false);
        } catch (SQLException e) {
            throw new IOException("No se pudo leer la base: " + e.getMessage(), e);
        }
    }

    /** Guarda la copia previa y aplica el paquete. */
    public synchronized Resultado importar(Paquete p, String usuario) throws IOException {
        Path respaldo = respaldar();
        Revision r;
        try (Connection c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                r = procesar(c, p, true);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IOException("No se pudo actualizar la base (no se cambio nada de la base; la copia previa esta en "
                    + respaldo + "): " + e.getMessage(), e);
        }
        logger.info("Configuracion importada por {} desde {} ({}); copia previa en {}", usuario, p.archivo(), p.origen(), respaldo);
        return new Resultado(r, respaldo);
    }

    /**
     * Copia previa en backup\AAAA-MM-DD_HHmmss-antes-de-importar-configuracion: la configuración actual en el
     * mismo formato (se puede volver a importar), la base H2 completa y el archivo de eventos de los generadores.
     */
    private Path respaldar() throws IOException {
        Path carpeta = Paths.get(CARPETA_RESPALDO, LocalDateTime.now().format(SELLO) + "-antes-de-importar-configuracion");
        Files.createDirectories(carpeta);
        try (OutputStream out = Files.newOutputStream(carpeta.resolve("configuracion-de-este-equipo.zip"))) {
            exportar(out, "copia previa");
        }
        String destino = carpeta.resolve("base-h2-completa.zip").toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("BACKUP TO '" + destino + "'");
        } catch (SQLException e) {
            throw new IOException("No se pudo guardar la copia de la base, no se importo nada: " + e.getMessage(), e);
        }
        Path eventos = Paths.get(HistorialEventosService.ruta());
        if (Files.exists(eventos)) Files.copy(eventos, carpeta.resolve("eventos-generadores"));
        return carpeta;
    }

    private Revision procesar(Connection c, Paquete p, boolean aplicar) throws SQLException, IOException {
        Acumulador a = new Acumulador();

        // Base H2: todo en la transacción de c.
        for (Tabla t : TABLAS) {
            List<Map<String, Object>> filas = p.tablas().get(t.nombre());
            if (filas == null) {
                a.avisos.add(t.grupo() + ": no viene en el archivo, no se cambia.");
                continue;
            }
            procesarTabla(c, t, filas, aplicar, a);
            a.cerrar(t.grupo());
        }

        // Archivos de configuración: se escriben después de confirmar la base (ver importar).
        Map<String, byte[]> aEscribir = new LinkedHashMap<>();
        for (String nombre : ConfigLoaderService.ARCHIVOS) {
            byte[] nuevo = p.archivos().get(nombre);
            Path destino = configLoaderService.rutaArchivo(nombre);
            boolean existe = Files.exists(destino);
            if (nuevo == null) {
                if (existe) a.seDejan++;
                continue;
            }
            if (!existe) {
                a.nuevos++;
                a.diferencias.add(new Diferencia(GRUPO_ARCHIVOS, nombre, "nuevo"));
                aEscribir.put(nombre, nuevo);
            } else if (texto(Files.readAllBytes(destino)).equals(texto(nuevo))) {
                a.iguales++;
            } else {
                a.cambian++;
                a.diferencias.add(new Diferencia(GRUPO_ARCHIVOS, nombre, "cambia el contenido"));
                aEscribir.put(nombre, nuevo);
            }
        }
        a.cerrar(GRUPO_ARCHIVOS);

        // Eventos copiados del historial de los controladores.
        Map<String, Integer> eventosNuevos = new LinkedHashMap<>();
        for (Map.Entry<String, List<EventoGenerador>> e : p.eventos().entrySet()) {
            int nuevos = historialEventos.agregarDelControlador(e.getKey(), e.getValue(), false);
            a.nuevos += nuevos;
            a.iguales += e.getValue().size() - nuevos;
            if (nuevos > 0) {
                a.diferencias.add(new Diferencia(GRUPO_EVENTOS, e.getKey(), nuevos + " eventos nuevos"));
                eventosNuevos.put(e.getKey(), nuevos);
            }
        }
        a.cerrar(GRUPO_EVENTOS);

        if (aplicar) {
            c.commit();
            for (Map.Entry<String, byte[]> e : aEscribir.entrySet()) {
                Path destino = configLoaderService.rutaArchivo(e.getKey());
                Files.createDirectories(destino.getParent());
                Files.write(destino, e.getValue());
            }
            for (String generador : eventosNuevos.keySet()) {
                historialEventos.agregarDelControlador(generador, p.eventos().get(generador), true);
            }
        }
        return new Revision(p, a.grupos, a.diferencias, a.avisos);
    }

    /** Compara (y si {@code aplicar}, crea o actualiza) las filas de una tabla por su clave natural. */
    private void procesarTabla(Connection c, Tabla t, List<Map<String, Object>> filas, boolean aplicar, Acumulador a) throws SQLException {
        String tablaReal = t.nombre();
        Map<String, Integer> columnas = columnas(c, tablaReal);
        if (columnas.isEmpty()) {
            a.avisos.add(t.grupo() + ": la tabla no existe en este equipo (reinicie la app una vez y vuelva a revisar).");
            return;
        }
        boolean esRegistros = t.nombre().equals(REGISTROS_MODELO);
        Map<String, Map<String, Object>> actuales = new LinkedHashMap<>();
        for (Map<String, Object> f : leerFilas(c, consultaActual(t))) actuales.put(clave(t, f), f);

        // Registros de modelo: el conjunto de cada modelo que viene en el archivo queda igual al del archivo.
        Set<String> modelosDelArchivo = new HashSet<>();
        if (esRegistros) for (Map<String, Object> f : filas) modelosDelArchivo.add(String.valueOf(f.get(MODELO_NOMBRE)));

        Set<String> vistas = new HashSet<>();
        for (Map<String, Object> f : filas) {
            String k = clave(t, f);
            vistas.add(k);
            Map<String, Object> actual = actuales.get(k);
            if (actual == null) {
                a.nuevos++;
                a.diferencias.add(new Diferencia(t.grupo(), k, "nuevo"));
                if (aplicar) insertar(c, t, f, columnas);
                continue;
            }
            List<String> distintas = new ArrayList<>();
            List<String> detalle = new ArrayList<>();
            for (String col : f.keySet()) {
                if (!columnas.containsKey(col) || col.equals("ID") || t.clave().contains(col) || t.noActualizar().contains(col)
                        || (esRegistros && col.equals("MODELO_ID"))) {
                    continue;
                }
                if (!igual(f.get(col), actual.get(col))) {
                    distintas.add(col);
                    detalle.add(etiqueta(col) + " " + mostrar(actual.get(col)) + " -> " + mostrar(f.get(col)));
                }
            }
            if (distintas.isEmpty()) {
                a.iguales++;
            } else {
                a.cambian++;
                a.diferencias.add(new Diferencia(t.grupo(), k, "cambia: " + String.join("; ", detalle)));
                if (aplicar) actualizar(c, t, f, distintas, actual);
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : actuales.entrySet()) {
            if (vistas.contains(e.getKey())) continue;
            if (esRegistros && modelosDelArchivo.contains(String.valueOf(e.getValue().get(MODELO_NOMBRE)))) {
                a.seQuitan++;
                a.diferencias.add(new Diferencia(t.grupo(), e.getKey(), "se quita (el modelo del archivo no lo tiene)"));
                if (aplicar) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + REGISTROS_MODELO + " WHERE ID = ?")) {
                        ps.setObject(1, e.getValue().get("ID"));
                        ps.executeUpdate();
                    }
                }
            } else {
                a.seDejan++;
            }
        }
    }

    private void insertar(Connection c, Tabla t, Map<String, Object> f, Map<String, Integer> columnas) throws SQLException {
        Map<String, Object> valores = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : f.entrySet()) {
            String col = e.getKey();
            if (!columnas.containsKey(col) || (col.equals("ID") && !t.clave().contains("ID"))) continue;
            valores.put(col, e.getValue());
        }
        valores.putAll(t.alCrear());
        if (t.nombre().equals(REGISTROS_MODELO)) {
            Object modeloId = idModelo(c, String.valueOf(f.get(MODELO_NOMBRE)));
            if (modeloId == null) throw new SQLException("No existe el modelo " + f.get(MODELO_NOMBRE) + " para sus registros");
            valores.put("MODELO_ID", modeloId);
        }
        String cols = String.join(", ", valores.keySet());
        String marcas = valores.keySet().stream().map(x -> "?").collect(Collectors.joining(", "));
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + t.nombre() + " (" + cols + ") VALUES (" + marcas + ")")) {
            int i = 1;
            for (Object v : valores.values()) ps.setObject(i++, v);
            ps.executeUpdate();
        }
    }

    private void actualizar(Connection c, Tabla t, Map<String, Object> f, List<String> distintas, Map<String, Object> actual) throws SQLException {
        String set = distintas.stream().map(col -> col + " = ?").collect(Collectors.joining(", "));
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + t.nombre() + " SET " + set + " WHERE ID = ?")) {
            int i = 1;
            for (String col : distintas) ps.setObject(i++, f.get(col));
            ps.setObject(i, actual.get("ID"));
            ps.executeUpdate();
        }
    }

    private static Object idModelo(Connection c, String nombre) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ID FROM MODELO_MEDIDOR WHERE NOMBRE = ?")) {
            ps.setString(1, nombre);
            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? r.getObject(1) : null;
            }
        }
    }

    /** Los registros de modelo llevan el nombre del modelo (el id cambia de un equipo a otro). */
    private static String consultaActual(Tabla t) {
        if (t.nombre().equals(REGISTROS_MODELO)) {
            return "SELECT r.*, m.NOMBRE AS " + MODELO_NOMBRE + " FROM " + REGISTROS_MODELO + " r JOIN MODELO_MEDIDOR m ON m.ID = r.MODELO_ID ORDER BY m.NOMBRE, r.PARAMETRO";
        }
        return "SELECT * FROM " + t.nombre() + " ORDER BY " + String.join(", ", t.clave());
    }

    /** Filas con valores simples: números, booleanos y texto (fechas como texto, que H2 vuelve a convertir). */
    private static List<Map<String, Object>> leerFilas(Connection c, String sql) throws SQLException {
        List<Map<String, Object>> filas = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery(sql)) {
            ResultSetMetaData md = r.getMetaData();
            while (r.next()) {
                Map<String, Object> fila = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String col = md.getColumnLabel(i).toUpperCase(Locale.ROOT);
                    fila.put(col, valor(r, i, md.getColumnType(i)));
                }
                filas.add(fila);
            }
        }
        return filas;
    }

    private static Object valor(ResultSet r, int i, int tipo) throws SQLException {
        switch (tipo) {
            case Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE -> {
                return r.getString(i);
            }
            default -> {
                Object o = r.getObject(i);
                if (o == null || o instanceof Number || o instanceof Boolean || o instanceof String) return o;
                return o.toString();
            }
        }
    }

    private static Map<String, Integer> columnas(Connection c, String tabla) throws SQLException {
        Map<String, Integer> cols = new LinkedHashMap<>();
        try (ResultSet r = c.getMetaData().getColumns(null, "PUBLIC", tabla, null)) {
            while (r.next()) cols.put(r.getString("COLUMN_NAME").toUpperCase(Locale.ROOT), r.getInt("DATA_TYPE"));
        }
        return cols;
    }

    private static String clave(Tabla t, Map<String, Object> f) {
        return t.clave().stream().map(col -> mostrar(f.get(col))).collect(Collectors.joining(" / "));
    }

    /** Compara valores del archivo (JSON) con los de la base: 5, 5L y 5.0 son iguales. */
    static boolean igual(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        return Objects.equals(a == null ? null : a.toString(), b == null ? null : b.toString());
    }

    private static String mostrar(Object v) {
        if (v == null) return "(vacio)";
        if (v instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return v.toString();
    }

    /** "UMBRAL_MINIMO_KW" -> "umbral minimo kw". */
    private static String etiqueta(String columna) {
        return columna.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Texto con fin de línea unificado, para no marcar como distinto un archivo que solo cambia CRLF/LF. */
    private static String texto(byte[] datos) {
        return new String(datos, StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
    }
}
