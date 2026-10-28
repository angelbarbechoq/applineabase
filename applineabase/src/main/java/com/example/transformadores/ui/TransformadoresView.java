package com.example.transformadores.ui;

import com.example.base.ui.MainLayout;
import com.example.generador.model.Generador;
import com.example.generador.model.MetaImportacionRed;
import com.example.generador.service.GeneradorService;
import com.example.security.LineaAccessService;
import com.example.transformadores.service.TransformadoresService;
import com.example.transformadores.service.TransformadoresService.EstadoGenerador;
import com.example.transformadores.service.TransformadoresService.Foto;
import com.example.transformadores.service.TransformadoresService.GeneradorAhora;
import com.example.transformadores.service.TransformadoresService.Medicion;
import com.example.transformadores.service.TransformadoresService.TransformadorAhora;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import jakarta.annotation.security.PermitAll;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transformadores (pedido del usuario el 2026-10-27, sobre la maqueta aprobada): diagrama unifilar de la planta 1 en vivo,
 * cada 15 s. Red, medidor principal, nodo de media tensión, cada transformador con su medidor y, en su baja tensión, su
 * generador (cuánto aporta a la planta) y la suma de sus máquinas con medidor. Los kW van sin palabras: el sentido lo da
 * la flecha y el origen el color. El balance del nodo deja a la vista la diferencia entre el principal y la suma de los
 * transformadores (los PM5110 no cuadran; se ajustan en sitio). Lo dibuja unifilar-transformadores.js.
 */
@PageTitle("Transformadores | LineaBase")
@Route(value = "transformadores", layout = MainLayout.class)
@PermitAll
public class TransformadoresView extends VerticalLayout implements BeforeEnterObserver {

    private static final String SCRIPT = leerRecurso("/transformadores/unifilar-transformadores.js");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private static final int POLL_MS = 15000, POLL_MS_APP = 30000;
    /** Colores de los generadores, en el orden de generador-config.json (mismos que Analisis). */
    private static final String[] COLORES_GEN = {"#F29F67", "#6F42C1"};
    /**
     * Diferencia del nodo (principal - transformadores, % de lo que pasa): verde = lo esperable por las pérdidas de los
     * transformadores; amarillo y rojo = error de medición. Provisorios hasta corregir los PM5110.
     */
    private static final double NODO_VERDE_MIN = -1, NODO_VERDE_MAX = 3, NODO_AMARILLO_MIN = -4, NODO_AMARILLO_MAX = 6;

    private final TransformadoresService service;
    private final GeneradorService generadorService;
    private final LineaAccessService lineaAccessService;
    private final Span lectura = new Span();
    private final Div unifilar = new Div();
    private Registration pollRegistration;

    public TransformadoresView(TransformadoresService service, GeneradorService generadorService,
                               LineaAccessService lineaAccessService) {
        this.service = service;
        this.generadorService = generadorService;
        this.lineaAccessService = lineaAccessService;
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        H3 titulo = new H3("Transformadores");
        titulo.getStyle().set("margin", "0");
        lectura.getStyle().set("color", "#6C7383").set("font-size", "13px");
        Div barra = new Div(titulo, lectura);
        barra.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("align-items", "baseline").set("gap", "6px 16px");
        unifilar.setWidthFull();
        add(barra, unifilar);
        refrescar();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        AccesoTransformadores.verificar(event, lineaAccessService);
    }

    @Override
    protected void onAttach(AttachEvent e) {
        UI ui = e.getUI();
        ui.setPollInterval(POLL_MS);
        pollRegistration = ui.addPollListener(p -> refrescar());
    }

    @Override
    protected void onDetach(DetachEvent e) {
        if (pollRegistration != null) pollRegistration.remove();
        e.getUI().setPollInterval(POLL_MS_APP);
    }

    private void refrescar() {
        Foto f = service.leer();
        lectura.setText("Última lectura " + f.leido().format(HORA));
        try {
            unifilar.getElement().executeJs(SCRIPT + "\nwindow.UnifilarTransformadores.render(this, JSON.parse($0));",
                    JSON.writeValueAsString(datos(f)));
        } catch (Exception ex) {
            unifilar.getElement().setText("No se pudo armar el diagrama: " + ex.getMessage());
        }
    }

