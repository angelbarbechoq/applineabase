package com.example.lotes.service;

import com.example.lotes.service.LotesExcelReader.LoteExcel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.YearMonth;
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
 * Línea base por producto (ISO 50006). Período de línea base = los lotes de {@link #ANIO_BASE} del Excel de medición
 * de ese año (hoja MEDICION_XTR); los lotes del año más reciente se comparan contra ella y nunca la modifican.
 * Regresión kWh = m × kg + b con todos los lotes del producto en la línea, sin quitar ninguno. Se usa solo si pasa las
 * cinco pruebas (al menos 3 lotes, R² >= 0,75, prueba F con p < 0,05, m > 0, b >= 0); si no, el producto queda sin
 * referencia válida y se controla con la línea base mensual de la línea (suma de los lotes de cada mes del año base).
 * Los libros se leen una vez y quedan en memoria; {@link #recargar()} vuelve a leerlos.
 */
@Service
public class LineaBaseProductoService {

    private static final Logger log = LoggerFactory.getLogger(LineaBaseProductoService.class);

    public static final int ANIO_BASE = 2025;
    public static final double R2_MINIMO = 0.75;
    public static final double P_MAXIMO = 0.05;
    public static final int LOTES_MINIMO = 3;

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

    public record Producto(String linea, String clave, String nombre, List<LoteExcel> lotes, Evaluacion ev) {
    }

    /** Banda de Tiempo Real en kWh/min y el ritmo de producción del año base. */
    public record Banda(Producto producto, double kgPorMin, double minutosMedios, double objetivo, double delta) {
        public double maximo() { return objetivo + delta; }
        public double minimo() { return Math.max(objetivo - delta, 0); }
    }

    public record Mes(YearMonth mes, double kwh, double kg) {
    }

    public record Mensual(String linea, List<Mes> meses, Evaluacion ev) {
    }

    public record Datos(List<LoteExcel> base, List<LoteExcel> comparacion, int anioComparacion, List<String> archivos,
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
        int aComp = 0;
        for (File f : CatalogoProductosService.archivosMasRecientes()) {
            Matcher m = CatalogoProductosService.ARCHIVO.matcher(f.getName());
            if (!m.matches()) continue;
            int anio = 2000 + Integer.parseInt(m.group(2));
            if (anio == ANIO_BASE) fBase = f;
            else if (anio > ANIO_BASE && anio > aComp) { fComp = f; aComp = anio; }
        }
        if (fBase == null) err.append("No se encontró el Excel de medición de ").append(ANIO_BASE).append(" en ")
                .append(CatalogoProductosService.CARPETA).append(". ");
        try {
            if (fBase != null) { base = LotesExcelReader.leer(fBase, ANIO_BASE); archivos.add(fBase.getName()); }
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
        log.info("Línea base por producto: {} lotes de {} y {} de {} ({})", base.size(), ANIO_BASE, comp.size(), aComp, archivos);
        return new Datos(base, comp, aComp, archivos, err.isEmpty() ? null : err.toString().trim(), LocalDateTime.now());
    }

    // ---------------- Consultas ----------------

    public List<String> lineas() {
        TreeSet<String> s = new TreeSet<>();
        for (LoteExcel x : datos().base()) s.add(x.linea());
        return new ArrayList<>(s);
    }

    /** Productos de la línea con lotes en el año base, en orden alfabético. */
    public List<Producto> productos(String linea) {
        Map<String, List<LoteExcel>> g = new TreeMap<>();
        for (LoteExcel x : datos().base()) {
            if (x.linea().equals(linea)) g.computeIfAbsent(x.clave(), k -> new ArrayList<>()).add(x);
        }
        List<Producto> out = new ArrayList<>();
        g.forEach((clave, l) -> out.add(producto(linea, clave, l)));
        out.sort(Comparator.comparing(Producto::nombre, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Producto por su nombre (el del catálogo o el ITEM del Excel). Vacío si no tiene lotes en el año base. */
    public Optional<Producto> producto(String linea, String nombre) {
        if (linea == null || nombre == null) return Optional.empty();
        String clave = nombre.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        List<LoteExcel> l = datos().base().stream().filter(x -> x.linea().equals(linea) && x.clave().equals(clave)).toList();
        return l.isEmpty() ? Optional.empty() : Optional.of(producto(linea, clave, l));
    }

    private Producto producto(String linea, String clave, List<LoteExcel> l) {
        List<LoteExcel> ord = new ArrayList<>(l);
        ord.sort(Comparator.comparing(LoteExcel::inicio));
        Evaluacion ev = cache.computeIfAbsent(linea + "|" + clave,
                k -> evaluar(ord.stream().map(x -> new double[]{x.kg(), x.kwh()}).toList()));
        return new Producto(linea, clave, ord.getLast().item(), ord, ev);
    }

    /** Lotes del año de comparación de ese producto. */
    public List<LoteExcel> comparacion(String linea, String clave) {
        return datos().comparacion().stream().filter(x -> x.linea().equals(linea) && x.clave().equals(clave))
                .sorted(Comparator.comparing(LoteExcel::inicio)).toList();
    }

    /** Línea base mensual del año base: suma de kWh y kg de los lotes que terminan en cada mes. */
    public Mensual mensual(String linea) {
        Map<YearMonth, double[]> g = new TreeMap<>();
        for (LoteExcel x : datos().base()) {
            if (!x.linea().equals(linea)) continue;
            double[] a = g.computeIfAbsent(YearMonth.from(x.fin()), k -> new double[2]);
            a[0] += x.kwh();
            a[1] += x.kg();
        }
        List<Mes> meses = new ArrayList<>();
        g.forEach((m, a) -> meses.add(new Mes(m, a[0], a[1])));
        Evaluacion ev = cache.computeIfAbsent("mes|" + linea, k -> evaluar(meses.stream().map(x -> new double[]{x.kg(), x.kwh()}).toList()));
        return new Mensual(linea, meses, ev);
    }

    /**
     * Banda de Tiempo Real del producto: objetivo = m × kg/min + b / minutos del lote, con kg/min = kg / minutos de los
     * lotes del año base del producto y minutos del lote = su duración media. Máximo y mínimo = ± 2 sigma / minutos.
     * Vacío si el producto no tiene línea base válida.
     */
    public Optional<Banda> banda(String linea, String nombre) {
        return producto(linea, nombre).filter(p -> p.ev().valida()).map(p -> {
            double kg = 0, min = 0;
            int n = 0;
            for (int i = 0; i < p.lotes().size(); i++) {
                if (p.ev().atipico(i)) continue;
                LoteExcel x = p.lotes().get(i);
                kg += x.kg();
                min += x.minutos();
                n++;
            }
            double r = kg / min, tm = min / n;
            Regresion rg = p.ev().r();
            return new Banda(p, r, tm, rg.m() * r + rg.b() / tm, 2 * rg.se() / tm);
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
        String motivo = String.join("; ", pr.stream().filter(x -> !x.cumple()).map(Prueba::motivo).toList());
        return new Evaluacion(n, todos.size(), r, pr, ok, motivo, fuera, r0 == null ? null : r0.r2());
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
