package com.example.generador.service;

import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.generador.model.Generador;
import com.example.generador.model.MetaImportacionRed;
import com.example.generador.model.ModeloControlador;
import com.example.generador.model.ParametroGenerador;
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
                    m.get("reloj") instanceof Number reloj ? reloj.intValue() : null));
        }
        return lista;
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
