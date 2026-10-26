package com.example.generador.service;

import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.alarmas.service.AlarmaTrayNotifierService;
import com.example.generador.model.EventoGenerador;
import com.example.generador.model.LecturaGenerador;
import com.example.generador.model.ParametroGenerador;
import com.example.generador.model.SenalesRed;
import com.example.generador.model.SenalesRed.Estado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.generador.model.TipoEventoGenerador.*;

/**
 * Red e interruptores según el propio controlador (modelos con "red" en generador-modelos.json; hoy el InteliGen 500
 * G2 del Caterpillar y el InteliGen 200 del GenPower, este sin ROCOF ni salto de vector). Cada lectura de fondo (1 por minuto) se compara con la anterior:
 * - historial de eventos: falla de red, paso a isla, retorno de red y vuelta a paralelo (estado de los interruptores),
 *   apertura y cierre del interruptor de la planta (MCB Feedback), ROCOF o salto de vector vistos activos y la suba
 *   de los máximos que guarda el controlador;
 * - alarma "Pérdida de red" (Alarmas Activas, aviso del navegador y globo de Windows en la PC de la app): abierta
 *   mientras el controlador esté sin red, se cierra sola al volver.
 * Las protecciones de red actúan en menos de 1 s: con una lectura por minuto casi nunca se ven activas, por eso se
 * anotan los máximos. Todavía no se sabe cuándo el controlador reinicia esos máximos (se verá en el primer corte).
 * Solo lectura del controlador. Con estas señales la isla no se busca comparando con el medidor del transformador
 * (IslaService la toma de lo guardado por el controlador).
 */
@Service
public class RedControladorService {

    private static final Logger logger = LoggerFactory.getLogger(RedControladorService.class);

    /** Estado de los interruptores según la última lectura y desde cuándo está así (null si el modelo no lo da). */
    public record EstadoActual(Estado estado, String nombre, LocalDateTime desde) {
    }

    /** Lo visto en la lectura anterior de cada generador. */
    private static final class Previo {
        Estado estado;
        String nombre;
        LocalDateTime desde;
        Boolean mcb;
        Double rocofMax, saltoVectorMax;
        boolean rocofActiva, saltoVectorActivo;
    }

    private final Map<String, Previo> previos = new ConcurrentHashMap<>();
    private final HistorialEventosService historial;
    private final AlarmaEventoRepository eventoRepository;
    private final AlarmaTrayNotifierService trayNotifier;

    public RedControladorService(HistorialEventosService historial, AlarmaEventoRepository eventoRepository,
                                 AlarmaTrayNotifierService trayNotifier) {
        this.historial = historial;
        this.eventoRepository = eventoRepository;
        this.trayNotifier = trayNotifier;
    }

    /** Estado de los interruptores del generador según la última lectura de fondo (null = todavía no se leyó). */
    public EstadoActual estadoActual(String generador) {
        Previo p = previos.get(generador);
        return p == null || p.estado == null ? null : new EstadoActual(p.estado, p.nombre, p.desde);
    }

