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
 *   apertura y cierre del interruptor de la planta (MCB Feedback), ROCOF o salto de vector vistos activos, aviso si en
 *   paralelo pasan la mitad del ajuste de disparo y, al salir de paralelo, los máximos de esa marcha;
 * - alarma "Pérdida de red" (Alarmas Activas, aviso del navegador y globo de Windows en la PC de la app): abierta
 *   mientras el controlador esté sin red, se cierra sola al volver.
 * - selector de tres posiciones del controlador (AMF / sincronismo con la red / isla, entrada Force Island): con el
 *   selector en Isla la isla es a propósito ("Paso a isla con el selector") y no se abre la alarma, tampoco si después
 *   corta la empresa eléctrica (pedido del usuario el 28-10-2026; base de la sirena física que se agregará).
 * Las protecciones de red actúan en menos de 1 s: con una lectura por minuto casi nunca se ven activas, por eso se usan
 * los máximos que guarda el controlador. El Caterpillar los reinicia cada vez que entra en paralelo y después suben solos
 * con la red normal (25 y 26-10-2026: 0,2 a 1,1 grados y 0,17 a 0,46 Hz/s, con ajustes de 10 grados y 1,30 Hz/s): por eso
 * no se anota cada suba, solo el resumen al salir de paralelo y el aviso a la mitad del ajuste.
 * Solo lectura del controlador. Con estas señales la isla no se busca comparando con el medidor del transformador
 * (IslaService la toma de lo guardado por el controlador).
 */
@Service
public class RedControladorService {

    private static final Logger logger = LoggerFactory.getLogger(RedControladorService.class);

    /** Estado de los interruptores según la última lectura y desde cuándo está así (null si el modelo no lo da). */
    /**
     * @param isla generador con carga y el interruptor de la planta abierto (cualquier estado sin red: también MainsRet,
     *             que el controlador usa después de abrir por una protección de red mientras espera para volver)
     * @param mcb  interruptor de la planta cerrado según la entrada MCB Feedback (null si el modelo no la tiene)
     * @param selectorIsla selector de tres posiciones del controlador en Isla (entrada Force Island): isla a propósito
     */
    public record EstadoActual(Estado estado, String nombre, LocalDateTime desde, boolean isla, Boolean mcb, boolean selectorIsla) {
    }

    /** Lo visto en la lectura anterior de cada generador. */
    private static final class Previo {
        Estado estado;
        String nombre;
        LocalDateTime desde;
        Boolean mcb, selectorIsla;
        boolean rocofActiva, saltoVectorActivo;
        boolean enIsla;
        // Marcha en paralelo en curso: máximos vistos, si ya se avisó cada uno y desde cuándo (null = la app empezó a
        // leer con la marcha ya en paralelo).
        Double rocofMarcha, saltoMarcha;
        boolean avisoRocof, avisoSalto;
        LocalDateTime inicioParalelo;
    }

    /** Fracción del ajuste de disparo desde la que se avisa. */
    static final double FRACCION_AVISO = 0.5;

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
        return p == null || p.estado == null ? null : new EstadoActual(p.estado, p.nombre, p.desde, p.enIsla, p.mcb,
                Boolean.TRUE.equals(p.selectorIsla));
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
        // Selector del controlador en Isla (entrada Force Island): la isla es a propósito, no abre la alarma urgente.
        Boolean selector = SenalesRed.valor(red.forzarIsla(), l);
        boolean selectorIsla = Boolean.TRUE.equals(selector);
        // Isla: sin red con el interruptor de la planta abierto y el generador con carga. El 27-10-2026 el Caterpillar abrió
        // por ROCOF y quedó 6 min en MainsRet (9), alimentando solo su parte de la planta: también es isla.
        boolean isla = estado != null && SenalesRed.sinRed(estado) && !Boolean.TRUE.equals(mcb)
                && l.kw() != null && l.kw() >= GeneradorAnalisisService.KW_VACIO;

