package com.example.lotes.service;

import com.example.lotes.service.LotesExcelReader.LoteExcel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

/**
 * Línea base por producto y por línea (ISO 50006), decidido con el usuario el 2026-11-13 (opción B).
 * <ul>
 *   <li>Período base = el año calendario anterior completo ({@link #anioBase()}: en 2026 es 2025, en 2027 será 2026), lotes
 *   de la hoja MEDICION_XTR del Excel de medición de ese año. Los lotes del año en curso solo se comparan; nunca entran.</li>
 *   <li>Regresión kWh = m × kg + b (atípicos a más de 2 sigma solo si R² < 0,75). Prueba t del intercepto: si b no es
 *   significativo (p > 0,05), positivo o negativo, se quita y el modelo es kWh = m × kg ({@link #evaluarConOrigen}).
 *   Válida si pasa: al menos 3 lotes, R² >= 0,75, prueba F con p < 0,05, m > 0 y, si b queda, b > 0.</li>
 *   <li>Por producto: los lotes del producto en su línea. Por línea: todos los lotes de la línea.</li>
 *   <li>{@link #aplicable}: la del producto si es válida; si no, la de su misma línea, con el motivo.</li>
 * </ul>
 * Los libros se leen una vez y quedan en memoria; {@link #recargar()} vuelve a leerlos.
 */
@Service
public class LineaBaseProductoService {

    private static final Logger log = LoggerFactory.getLogger(LineaBaseProductoService.class);

    public static final double R2_MINIMO = 0.75;
    public static final double P_MAXIMO = 0.05;
    public static final int LOTES_MINIMO = 3;

    /** Año base: el año calendario anterior al de hoy. */
    public static int anioBase() {
        return java.time.LocalDate.now().getYear() - 1;
    }

    public record Prueba(String nombre, String resultado, String requisito, boolean cumple, String motivo) {
    }

    /** Regresión y pruebas de un grupo de lotes (producto) o de meses (línea). */
    /**
     * n = lotes usados (sin los atípicos quitados); atipicos = índices quitados en la lista original; r2Inicial = R² con
     * todos los lotes, antes de quitar.
     */
    public record Evaluacion(int n, int total, Regresion r, List<Prueba> pruebas, boolean valida, String motivo,
                             Set<Integer> atipicos, Double r2Inicial) {
        public double objetivo(double kg) { return r.m() * kg + r.b(); }
        public boolean atipico(int i) { return atipicos.contains(i); }
    }

    /**
     * Línea base de un producto en su línea, o de toda la línea (clave null). lotes = los evaluados (año base, en orden de
     * inicio); anio = año base.
     */
    public record Producto(String linea, String clave, String nombre, List<LoteExcel> lotes, Evaluacion ev, int anio) {
        public boolean esLinea() { return clave == null; }
    }

    /**
     * Línea base que se usa para un producto: la suya si es válida; si no, la de su línea. producto = la del producto (null
     * si no tuvo lotes en el año base); motivo = por qué no se usa la del producto (null si se usa).
     */
    public record Aplicada(Producto lineaBase, Producto producto, String motivo) {
        public boolean dePropia() { return motivo == null; }
    }

    /**
     * Banda de Tiempo Real en kWh/min. ritmoDelProducto = el ritmo (kg/min y duración) sale de los lotes del producto en el
     * año base; si es false, del promedio de la línea.
     */
    public record Banda(Aplicada aplicada, double kgPorMin, double minutosMedios, double objetivo, double delta,
                        boolean ritmoDelProducto) {
        public Producto producto() { return aplicada.lineaBase(); }
        public double maximo() { return objetivo + delta; }
        public double minimo() { return Math.max(objetivo - delta, 0); }
    }

    public record Datos(int anioBase, List<LoteExcel> base, List<LoteExcel> comparacion, int anioComparacion, List<String> archivos,
                        String error, LocalDateTime leido) {
    }

    private volatile Datos datos;
    private final Map<String, Evaluacion> cache = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void cargarAlArrancar() {
        Thread.ofVirtual().start(this::datos);
    }

    public Datos datos() {
        Datos d = datos;
        if (d == null) {
            synchronized (this) {
                if (datos == null) datos = leer();
                d = datos;
            }
        }
        return d;
    }

    public synchronized Datos recargar() {
        cache.clear();
        datos = leer();
        return datos;
    }

