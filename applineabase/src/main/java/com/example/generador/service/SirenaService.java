package com.example.generador.service;

import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.dataacquisition.service.ModbusTcpConexion;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sirena física (pedido del usuario el 28-10-2026): el servidor le escribe por Modbus TCP al PLC Siemens LOGO! 8 en
 * 192.168.0.240 sus dos entradas de red. El único equipo al que la app escribe es este PLC (autorizado por el usuario solo
 * para este caso): los controladores de los generadores siguen solo en lectura.
 * <p>
 * El programa del LOGO! (diagrama del usuario del 29-10-2026) lleva NI1 (V1.0) a la entrada S de un flip-flop RS (B013, con
 * prioridad de reset) y NI2 (V1.1) a su entrada R; la salida del RS alimenta un generador de pulsos (4 s sonando, 3 s
 * apagada) que hace sonar la sirena. Por eso el protocolo es:
 * <ul>
 *   <li><b>Sirena ON:</b> V1.1 = 0 y después V1.0 = 1. Como el RS se queda enganchado, sigue sonando aunque V1.0 vuelva a 0.</li>
 *   <li><b>Sirena OFF:</b> V1.0 = 0 y después V1.1 = 1 (el reset gana). Se deja V1.1 en 1 mientras está apagada.</li>
 * </ul>
 * Qué la hace sonar, alarma por alarma (cada una tiene su identidad, para poder distinguir una nueva de una que ya estaba):
 * <ul>
 *   <li>{@code red:<generador>}: pérdida de red (disparo del interruptor de la planta por ROCOF o salto de vector, falla de
 *       red con los generadores parados o arrancando por AMF). Con la lectura al segundo encendida suena a los pocos segundos
 *       de quedar el controlador sin red; si no, con la alarma de un minuto. No suena con el selector del controlador en Isla
 *       (esa isla es a propósito) ni durante un corte programado.</li>
 *   <li>{@code potencia:<generador>}: alarma de potencia sobre el límite.</li>
 *   <li>{@code alarma:<generador>:<nombre>}: cada alarma de la lista de un controlador que esté activa ahora (paro de
 *       emergencia, paro por protección, avisos); las que solo faltan de reset no suenan. La lectura al segundo trae la lista.</li>
 * </ul>
 * <b>Silenciar</b> (pedido del usuario el 29-10-2026): calla la sirena por un tiempo configurable ({@code silencioMinutos}, 5 por
 * defecto). Pasado ese tiempo, si siguen las alarmas, vuelve a sonar. Si durante el silencio aparece <i>otra</i> alarma, de
 * cualquier generador o categoría (o vuelve una que se había corregido), el silencio se anula y la sirena suena; para volver a
 * silenciar hay que apretar el botón otra vez, con un tiempo completo. Si se corrigen todas las alarmas durante el silencio, el
 * silencio también se borra y queda normal, esperando otro pulso. "Probar" la prende 3 segundos.
 * <p>
 * Mientras suena se vuelve a escribir cada {@link #REAFIRMAR_MS} ms (si el LOGO! se reinicia, la memoria V vuelve a cero y el
 * RS se apaga) y se lee de vuelta para ver que el LOGO! tomó los valores. Sale deshabilitada: sin habilitarla no se toca el
 * PLC. Si dos instancias de la app (la de diseño y el servidor) la tienen habilitada, las dos escriben el mismo estado, pero el
 * silencio y los cortes programados son de cada una.
 * <p>
 * Direcciones: en el LOGO! la memoria V 0.0 - 850.7 es "Bob. 1 - 6808" de lectura y escritura (Espacio dir. Modbus de su
 * configuración); V0.0 es la bobina 1, o sea la dirección 0 en el cable: V1.0 = 8 y V1.1 = 9. Se pueden cambiar en
 * generador-config.json (bloque "sirena": un nombre como "V1.0" o el número de dirección).
 */
@Service
public class SirenaService {

    private static final Logger logger = LoggerFactory.getLogger(SirenaService.class);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final Pattern ENTRADA_V = Pattern.compile("(?i)V\\s*(\\d{1,3})\\.([0-7])");

