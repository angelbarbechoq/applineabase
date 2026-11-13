package com.example.lotes.service;

import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.lotes.model.LoteProduccion;
import com.example.lotes.repository.LoteProduccionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vigilancia en tiempo real del lote en curso contra su línea base por producto. Cada minuto, para cada línea con un lote
 * abierto y un producto con línea base válida: promedio de la última hora en kWh/min (contador guardado ahora y hace 60
 * min). Si queda sobre el máximo o bajo el mínimo de la banda durante {@link #MINUTOS_FUERA} min seguidos, abre la alarma
 * {@link TipoAlarma#CONSUMO_LOTE} (no urgente, sin sirena); se cierra sola al volver a la banda, al cerrar el lote o si el
 * producto deja de tener línea base. Recién vigila con una hora de lote (el promedio necesita la hora completa).
 */
@Service
public class VigilanciaLoteService {

    private static final Logger log = LoggerFactory.getLogger(VigilanciaLoteService.class);

    public static final int MINUTOS_PROMEDIO = 60;
    public static final int MINUTOS_FUERA = 30;

    private final LoteProduccionRepository lotes;
    private final LotesService lotesService;
    private final LineaBaseProductoService lineaBase;
    private final AlarmaEventoRepository alarmas;

    /** Línea -> desde cuándo y hacia dónde está fuera de la banda ("sobre" o "bajo"). En memoria. */
    private final Map<String, Fuera> fuera = new ConcurrentHashMap<>();

    private record Fuera(LocalDateTime desde, String sentido) {
    }

    public VigilanciaLoteService(LoteProduccionRepository lotes, LotesService lotesService,
                                 LineaBaseProductoService lineaBase, AlarmaEventoRepository alarmas) {
        this.lotes = lotes;
        this.lotesService = lotesService;
        this.lineaBase = lineaBase;
        this.alarmas = alarmas;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void revisar() {
        try {
            Set<String> vigiladas = new HashSet<>();
            for (LoteProduccion l : lotes.findByFinIsNull()) {
                if (LotesService.SIN_ITEM.equals(l.getProducto())) continue;
                if (revisar(l)) vigiladas.add(l.getLinea());
            }
            // Alarmas abiertas de líneas que ya no se vigilan (lote cerrado, borrado o sin línea base): se cierran.
            for (AlarmaEvento a : alarmas.findByTipoAlarmaAndActivaTrue(TipoAlarma.CONSUMO_LOTE)) {
                if (!vigiladas.contains(a.getLineaMaquina())) cerrar(a, "lote cerrado o sin línea base");
            }
            fuera.keySet().retainAll(vigiladas);
        } catch (Exception e) {
            log.warn("Vigilancia de lotes: {}", e.toString());
        }
    }

    /** true si la línea se pudo vigilar (lote con línea base válida y datos de la última hora). */
    private boolean revisar(LoteProduccion l) {
        String linea = l.getLinea();
        Optional<LineaBaseProductoService.Banda> ob = lineaBase.banda(linea, l.getProducto());
        if (ob.isEmpty()) return false;
        LineaBaseProductoService.Banda b = ob.get();
        LocalDateTime ahora = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        Lectura fin = ultimaLectura(linea, ahora);
        if (fin == null) return true; // sin dato reciente: se mantiene el estado
        LocalDateTime desde = fin.cuando().minusMinutes(MINUTOS_PROMEDIO);
        if (desde.isBefore(l.getInicio())) return true; // menos de una hora de lote
        Lectura ini = ultimaLectura(linea, desde);
        if (ini == null || !fin.cuando().isAfter(ini.cuando())) return true;
        double min = Duration.between(ini.cuando(), fin.cuando()).toSeconds() / 60.0;
        double prom = (fin.kwh() - ini.kwh()) / min;
        String sentido = prom > b.maximo() ? "sobre" : prom < b.minimo() ? "bajo" : null;

        List<AlarmaEvento> abiertas = alarmas.findByLineaMaquinaAndTipoAlarmaAndActivaTrue(linea, TipoAlarma.CONSUMO_LOTE);
        AlarmaEvento abierta = abiertas.isEmpty() ? null : abiertas.getFirst();
        if (sentido == null) {
            fuera.remove(linea);
            if (abierta != null) cerrar(abierta, String.format("vuelve a la banda: %s kWh/min", n(prom)));
            return true;
        }
        Fuera f = fuera.get(linea);
        if (f == null || !f.sentido().equals(sentido)) {
            f = new Fuera(fin.cuando(), sentido);
            fuera.put(linea, f);
            if (abierta != null) cerrar(abierta, "cambia de lado de la banda");
            abierta = null;
        }
        long minFuera = Duration.between(f.desde(), fin.cuando()).toMinutes();
        if (abierta == null && minFuera >= MINUTOS_FUERA) {
            AlarmaEvento ev = new AlarmaEvento();
            ev.setLineaMaquina(linea);
            ev.setTipoAlarma(TipoAlarma.CONSUMO_LOTE);
            ev.setMensaje(mensaje(linea, l.getProducto(), sentido, prom, b));
            ev.setFechaInicio(f.desde());
            ev.setActiva(true);
            ev.setUrgente(false);
            alarmas.save(ev);
            log.warn("Alarma: {}", ev.getMensaje());
        } else if (abierta != null) {
            String m = mensaje(linea, l.getProducto(), sentido, prom, b);
            if (!m.equals(abierta.getMensaje())) {
                abierta.setMensaje(m);
                alarmas.save(abierta);
            }
        }
        return true;
    }

    private void cerrar(AlarmaEvento a, String motivo) {
        a.setActiva(false);
        a.setFechaFin(LocalDateTime.now());
        a.setMensaje(corto(a.getMensaje() + " (resuelta: " + motivo + ")"));
        alarmas.save(a);
        log.info("Alarma resuelta: {}", a.getMensaje());
    }

    /** La columna del mensaje es de 255 caracteres. */
    private static String corto(String m) {
        return m.length() <= 255 ? m : m.substring(0, 254) + "…";
    }

    private static String mensaje(String linea, String producto, String sentido, double prom, LineaBaseProductoService.Banda b) {
        return corto(linea.replace("Linea", "Línea ") + ": consumo " + ("sobre".equals(sentido) ? "sobre el máximo" : "bajo el mínimo")
                + " de la línea base " + (b.aplicada().dePropia() ? "del producto " + producto : "de la línea (producto " + producto + ")")
                + ". Promedio de la última hora " + n(prom) + " kWh/min (objetivo "
                + n(b.objetivo()) + ", " + ("sobre".equals(sentido) ? "máximo " + n(b.maximo()) : "mínimo " + n(b.minimo())) + ")");
    }

    private static String n(double v) {
        return LineaBaseProductoService.num(v, 3);
    }

    private record Lectura(LocalDateTime cuando, double kwh) {
    }

    /** Contador guardado en ese minuto o en alguno de los 5 anteriores (la lectura es cada 60 s). */
    private Lectura ultimaLectura(String linea, LocalDateTime momento) {
        for (int i = 0; i <= 5; i++) {
            LocalDateTime t = momento.minusMinutes(i);
            Optional<Double> k = lotesService.kwhGuardado(linea, t);
            if (k.isPresent()) return new Lectura(t, k.get());
        }
        return null;
    }
}