    /** Compara la lectura con la anterior: eventos de red y alarma de pérdida de red. */
    public void procesar(String generador, SenalesRed red, LecturaGenerador l) {
        LocalDateTime t = l.fecha();
        Integer codigo = SenalesRed.codigo(l);
        Estado estado = red.estado(codigo);
        String nombre = red.nombre(codigo);
        Boolean mcb = SenalesRed.valor(red.mcbCerrado(), l);
        boolean rocofActiva = Boolean.TRUE.equals(SenalesRed.valor(red.rocof(), l));
        boolean saltoActivo = Boolean.TRUE.equals(SenalesRed.valor(red.saltoVector(), l));
        Double rocofMax = l.valor(ParametroGenerador.ROCOF_MAX), saltoMax = l.valor(ParametroGenerador.SALTO_VECTOR_MAX);
        Boolean sinRed = red.sinRed(l);

        List<EventoGenerador> eventos = new ArrayList<>();
        Previo p = previos.get(generador);
        AlarmaEvento abierta = alarmaAbierta(generador);
        if (p == null) {
            p = new Previo();
            previos.put(generador, p);
            // Al iniciar la lectura: si ya estaba sin red, se anota (y la alarma abierta de antes da la hora de comienzo).
            p.desde = Boolean.TRUE.equals(sinRed) && abierta != null ? abierta.getFechaInicio() : t;
            if (estado != null && SenalesRed.sinRed(estado)) {
                eventos.add(EventoGenerador.de(t, tipoDe(estado), textoEstado(estado, nombre) + detalle(red, l)
                        + " (ya estaba al iniciar la lectura)", l));
            }
        } else {
            if (estado != null && p.estado != null && estado != p.estado) {
                EventoGenerador ev = cambioDeEstado(red, l, p, estado, nombre);
                if (ev != null) eventos.add(ev);
            }
            if (estado != null && estado != p.estado) p.desde = t;
            if (mcb != null && p.mcb != null && !mcb.equals(p.mcb)) {
                eventos.add(EventoGenerador.de(t, mcb ? INTERRUPTOR_RED_CERRADO : INTERRUPTOR_RED_ABIERTO, mcb
                        ? "El controlador ve cerrado el interruptor de la planta (MCB Feedback); estado " + nombre
                        : "El controlador ve abierto el interruptor de la planta (MCB Feedback); estado " + nombre + detalle(red, l), l));
            }
            if (rocofActiva && !p.rocofActiva) {
                eventos.add(EventoGenerador.de(t, PROTECCION_RED, "ROCOF activa en la lectura" + rocof(l), l));
            }
            if (saltoActivo && !p.saltoVectorActivo) {
                eventos.add(EventoGenerador.de(t, PROTECCION_RED, "Salto de vector activo en la lectura" + salto(l), l));
            }
            if (rocofMax != null && p.rocofMax != null && rocofMax > p.rocofMax) {
                eventos.add(EventoGenerador.de(t, MAXIMO_RED, String.format(Locale.ROOT,
                        "El controlador registro una ROCOF maxima de %.2f Hz/s (antes %.2f)", rocofMax, p.rocofMax), l));
            }
            if (saltoMax != null && p.saltoVectorMax != null && saltoMax > p.saltoVectorMax) {
                eventos.add(EventoGenerador.de(t, MAXIMO_RED, String.format(Locale.ROOT,
                        "El controlador registro un salto de vector maximo de %.1f grados (antes %.1f)", saltoMax, p.saltoVectorMax), l));
            }
        }
        if (estado != null) {
            p.estado = estado;
            p.nombre = nombre;
        }
        if (mcb != null) p.mcb = mcb;
        p.rocofActiva = rocofActiva;
        p.saltoVectorActivo = saltoActivo;
        if (rocofMax != null) p.rocofMax = rocofMax;
        if (saltoMax != null) p.saltoVectorMax = saltoMax;
        historial.registrar(generador, eventos);
        if (sinRed != null) sincronizarAlarma(generador, abierta, sinRed, red, l, estado, nombre);
    }

    /** Evento por el cambio de estado de los interruptores; null si no interesa (arranque o parada normal). */
    private static EventoGenerador cambioDeEstado(SenalesRed red, LecturaGenerador l, Previo p, Estado estado, String nombre) {
        LocalDateTime t = l.fecha();
        if (SenalesRed.sinRed(estado)) {
            return EventoGenerador.de(t, tipoDe(estado), textoEstado(estado, nombre) + detalle(red, l), l);
        }
        if (!SenalesRed.sinRed(p.estado)) return null;
        String tras = " tras " + duracion(p.desde, t) + " sin red";
        return estado == Estado.PARALELO
                ? EventoGenerador.de(t, FIN_ISLA, "Vuelve a paralelo con la red segun el controlador (" + nombre + ")" + tras, l)
                : EventoGenerador.de(t, RETORNO_RED, "La red ya no figura en falla segun el controlador: estado " + nombre + tras, l);
    }

    private static com.example.generador.model.TipoEventoGenerador tipoDe(Estado e) {
        return switch (e) {
            case ISLA -> ISLA;
            case RETORNO_RED -> RETORNO_RED;
            default -> FALLA_RED;
        };
    }

    private static String textoEstado(Estado e, String nombre) {
        return switch (e) {
            case ISLA -> "El controlador esta en isla (" + nombre + "): el interruptor de la planta esta abierto y el generador "
                    + "alimenta solo su parte de la planta";
            case RETORNO_RED -> "Retorno de red segun el controlador (" + nombre + "): espera o sincroniza para volver a paralelo";
            default -> "Falla de red segun el controlador (" + nombre + ")";
        };
    }