    static final long REAFIRMAR_MS = 5_000;
    /** La lectura rápida (un segundo) vale este tiempo: si deja de llegar, la condición se apaga sola. */
    static final long VIGENCIA_RAPIDA_MS = 5_000;
    static final long ESPERA_SIN_RESPUESTA_MS = 5_000;
    static final long PRUEBA_MS = 3_000;
    public static final int SILENCIO_MINUTOS_DEFECTO = 5;
    public static final int SILENCIO_MINUTOS_MAXIMO = 240;
    /** El LOGO! con el puerto cerrado tarda unos 2 s en rechazar la conexión (medido el 29-10): con menos se vería como "sin respuesta". */
    private static final int TIMEOUT_MS = 4_000;
    private static final long REFRESCO_CONFIG_MS = 30_000;
    private static final long REFRESCO_ALARMAS_MS = 2_000;

    /** Ventana en la que la alarma de red no hace sonar la sirena (la alarma en pantalla y el historial siguen igual). */
    public record CorteProgramado(LocalDateTime desde, LocalDateTime hasta) {
        boolean incluye(LocalDateTime t) {
            return !t.isBefore(desde) && t.isBefore(hasta);
        }

        public String estado(LocalDateTime ahora) {
            return incluye(ahora) ? "Activo ahora" : ahora.isBefore(desde) ? "Programado" : "Terminado";
        }
    }

    /**
     * @param entradaActivar    entrada de red que enciende la sirena (S del RS del LOGO!), "V1.0"
     * @param entradaDesactivar entrada de red que la apaga (R del RS), "V1.1"
     * @param silencioMinutos   cuánto calla "Silenciar" antes de volver a sonar si siguen las alarmas
     */
    public record ConfigSirena(boolean habilitada, String ip, int puerto, int unitId, String entradaActivar, String entradaDesactivar,
                               int silencioMinutos, List<CorteProgramado> cortes) {
        boolean corteActivo(LocalDateTime t) {
            return cortes.stream().anyMatch(c -> c.incluye(t));
        }
    }

    /**
     * Lo que muestra la pantalla de la sirena. {@code silencioHasta}: hora (ms) en que vuelve a sonar, 0 si no está silenciada.
     * {@code leidoActivar} y {@code leidoDesactivar}: lo que el LOGO! devolvió en la última escritura.
     */
    public record Estado(boolean habilitada, String ip, boolean plcEnUso, boolean plcOk, String errorPlc, String entradaActivar,
                         String entradaDesactivar, boolean condRed, boolean condPotencia, boolean condGenerador,
                         boolean silenciadaRed, boolean silenciadaPotencia, boolean silenciadaGenerador, long silencioHasta,
                         int silencioMinutos, boolean corteActivo, boolean sonando, boolean prueba, String motivoRed,
                         String motivoPotencia, String motivoGenerador, Boolean leidoActivar, Boolean leidoDesactivar) {
    }

    /** Alarmas activas ahora de un generador, vistas por la lectura al segundo (valen {@link #VIGENCIA_RAPIDA_MS} ms). */
    private record AlarmasGenerador(long hasta, List<String> nombres) {
    }

    private final ConfigLoaderService configLoader;
    private final AlarmaEventoRepository eventos;
    private final ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean ocupado = new AtomicBoolean();

    private volatile ConfigSirena cfg;
    private volatile long cfgMs;

    /** Generador -> hasta cuándo vale su condición rápida de sin red (la pone la lectura al segundo). */
    private final Map<String, Long> rapidaHasta = new ConcurrentHashMap<>();
    /** Generador -> sus alarmas activas ahora según la lista del controlador (la pone la lectura al segundo). */
    private final Map<String, AlarmasGenerador> alarmasGenerador = new ConcurrentHashMap<>();
    private long alarmasMs;
    private boolean alarmasFallo;
    /** Generadores con la alarma de pérdida de red / de potencia abierta (de la lectura de un minuto). */
    private Set<String> redAbierta = Set.of(), potenciaAbierta = Set.of();

    // Estado calculado en cada ciclo (se lee desde la pantalla: siempre bajo this).
    private Set<String> clavesRed = Set.of(), clavesPotencia = Set.of(), clavesGenerador = Set.of();
    /** Las alarmas que están sonando o sonarían ahora (sin las de red en un corte programado). */
    private Set<String> suenan = Set.of();
    /** Las que estaban sonando cuando se apretó Silenciar y siguen activas. Con silencioHasta = 0 no hay silencio. */
    private Set<String> silenciadas = new HashSet<>();
    private long silencioHasta;
    private boolean corteActivo, deseado;
    private long pruebaHasta;
    private String motivoRed = "", motivoPotencia = "", motivoGenerador = "";