    private Datos leer() {
        List<LoteExcel> base = List.of(), comp = List.of();
        List<String> archivos = new ArrayList<>();
        StringBuilder err = new StringBuilder();
        File fBase = null, fComp = null;
        int aComp = 0, anioBase = anioBase();
        for (File f : CatalogoProductosService.archivosMasRecientes()) {
            Matcher m = CatalogoProductosService.ARCHIVO.matcher(f.getName());
            if (!m.matches()) continue;
            int anio = 2000 + Integer.parseInt(m.group(2));
            if (anio == anioBase) fBase = f;
            else if (anio > anioBase && anio > aComp) { fComp = f; aComp = anio; }
        }
        if (fBase == null) err.append("No se encontró el Excel de medición de ").append(anioBase).append(" en ")
                .append(CatalogoProductosService.CARPETA).append(". ");
        try {
            if (fBase != null) { base = LotesExcelReader.leer(fBase, anioBase); archivos.add(fBase.getName()); }
        } catch (Exception e) {
            log.warn("No se pudo leer {}: {}", fBase, e.toString());
            err.append("No se pudo leer ").append(fBase.getName()).append(": ").append(e.getMessage()).append(". ");
        }
        try {
            if (fComp != null) { comp = LotesExcelReader.leer(fComp, aComp); archivos.add(fComp.getName()); }
        } catch (Exception e) {
            log.warn("No se pudo leer {}: {}", fComp, e.toString());
            err.append("No se pudo leer ").append(fComp.getName()).append(": ").append(e.getMessage()).append(". ");
        }
        log.info("Línea base: {} lotes de {} y {} de {} ({})", base.size(), anioBase, comp.size(), aComp, archivos);
        return new Datos(anioBase, base, comp, aComp, archivos, err.isEmpty() ? null : err.toString().trim(), LocalDateTime.now());
    }

    // ---------------- Consultas ----------------

    public List<String> lineas() {
        TreeSet<String> s = new TreeSet<>();
        for (LoteExcel x : datos().base()) s.add(x.linea());
        return new ArrayList<>(s);
    }

