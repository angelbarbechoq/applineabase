package com.example.generador.service;

import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.generador.model.Generador;
import com.example.generador.model.MetaImportacionRed;
import com.example.generador.model.ModeloControlador;
import com.example.generador.model.ParametroGenerador;
import com.example.generador.model.SenalesRed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generadores (generador-config.json) y mapas de registros por modelo de controlador
 * (generador-modelos.json), ambos en C:\LineaBaseX\config. Se leen en cada llamada: un cambio en
 * los archivos vale desde el ciclo siguiente, sin reiniciar ni recompilar.
 */
@Service
public class GeneradoresConfigService {

    private static final Logger logger = LoggerFactory.getLogger(GeneradoresConfigService.class);

    private final ConfigLoaderService configLoaderService;
    /** Errores del archivo ya avisados en el log (para no repetirlos cada minuto). */
    private final Set<String> avisados = ConcurrentHashMap.newKeySet();

    public GeneradoresConfigService(ConfigLoaderService configLoaderService) {
        this.configLoaderService = configLoaderService;
    }

    public List<Generador> generadores() {
        List<Generador> lista = new ArrayList<>();
        for (Map<String, Object> g : configLoaderService.loadGeneradoresConfig()) {
            lista.add(new Generador(String.valueOf(g.get("nombre")),
                    g.get("modelo") == null ? "" : String.valueOf(g.get("modelo")),
                    String.valueOf(g.get("ipAddress")),
                    g.get("unitId") instanceof Number n ? n.intValue() : 1,
                    texto(g.get("redAsociada")),
                    g.get("descripcion") == null ? "" : String.valueOf(g.get("descripcion"))));
        }
        return lista;
    }

    /**
     * Límite de potencia del generador para la alarma de potencia alta (generador-config.json, "alarmaPotenciaKw" del
     * generador, en kW). null = sin alarma (falta o no es positivo).
     */
    public Double limitePotenciaKw(String generador) {
        for (Map<String, Object> g : configLoaderService.loadGeneradoresConfig()) {
            if (generador.equals(String.valueOf(g.get("nombre")))) {
                return g.get("alarmaPotenciaKw") instanceof Number n && n.doubleValue() > 0 ? n.doubleValue() : null;
            }
        }
        return null;
    }

    /**
     * Semáforo de la meta "consumo cero de la red" (generador-config.json, "metaImportacionRed": verdeHasta y
     * amarilloHasta en % del consumo). Si falta o no tiene sentido (no 0 &lt;= verde &lt; amarillo &lt;= 100), 2 % y 10 %.
     */
    public MetaImportacionRed metaImportacionRed() {
        Map<String, Object> m = configLoaderService.loadGeneradorMetaImportacionRed();
        if (m.isEmpty()) return MetaImportacionRed.POR_DEFECTO;
        if (m.get("verdeHasta") instanceof Number v && m.get("amarilloHasta") instanceof Number a
                && v.doubleValue() >= 0 && v.doubleValue() < a.doubleValue() && a.doubleValue() <= 100) {
            return new MetaImportacionRed(v.doubleValue(), a.doubleValue());
        }
        if (avisados.add("metaImportacionRed|" + m)) {
            logger.warn("generador-config.json: metaImportacionRed {} no es valida (verdeHasta < amarilloHasta, 0-100); se usa 2 y 10", m);
        }
        return MetaImportacionRed.POR_DEFECTO;
    }

    /**
     * Guarda los límites del semáforo de la meta en generador-config.json (lo hace el administrador desde Análisis >
     * Períodos en marcha). Mismas reglas que al leerlo: 0 &lt;= verde &lt; amarillo &lt;= 100.
     */
    public void guardarMetaImportacionRed(double verdeHasta, double amarilloHasta) {
        if (!(verdeHasta >= 0 && verdeHasta < amarilloHasta && amarilloHasta <= 100)) {
            throw new IllegalArgumentException("El límite verde debe ser menor que el amarillo, entre 0 y 100 %");
        }
        configLoaderService.saveGeneradorMetaImportacionRed(verdeHasta, amarilloHasta);
    }

