package com.example.generador.service;

import com.example.generador.model.CostoCombustible;
import com.example.generador.model.Generador;
import com.example.generador.model.MetaImportacionRed;
import com.example.generador.service.GeneradorAnalisisService.Agrupacion;
import com.example.generador.service.GeneradorAnalisisService.Consulta;
import com.example.generador.service.GeneradorAnalisisService.Fila;
import com.example.generador.service.GeneradorAnalisisService.MinutoMarcha;
import com.example.generador.service.GeneradorAnalisisService.PeriodoMarcha;
import com.example.generador.service.GeneradorAnalisisService.PeriodoPlanta;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reporte mensual de generadores (rediseño del 2026-10-27 sobre la maqueta aprobada por el usuario): un HTML de un solo
 * archivo (Chart.js adentro, funciona sin internet) con el mes elegido en Análisis; si es el mes en curso, del 1 hasta
 * hoy. Mismas cuentas que Análisis: aporte de cada generador (generado - su parte de lo exportado) + importada de la red
 * = consumo de la planta 1, por períodos de la planta (los arranques que se pisan van juntos); la exportada va solo como
 * información. Después el balance diario de días completos, el combustible y costo de generación y, opcional, el anexo
 * con el perfil de potencia de cada arranque. Se imprime o se guarda como PDF desde el navegador. Solo lectura.
 */
@Service
public class ReporteGeneradoresService {

    private static final String PLANTILLA = "/reportes/reporte-generadores.html";
    private static final String CHART_JS = "/reportes/chart.umd.min.js";
    private static final DateTimeFormatter MINUTO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter GENERADO = DateTimeFormatter.ofPattern("dd-MM-yyyy 'a las' HH:mm");
    /** Colores de los generadores en el orden de generador-config.json, los mismos de Análisis. */
    private static final String[] COLORES_GEN = {"#F29F67", "#6F42C1"};

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

    public static String nombreArchivo(YearMonth mes) {
        return "reporte-generadores-" + mes + ".html";
    }

    /** Escribe el reporte del mes: del día 1 al último, o hasta hoy si el mes no terminó. */
    public void escribir(OutputStream out, List<Generador> generadores, YearMonth mes) throws IOException {
        LocalDate desde = mes.atDay(1), hoy = LocalDate.now();
        LocalDate hasta = mes.atEndOfMonth().isAfter(hoy) ? hoy : mes.atEndOfMonth();
        Consulta consulta = new Consulta();
        List<PeriodoMarcha> periodos = desde.isAfter(hasta) ? List.of() : analisis.periodosEnMarcha(consulta, generadores, desde, hasta);
        Map<String, CostoCombustible> combustible = costos.porPeriodo(periodos);

        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("mes", mes.toString());
        datos.put("desde", desde.toString());
        datos.put("hasta", hasta.toString());
        datos.put("mesCompleto", !hasta.isBefore(mes.atEndOfMonth()));
        List<Map<String, Object>> gens = new ArrayList<>();
        for (int i = 0; i < generadores.size(); i++) {
            Generador g = generadores.get(i);
            gens.add(Map.of("nombre", g.nombre(), "red", g.redAsociada() == null ? "" : g.redAsociada(),
                    "color", COLORES_GEN[i % COLORES_GEN.length]));
        }
        datos.put("generadores", gens);
        MetaImportacionRed meta = generadorService.metaImportacionRed();
        datos.put("verdeHasta", meta.verdeHasta());
        datos.put("amarilloHasta", meta.amarilloHasta());
        List<Map<String, Object>> lista = new ArrayList<>();
        for (PeriodoPlanta p : GeneradorAnalisisService.periodosPlanta(periodos)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("inicio", p.inicio().format(MINUTO));
            m.put("fin", p.fin().format(MINUTO));
            m.put("enCurso", p.enCurso());
            List<Map<String, Object>> arranques = new ArrayList<>();
            for (PeriodoMarcha a : p.arranques()) {
                arranques.add(datosArranque(a, combustible.get(CostoCombustibleService.clave(a.generador(), a.arranqueId()))));
            }
            m.put("arranques", arranques);
            lista.add(m);
        }
        datos.put("periodos", lista);
        datos.put("dias", desde.isAfter(hasta) ? List.of() : diasCompletos(consulta, generadores, desde, hasta));

        // "</" dentro del <script> cerraría la etiqueta: se escribe "<\/" (mismo texto para JavaScript).
        String json = objectMapper.writeValueAsString(datos).replace("</", "<\\/");
        String html = recurso(PLANTILLA)
                .replace("/*DATOS*/null", json)
                .replace("/*GENERADO_EL*/", LocalDateTime.now().format(GENERADO))
                .replace("/*CHARTJS*/", recurso(CHART_JS));
        out.write(html.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Cada día completo (24 h) del mes, como Resumen de energía: la importada del medidor principal y lo que puso cada
     * generador en la planta (generado - su parte de lo exportado). Aporte + red = consumo del día.
     */
    private List<Map<String, Object>> diasCompletos(Consulta c, List<Generador> generadores, LocalDate desde, LocalDate hasta) {
        Map<String, Map<String, Fila>> porGenerador = new LinkedHashMap<>();
        for (Generador g : generadores) {
            Map<String, Fila> m = new HashMap<>();
            analisis.analizar(c, List.of(g), Agrupacion.DIA, desde, hasta).forEach(f -> m.put(f.periodo(), f));
            porGenerador.put(g.nombre(), m);
        }
        List<Map<String, Object>> dias = new ArrayList<>();
        for (Fila f : analisis.analizar(c, generadores, Agrupacion.DIA, desde, hasta)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("dia", f.desde().toString());
            d.put("red", redondo(f.redImportada()));
            Map<String, Long> aporte = new LinkedHashMap<>();
            porGenerador.forEach((n, m) -> {
                Fila x = m.get(f.periodo());
                aporte.put(n, x == null || x.generadorUsado() == null ? 0L : Math.round(x.generadorUsado()));
            });
            d.put("aporte", aporte);
            dias.add(d);
        }
        dias.sort(Comparator.comparing(d -> (String) d.get("dia")));
        return dias;
    }

    private Map<String, Object> datosArranque(PeriodoMarcha p, CostoCombustible c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generador", p.generador());
        m.put("red", p.red());
        m.put("inicio", p.inicio().format(MINUTO));
        m.put("fin", p.fin().format(MINUTO));
        m.put("enCurso", p.enCurso());
        m.put("estimado", p.inicioEstimado());
        m.put("generado", redondo(p.kwhGenerado()));
        m.put("importada", redondo(p.kwhRedImportada()));
        m.put("exportada", redondo(p.kwhRedExportada()));
        if (c != null) {
            m.put("galones", c.getGalones());
            m.put("precio", c.getPrecioGalon());
        }
        // Igual que la pestaña Costos: lo aportado (generado - exportado) al precio de la franja horaria de cada hora.
        if (p.costoRed() != null) m.put("costoRed", Math.round(p.costoRed() * 100) / 100.0);

        // Perfil de potencia en promedios de 15 minutos (anexo opcional): generador, consumo y red (+ importa, - exporta).
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
        return m;
    }

    private static Long redondo(Double v) {
        return v == null ? null : Math.round(v);
    }

    private static String recurso(String ruta) throws IOException {
        try (InputStream in = ReporteGeneradoresService.class.getResourceAsStream(ruta)) {
            if (in == null) throw new IOException("No se encontró " + ruta + " dentro de la aplicación");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