    /** Lo que se sabe además: interruptores, avisos de red y máximos del controlador. */
    private static String detalle(SenalesRed red, LecturaGenerador l) {
        StringBuilder sb = new StringBuilder();
        Boolean mcb = SenalesRed.valor(red.mcbCerrado(), l), gcb = SenalesRed.valor(red.gcbCerrado(), l);
        if (mcb != null) sb.append("; interruptor de la planta ").append(mcb ? "cerrado" : "abierto");
        if (gcb != null) sb.append("; generador ").append(gcb ? "conectado" : "desconectado");
        if (Boolean.TRUE.equals(SenalesRed.valor(red.avisoFallaRed(), l))) sb.append("; aviso de falla de red activo");
        if (Boolean.TRUE.equals(SenalesRed.valor(red.proteccionRed(), l))) sb.append("; proteccion de red activa");
        if (Boolean.TRUE.equals(SenalesRed.valor(red.rocof(), l))) sb.append("; ROCOF activa");
        if (Boolean.TRUE.equals(SenalesRed.valor(red.saltoVector(), l))) sb.append("; salto de vector activo");
        Double rocofMax = l.valor(ParametroGenerador.ROCOF_MAX), saltoMax = l.valor(ParametroGenerador.SALTO_VECTOR_MAX);
        if (rocofMax != null) sb.append(String.format(Locale.ROOT, "; ROCOF maxima registrada %.2f Hz/s", rocofMax));
        if (saltoMax != null) sb.append(String.format(Locale.ROOT, "; salto de vector maximo %.1f grados", saltoMax));
        return sb.toString();
    }

    private static String rocof(LecturaGenerador l) {
        Double v = l.valor(ParametroGenerador.ROCOF), max = l.valor(ParametroGenerador.ROCOF_MAX);
        return (v == null ? "" : String.format(Locale.ROOT, " (%.2f Hz/s", v) + (max == null ? ")" : String.format(Locale.ROOT, ", maxima %.2f)", max)));
    }

    private static String salto(LecturaGenerador l) {
        Double max = l.valor(ParametroGenerador.SALTO_VECTOR_MAX);
        return max == null ? "" : String.format(Locale.ROOT, " (maximo %.1f grados)", max);
    }

    // ================= Alarma de pérdida de red =================

    private AlarmaEvento alarmaAbierta(String generador) {
        List<AlarmaEvento> abiertas = eventoRepository.findByLineaMaquinaAndTipoAlarmaAndActivaTrue(generador, TipoAlarma.PERDIDA_RED);
        return abiertas.isEmpty() ? null : abiertas.get(0);
    }

    /**
     * Una alarma abierta mientras el controlador esté sin red; el mensaje sigue al estado (falla, isla, retorno) y se
     * cierra al volver. Si la app se reinició con la alarma abierta, se sigue con la misma.
     */
    private void sincronizarAlarma(String generador, AlarmaEvento abierta, boolean sinRed, SenalesRed red, LecturaGenerador l,
                                   Estado estado, String nombre) {
        if (sinRed) {
            // Corto: el mensaje de la alarma tiene lugar para 255 caracteres (el detalle completo va al historial).
            Boolean mcb = SenalesRed.valor(red.mcbCerrado(), l);
            String mensaje = corto("Generador " + generador + ": perdida de red, " + switch (estado == null ? Estado.ISLA : estado) {
                case ISLA -> "el generador alimenta solo su parte de la planta";
                case RETORNO_RED -> "volvio la red y el controlador espera para volver a paralelo";
                default -> "el controlador indica falla de red";
            } + (estado == null ? "" : " (" + nombre + ")")
                    + (mcb == null ? "" : "; interruptor de la planta " + (mcb ? "cerrado" : "abierto")));
            if (abierta == null) {
                AlarmaEvento ev = new AlarmaEvento();
                ev.setLineaMaquina(generador);
                ev.setTipoAlarma(TipoAlarma.PERDIDA_RED);
                ev.setMensaje(mensaje);
                // Hora de guardado: el aviso del navegador busca las alarmas con inicio posterior a su última revisión.
                ev.setFechaInicio(LocalDateTime.now());
                ev.setActiva(true);
                ev.setUrgente(true);
                eventoRepository.save(ev);
                logger.warn("Alarma: {}", mensaje);
                trayNotifier.notificarUrgente(generador, mensaje);
            } else if (!mensaje.equals(abierta.getMensaje())) {
                abierta.setMensaje(mensaje);
                eventoRepository.save(abierta);
            }
        } else if (abierta != null) {
            abierta.setMensaje(corto("Generador " + generador + ": perdida de red (resuelta: "
                    + (estado == Estado.PARALELO ? "vuelve a paralelo con la red" : "la red ya no figura en falla, estado " + nombre)
                    + ", tras " + duracion(abierta.getFechaInicio(), l.fecha()) + ")"));
            abierta.setActiva(false);
            abierta.setFechaFin(l.fecha());
            eventoRepository.save(abierta);
            logger.info("Alarma resuelta: {}", abierta.getMensaje());
        }
    }

    private static String corto(String s) {
        return s.length() <= 255 ? s : s.substring(0, 252) + "...";
    }

    private static String duracion(LocalDateTime desde, LocalDateTime hasta) {
        if (desde == null || hasta == null) return "-";
        long min = Math.max(0, Math.round(Duration.between(desde, hasta).toSeconds() / 60.0));
        return min < 60 ? min + " min" : (min / 60) + " h " + (min % 60) + " min";
    }
}