    public Optional<Generador> generador(String nombre) {
        return generadores().stream().filter(g -> g.nombre().equals(nombre)).findFirst();
    }

    public Optional<ModeloControlador> modelo(String nombre) {
        return modelos().stream().filter(m -> m.modelo().equalsIgnoreCase(nombre)).findFirst();
    }

    public List<ModeloControlador> modelos() {
        List<ModeloControlador> lista = new ArrayList<>();
        for (Map<String, Object> m : configLoaderService.loadGeneradorModelosConfig()) {
            String modelo = String.valueOf(m.get("modelo"));
            List<ModeloControlador.Registro> registros = new ArrayList<>();
            if (m.get("registros") instanceof List<?> regs) {
                for (Object o : regs) {
                    if (o instanceof Map<?, ?> r) {
                        ModeloControlador.Registro reg = registro(modelo, r);
                        if (reg != null) registros.add(reg);
                    }
                }
            }
            ModeloControlador.ListaAlarmas alarmas = null;
            if (m.get("listaAlarmas") instanceof Map<?, ?> la && la.get("cantidad") instanceof Number cant
                    && la.get("primerRegistro") instanceof Number primero) {
                int largo = la.get("largoRegistro") instanceof Number l ? l.intValue() : 27;
                int maximo = la.get("maximo") instanceof Number mx ? mx.intValue() : 16;
                alarmas = new ModeloControlador.ListaAlarmas(cant.intValue(), primero.intValue(), largo, maximo);
            }
            lista.add(new ModeloControlador(modelo, texto(m.get("descripcion")),
                    Boolean.TRUE.equals(m.get("confirmado")),
                    m.get("maxHueco") instanceof Number n ? Math.max(0, n.intValue()) : 0,
                    !Boolean.FALSE.equals(m.get("noDisponible8000")),
                    List.copyOf(registros), alarmas,
                    m.get("reloj") instanceof Number reloj ? reloj.intValue() : null,
                    m.get("red") instanceof Map<?, ?> red ? senalesRed(modelo, red) : null, textos(m.get("modos")),
                    textos(m.get("estadosMotor")), textos(m.get("entradas")), textos(m.get("salidas")),
                    bit(modelo, m, "listoAmf"), textos(m.get("temporizadores")), tiempos(modelo, m.get("tiemposAjustados"))));
        }
        return lista;
    }

    /**
     * Campo "tiemposAjustados": por nombre de temporizador, los segundos fijos (copiados de los ajustes del controlador)
     * o {"parametro": "AJUSTE_..."} para leerlo del controlador. Lo que esté mal se ignora con aviso en el log.
     */
    private Map<String, ModeloControlador.TiempoAjustado> tiempos(String modelo, Object o) {
        Map<String, ModeloControlador.TiempoAjustado> t = new java.util.HashMap<>();
        if (!(o instanceof Map<?, ?> mapa)) return Map.of();
        for (Map.Entry<?, ?> e : mapa.entrySet()) {
            String nombre = String.valueOf(e.getKey());
            try {
                if (e.getValue() instanceof Number n) {
                    t.put(nombre, new ModeloControlador.TiempoAjustado(n.doubleValue(), null));
                } else if (e.getValue() instanceof Map<?, ?> r) {
                    t.put(nombre, new ModeloControlador.TiempoAjustado(null,
                            ParametroGenerador.valueOf(String.valueOf(r.get("parametro")).trim().toUpperCase(Locale.ROOT))));
                }
            } catch (Exception ex) {
                if (avisados.add(modelo + "|tiempo|" + nombre)) {
                    logger.warn("generador-modelos.json, modelo {}: tiempo ajustado {} ignorado ({})", modelo, nombre, ex.getMessage());
                }
            }
        }
        return Map.copyOf(t);
    }

    /** Lista de nombres del JSON (vacía si falta). */
    private static List<String> textos(Object o) {
        List<String> l = new ArrayList<>();
        if (o instanceof List<?> lista) for (Object x : lista) l.add(String.valueOf(x));
        return List.copyOf(l);
    }

