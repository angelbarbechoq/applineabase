package com.example.generador.service;

import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.generador.model.Generador;
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
            lista.add(new ModeloControlador(modelo, texto(m.get("descripcion")),
                    Boolean.TRUE.equals(m.get("confirmado")),
                    m.get("maxHueco") instanceof Number n ? Math.max(0, n.intValue()) : 0,
                    !Boolean.FALSE.equals(m.get("noDisponible8000")),
                    List.copyOf(registros)));
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