    // PLC (solo lo toca el ciclo, de a uno por vez; los resultados se publican con registrarPlc y registrarEscritura).
    private ModbusTcpConexion plc;
    private String plcClave;
    private long esperarHasta;
    private boolean plcOk;
    private String errorPlc;
    private Boolean escrito, leidoActivar, leidoDesactivar;
    private long escritoMs;

    public SirenaService(ConfigLoaderService configLoader, AlarmaEventoRepository eventos) {
        this.configLoader = configLoader;
        this.eventos = eventos;
    }

    // ================= Configuración =================

    public ConfigSirena config() {
        return config(System.currentTimeMillis());
    }

    private ConfigSirena config(long ahora) {
        ConfigSirena c = cfg;
        if (c == null || ahora - cfgMs > REFRESCO_CONFIG_MS) {
            c = leerConfig();
            cfg = c;
            cfgMs = ahora;
        }
        return c;
    }

    private ConfigSirena leerConfig() {
        Map<String, Object> m = configLoader.loadGeneradorSirena();
        List<CorteProgramado> cortes = new ArrayList<>();
        if (m.get("cortesProgramados") instanceof List<?> lista) {
            for (Object o : lista) {
                if (!(o instanceof Map<?, ?> c)) continue;
                try {
                    LocalDateTime desde = LocalDateTime.parse(String.valueOf(c.get("desde")), FECHA);
                    LocalDateTime hasta = LocalDateTime.parse(String.valueOf(c.get("hasta")), FECHA);
                    if (desde.isBefore(hasta)) cortes.add(new CorteProgramado(desde, hasta));
                } catch (DateTimeParseException e) {
                    logger.warn("generador-config.json, sirena: corte programado ignorado {} ({})", c, e.getMessage());
                }
            }
        }
        cortes.sort(java.util.Comparator.comparing(CorteProgramado::desde));
        int silencio = Math.max(1, Math.min(SILENCIO_MINUTOS_MAXIMO, entero(m.get("silencioMinutos"), SILENCIO_MINUTOS_DEFECTO)));
        return new ConfigSirena(Boolean.TRUE.equals(m.get("habilitada")), texto(m.get("ip"), "192.168.0.240"),
                entero(m.get("puerto"), 502), entero(m.get("unitId"), 1), texto(m.get("entradaActivar"), "V1.0"),
                texto(m.get("entradaDesactivar"), "V1.1"), silencio, List.copyOf(cortes));
    }

    private static String texto(Object o, String porDefecto) {
        return o == null || String.valueOf(o).isBlank() ? porDefecto : String.valueOf(o).trim();
    }

    private static int entero(Object o, int porDefecto) {
        return o instanceof Number n ? n.intValue() : porDefecto;
    }

    /** Guarda la configuración conservando lo demás del bloque "sirena" (por ejemplo su descripción). */
    private void guardar(ConfigSirena c) {
        Map<String, Object> m = new LinkedHashMap<>(configLoader.loadGeneradorSirena());
        m.remove("entradaRed");
        m.remove("entradaPotencia");
        m.put("habilitada", c.habilitada());
        m.put("ip", c.ip());
        m.put("puerto", c.puerto());
        m.put("unitId", c.unitId());
        m.put("entradaActivar", c.entradaActivar());
        m.put("entradaDesactivar", c.entradaDesactivar());
        m.put("silencioMinutos", c.silencioMinutos());
        List<Map<String, Object>> cortes = new ArrayList<>();
        for (CorteProgramado cp : c.cortes()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("desde", cp.desde().format(FECHA));
            e.put("hasta", cp.hasta().format(FECHA));
            cortes.add(e);
        }
        m.put("cortesProgramados", cortes);
        configLoader.saveGeneradorSirena(m);
        cfg = c;
        cfgMs = System.currentTimeMillis();
    }

    private static ConfigSirena con(ConfigSirena c, boolean habilitada, int silencioMinutos, List<CorteProgramado> cortes) {
        return new ConfigSirena(habilitada, c.ip(), c.puerto(), c.unitId(), c.entradaActivar(), c.entradaDesactivar(), silencioMinutos, cortes);
    }

