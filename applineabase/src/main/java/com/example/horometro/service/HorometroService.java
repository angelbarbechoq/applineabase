package com.example.horometro.service;

import com.example.dataacquisition.event.MaquinaDataUpdateEvent;
import com.example.dataacquisition.event.MaquinaEstadoCambioEvent;
import com.example.horometro.event.HorometroUpdateEvent;
import com.example.horometro.model.HorometroDiario;
import com.example.horometro.model.HorometroMensual;
import com.example.horometro.model.HorometroSemanal;
import com.example.horometro.model.HorometroTotal;
import com.example.horometro.repository.HorometroDiarioRepository;
import com.example.horometro.repository.HorometroMensualRepository;
import com.example.horometro.repository.HorometroSemanalRepository;
import com.example.horometro.repository.HorometroTotalRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Acumula horas de funcionamiento por máquina en cuatro granularidades (día, semana ISO,
 * mes, sin límite de tiempo), a partir de los cambios de estado ON/OFF que publica
 * AlarmaEvaluatorService (regla DETENCION o CICLO_COMPRESOR, según la máquina).
 *
 * Dos caminos escriben aquí:
 * - En vivo: onMaquinaEstadoCambioEvent, cada vez que una máquina termina un tramo
 *   encendida (acumularHoras, aditivo — cada tramo se reporta una sola vez).
 * - Backfill histórico (HorometroBackfillRunner): fijarHorasDia, idempotente — puede
 *   volver a calcular el mismo día muchas veces (p. ej. "hoy" en cada reinicio) sin
 *   duplicar horas, porque fija el valor absoluto del día en vez de sumarlo.
 */
@Service
public class HorometroService {

    private final HorometroTotalRepository totalRepository;
    private final HorometroDiarioRepository diarioRepository;
    private final HorometroSemanalRepository semanalRepository;
    private final HorometroMensualRepository mensualRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** Línea -> instante en que se confirmó que está encendida (tramo en curso, aún no cerrado). */
    private final ConcurrentHashMap<String, LocalDateTime> inicioEncendidoActual = new ConcurrentHashMap<>();

    /** Línea -> último PW recibido, para mostrarlo en vivo junto al umbral al configurarlo. */
    private final ConcurrentHashMap<String, Double> ultimoPwPorLinea = new ConcurrentHashMap<>();

