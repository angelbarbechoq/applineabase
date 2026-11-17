package com.example.backend.lectura;

import com.example.backend.modelo.EstadoDispositivo;
import com.example.backend.modelo.Fila;
import com.example.backend.modelo.ResultadoLectura;
import com.example.backend.modelo.TipoTabla;
import com.example.generador.model.Generador;
import com.example.generador.model.LecturaGenerador;
import com.example.generador.model.ModeloControlador;
import com.example.generador.model.ParametroGenerador;
import com.example.generador.service.GeneradoresConfigService;
import com.example.generador.service.LectorControlador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lectura de los controladores ComAp de los generadores. SOLO LECTURA (función 03, {@link LectorControlador} tal cual
 * de applineabase). Arma la fila de {@code {mes}Generador} con el mismo criterio que GeneradorReaderService y
 * GeneradorAlmacen.guardarLectura: en marcha cada ciclo, parado cada {@link #MINUTOS_PARADO} min; sin RPM o con el
 * mapa sin confirmar no hay fila.
 *
 * Todavía no hace lo que la app calcula a partir de la lectura (arranques, historial de eventos, isla, señales de
 * red, alarmas del controlador): eso sigue en la app principal.
 */
@Service
public class LectorGeneradores {

    private static final Logger logger = LoggerFactory.getLogger(LectorGeneradores.class);
    static final int MINUTOS_PARADO = 15;

    private final GeneradoresConfigService config;
    private final Map<String, LocalDateTime> ultimaFilaParado = new ConcurrentHashMap<>();
    private final Map<String, Boolean> enMarchaAnterior = new ConcurrentHashMap<>();

    public LectorGeneradores(GeneradoresConfigService config) {
        this.config = config;
    }

    public ResultadoLectura leer() {
        ResultadoLectura r = ResultadoLectura.nuevo();
        for (Generador g : config.generadores()) {
            long inicio = System.currentTimeMillis();
            Optional<ModeloControlador> modelo = config.modelo(g.modelo());
            if (modelo.isEmpty()) {
                r.estados().add(new EstadoDispositivo("Generador", g.nombre(), g.ip(), 0, 1,
                        "el modelo '" + g.modelo() + "' no está en generador-modelos.json", 0));
                continue;
            }
            LecturaGenerador l;
            try {
                l = LectorControlador.leer(g.ip(), g.unitId(), modelo.get());
            } catch (Exception e) {
                r.estados().add(new EstadoDispositivo("Generador", g.nombre(), g.ip(), 0, 1,
                        "sin comunicación: " + e.getMessage(), System.currentTimeMillis() - inicio));
                continue;
            }
            long ms = System.currentTimeMillis() - inicio;
            if (!modelo.get().confirmado()) {
                r.estados().add(new EstadoDispositivo("Generador", g.nombre(), g.ip(), 0, 1,
                        "mapa " + g.modelo() + " sin confirmar: no se guarda", ms));
                continue;
            }
            if (l.rpm() == null) {
                r.estados().add(new EstadoDispositivo("Generador", g.nombre(), g.ip(), 0, 1,
                        "lectura sin RPM: no se guarda (" + l.errores().get(ParametroGenerador.RPM) + ")", ms));
                continue;
            }
            String nota = l.errores().isEmpty() ? null : "sin respuesta en " + l.errores().keySet();
            if (debeGuardar(g.nombre(), l)) {
                r.filas().add(fila(g.nombre(), l));
                r.estados().add(new EstadoDispositivo("Generador", g.nombre(), g.ip(), 1, 1, nota, ms));
            } else {
                // Parado y ya guardado hace menos de 15 min: leído bien, sin fila este ciclo (igual que la app).
                r.estados().add(new EstadoDispositivo("Generador", g.nombre(), g.ip(), 1, 1, nota, ms));
            }
        }
        return r;
    }

    private boolean debeGuardar(String nombre, LecturaGenerador l) {
        boolean enMarcha = l.enMarcha();
        Boolean antes = enMarchaAnterior.put(nombre, enMarcha);
        if (enMarcha) return true;
        if (Boolean.TRUE.equals(antes)) ultimaFilaParado.remove(nombre); // recién paró: guardar la primera parado
        LocalDateTime ultima = ultimaFilaParado.get(nombre);
        if (ultima == null || Duration.between(ultima, l.fecha()).toMinutes() >= MINUTOS_PARADO) {
            ultimaFilaParado.put(nombre, l.fecha());
            return true;
        }
        return false;
    }

    /** Mismas columnas que GeneradorAlmacen.guardarLectura: estado y una por parámetro (enteros redondeados). */
    private static Fila fila(String nombre, LecturaGenerador l) {
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("estado", l.enMarcha() ? "MARCHA" : "PARADO");
        for (ParametroGenerador p : ParametroGenerador.values()) {
            Double v = l.valor(p);
            cols.put(p.columna(), v == null ? null : p.entero() ? (Object) Math.round(v) : v);
        }
        return new Fila(TipoTabla.GENERADOR, nombre, l.fecha().withNano(0), cols);
    }
}
