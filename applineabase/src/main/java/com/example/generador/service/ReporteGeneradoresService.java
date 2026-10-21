package com.example.generador.service;

import com.example.generador.model.CostoCombustible;
import com.example.generador.model.Generador;
import com.example.generador.model.MetaImportacionRed;
import com.example.generador.service.GeneradorAnalisisService.DiaMarcha;
import com.example.generador.service.GeneradorAnalisisService.MinutoMarcha;
import com.example.generador.service.GeneradorAnalisisService.PeriodoMarcha;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reporte de generadores: un HTML de un solo archivo (Chart.js adentro, funciona sin internet) con todos los
 * arranques del período elegido en Análisis. En el reporte se eligen los arranques y se imprime o se guarda como
 * PDF desde el navegador. Primero lo global (horas, energía, balance del período, meta de consumo cero de la red,
 * combustible y costo) y después el detalle (tabla por período, importación por período, balance diario y, opcional,
 * el perfil de potencia de cada arranque).
 *
 * Las cifras salen del mismo cálculo que la pestaña Períodos en marcha (contadores); el combustible, de lo cargado en
 * Costos, y el costo en la red, del mismo cálculo que Costos (precio de la franja horaria de cada hora). Solo lectura.
 */
@Service
public class ReporteGeneradoresService {

    private static final String PLANTILLA = "/reportes/reporte-generadores.html";
    private static final String CHART_JS = "/reportes/chart.umd.min.js";
    private static final DateTimeFormatter MINUTO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter GENERADO = DateTimeFormatter.ofPattern("dd-MM-yyyy 'a las' HH:mm");

    private final GeneradorService generadorService;
    private final GeneradorAnalisisService analisis;
    private final CostoCombustibleService costos;
    private final ObjectMapper objectMapper;

    public ReporteGeneradoresService(GeneradorService generadorService, GeneradorAnalisisService analisis,
                                     CostoCombustibleService costos, ObjectMapper objectMapper) {
        this.generadorService = generadorService;
        this.analisis = analisis;
        this.costos = costos;
        this.objectMapper = objectMapper;
    }

    public static String nombreArchivo(LocalDate desde, LocalDate hasta) {
        return "reporte-generadores-" + desde + "_a_" + hasta + ".html";
    }

    /** Escribe el reporte de los arranques de esos generadores que se pisan con [desde, hasta]. */
    public void escribir(OutputStream out, List<Generador> generadores, LocalDate desde, LocalDate hasta) throws IOException {
        List<PeriodoMarcha> periodos = analisis.periodosEnMarcha(generadores, desde, hasta);
        Map<String, CostoCombustible> combustible = costos.porPeriodo(periodos);
        List<Map<String, Object>> datos = new ArrayList<>();
        for (PeriodoMarcha p : periodos) {
            datos.add(datosPeriodo(p, combustible.get(CostoCombustibleService.clave(p.generador(), p.arranqueId()))));
        }
        MetaImportacionRed meta = generadorService.metaImportacionRed();
        // "</" dentro del <script> cerraría la etiqueta: se escribe "<\/" (mismo texto para JavaScript).
        String json = objectMapper.writeValueAsString(datos).replace("</", "<\\/");
        String planta = objectMapper.writeValueAsString(consumoPlanta(generadores, periodos)).replace("</", "<\\/");
        String html = recurso(PLANTILLA)
                .replace("/*DATOS*/", json)
                .replace("/*PLANTA*/null", planta)
                .replace("/*GENERADO_EL*/", LocalDateTime.now().format(GENERADO))
                .replace("/*VERDE_HASTA*/", numero(meta.verdeHasta()))
                .replace("/*AMARILLO_HASTA*/", numero(meta.amarilloHasta()))
                .replace("/*CHARTJS*/", recurso(CHART_JS));
        out.write(html.getBytes(StandardCharsets.UTF_8));
    }

    /** Puntos de 15 minutos del consumo de toda la planta para la sección "Balance energético de la planta". */
    private static final int MINUTOS_PLANTA = 15;

