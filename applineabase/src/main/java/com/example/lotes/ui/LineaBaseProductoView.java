package com.example.lotes.ui;

import com.example.base.ui.MainLayout;
import com.example.lotes.service.LineaBaseProductoService;
import com.example.lotes.service.LineaBaseProductoService.Evaluacion;
import com.example.lotes.service.LineaBaseProductoService.Mensual;
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
 * Línea base por producto: elegir línea y producto, dispersión kWh contra kg de los lotes del año base con la recta,
 * el objetivo y ± 2 sigma, tabla de validez y resumen de todos los productos de la línea (para la auditoría).
 */
@PageTitle("Línea base por producto | LineaBase")
@Route(value = "lotes/linea-base", layout = MainLayout.class)
@RolesAllowed("ADMIN")
public class LineaBaseProductoView extends VerticalLayout {

    private static final DateTimeFormatter FH = DateTimeFormatter.ofPattern("dd-MM-yy HH:mm");
    private static final String VERDE = "#3BB273", AZUL = "#3B8FF3", NARANJA = "#F29F67", GRIS = "#9aa0ae";

    private final LineaBaseProductoService servicio;
    private final ComboBox<String> linea = new ComboBox<>("Línea");
    private final ComboBox<Producto> producto = new ComboBox<>("Producto");
    private final Checkbox comparar = new Checkbox("Mostrar lotes del año de comparación", true);
    private final LineaBaseProductoPanel panel;
    private final Span origen = new Span();
    private final H2 resumenTitulo = new H2();
    private final Span resumenTotal = new Span();
    private final Grid<Producto> resumen = new Grid<>(Producto.class, false);

    public LineaBaseProductoView(LineaBaseProductoService servicio) {
        this.servicio = servicio;
        this.panel = new LineaBaseProductoPanel(servicio, false);
        setPadding(true);
        setSpacing(true);
        getStyle().set("background", "#F5F6FA");

        H2 titulo = new H2("Línea base por producto");
        titulo.getStyle().set("margin", "0");
        origen.getStyle().set("font-size", "12px").set("color", "#6b6f80");

        linea.setWidth("140px");
        linea.setItemLabelGenerator(l -> l.replace("Linea", "Línea "));
        linea.addValueChangeListener(e -> cargarProductos());
        producto.setWidth("560px");
        producto.setItemLabelGenerator(p -> (p.ev().valida() ? "✓ " : "✗ ") + p.nombre() + " (" + p.ev().total() + ")");
        producto.addValueChangeListener(e -> dibujar());
        comparar.addValueChangeListener(e -> dibujar());
        Button recargar = new Button("Volver a leer el Excel", e -> {
            LineaBaseProductoService.Datos d = servicio.recargar();
            Notification.show(d.error() != null ? d.error() : "Leídos " + d.base().size() + " lotes de " + LineaBaseProductoService.ANIO_BASE
                    + " y " + d.comparacion().size() + " de " + d.anioComparacion() + ".", 5000, Notification.Position.MIDDLE);
            iniciar();
        });
        HorizontalLayout filtros = new HorizontalLayout(linea, producto, comparar, recargar);
        filtros.setAlignItems(FlexComponent.Alignment.BASELINE);
        filtros.setWrap(true);

        resumen.addColumn(Producto::nombre).setHeader("Producto").setFlexGrow(1).setSortable(true);
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

        add(titulo, origen, LineaBaseProductoPanel.tarjeta(filtros), panel,
                LineaBaseProductoPanel.tarjeta(resumenTitulo, LineaBaseProductoPanel.nota("Todos los productos con lotes en el año base. Clic en una fila para verla arriba."), resumen, resumenTotal));
        iniciar();
    }

    private void iniciar() {
        LineaBaseProductoService.Datos d = servicio.datos();
        origen.setText(d.error() != null ? d.error() : "Año base " + LineaBaseProductoService.ANIO_BASE + ": " + d.base().size()
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
        producto.setItems(ps);
        resumen.setItems(ps);
        resumenTitulo.setText("Resumen de la " + l.replace("Linea", "Línea "));
        long ok = ps.stream().filter(p -> p.ev().valida()).count();
        resumenTotal.setText(ok + " de " + ps.size() + " productos con línea base válida.");
        resumenTotal.getStyle().set("font-size", "12px").set("color", "#6b6f80");
        producto.setValue(ps.stream().filter(p -> p.ev().valida()).findFirst().orElse(ps.isEmpty() ? null : ps.getFirst()));
    }

    private void dibujar() {
        Producto p = producto.getValue();
        if (p == null) {
            panel.mensaje("Elija un producto.");
            return;
        }
        resumen.select(p);
        panel.mostrar(p, comparar.getValue());
    }
}
