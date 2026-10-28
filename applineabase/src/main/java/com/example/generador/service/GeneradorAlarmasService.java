package com.example.generador.service;

import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.alarmas.service.AlarmaTrayNotifierService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lleva la lista de alarmas del controlador de cada generador a las alarmas de la app (Alarmas Activas y
 * avisos del navegador, que salen de {@code AlarmaEvento}). Un evento abierto por alarma mientras esté en
 * la lista del controlador; se cierra cuando sale de la lista (reset). Si cambia de estado (activa / falta
 * el reset) se actualiza el mensaje del mismo evento.
 *
 * Urgente (aviso rojo con sonido y globo de Windows en la PC de la app) si impide arrancar el generador:
 * parada de emergencia o parada por protección. Lo llama la lectura de fondo (cada minuto), no la pantalla.
 */
@Service
public class GeneradorAlarmasService {

    private static final Logger logger = LoggerFactory.getLogger(GeneradorAlarmasService.class);

    private final AlarmaEventoRepository eventoRepository;
    private final AlarmaTrayNotifierService trayNotifier;
    private final HistorialEventosService historial;

    public GeneradorAlarmasService(AlarmaEventoRepository eventoRepository, AlarmaTrayNotifierService trayNotifier,
                                   HistorialEventosService historial) {
        this.eventoRepository = eventoRepository;
        this.trayNotifier = trayNotifier;
        this.historial = historial;
    }

    /** Mensaje del evento: "Generador GenPower: E-STOP (activa ahora, sin reset). Parada de emergencia: ...". */
    static String mensaje(String generador, String texto) {
        return prefijo(generador, AlarmaComap.nombre(texto)) + AlarmaComap.estado(texto) + "). " + AlarmaComap.explicacion(texto);
    }

    private static String prefijo(String generador, String nombre) {
        return "Generador " + generador + ": " + nombre + " (";
    }

    /**
     * Sincroniza las alarmas abiertas del generador con la lista leída del controlador.
     *
     * @param alarmas lista del controlador con sus signos; null = no se pudo leer (no se toca nada)
     */
    public void sincronizar(String generador, List<String> alarmas, LocalDateTime fecha) {
        if (alarmas == null) return;
        List<AlarmaEvento> abiertas = eventoRepository.findByLineaMaquinaAndTipoAlarmaAndActivaTrue(generador, TipoAlarma.ALARMA_GENERADOR);
        Map<String, String> actuales = new LinkedHashMap<>();
        for (String a : alarmas) actuales.put(AlarmaComap.nombre(a), a);

        // Cerrar las que salieron de la lista y actualizar el estado de las que siguen.
        for (AlarmaEvento ev : abiertas) {
            String nombre = actuales.keySet().stream().filter(n -> ev.getMensaje().startsWith(prefijo(generador, n))).findFirst().orElse(null);
            if (nombre == null) {
                String resto = ev.getMensaje().substring(Math.min(ev.getMensaje().length(), ("Generador " + generador + ": ").length()));
                int corte = resto.indexOf(" (");
                ev.setMensaje(prefijo(generador, corte < 0 ? resto : resto.substring(0, corte)) + "resuelta: salió de la lista del controlador)");
                ev.setActiva(false);
                ev.setFechaFin(fecha);
                eventoRepository.save(ev);
                logger.info("Alarma del generador resuelta: {}", ev.getMensaje());
            } else {
                String nuevo = mensaje(generador, actuales.remove(nombre));
                if (!nuevo.equals(ev.getMensaje())) {
                    ev.setMensaje(nuevo);
                    eventoRepository.save(ev);
                }
            }
        }
        // Abrir las nuevas. Si ya estaba en el historial de antes de esta lectura (por ejemplo, la app se
        // reinició con la alarma puesta), se usa la hora en que apareció y no se vuelve a avisar. Si es nueva,
        // la hora de guardado: el aviso del navegador busca las alarmas con inicio posterior a su última
        // revisión, y con la hora de la lectura (un instante antes) podría quedar afuera.
        for (String texto : actuales.values()) {
            HistorialEventosService.Aparicion ap = historial.aparicion(generador, texto);
            AlarmaEvento ev = new AlarmaEvento();
            ev.setLineaMaquina(generador);
            ev.setTipoAlarma(TipoAlarma.ALARMA_GENERADOR);
            ev.setMensaje(mensaje(generador, texto));
            ev.setFechaInicio(ap != null && !ap.inicioLectura() && ap.fecha().isBefore(fecha) ? ap.fecha() : LocalDateTime.now());
            ev.setActiva(true);
            ev.setUrgente(AlarmaComap.impideArrancar(texto));
            eventoRepository.save(ev);
            logger.warn("Alarma del generador: {}", ev.getMensaje());
            if (ev.isUrgente()) trayNotifier.notificarUrgente(generador, ev.getMensaje());
        }
    }