    public synchronized void habilitar(boolean si) {
        ConfigSirena c = config();
        guardar(con(c, si, c.silencioMinutos(), c.cortes()));
        logger.info("Sirena {}", si ? "habilitada" : "deshabilitada");
    }

    /** Cuánto calla "Silenciar" (1 a {@link #SILENCIO_MINUTOS_MAXIMO} minutos). Vale desde el próximo pulso. */
    public synchronized void silencioMinutos(int minutos) {
        if (minutos < 1 || minutos > SILENCIO_MINUTOS_MAXIMO) {
            throw new IllegalArgumentException("El silencio va de 1 a " + SILENCIO_MINUTOS_MAXIMO + " minutos");
        }
        ConfigSirena c = config();
        guardar(con(c, c.habilitada(), minutos, c.cortes()));
        logger.info("Sirena: el silencio dura {} min", minutos);
    }

    public synchronized void agregarCorte(LocalDateTime desde, LocalDateTime hasta) {
        if (desde == null || hasta == null) throw new IllegalArgumentException("Falta el inicio o el fin del corte");
        if (!hasta.isAfter(desde)) throw new IllegalArgumentException("El fin tiene que ser posterior al inicio");
        if (!hasta.isAfter(LocalDateTime.now())) throw new IllegalArgumentException("Ese corte ya terminó");
        ConfigSirena c = config();
        List<CorteProgramado> cortes = new ArrayList<>(c.cortes());
        cortes.add(new CorteProgramado(desde.withSecond(0).withNano(0), hasta.withSecond(0).withNano(0)));
        cortes.sort(java.util.Comparator.comparing(CorteProgramado::desde));
        guardar(con(c, c.habilitada(), c.silencioMinutos(), cortes));
        logger.info("Sirena: corte programado del {} al {}", desde.format(FECHA), hasta.format(FECHA));
    }

    public synchronized void borrarCorte(CorteProgramado corte) {
        ConfigSirena c = config();
        List<CorteProgramado> cortes = new ArrayList<>(c.cortes());
        if (cortes.remove(corte)) {
            guardar(con(c, c.habilitada(), c.silencioMinutos(), cortes));
            logger.info("Sirena: corte programado borrado ({} a {})", corte.desde().format(FECHA), corte.hasta().format(FECHA));
        }
    }

    // ================= Lo que decide si suena =================

    public boolean habilitada() {
        return config().habilitada();
    }

    /**
     * Lectura al segundo ({@link CapturaSegundoService}): el controlador de este generador está sin red (falla de red, isla
     * o retorno de red) y su selector no está en Isla. Vale {@link #VIGENCIA_RAPIDA_MS} ms: hay que renovarla cada segundo.
     */
    public void condicionRapida(String generador, boolean activa) {
        if (activa) rapidaHasta.put(generador, System.currentTimeMillis() + VIGENCIA_RAPIDA_MS);
        else rapidaHasta.remove(generador);
    }

    /**
     * Lectura al segundo ({@link CapturaSegundoService}): nombres de las alarmas de la lista del controlador de este generador
     * que están activas ahora (con "!"; las que solo faltan de reset no cuentan). Vacía = ninguna. Pedido del usuario el
     * 29-10-2026, con un paro de emergencia del GenPower que no hacía sonar la sirena. Hay que renovarla cada segundo.
     */
    public void alarmasGenerador(String generador, List<String> activas) {
        if (activas == null || activas.isEmpty()) alarmasGenerador.remove(generador);
        else alarmasGenerador.put(generador, new AlarmasGenerador(System.currentTimeMillis() + VIGENCIA_RAPIDA_MS, List.copyOf(activas)));
    }

    /**
     * Calla la sirena por {@code silencioMinutos}. Pasado ese tiempo, si siguen las alarmas, vuelve a sonar; si aparece otra
     * alarma o se corrigen todas antes, el silencio se anula (ver {@link #ciclo()}). Sin nada sonando no hace nada.
     */
    public synchronized void silenciar() {
        if (suenan.isEmpty()) return;
        silenciadas = new HashSet<>(suenan);
        silencioHasta = System.currentTimeMillis() + config().silencioMinutos() * 60_000L;
        logger.info("Sirena silenciada {} min ({} alarma(s): {})", config().silencioMinutos(), silenciadas.size(), String.join(", ", new TreeSet<>(silenciadas)));
    }

