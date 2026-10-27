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
}