    /**
     * Lo que dibuja el navegador. Red = medidor principal; cada generador aporta a la planta lo que genera menos su parte de
     * lo exportado (repartido según lo que genera cada uno); consumo = generadores + red. Paso al otro transformador = lo
     * que sale por un transformador menos lo exportado por el principal. Resto de cada barra = transformador + generadores
     * - máquinas con medidor ("sin medidor" si es positivo, diferencia de medición si es negativo).
     */
    private Map<String, Object> datos(Foto f) {
        Map<String, Object> d = new LinkedHashMap<>();
        Medicion p = f.principal();
        List<Generador> configurados = generadorService.generadores();
        double generado = 0;
        boolean generadoresCompletos = true;
        for (TransformadorAhora t : f.transformadores()) {
            for (GeneradorAhora g : t.generadores()) {
                if (g.kw() == null) generadoresCompletos = false;
                else generado += g.kw();
            }
        }
        boolean conPrincipal = p.kw() != null;
        double importada = conPrincipal ? Math.max(0, p.kw()) : 0, exportada = conPrincipal ? Math.max(0, -p.kw()) : 0;
        double consumo = generado + (conPrincipal ? p.kw() : 0);
        d.put("conPrincipal", conPrincipal);
        d.put("principal", medicion(p, true));
        d.put("generado", generado);
        d.put("importada", importada);
        d.put("exportada", exportada);
        d.put("completo", conPrincipal && generadoresCompletos);
        d.put("consumo", conPrincipal ? consumo : null);
        Double pctRed = conPrincipal && consumo > 0 ? importada / consumo * 100 : null;
        d.put("pctRed", pctRed);
        if (pctRed != null && generado > 0) {
            MetaImportacionRed meta = generadorService.metaImportacionRed();
            d.put("meta", meta.zona(pctRed).name().toLowerCase());
        }

        // Balance del nodo: el principal debería ser la suma de los transformadores más sus pérdidas.
        boolean todosConMedidor = f.transformadores().stream().allMatch(t -> t.medidor().kw() != null);
        if (conPrincipal && todosConMedidor && !f.transformadores().isEmpty()) {
            double trafos = f.transformadores().stream().mapToDouble(t -> t.medidor().kw()).sum();
            double pasa = f.transformadores().stream().mapToDouble(t -> Math.abs(t.medidor().kw())).sum();
            double dif = p.kw() - trafos, ref = Math.max(Math.max(pasa, Math.abs(p.kw())), 100);
            double pct = dif / ref * 100;
            Map<String, Object> nodo = new LinkedHashMap<>();
            nodo.put("principal", p.kw());
            nodo.put("transformadores", trafos);
            nodo.put("diferencia", dif);
            nodo.put("porcentaje", pct);
            nodo.put("estado", pct >= NODO_VERDE_MIN && pct <= NODO_VERDE_MAX ? "verde"
                    : pct >= NODO_AMARILLO_MIN && pct <= NODO_AMARILLO_MAX ? "amarillo" : "rojo");
            d.put("nodo", nodo);
        }

        List<Map<String, Object>> trafos = new ArrayList<>();
        for (TransformadorAhora t : f.transformadores()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nombre", nombreTransformador(t.nombre()));
            m.put("medidor", medicion(t.medidor(), false));
            List<Map<String, Object>> gens = new ArrayList<>();
            double kwGeneradores = 0;
            for (GeneradorAhora g : t.generadores()) {
                Map<String, Object> mg = new LinkedHashMap<>();
                mg.put("nombre", g.nombre());
                mg.put("estado", g.estado().name());
                mg.put("kw", g.kw());
                mg.put("pf", g.pf());
                mg.put("color", colorGenerador(configurados, g.nombre()));
                mg.put("listo", g.listo());
                mg.put("alarmas", g.alarmas());
                mg.put("voltios", g.voltios());
                mg.put("mcbAbierto", g.mcbAbierto());
                mg.put("isla", g.isla());
                mg.put("selectorIsla", g.selectorIsla());
                mg.put("islaDesde", g.islaDesde() == null ? null : g.islaDesde().format(DateTimeFormatter.ofPattern("HH:mm")));
                if (g.estado() == EstadoGenerador.EN_MARCHA && g.kw() != null) {
                    kwGeneradores += g.kw();
                    if (conPrincipal) {
                        double aporte = generado <= 0 ? 0 : Math.max(0, g.kw() - exportada * g.kw() / generado);
                        mg.put("aporte", aporte);
                        mg.put("pct", consumo > 0 ? aporte / consumo * 100 : null);
                    }
                }
                gens.add(mg);
            }
            m.put("generadores", gens);
            m.put("color", gens.isEmpty() ? "#8A909C" : gens.get(0).get("color"));
            Double kw = t.medidor().kw();
            m.put("paso", kw != null && kw < 0 && conPrincipal ? Math.max(0, -kw - exportada) : 0.0);
            m.put("maquinas", t.kwMaquinas());
            m.put("maquinasConDato", t.maquinasConDato());
            m.put("maquinasTotal", t.maquinas());
            m.put("resto", kw == null ? null : kw + kwGeneradores - t.kwMaquinas());
            trafos.add(m);
        }
        d.put("transformadores", trafos);
        // Red de la empresa eléctrica según los controladores de los generadores: alguno ve falla = sin red (rojo); alguno
        // la ve bien = con red (verde); ninguno lo sabe = sin dato (gris).
        List<Boolean> vistas = f.transformadores().stream().flatMap(t -> t.generadores().stream())
                .map(GeneradorAhora::redPresente).filter(java.util.Objects::nonNull).toList();
        d.put("red", vistas.contains(Boolean.FALSE) ? "falla" : vistas.contains(Boolean.TRUE) ? "ok" : null);
        return d;
    }

    /** kW, factor de potencia (0 a 1 con su signo; el principal lo guarda x100), corriente y tensión entre fases medias. */
    private static Map<String, Object> medicion(Medicion m, boolean pfPorCien) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("kw", m.kw());
        d.put("pf", m.pf() == null ? null : pfPorCien ? Math.abs(m.pf()) / 100 : m.pf());
        d.put("amperes", m.amperes());
        d.put("voltios", m.voltios());
        return d;
    }

    private static String colorGenerador(List<Generador> configurados, String nombre) {
        for (int i = 0; i < configurados.size(); i++) {
            if (configurados.get(i).nombre().equals(nombre)) return COLORES_GEN[i % COLORES_GEN.length];
        }
        return COLORES_GEN[0];
    }

    /** "Trafo1" -> "Transformador 1". */
    private static String nombreTransformador(String medidor) {
        Matcher m = Pattern.compile("Trafo(\\d+)").matcher(medidor);
        return m.matches() ? "Transformador " + m.group(1) : medidor;
    }

    private static String leerRecurso(String ruta) {
        try (java.io.InputStream in = TransformadoresView.class.getResourceAsStream(ruta)) {
            return in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }
}