    /** Prende la sirena {@link #PRUEBA_MS} ms para probarla junto con el cableado, aunque esté deshabilitada. */
    public synchronized void probar() {
        pruebaHasta = System.currentTimeMillis() + PRUEBA_MS;
        logger.info("Sirena: prueba ({} ms)", PRUEBA_MS);
        // El ciclo la escribe en el próximo segundo.
    }

    /** Un tic por segundo (hilo de un segundo de DataAcquisitionTask): el ciclo corre aparte y no demora al tic. */
    public void tic() {
        if (!ocupado.compareAndSet(false, true)) return;
        hilos.execute(() -> {
            try {
                ciclo();
            } catch (Exception e) {
                logger.error("Sirena: error en el ciclo", e);
            } finally {
                ocupado.set(false);
            }
        });
    }

    private void ciclo() {
        long ahora = System.currentTimeMillis();
        ConfigSirena c = config(ahora);
        refrescarAlarmas(ahora);
        boolean suena;
        synchronized (this) {
            // Las alarmas de cada causa, con su identidad.
            List<String> rapidas = rapidaHasta.entrySet().stream().filter(e -> e.getValue() > ahora).map(Map.Entry::getKey).sorted().toList();
            Set<String> gensRed = new TreeSet<>(redAbierta);
            gensRed.addAll(rapidas);
            clavesRed = claves("red:", gensRed);
            clavesPotencia = claves("potencia:", potenciaAbierta);
            Set<String> gen = new TreeSet<>();
            List<String> motivosGen = new ArrayList<>();
            for (Map.Entry<String, AlarmasGenerador> e : new TreeMap<>(alarmasGenerador).entrySet()) {
                if (e.getValue().hasta() <= ahora) continue;
                for (String nombre : e.getValue().nombres()) gen.add("alarma:" + e.getKey() + ":" + nombre);
                motivosGen.add(e.getKey() + ": " + String.join(", ", e.getValue().nombres()));
            }
            clavesGenerador = gen;
            motivoGenerador = String.join("; ", motivosGen);
            List<String> motivos = new ArrayList<>();
            if (!redAbierta.isEmpty()) motivos.add("alarma de pérdida de red abierta (" + String.join(", ", redAbierta) + ")");
            if (!rapidas.isEmpty()) motivos.add(String.join(", ", rapidas) + ": sin red según el controlador");
            motivoRed = String.join("; ", motivos);
            motivoPotencia = potenciaAbierta.isEmpty() ? "" : "alarma de potencia sobre el límite abierta (" + String.join(", ", potenciaAbierta) + ")";
            corteActivo = c.corteActivo(LocalDateTime.now());

            // Las que suenan: las de red no cuentan durante un corte programado.
            Set<String> ahoraSuenan = new HashSet<>(clavesPotencia);
            ahoraSuenan.addAll(clavesGenerador);
            if (!corteActivo) ahoraSuenan.addAll(clavesRed);
            suenan = ahoraSuenan;

            // Silencio: vence por tiempo, se anula con una alarma nueva y se borra al corregirse todas.
            if (silencioHasta != 0) {
                if (ahora >= silencioHasta) {
                    quitarSilencio("se cumplió el tiempo");
                } else {
                    silenciadas.retainAll(suenan);
                    if (suenan.isEmpty()) {
                        quitarSilencio("se corrigieron todas las alarmas");
                    } else if (!silenciadas.containsAll(suenan)) {
                        Set<String> nuevas = new TreeSet<>(suenan);
                        nuevas.removeAll(silenciadas);
                        quitarSilencio("apareció otra alarma: " + String.join(", ", nuevas));
                    }
                }
            }
            boolean quiere = !suenan.isEmpty() && silencioHasta == 0;
            deseado = ahora < pruebaHasta || (c.habilitada() && quiere);
            suena = deseado;
        }
        escribir(c, suena, ahora);
    }

    private static Set<String> claves(String prefijo, Set<String> generadores) {
        Set<String> r = new TreeSet<>();
        for (String g : generadores) r.add(prefijo + g);
        return r;
    }

    /** Termina el silencio (siempre bajo this): la sirena vuelve a su comportamiento normal. */
    private void quitarSilencio(String motivo) {
        silencioHasta = 0;
        silenciadas = new HashSet<>();
        logger.info("Sirena: termina el silencio ({})", motivo);
    }

