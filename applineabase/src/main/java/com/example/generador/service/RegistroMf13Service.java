package com.example.generador.service;

import com.example.generador.model.ConsumoMensualGenerador;
import com.example.generador.model.Generador;
import com.example.generador.model.LecturaGenerador;
import com.example.generador.model.ParametroGenerador;
import com.example.generador.model.RegistroMf13;
import com.example.generador.repository.ConsumoMensualGeneradorRepository;
import com.example.generador.repository.RegistroMf13Repository;
import com.example.generador.service.PlanillaCombustibleService.Medida;
import com.example.generador.service.PlanillaCombustibleService.Planilla;
import com.example.mantenimiento.model.TecnicoMantenimiento;
import com.example.mantenimiento.repository.TecnicoMantenimientoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registro MF-13 "Control de generador" (reemplaza a la planilla de Excel de los operadores) y el consumo de diésel y
 * energía que sale de él: por mes, por período de marcha y las observaciones. Los meses anteriores al registro salen
 * del histórico {@link ConsumoMensualGenerador}.
 *
 * <p>Cálculo, igual que con la planilla: la energía y las horas son la diferencia de los contadores del controlador
 * entre registros; el diésel es la diferencia del contador del motor (Caterpillar) o lo que bajó el nivel del tanque
 * más lo cargado (GenPower, según la medida de {@code combustible} en generador-config.json). Una lectura que no sigue
 * la secuencia de sus vecinas (ej. 4.749 anotado por 474) no se usa.
 */
@Service
public class RegistroMf13Service {

    /** Generador viejo sin controlador ni medidor que solo figura en el histórico. */
    public static final String KOHLER = "Kohler";

    /** Marca de las filas del mes anterior importadas solo como punto de partida del cálculo. */
    private static final String REFERENCIA = "(referencia del mes anterior)";

    private static boolean esReferencia(RegistroMf13 r) {
        return r.getOrigen() != null && r.getOrigen().contains(REFERENCIA);
    }

    private static final Pattern NUMERO = Pattern.compile("^\\s*(-?\\d+(?:[.,]\\d+)?)");
    private static final Pattern HORA_TEXTO = Pattern.compile("(\\d{1,2})\\s*[:hH;.]\\s*(\\d{2})");
    private static final Pattern GALONES = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*GL");

    private final RegistroMf13Repository repo;
    private final ConsumoMensualGeneradorRepository historico;
    private final GeneradorService generadorService;
    private final PlanillaCombustibleService planillas;
    private final TecnicoMantenimientoRepository tecnicos;

    public RegistroMf13Service(RegistroMf13Repository repo, ConsumoMensualGeneradorRepository historico,
                               GeneradorService generadorService, PlanillaCombustibleService planillas,
                               TecnicoMantenimientoRepository tecnicos) {
        this.repo = repo;
        this.historico = historico;
        this.generadorService = generadorService;
        this.planillas = planillas;
        this.tecnicos = tecnicos;
    }

    public List<Generador> generadores() {
        return generadorService.generadores();
    }

    /** Nombres del catálogo de Personal de Mantenimiento, para el campo Responsable. */
    public List<String> tecnicos() {
        return tecnicos.findAllByOrderByNombreAsc().stream().map(TecnicoMantenimiento::getNombre).toList();
    }

    /** true si el diésel del generador se mide con el contador del motor (Caterpillar); false = nivel del tanque. */
    public boolean usaContador(String generador) {
        Planilla p = planillas.planilla(generador);
        return p != null && p.medida() == Medida.CONTADOR;
    }

    public List<RegistroMf13> registros(String generador, YearMonth mes) {
        return repo.findByGeneradorAndFechaHoraBetweenOrderByFechaHoraAscIdAsc(generador, mes.atDay(1).atStartOfDay(),
                mes.atEndOfMonth().atTime(LocalTime.MAX));
    }

    private List<RegistroMf13> todos(String generador) {
        return repo.findByGeneradorOrderByFechaHoraAscIdAsc(generador);
    }

    // ------------------------------------------------------------------ registro nuevo

    /** Valores leídos del controlador para el registro; error != null si no contestó. */
    public record Controlador(Double presAceite, Double temperatura, Double cargaKw, Double prodKwh, Double horas,
                              Double bateria, String error) {
    }

    public Controlador leerControlador(String generador) {
        Optional<Generador> g = generadores().stream().filter(x -> x.nombre().equals(generador)).findFirst();
        if (g.isEmpty()) return new Controlador(null, null, null, null, null, null, "Generador no configurado");
        try {
            LecturaGenerador l = generadorService.leerEnVivo(g.get());
            return new Controlador(l.presionAceite(), l.tempRefrigerante(), l.kw(), l.valor(ParametroGenerador.KWH),
                    l.valor(ParametroGenerador.HORAS_MARCHA), l.bateria(), null);
        } catch (IOException | RuntimeException e) {
            return new Controlador(null, null, null, null, null, null, "El controlador no contesta: " + e.getMessage());
        }
    }

