package com.example.lotes.ui;

import com.example.base.ui.MainLayout;
import com.example.lotes.service.LineaBaseProductoService;
import com.example.lotes.service.LineaBaseProductoService.Evaluacion;
import com.example.lotes.service.LineaBaseProductoService.Producto;
import com.example.lotes.service.LotesExcelReader.LoteExcel;
import com.example.lotes.service.Regresion;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.example.lotes.service.LineaBaseProductoService.num;

/**
 * SGEn (solo ADMIN): elegir línea y «Toda la línea» o un producto; pestaña «Línea base» (dispersión kWh contra kg del año
 * base con la recta y ± 2 sigma, validez, lotes y resumen de la línea, para la auditoría) y pestaña «Índice de consumo»
 * (IC contra kg del lote, CUSUM de la pendiente y tamaño crítico por Kneedle; lo dibuja lineabase/indice-consumo.js, copia
 * de la maqueta aprobada el 2026-11-13).
 */
@PageTitle("SGEn | LineaBase")
@Route(value = "sgen", layout = MainLayout.class)
@RolesAllowed("ADMIN")
public class LineaBaseProductoView extends VerticalLayout {

    private static final DateTimeFormatter FH = DateTimeFormatter.ofPattern("dd-MM-yy HH:mm");
    private static final String VERDE = "#3BB273", AZUL = "#3B8FF3", NARANJA = "#F29F67", GRIS = "#9aa0ae";

    private final LineaBaseProductoService servicio;
    private final ComboBox<String> linea = new ComboBox<>("Línea");
    private final ComboBox<Producto> producto = new ComboBox<>("Producto");
    private final Checkbox comparar = new Checkbox("Mostrar lotes que se comparan", true);
    private final LineaBaseProductoPanel panel;
    private final Span origen = new Span();
    private final H2 resumenTitulo = new H2();
    private final Span resumenTotal = new Span();
    private final Grid<Producto> resumen = new Grid<>(Producto.class, false);
    private static final String SCRIPT_IC = leerRecurso("/lineabase/sgen-graficas.js") + "\n" + leerRecurso("/lineabase/indice-consumo.js");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final com.vaadin.flow.component.tabs.TabSheet pestanas = new com.vaadin.flow.component.tabs.TabSheet();
    private final Div indice = new Div();
    private com.vaadin.flow.component.tabs.Tab tabIndice;

    public LineaBaseProductoView(LineaBaseProductoService servicio) {
        this.servicio = servicio;
        this.panel = new LineaBaseProductoPanel(servicio, false);
        setPadding(true);
        setSpacing(true);
        getStyle().set("background", "#F5F6FA");

        H2 titulo = new H2("SGEn");
        titulo.getStyle().set("margin", "0");
        origen.getStyle().set("font-size", "12px").set("color", "#6b6f80");

        linea.setWidth("140px");
        linea.setItemLabelGenerator(l -> l.replace("Linea", "Línea "));
        linea.addValueChangeListener(e -> cargarProductos());
        producto.setWidth("560px");
        producto.setItemLabelGenerator(p -> (p.ev().valida() ? "✓ " : "✗ ") + (p.esLinea() ? "Toda la línea" : p.nombre()) + " (" + p.ev().total() + ")");
        producto.addValueChangeListener(e -> dibujar());
        comparar.addValueChangeListener(e -> dibujar());
        Button recargar = new Button("Volver a leer el Excel", e -> {
            LineaBaseProductoService.Datos d = servicio.recargar();
            Notification.show(d.error() != null ? d.error() : "Leídos " + d.base().size() + " lotes de " + d.anioBase()
                    + " y " + d.comparacion().size() + " de " + d.anioComparacion() + ".", 5000, Notification.Position.MIDDLE);
            iniciar();
        });
        HorizontalLayout filtros = new HorizontalLayout(linea, producto, comparar, recargar);
        filtros.setAlignItems(FlexComponent.Alignment.BASELINE);
        filtros.setWrap(true);

        resumen.addColumn(p -> p.esLinea() ? "Toda la línea" : p.nombre()).setHeader("Producto").setFlexGrow(1).setSortable(true);
        resumen.addColumn(p -> p.ev().atipicos().isEmpty() ? String.valueOf(p.ev().n()) : p.ev().n() + " de " + p.ev().total()).setHeader("Lotes").setAutoWidth(true).setFlexGrow(0);
        resumen.addColumn(p -> LineaBaseProductoPanel.conF(p.ev()) ? num(p.ev().r().r2(), 3) : "—").setHeader("R²").setAutoWidth(true).setFlexGrow(0);
        resumen.addColumn(p -> LineaBaseProductoPanel.conF(p.ev()) ? LineaBaseProductoService.p(p.ev().r().p()) : "—").setHeader("p (F)").setAutoWidth(true).setFlexGrow(0);
        resumen.addColumn(p -> p.ev().r() == null ? "—" : num(p.ev().r().m(), 4)).setHeader("m kWh/kg").setAutoWidth(true).setFlexGrow(0);
        resumen.addColumn(p -> p.ev().r() == null ? "—" : num(p.ev().r().b(), 0)).setHeader("b kWh").setAutoWidth(true).setFlexGrow(0);
        resumen.addComponentColumn(p -> LineaBaseProductoPanel.chip(p.ev().valida())).setHeader("Estado").setAutoWidth(true).setFlexGrow(0);
        resumen.addColumn(p -> p.ev().motivo()).setHeader("Motivo").setFlexGrow(1);
        resumen.setHeight("380px");
        resumen.addItemClickListener(e -> producto.setValue(e.getItem()));
        resumenTitulo.getStyle().set("font-size", "16px").set("margin", "0");

        VerticalLayout lb = new VerticalLayout(panel,
                LineaBaseProductoPanel.tarjeta(resumenTitulo, LineaBaseProductoPanel.nota("La línea y todos los productos con lotes en el año base. Clic en una fila para verla arriba."), resumen, resumenTotal));
        lb.setPadding(false);
        lb.setSpacing(false);
        indice.setWidthFull();
        pestanas.setWidthFull();
        pestanas.add("Línea base", lb);
        tabIndice = pestanas.add("Índice de consumo", indice);
        // El índice se dibuja con la pestaña visible (el auto ajuste mide el alto disponible).
        pestanas.addSelectedChangeListener(e -> { if (e.getSelectedTab() == tabIndice) dibujarIndice(); });
        add(titulo, origen, LineaBaseProductoPanel.tarjeta(filtros), pestanas);
        iniciar();
    }

