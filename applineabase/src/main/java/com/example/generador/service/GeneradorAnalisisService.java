package com.example.generador.service;

import com.example.dataacquisition.MaquinasVirtuales;
import com.example.dataacquisition.RutaArchivosEnergia;
import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.franjahoraria.model.Franja;
import com.example.franjahoraria.model.TarifaFranja;
import com.example.franjahoraria.service.FeriadoService;
import com.example.franjahoraria.service.TarifaFranjaService;
import com.example.franjahoraria.service.TarifaFranjaService.TarifaDelMes;
import com.example.generador.model.Generador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Análisis del grupo electrógeno por día, semana o mes: energía generada (contador del
 * controlador), energía importada de la red y exportada a la red (contadores kWh y KWhR del
 * medidor principal de la planta 1, leído por PLC), consumo de la planta 1, horas de marcha en
 * paralelo con la red / en isla / en vacío y arranques.
 *
 * La red es siempre la del medidor principal (KWhPlanta1, ION8600 clase 0,2, propio de la fábrica y en paralelo con
 * el de la empresa eléctrica, con el que coincide a menos de 0,3 %): ve los dos transformadores juntos, así que lo que
 * sale por uno y entra por el otro no cuenta como exportado ni importado. Los medidores de los transformadores no se
 * usan: con los generadores en marcha no coinciden con el principal (hasta ~100 kW de diferencia).
 *
 * Toda energía sale de diferencias de contadores (no de integrar potencias): las lecturas
 * faltantes no pierden energía, solo la corren a la lectura siguiente. Se descartan valores en
 * cero o basura (ceros de F-10, 1e-42) y saltos imposibles. Las filas repetidas entre archivos
 * mensuales (el de septiembre tiene las primeras horas del 1 de octubre) se cuentan una vez.
 */
@Service
public class GeneradorAnalisisService {

    private static final Logger logger = LoggerFactory.getLogger(GeneradorAnalisisService.class);

    /** En marcha con menos de esto = en vacío (sin carga). */
    public static final double KW_VACIO = 10.0;
    /** Tensión de red (promedio fase-fase, medida por el controlador) a partir de la cual hay red. */
    public static final double V_RED_PRESENTE = 100.0;
    /** Minutos que cuenta como máximo cada lectura en marcha (se guarda una por minuto). */
    private static final double MAX_MIN_POR_LECTURA = 2.0;
    /** Plausibilidad de los contadores: potencia máxima creíble (kW). */
    private static final double KW_MAX_RED = 20000, KW_MAX_GENERADOR = 5000;
    /** Medidor principal: la red de toda la planta 1 (transformadores 1 y 2 juntos). */
    public static final String PRINCIPAL = MaquinasVirtuales.KWH_PLANTA_1;
    /** El PLC guarda la potencia del medidor principal en W (la de los transformadores, en kW). */
    public static final double KW_POR_UNIDAD_PRINCIPAL = 0.001;