    /** Problemas que impiden guardar (vacía = se puede guardar). */
    public List<String> validar(RegistroMf13 r) {
        List<String> errores = new ArrayList<>();
        if (r.getFechaHora() == null) errores.add("Falta la fecha y hora.");
        else if (r.getFechaHora().isAfter(LocalDateTime.now().plusMinutes(5))) errores.add("La fecha y hora no puede ser futura.");
        if (r.getMomento() == null) errores.add("Falta el momento (arranque, control, parada o revisión).");
        if (r.getResponsable() == null || r.getResponsable().isBlank()) errores.add("Falta el responsable.");
        if (!errores.isEmpty()) return errores;
        List<RegistroMf13> lista = todos(r.getGenerador()).stream().filter(x -> !x.getId().equals(r.getId())).toList();
        comparar(errores, "La energía (Prod kWh)", r.getProdKwh(), ultimo(lista, r, RegistroMf13::getProdKwh, true),
                ultimo(lista, r, RegistroMf13::getProdKwh, false));
        comparar(errores, "Las horas", r.getHoras(), ultimo(lista, r, RegistroMf13::getHoras, true),
                ultimo(lista, r, RegistroMf13::getHoras, false));
        comparar(errores, "El contador de diésel", r.getContadorDiesel(), ultimo(lista, r, RegistroMf13::getContadorDiesel, true),
                ultimo(lista, r, RegistroMf13::getContadorDiesel, false));
        Double nivelAntes = ultimo(lista, r, RegistroMf13::getNivelCm, true);
        if (r.getNivelCm() != null && nivelAntes != null && r.getNivelCm() > nivelAntes + 0.5
                && (r.getDieselCargado() == null || r.getDieselCargado() <= 0)) {
            errores.add(String.format(Locale.ROOT, "El nivel del tanque subió de %.1f a %.1f cm: anote los galones de diésel cargados.",
                    nivelAntes, r.getNivelCm()).replace('.', ','));
        }
        return errores;
    }

    /** Último valor válido antes (o primero después) del registro; las lecturas mal anotadas no cuentan. */
    private static Double ultimo(List<RegistroMf13> lista, RegistroMf13 r, Function<RegistroMf13, Double> campo, boolean antes) {
        Double v = null;
        for (RegistroMf13 x : validos(lista, campo)) {
            boolean esAntes = !x.getFechaHora().isAfter(r.getFechaHora());
            if (antes && esAntes && campo.apply(x) != null) v = campo.apply(x);
            if (!antes && !esAntes && campo.apply(x) != null) return campo.apply(x);
        }
        return v;
    }

    private static void comparar(List<String> errores, String nombre, Double valor, Double antes, Double despues) {
        if (valor == null) return;
        if (antes != null && valor < antes) {
            errores.add(nombre + " no puede ser menor que la del registro anterior (" + texto(antes) + ").");
        }
        if (despues != null && valor > despues) {
            errores.add(nombre + " no puede ser mayor que la del registro siguiente (" + texto(despues) + ").");
        }
    }

    private static String texto(double v) {
        return String.format(Locale.ROOT, "%.1f", v).replace('.', ',');
    }

    @Transactional
    public RegistroMf13 guardar(RegistroMf13 r, String usuario) {
        if (r.getDieselCargado() != null && r.getDieselCargado() > 0) r.setObsCargaDiesel(true);
        if (r.getOrigen() == null) r.setOrigen("Registro en la app");
        r.setUsuario(usuario);
        r.setFechaRegistro(LocalDateTime.now());
        return repo.save(r);
    }

    @Transactional
    public void borrar(RegistroMf13 r) {
        repo.delete(r);
    }

    // ------------------------------------------------------------------ consumo

    /**
     * Consumo de un generador en un mes.
     *
     * @param galonesEstimados parte del diésel que sale de cargas sin cantidad anotada (estimadas al importar)
     * @param cargasSinCantidad tramos en que el nivel subió sin cantidad cargada: ese consumo no se pudo calcular
     */
    public record Consumo(double galones, Double kwh, Double horas, int cargas, double galonesEstimados,
                          int cargasSinCantidad, String origen, String nota) {
        public Double kwhPorGalon() {
            return kwh == null || galones <= 0 ? null : kwh / galones;
        }
    }

    public record FilaMes(YearMonth mes, Map<String, Consumo> porGenerador) {
        public double galones() {
            return porGenerador.values().stream().mapToDouble(Consumo::galones).sum();
        }

        public double kwh() {
            return porGenerador.values().stream().mapToDouble(c -> c.kwh() == null ? 0 : c.kwh()).sum();
        }

        public double horas() {
            return porGenerador.values().stream().mapToDouble(c -> c.horas() == null ? 0 : c.horas()).sum();
        }
    }

    /** Columnas de la tabla mensual: los generadores configurados y los del histórico que ya no están (Kohler). */
    public List<String> nombresConsumo(int anio) {
        List<String> nombres = new ArrayList<>(generadores().stream().map(Generador::nombre).toList());
        historico.findByMesBetweenOrderByMesAsc(LocalDate.of(anio, 1, 1), LocalDate.of(anio, 12, 1)).stream()
                .map(ConsumoMensualGenerador::getGenerador).distinct().filter(n -> !nombres.contains(n)).forEach(nombres::add);
        return nombres;
    }