    private void iniciar() {
        LineaBaseProductoService.Datos d = servicio.datos();
        origen.setText(d.error() != null ? d.error() : "Año base " + d.anioBase() + ": " + d.base().size()
                + " lotes; año de comparación " + d.anioComparacion() + ": " + d.comparacion().size() + " lotes (" + String.join(", ", d.archivos()) + ").");
        String antes = linea.getValue();
        List<String> ls = servicio.lineas();
        linea.setItems(ls);
        if (!ls.isEmpty()) linea.setValue(antes != null && ls.contains(antes) ? antes : ls.getFirst());
    }

    private void cargarProductos() {
        String l = linea.getValue();
        if (l == null) return;
        List<Producto> ps = servicio.productos(l);
        java.util.Optional<Producto> pl = servicio.deLinea(l);
        List<Producto> items = new java.util.ArrayList<>();
        pl.ifPresent(items::add);
        items.addAll(ps);
        producto.setItems(items);
        resumen.setItems(items);
        resumenTitulo.setText("Resumen de la " + l.replace("Linea", "Línea "));
        long ok = ps.stream().filter(p -> p.ev().valida()).count();
        resumenTotal.setText(ok + " de " + ps.size() + " productos con línea base válida.");
        resumenTotal.getStyle().set("font-size", "12px").set("color", "#6b6f80");
        producto.setValue(items.isEmpty() ? null : items.getFirst());
    }

    private void dibujar() {
        Producto p = producto.getValue();
        if (p == null) {
            panel.mensaje("Elija un producto.");
            return;
        }
        resumen.select(p);
        panel.mostrar(p, comparar.getValue());
        if (pestanas.getSelectedTab() == tabIndice) dibujarIndice();
    }

    /**
     * Pestaña «Índice de consumo»: datos de la línea base elegida para indice-consumo.js. b = 0 si el modelo es
     * proporcional; pb y bcon = p de la prueba t y b del modelo con intercepto (con los mismos lotes, sin los atípicos).
     */
    private void dibujarIndice() {
        Producto p = producto.getValue();
        if (p == null) return;
        java.util.Map<String, Object> d = new java.util.LinkedHashMap<>();
        Evaluacion e = p.ev();
        Regresion r = e.r();
        if (r != null && r.se() != null) {
            List<double[]> pts = new ArrayList<>();
            List<Integer> fuera = new ArrayList<>(e.atipicos());
            for (int i = 0; i < p.lotes().size(); i++) if (!e.atipico(i)) pts.add(new double[]{p.lotes().get(i).kg(), p.lotes().get(i).kwh()});
            Regresion conB = r.origen() ? Regresion.de(pts) : r;
            java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
            ev.put("m", r.m());
            ev.put("b", r.origen() ? 0.0 : r.b());
            ev.put("pb", conB == null ? null : conB.pB());
            ev.put("bcon", conB == null ? null : conB.b());
            ev.put("valida", e.valida());
            ev.put("r2", r.r2());
            ev.put("fuera", fuera);
            d.put("ev", ev);
        } else {
            d.put("ev", null);
        }
        d.put("base", filas(p.lotes()));
        d.put("comp", filas(servicio.comparacion(p)));
        d.put("anioBase", p.anio());
        d.put("anioComp", servicio.datos().anioComparacion());
        try {
            indice.getElement().executeJs(SCRIPT_IC + "\nwindow.IndiceConsumo.render(this, JSON.parse($0));", JSON.writeValueAsString(d));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            Notification.show("No se pudo dibujar el índice de consumo: " + ex.getMessage(), 5000, Notification.Position.MIDDLE);
        }
    }

    private static List<List<Object>> filas(List<LoteExcel> l) {
        List<List<Object>> out = new ArrayList<>();
        for (LoteExcel x : l) out.add(List.of(x.kg(), x.kwh(), x.item(), x.inicio().format(FH)));
        return out;
    }

    private static String leerRecurso(String ruta) {
        try (java.io.InputStream in = LineaBaseProductoView.class.getResourceAsStream(ruta)) {
            return in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            return "";
        }
    }
}
