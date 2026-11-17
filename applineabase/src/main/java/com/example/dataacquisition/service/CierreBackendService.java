package com.example.dataacquisition.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Con la lectura de equipos de la app prendida ({@code app.adquisicion.habilitada=true}, lo normal), la app es la que
 * toma los datos: si el backend recolector (backendMySQLSGEn, {@code app.backend.url}) está corriendo, se le pide que se
 * cierre ({@code POST /api/cerrar}). Al arrancar y después cada minuto, por si se abre más tarde. Si no contesta es que
 * no está corriendo: no se hace nada.
 */
@Service
@ConditionalOnProperty(name = "app.adquisicion.habilitada", havingValue = "true", matchIfMissing = true)
public class CierreBackendService {

    private static final Logger logger = LoggerFactory.getLogger(CierreBackendService.class);
    private static final String MOTIVO = "la app lee los equipos (app.adquisicion.habilitada=true)";

    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public CierreBackendService(@Value("${app.backend.url:http://localhost:8081}") String url) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void cerrarSiCorre() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url + "/api/cerrar?motivo="
                            + URLEncoder.encode(MOTIVO, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                logger.warn("El backend recolector estaba corriendo en {}: se le pidió que se cierre porque {}", url, MOTIVO);
            } else {
                logger.warn("El backend recolector corre en {} y no aceptó cerrarse (respondió {}): cerrarlo a mano",
                        url, r.statusCode());
            }
        } catch (ConnectException | HttpConnectTimeoutException e) {
            // No está corriendo: lo esperado.
        } catch (Exception e) {
            logger.debug("Sin respuesta del backend en {}: {}", url, e.getMessage());
        }
    }
}