        List<EventoGenerador> eventos = new ArrayList<>();
        Previo p = previos.get(generador);
        AlarmaEvento abierta = alarmaAbierta(generador);
        if (p == null) {
            p = new Previo();
            previos.put(generador, p);
            // Al iniciar la lectura: si ya estaba sin red, se anota (y la alarma abierta de antes da la hora de comienzo).
            p.desde = Boolean.TRUE.equals(sinRed) && abierta != null ? abierta.getFechaInicio() : t;
            if (estado != null && SenalesRed.sinRed(estado)) {
                eventos.add(isla ? EventoGenerador.de(t, selectorIsla ? ISLA_SELECTOR : ISLA, (selectorIsla ? textoIslaSelector(nombre)
                        : textoIsla(red, l, nombre)) + " (ya estaba al iniciar la lectura)", l)
                        : EventoGenerador.de(t, tipoDe(estado), textoEstado(estado, nombre) + detalle(red, l)
                        + " (ya estaba al iniciar la lectura)", l));
            }
        } else {
            if (estado != null && p.estado != null && estado != p.estado) {
                EventoGenerador ev = cambioDeEstado(red, l, p, estado, nombre, isla, selectorIsla);
                if (ev != null) eventos.add(ev);
            }
            if (estado != null && estado != p.estado) p.desde = t;
            if (mcb != null && p.mcb != null && !mcb.equals(p.mcb)) {
                eventos.add(EventoGenerador.de(t, mcb ? INTERRUPTOR_RED_CERRADO : INTERRUPTOR_RED_ABIERTO, mcb
                        ? "El controlador ve cerrado el interruptor de la planta (MCB Feedback); estado " + nombre
                        : "El controlador ve abierto el interruptor de la planta (MCB Feedback); estado " + nombre + detalle(red, l), l));
            }
            if (selector != null && p.selectorIsla != null && !selector.equals(p.selectorIsla)) {
                eventos.add(EventoGenerador.de(t, selector ? SELECTOR_ISLA : SELECTOR_FUERA_ISLA, selector
                        ? "El selector del controlador pasa a Isla (entrada Force Island); estado " + nombre
                        : "El selector del controlador sale de Isla (entrada Force Island); estado " + nombre, l));
            }
            if (rocofActiva && !p.rocofActiva) {
                eventos.add(EventoGenerador.de(t, PROTECCION_RED, "ROCOF activa en la lectura" + rocof(l), l));
            }
            if (saltoActivo && !p.saltoVectorActivo) {
                eventos.add(EventoGenerador.de(t, PROTECCION_RED, "Salto de vector activo en la lectura" + salto(l), l));
            }
        }
        if (estado != null) maximosEnParalelo(red, l, p, estado, rocofMax, saltoMax, eventos);
        if (estado != null) {
            p.estado = estado;
            p.nombre = nombre;
        }
        if (mcb != null) p.mcb = mcb;
        if (selector != null) p.selectorIsla = selector;
        p.rocofActiva = rocofActiva;
        p.saltoVectorActivo = saltoActivo;
        boolean eraIsla = p.enIsla;
        p.enIsla = isla;
        historial.registrar(generador, eventos);
        // Con el selector en Isla no se abre la alarma (isla a propósito, y el corte de la empresa eléctrica que llega con la
        // planta ya en isla); una que ya estaba abierta sigue hasta que vuelva la red.
        if (sinRed != null) sincronizarAlarma(generador, abierta, sinRed && (abierta != null || !selectorIsla), red, l, estado, nombre,
                isla || eraIsla && Boolean.TRUE.equals(sinRed));
    }

    /**
     * Máximos de ROCOF y salto de vector de la marcha en paralelo. El controlador los reinicia al entrar en paralelo, así
     * que su valor es el de la marcha en curso: mientras dura se avisa una vez si pasan la mitad del ajuste de disparo, y
     * al salir (parada normal, isla o falla de red: la lectura de salida todavía tiene el máximo de la marcha) se anota el
     * resumen. Se llama antes de pasar {@code estado} a la lectura anterior.
     */
    private static void maximosEnParalelo(SenalesRed red, LecturaGenerador l, Previo p, Estado estado, Double rocofMax,
                                          Double saltoMax, List<EventoGenerador> eventos) {
        boolean enParalelo = estado == Estado.PARALELO, antes = p.estado == Estado.PARALELO;
        if (!enParalelo && !antes) return;
        if (enParalelo && !antes) {
            p.rocofMarcha = p.saltoMarcha = null;
            p.avisoRocof = p.avisoSalto = false;
            p.inicioParalelo = p.estado == null ? null : l.fecha(); // sin lectura anterior: la marcha ya estaba en curso
            // Al volver a paralelo la primera lectura todavía puede traer el máximo de antes (27-10: 0,89 Hz/s de la isla,
            // aviso falso): se empieza a contar desde la lectura siguiente.
            if (p.estado != null) return;
        }
        p.rocofMarcha = mayor(p.rocofMarcha, rocofMax);
        p.saltoMarcha = mayor(p.saltoMarcha, saltoMax);
        LocalDateTime t = l.fecha();
        if (!p.avisoSalto && pasaAviso(p.saltoMarcha, red.limiteSaltoVector())) {
            p.avisoSalto = true;
            eventos.add(EventoGenerador.de(t, VARIACION_RED_ALTA, String.format(Locale.ROOT,
                    "El salto de vector llegó a %.1f grados, %s del ajuste de disparo de %s grados (la protección abre el "
                            + "interruptor de la planta al llegar al ajuste)", p.saltoMarcha, porcentaje(p.saltoMarcha, red.limiteSaltoVector()),
                    numero(red.limiteSaltoVector())), l));
        }
        if (!p.avisoRocof && pasaAviso(p.rocofMarcha, red.limiteRocof())) {
            p.avisoRocof = true;
            eventos.add(EventoGenerador.de(t, VARIACION_RED_ALTA, String.format(Locale.ROOT,
                    "La ROCOF llegó a %.2f Hz/s, %s del ajuste de disparo de %s Hz/s (la protección abre el interruptor de la "
                            + "planta al llegar al ajuste)", p.rocofMarcha, porcentaje(p.rocofMarcha, red.limiteRocof()),
                    numero(red.limiteRocof())), l));
        }
        if (!enParalelo && (p.rocofMarcha != null || p.saltoMarcha != null)) {
            StringBuilder sb = new StringBuilder("Máximos de la marcha en paralelo");
            sb.append(p.inicioParalelo == null ? " (la app empezó a leer con la marcha en curso)"
                    : " (" + duracion(p.inicioParalelo, t) + ")");
            sb.append(": ");
            if (p.saltoMarcha != null) {
                sb.append(String.format(Locale.ROOT, "salto de vector %.1f grados", p.saltoMarcha));
                if (red.limiteSaltoVector() != null) sb.append(" (").append(porcentaje(p.saltoMarcha, red.limiteSaltoVector()))
                        .append(" del ajuste de ").append(numero(red.limiteSaltoVector())).append(" grados)");
            }
            if (p.rocofMarcha != null) {
                if (p.saltoMarcha != null) sb.append(", ");
                sb.append(String.format(Locale.ROOT, "ROCOF %.2f Hz/s", p.rocofMarcha));
                if (red.limiteRocof() != null) sb.append(" (").append(porcentaje(p.rocofMarcha, red.limiteRocof()))
                        .append(" del ajuste de ").append(numero(red.limiteRocof())).append(" Hz/s)");
            }
            sb.append("; sale de paralelo: estado ").append(red.nombre(SenalesRed.codigo(l)));
            eventos.add(EventoGenerador.de(t, RESUMEN_RED, sb.toString(), l));
            p.rocofMarcha = p.saltoMarcha = null;
        }
    }

    private static Double mayor(Double a, Double b) {
        return a == null ? b : b == null ? a : Math.max(a, b);
    }

    private static boolean pasaAviso(Double valor, Double limite) {
        return valor != null && limite != null && valor >= FRACCION_AVISO * limite;
    }

    private static String porcentaje(double valor, double limite) {
        return Math.round(valor / limite * 100) + " %";
    }

    private static String numero(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    /** Evento por el cambio de estado de los interruptores; null si no interesa (arranque o parada normal). */
    private static EventoGenerador cambioDeEstado(SenalesRed red, LecturaGenerador l, Previo p, Estado estado, String nombre,
                                                  boolean isla, boolean selectorIsla) {
        LocalDateTime t = l.fecha();
        if (SenalesRed.sinRed(estado)) {
            // De paralelo a isla (también si el controlador pasa directo a MainsRet tras abrir por una protección de red).
            if (isla && !p.enIsla) return selectorIsla ? EventoGenerador.de(t, ISLA_SELECTOR, textoIslaSelector(nombre), l)
                    : EventoGenerador.de(t, ISLA, textoIsla(red, l, nombre), l);
            if (isla) {
                // Sigue en isla: solo interesan el corte de la empresa eléctrica y la vuelta de la red.
                if (estado == Estado.FALLA_RED) return EventoGenerador.de(t, FALLA_RED, "Se cortó la red con el generador ya en isla ("
                        + nombre + ")" + (selectorIsla ? ", selector en Isla" : "") + detalle(red, l), l);
                if (p.estado == Estado.FALLA_RED) return EventoGenerador.de(t, RETORNO_RED, "Volvió la red con el generador en isla ("
                        + nombre + ")" + (selectorIsla ? "; con el selector en Isla sigue en isla hasta pasarlo a sincronismo" : ""), l);
                return null;
            }
            return EventoGenerador.de(t, tipoDe(estado), textoEstado(estado, nombre) + detalle(red, l), l);
        }
        if (!SenalesRed.sinRed(p.estado)) return null;
        String tras = " tras " + duracion(p.desde, t) + (p.enIsla ? " en isla" : " sin red");
        return estado == Estado.PARALELO
                ? EventoGenerador.de(t, FIN_ISLA, "Vuelve a paralelo con la red según el controlador (" + nombre + ")" + tras, l)
                : EventoGenerador.de(t, RETORNO_RED, "La red ya no figura en falla según el controlador: estado " + nombre + tras, l);
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
            case ISLA -> "El controlador está en isla (" + nombre + "): el interruptor de la planta está abierto y el generador "
                    + "alimenta solo su parte de la planta";
            case RETORNO_RED -> "Retorno de red según el controlador (" + nombre + "): espera o sincroniza para volver a paralelo";
            default -> "Falla de red según el controlador (" + nombre + ")";
        };
    }

    /**
     * Paso a isla: qué pasó, por qué abrió si se sabe (máximo de ROCOF o de salto de vector en o sobre su ajuste de
     * disparo) y el detalle de las señales.
     */
    private static String textoIsla(SenalesRed red, LecturaGenerador l, String nombre) {
        StringBuilder sb = new StringBuilder("Se abrió el interruptor de la planta y el generador alimenta solo su parte de la planta (")
                .append(nombre).append(")");
        Double rocofMax = l.valor(ParametroGenerador.ROCOF_MAX), saltoMax = l.valor(ParametroGenerador.SALTO_VECTOR_MAX);
        if (rocofMax != null && red.limiteRocof() != null && rocofMax >= red.limiteRocof()) {
            sb.append(String.format(Locale.ROOT, "; abrió por ROCOF: %.2f Hz/s con ajuste de disparo de %s Hz/s",
                    rocofMax, numero(red.limiteRocof())));
        }
        if (saltoMax != null && red.limiteSaltoVector() != null && saltoMax >= red.limiteSaltoVector()) {
            sb.append(String.format(Locale.ROOT, "; abrió por salto de vector: %.1f grados con ajuste de disparo de %s grados",
                    saltoMax, numero(red.limiteSaltoVector())));
        }
        return sb.append(detalle(red, l)).toString();
    }

    /** Paso a isla a propósito, con el selector del controlador en Isla. */
    private static String textoIslaSelector(String nombre) {
        return "Paso a isla con el selector en Isla: se abrió el interruptor de la planta y el generador alimenta solo su parte"
                + " de la planta (" + nombre + ")";
    }

    /** Lo que se sabe además: interruptores, avisos de red y máximos del controlador. */
    private static String detalle(SenalesRed red, LecturaGenerador l) {
        StringBuilder sb = new StringBuilder();
        Boolean mcb = SenalesRed.valor(red.mcbCerrado(), l), gcb = SenalesRed.valor(red.gcbCerrado(), l);
        if (mcb != null) sb.append("; interruptor de la planta ").append(mcb ? "cerrado" : "abierto");
        if (gcb != null) sb.append("; generador ").append(gcb ? "conectado" : "desconectado");
        if (Boolean.TRUE.equals(SenalesRed.valor(red.avisoFallaRed(), l))) sb.append("; aviso de falla de red activo");
        if (Boolean.TRUE.equals(SenalesRed.valor(red.proteccionRed(), l))) sb.append("; protección de red activa");
        if (Boolean.TRUE.equals(SenalesRed.valor(red.rocof(), l))) sb.append("; ROCOF activa");
        if (Boolean.TRUE.equals(SenalesRed.valor(red.saltoVector(), l))) sb.append("; salto de vector activo");
        Double rocofMax = l.valor(ParametroGenerador.ROCOF_MAX), saltoMax = l.valor(ParametroGenerador.SALTO_VECTOR_MAX);
        if (rocofMax != null) sb.append(String.format(Locale.ROOT, "; ROCOF máxima registrada %.2f Hz/s", rocofMax));
        if (saltoMax != null) sb.append(String.format(Locale.ROOT, "; salto de vector máximo %.1f grados", saltoMax));
        return sb.toString();
    }

    private static String rocof(LecturaGenerador l) {
        Double v = l.valor(ParametroGenerador.ROCOF), max = l.valor(ParametroGenerador.ROCOF_MAX);
        return (v == null ? "" : String.format(Locale.ROOT, " (%.2f Hz/s", v) + (max == null ? ")" : String.format(Locale.ROOT, ", máxima %.2f)", max)));
    }

    private static String salto(LecturaGenerador l) {
        Double max = l.valor(ParametroGenerador.SALTO_VECTOR_MAX);
        return max == null ? "" : String.format(Locale.ROOT, " (máximo %.1f grados)", max);
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
                                   Estado estado, String nombre, boolean isla) {
        if (sinRed) {
            // Corto: el mensaje de la alarma tiene lugar para 255 caracteres (el detalle completo va al historial).
            Boolean mcb = SenalesRed.valor(red.mcbCerrado(), l);
            String mensaje = corto(isla
                    ? "Generador " + generador + ": en isla, se abrió el interruptor de la planta y el generador alimenta solo su parte"
                    + " de la planta" + (estado == null ? "" : " (" + nombre + ")")
                    : "Generador " + generador + ": pérdida de red, " + switch (estado == null ? Estado.ISLA : estado) {
                case ISLA -> "el generador alimenta solo su parte de la planta";
                case RETORNO_RED -> "volvió la red y el controlador espera para volver a paralelo";
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
            abierta.setMensaje(corto("Generador " + generador + ": pérdida de red (resuelta: "
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