    // ================= Potencia sobre el límite =================

    /** kW que tiene que bajar del límite para cerrar la alarma: así no se abre y cierra cada minuto cerca del límite. */
    static final double HISTERESIS_KW = 20;

    /** Máximo de la alarma de potencia abierta de cada generador (para no guardar el mensaje en cada lectura). */
    private final Map<String, Double> maximoPotencia = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Alarma de potencia alta (pedido del usuario el 28-10-2026, 700 kW): se abre con la primera lectura del controlador
     * sobre el límite ("alarmaPotenciaKw" de generador-config.json), lleva el máximo y se cierra cuando baja
     * {@link #HISTERESIS_KW} del límite o el generador para. Evento en el historial al abrir y al cerrar. No urgente.
     *
     * @param limite kW; null = sin alarma para el generador (cierra la que hubiera)
     */
    public void sincronizarPotencia(String generador, com.example.generador.model.LecturaGenerador l, Double limite) {
        Double kw = l.kw();
        if (kw == null) return;
        List<AlarmaEvento> abiertas = eventoRepository.findByLineaMaquinaAndTipoAlarmaAndActivaTrue(generador, TipoAlarma.POTENCIA_GENERADOR);
        AlarmaEvento abierta = abiertas.isEmpty() ? null : abiertas.get(0);
        String lim = limite == null ? "" : String.format(java.util.Locale.ROOT, "%.0f", limite);
        if (abierta == null) {
            if (limite == null || kw <= limite) return;
            AlarmaEvento ev = new AlarmaEvento();
            ev.setLineaMaquina(generador);
            ev.setTipoAlarma(TipoAlarma.POTENCIA_GENERADOR);
            ev.setMensaje(mensajePotencia(generador, lim, kw));
            ev.setFechaInicio(LocalDateTime.now());
            ev.setActiva(true);
            ev.setUrgente(false);
            eventoRepository.save(ev);
            maximoPotencia.put(generador, kw);
            logger.warn("Alarma: {}", ev.getMensaje());
            historial.registrar(generador, List.of(com.example.generador.model.EventoGenerador.de(l.fecha(),
                    com.example.generador.model.TipoEventoGenerador.POTENCIA_ALTA,
                    String.format(java.util.Locale.ROOT, "El generador pasa el límite de %s kW: %.0f kW", lim, kw), l)));
            return;
        }
        // Máximo de la alarma: el guardado en memoria o, si la app se reinició, el que dice el mensaje.
        double maximo = Math.max(kw, maximoPotencia.getOrDefault(generador, maximoDelMensaje(abierta.getMensaje())));
        maximoPotencia.put(generador, maximo);
        if (limite != null && kw > limite - HISTERESIS_KW) {
            String nuevo = mensajePotencia(generador, lim, maximo);
            if (!nuevo.equals(abierta.getMensaje())) {
                abierta.setMensaje(nuevo);
                eventoRepository.save(abierta);
            }
            return;
        }
        long min = Math.max(0, Math.round(java.time.Duration.between(abierta.getFechaInicio(), l.fecha()).toSeconds() / 60.0));
        abierta.setMensaje(String.format(java.util.Locale.ROOT,
                "Generador %s: potencia sobre el límite de %s kW (resuelta: %.0f kW tras %d min; máximo %.0f kW)",
                generador, lim.isEmpty() ? "-" : lim, kw, min, maximo));
        abierta.setActiva(false);
        abierta.setFechaFin(l.fecha());
        eventoRepository.save(abierta);
        maximoPotencia.remove(generador);
        logger.info("Alarma resuelta: {}", abierta.getMensaje());
        historial.registrar(generador, List.of(com.example.generador.model.EventoGenerador.de(l.fecha(),
                com.example.generador.model.TipoEventoGenerador.POTENCIA_NORMAL,
                String.format(java.util.Locale.ROOT, "La potencia vuelve bajo el límite: %.0f kW tras %d min; máximo %.0f kW", kw, min, maximo), l)));
    }

    private static String mensajePotencia(String generador, String limite, double maximo) {
        return String.format(java.util.Locale.ROOT, "Generador %s: potencia sobre el límite de %s kW (máximo %.0f kW)", generador, limite, maximo);
    }

    /** "(máximo 866 kW)" del mensaje de la alarma; 0 si no está. */
    private static double maximoDelMensaje(String mensaje) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("máximo (\\d+) kW").matcher(mensaje == null ? "" : mensaje);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }
}