    /** Productos de la línea con lotes en el año base, en orden alfabético. */
    public List<Producto> productos(String linea) {
        TreeSet<String> claves = new TreeSet<>();
        for (LoteExcel x : datos().base()) if (x.linea().equals(linea)) claves.add(x.clave());
        List<Producto> out = new ArrayList<>();
        for (String c : claves) out.add(armar(linea, c));
        out.sort(Comparator.comparing(Producto::nombre, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Línea base del producto (el nombre del catálogo o el ITEM del Excel). Vacío si no tuvo lotes en el año base. */
    public Optional<Producto> producto(String linea, String nombre) {
        if (linea == null || nombre == null) return Optional.empty();
        String clave = clave(nombre);
        boolean hay = datos().base().stream().anyMatch(x -> x.linea().equals(linea) && x.clave().equals(clave));
        return hay ? Optional.of(armar(linea, clave)) : Optional.empty();
    }

    /** Línea base de toda la línea: todos sus lotes del año base. Vacío si la línea no tuvo lotes. */
    public Optional<Producto> deLinea(String linea) {
        if (linea == null) return Optional.empty();
        List<LoteExcel> l = datos().base().stream().filter(x -> x.linea().equals(linea))
                .sorted(Comparator.comparing(LoteExcel::inicio)).toList();
        if (l.isEmpty()) return Optional.empty();
        Evaluacion ev = cache.computeIfAbsent(linea + "|*", k -> evaluarConOrigen(puntos(l)));
        return Optional.of(new Producto(linea, null, linea.replace("Linea", "Línea ") + " (todos los productos)", l, ev, datos().anioBase()));
    }

    /**
     * Línea base que se usa para el producto elegido: la suya si es válida; si no, la de su línea con el motivo. Vacío si
     * la línea no tuvo lotes en el año base. nombre null = la de la línea sin producto.
     */
    public Optional<Aplicada> aplicable(String linea, String nombre) {
        Optional<Producto> pl = deLinea(linea);
        if (pl.isEmpty()) return Optional.empty();
        if (nombre == null) return Optional.of(new Aplicada(pl.get(), null, "sin producto elegido"));
        Optional<Producto> pp = producto(linea, nombre);
        if (pp.isPresent() && pp.get().ev().valida()) return Optional.of(new Aplicada(pp.get(), pp.get(), null));
        String motivo = pp.map(p -> p.ev().motivo()).orElse("sin lotes en " + datos().anioBase());
        return Optional.of(new Aplicada(pl.get(), pp.orElse(null), motivo));
    }

    /** true si el producto tiene línea base propia válida (para marcar la lista). */
    public boolean tienePropia(String linea, String nombre) {
        return producto(linea, nombre).map(p -> p.ev().valida()).orElse(false);
    }

    private Producto armar(String linea, String clave) {
        List<LoteExcel> l = datos().base().stream().filter(x -> x.linea().equals(linea) && x.clave().equals(clave))
                .sorted(Comparator.comparing(LoteExcel::inicio)).toList();
        Evaluacion ev = cache.computeIfAbsent(linea + "|" + clave, k -> evaluarConOrigen(puntos(l)));
        return new Producto(linea, clave, l.getLast().item(), l, ev, datos().anioBase());
    }

    private static List<double[]> puntos(List<LoteExcel> l) {
        return l.stream().map(x -> new double[]{x.kg(), x.kwh()}).toList();
    }

    private static String clave(String nombre) {
        return nombre.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /** Lotes del año en curso que se comparan contra esa línea base (los del producto, o todos los de la línea). */
    public List<LoteExcel> comparacion(Producto p) {
        return datos().comparacion().stream().filter(x -> x.linea().equals(p.linea()) && (p.esLinea() || x.clave().equals(p.clave())))
                .sorted(Comparator.comparing(LoteExcel::inicio)).toList();
    }

    /**
     * Banda de Tiempo Real con la línea base aplicable (la del producto o la de su línea): objetivo = m × kg/min + b /
     * minutos del lote. kg/min y minutos del lote (duración media) salen de los lotes del producto en el año base si los
     * tuvo; si no, de los lotes de la línea. Máximo y mínimo = ± 2 sigma / minutos. Vacío si la línea base aplicable no es
     * válida.
     */
    public Optional<Banda> banda(String linea, String nombre) {
        return aplicable(linea, nombre).filter(a -> a.lineaBase().ev().valida()).map(a -> {
            Producto lb = a.lineaBase();
            // Ritmo: los lotes del producto (sin los atípicos si su línea base es la propia), o los de la línea.
            List<LoteExcel> ritmo = new ArrayList<>();
            boolean delProducto = a.producto() != null;
            Producto fuente = delProducto ? a.producto() : lb;
            for (int i = 0; i < fuente.lotes().size(); i++) {
                if (fuente.ev().valida() && fuente.ev().atipico(i)) continue;
                ritmo.add(fuente.lotes().get(i));
            }
            double kg = 0, min = 0;
            for (LoteExcel x : ritmo) { kg += x.kg(); min += x.minutos(); }
            double r = kg / min, tm = min / ritmo.size();
            Regresion rg = lb.ev().r();
            return new Banda(a, r, tm, rg.m() * r + rg.b() / tm, 2 * rg.se() / tm, delProducto);
        });
    }

    // ---------------- Pruebas ----------------

    /**
     * Si con todos los lotes R² < 0,75, se quita el lote más alejado de la recta si está a más de 2 sigma y se vuelve a
     * ajustar, hasta llegar a 0,75 o quedar {@link #LOTES_MINIMO} lotes. Después se corren las cinco pruebas con los que
     * quedan. Con R² >= 0,75 de entrada no se quita ninguno.
     */
    static Evaluacion evaluar(List<double[]> todos) {
        Set<Integer> fuera = new TreeSet<>();
        Regresion r0 = Regresion.de(todos);
        Regresion r = r0;
        while (r != null && r.se() != null && r.r2() < R2_MINIMO && todos.size() - fuera.size() > LOTES_MINIMO) {
            int peor = -1;
            double dmax = 0;
            for (int i = 0; i < todos.size(); i++) {
                if (fuera.contains(i)) continue;
                double d = Math.abs(todos.get(i)[1] - (r.m() * todos.get(i)[0] + r.b()));
                if (d > 2 * r.se() && d > dmax) { dmax = d; peor = i; }
            }
            if (peor < 0) break;
            fuera.add(peor);
            List<double[]> quedan = new ArrayList<>();
            for (int i = 0; i < todos.size(); i++) if (!fuera.contains(i)) quedan.add(todos.get(i));
            r = Regresion.de(quedan);
        }
        int n = todos.size() - fuera.size();
        boolean conF = r != null && r.p() != null;
        List<Prueba> pr = List.of(
                new Prueba("Lotes", fuera.isEmpty() ? String.valueOf(n) : n + " de " + todos.size(), "al menos " + LOTES_MINIMO, n >= LOTES_MINIMO, "menos de " + LOTES_MINIMO + " lotes"),
                new Prueba("R²", conF ? num(r.r2(), 3) + (fuera.isEmpty() ? "" : " (antes " + num(r0.r2(), 3) + ")") : "—", "≥ 0,75", conF && r.r2() >= R2_MINIMO, "R² bajo"),
                new Prueba("Prueba F (p)", conF ? p(r.p()) : "—", "p < 0,05", conF && r.p() < P_MAXIMO, "prueba F no significativa"),
                new Prueba("Pendiente m", r == null ? "—" : num(r.m(), 4) + " kWh/kg", "m > 0", r != null && r.m() > 0, "pendiente no positiva"),
                new Prueba("Intercepto b", r == null ? "—" : num(r.b(), 0) + " kWh", "b ≥ 0", r != null && r.b() >= 0, "intercepto negativo"));
        boolean ok = pr.stream().allMatch(Prueba::cumple);
        // Con menos de 3 lotes fallan todas las pruebas; el motivo real es la falta de lotes.
        String motivo = n < LOTES_MINIMO ? (n == 1 ? "solo 1 lote" : "solo " + n + " lotes") + " en el año base (mínimo " + LOTES_MINIMO + ")"
                : String.join("; ", pr.stream().filter(x -> !x.cumple()).map(Prueba::motivo).toList());
        return new Evaluacion(n, todos.size(), r, pr, ok, motivo, fuera, r0 == null ? null : r0.r2());
    }

    /**
     * {@link #evaluar} con la prueba t del intercepto (hipótesis nula b = 0, α = 0,05; Newbold, Carlson y Thorne, ec. 11.20,
     * aplicada al intercepto), decidido con el usuario el 2026-11-13. Si b no es significativo, sea positivo o negativo, se
     * quita y el modelo es proporcional (kWh = m × kg, {@link Regresion#porOrigen}) con los mismos lotes (sin los atípicos);
     * se vuelven a pedir R², prueba F y m > 0. Si b es significativo, se queda y tiene que ser positivo (lo exige evaluar).
     */
    static Evaluacion evaluarConOrigen(List<double[]> todos) {
        Evaluacion e = evaluar(todos);
        Regresion r = e.r();
        if (r == null || r.pB() == null || r.pB() <= P_MAXIMO) return e;
        List<double[]> quedan = new ArrayList<>();
        for (int i = 0; i < todos.size(); i++) if (!e.atipico(i)) quedan.add(todos.get(i));
        Regresion o = Regresion.porOrigen(quedan);
        if (o == null || o.p() == null) return e;
        List<Prueba> pr = List.of(
                e.pruebas().getFirst(),
                new Prueba("R²", num(o.r2(), 3) + " (modelo proporcional)", "≥ 0,75", o.r2() >= R2_MINIMO, "R² bajo"),
                new Prueba("Prueba F (p)", p(o.p()), "p < 0,05", o.p() < P_MAXIMO, "prueba F no significativa"),
                new Prueba("Pendiente m", num(o.m(), 4) + " kWh/kg", "m > 0", o.m() > 0, "pendiente no positiva"),
                new Prueba("Intercepto b", num(r.b(), 0) + " kWh, no significativo (prueba t, p " + p(r.pB()) + "): se quita",
                        "b significativo y > 0, o se quita", true, ""));
        boolean ok = quedan.size() >= LOTES_MINIMO && pr.stream().allMatch(Prueba::cumple);
        String motivo = ok ? "" : String.join("; ", pr.stream().filter(x -> !x.cumple()).map(Prueba::motivo).toList());
        return new Evaluacion(quedan.size(), todos.size(), o, pr, ok, motivo, e.atipicos(), e.r2Inicial());
    }

    public static String p(double p) {
        return p < 0.0001 ? "< 0,0001" : num(p, 4);
    }

    /** Número con punto de miles y coma decimal. */
    public static String num(double v, int dec) {
        String s = String.format(Locale.US, "%,." + dec + "f", v);
        return s.replace(',', '#').replace('.', ',').replace('#', '.');
    }
}