    private void refrescarAlarmas(long ahora) {
        if (ahora - alarmasMs < REFRESCO_ALARMAS_MS) return;
        alarmasMs = ahora;
        try {
            Set<String> r = generadores(eventos.findByTipoAlarmaAndActivaTrue(TipoAlarma.PERDIDA_RED));
            Set<String> p = generadores(eventos.findByTipoAlarmaAndActivaTrue(TipoAlarma.POTENCIA_GENERADOR));
            synchronized (this) {
                redAbierta = r;
                potenciaAbierta = p;
            }
            alarmasFallo = false;
        } catch (Exception e) {
            // Se sigue con lo último que se sabía.
            if (!alarmasFallo) logger.warn("Sirena: no se pudieron leer las alarmas ({})", e.getMessage());
            alarmasFallo = true;
        }
    }

    private static Set<String> generadores(List<AlarmaEvento> lista) {
        Set<String> r = new TreeSet<>();
        if (lista != null) {
            for (AlarmaEvento e : lista) {
                if (e.getLineaMaquina() != null) r.add(e.getLineaMaquina());
            }
        }
        return r;
    }

    // ================= PLC =================

    /**
     * Direcciones: "V1.0" -> 8 (8 * byte + bit, base 0 en el cable: V0.0 es la dirección 0). Un número solo se toma como la
     * dirección tal cual. null = no se entiende.
     */
    static Integer direccion(String entrada) {
        if (entrada == null) return null;
        Matcher m = ENTRADA_V.matcher(entrada.trim());
        if (m.matches()) return Integer.parseInt(m.group(1)) * 8 + Integer.parseInt(m.group(2));
        try {
            int n = Integer.parseInt(entrada.trim());
            return n >= 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void escribir(ConfigSirena c, boolean suena, long ahora) {
        boolean prueba;
        synchronized (this) {
            prueba = ahora < pruebaHasta;
        }
        if (!c.habilitada() && !prueba && !Boolean.TRUE.equals(escrito)) {
            cerrar(); // sin sirena no se mantiene conexión con el LOGO!
            return;
        }
        if (ahora < esperarHasta) return;
        Integer dirActivar = direccion(c.entradaActivar()), dirDesactivar = direccion(c.entradaDesactivar());
        if (dirActivar == null || dirDesactivar == null) {
            registrarPlc(false, "dirección de entrada de red inválida (" + c.entradaActivar() + " / " + c.entradaDesactivar() + ")");
            return;
        }
        // Al arrancar (escrito == null) se escribe el estado apagado aunque no haya nada que prender: así una sirena que quedó
        // enganchada por una corrida anterior de la app se apaga.
        boolean hace = escrito == null || escrito != suena || (suena && ahora - escritoMs >= REAFIRMAR_MS);
        if (!hace) return;
        try {
            ModbusTcpConexion p = conexion(c);
            if (suena) {
                p.escribirBit(c.unitId(), dirDesactivar, false); // primero se suelta el reset...
                p.escribirBit(c.unitId(), dirActivar, true);     // ...y después se activa
            } else {
                p.escribirBit(c.unitId(), dirActivar, false);
                p.escribirBit(c.unitId(), dirDesactivar, true);  // el reset gana: el RS se apaga
            }
            boolean leidoA = p.leerBits(c.unitId(), dirActivar, 1)[0];
            boolean leidoD = p.leerBits(c.unitId(), dirDesactivar, 1)[0];
            boolean antes = Boolean.TRUE.equals(escrito);
            boolean primera = escrito == null;
            registrarEscritura(suena, leidoA, leidoD, ahora);
            if (primera || antes != suena) {
                logger.info("Sirena {}: {} ({} = {}, {} = {})", suena ? "ENCENDIDA" : "apagada", suena ? "suena" : "no suena",
                        c.entradaActivar(), suena ? 1 : 0, c.entradaDesactivar(), suena ? 0 : 1);
            }
            if (leidoA != suena || leidoD == suena) {
                logger.warn("Sirena: se escribió {}={} y {}={} y el LOGO! lee {} y {} (revisar la dirección o el programa del LOGO!)",
                        c.entradaActivar(), suena ? 1 : 0, c.entradaDesactivar(), suena ? 0 : 1, leidoA ? 1 : 0, leidoD ? 1 : 0);
            }
            registrarPlc(true, null);
        } catch (ModbusTcpConexion.ExcepcionModbus e) {
            esperarHasta = ahora + ESPERA_SIN_RESPUESTA_MS;
            registrarPlc(false, "el LOGO! contestó con " + e.getMessage() + ": revisar las direcciones " + c.entradaActivar() + " y " + c.entradaDesactivar());
        } catch (IOException e) {
            cerrar();
            esperarHasta = ahora + ESPERA_SIN_RESPUESTA_MS;
            String detalle = e.getMessage() == null ? "" : e.getMessage();
            boolean cerrado = detalle.toLowerCase(java.util.Locale.ROOT).contains("refused");
            registrarPlc(false, (cerrado
                    ? "el LOGO! rechaza la conexión en " + c.ip() + ":" + c.puerto() + ": el puerto está cerrado, el LOGO! no tiene habilitado el servidor Modbus TCP"
                    : "sin respuesta del LOGO! en " + c.ip() + ":" + c.puerto()) + " (" + detalle + ")");
        }
    }

    private synchronized void registrarEscritura(boolean suena, boolean leidoA, boolean leidoD, long ahora) {
        escrito = suena;
        leidoActivar = leidoA;
        leidoDesactivar = leidoD;
        escritoMs = ahora;
    }

    private synchronized void registrarPlc(boolean ok, String error) {
        boolean cambio = ok != plcOk || (error != null && !error.equals(errorPlc));
        plcOk = ok;
        errorPlc = error;
        if (cambio) {
            if (ok) logger.info("Sirena: el LOGO! contesta");
            else logger.warn("Sirena: {}", error);
        }
    }

    private ModbusTcpConexion conexion(ConfigSirena c) {
        String clave = c.ip() + ":" + c.puerto();
        if (plc == null || !clave.equals(plcClave)) {
            cerrar();
            plc = new ModbusTcpConexion(c.ip(), c.puerto(), TIMEOUT_MS);
            plcClave = clave;
        }
        return plc;
    }

    private void cerrar() {
        if (plc != null) {
            plc.close();
            plc = null;
        }
    }

    /** Al cerrar la app la sirena queda apagada: ya no hay quien la vigile. */
    @PreDestroy
    void alCerrar() {
        try {
            if (Boolean.TRUE.equals(escrito)) {
                ConfigSirena c = config();
                Integer dirActivar = direccion(c.entradaActivar()), dirDesactivar = direccion(c.entradaDesactivar());
                if (dirActivar != null && dirDesactivar != null) {
                    ModbusTcpConexion p = conexion(c);
                    p.escribirBit(c.unitId(), dirActivar, false);
                    p.escribirBit(c.unitId(), dirDesactivar, true);
                    logger.info("Sirena apagada al cerrar la app");
                }
            }
        } catch (Exception e) {
            logger.warn("Sirena: no se pudo apagar al cerrar la app ({})", e.getMessage());
        } finally {
            cerrar();
            hilos.shutdownNow();
        }
    }

    // ================= Para la pantalla =================

    public synchronized Estado estado() {
        ConfigSirena c = config();
        long ahora = System.currentTimeMillis();
        boolean enUso = c.habilitada() || ahora < pruebaHasta || Boolean.TRUE.equals(escrito);
        boolean silencio = silencioHasta > ahora;
        // Una causa está silenciada si todas sus alarmas están entre las que se silenciaron.
        boolean silRed = silencio && !clavesRed.isEmpty() && silenciadas.containsAll(clavesRed);
        boolean silPotencia = silencio && !clavesPotencia.isEmpty() && silenciadas.containsAll(clavesPotencia);
        boolean silGenerador = silencio && !clavesGenerador.isEmpty() && silenciadas.containsAll(clavesGenerador);
        return new Estado(c.habilitada(), c.ip(), enUso, plcOk, errorPlc, c.entradaActivar(), c.entradaDesactivar(), !clavesRed.isEmpty(),
                !clavesPotencia.isEmpty(), !clavesGenerador.isEmpty(), silRed, silPotencia, silGenerador, silencio ? silencioHasta : 0,
                c.silencioMinutos(), corteActivo, deseado, ahora < pruebaHasta, motivoRed, motivoPotencia, motivoGenerador,
                leidoActivar, leidoDesactivar);
    }
}