    /**
     * Campo "red" del modelo: "estado" con los códigos de cada caso (paralelo, isla, fallaRed, retornoRed) y sus
     * "nombres", y los bits mcbCerrado, gcbCerrado, avisoFallaRed, proteccionRed, saltoVector, rocof y forzarIsla como
     * {"parametro": ..., "bit": ...}, y los ajustes de disparo limiteSaltoVector (grados) y limiteRocof (Hz/s). Lo que
     * falte o esté mal queda vacío (con aviso en el log).
     */
    private SenalesRed senalesRed(String modelo, Map<?, ?> r) {
        Map<?, ?> estado = r.get("estado") instanceof Map<?, ?> e ? e : Map.of();
        List<String> nombres = new ArrayList<>();
        if (estado.get("nombres") instanceof List<?> l) for (Object o : l) nombres.add(String.valueOf(o));
        return new SenalesRed(codigos(estado.get("paralelo")), codigos(estado.get("isla")), codigos(estado.get("fallaRed")),
                codigos(estado.get("retornoRed")), List.copyOf(nombres), bit(modelo, r, "mcbCerrado"), bit(modelo, r, "gcbCerrado"),
                bit(modelo, r, "avisoFallaRed"), bit(modelo, r, "proteccionRed"), bit(modelo, r, "saltoVector"), bit(modelo, r, "rocof"),
                bit(modelo, r, "forzarIsla"),
                limite(r.get("limiteSaltoVector")), limite(r.get("limiteRocof")));
    }

    /** Ajuste de disparo (limiteSaltoVector, limiteRocof); null si falta o no es positivo. */
    private static Double limite(Object o) {
        return o instanceof Number n && n.doubleValue() > 0 ? n.doubleValue() : null;
    }

    private static Set<Integer> codigos(Object o) {
        Set<Integer> s = new java.util.HashSet<>();
        if (o instanceof List<?> l) for (Object x : l) if (x instanceof Number n) s.add(n.intValue());
        return Set.copyOf(s);
    }

    private SenalesRed.Bit bit(String modelo, Map<?, ?> r, String nombre) {
        if (!(r.get(nombre) instanceof Map<?, ?> b)) return null;
        try {
            ParametroGenerador p = ParametroGenerador.valueOf(String.valueOf(b.get("parametro")).trim().toUpperCase(Locale.ROOT));
            int bit = ((Number) b.get("bit")).intValue();
            if (bit < 0 || bit > 31) throw new IllegalArgumentException("bit " + bit + " fuera de 0-31");
            return new SenalesRed.Bit(p, bit);
        } catch (Exception e) {
            if (avisados.add(modelo + "|red|" + nombre)) {
                logger.warn("generador-modelos.json, modelo {}: senal de red {} ignorada {} ({})", modelo, nombre, b, e.getMessage());
            }
            return null;
        }
    }

    private ModeloControlador.Registro registro(String modelo, Map<?, ?> r) {
        try {
            ParametroGenerador p = ParametroGenerador.valueOf(String.valueOf(r.get("parametro")).trim().toUpperCase(Locale.ROOT));
            int registro = ((Number) r.get("registro")).intValue();
            if (registro < 40001 || registro > 105536) {
                throw new IllegalArgumentException("registro " + registro + " fuera de 40001-105536");
            }
            ModeloControlador.Tipo tipo = ModeloControlador.Tipo.valueOf(
                    String.valueOf(r.get("tipo") == null ? "UINT16" : r.get("tipo")).trim().toUpperCase(Locale.ROOT));
            double divisor = r.get("divisor") instanceof Number n && n.doubleValue() != 0 ? n.doubleValue() : 1.0;
            boolean bajaPrimero = "BAJA_PRIMERO".equalsIgnoreCase(String.valueOf(r.get("ordenPalabras")));
            return new ModeloControlador.Registro(p, registro, tipo, divisor, bajaPrimero);
        } catch (Exception e) {
            String clave = modelo + "|" + r;
            if (avisados.add(clave)) {
                logger.warn("generador-modelos.json, modelo {}: registro ignorado {} ({})", modelo, r, e.getMessage());
            }
            return null;
        }
    }

    private static String texto(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }
}
