package com.example.generador.service;

import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.generador.model.Generador;
import com.example.generador.model.LecturaGenerador;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * La planta en este momento: cuánto consume, cuánto de ese consumo pone cada generador y cuánto viene de la red.
 *
 * Por cada generador: su potencia leída en vivo del controlador (SOLO LECTURA, función 03) y la potencia del medidor
 * de su transformador (último minuto guardado del PLC; positiva = importa de la red, negativa = exporta).
 * Consumo del transformador = generador + red. Lo que el generador pone en la planta = lo que genera menos lo que
 * sale a la red por su transformador. Consumo de la planta = suma de los transformadores; así la parte de los
 * generadores más la importada de la red da el 100 %, y lo exportado va aparte.
 */
@Service
public class PlantaEnVivoService {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);
    /** Lectura del medidor más vieja que esto = no se usa (el PLC guarda una por minuto). */
    public static final long RED_VIGENTE_SEGUNDOS = 180;

    public enum EstadoGenerador {
        EN_MARCHA("En marcha"), PARADO("Parado"), SIN_COMUNICACION("Sin comunicacion");

        private final String etiqueta;

        EstadoGenerador(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        public String etiqueta() {
            return etiqueta;
        }
    }

    /**
     * Un generador y su transformador ahora.
     *
     * @param kwGenerador lo que genera (0 parado; null sin comunicación)
     * @param kwRed       medidor del transformador: + importa, - exporta (null sin dato reciente)
     */
    public record FilaGenerador(String generador, String red, EstadoGenerador estado, Double kwGenerador, Double kwRed,
                                LocalDateTime fechaRed, String error) {

        public boolean completa() {
            return kwGenerador != null && kwRed != null;
        }

        public Double importada() {
            return kwRed == null ? null : Math.max(0, kwRed);
        }

        public Double exportada() {
            return kwRed == null ? null : Math.max(0, -kwRed);
        }

        /** Lo que el generador pone en la planta: lo que genera menos lo que sale a la red por su transformador. */
        public Double consumidaEnPlanta() {
            return !completa() ? null : Math.max(0, kwGenerador - exportada());
        }

        public Double consumoTransformador() {
            return !completa() ? null : Math.max(0, kwGenerador + kwRed);
        }
    }

    /** La planta ahora: solo cuenta los generadores con lectura completa (los demás se avisan en pantalla). */
    public record Planta(LocalDateTime leido, List<FilaGenerador> filas) {

        private List<FilaGenerador> completas() {
            return filas.stream().filter(FilaGenerador::completa).toList();
        }

        public boolean completa() {
            return filas.stream().allMatch(FilaGenerador::completa);
        }

        public double consumo() {
            return completas().stream().mapToDouble(FilaGenerador::consumoTransformador).sum();
        }

        public double generada() {
            return completas().stream().mapToDouble(FilaGenerador::kwGenerador).sum();
        }

        public double consumidaDeGeneradores() {
            return completas().stream().mapToDouble(FilaGenerador::consumidaEnPlanta).sum();
        }

        public double importada() {
            return completas().stream().mapToDouble(FilaGenerador::importada).sum();
        }

        public double exportada() {
            return completas().stream().mapToDouble(FilaGenerador::exportada).sum();
        }

        /** Parte del consumo de la planta (%), o null si no hay consumo. */
        public Double porcentaje(double kw) {
            double c = consumo();
            return c <= 0 ? null : kw / c * 100;
        }
    }

    private final GeneradorService generadorService;

    public PlantaEnVivoService(GeneradorService generadorService) {
        this.generadorService = generadorService;
    }

    public Planta leer() {
        LocalDateTime ahora = LocalDateTime.now().withNano(0);
        List<FilaGenerador> filas = new ArrayList<>();
        for (Generador g : generadorService.generadores()) {
            EstadoGenerador estado;
            Double kw;
            String error = null;
            try {
                LecturaGenerador l = generadorService.leerEnVivo(g);
                boolean marcha = l.enMarcha();
                estado = marcha ? EstadoGenerador.EN_MARCHA : EstadoGenerador.PARADO;
                kw = marcha ? (l.kw() == null ? null : Math.max(0, l.kw())) : 0.0;
                if (marcha && l.kw() == null) error = "el controlador no dio la potencia";
            } catch (Exception e) {
                estado = EstadoGenerador.SIN_COMUNICACION;
                kw = null;
                error = e.getMessage();
            }
            Double kwRed = null;
            LocalDateTime fechaRed = null;
            Map<String, Object> medidor = generadorService.ultimaRed(g);
            if (!medidor.isEmpty() && medidor.get("PW") instanceof Number pw) {
                fechaRed = fecha(medidor.get("fecha"));
                if (fechaRed != null && !fechaRed.isBefore(ahora.minusSeconds(RED_VIGENTE_SEGUNDOS))) kwRed = pw.doubleValue();
            }
            if (kwRed == null && error == null) {
                error = fechaRed == null ? "sin datos del medidor " + Objects.toString(g.redAsociada(), "")
                        : "medidor " + g.redAsociada() + " sin datos desde " + fechaRed.format(FECHA);
            }
            filas.add(new FilaGenerador(g.nombre(), g.redAsociada(), estado, kw, kwRed, fechaRed, error));
        }
        return new Planta(ahora, filas);
    }

    private static LocalDateTime fecha(Object o) {
        try {
            return o == null ? null : LocalDateTime.parse(String.valueOf(o), FECHA);
        } catch (Exception e) {
            return null;
        }
    }
}
