package com.example.generador.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.ModbusTcpConexion;
import com.example.generador.model.Generador;
import com.example.generador.model.LecturaGenerador;
import com.example.generador.model.ModeloControlador;
import com.example.generador.model.ParametroGenerador;
import com.example.generador.model.SenalesRed;
import com.example.generador.model.SenalesRed.Estado;
import com.example.generador.model.SenalesRed.Selector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.example.generador.model.ParametroGenerador.*;

/**
 * Captura al segundo de los controladores de los generadores (pedido del usuario el 28-10-2026, para ver qué pasa
 * alrededor de un disparo por ROCOF, que con las lecturas de un minuto no se ve). **Solo lectura** (función 03).
 * <p>
 * Cada segundo (tic del hilo de un segundo de {@code DataAcquisitionTask}) lee unos 25 valores de cada controlador por
 * una conexión que queda abierta, cada generador en su propio hilo para que uno lento no demore al otro ni a la lectura
 * de un minuto. Los últimos {@link #SEGUNDOS_MEMORIA} s quedan en memoria; no se escribe nada mientras todo esté normal.
 * Cuando pasa algo (se abre el MCB o el GCB, sale de paralelo, la ROCOF pasa la mitad del ajuste, sube de golpe el
 * máximo de ROCOF o de salto de vector, la potencia cae más de {@link #CAIDA_KW} kW en un segundo o la frecuencia de red
 * sale de 59,8-60,2 Hz) se guarda sola una captura con los dos generadores, con el evento en el medio (pedido del
 * usuario): {@link #SEGUNDOS_ANTES} s antes y {@link #SEGUNDOS_DESPUES} s después de que todo vuelva a lo normal (así una
 * isla queda entera, con la vuelta a paralelo; máximo {@link #MAXIMO_DESPUES} s desde el primer disparo). Se escribe toda
 * junta al completarse. El administrador puede además guardar a mano los últimos 10 minutos.
 * <p>
 * Archivo {@code C:\LineaBaseX\generador\capturas}: tabla {@code capturas} (una fila por captura) y {@code muestras}
 * (una fila por segundo y generador).
 */
@Service
public class CapturaSegundoService {