    /** Un mes por fila, de enero al mes actual (o diciembre en años pasados). */
    public List<FilaMes> consumoAnual(int anio) {
        Map<String, List<RegistroMf13>> registros = new LinkedHashMap<>();
        for (Generador g : generadores()) registros.put(g.nombre(), todos(g.nombre()));
        List<ConsumoMensualGenerador> hist = historico.findByMesBetweenOrderByMesAsc(LocalDate.of(anio, 1, 1), LocalDate.of(anio, 12, 1));
        YearMonth hoy = YearMonth.now();
        List<FilaMes> filas = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            YearMonth mes = YearMonth.of(anio, m);
            if (mes.isAfter(hoy)) break;
            Map<String, Consumo> porGen = new LinkedHashMap<>();
            for (String nombre : nombresConsumo(anio)) {
                List<RegistroMf13> rs = registros.get(nombre);
                Consumo c = null;
                if (rs != null && rs.stream().filter(r -> YearMonth.from(r.getFechaHora()).equals(mes) && !esReferencia(r)).count() >= 2) {
                    c = consumoDelRegistro(nombre, rs, mes);
                } else {
                    Optional<ConsumoMensualGenerador> h = hist.stream()
                            .filter(x -> x.getGenerador().equals(nombre) && YearMonth.from(x.getMes()).equals(mes)).findFirst();
                    if (h.isPresent()) {
                        c = new Consumo(h.get().getGalones(), h.get().getKwh(), h.get().getHoras(), 0, 0, 0,
                                "Histórico MF-13", h.get().getNota());
                    }
                }
                if (c != null) porGen.put(nombre, c);
            }
            filas.add(new FilaMes(mes, porGen));
        }
        return filas;
    }

    public Consumo consumoDelMes(String generador, YearMonth mes) {
        return consumoDelRegistro(generador, todos(generador), mes);
    }

    private Consumo consumoDelRegistro(String generador, List<RegistroMf13> rs, YearMonth mes) {
        boolean contador = usaContador(generador);
        double kwh = sumaPares(rs, RegistroMf13::getProdKwh, r -> YearMonth.from(r.getFechaHora()).equals(mes));
        double horas = sumaPares(rs, RegistroMf13::getHoras, r -> YearMonth.from(r.getFechaHora()).equals(mes));
        Diesel d = contador
                ? new Diesel(sumaPares(rs, RegistroMf13::getContadorDiesel, r -> YearMonth.from(r.getFechaHora()).equals(mes)), 0, 0)
                : porNivel(generador, rs, r -> YearMonth.from(r.getFechaHora()).equals(mes));
        int cargas = (int) rs.stream().filter(r -> YearMonth.from(r.getFechaHora()).equals(mes))
                .filter(r -> r.isObsCargaDiesel() || (r.getDieselCargado() != null && r.getDieselCargado() > 0)).count();
        String nota = contador ? "Contador de diésel del motor y contador de energía del controlador."
                : "Nivel del tanque más las cargas de diésel, y contador de energía del controlador.";
        if (d.estimado() > 0) nota += String.format(Locale.ROOT, " Incluye %.1f gal estimados (cargas sin cantidad anotada en la planilla).", d.estimado()).replace('.', ',');
        if (d.sinCantidad() > 0) nota += " " + d.sinCantidad() + " cargas sin cantidad: ese consumo falta.";
        return new Consumo(d.galones(), kwh, horas, cargas, d.estimado(), d.sinCantidad(), "Registro MF-13", nota);
    }

    private record Diesel(double galones, double estimado, int sinCantidad) {
    }

    /** Suma de lo que avanzó un contador entre registros válidos seguidos, cuando el segundo cumple {@code incluir}. */
    private static double sumaPares(List<RegistroMf13> rs, Function<RegistroMf13, Double> campo, java.util.function.Predicate<RegistroMf13> incluir) {
        List<RegistroMf13> ok = validos(rs, campo);
        double s = 0;
        for (int i = 1; i < ok.size(); i++) {
            if (incluir.test(ok.get(i))) s += campo.apply(ok.get(i)) - campo.apply(ok.get(i - 1));
        }
        return s;
    }

    /**
     * Lecturas que siguen la secuencia creciente: se descarta la que baja respecto de la última buena (ej. 569.858 entre
     * 576.000) y la que queda por encima de la mayoría de las tres siguientes (ej. 4.749 anotado por 474).
     */
    public static List<RegistroMf13> validos(List<RegistroMf13> rs, Function<RegistroMf13, Double> campo) {
        List<RegistroMf13> v = rs.stream().filter(r -> campo.apply(r) != null).toList();
        List<RegistroMf13> ok = new ArrayList<>();
        double prev = -1;
        for (int k = 0; k < v.size(); k++) {
            double x = campo.apply(v.get(k));
            int menores = 0, vistas = 0;
            for (int j = k + 1; j < Math.min(v.size(), k + 4); j++) {
                vistas++;
                if (x > campo.apply(v.get(j))) menores++;
            }
            boolean siguientes = menores < Math.min(2, Math.max(1, vistas)) || vistas == 0;
            if (x >= prev && siguientes) {
                ok.add(v.get(k));
                prev = x;
            }
        }
        return ok;
    }

    /** Diésel por nivel del tanque en los tramos entre registros con nivel cuyo segundo cumple {@code incluir}. */
    private Diesel porNivel(String generador, List<RegistroMf13> rs, java.util.function.Predicate<RegistroMf13> incluir) {
        int[] sin = {0};
        double gal = 0, est = 0;
        for (Tramo t : tramosNivel(generador, rs, incluir, sin)) {
            gal += t.galones();
            if (t.estimado()) est += t.galones();
        }
        return new Diesel(gal, est, sin[0]);
    }

    /**
     * Diésel entre dos registros seguidos: lo que marca el tramo (contador del motor o nivel del tanque más la carga),
     * con el contador de energía en cada punta (null si no hay lectura válida). La suma de los tramos de un mes da el mes.
     */
    public record Tramo(LocalDateTime hasta, Double kwhDesde, Double kwhHasta, double galones, boolean estimado) {
    }

    private List<Tramo> tramos(String generador, List<RegistroMf13> rs, java.util.function.Predicate<RegistroMf13> incluir) {
        if (!usaContador(generador)) return tramosNivel(generador, rs, incluir, new int[1]);
        Set<RegistroMf13> kv = kwhValidos(rs);
        List<RegistroMf13> ok = validos(rs, RegistroMf13::getContadorDiesel);
        List<Tramo> out = new ArrayList<>();
        for (int i = 1; i < ok.size(); i++) {
            RegistroMf13 a = ok.get(i - 1), b = ok.get(i);
            if (incluir.test(b)) {
                out.add(new Tramo(b.getFechaHora(), kwhDe(a, kv), kwhDe(b, kv), b.getContadorDiesel() - a.getContadorDiesel(), false));
            }
        }
        return out;
    }

    private List<Tramo> tramosNivel(String generador, List<RegistroMf13> rs, java.util.function.Predicate<RegistroMf13> incluir, int[] sin) {
        Planilla p = planillas.planilla(generador);
        double galCm = p == null ? 0 : p.galonesPorCm();
        Set<RegistroMf13> kv = kwhValidos(rs);
        List<Tramo> out = new ArrayList<>();
        List<Integer> conNivel = new ArrayList<>();
        for (int i = 0; i < rs.size(); i++) if (rs.get(i).getNivelCm() != null) conNivel.add(i);
        for (int k = 1; k < conNivel.size(); k++) {
            RegistroMf13 a = rs.get(conNivel.get(k - 1)), b = rs.get(conNivel.get(k));
            if (!incluir.test(b)) continue;
            double na = a.getNivelCm(), nb = b.getNivelCm();
            if (nb <= na + 0.2) {
                out.add(new Tramo(b.getFechaHora(), kwhDe(a, kv), kwhDe(b, kv), Math.max(0, na - nb) * galCm, false));
                continue;
            }
            RegistroMf13 conCarga = null;
            for (int i = conNivel.get(k - 1); i <= conNivel.get(k); i++) {
                Double c = rs.get(i).getDieselCargado();
                if (c != null && c > 0) {
                    conCarga = rs.get(i);
                    break;
                }
            }
            if (conCarga != null) {
                out.add(new Tramo(b.getFechaHora(), kwhDe(a, kv), kwhDe(b, kv), (na - nb) * galCm + conCarga.getDieselCargado(),
                        conCarga.isDieselCargadoEstimado()));
            } else if (energiaEntre(rs, conNivel.get(k - 1), conNivel.get(k), kv) > 0) {
                sin[0]++;
            }
        }
        return out;
    }

    private static Double kwhDe(RegistroMf13 r, Set<RegistroMf13> validos) {
        return validos.contains(r) ? r.getProdKwh() : null;
    }

    /**
     * Diésel del registro MF-13 repartido entre los arranques que guardó la aplicación, para Costos: cada tramo va al
     * arranque según el contador de energía (o, si en el tramo no se generó, al arranque en cuya hora cae). Lo que no cae
     * en ningún arranque (marchas de antes de que la aplicación leyera el controlador, o sin registro) queda en
     * {@code soloMf13}, una fila por marcha del MF-13. Así la suma da lo mismo que el mes en Control MF-13. Vacío si el
     * mes no sale del registro (menos de dos registros propios).
     */
    public record DieselArranques(Map<Long, Double> porArranque, List<Marcha> soloMf13) {
    }

    public Optional<DieselArranques> dieselPorArranque(String generador, YearMonth mes) {
        List<RegistroMf13> rs = todos(generador);
        long propios = rs.stream().filter(r -> YearMonth.from(r.getFechaHora()).equals(mes) && !esReferencia(r)).count();
        if (propios < 2) return Optional.empty();
        List<PlanillaCombustibleService.ArranqueGuardado> arranques = planillas.arranques(generador);
        Map<Long, Double> porArranque = new LinkedHashMap<>();
        for (var a : arranques) if (YearMonth.from(a.inicio()).equals(mes)) porArranque.put(a.id(), 0.0);
        List<Marcha> marchas = marchas(generador, mes);
        double[] galResto = new double[marchas.size() + 1], kwhResto = new double[marchas.size() + 1];
        boolean[] estResto = new boolean[marchas.size() + 1];
        for (Tramo t : tramos(generador, rs, r -> YearMonth.from(r.getFechaHora()).equals(mes))) {
            if (t.galones() == 0) continue; // los negativos también (carga anotada de más): el mes los suma
            double asignado = 0, kwhTramo = 0;
            if (t.kwhDesde() != null && t.kwhHasta() != null && t.kwhHasta() > t.kwhDesde()) {
                kwhTramo = t.kwhHasta() - t.kwhDesde();
                double kwhAsignado = 0;
                for (var a : arranques) {
                    double ov = Math.min(t.kwhHasta(), a.kwhFin()) - Math.max(t.kwhDesde(), a.kwhInicio());
                    if (ov <= 0) continue;
                    porArranque.merge(a.id(), t.galones() * ov / kwhTramo, Double::sum);
                    kwhAsignado += ov;
                }
                asignado = kwhAsignado / kwhTramo;
            } else {
                for (var a : arranques) {
                    if (!t.hasta().isBefore(a.inicio()) && !t.hasta().isAfter(a.fin())) {
                        porArranque.merge(a.id(), t.galones(), Double::sum);
                        asignado = 1;
                        break;
                    }
                }
            }
            if (asignado >= 0.9999) continue;
            int m = marchas.isEmpty() ? 0 : marchaDe(marchas, t.hasta());
            galResto[m] += t.galones() * (1 - asignado);
            kwhResto[m] += kwhTramo * (1 - asignado);
            estResto[m] |= t.estimado();
        }
        List<Marcha> solo = new ArrayList<>();
        for (int i = 0; i < galResto.length; i++) {
            if (Math.abs(galResto[i]) < 0.05) continue;
            Marcha m = i < marchas.size() ? marchas.get(i) : null;
            // Resto chico (el contador de energía de la planilla y el del arranque difieren en unos kWh): va al arranque
            // de la aplicación que coincide en hora con la marcha.
            if (m != null && kwhResto[i] < 20) {
                Long id = arranqueEnHora(arranques, m);
                if (id != null) {
                    porArranque.merge(id, galResto[i], Double::sum);
                    continue;
                }
            }
            LocalDateTime ini = m != null ? m.inicio() : mes.atDay(1).atStartOfDay();
            LocalDateTime fin = m != null ? m.fin() : ini;
            double horas = m != null && m.kwh() > 0 ? m.horas() * Math.min(1, kwhResto[i] / m.kwh()) : 0;
            solo.add(new Marcha(generador, ini, fin, horas, kwhResto[i], galResto[i], estResto[i]));
        }
        return Optional.of(new DieselArranques(porArranque, solo));
    }

    /** Arranque de la aplicación que más se pisa en hora con una marcha del MF-13; null si ninguno. */
    private static Long arranqueEnHora(List<PlanillaCombustibleService.ArranqueGuardado> arranques, Marcha m) {
        Long mejor = null;
        long max = 0;
        for (var a : arranques) {
            LocalDateTime d = a.inicio().isAfter(m.inicio()) ? a.inicio() : m.inicio();
            LocalDateTime h = a.fin().isBefore(m.fin()) ? a.fin() : m.fin();
            long s = java.time.Duration.between(d, h).toSeconds();
            if (s > max || (mejor == null && s == 0 && !d.isAfter(h))) {
                max = s;
                mejor = a.id();
            }
        }
        return mejor;
    }

    /** Marcha del MF-13 a la que pertenece un instante: la que lo contiene, si no la última anterior, si no la primera. */
    private static int marchaDe(List<Marcha> marchas, LocalDateTime t) {
        int anterior = 0;
        for (int i = 0; i < marchas.size(); i++) {
            Marcha m = marchas.get(i);
            if (!t.isBefore(m.inicio()) && !t.isAfter(m.fin())) return i;
            if (!m.fin().isAfter(t)) anterior = i;
        }
        return anterior;
    }

    /** Registros cuya energía sigue la secuencia (los mal anotados no cuentan para la energía de un tramo). */
    private static Set<RegistroMf13> kwhValidos(List<RegistroMf13> rs) {
        Set<RegistroMf13> s = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        s.addAll(validos(rs, RegistroMf13::getProdKwh));
        return s;
    }

    private static double energiaEntre(List<RegistroMf13> rs, int desde, int hasta, Set<RegistroMf13> validos) {
        Double min = null, max = null;
        for (int i = desde; i <= hasta; i++) {
            Double k = rs.get(i).getProdKwh();
            if (k == null || !validos.contains(rs.get(i))) continue;
            min = min == null ? k : Math.min(min, k);
            max = max == null ? k : Math.max(max, k);
        }
        return min == null ? 0 : max - min;
    }

    /** Período en marcha: de un arranque a la parada siguiente. */
    /** @param galones null = sin lecturas de diésel válidas en la marcha */
    public record Marcha(String generador, LocalDateTime inicio, LocalDateTime fin, double horas, double kwh, Double galones,
                         boolean incluyeEstimado) {
        public Double kwhPorGalon() {
            return galones == null || galones <= 0 ? null : kwh / galones;
        }
    }

    public List<Marcha> marchas(String generador, YearMonth mes) {
        List<RegistroMf13> rs = todos(generador);
        List<Marcha> out = new ArrayList<>();
        List<RegistroMf13> actual = null;
        for (RegistroMf13 r : rs) {
            if (!YearMonth.from(r.getFechaHora()).equals(mes)) continue;
            boolean corte = actual != null && r.getFechaHora().isAfter(actual.get(actual.size() - 1).getFechaHora().plusHours(3));
            if (RegistroMf13.ARRANQUE.equals(r.getMomento()) || corte) {
                if (actual != null) cerrar(generador, rs, actual, out);
                actual = RegistroMf13.REVISION.equals(r.getMomento()) ? null : new ArrayList<>(List.of(r));
            } else if (actual != null) {
                actual.add(r);
            } else if (RegistroMf13.CONTROL.equals(r.getMomento())) {
                actual = new ArrayList<>(List.of(r));
            }
            if (actual != null && RegistroMf13.PARADA.equals(r.getMomento())) {
                cerrar(generador, rs, actual, out);
                actual = null;
            }
        }
        if (actual != null) cerrar(generador, rs, actual, out);
        return out;
    }

    private void cerrar(String generador, List<RegistroMf13> todos, List<RegistroMf13> marcha, List<Marcha> out) {
        Set<Long> ids = new HashSet<>();
        marcha.forEach(r -> ids.add(r.getId()));
        java.util.function.Predicate<RegistroMf13> dentro = r -> ids.contains(r.getId());
        // un tramo entre dos registros cuenta en la marcha de su segundo registro (lo del arranque antes de la
        // primera lectura queda en la marcha), igual que el mes; así la suma de las marchas da el mes
        double kwh = paresHasta(todos, RegistroMf13::getProdKwh, ids);
        if (kwh <= 0) return;
        double horas = paresHasta(todos, RegistroMf13::getHoras, ids);
        Double gal;
        boolean est = false;
        if (usaContador(generador)) {
            gal = tienePar(todos, RegistroMf13::getContadorDiesel, ids) ? paresHasta(todos, RegistroMf13::getContadorDiesel, ids) : null;
        } else {
            Diesel d = porNivel(generador, todos, dentro);
            gal = d.galones() > 0 ? d.galones() : null;
            est = d.estimado() > 0;
        }
        out.add(new Marcha(generador, marcha.get(0).getFechaHora(), marcha.get(marcha.size() - 1).getFechaHora(), horas, kwh, gal, est));
    }

    private static double paresHasta(List<RegistroMf13> rs, Function<RegistroMf13, Double> campo, Set<Long> ids) {
        List<RegistroMf13> ok = validos(rs, campo);
        double s = 0;
        for (int i = 1; i < ok.size(); i++) {
            if (ids.contains(ok.get(i).getId())) s += campo.apply(ok.get(i)) - campo.apply(ok.get(i - 1));
        }
        return s;
    }

    private static boolean tienePar(List<RegistroMf13> rs, Function<RegistroMf13, Double> campo, Set<Long> ids) {
        List<RegistroMf13> ok = validos(rs, campo);
        for (int i = 1; i < ok.size(); i++) if (ids.contains(ok.get(i).getId())) return true;
        return false;
    }

    // ------------------------------------------------------------------ importación de la planilla MF-13

    public record ResultadoImportacion(Map<String, Integer> importados, int repetidos, int estimadas, List<String> avisos) {
    }

    /**
     * Importa de la planilla MF-13 (.xlsx, una hoja por generador según {@code combustible.hoja}) los registros de un
     * mes, más los últimos del mes anterior como referencia para el cálculo del primer tramo. Lo ya importado se omite.
     * En el GenPower, cuando el nivel sube y la planilla no dice cuánto se cargó, la cantidad se estima con el nivel y
     * el rendimiento medido en la misma marcha, y queda marcada como estimada.
     */
    @Transactional
    public ResultadoImportacion importar(byte[] xlsx, String archivo, YearMonth mes, String usuario) throws IOException {
        Map<String, List<Map<String, String>>> hojas = PlanillaCombustibleService.leerLibro(xlsx);
        Map<String, Integer> importados = new LinkedHashMap<>();
        List<String> avisos = new ArrayList<>();
        int repetidos = 0, estimadas = 0;
        for (Generador g : generadores()) {
            Planilla p = planillas.planilla(g.nombre());
            if (p == null) {
                avisos.add(g.nombre() + ": no tiene planilla configurada (combustible en generador-config.json).");
                continue;
            }
            List<Map<String, String>> filas = hojas.entrySet().stream().filter(e -> PlanillaCombustibleService.mismaHoja(e.getKey(), p.hoja()))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            if (filas == null) {
                avisos.add(g.nombre() + ": el archivo no tiene la hoja \"" + p.hoja() + "\".");
                continue;
            }
            List<RegistroMf13> leidos = leerHoja(g.nombre(), filas, p, archivo);
            LocalDateTime ini = mes.atDay(1).atStartOfDay(), fin = mes.plusMonths(1).atDay(1).atStartOfDay();
            List<RegistroMf13> delMes = new ArrayList<>(leidos.stream().filter(r -> !r.getFechaHora().isBefore(ini) && r.getFechaHora().isBefore(fin)).toList());
            if (delMes.isEmpty()) {
                avisos.add(g.nombre() + ": la planilla no tiene registros de " + mes + ".");
                continue;
            }
            // referencia: el último registro anterior al mes con energía, con nivel y con contador
            List<RegistroMf13> antes = leidos.stream().filter(r -> r.getFechaHora().isBefore(ini)).toList();
            List<RegistroMf13> referencia = new ArrayList<>();
            for (Function<RegistroMf13, Double> campo : List.<Function<RegistroMf13, Double>>of(RegistroMf13::getProdKwh,
                    RegistroMf13::getNivelCm, RegistroMf13::getContadorDiesel, RegistroMf13::getHoras)) {
                antes.stream().filter(r -> campo.apply(r) != null).reduce((a, b) -> b)
                        .filter(r -> !referencia.contains(r)).ifPresent(referencia::add);
            }
            referencia.sort(Comparator.comparing(RegistroMf13::getFechaHora));
            referencia.forEach(r -> r.setOrigen(corto(r.getOrigen() + " " + REFERENCIA, 160)));
            List<RegistroMf13> lote = new ArrayList<>(referencia);
            lote.addAll(delMes);
            if (p.medida() == Medida.NIVEL_CM) estimadas += estimarCargas(lote, p.galonesPorCm());
            Set<String> existentes = new HashSet<>();
            repo.findByGeneradorAndFechaHoraBetweenOrderByFechaHoraAscIdAsc(g.nombre(), ini.minusDays(40), fin)
                    .forEach(r -> existentes.add(clave(r)));
            int n = 0;
            for (RegistroMf13 r : lote) {
                if (!existentes.add(clave(r))) {
                    repetidos++;
                    continue;
                }
                r.setUsuario(usuario);
                r.setFechaRegistro(LocalDateTime.now());
                repo.save(r);
                n++;
            }
            importados.put(g.nombre(), n);
        }
        return new ResultadoImportacion(importados, repetidos, estimadas, avisos);
    }

    private static String clave(RegistroMf13 r) {
        return r.getFechaHora() + "|" + r.getProdKwh() + "|" + r.getNivelCm() + "|" + r.getContadorDiesel() + "|" + r.getHoras();
    }

    /** Filas de la hoja con fecha, en orden; la hora sale de HORA INI o HORA FIN. Columnas del formato MF-13 (A a R). */
    private List<RegistroMf13> leerHoja(String generador, List<Map<String, String>> filas, Planilla p, String archivo) {
        List<RegistroMf13> out = new ArrayList<>();
        LocalDateTime anterior = null, maxT = null;
        String momentoAnterior = null;
        for (Map<String, String> f : filas) {
            Double serie = numero(f.get("B"));
            if (serie == null || serie < 40000 || serie > 60000) continue;  // sin fecha (encabezados, filas vacías)
            LocalDate fecha = LocalDate.of(1899, 12, 30).plusDays((long) Math.floor(serie));
            boolean conInicio = tieneValor(f.get("J")), conFin = tieneValor(f.get("K"));
            LocalTime hora = hora(f.get("J"));
            if (hora == null) hora = hora(f.get("K"));
            LocalDateTime t;
            if (hora == null) {
                t = anterior != null && anterior.toLocalDate().equals(fecha) ? anterior : fecha.atStartOfDay();
            } else {
                t = fecha.atTime(hora);
                // "08-10 00:00" anotado después de "08-10 20:00" es la medianoche siguiente
                if (anterior != null && hora.getHour() < 2 && t.isBefore(anterior.minusHours(2))) t = t.plusDays(1);
            }
            String obs = f.get("R");
            String obsMay = obs == null ? "" : obs.toUpperCase(Locale.ROOT);
            RegistroMf13 r = new RegistroMf13();
            r.setGenerador(generador);
            r.setFechaHora(t);
            String momento;
            if (conFin) momento = RegistroMf13.PARADA;
            else if (conInicio) momento = momentoAnterior == null || RegistroMf13.PARADA.equals(momentoAnterior)
                    || RegistroMf13.REVISION.equals(momentoAnterior) || (maxT != null && t.isAfter(maxT.plusHours(3)))
                    ? RegistroMf13.ARRANQUE : RegistroMf13.CONTROL;
            else momento = obsMay.contains("REVISI") || obsMay.contains("NO SE ENCIENDE") || obsMay.contains("NO SE REALIZA")
                    ? RegistroMf13.REVISION : RegistroMf13.CONTROL;
            r.setMomento(momento);
            if (obsMay.contains("SINC")) r.setModo("Sincronismo");
            else if (obsMay.contains("ISLA")) r.setModo("Isla");
            else if (obsMay.contains("MANUAL")) r.setModo("Manual");
            r.setResponsable(corto(f.get("A"), 80));
            r.setNivLubricante(corto(f.get("C"), 10));
            r.setNivelCm(numero(f.get("D")));
            r.setNivAgua(corto(f.get("E"), 10));
            r.setPrecalentador(corto(f.get("F"), 10));
            r.setFiltros(corto(f.get("G"), 10));
            r.setCorreas(corto(f.get("H"), 10));
            r.setBateria(numero(f.get("I")));
            r.setPresAceite(numero(f.get("L")));
            r.setTemperatura(numero(f.get("M")));
            r.setCargaKw(numero(f.get("N")));
            if (p.medida() == Medida.CONTADOR) {
                String o = f.get("O");
                r.setContadorDiesel(o != null && o.toLowerCase(Locale.ROOT).contains("cm") ? null : numero(o));
            }
            r.setProdKwh(numero(f.get("P")));
            r.setHoras(numero(f.get("Q")));
            r.setObservacion(corto(obs, 500));
            if (p.medida() == Medida.NIVEL_CM && (obsMay.contains("CARG") || obsMay.contains("LLEN"))
                    && !obsMay.contains("REFRIGERANTE") && !obsMay.contains("ACEITE")) {
                Matcher m = GALONES.matcher(obsMay);
                if (m.find()) r.setDieselCargado(Double.parseDouble(m.group(1).replace(',', '.')));
            }
            r.setObsCargaDiesel((obsMay.contains("CARG") || obsMay.contains("LLEN")) && !obsMay.contains("REFRIGERANTE") && !obsMay.contains("ACEITE"));
            r.setObsAceite(obsMay.contains("ACEITE"));
            r.setObsRefrigerante(obsMay.contains("REFRIGERANTE"));
            r.setObsAutomatico(obsMay.contains("AUTOM"));
            r.setObsFalla(obsMay.matches(".*(FUGA|FALLA|DERRAME|PROBLEMA|NO SE ENCIENDE).*"));
            r.setOrigen(corto("Planilla MF-13 (" + archivo + "), fila " + f.get("#"), 160));
            out.add(r);
            anterior = t;
            if (maxT == null || t.isAfter(maxT)) maxT = t;
            momentoAnterior = momento;
        }
        out.sort(Comparator.comparing(RegistroMf13::getFechaHora));
        return out;
    }

    /**
     * Cargas sin cantidad anotada (el nivel sube y no dice cuánto se cargó): cargado = lo que subió el nivel + lo que
     * consumió en el tramo (kWh del tramo / rendimiento medido en las 12 h vecinas, o en el mes). Devuelve cuántas estimó.
     */
    private static int estimarCargas(List<RegistroMf13> lote, double galCm) {
        record Tramo(int a, int b, double gal, double kwh) {
        }
        List<Integer> conNivel = new ArrayList<>();
        for (int i = 0; i < lote.size(); i++) if (lote.get(i).getNivelCm() != null) conNivel.add(i);
        List<Tramo> medidos = new ArrayList<>(), pendientes = new ArrayList<>();
        Set<RegistroMf13> validosLote = kwhValidos(lote);
        for (int k = 1; k < conNivel.size(); k++) {
            int a = conNivel.get(k - 1), b = conNivel.get(k);
            double na = lote.get(a).getNivelCm(), nb = lote.get(b).getNivelCm(), kwh = energiaEntre(lote, a, b, validosLote);
            if (nb <= na + 0.2) {
                medidos.add(new Tramo(a, b, Math.max(0, na - nb) * galCm, kwh));
                continue;
            }
            Double cargado = null;
            for (int i = a; i <= b; i++) if (lote.get(i).getDieselCargado() != null) cargado = lote.get(i).getDieselCargado();
            if (cargado != null) medidos.add(new Tramo(a, b, (na - nb) * galCm + cargado, kwh));
            else pendientes.add(new Tramo(a, b, 0, kwh));
        }
        int n = 0;
        for (Tramo t : pendientes) {
            LocalDateTime ta = lote.get(t.a()).getFechaHora(), tb = lote.get(t.b()).getFechaHora();
            double g = 0, k = 0;
            for (Tramo m : medidos) {
                boolean cerca = Math.abs(java.time.Duration.between(lote.get(m.a()).getFechaHora(), ta).toHours()) < 12
                        || Math.abs(java.time.Duration.between(lote.get(m.b()).getFechaHora(), tb).toHours()) < 12;
                if (cerca && m.kwh() > 50 && m.gal() > 0) {
                    g += m.gal();
                    k += m.kwh();
                }
            }
            if (k < 300) {
                g = 0;
                k = 0;
                for (Tramo m : medidos) if (m.kwh() > 50 && m.gal() > 0) {
                    g += m.gal();
                    k += m.kwh();
                }
            }
            double consumo = k > 0 ? t.kwh() * g / k : 0;
            RegistroMf13 despues = lote.get(t.b());
            double cargado = (despues.getNivelCm() - lote.get(t.a()).getNivelCm()) * galCm + consumo;
            despues.setDieselCargado(Math.round(cargado * 10) / 10.0);
            despues.setDieselCargadoEstimado(true);
            despues.setObsCargaDiesel(true);
            n++;
        }
        return n;
    }

    private static boolean tieneValor(String s) {
        return s != null && !s.isBlank() && !s.trim().equals("…") && !s.trim().equals("―") && !s.trim().equals("...");
    }

    /** Hora de una celda: fracción del día de Excel (o fecha y hora, se toma la parte de la hora) o texto "16:30" / "15H00". */
    private static LocalTime hora(String s) {
        if (!tieneValor(s)) return null;
        try {
            double v = Double.parseDouble(s.trim());
            double frac = v - Math.floor(v);
            int min = (int) Math.round(frac * 1440) % 1440;
            return LocalTime.of(min / 60, min % 60);
        } catch (NumberFormatException e) {
            Matcher m = HORA_TEXTO.matcher(s);
            if (m.find()) {
                int h = Integer.parseInt(m.group(1)), mi = Integer.parseInt(m.group(2));
                if (h < 24 && mi < 60) return LocalTime.of(h, mi);
            }
            return null;
        }
    }

    private static Double numero(String s) {
        if (s == null) return null;
        Matcher m = NUMERO.matcher(s);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String corto(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
