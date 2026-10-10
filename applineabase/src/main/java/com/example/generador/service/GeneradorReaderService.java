package com.example.generador.service;

import com.example.generador.model.Generador;
import com.example.generador.model.LecturaGenerador;
import com.example.generador.model.ModeloControlador;
import com.example.generador.model.ParametroGenerador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lectura de los generadores en el ciclo de adquisición (fase G del plan, mantenimiento basado en
 * condición). **SOLO LECTURA**: únicamente función 03 (ver {@link LectorControlador}).
 *
 * Cada generador se lee con el mapa de su modelo (generador-modelos.json). Guardado según estado:
 * en marcha cada ciclo (1 min); parado cada {@link #MINUTOS_PARADO} min (batería, refrigerante y
 * contadores: lo que dice si está listo para arrancar); y un registro por arranque. Con un mapa
 * sin confirmar no se guarda nada (solo se ve en vivo en la pantalla, para verificarlo).
 */
@Service
public class GeneradorReaderService {

    private static final Logger logger = LoggerFactory.getLogger(GeneradorReaderService.class);
    static final int MINUTOS_PARADO = 15;

    private final GeneradoresConfigService config;
    private final GeneradorAlmacen almacen;
    private final HistorialEventosService historial;

    /** Último estado conocido (true = en marcha) y última lectura guardada estando parado. */
    private final Map<String, Boolean> enMarchaAnterior = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> ultimoGuardadoParado = new ConcurrentHashMap<>();
    /** Comunicación por generador: el log avisa solo cuando cambia (no cada minuto). */
    private final Map<String, Boolean> comunicando = new ConcurrentHashMap<>();
    private final Set<String> avisados = ConcurrentHashMap.newKeySet();

    public GeneradorReaderService(GeneradoresConfigService config, GeneradorAlmacen almacen, HistorialEventosService historial) {
        this.config = config;
        this.almacen = almacen;
        this.historial = historial;
    }

    public void leerGeneradores() {
        for (Generador g : config.generadores()) {
            Optional<ModeloControlador> modelo = config.modelo(g.modelo());
            if (modelo.isEmpty()) {
                avisarUnaVez(g.nombre() + "|modelo", "Generador {}: el modelo '{}' no esta en generador-modelos.json, no se lee",
                        g.nombre(), g.modelo());
                continue;
            }
            LecturaGenerador l;
            try {
                l = LectorControlador.leer(g.ip(), g.unitId(), modelo.get());
            } catch (IOException e) {
                if (!Boolean.FALSE.equals(comunicando.put(g.nombre(), false))) {
                    logger.warn("Generador {} ({}) sin comunicacion: {}", g.nombre(), g.ip(), e.getMessage());
                }
                if (modelo.get().confirmado()) historial.registrarSinComunicacion(g.nombre(), e.getMessage());
                continue;
            } catch (Exception e) {
                logger.error("Error leyendo generador {}: {}", g.nombre(), e.getMessage(), e);
                continue;
            }
            if (!Boolean.TRUE.equals(comunicando.put(g.nombre(), true))) {
                logger.info("Generador {} ({}) comunicando{}", g.nombre(), g.ip(),
                        l.errores().isEmpty() ? "" : "; sin respuesta en " + l.errores().keySet());
            }
            if (!modelo.get().confirmado()) {
                avisarUnaVez(g.nombre() + "|confirmar", "Generador {}: mapa {} sin confirmar, no se guarda historial hasta confirmarlo",
                        g.nombre(), g.modelo());
                continue;
            }
            if (l.rpm() == null) {
                // Sin RPM no se sabe si está en marcha: no abrir/cerrar arranques con un dato faltante.
                logger.warn("Generador {}: lectura sin RPM ({}), no se guarda", g.nombre(), l.errores().get(ParametroGenerador.RPM));
                continue;
            }
            procesar(g.nombre(), l);
            try {
                historial.registrarLectura(g.nombre(), l);
            } catch (Exception e) {
                logger.error("Generador {}: error en el historial de eventos: {}", g.nombre(), e.getMessage(), e);
            }
        }
    }

    private void avisarUnaVez(String clave, String mensaje, Object... args) {
        if (avisados.add(clave)) {
            logger.warn(mensaje, args);
        }
    }

    void procesar(String nombre, LecturaGenerador l) {
        boolean enMarcha = l.enMarcha();
        Boolean antes = enMarchaAnterior.put(nombre, enMarcha);

        if (enMarcha && !Boolean.TRUE.equals(antes)) {
            // Arranque (o la app empezó con el generador ya en marcha: inicio estimado).
            if (antes == null && almacen.hayArranqueAbierto(nombre)) {
                logger.info("Generador {}: sigue en marcha, se continua el arranque abierto", nombre);
            } else {
                almacen.abrirArranque(nombre, l, antes == null);
            }
        } else if (!enMarcha && Boolean.TRUE.equals(antes)) {
            almacen.cerrarArranque(nombre, l);
            ultimoGuardadoParado.remove(nombre); // guardar la primera lectura ya parado
        } else if (!enMarcha && antes == null) {
            // App recién iniciada con el generador parado: cerrar un arranque que quedó abierto.
            almacen.cerrarArranque(nombre, l);
        }

        if (enMarcha) {
            almacen.guardarLectura(nombre, l);
            almacen.actualizarKwMax(nombre, l.kw());
            return;
        }
        LocalDateTime ultimo = ultimoGuardadoParado.get(nombre);
        if (ultimo == null || Duration.between(ultimo, l.fecha()).toMinutes() >= MINUTOS_PARADO) {
            almacen.guardarLectura(nombre, l);
            ultimoGuardadoParado.put(nombre, l.fecha());
        }
    }
}