    private static final String[] MESES = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
            "agosto", "septiembre", "octubre", "noviembre", "diciembre"};
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter DIA_CORTO = DateTimeFormatter.ofPattern("dd-MM");

    public enum Agrupacion {
        DIA("Dia"), SEMANA("Semana"), MES("Mes");

        private final String etiqueta;

        Agrupacion(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        public String etiqueta() {
            return etiqueta;
        }
    }

    /**
     * Un período. null = sin datos (distinto de 0). Red = medidor principal (toda la planta 1): redImportada de todo el
     * día; lo exportado se reparte entre los generadores en marcha según lo que generaba cada uno: redExportada = la parte
     * de los generadores elegidos, redExportadaOtros = la de los demás. generadoOtros = lo que generaron los demás.
     */
    public record Fila(String periodo, LocalDate desde, LocalDate hasta,
                       Double redImportada, Double redExportada, Double generado,
                       Double generadoParalelo, Double generadoIsla,
                       Double horasMarcha, Double horasParalelo, Double horasIsla, Double horasVacio,
                       Integer arranques, Double kwMax, Double generadoOtros, Double redExportadaOtros) {

        /** Consumo de la planta 1: lo importado + lo que pusieron en la planta los generadores elegidos y los demás. */
        public Double consumo() {
            if (redImportada == null && generado == null) return null;
            return nz(redImportada) + nz(generadorUsado()) + nz(aporteOtros());
        }

        /** Parte del consumo cubierta por los generadores elegidos (%), descontando lo que exportaron. */
        public Double aporteGenerador() {
            Double c = consumo();
            if (c == null || c <= 0 || generado == null) return null;
            return Math.max(0, Math.min(100, generadorUsado() / c * 100));
        }

        /** Lo que pusieron en la planta los generadores elegidos: generado - su parte de lo exportado. */
        public Double generadorUsado() {
            return generado == null ? null : Math.max(0, generado - nz(redExportada));
        }

        /** Lo que pusieron en la planta los demás generadores (null si no hay otros). */
        public Double aporteOtros() {
            return generadoOtros == null ? null : Math.max(0, generadoOtros - nz(redExportadaOtros));
        }

        /** Parte del consumo que se tomó de la red (%): el indicador de estiaje, meta 0. */
        public Double porcentajeDeRed() {
            Double c = consumo();
            return c == null || c <= 0 || redImportada == null ? null : Math.min(100, redImportada / c * 100);
        }

        /** Tomado - entregado a la red por toda la planta: negativo = se entregó más de lo que se tomó. */
        public Double balanceNeto() {
            return redImportada == null ? null : redImportada - nz(redExportada) - nz(redExportadaOtros);
        }
    }

    /**
     * Un minuto en marcha: lo que dio el generador, lo que daban los demás generadores y la red de la planta 1
     * (medidor principal, + tomado, - entregado).
     */
    public record MinutoMarcha(LocalDateTime fecha, Double generadorKw, double otrosKw, Double redKw) {
        /** Consumo de la planta 1: los generadores + la red. */
        public Double consumoKw() {
            return generadorKw == null || redKw == null ? null : generadorKw + otrosKw + redKw;
        }
    }

    /** Acumulado de un día (campos null hasta que llega un dato). */
    private static final class Acum {
        Double redImportada, redExportada, redExportadaOtros, generado, generadoOtros, generadoParalelo, generadoIsla;
        Double horasMarcha, horasParalelo, horasIsla, horasVacio, kwMax;
        Integer arranques;

        void sumar(Acum o) {
            redImportada = suma(redImportada, o.redImportada);
            redExportada = suma(redExportada, o.redExportada);
            redExportadaOtros = suma(redExportadaOtros, o.redExportadaOtros);
            generadoOtros = suma(generadoOtros, o.generadoOtros);
            generado = suma(generado, o.generado);
            generadoParalelo = suma(generadoParalelo, o.generadoParalelo);
            generadoIsla = suma(generadoIsla, o.generadoIsla);
            horasMarcha = suma(horasMarcha, o.horasMarcha);
            horasParalelo = suma(horasParalelo, o.horasParalelo);
            horasIsla = suma(horasIsla, o.horasIsla);
            horasVacio = suma(horasVacio, o.horasVacio);
            // Con if: un ternario Integer/int o Double/double desempaqueta el null y falla.
            if (o.arranques != null) arranques = arranques == null ? o.arranques : Integer.valueOf(arranques + o.arranques);
            if (o.kwMax != null) kwMax = kwMax == null ? o.kwMax : Double.valueOf(Math.max(kwMax, o.kwMax));
        }
    }

    private record LecturaGuardada(LocalDateTime fecha, boolean marcha, Double kw, Double kwh, Double horas,
                                   Double arranques, Double redV) {
    }

    private final GeneradorService generadorService;
    private final TarifaFranjaService tarifaFranjaService;
    private final FeriadoService feriadoService;

    public GeneradorAnalisisService(GeneradorService generadorService, TarifaFranjaService tarifaFranjaService,
                                    FeriadoService feriadoService) {
        this.generadorService = generadorService;
        this.tarifaFranjaService = tarifaFranjaService;
        this.feriadoService = feriadoService;
    }

    /**
     * Filas por período entre dos fechas (inclusive) para uno o varios generadores. La red es siempre la de toda la
     * planta 1 (medidor principal); los generadores no elegidos entran como "otros" para que el consumo sea el de la planta.
     */
    public List<Fila> analizar(List<Generador> generadores, Agrupacion agrupacion, LocalDate desde, LocalDate hasta) {
        TreeMap<LocalDate, Acum> dias = new TreeMap<>(), diasOtros = new TreeMap<>();
        for (LocalDate d = desde; !d.isAfter(hasta); d = d.plusDays(1)) {
            dias.put(d, new Acum());
            diasOtros.put(d, new Acum());
        }

        List<Generador> todos = generadorService.generadores();
        Set<String> elegidos = new HashSet<>();
        for (Generador g : generadores) {
            elegidos.add(g.nombre());
            acumularGenerador(g, desde, hasta, dias);
        }
        boolean hayOtros = false;
        for (Generador g : todos) {
            if (elegidos.contains(g.nombre())) continue;
            hayOtros = true;
            acumularGenerador(g, desde, hasta, diasOtros);
        }
        if (hayOtros) dias.forEach((d, a) -> a.generadoOtros = diasOtros.get(d).generado);
        acumularRed(elegidos, todos, desde, hasta, dias);

        List<Fila> filas = new ArrayList<>();
        LocalDate inicio = desde;
        while (!inicio.isAfter(hasta)) {
            LocalDate fin = switch (agrupacion) {
                case DIA -> inicio;
                case SEMANA -> inicio.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                case MES -> inicio.with(TemporalAdjusters.lastDayOfMonth());
            };
            if (fin.isAfter(hasta)) fin = hasta;
            Acum a = new Acum();
            for (Acum d : dias.subMap(inicio, true, fin, true).values()) a.sumar(d);
            filas.add(new Fila(etiqueta(agrupacion, inicio, fin), inicio, fin, a.redImportada, a.redExportada, a.generado,
                    a.generadoParalelo, a.generadoIsla, a.horasMarcha, a.horasParalelo, a.horasIsla, a.horasVacio,
                    a.arranques, a.kwMax, a.generadoOtros, a.redExportadaOtros));
            inicio = fin.plusDays(1);
        }
        return filas;
    }

    /** Totales de una lista de filas (para las tarjetas de resumen). */
    public Fila total(List<Fila> filas) {
        Acum a = new Acum();
        for (Fila f : filas) {
            Acum x = new Acum();
            x.redImportada = f.redImportada();
            x.redExportada = f.redExportada();
            x.generado = f.generado();
            x.generadoParalelo = f.generadoParalelo();
            x.generadoIsla = f.generadoIsla();
            x.horasMarcha = f.horasMarcha();
            x.horasParalelo = f.horasParalelo();
            x.horasIsla = f.horasIsla();
            x.horasVacio = f.horasVacio();
            x.arranques = f.arranques();
            x.kwMax = f.kwMax();
            x.generadoOtros = f.generadoOtros();
            x.redExportadaOtros = f.redExportadaOtros();
            a.sumar(x);
        }
        LocalDate desde = filas.isEmpty() ? null : filas.get(0).desde();
        LocalDate hasta = filas.isEmpty() ? null : filas.get(filas.size() - 1).hasta();
        return new Fila("Total", desde, hasta, a.redImportada, a.redExportada, a.generado, a.generadoParalelo,
                a.generadoIsla, a.horasMarcha, a.horasParalelo, a.horasIsla, a.horasVacio, a.arranques, a.kwMax,
                a.generadoOtros, a.redExportadaOtros);
    }

    private static String etiqueta(Agrupacion agrupacion, LocalDate desde, LocalDate hasta) {
        return switch (agrupacion) {
            case DIA -> desde.format(DIA);
            case SEMANA -> "Semana " + desde.format(DIA_CORTO) + " al " + hasta.format(DIA_CORTO);
            case MES -> MESES[desde.getMonthValue() - 1] + " " + desde.getYear();
        };
    }

    // ================= Períodos en marcha =================

    /**
     * Un período en marcha (un arranque): lo que trabajó el generador y lo que pasó con la red de la planta 1 (medidor
     * principal) en ese mismo intervalo. fin = ahora si sigue en marcha. Si otro generador estaba en marcha a la vez, lo
     * importado y lo exportado por la planta se reparten entre los dos según lo que generaba cada uno: sumar los arranques
     * da lo de la planta, sin contar dos veces.
     *
     * @param kwhRedImportada  parte de lo importado por la planta 1 que le toca a este arranque
     * @param kwhRedExportada  parte de lo exportado por la planta 1 que le toca a este arranque
     * @param lecturasTomando  lecturas del medidor principal con potencia positiva: en ese minuto la planta tomaba de la red
     * @param lecturasRed      lecturas del medidor principal en el período
     * @param picoTomadoKw     máxima potencia que tomó la planta de la red en el período (null si nunca tomó)
     * @param aprovechadoFranja kWh aprovechados (generado - su parte de lo exportado) por franja horaria A..D, según la
     *                         hora en que se generaron; null si las lecturas del generador no alcanzan para repartirlos
     * @param costoRed         lo que habría costado lo aprovechado comprado a la red, con el precio de la franja de cada
     *                         hora y la tarifa de su mes (Consumo por franja horaria); null sin reparto o sin tarifa
     */
    public record PeriodoMarcha(String generador, String red, long arranqueId, Integer nro, LocalDateTime inicio,
                                LocalDateTime fin, boolean enCurso, boolean inicioEstimado,
                                Double kwhGenerado, Double kwhRedImportada, Double kwhRedExportada, Double kwMax,
                                double minParalelo, double minIsla, double minVacio,
                                int lecturasTomando, int lecturasRed, Double picoTomadoKw,
                                double[] aprovechadoFranja, Double costoRed) {

        /** $/kWh medio de la red en las horas en que trabajó el generador. */
        public Double precioRedMedio() {
            Double a = generadorUsado();
            return costoRed == null || a == null || a <= 0 ? null : costoRed / a;
        }

        /** Lo que puso el generador en el consumo de la planta: generado - su parte de lo exportado. */
        public Double generadorUsado() {
            return kwhGenerado == null ? null : Math.max(0, kwhGenerado - nz(kwhRedExportada));
        }

        /** Parte del consumo de la planta que se tomó de la red (%): el indicador de estiaje, meta 0. */
        public Double porcentajeDeRed() {
            Double c = consumo();
            return c == null || c <= 0 || kwhRedImportada == null ? null : Math.min(100, kwhRedImportada / c * 100);
        }

        /** Tomado - entregado a la red: negativo = se entregó más de lo que se tomó. */
        public Double balanceNeto() {
            return kwhRedImportada == null ? null : kwhRedImportada - nz(kwhRedExportada);
        }

        /** % del tiempo en que se tomó de la red. */
        public Double porcentajeTiempoTomando() {
            return lecturasRed == 0 ? null : lecturasTomando * 100.0 / lecturasRed;
        }

        public double horas() {
            return Duration.between(inicio, fin).toSeconds() / 3600.0;
        }

        public Double kwMedio() {
            return kwhGenerado == null || horas() <= 0 ? null : kwhGenerado / horas();
        }

        public Double consumo() {
            if (kwhRedImportada == null && kwhGenerado == null) return null;
            return Math.max(0, nz(kwhRedImportada) + nz(kwhGenerado) - nz(kwhRedExportada));
        }

        public Double aporteGenerador() {
            Double c = consumo();
            if (c == null || c <= 0 || kwhGenerado == null) return null;
            return Math.max(0, Math.min(100, (kwhGenerado - nz(kwhRedExportada)) / c * 100));
        }
    }

    /** Arranques que se pisan con el período elegido (completos, no recortados), del más reciente al más viejo. */
    public List<PeriodoMarcha> periodosEnMarcha(List<Generador> generadores, LocalDate desde, LocalDate hasta) {
        LocalDateTime ini = desde.atStartOfDay();
        LocalDateTime fin = hasta.atTime(23, 59, 59);
        List<PeriodoMarcha> lista = new ArrayList<>();
        List<TarifaFranja> tarifas = tarifaFranjaService.listar();
        Map<YearMonth, TarifaDelMes> tarifaPorMes = new HashMap<>();
        Function<YearMonth, TarifaDelMes> tarifaDe = m -> tarifaPorMes.computeIfAbsent(m, k -> TarifaFranjaService.delMes(tarifas, k));
        Map<Generador, List<GeneradorService.Arranque>> porGenerador = new LinkedHashMap<>();
        for (Generador g : generadores) {
            List<GeneradorService.Arranque> arranques = generadorService.arranques(g).stream()
                    .filter(a -> !a.inicio().isAfter(fin) && (a.fin() == null || !a.fin().isBefore(ini))).toList();
            if (!arranques.isEmpty()) porGenerador.put(g, arranques);
        }
        if (porGenerador.isEmpty()) return lista;
        LocalDate primero = porGenerador.values().stream().flatMap(List::stream).map(a -> a.inicio().toLocalDate())
                .min(LocalDate::compareTo).orElse(desde);
        LocalDate ultimo = porGenerador.values().stream().flatMap(List::stream)
                .map(a -> a.fin() == null ? LocalDate.now() : a.fin().toLocalDate()).max(LocalDate::compareTo).orElse(hasta);
        List<Generador> todos = generadorService.generadores();
        Map<String, TreeMap<LocalDateTime, LecturaGuardada>> lecturasDe = lecturasDe(todos, primero, ultimo);
        RedPrincipal red = redPrincipal(todos, lecturasDe, primero, ultimo);
        TreeMap<LocalDateTime, Double> potenciaRed = seriePotenciaPrincipal(primero, ultimo);
        Set<LocalDate> feriados = feriadoService.fechasEntre(primero, ultimo);
        Predicate<LocalDate> especial = d -> Franja.finDeSemana(d) || feriados.contains(d);

        for (Map.Entry<Generador, List<GeneradorService.Arranque>> e : porGenerador.entrySet()) {
            Generador g = e.getKey();
            TreeMap<LocalDateTime, LecturaGuardada> lecturas = lecturasDe.computeIfAbsent(g.nombre(), k -> lecturasGenerador(g, primero, ultimo));
            TreeMap<LocalDateTime, Double> kwhGen = new TreeMap<>();
            lecturas.values().forEach(l -> { if (l.kwh() != null && l.kwh() > 0) kwhGen.put(l.fecha(), l.kwh()); });
            TreeMap<LocalDateTime, Double> importada = red.importadaDe().getOrDefault(g.nombre(), new TreeMap<>());
            TreeMap<LocalDateTime, Double> exportada = red.exportadaDe().getOrDefault(g.nombre(), new TreeMap<>());

            for (GeneradorService.Arranque a : e.getValue()) {
                LocalDateTime f = a.fin() == null ? LocalDateTime.now().withNano(0) : a.fin();
                Double generado = a.kwhGenerados() != null ? Double.valueOf(a.kwhGenerados())
                        : deltaEnVentana(kwhGen, a.inicio(), f, KW_MAX_GENERADOR);
                double[] min = new double[3]; // paralelo, isla, vacío
                Double kwMax = a.kwMax();
                LecturaGuardada previa = null;
                for (LecturaGuardada l : lecturas.subMap(a.inicio(), true, f, true).values()) {
                    if (previa != null && previa.marcha()) {
                        double m = Math.min(Duration.between(previa.fecha(), l.fecha()).toSeconds() / 60.0, MAX_MIN_POR_LECTURA);
                        if (previa.kw() != null && previa.kw() < KW_VACIO) min[2] += m;
                        else if (previa.redV() != null && previa.redV() >= V_RED_PRESENTE) min[0] += m;
                        else if (previa.redV() != null) min[1] += m;
                    }
                    if (l.marcha() && l.kw() != null && (kwMax == null || l.kw() > kwMax)) kwMax = l.kw();
                    previa = l;
                }
                int lecturasRed = 0, tomando = 0;
                Double pico = null;
                for (Double pw : potenciaRed.subMap(a.inicio(), true, f, true).values()) {
                    lecturasRed++;
                    if (pw > 0) {
                        tomando++;
                        if (pico == null || pw > pico) pico = pw;
                    }
                }
                Double exportado = deltaEnVentana(exportada, a.inicio(), f, KW_MAX_RED);
                // Comparación con la red: lo generado y lo entregado, repartidos por la franja de la hora en que ocurrieron.
                // El total del arranque manda (contador del controlador al inicio y al fin): el reparto se escala a ese total.
                double[] aprovechadoFranja = null;
                Double costoRed = null;
                Reparto rg = repartir(kwhGen, a.inicio(), f, KW_MAX_GENERADOR, especial, tarifaDe);
                if (generado != null && rg.total() > 0) {
                    Reparto re = repartir(exportada, a.inicio(), f, KW_MAX_RED, especial, tarifaDe);
                    double escala = generado / rg.total();
                    aprovechadoFranja = new double[rg.kwh().length];
                    for (int i = 0; i < aprovechadoFranja.length; i++) {
                        aprovechadoFranja[i] = Math.max(0, rg.kwh()[i] * escala - re.kwh()[i]);
                    }
                    double suma = Arrays.stream(aprovechadoFranja).sum();
                    double aprovechado = Math.max(0, generado - nz(exportado));
                    if (suma > 0) {
                        for (int i = 0; i < aprovechadoFranja.length; i++) aprovechadoFranja[i] *= aprovechado / suma;
                    }
                    if (!rg.sinTarifa() && !re.sinTarifa()) costoRed = Math.max(0, rg.costo() * escala - re.costo());
                }
                lista.add(new PeriodoMarcha(g.nombre(), g.redAsociada(), a.id(), a.arranqueNro(), a.inicio(), f, a.fin() == null,
                        a.inicioEstimado(), generado, deltaEnVentana(importada, a.inicio(), f, KW_MAX_RED),
                        exportado, kwMax, min[0], min[1], min[2], tomando, lecturasRed, pico, aprovechadoFranja, costoRed));
            }
        }
        lista.sort((x, y) -> y.inicio().compareTo(x.inicio()));
        return lista;
    }

    /**
     * Minuto a minuto de un período en marcha: potencia del generador (lo guardado del controlador), la de los demás
     * generadores y la del medidor principal en la lectura más cercana anterior (hasta 90 s), para graficar cuándo
     * la planta tomó de la red y cuándo le entregó.
     */
    public List<MinutoMarcha> minutosMarcha(PeriodoMarcha p) {
        List<MinutoMarcha> lista = new ArrayList<>();
        Generador g = generadorService.generadores().stream().filter(x -> x.nombre().equals(p.generador())).findFirst().orElse(null);
        if (g == null) return lista;
        LocalDate desde = p.inicio().toLocalDate(), hasta = p.fin().toLocalDate();
        TreeMap<LocalDateTime, LecturaGuardada> lecturas = lecturasGenerador(g, desde, hasta);
        List<TreeMap<LocalDateTime, LecturaGuardada>> otros = generadorService.generadores().stream()
                .filter(x -> !x.nombre().equals(g.nombre())).map(x -> lecturasGenerador(x, desde, hasta)).toList();
        TreeMap<LocalDateTime, Double> potenciaRed = seriePotenciaPrincipal(desde, hasta);
        for (LecturaGuardada l : lecturas.subMap(p.inicio(), true, p.fin(), true).values()) {
            if (!l.marcha()) continue;
            Map.Entry<LocalDateTime, Double> red = potenciaRed.floorEntry(l.fecha());
            Double redKw = red != null && Duration.between(red.getKey(), l.fecha()).toSeconds() <= 90 ? red.getValue() : null;
            double otrosKw = otros.stream().mapToDouble(o -> kwGenerador(o, l.fecha())).sum();
            lista.add(new MinutoMarcha(l.fecha(), l.kw(), otrosKw, redKw));
        }
        return lista;
    }

    /** Potencia media de un medidor entre desde (incluido) y hasta (excluido), del archivo VIP; null sin lecturas. */
    public Double potenciaMedia(String maquina, LocalDateTime desde, LocalDateTime hasta) {
        if (!PLCDataQueryService.esNombreMaquinaValido(maquina)) return null;
        var lecturas = seriePotencia(maquina, desde.toLocalDate(), hasta.toLocalDate()).subMap(desde, true, hasta, false);
        return lecturas.isEmpty() ? null : lecturas.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** Potencia media del medidor principal (kW, + toma de la red) entre desde (incluido) y hasta (excluido); null sin lecturas. */
    public Double potenciaMediaPrincipal(LocalDateTime desde, LocalDateTime hasta) {
        Double v = potenciaMedia(PRINCIPAL, desde, hasta);
        return v == null ? null : v * KW_POR_UNIDAD_PRINCIPAL;
    }

    /**
     * Consumo de la planta 1 en un intervalo (potencia media, kW): lo que generó y lo que puso en la planta cada
     * generador pedido (en el orden de la lista), lo que pusieron los demás generadores, y lo importado y lo exportado
     * por el medidor principal.
     */
    public record PuntoPlanta(LocalDateTime fecha, double[] generado, double[] aporteGeneradores, double aporteOtros,
                              double importada, double exportada) {
        public double consumo() {
            return Arrays.stream(aporteGeneradores).sum() + aporteOtros + importada;
        }
    }

    /** Lectura del generador más vieja que esto = sin dato (parado guarda una cada 15 min). */
    private static final long GENERADOR_VIGENTE_MIN = 16;
    /** En marcha, la potencia se usa si la lectura tiene hasta esta edad (guarda una por minuto). */
    private static final long MARCHA_VIGENTE_MIN = 5;

    /**
     * Consumo de la planta 1 entre dos instantes, en puntos de {@code minutosPorPunto} minutos. Por cada lectura del
     * medidor principal (una por minuto, + importa / - exporta) se toma la potencia de cada generador en ese minuto (0 si
     * estaba parado); lo exportado se reparte entre los generadores según lo que genera cada uno, y cada uno pone en la
     * planta lo que genera menos su parte. Consumo = generadores + importada. Sin lecturas del principal no hay punto
     * (hueco en el gráfico).
     */
    public List<PuntoPlanta> consumoPlanta(List<Generador> generadores, LocalDateTime desde, LocalDateTime hasta, int minutosPorPunto) {
        List<Generador> todos = generadorService.generadores();
        int n = generadores.size(), m = todos.size();
        int[] indice = new int[m]; // lugar de cada generador en la lista pedida; -1 = uno de los "otros"
        List<TreeMap<LocalDateTime, LecturaGuardada>> lecturas = new ArrayList<>();
        for (int j = 0; j < m; j++) {
            String nombre = todos.get(j).nombre();
            indice[j] = -1;
            for (int i = 0; i < n; i++) if (generadores.get(i).nombre().equals(nombre)) indice[j] = i;
            lecturas.add(lecturasGenerador(todos.get(j), desde.toLocalDate(), hasta.toLocalDate()));
        }
        // Por punto: {lecturas, importada, exportada, aporte de los otros, aporte de cada pedido..., generado de cada pedido...}.
        TreeMap<LocalDateTime, double[]> puntos = new TreeMap<>();
        for (Map.Entry<LocalDateTime, Double> l : seriePotenciaPrincipal(desde.toLocalDate(), hasta.toLocalDate())
                .subMap(desde, true, hasta, false).entrySet()) {
            double kw = l.getValue();
            double exportada = Math.max(0, -kw);
            double[] kwGen = new double[m];
            double totalGen = 0;
            for (int j = 0; j < m; j++) {
                kwGen[j] = kwGenerador(lecturas.get(j), l.getKey());
                totalGen += kwGen[j];
            }
            double[] p = puntos.computeIfAbsent(inicioPunto(l.getKey(), minutosPorPunto), k -> new double[4 + 2 * n]);
            p[0]++;
            p[1] += Math.max(0, kw);
            p[2] += exportada;
            for (int j = 0; j < m; j++) {
                double aporte = totalGen > 0 ? Math.max(0, kwGen[j] - exportada * kwGen[j] / totalGen) : 0;
                if (indice[j] < 0) {
                    p[3] += aporte;
                } else {
                    p[4 + indice[j]] += aporte;
                    p[4 + n + indice[j]] += kwGen[j];
                }
            }
        }
        List<PuntoPlanta> lista = new ArrayList<>();
        puntos.forEach((t, p) -> {
            double[] aporte = new double[n], generado = new double[n];
            for (int i = 0; i < n; i++) {
                aporte[i] = p[4 + i] / p[0];
                generado[i] = p[4 + n + i] / p[0];
            }
            lista.add(new PuntoPlanta(t, generado, aporte, p[3] / p[0], p[1] / p[0], p[2] / p[0]));
        });
        return lista;
    }

    /** Potencia del generador en un instante: la de su última lectura si estaba en marcha; 0 parado o sin dato. */
    private static double kwGenerador(TreeMap<LocalDateTime, LecturaGuardada> lecturas, LocalDateTime t) {
        Map.Entry<LocalDateTime, LecturaGuardada> e = lecturas.floorEntry(t);
        if (e == null) return 0;
        long edad = Duration.between(e.getKey(), t).toMinutes();
        LecturaGuardada l = e.getValue();
        if (edad > GENERADOR_VIGENTE_MIN || !l.marcha() || edad > MARCHA_VIGENTE_MIN || l.kw() == null) return 0;
        return Math.max(0, l.kw());
    }

    /** Comienzo del punto de {@code minutos} minutos que contiene a t (contados desde la medianoche). */
    private static LocalDateTime inicioPunto(LocalDateTime t, int minutos) {
        LocalDateTime m = t.withSecond(0).withNano(0);
        if (minutos <= 1) return m;
        int delDia = m.getHour() * 60 + m.getMinute();
        return m.minusMinutes(delDia % minutos);
    }

    /** Lo de un período en marcha que cayó en un día calendario. null = sin lecturas que lo cubran. */
    public record DiaMarcha(LocalDate dia, Double generado, Double redImportada, Double redExportada) {
    }

    /**
     * Un período en marcha partido por día calendario, con los mismos contadores que el período (generador y su parte
     * del medidor principal), para el balance diario del reporte.
     */
    public List<DiaMarcha> repartoPorDia(PeriodoMarcha p) {
        List<DiaMarcha> lista = new ArrayList<>();
        List<Generador> todos = generadorService.generadores();
        if (todos.stream().noneMatch(x -> x.nombre().equals(p.generador()))) return lista;
        LocalDate desde = p.inicio().toLocalDate(), hasta = p.fin().toLocalDate();
        Map<String, TreeMap<LocalDateTime, LecturaGuardada>> lecturasDe = lecturasDe(todos, desde, hasta);
        TreeMap<LocalDateTime, Double> kwhGen = new TreeMap<>();
        lecturasDe.get(p.generador()).values().forEach(l -> { if (l.kwh() != null && l.kwh() > 0) kwhGen.put(l.fecha(), l.kwh()); });
        RedPrincipal red = redPrincipal(todos, lecturasDe, desde, hasta);
        TreeMap<LocalDateTime, Double> importada = red.importadaDe().get(p.generador());
        TreeMap<LocalDateTime, Double> exportada = red.exportadaDe().get(p.generador());
        LocalDateTime ini = p.inicio();
        while (ini.isBefore(p.fin())) {
            LocalDateTime fin = ini.toLocalDate().plusDays(1).atStartOfDay();
            if (fin.isAfter(p.fin())) fin = p.fin();
            lista.add(new DiaMarcha(ini.toLocalDate(), deltaEnVentana(kwhGen, ini, fin, KW_MAX_GENERADOR),
                    deltaEnVentana(importada, ini, fin, KW_MAX_RED), deltaEnVentana(exportada, ini, fin, KW_MAX_RED)));
            ini = fin;
        }
        return lista;
    }

    /**
     * Aumento de un contador dentro de [ini, fin]. Las lecturas son cada ~1 min y no coinciden con el
     * inicio/fin del arranque: el intervalo que cruza un borde aporta en proporción al tiempo que cae
     * adentro. null = sin lecturas que cubran el intervalo.
     */
    static Double deltaEnVentana(TreeMap<LocalDateTime, Double> serie, LocalDateTime ini, LocalDateTime fin, double kwMax) {
        Map.Entry<LocalDateTime, Double> a = serie.floorEntry(ini);
        if (a == null) a = serie.ceilingEntry(ini);
        if (a == null || a.getKey().isAfter(fin)) return null;
        double total = 0;
        boolean cubierto = false;
        for (Map.Entry<LocalDateTime, Double> b : serie.tailMap(a.getKey(), false).entrySet()) {
            long tramo = Duration.between(a.getKey(), b.getKey()).toSeconds();
            LocalDateTime desdeAdentro = a.getKey().isAfter(ini) ? a.getKey() : ini;
            LocalDateTime hastaAdentro = b.getKey().isBefore(fin) ? b.getKey() : fin;
            long adentro = Duration.between(desdeAdentro, hastaAdentro).toSeconds();
            Double d = tramo <= 0 ? null : delta(a.getValue(), b.getValue(), kwMax * tramo / 3600.0 + 1);
            if (d != null && adentro > 0) {
                total += d * adentro / tramo;
                cubierto = true;
            }
            if (!b.getKey().isBefore(fin)) break;
            a = b;
        }
        return cubierto ? total : null;
    }

    /** Lo que sumó un contador en una ventana, por franja horaria (A..D), y su valor con la tarifa de la red. */
    record Reparto(double[] kwh, double costo, boolean sinTarifa) {
        double total() {
            return Arrays.stream(kwh).sum();
        }
    }

    /**
     * Igual que {@link #deltaEnVentana}, pero cada tramo entre dos lecturas se reparte entre las franjas que cruza
     * (en proporción al tiempo) y se valoriza con el precio de esa franja en la tarifa de su mes. sinTarifa = algún
     * pedazo cayó en un mes sin tarifa cargada (ni propia ni anterior).
     */
    static Reparto repartir(TreeMap<LocalDateTime, Double> serie, LocalDateTime ini, LocalDateTime fin, double kwMax,
                            Predicate<LocalDate> diaEspecial, Function<YearMonth, TarifaDelMes> tarifaDe) {
        double[] kwh = new double[Franja.values().length];
        double costo = 0;
        boolean sinTarifa = false;
        Map.Entry<LocalDateTime, Double> a = serie.floorEntry(ini);
        if (a == null) a = serie.ceilingEntry(ini);
        if (a == null || a.getKey().isAfter(fin)) return new Reparto(kwh, 0, false);
        for (Map.Entry<LocalDateTime, Double> b : serie.tailMap(a.getKey(), false).entrySet()) {
            long tramo = Duration.between(a.getKey(), b.getKey()).toSeconds();
            LocalDateTime desdeAdentro = a.getKey().isAfter(ini) ? a.getKey() : ini;
            LocalDateTime hastaAdentro = b.getKey().isBefore(fin) ? b.getKey() : fin;
            Double d = tramo <= 0 ? null : delta(a.getValue(), b.getValue(), kwMax * tramo / 3600.0 + 1);
            if (d != null && hastaAdentro.isAfter(desdeAdentro)) {
                double adentro = d * Duration.between(desdeAdentro, hastaAdentro).toSeconds() / tramo;
                for (Franja.Pedazo p : Franja.partir(desdeAdentro, hastaAdentro, diaEspecial)) {
                    double k = adentro * p.fraccion();
                    kwh[p.franja().ordinal()] += k;
                    TarifaDelMes t = tarifaDe.apply(YearMonth.from(p.desde()));
                    if (t.sinTarifa()) sinTarifa = true;
                    else costo += k * t.precio(p.franja());
                }
            }
            if (!b.getKey().isBefore(fin)) break;
            a = b;
        }
        return new Reparto(kwh, costo, sinTarifa);
    }

    // ================= Generador =================

    private void acumularGenerador(Generador g, LocalDate desde, LocalDate hasta, TreeMap<LocalDate, Acum> dias) {
        TreeMap<LocalDateTime, LecturaGuardada> lecturas = lecturasGenerador(g, desde, hasta);
        LecturaGuardada a = null;
        for (LecturaGuardada b : lecturas.values()) {
            LocalDate dia = b.fecha().toLocalDate();
            Acum acum = dias.get(dia);
            if (acum != null) {
                // Día con lecturas del generador: energía/horas/arranques en 0 (no "sin datos").
                acum.generado = suma(acum.generado, 0.0);
                acum.horasMarcha = suma(acum.horasMarcha, 0.0);
                acum.arranques = acum.arranques == null ? 0 : acum.arranques;
                if (b.marcha() && b.kw() != null && (acum.kwMax == null || b.kw() > acum.kwMax)) acum.kwMax = b.kw();
            }
            if (a != null && acum != null) {
                double horas = Duration.between(a.fecha(), b.fecha()).toSeconds() / 3600.0;
                // Energía: del contador; se clasifica con la lectura en marcha del intervalo.
                LecturaGuardada enMarcha = b.marcha() ? b : a.marcha() ? a : null;
                Double dkwh = delta(a.kwh(), b.kwh(), KW_MAX_GENERADOR * horas + 1);
                if (dkwh != null) {
                    acum.generado += dkwh;
                    if (enMarcha != null && enMarcha.redV() != null) {
                        if (enMarcha.redV() >= V_RED_PRESENTE) acum.generadoParalelo = suma(acum.generadoParalelo, dkwh);
                        else acum.generadoIsla = suma(acum.generadoIsla, dkwh);
                    }
                }
                // Horas: del horómetro del controlador; si no lo da, de las lecturas en marcha.
                Double dh = delta(a.horas(), b.horas(), horas + 0.15);
                double minutos = a.marcha() ? Math.min(horas * 60, MAX_MIN_POR_LECTURA) : 0;
                acum.horasMarcha += dh != null ? dh : minutos / 60.0;
                if (minutos > 0) {
                    if (a.kw() != null && a.kw() < KW_VACIO) acum.horasVacio = suma(acum.horasVacio, minutos / 60.0);
                    else if (a.redV() != null && a.redV() >= V_RED_PRESENTE) acum.horasParalelo = suma(acum.horasParalelo, minutos / 60.0);
                    else if (a.redV() != null) acum.horasIsla = suma(acum.horasIsla, minutos / 60.0);
                }
                Double darr = delta(a.arranques(), b.arranques(), 50);
                if (darr != null) acum.arranques += (int) Math.round(darr);
            }
            a = b;
        }
    }

    /** Lecturas guardadas del generador entre el día anterior a {@code desde} y {@code hasta} (sin repetidas). */
    private TreeMap<LocalDateTime, LecturaGuardada> lecturasGenerador(Generador g, LocalDate desde, LocalDate hasta) {
        TreeMap<LocalDateTime, LecturaGuardada> lecturas = new TreeMap<>();
        for (YearMonth m = YearMonth.from(desde.minusDays(1)); !m.isAfter(YearMonth.from(hasta)); m = m.plusMonths(1)) {
            String ruta = GeneradorAlmacen.rutaLecturas(m);
            if (!new File(ruta).exists()) continue;
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta.replace('\\', '/') + "?mode=ro");
                 ResultSet r = c.createStatement().executeQuery("SELECT * FROM \"" + g.nombre() + "\"")) {
                Set<String> cols = columnas(r.getMetaData());
                while (r.next()) {
                    LocalDateTime f = fecha(r.getString("fecha"));
                    if (f == null || f.toLocalDate().isBefore(desde.minusDays(1)) || f.toLocalDate().isAfter(hasta)) continue;
                    Double v1 = num(r, cols, "red_v_l1l2"), v2 = num(r, cols, "red_v_l2l3"), v3 = num(r, cols, "red_v_l3l1");
                    Double redV = v1 == null || v2 == null || v3 == null ? null : (v1 + v2 + v3) / 3.0;
                    lecturas.put(f, new LecturaGuardada(f, "MARCHA".equals(r.getString("estado")), num(r, cols, "kw"),
                            num(r, cols, "kwh"), num(r, cols, "horas_marcha"), num(r, cols, "arranques"), redV));
                }
            } catch (Exception e) {
                if (!String.valueOf(e.getMessage()).contains("no such table")) {
                    logger.warn("Analisis: no se pudieron leer las lecturas de {} en {}: {}", g.nombre(), ruta, e.getMessage());
                }
            }
        }
        return lecturas;
    }

    // ================= Red (medidor principal de la planta 1, leído por PLC) =================

    /**
     * Importado: todo el día (la red alimenta la planta siempre). Exportado: la parte de los generadores elegidos y la de
     * los demás, por día; lo exportado sin ningún generador en marcha no se cuenta (ej. Caterpillar el 09-10: antes de
     * que se registrara su arranque, el transformador marcaba 505 kWh "exportados" contra 194 generados desde las 09:19).
     */
    private void acumularRed(Set<String> elegidos, List<Generador> todos, LocalDate desde, LocalDate hasta, TreeMap<LocalDate, Acum> dias) {
        RedPrincipal red = redPrincipal(todos, lecturasDe(todos, desde, hasta), desde, hasta);
        sumarDeltas(red.importada(), dias, true);
        for (Map.Entry<LocalDate, Acum> e : dias.entrySet()) {
            Acum a = e.getValue();
            LocalDateTime ini = e.getKey().atStartOfDay(), fin = ini.plusDays(1);
            for (Generador g : todos) {
                Double d = deltaEnVentana(red.exportadaDe().get(g.nombre()), ini, fin, KW_MAX_RED);
                if (d == null) continue;
                if (elegidos.contains(g.nombre())) a.redExportada = suma(a.redExportada, d);
                else a.redExportadaOtros = suma(a.redExportadaOtros, d);
            }
            if (a.redImportada != null && a.redExportada == null) a.redExportada = 0.0;
        }
    }

    /**
     * Contadores del medidor principal (importado y exportado por toda la planta 1) y, por generador, la parte que le
     * toca de cada uno, como series acumuladas con las mismas fechas que el contador (sirven para deltaEnVentana y
     * repartir).
     */
    private record RedPrincipal(TreeMap<LocalDateTime, Double> importada, TreeMap<LocalDateTime, Double> exportada,
                                Map<String, TreeMap<LocalDateTime, Double>> importadaDe,
                                Map<String, TreeMap<LocalDateTime, Double>> exportadaDe) {
    }

    private RedPrincipal redPrincipal(List<Generador> todos, Map<String, TreeMap<LocalDateTime, LecturaGuardada>> lecturasDe,
                                      LocalDate desde, LocalDate hasta) {
        LocalDateTime ahora = LocalDateTime.now().withNano(0);
        Map<String, TreeMap<LocalDateTime, LocalDateTime>> marchas = new HashMap<>();
        for (Generador g : todos) {
            TreeMap<LocalDateTime, LocalDateTime> m = new TreeMap<>();
            for (GeneradorService.Arranque a : generadorService.arranques(g)) m.put(a.inicio(), a.fin() == null ? ahora : a.fin());
            marchas.put(g.nombre(), m);
        }
        TreeMap<LocalDateTime, Double> importada = serieContador(PRINCIPAL, false, desde, hasta);
        TreeMap<LocalDateTime, Double> exportada = serieContador(PRINCIPAL, true, desde, hasta);
        return new RedPrincipal(importada, exportada, repartirEntreGeneradores(importada, todos, lecturasDe, marchas),
                repartirEntreGeneradores(exportada, todos, lecturasDe, marchas));
    }

    /**
     * Reparte cada tramo de un contador del medidor principal entre los generadores que estaban en un arranque al final
     * del tramo, según lo que generaba cada uno (en partes iguales si ninguno tenía carga). Lo de los tramos sin ningún
     * generador en marcha no le toca a ninguno.
     */
    private static Map<String, TreeMap<LocalDateTime, Double>> repartirEntreGeneradores(TreeMap<LocalDateTime, Double> contador,
            List<Generador> todos, Map<String, TreeMap<LocalDateTime, LecturaGuardada>> lecturasDe,
            Map<String, TreeMap<LocalDateTime, LocalDateTime>> marchas) {
        int n = todos.size();
        double[] acumulado = new double[n];
        Map<String, TreeMap<LocalDateTime, Double>> partes = new LinkedHashMap<>();
        for (Generador g : todos) partes.put(g.nombre(), new TreeMap<>());
        Map.Entry<LocalDateTime, Double> a = null;
        for (Map.Entry<LocalDateTime, Double> b : contador.entrySet()) {
            if (a != null) {
                double horas = Duration.between(a.getKey(), b.getKey()).toSeconds() / 3600.0;
                Double d = delta(a.getValue(), b.getValue(), KW_MAX_RED * horas + 1);
                if (d != null && d > 0) {
                    boolean[] enMarcha = new boolean[n];
                    double[] kw = new double[n];
                    double totalKw = 0;
                    int cuantos = 0;
                    for (int i = 0; i < n; i++) {
                        String nombre = todos.get(i).nombre();
                        Map.Entry<LocalDateTime, LocalDateTime> arranque = marchas.get(nombre).floorEntry(b.getKey());
                        if (arranque == null || arranque.getValue().isBefore(b.getKey())) continue;
                        enMarcha[i] = true;
                        cuantos++;
                        kw[i] = kwGenerador(lecturasDe.getOrDefault(nombre, new TreeMap<>()), b.getKey());
                        totalKw += kw[i];
                    }
                    for (int i = 0; i < n; i++) {
                        if (totalKw > 0) acumulado[i] += d * kw[i] / totalKw;
                        else if (enMarcha[i]) acumulado[i] += d / cuantos;
                    }
                }
            }
            for (int i = 0; i < n; i++) partes.get(todos.get(i).nombre()).put(b.getKey(), acumulado[i]);
            a = b;
        }
        return partes;
    }

    /** Lecturas guardadas de cada generador (por nombre). */
    private Map<String, TreeMap<LocalDateTime, LecturaGuardada>> lecturasDe(List<Generador> generadores, LocalDate desde, LocalDate hasta) {
        Map<String, TreeMap<LocalDateTime, LecturaGuardada>> m = new HashMap<>();
        for (Generador g : generadores) m.put(g.nombre(), lecturasGenerador(g, desde, hasta));
        return m;
    }

    /** Potencia del medidor principal minuto a minuto, en kW (+ toma de la red, - entrega). */
    private TreeMap<LocalDateTime, Double> seriePotenciaPrincipal(LocalDate desde, LocalDate hasta) {
        TreeMap<LocalDateTime, Double> serie = seriePotencia(PRINCIPAL, desde, hasta);
        serie.replaceAll((t, v) -> v * KW_POR_UNIDAD_PRINCIPAL);
        return serie;
    }

    /** Potencia del medidor de la red minuto a minuto (archivo VIP); negativa = entregando a la red. */
    private TreeMap<LocalDateTime, Double> seriePotencia(String maquina, LocalDate desde, LocalDate hasta) {
        TreeMap<LocalDateTime, Double> serie = new TreeMap<>();
        for (YearMonth m = YearMonth.from(desde.minusDays(1)); !m.isAfter(YearMonth.from(hasta)); m = m.plusMonths(1)) {
            String ruta = RutaArchivosEnergia.construirRutaMensual(m.getYear(), m.getMonthValue(), true);
            if (!new File(ruta).exists()) continue;
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta.replace('\\', '/') + "?mode=ro");
                 ResultSet r = c.createStatement().executeQuery("SELECT fecha, PW FROM " + maquina)) {
                while (r.next()) {
                    LocalDateTime f = fecha(r.getString(1));
                    if (f != null && !f.toLocalDate().isBefore(desde) && !f.toLocalDate().isAfter(hasta)) serie.put(f, r.getDouble(2));
                }
            } catch (Exception e) {
                if (!String.valueOf(e.getMessage()).contains("no such table")) {
                    logger.warn("Analisis: no se pudo leer PW de {} en {}: {}", maquina, ruta, e.getMessage());
                }
            }
        }
        return serie;
    }

    /** Contador del medidor de la red: kWh importado (archivo normal) o KWhR exportado (archivo VIP). */
    private TreeMap<LocalDateTime, Double> serieContador(String maquina, boolean exportada, LocalDate desde, LocalDate hasta) {
        TreeMap<LocalDateTime, Double> serie = new TreeMap<>();
        for (YearMonth m = YearMonth.from(desde.minusDays(1)); !m.isAfter(YearMonth.from(hasta)); m = m.plusMonths(1)) {
            leerContador(RutaArchivosEnergia.construirRutaMensual(m.getYear(), m.getMonthValue(), exportada), maquina,
                    exportada ? "KWhR" : "kwh", desde, hasta, serie);
        }
        return serie;
    }

    private void leerContador(String ruta, String maquina, String columna, LocalDate desde, LocalDate hasta,
                              TreeMap<LocalDateTime, Double> serie) {
        if (!new File(ruta).exists()) return;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + ruta.replace('\\', '/') + "?mode=ro");
             ResultSet r = c.createStatement().executeQuery("SELECT fecha, " + columna + " FROM " + maquina)) {
            LocalDate antes = desde.minusDays(1);
            while (r.next()) {
                LocalDateTime f = fecha(r.getString(1));
                if (f == null || f.toLocalDate().isBefore(antes) || f.toLocalDate().isAfter(hasta)) continue;
                double v = r.getDouble(2);
                if (v > 1) serie.put(f, v); // 0 y basura (1e-42) no son lecturas del contador
            }
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("no such table")) {
                logger.warn("Analisis: no se pudo leer {} de {} en {}: {}", columna, maquina, ruta, e.getMessage());
            }
        }
    }

    private static void sumarDeltas(TreeMap<LocalDateTime, Double> serie, TreeMap<LocalDate, Acum> dias, boolean importada) {
        Map.Entry<LocalDateTime, Double> a = null;
        for (Map.Entry<LocalDateTime, Double> b : serie.entrySet()) {
            Acum acum = dias.get(b.getKey().toLocalDate());
            if (acum != null) {
                if (importada) acum.redImportada = suma(acum.redImportada, 0.0);
                else acum.redExportada = suma(acum.redExportada, 0.0);
            }
            if (a != null && acum != null) {
                double horas = Duration.between(a.getKey(), b.getKey()).toSeconds() / 3600.0;
                Double d = delta(a.getValue(), b.getValue(), KW_MAX_RED * horas + 1);
                if (d != null) {
                    if (importada) acum.redImportada += d;
                    else acum.redExportada += d;
                }
            }
            a = b;
        }
    }

    /** Diferencia de un contador, o null si falta un dato, bajó (reinicio) o el salto es imposible. */
    private static Double delta(Double a, Double b, double maximo) {
        if (a == null || b == null) return null;
        double d = b - a;
        return d < 0 || d > maximo ? null : d;
    }

    private static Double suma(Double a, Double b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + b;
    }

    private static double nz(Double v) {
        return v == null ? 0 : v;
    }

    private static Set<String> columnas(ResultSetMetaData md) throws java.sql.SQLException {
        Set<String> s = new HashSet<>();
        for (int i = 1; i <= md.getColumnCount(); i++) s.add(md.getColumnName(i).toLowerCase(Locale.ROOT));
        return s;
    }

    private static Double num(ResultSet r, Set<String> cols, String col) throws java.sql.SQLException {
        if (!cols.contains(col)) return null;
        Object o = r.getObject(col);
        return o instanceof Number n ? n.doubleValue() : null;
    }

    /** "dd-MM-yyyy HH:mm:ss" sin DateTimeFormatter (se leen cientos de miles de filas). */
    static LocalDateTime fecha(String s) {
        if (s == null || s.length() < 19) return null;
        try {
            return LocalDateTime.of(Integer.parseInt(s.substring(6, 10)), Integer.parseInt(s.substring(3, 5)),
                    Integer.parseInt(s.substring(0, 2)), Integer.parseInt(s.substring(11, 13)),
                    Integer.parseInt(s.substring(14, 16)), Integer.parseInt(s.substring(17, 19)));
        } catch (Exception e) {
            return null;
        }
    }
}