    /**
     * Con más de un generador: consumo de toda la planta 1 (medidor principal) en promedios de 15 minutos, solo en los
     * tramos en que alguno de los arranques del período estaba en marcha (el reporte después se queda con los de los
     * arranques elegidos). Cada punto: fecha, lo que generó cada generador, lo que puso en la planta cada uno,
     * importada y exportada (kW). null con un solo generador.
     */
    private Map<String, Object> consumoPlanta(List<Generador> generadores, List<PeriodoMarcha> periodos) {
        if (generadores.size() < 2 || periodos.isEmpty()) return null;
        LocalDateTime ini = periodos.stream().map(PeriodoMarcha::inicio).min(LocalDateTime::compareTo).orElseThrow()
                .minusMinutes(MINUTOS_PLANTA);
        LocalDateTime fin = periodos.stream().map(PeriodoMarcha::fin).max(LocalDateTime::compareTo).orElseThrow();
        int n = generadores.size();
        List<Object[]> puntos = new ArrayList<>();
        for (GeneradorAnalisisService.PuntoPlanta p : analisis.consumoPlanta(generadores, ini, fin, MINUTOS_PLANTA)) {
            LocalDateTime desde = p.fecha(), hasta = p.fecha().plusMinutes(MINUTOS_PLANTA);
            if (periodos.stream().noneMatch(a -> a.inicio().isBefore(hasta) && a.fin().isAfter(desde))) continue;
            Object[] fila = new Object[1 + 2 * n + 2];
            fila[0] = desde.format(MINUTO);
            for (int i = 0; i < n; i++) {
                fila[1 + i] = Math.round(p.generado()[i]);
                fila[1 + n + i] = Math.round(p.aporteGeneradores()[i]);
            }
            fila[1 + 2 * n] = Math.round(p.importada());
            fila[2 + 2 * n] = Math.round(p.exportada());
            puntos.add(fila);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generadores", generadores.stream().map(Generador::nombre).toList());
        m.put("minutos", MINUTOS_PLANTA);
        m.put("puntos", puntos);
        return m;
    }

    private Map<String, Object> datosPeriodo(PeriodoMarcha p, CostoCombustible c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.generador() + "-" + p.arranqueId());
        m.put("generador", p.generador());
        m.put("red", p.red());
        m.put("nro", p.nro());
        m.put("inicio", p.inicio().format(MINUTO));
        m.put("fin", p.fin().format(MINUTO));
        m.put("enCurso", p.enCurso());
        m.put("inicioEstimado", p.inicioEstimado());
        // El primer arranque registrado de cada generador: con inicio estimado, es cuando empezó su lectura en LineaBaseX
        // (va en las notas del reporte); en los demás, la app no estaba leyendo (se avisa antes de imprimir).
        m.put("primero", p.arranqueId() == 1);
        m.put("horas", Math.round(p.horas() * 10) / 10.0);
        m.put("generado", redondo(p.kwhGenerado()));
        m.put("tomado", redondo(p.kwhRedImportada()));
        m.put("entregado", redondo(p.kwhRedExportada()));
        m.put("consumo", redondo(p.consumo()));

        List<Object[]> porDia = new ArrayList<>();
        for (DiaMarcha d : analisis.repartoPorDia(p)) {
            porDia.add(new Object[]{d.dia().toString(), redondo(d.generado()), redondo(d.redImportada()), redondo(d.redExportada())});
        }
        m.put("porDia", porDia);

        // Perfil de potencia en promedios de 15 minutos (anexo del reporte): generador, consumo y red (+ importa, - exporta).
        TreeMap<LocalDateTime, double[]> cuartos = new TreeMap<>();
        for (MinutoMarcha x : analisis.minutosMarcha(p)) {
            if (x.generadorKw() == null || x.redKw() == null) continue;
            LocalDateTime q = x.fecha().withMinute(x.fecha().getMinute() / 15 * 15).withSecond(0).withNano(0);
            double[] v = cuartos.computeIfAbsent(q, k -> new double[4]);
            v[0] += x.generadorKw();
            v[1] += x.consumoKw();
            v[2] += x.redKw();
            v[3]++;
        }
        List<Object[]> perfil = new ArrayList<>();
        cuartos.forEach((t, v) -> perfil.add(new Object[]{t.format(MINUTO), Math.round(v[0] / v[3]), Math.round(v[1] / v[3]), Math.round(v[2] / v[3])}));
        m.put("perfil15", perfil);

        if (c != null) {
            m.put("galones", c.getGalones());
            m.put("precioGalon", c.getPrecioGalon());
            m.put("costo", Math.round(c.costo() * 100) / 100.0);
        }
        if (p.costoRed() != null) {
            // Igual que la pestaña Costos: aprovechado = generado - exportado, al precio de la franja horaria de cada hora.
            m.put("aprovechado", redondo(p.generadorUsado()));
            m.put("costoRed", Math.round(p.costoRed() * 100) / 100.0);
        }
        return m;
    }

    private static Long redondo(Double v) {
        return v == null ? null : Math.round(v);
    }

    /** 2.0 como "2" y 2.5 como "2.5" (para escribirlo dentro del JavaScript). */
    private static String numero(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String recurso(String ruta) throws IOException {
        try (InputStream in = ReporteGeneradoresService.class.getResourceAsStream(ruta)) {
            if (in == null) throw new IOException("No se encontro " + ruta + " dentro de la aplicacion");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