    private static final Logger logger = LoggerFactory.getLogger(CapturaSegundoService.class);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);

    static final int SEGUNDOS_MEMORIA = 600;
    static final int SEGUNDOS_ANTES = 300;
    static final int SEGUNDOS_DESPUES = 300;
    static final int MAXIMO_DESPUES = 1200;
    static final double CAIDA_KW = 200;
    /** Subida de un segundo a otro del máximo de ROCOF (Hz/s) o del de salto de vector (grados) que dispara la captura. */
    static final double SUBIDA_ROCOF_MAX = 0.25, SUBIDA_SALTO_VECTOR = 1.0;
    static final double FRECUENCIA_MIN = 59.8, FRECUENCIA_MAX = 60.2;
    private static final int TIMEOUT_MS = 2000;
    /** Con el controlador sin responder se reintenta cada este tiempo, no cada segundo. */
    private static final long ESPERA_SIN_RESPUESTA_MS = 10_000;

    /** Valores que se leen cada segundo (además de las palabras que usan las señales de red del modelo). */
    static final Set<ParametroGenerador> PARAMETROS = EnumSet.of(RPM, KW, KVAR, KVA, PF, FRECUENCIA, V_L1L2, V_L2L3, V_L3L1,
            I_L1, I_L2, I_L3, RED_FRECUENCIA, RED_V_L1L2, RED_V_L2L3, RED_V_L3L1, RED_KW, ROCOF, ROCOF_MAX, SALTO_VECTOR_MAX,
            ESTADO_INTERRUPTORES, ESTADO_MOTOR, ENTRADAS_BINARIAS, AVISOS_RED, PROTECCIONES_RED);
    /** Columnas de la tabla de muestras (las de los valores, en este orden). */
    static final List<ParametroGenerador> COLUMNAS = List.of(RPM, KW, KVAR, PF, FRECUENCIA, V_L1L2, V_L2L3, V_L3L1, I_L1, I_L2,
            I_L3, RED_FRECUENCIA, RED_V_L1L2, RED_V_L2L3, RED_V_L3L1, RED_KW, ROCOF, ROCOF_MAX, SALTO_VECTOR_MAX,
            ESTADO_INTERRUPTORES, ESTADO_MOTOR, ENTRADAS_BINARIAS);

    /** Columnas de valores que se guardan de cada segundo (para el CSV). */
    public static List<ParametroGenerador> columnas() {
        return COLUMNAS;
    }

    /** Una lectura de un segundo de un generador. */
    public record Muestra(String generador, long t, Map<ParametroGenerador, Double> valores, Boolean mcb, Boolean gcb,
                          Selector selector, Estado estado, boolean marcha) {
        public Double v(ParametroGenerador p) {
            return valores.get(p);
        }
    }

    /** Una captura guardada. */
    public record Captura(long id, LocalDateTime inicio, LocalDateTime fin, LocalDateTime disparo, String motivo,
                          boolean manual, boolean abierta, int muestras) {
    }

    /** Captura en curso: las muestras juntadas (se escriben al completarse) y desde cuándo todo está normal. */
    private static final class Activa {
        long disparo, ultimoAnormal;
        // Un motivo por clase (texto hasta el primer número): la ROCOF alta de cada segundo no se repite.
        final Map<String, String> clases = new LinkedHashMap<>();
        final Set<String> motivos = new LinkedHashSet<>();

        void agregar(List<String> nuevos) {
            for (String s : nuevos) {
                if (clases.size() < 8 && clases.putIfAbsent(s.replaceAll("[0-9].*", ""), s) == null) motivos.add(s);
            }
        }
        final List<Muestra> pendientes = new ArrayList<>();
    }

    private final GeneradoresConfigService config;
    private final SirenaService sirena;
    private final Map<String, Deque<Muestra>> memoria = new ConcurrentHashMap<>();
    private final Map<String, Muestra> anterior = new ConcurrentHashMap<>();
    private final Map<String, ModbusTcpConexion> conexiones = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> leyendo = new ConcurrentHashMap<>();
    private final Map<String, Long> esperarHasta = new ConcurrentHashMap<>();
    private final Map<ModeloControlador, ModeloControlador> reducidos = new ConcurrentHashMap<>();
    private final ExecutorService hilos = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("captura-", 0).factory());
    private Activa activa;
    /** Generadores a leer con su modelo (se vuelve a armar cada minuto). */
    private volatile Map<Generador, ModeloControlador> aLeer = Map.of();
    private volatile long configLeida;
    /** Encendida o apagada por el administrador (queda guardado en generador-config.json). */
    private volatile boolean encendida;
    private volatile boolean estadoLeido;

    public CapturaSegundoService(GeneradoresConfigService config, SirenaService sirena) {
        this.config = config;
        this.sirena = sirena;
    }

    public static String ruta() {
        return RutaArchivosEnergia.BASE_PATH + "\\generador\\capturas";
    }

    // ================= Lectura al segundo =================

    /** Un tic por segundo: lanza la lectura de cada generador que no esté leyendo todavía. No espera a que termine. */
    public void tic() {
        long ahora = System.currentTimeMillis();
        if (!estadoLeido) {
            encendida = config.capturaAlSegundo();
            estadoLeido = true;
        }
        // También se lee al segundo si la sirena está habilitada: es lo que la hace sonar unos segundos después del disparo.
        if (!encendida && !sirena.habilitada()) {
            if (!conexiones.isEmpty()) {
                // Se apagaron las dos (por ejemplo, se deshabilitó la sirena con la captura apagada): no quedan conexiones abiertas.
                conexiones.values().forEach(ModbusTcpConexion::close);
                conexiones.clear();
                memoria.clear();
                anterior.clear();
            }
            return;
        }
        if (ahora - configLeida > 60_000) {
            // La configuración se vuelve a leer cada minuto, no cada segundo.
            Map<Generador, ModeloControlador> lista = new LinkedHashMap<>();
            for (Generador g : config.generadores()) {
                config.modelo(g.modelo()).filter(ModeloControlador::confirmado).ifPresent(m -> lista.put(g, m));
            }
            aLeer = lista;
            configLeida = ahora;
        }
        for (Map.Entry<Generador, ModeloControlador> e : aLeer.entrySet()) {
            Generador g = e.getKey();
            ModeloControlador modelo = e.getValue();
            Long hasta = esperarHasta.get(g.nombre());
            if (hasta != null && System.currentTimeMillis() < hasta) continue;
            AtomicBoolean ocupado = leyendo.computeIfAbsent(g.nombre(), k -> new AtomicBoolean());
            if (!ocupado.compareAndSet(false, true)) continue;
            hilos.execute(() -> {
                try {
                    leer(g, modelo);
                } finally {
                    ocupado.set(false);
                }
            });
        }
    }

    private void leer(Generador g, ModeloControlador modelo) {
        ModbusTcpConexion c = conexiones.computeIfAbsent(g.nombre(), k -> new ModbusTcpConexion(g.ip(), TIMEOUT_MS));
        try {
            LecturaGenerador l = LectorControlador.leer(c, g.unitId(), reducidos.computeIfAbsent(modelo, CapturaSegundoService::reducido));
            Muestra m = muestra(g.nombre(), System.currentTimeMillis(), l, modelo.red());
            sirena.condicionRapida(g.nombre(), sinRedSinSelectorIsla(m));
            // Lista de alarmas del controlador: null = no se pudo leer esta vez (la sirena conserva lo último hasta que venza).
            if (l.alarmas() != null) sirena.alarmasGenerador(g.nombre(), alarmasActivas(l.alarmas()));
            if (!encendida) {
                // Solo la sirena usa esta lectura (o se apagó la captura mientras leía): no se guarda nada.
                if (!sirena.habilitada()) {
                    c.close();
                    conexiones.remove(g.nombre());
                }
                return;
            }
            if (esperarHasta.remove(g.nombre()) != null) logger.info("Captura al segundo: {} vuelve a responder", g.nombre());
            registrar(m, modelo.red());
        } catch (Exception e) {
            c.close();
            if (esperarHasta.put(g.nombre(), System.currentTimeMillis() + ESPERA_SIN_RESPUESTA_MS) == null) {
                logger.warn("Captura al segundo: {} sin respuesta ({}), se reintenta cada {} s", g.nombre(), e.getMessage(),
                        ESPERA_SIN_RESPUESTA_MS / 1000);
            }
        }
    }

    /**
     * Lo que hace sonar la sirena de red sin esperar a la alarma de un minuto: el controlador está sin red (falla de red, isla o
     * retorno de red) y el selector no está en Isla. Es la misma regla con la que se abre la alarma de pérdida de red.
     */
    static boolean sinRedSinSelectorIsla(Muestra m) {
        return m.estado() != null && SenalesRed.sinRed(m.estado()) && m.selector() != Selector.ISLA;
    }

    /**
     * Nombres (sin los signos "!" y "*") de las alarmas de la lista del controlador que están activas ahora ("!"). Las que ya no
     * están activas pero faltan de reset no cuentan: la sirena suena mientras la causa está presente.
     */
    static List<String> alarmasActivas(List<String> lista) {
        List<String> r = new ArrayList<>();
        for (String a : lista) {
            if (AlarmaComap.activa(a)) r.add(AlarmaComap.nombre(a));
        }
        return r;
    }

    /** El mapa del modelo con solo los valores de la captura: con la lista de alarmas (la usa la sirena) y sin reloj, para que la lectura sea corta. */
    static ModeloControlador reducido(ModeloControlador m) {
        Set<ParametroGenerador> leer = EnumSet.copyOf(PARAMETROS);
        SenalesRed red = m.red();
        if (red != null) {
            for (SenalesRed.Bit b : new SenalesRed.Bit[]{red.mcbCerrado(), red.gcbCerrado(), red.avisoFallaRed(), red.proteccionRed(),
                    red.saltoVector(), red.rocof(), red.forzarIsla(), red.sincronismo()}) {
                if (b != null) leer.add(b.parametro());
            }
        }
        return new ModeloControlador(m.modelo(), m.descripcion(), m.confirmado(), m.maxHueco(), m.noDisponible8000(),
                m.registros().stream().filter(r -> leer.contains(r.parametro())).toList(), m.listaAlarmas(), null, red, m.modos(),
                m.estadosMotor(), m.entradas(), m.salidas(), m.listoAmf(), m.temporizadores(), m.tiemposAjustados());
    }

    static Muestra muestra(String generador, long t, LecturaGenerador l, SenalesRed red) {
        Boolean mcb = red == null ? null : SenalesRed.valor(red.mcbCerrado(), l);
        Boolean gcb = red == null ? null : SenalesRed.valor(red.gcbCerrado(), l);
        Selector selector = red == null ? null : red.selector(l);
        Estado estado = red == null ? null : red.estado(SenalesRed.codigo(l));
        return new Muestra(generador, t, new LinkedHashMap<>(l.valores()), mcb, gcb, selector, estado, l.enMarcha());
    }

    // ================= Memoria, disparos y capturas =================

    synchronized void registrar(Muestra m, SenalesRed red) {
        Deque<Muestra> cola = memoria.computeIfAbsent(m.generador(), k -> new ArrayDeque<>());
        cola.addLast(m);
        while (!cola.isEmpty() && m.t() - cola.peekFirst().t() > SEGUNDOS_MEMORIA * 1000L) cola.removeFirst();
        Muestra prev = anterior.put(m.generador(), m);
        List<String> motivos = prev == null ? List.of() : disparos(prev, m, red == null ? null : red.limiteRocof());
        boolean anormal = !motivos.isEmpty() || anormal(m);
        try {
            if (activa == null) {
                if (!motivos.isEmpty()) abrir(m.t(), motivos);
                return;
            }
            activa.pendientes.add(m);
            activa.agregar(motivos);
            if (anormal) activa.ultimoAnormal = m.t();
            long desdeDisparo = m.t() - activa.disparo, desdeNormal = m.t() - activa.ultimoAnormal;
            if (desdeDisparo >= MAXIMO_DESPUES * 1000L || desdeNormal >= SEGUNDOS_DESPUES * 1000L) cerrar(m.t());
        } catch (SQLException e) {
            logger.error("Captura al segundo: no se pudo guardar ({})", e.getMessage());
        }
    }

    /** Por qué guardar una captura, comparando con el segundo anterior del mismo generador. */
    static List<String> disparos(Muestra a, Muestra m, Double limiteRocof) {
        List<String> r = new ArrayList<>();
        String g = m.generador();
        if (m.marcha() && Boolean.TRUE.equals(a.mcb()) && Boolean.FALSE.equals(m.mcb()) && m.selector() != Selector.ISLA) {
            r.add(g + ": se abre el interruptor de la planta (MCB)");
        }
        if (a.marcha() && m.marcha() && Boolean.TRUE.equals(a.gcb()) && Boolean.FALSE.equals(m.gcb()) && m.selector() != Selector.AMF) {
            r.add(g + ": se abre el interruptor del generador (GCB)");
        }
        if (m.marcha() && a.estado() == Estado.PARALELO && m.estado() != null && m.estado() != Estado.PARALELO
                && m.selector() == Selector.SINCRONISMO) {
            r.add(g + ": sale de paralelo");
        }
        Double rocof = m.v(ROCOF);
        if (rocof != null && limiteRocof != null && Math.abs(rocof) >= 0.5 * limiteRocof) {
            r.add(String.format(Locale.ROOT, "%s: ROCOF %.2f Hz/s (%d %% del ajuste)", g, Math.abs(rocof), Math.round(Math.abs(rocof) / limiteRocof * 100)));
        }
        if (sube(a.v(ROCOF_MAX), m.v(ROCOF_MAX), SUBIDA_ROCOF_MAX)) {
            r.add(String.format(Locale.ROOT, "%s: el máximo de ROCOF sube de %.2f a %.2f Hz/s", g, a.v(ROCOF_MAX), m.v(ROCOF_MAX)));
        }
        if (sube(a.v(SALTO_VECTOR_MAX), m.v(SALTO_VECTOR_MAX), SUBIDA_SALTO_VECTOR)) {
            r.add(String.format(Locale.ROOT, "%s: el salto de vector máximo sube de %.1f a %.1f grados", g, a.v(SALTO_VECTOR_MAX), m.v(SALTO_VECTOR_MAX)));
        }
        Double kwA = a.v(KW), kw = m.v(KW);
        if (a.marcha() && kwA != null && kw != null && kwA - kw >= CAIDA_KW && m.selector() != Selector.AMF) {
            r.add(String.format(Locale.ROOT, "%s: la potencia cae de %.0f a %.0f kW en 1 s", g, kwA, kw));
        }
        Double f = m.v(RED_FRECUENCIA), fA = a.v(RED_FRECUENCIA);
        if (fueraDeRango(f) && !fueraDeRango(fA)) {
            r.add(String.format(Locale.ROOT, "%s: frecuencia de red %.1f Hz", g, f));
        }
        return r;
    }

    private static boolean sube(Double antes, Double ahora, double minimo) {
        return antes != null && ahora != null && ahora - antes >= minimo;
    }

    private static boolean fueraDeRango(Double f) {
        return f != null && f > 0 && (f < FRECUENCIA_MIN || f > FRECUENCIA_MAX);
    }

    /** Algo fuera de lo normal en este segundo: la captura sigue hasta {@link #SEGUNDOS_DESPUES} s después de que no lo haya. */
    static boolean anormal(Muestra m) {
        if (fueraDeRango(m.v(RED_FRECUENCIA))) return true;
        if (!m.marcha()) return false;
        if (Boolean.FALSE.equals(m.mcb()) && m.selector() != Selector.ISLA) return true;
        if (Boolean.FALSE.equals(m.gcb()) && m.selector() == Selector.SINCRONISMO) return true;
        return m.selector() == Selector.SINCRONISMO && m.estado() != null && m.estado() != Estado.PARALELO;
    }

    private void abrir(long t, List<String> motivos) throws SQLException {
        Activa a = new Activa();
        a.disparo = t;
        a.ultimoAnormal = t;
        a.agregar(motivos);
        memoria.values().forEach(cola -> cola.stream().filter(x -> x.t() >= t - SEGUNDOS_ANTES * 1000L).forEach(a.pendientes::add));
        activa = a;
        logger.warn("Captura al segundo iniciada: {}", String.join("; ", motivos));
    }

    private void cerrar(long t) throws SQLException {
        Activa a = activa;
        activa = null;
        a.pendientes.sort(java.util.Comparator.comparingLong(Muestra::t));
        String motivo = String.join("; ", a.motivos);
        long id = nuevaCaptura(a.pendientes.isEmpty() ? a.disparo : a.pendientes.get(0).t(), a.disparo, motivo, false);
        escribir(id, a.pendientes, null, t);
        logger.info("Captura al segundo {} guardada ({} s después del disparo): {}", id, (t - a.disparo) / 1000, motivo);
    }

    /** Guarda a mano los últimos 10 minutos de los dos generadores (botón del administrador). Devuelve el id. */
    public synchronized long guardarAhora(String usuario) throws SQLException {
        List<Muestra> todas = new ArrayList<>();
        memoria.values().forEach(todas::addAll);
        if (todas.isEmpty()) throw new SQLException("todavía no hay lecturas al segundo en memoria");
        todas.sort(java.util.Comparator.comparingLong(Muestra::t));
        long fin = todas.get(todas.size() - 1).t();
        long id = nuevaCaptura(todas.get(0).t(), fin, "Guardada a mano por " + usuario, true);
        escribir(id, todas, null, fin);
        logger.info("Captura al segundo {} guardada a mano por {}", id, usuario);
        return id;
    }

    public boolean encendida() {
        if (!estadoLeido) {
            encendida = config.capturaAlSegundo();
            estadoLeido = true;
        }
        return encendida;
    }

    /**
     * Enciende o apaga la captura (botón del administrador) y lo guarda. Al apagar se guarda lo que haya de una captura
     * en curso, se cierran las conexiones y se vacía la memoria.
     */
    public synchronized void encender(boolean si) throws SQLException {
        config.guardarCapturaAlSegundo(si);
        encendida = si;
        estadoLeido = true;
        configLeida = 0;
        if (si) {
            logger.info("Captura al segundo encendida");
            return;
        }
        if (activa != null) cerrar(System.currentTimeMillis());
        conexiones.values().forEach(ModbusTcpConexion::close);
        conexiones.clear();
        memoria.clear();
        anterior.clear();
        esperarHasta.clear();
        logger.info("Captura al segundo apagada");
    }

    /** Motivo de la captura que se está juntando (se guarda al completarse); null si no hay ninguna. */
    public synchronized String enCurso() {
        return activa == null ? null : fecha(activa.disparo).format(FECHA) + " · " + String.join("; ", activa.motivos);
    }

    /** Segundos que hay en memoria de cada generador (para la pantalla). */
    public Map<String, Integer> enMemoria() {
        Map<String, Integer> r = new LinkedHashMap<>();
        memoria.forEach((g, cola) -> r.put(g, cola.size()));
        return r;
    }

    // ================= Archivo =================

    private static Connection conectar() throws SQLException {
        try {
            Files.createDirectories(Paths.get(ruta()).getParent());
        } catch (Exception e) {
            throw new SQLException("No se pudo crear la carpeta de " + ruta(), e);
        }
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + ruta());
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout = 10000;");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS capturas (id INTEGER PRIMARY KEY AUTOINCREMENT, inicio INTEGER NOT NULL, "
                    + "fin INTEGER, disparo INTEGER NOT NULL, motivo TEXT, manual INTEGER NOT NULL DEFAULT 0)");
            StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS muestras (captura_id INTEGER NOT NULL, generador TEXT NOT NULL, "
                    + "t INTEGER NOT NULL, fecha TEXT NOT NULL, marcha INTEGER, mcb INTEGER, gcb INTEGER, selector TEXT, estado TEXT");
            for (ParametroGenerador p : COLUMNAS) sql.append(", ").append(p.columna()).append(" REAL");
            st.executeUpdate(sql.append(")").toString());
            st.executeUpdate("CREATE INDEX IF NOT EXISTS muestras_captura ON muestras (captura_id, t)");
        }
        return c;
    }

    private static long nuevaCaptura(long inicio, long disparo, String motivo, boolean manual) throws SQLException {
        try (Connection c = conectar();
             PreparedStatement ps = c.prepareStatement("INSERT INTO capturas (inicio, disparo, motivo, manual) VALUES (?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, inicio);
            ps.setLong(2, disparo);
            ps.setString(3, motivo);
            ps.setInt(4, manual ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                return k.next() ? k.getLong(1) : -1;
            }
        }
    }

    /** Agrega muestras a la captura y, si se dan, actualiza el motivo y el fin (fin = la captura queda cerrada). */
    private static void escribir(long id, List<Muestra> muestras, String motivo, Long fin) throws SQLException {
        try (Connection c = conectar()) {
            c.setAutoCommit(false);
            StringBuilder sql = new StringBuilder("INSERT INTO muestras (captura_id, generador, t, fecha, marcha, mcb, gcb, selector, estado");
            for (ParametroGenerador p : COLUMNAS) sql.append(", ").append(p.columna());
            sql.append(") VALUES (?,?,?,?,?,?,?,?,?").append(",?".repeat(COLUMNAS.size())).append(")");
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (Muestra m : muestras) {
                    ps.setLong(1, id);
                    ps.setString(2, m.generador());
                    ps.setLong(3, m.t());
                    ps.setString(4, fecha(m.t()).format(FECHA));
                    ps.setInt(5, m.marcha() ? 1 : 0);
                    setBool(ps, 6, m.mcb());
                    setBool(ps, 7, m.gcb());
                    ps.setString(8, m.selector() == null ? null : m.selector().name());
                    ps.setString(9, m.estado() == null ? null : m.estado().name());
                    for (int i = 0; i < COLUMNAS.size(); i++) {
                        Double v = m.v(COLUMNAS.get(i));
                        if (v == null) ps.setNull(10 + i, Types.REAL);
                        else ps.setDouble(10 + i, v);
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            if (motivo != null || fin != null) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE capturas SET motivo = coalesce(?, motivo), fin = coalesce(?, fin) WHERE id = ?")) {
                    ps.setString(1, motivo);
                    if (fin == null) ps.setNull(2, Types.INTEGER);
                    else ps.setLong(2, fin);
                    ps.setLong(3, id);
                    ps.executeUpdate();
                }
            }
            c.commit();
        }
    }

    private static void setBool(PreparedStatement ps, int i, Boolean b) throws SQLException {
        if (b == null) ps.setNull(i, Types.INTEGER);
        else ps.setInt(i, b ? 1 : 0);
    }

    static LocalDateTime fecha(long t) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault());
    }

    /** Borra una captura con todas sus muestras (botón del administrador). */
    public void borrar(long capturaId) throws SQLException {
        try (Connection c = conectar()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM muestras WHERE captura_id = ?")) {
                ps.setLong(1, capturaId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM capturas WHERE id = ?")) {
                ps.setLong(1, capturaId);
                ps.executeUpdate();
            }
            c.commit();
        }
        logger.info("Captura al segundo {} borrada", capturaId);
    }

    /** Capturas guardadas, la más reciente primero. */
    public List<Captura> capturas() {
        if (!Files.exists(Paths.get(ruta()))) return List.of();
        List<Captura> r = new ArrayList<>();
        try (Connection c = conectar();
             ResultSet rs = c.createStatement().executeQuery("SELECT c.id, c.inicio, c.fin, c.disparo, c.motivo, c.manual, "
                     + "(SELECT count(*) FROM muestras m WHERE m.captura_id = c.id) FROM capturas c ORDER BY c.id DESC")) {
            while (rs.next()) {
                long fin = rs.getLong(3);
                boolean abierta = rs.wasNull();
                r.add(new Captura(rs.getLong(1), fecha(rs.getLong(2)), abierta ? null : fecha(fin), fecha(rs.getLong(4)),
                        rs.getString(5), rs.getInt(6) == 1, abierta, rs.getInt(7)));
            }
        } catch (SQLException e) {
            logger.error("No se pudieron leer las capturas al segundo: {}", e.getMessage());
        }
        return r;
    }

    /** Muestras de una captura, en orden de tiempo (las de los dos generadores mezcladas). */
    public List<Muestra> muestras(long capturaId) {
        List<Muestra> r = new ArrayList<>();
        try (Connection c = conectar();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM muestras WHERE captura_id = ? ORDER BY t")) {
            ps.setLong(1, capturaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<ParametroGenerador, Double> v = new LinkedHashMap<>();
                    for (ParametroGenerador p : COLUMNAS) {
                        double x = rs.getDouble(p.columna());
                        if (!rs.wasNull()) v.put(p, x);
                    }
                    String sel = rs.getString("selector"), est = rs.getString("estado");
                    r.add(new Muestra(rs.getString("generador"), rs.getLong("t"), v, bool(rs, "mcb"), bool(rs, "gcb"),
                            sel == null ? null : Selector.valueOf(sel), est == null ? null : Estado.valueOf(est), rs.getInt("marcha") == 1));
                }
            }
        } catch (SQLException e) {
            logger.error("No se pudo leer la captura {}: {}", capturaId, e.getMessage());
        }
        return r;
    }

    private static Boolean bool(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v == 1;
    }
}