    public HorometroService(HorometroTotalRepository totalRepository, HorometroDiarioRepository diarioRepository,
                             HorometroSemanalRepository semanalRepository, HorometroMensualRepository mensualRepository,
                             ApplicationEventPublisher eventPublisher) {
        this.totalRepository = totalRepository;
        this.diarioRepository = diarioRepository;
        this.semanalRepository = semanalRepository;
        this.mensualRepository = mensualRepository;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    @Transactional
    public void onMaquinaEstadoCambioEvent(MaquinaEstadoCambioEvent event) {
        String linea = event.getNombreMaquina();
        if (event.isEncendida()) {
            inicioEncendidoActual.putIfAbsent(linea, event.getDesde());
        } else {
            LocalDateTime inicio = inicioEncendidoActual.remove(linea);
            if (inicio != null && inicio.isBefore(event.getDesde())) {
                acumularHoras(linea, inicio, event.getDesde());
            }
        }
        publicarActualizacion(linea);
    }

    /**
     * Actualización "late" del dashboard mientras la máquina sigue encendida: no persiste
     * nada nuevo (el tramo en curso todavía no está cerrado), solo recalcula el snapshot
     * en vivo para que las horas de hoy no se vean congeladas entre transiciones.
     */
    @EventListener
    public void onMaquinaDataUpdateEvent(MaquinaDataUpdateEvent event) {
        String linea = event.getNombreMaquina();
        Object pw = event.getDatos().get("PW");
        if (pw instanceof Number numero) {
            ultimoPwPorLinea.put(linea, numero.doubleValue());
        }
        if (totalRepository.existsById(linea)) {
            publicarActualizacion(linea);
        }
    }

    /** Último PW conocido de la línea, o null si todavía no se ha recibido ninguno. */
    public Double getUltimoPw(String linea) {
        return ultimoPwPorLinea.get(linea);
    }

    /** Suma aditiva de un tramo ON real, nunca antes reportado. Usado por el camino en vivo. */
    @Transactional
    public void acumularHoras(String linea, LocalDateTime desde, LocalDateTime hasta) {
        if (!hasta.isAfter(desde)) {
            return;
        }
        LocalDateTime cursor = desde;
        while (cursor.toLocalDate().isBefore(hasta.toLocalDate())) {
            LocalDateTime finDelDia = cursor.toLocalDate().plusDays(1).atStartOfDay();
            acumularHorasEnDia(linea, cursor.toLocalDate(), horasEntre(cursor, finDelDia));
            cursor = finDelDia;
        }
        acumularHorasEnDia(linea, cursor.toLocalDate(), horasEntre(cursor, hasta));
        actualizarCheckpoint(linea, hasta);
    }

    private void acumularHorasEnDia(String linea, LocalDate fecha, double horas) {
        if (horas <= 0) {
            return;
        }
        HorometroDiario diario = diarioRepository.findByLineaMaquinaAndFecha(linea, fecha)
                .orElseGet(() -> new HorometroDiario(linea, fecha, 0));
        diario.setHoras(diario.getHoras() + horas);
        diarioRepository.save(diario);
        sumarASemanaMesYTotal(linea, fecha, horas);
    }

    /**
     * Fija el valor absoluto de horas de un día (no suma). Idempotente: llamarlo varias
     * veces con el mismo resultado para el mismo día no duplica horas en mensual/total,
     * porque ajusta por la diferencia contra el valor anterior. Usado por el backfill.
     */
    @Transactional
    public void fijarHorasDia(String linea, LocalDate fecha, double horasCalculadas) {
        HorometroDiario diario = diarioRepository.findByLineaMaquinaAndFecha(linea, fecha)
                .orElseGet(() -> new HorometroDiario(linea, fecha, 0));
        double delta = horasCalculadas - diario.getHoras();
        diario.setHoras(horasCalculadas);
        diarioRepository.save(diario);
        if (delta != 0) {
            sumarASemanaMesYTotal(linea, fecha, delta);
        }
    }

    private void sumarASemanaMesYTotal(String linea, LocalDate fecha, double horas) {
        String semanaId = semanaId(fecha);
        HorometroSemanal semanal = semanalRepository.findByLineaMaquinaAndSemanaId(linea, semanaId)
                .orElseGet(() -> new HorometroSemanal(linea, semanaId, 0));
        semanal.setHoras(semanal.getHoras() + horas);
        semanalRepository.save(semanal);

        String anioMes = anioMes(fecha);
        HorometroMensual mensual = mensualRepository.findByLineaMaquinaAndAnioMes(linea, anioMes)
                .orElseGet(() -> new HorometroMensual(linea, anioMes, 0));
        mensual.setHoras(mensual.getHoras() + horas);
        mensualRepository.save(mensual);

        HorometroTotal total = obtenerOCrearTotal(linea);
        total.setHorasAcumuladas(total.getHorasAcumuladas() + horas);
        totalRepository.save(total);
    }

    private void actualizarCheckpoint(String linea, LocalDateTime hasta) {
        HorometroTotal total = obtenerOCrearTotal(linea);
        total.setFechaUltimoProcesado(hasta);
        totalRepository.save(total);
    }

    @Transactional
    public HorometroTotal obtenerOCrearTotal(String linea) {
        return totalRepository.findById(linea).orElseGet(() -> totalRepository.save(new HorometroTotal(linea)));
    }

    /**
     * Fija desde cuándo cuenta el total, solo si todavía no tiene una (no la pisa en
     * arranques posteriores). Llamado por el backfill con la fecha del dato más antiguo
     * que encontró para esa máquina.
     */
    @Transactional
    public void marcarFechaInicioSiNoExiste(String linea, LocalDateTime fechaInicio) {
        HorometroTotal total = obtenerOCrearTotal(linea);
        if (total.getFechaInicio() == null) {
            total.setFechaInicio(fechaInicio);
            totalRepository.save(total);
        }
    }

    /** Llamado por el backfill al terminar, para que la acumulación en vivo continúe desde ahí. */
    public void inicializarEstadoEnVivo(String linea, boolean encendidaAhora, LocalDateTime desde) {
        if (encendidaAhora) {
            inicioEncendidoActual.put(linea, desde);
        } else {
            inicioEncendidoActual.remove(linea);
        }
    }

    public HorometroSnapshot obtenerSnapshot(String linea) {
        return calcularSnapshot(linea, LocalDateTime.now());
    }

    private void publicarActualizacion(String linea) {
        HorometroSnapshot snapshot = calcularSnapshot(linea, LocalDateTime.now());
        eventPublisher.publishEvent(new HorometroUpdateEvent(this, linea, snapshot.encendida(),
                snapshot.horasHoy(), snapshot.horasMes(), snapshot.horasTotal()));
    }

    private HorometroSnapshot calcularSnapshot(String linea, LocalDateTime ahora) {
        HorometroTotal total = totalRepository.findById(linea).orElse(null);
        double horasHoy = diarioRepository.findByLineaMaquinaAndFecha(linea, ahora.toLocalDate())
                .map(HorometroDiario::getHoras).orElse(0.0);
        double horasMes = mensualRepository.findByLineaMaquinaAndAnioMes(linea, anioMes(ahora.toLocalDate()))
                .map(HorometroMensual::getHoras).orElse(0.0);
        double horasTotal = total == null ? 0.0 : total.getHorasAcumuladas();
        LocalDateTime desdeCuandoCuentaTotal = total == null ? null : total.getFechaInicio();

        LocalDateTime inicioActual = inicioEncendidoActual.get(linea);
        boolean encendida = inicioActual != null;
        if (encendida) {
            LocalDateTime desdeHoy = inicioActual.toLocalDate().isBefore(ahora.toLocalDate())
                    ? ahora.toLocalDate().atStartOfDay() : inicioActual;
            horasHoy += horasEntre(desdeHoy, ahora);
            horasMes += horasEntre(inicioActual, ahora);
            horasTotal += horasEntre(inicioActual, ahora);
        }
        return new HorometroSnapshot(linea, encendida, horasHoy, horasMes, horasTotal, desdeCuandoCuentaTotal);
    }

    private double horasEntre(LocalDateTime desde, LocalDateTime hasta) {
        if (!hasta.isAfter(desde)) {
            return 0.0;
        }
        return Duration.between(desde, hasta).toMillis() / 3_600_000.0;
    }

    private String anioMes(LocalDate fecha) {
        return String.format("%04d-%02d", fecha.getYear(), fecha.getMonthValue());
    }

    /** Semana ISO (lunes a domingo): "yyyy-Www". */
    private String semanaId(LocalDate fecha) {
        int anioSemana = fecha.get(IsoFields.WEEK_BASED_YEAR);
        int semana = fecha.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return String.format("%04d-W%02d", anioSemana, semana);
    }

    /**
     * Reporte histórico anclado al domingo de la semana pedida: horas de ese domingo, de esa
     * semana completa, del mes que contiene ese domingo, y el total acumulado hasta esa fecha
     * (reconstruido sumando el histórico diario, ya que el total ya no se reinicia nunca).
     * Usado para el CSV semanal — no depende del estado en vivo, es 100% histórico/reproducible.
     */
    public ReporteSemanal generarReporte(String linea, LocalDate cualquierDiaDeLaSemana) {
        LocalDate domingo = cualquierDiaDeLaSemana.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        LocalDate lunes = domingo.minusDays(6);

        double horasDomingo = diarioRepository.findByLineaMaquinaAndFecha(linea, domingo)
                .map(HorometroDiario::getHoras).orElse(0.0);
        double horasSemana = semanalRepository.findByLineaMaquinaAndSemanaId(linea, semanaId(domingo))
                .map(HorometroSemanal::getHoras).orElse(0.0);
        double horasMes = mensualRepository.findByLineaMaquinaAndAnioMes(linea, anioMes(domingo))
                .map(HorometroMensual::getHoras).orElse(0.0);
        double horasTotal = diarioRepository.sumHorasHastaFecha(linea, domingo);

        return new ReporteSemanal(linea, lunes, domingo, horasDomingo, horasSemana, horasMes, horasTotal);
    }

    public record HorometroSnapshot(String linea, boolean encendida, double horasHoy, double horasMes,
                                     double horasTotal, LocalDateTime desdeCuandoCuentaTotal) {
    }

    public record ReporteSemanal(String linea, LocalDate lunes, LocalDate domingo, double horasDomingo,
                                  double horasSemana, double horasMes, double horasTotal) {
    }
}
