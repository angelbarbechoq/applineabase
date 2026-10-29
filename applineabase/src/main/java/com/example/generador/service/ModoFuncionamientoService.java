package com.example.generador.service;

import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.generador.model.EventoGenerador;
import com.example.generador.model.LecturaGenerador;
import com.example.generador.model.SenalesRed;
import com.example.generador.model.SenalesRed.Estado;
import com.example.generador.model.SenalesRed.Selector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.generador.model.TipoEventoGenerador.*;

/**
 * Modo de funcionamiento según el selector de tres posiciones del tablero de cada generador (pedido del usuario el
 * 28-10-2026): AMF (ninguna entrada), Sincronismo (Remote Start/Stop) e Isla (Force Island). El modo ComAp queda siempre
 * en AUTO; el selector actúa por esas entradas binarias. Cada lectura de fondo:
 * - historial: cada cambio de posición del selector;
 * - verifica que el controlador haga lo que pide el selector y, si no lo hace por más de {@link #MINUTOS_MODO} min,
 *   abre la alarma "No cumple el modo del selector" (no urgente) con su evento; se cierra sola al cumplir:
 *   Sincronismo = en marcha y en paralelo con la red; Isla = en marcha con el interruptor de la planta abierto;
 *   AMF = parado y listo para entrar solo. Sin red según el controlador no se pide paralelo ni que esté parado: es la
 *   falla de red (o el disparo), que avisa la alarma de pérdida de red.
 * Solo lectura del controlador.
 */
@Service
public class ModoFuncionamientoService {

    private static final Logger logger = LoggerFactory.getLogger(ModoFuncionamientoService.class);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Minutos que puede durar la diferencia antes de avisar: arrancar y sincronizar tarda unos 3 min, y parar (descarga y
     * enfriamiento de 120 s) entre 3 y 4 min (25 al 27-10-2026).
     */
    static final int MINUTOS_MODO = 5;

    private static final class Previo {
        Selector selector;
        LocalDateTime desdeNoCumple;
    }

    private final Map<String, Previo> previos = new ConcurrentHashMap<>();
    private final HistorialEventosService historial;
    private final AlarmaEventoRepository eventoRepository;

    public ModoFuncionamientoService(HistorialEventosService historial, AlarmaEventoRepository eventoRepository) {
        this.historial = historial;
        this.eventoRepository = eventoRepository;
    }

    /** Posición del selector en la última lectura de fondo (null = sin dato o el modelo no tiene las entradas). */
    public Selector selector(String generador) {
        Previo p = previos.get(generador);
        return p == null ? null : p.selector;
    }

    /**
     * @param listoAmf Ready To AMF del controlador (null si el modelo no lo da)
     */
    public void procesar(String generador, SenalesRed red, LecturaGenerador l, Boolean listoAmf) {
        Selector selector = red.selector(l);
        if (selector == null) return;
        LocalDateTime t = l.fecha();
        Integer codigo = SenalesRed.codigo(l);
        String nombre = red.nombre(codigo);
        List<EventoGenerador> eventos = new ArrayList<>();
        Previo p = previos.computeIfAbsent(generador, k -> new Previo());
        if (p.selector != null && selector != p.selector) {
            eventos.add(EventoGenerador.de(t, CAMBIO_SELECTOR, "El selector pasa de " + p.selector.etiqueta() + " a "
                    + selector.etiqueta() + "; estado " + nombre, l));
            p.desdeNoCumple = null; // el plazo se cuenta desde el cambio
        }
        p.selector = selector;

        String falla = verificar(selector, red.estado(codigo), nombre, SenalesRed.valor(red.mcbCerrado(), l), l.enMarcha(), listoAmf);
        List<AlarmaEvento> abiertas = eventoRepository.findByLineaMaquinaAndTipoAlarmaAndActivaTrue(generador, TipoAlarma.MODO_GENERADOR);
        AlarmaEvento abierta = abiertas.isEmpty() ? null : abiertas.get(0);
        if (falla == null) {
            p.desdeNoCumple = null;
            if (abierta != null) {
                long min = minutos(abierta.getFechaInicio(), t);
                abierta.setMensaje(corto(abierta.getMensaje() + " (resuelta: cumple el modo tras " + min + " min)"));
                abierta.setActiva(false);
                abierta.setFechaFin(t);
                eventoRepository.save(abierta);
                logger.info("Alarma resuelta: {}", abierta.getMensaje());
                eventos.add(EventoGenerador.de(t, MODO_CUMPLE, "Cumple el modo del selector en " + selector.etiqueta()
                        + " (estado " + nombre + ") tras " + min + " min", l));
            }
        } else {
            if (p.desdeNoCumple == null) p.desdeNoCumple = t;
            String mensaje = corto("Generador " + generador + ": " + falla + ", desde las " + p.desdeNoCumple.format(HHMM));
            if (abierta == null && minutos(p.desdeNoCumple, t) > MINUTOS_MODO) {
                AlarmaEvento ev = new AlarmaEvento();
                ev.setLineaMaquina(generador);
                ev.setTipoAlarma(TipoAlarma.MODO_GENERADOR);
                ev.setMensaje(mensaje);
                // Hora de guardado: el aviso del navegador busca las alarmas con inicio posterior a su última revisión.
                ev.setFechaInicio(LocalDateTime.now());
                ev.setActiva(true);
                ev.setUrgente(false);
                eventoRepository.save(ev);
                logger.warn("Alarma: {}", mensaje);
                eventos.add(EventoGenerador.de(t, MODO_NO_CUMPLE, falla + " desde las " + p.desdeNoCumple.format(HHMM), l));
            } else if (abierta != null && !mensaje.equals(abierta.getMensaje())) {
                abierta.setMensaje(mensaje);
                eventoRepository.save(abierta);
            }
        }
        historial.registrar(generador, eventos);
    }

    /** Qué no cumple el controlador con el selector en esa posición; null = cumple. */
    static String verificar(Selector selector, Estado estado, String nombre, Boolean mcbCerrado, boolean marcha, Boolean listoAmf) {
        boolean sinRed = estado != null && SenalesRed.sinRed(estado);
        return switch (selector) {
            case SINCRONISMO -> sinRed ? null
                    : !marcha ? "con el selector en Sincronismo el generador no está en marcha"
                    : estado != null && estado != Estado.PARALELO
                    ? "con el selector en Sincronismo no está en paralelo con la red (estado " + nombre + ")" : null;
            case ISLA -> !marcha ? "con el selector en Isla el generador no está en marcha"
                    : Boolean.TRUE.equals(mcbCerrado) ? "con el selector en Isla el interruptor de la planta sigue cerrado" : null;
            case AMF -> sinRed ? null
                    : marcha ? "con el selector en AMF el generador sigue en marcha sin falla de red"
                    : Boolean.FALSE.equals(listoAmf) ? "con el selector en AMF el generador no está listo para entrar solo" : null;
        };
    }

    private static long minutos(LocalDateTime desde, LocalDateTime hasta) {
        return desde == null || hasta == null ? 0 : Math.max(0, Duration.between(desde, hasta).toMinutes());
    }

    private static String corto(String s) {
        return s.length() <= 255 ? s : s.substring(0, 252) + "...";
    }
}
