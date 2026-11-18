package com.example.dataacquisition.service;

import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.alarmas.service.AlarmaTrayNotifierService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Con la lectura de equipos de la app apagada ({@code app.adquisicion.habilitada=false}), pregunta cada 5 s (cada segundo mientras lee) al backend
 * recolector ({@code app.backend.url}, por defecto http://localhost:8081) en {@code /api/estado} si está trabajando:
 * <ul>
 *   <li>EN_LINEA: contestó, la lectura está prendida y el último ciclo es de hace menos de 3 min.</li>
 *   <li>SIN_LEER: contestó, pero la lectura está apagada, todavía no hizo un ciclo, el último es viejo o falló el
 *       guardado.</li>
 *   <li>FUERA_DE_LINEA: no contestó. Con 30 s seguidos sin respuesta abre la alarma urgente BACKEND_FUERA_DE_LINEA, que se
 *       cierra sola cuando vuelve a contestar.</li>
 * </ul>
 * La franja de arriba de la app (MainLayout) muestra este estado.
 */
@Service
@ConditionalOnProperty(name = "app.adquisicion.habilitada", havingValue = "false")
public class EstadoBackendService {

    private static final Logger logger = LoggerFactory.getLogger(EstadoBackendService.class);
    private static final String LINEA = "Backend";
    private static final long MAXIMO_SEGUNDOS_SIN_CICLO = 180;
    private static final long MS_SIN_RESPUESTA_PARA_ALARMA = 30_000;
    /** Ritmo normal de consulta; mientras el backend lee (o está por leer) se consulta cada segundo. */
    private static final long MS_ENTRE_CONSULTAS = 5_000;

    public enum Nivel { EN_LINEA, SIN_LEER, FUERA_DE_LINEA }

    /**
     * @param detalle            texto para el tooltip de la franja
     * @param segundosParaLectura los que faltaban para la próxima lectura del backend al preguntarle (null = no se sabe)
     * @param leyendo            el backend estaba leyendo al preguntarle
     * @param recibidoMs         cuándo llegó la respuesta (System.currentTimeMillis), para descontar el tiempo pasado
     */
    public record Estado(Nivel nivel, String detalle, LocalDateTime revisado, Integer segundosParaLectura, boolean leyendo,
                         long recibidoMs) {
        Estado(Nivel nivel, String detalle, LocalDateTime revisado) {
            this(nivel, detalle, revisado, null, false, System.currentTimeMillis());
        }

        /** Segundos que faltan ahora para la próxima lectura, o null si no se sabe. */
        public Integer segundosAhora() {
            if (segundosParaLectura == null) return null;
            return (int) Math.max(0, segundosParaLectura - (System.currentTimeMillis() - recibidoMs) / 1000);
        }
    }

    private final String url;
    private final AlarmaEventoRepository eventos;
    private final AlarmaTrayNotifierService bandeja;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();

    private volatile Estado estado = new Estado(Nivel.SIN_LEER, "Todavía no se preguntó al backend", LocalDateTime.now());
    /** Desde cuándo no contesta (0 = contesta). */
    private long sinRespuestaDesdeMs;
    private long proximaConsultaMs;

    public EstadoBackendService(@Value("${app.backend.url:http://localhost:8081}") String url,
                                AlarmaEventoRepository eventos, AlarmaTrayNotifierService bandeja) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.eventos = eventos;
        this.bandeja = bandeja;
    }

    public Estado estado() {
        return estado;
    }

    /**
     * Cada segundo decide si pregunta: cada 5 s normalmente, y cada segundo mientras el backend lee o le falta 1 s
     * para leer, así el fin de la lectura se ve enseguida.
     */
    @Scheduled(fixedDelay = 1_000, initialDelay = 3_000)
    public void revisar() {
        long ms = System.currentTimeMillis();
        Estado actual = estado;
        Integer falta = actual.segundosAhora();
        boolean cercaDeLeer = actual.leyendo() || (falta != null && falta <= 1);
        if (ms < proximaConsultaMs && !cercaDeLeer) return;
        proximaConsultaMs = ms + MS_ENTRE_CONSULTAS;
        LocalDateTime ahora = LocalDateTime.now().withNano(0);
        JsonNode r;
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url + "/api/estado"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new IllegalStateException("respondió " + resp.statusCode());
            r = json.readTree(resp.body());
        } catch (Exception e) {
            if (sinRespuestaDesdeMs == 0) sinRespuestaDesdeMs = ms;
            String motivo = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            estado = new Estado(Nivel.FUERA_DE_LINEA, "El backend no contesta en " + url + " (" + motivo + ")", ahora);
            if (ms - sinRespuestaDesdeMs >= MS_SIN_RESPUESTA_PARA_ALARMA) abrirAlarma(ahora);
            return;
        }
        sinRespuestaDesdeMs = 0;
        cerrarAlarma(ahora);
        Integer segundos = r.has("segundosParaProximaLectura") ? r.path("segundosParaProximaLectura").asInt() : null;
        boolean leyendo = r.path("leyendo").asBoolean(false);
        long recibido = System.currentTimeMillis();

        if (!r.path("lecturaHabilitada").asBoolean(false)) {
            estado = new Estado(Nivel.SIN_LEER, "El backend contesta, pero tiene la lectura apagada", ahora, segundos, leyendo, recibido);
        } else if (!r.has("ultimoCiclo")) {
            estado = new Estado(Nivel.SIN_LEER, "El backend contesta y espera su primer ciclo de lectura", ahora, segundos, leyendo, recibido);
        } else if (r.path("segundosDesdeUltimoCiclo").asLong() > MAXIMO_SEGUNDOS_SIN_CICLO) {
            estado = new Estado(Nivel.SIN_LEER, "El backend contesta, pero su último ciclo de lectura fue a las "
                    + hora(r.path("ultimoCiclo").asText()), ahora, segundos, leyendo, recibido);
        } else if (!r.path("errorGuardado").isNull() && !r.path("errorGuardado").isMissingNode()) {
            estado = new Estado(Nivel.SIN_LEER, "El backend lee, pero falló al guardar: " + r.path("errorGuardado").asText(), ahora, segundos, leyendo, recibido);
        } else if (r.path("prueba").asBoolean(false)) {
            // Al arrancar, el backend hace una lectura de prueba que no guarda: los equipos contestan, todavía sin datos nuevos.
            estado = new Estado(Nivel.EN_LINEA, "Lectura de prueba al arrancar a las " + hora(r.path("ultimoCiclo").asText())
                    + ": " + r.path("filas").asInt() + " filas leídas, sin guardar; la primera lectura guardada es la del reloj", ahora, segundos, leyendo, recibido);
        } else {
            estado = new Estado(Nivel.EN_LINEA, "Último ciclo a las " + hora(r.path("ultimoCiclo").asText()) + ": "
                    + r.path("guardadas").asInt() + " filas guardadas", ahora, segundos, leyendo, recibido);
        }
    }

    private void abrirAlarma(LocalDateTime ahora) {
        if (eventos.findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(LINEA, TipoAlarma.BACKEND_FUERA_DE_LINEA).isPresent()) return;
        String mensaje = "Backend recolector fuera de línea: no contesta, no se están guardando datos nuevos";
        AlarmaEvento ev = new AlarmaEvento();
        ev.setLineaMaquina(LINEA);
        ev.setTipoAlarma(TipoAlarma.BACKEND_FUERA_DE_LINEA);
        ev.setMensaje(mensaje);
        ev.setFechaInicio(ahora);
        ev.setActiva(true);
        ev.setUrgente(true);
        eventos.save(ev);
        logger.warn("Alarma: {}", mensaje);
        bandeja.notificarUrgente(LINEA, mensaje);
    }

    private void cerrarAlarma(LocalDateTime ahora) {
        eventos.findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(LINEA, TipoAlarma.BACKEND_FUERA_DE_LINEA).ifPresent(a -> {
            long min = Math.max(0, Duration.between(a.getFechaInicio(), ahora).toMinutes());
            a.setMensaje("Backend recolector fuera de línea (resuelta: volvió a contestar tras "
                    + (min < 60 ? min + " min" : (min / 60) + " h " + (min % 60) + " min") + ")");
            a.setActiva(false);
            a.setFechaFin(ahora);
            eventos.save(a);
            logger.info("Alarma resuelta: {}", a.getMensaje());
        });
    }

    /** "2026-11-17T14:30:30" -> "14:30:30". */
    private static String hora(String iso) {
        int t = iso.indexOf('T');
        return t < 0 ? iso : iso.substring(t + 1);
    }
}
