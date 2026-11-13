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

import static com.example.lotes.service.LineaBaseProductoService.num;

/**
 * Contenido de la línea base de un producto: estado, dispersión kWh contra kg con la recta y ± 2 sigma, validez, línea
 * base, línea base de la línea y lotes. Lo usan la pestaña «Línea base» de Tiempo Real (producto del lote en
 * curso o el elegido en la franja Lote) y la pantalla Línea base por producto.
 */
public class LineaBaseProductoPanel extends VerticalLayout {

    private static final DateTimeFormatter FH = DateTimeFormatter.ofPattern("dd-MM-yy HH:mm");
    private static final String VERDE = "#3BB273", AZUL = "#3B8FF3", NARANJA = "#F29F67", GRIS = "#9aa0ae";

    private final LineaBaseProductoService servicio;
    private final Div titulo = new Div();
    private final Div estado = new Div();
    private final Div grafico = new Div();
    private final Div validez = new Div();
    private final Div lineaBase = new Div();
    private final Div deLaLinea = new Div();
    private final Div lotes = new Div();
    private final HorizontalLayout fila;
    private final Div tarjetaLotes;

    public LineaBaseProductoPanel(LineaBaseProductoService servicio, boolean conTitulo) {
        this.servicio = servicio;
        setPadding(false);
        setSpacing(false);
        setWidthFull();
        titulo.setVisible(conTitulo);
        Div izq = tarjeta(new H2Chico("kWh contra kg procesados"), nota("Recta y banda calculadas solo con los lotes del año base (el año anterior). Los lotes de este año se comparan contra ella y no la modifican."), grafico);
        Div der = new Div(tarjeta(new H2Chico("Validez de la línea base"), validez), tarjeta(new H2Chico("Línea base"), lineaBase), tarjeta(deLaLinea));
        fila = new HorizontalLayout(izq, der);
        fila.setWidthFull();
        fila.setWrap(true);
        izq.getStyle().set("flex", "1.35 1 560px").set("min-width", "0");
        der.getStyle().set("flex", "1 1 380px").set("min-width", "0");
        tarjetaLotes = tarjeta(new H2Chico("Lotes"), lotes);
        Div como = tarjeta(new Html(COMO));
        add(tarjeta(titulo, estado), fila, tarjetaLotes, como);
        mensaje("Elija un producto.");
    }

    /** Solo un aviso (sin línea, sin producto o sin lotes en el año base). */
    public void mensaje(String texto) {
        titulo.removeAll();
        estado.removeAll();
        estado.add(new Html("<div style='" + caja("#eceef3", "#4a4f5c") + "'>" + texto + "</div>"));
        fila.setVisible(false);
        tarjetaLotes.setVisible(false);
    }

    /**
     * La línea base que se usa para el producto elegido (Tiempo Real): la del producto, o la de su línea con el aviso de
     * por qué no se usa la del producto.
     */
    public void mostrar(LineaBaseProductoService.Aplicada a, String productoElegido) {
        String aviso = a.dePropia() ? null
                : (productoElegido == null ? "Sin producto elegido: se muestra la línea base de la línea."
                : "<b>" + esc(productoElegido) + "</b> no tiene línea base propia (" + a.motivo() + "); se usa la de la "
                + a.lineaBase().linea().replace("Linea", "Línea ") + ".");
        mostrar(a.lineaBase(), true, aviso);
    }

    /** Línea base de un producto o de una línea; comparar = agrega los lotes de este año. */
    public void mostrar(Producto p, boolean comparar) {
        mostrar(p, comparar, null);
    }

    private void mostrar(Producto p, boolean comparar, String aviso) {
        fila.setVisible(true);
        tarjetaLotes.setVisible(true);
        titulo.removeAll();
        titulo.add(new Html("<div style='font-size:16px;font-weight:600'>" + esc(p.nombre())
                + (p.esLinea() ? "" : " · " + p.linea().replace("Linea", "Línea ")) + "</div>"));
        Evaluacion e = p.ev();
        Regresion r = e.r();
        String nl = p.linea().replace("Linea", "Línea ");
        List<LoteExcel> comp = comparar ? servicio.comparacion(p) : List.of();

        String quitados = e.atipicos().isEmpty() ? "" : " R² con todos los lotes " + num(e.r2Inicial(), 3) + " &lt; 0,75: se quitaron "
                + e.atipicos().size() + " atípicos a más de 2 sigma (en rojo); cada uno necesita su causa.";
        String periodo = " Año base: " + p.anio() + ".";
        estado.removeAll();
        if (aviso != null) estado.add(new Html("<div style='" + caja("#FBF3D5", "#7a6205") + "'>" + aviso + "</div>"));
        if (e.valida()) {
            String objetivo = r.origen()
                    ? num(r.m(), 4) + " kWh/kg × kg, por el origen (el intercepto no es significativo)"
                    : num(r.m(), 4) + " kWh/kg × kg + " + num(r.b(), 0) + " kWh";
            estado.add(new Html("<div style='" + caja("#E3F5EA", "#1d7a46") + "'><b>Línea base válida"
                    + (p.esLinea() ? " de la " + nl : " del producto") + ".</b> Objetivo = " + objetivo
                    + ". Máximo y mínimo: objetivo ± " + num(2 * r.se(), 0) + " kWh." + periodo + quitados + "</div>"));
        } else {
            estado.add(new Html("<div style='" + caja("#FCE4E6", "#a3222c") + "'><b>Sin línea base válida"
                    + (p.esLinea() ? " de la " + nl : " del producto") + ".</b> Motivo: " + e.motivo() + "."
                    + (p.esLinea() ? "" : " Se usa la línea base de la " + nl + ".") + periodo + quitados + "</div>"));
        }

        StringBuilder v = new StringBuilder("<table style='" + TABLA + "'><tr>" + th("Prueba") + th("Resultado", true) + th("Requisito") + th("Cumple") + "</tr>");
        for (LineaBaseProductoService.Prueba x : e.pruebas()) {
            v.append("<tr>").append(td(x.nombre())).append(td(x.resultado(), true)).append(td(x.requisito()))
                    .append(td(x.cumple() ? chipHtml("Sí", true) : chipHtml("No", false))).append("</tr>");
        }
        validez.removeAll();
        validez.add(new Html(v.append("</table>").toString()));

        double kgT = 0, kwhT = 0;
        for (int i = 0; i < p.lotes().size(); i++) {
            if (e.atipico(i)) continue;
            kgT += p.lotes().get(i).kg();
            kwhT += p.lotes().get(i).kwh();
        }
        List<String[]> kp = new ArrayList<>();
        if (e.valida()) {
            kp.add(new String[]{"Objetivo (m)", num(r.m(), 4) + " kWh/kg"});
            kp.add(new String[]{"Energía fija del lote (b)", r.origen() ? "0 (por el origen)" : num(r.b(), 0) + " kWh"});
            kp.add(new String[]{"Sigma", num(r.se(), 0) + " kWh"});
            kp.add(new String[]{"CV", r.cv() == null ? "—" : num(100 * r.cv(), 1) + " %"});
            kp.add(new String[]{"kWh/kg medio " + p.anio(), num(kwhT / kgT, 4)});
        } else {
            kp.add(new String[]{"Recta calculada", recta(r)});
            kp.add(new String[]{"Uso", "No se usa"});
        }
        lineaBase.removeAll();
        lineaBase.add(new Html(kpis(kp)));

        // Línea base de la línea, para comparar (solo cuando se muestra la de un producto).
        deLaLinea.removeAll();
        deLaLinea.setVisible(!p.esLinea());
        if (!p.esLinea()) {
            servicio.deLinea(p.linea()).ifPresent(l -> {
                Evaluacion le = l.ev();
                deLaLinea.add(new H2Chico("Línea base de la " + nl),
                        nota("Todos los lotes de la línea en " + l.anio() + " (" + le.total() + "). Se usa para los productos sin línea base propia."),
                        new Html(kpis(List.of(new String[]{"Recta", recta(le.r())},
                                new String[]{"R²", conF(le) ? num(le.r().r2(), 3) : "—"},
                                new String[]{"p (F)", conF(le) ? LineaBaseProductoService.p(le.r().p()) : "—"},
                                new String[]{"Estado", le.valida() ? "Válida" : "No válida: " + le.motivo()}))));
            });
        }

        // Tabla de lotes
        List<LoteExcel> todos = new ArrayList<>(p.lotes());
        todos.addAll(comp);
        todos.sort((a, b) -> a.inicio().compareTo(b.inicio()));
        StringBuilder t = new StringBuilder("<div style='max-height:420px;overflow:auto'><table style='" + TABLA + "'><tr>" + th("Año") + th("Inicio") + th("Fin")
                + (p.esLinea() ? th("Producto") : "")
                + th("Horas", true) + th("kg", true) + th("kWh", true) + th("kWh/kg", true) + th("Esperado", true) + th("Diferencia", true) + th("Estado") + "</tr>");
        for (LoteExcel x : todos) {
            boolean base = p.lotes().contains(x);
            boolean atip = base && e.atipico(p.lotes().indexOf(x));
            String esp = "—", dif = "—", est;
            if (e.valida()) {
                double es = e.objetivo(x.kg()), d = x.kwh() - es;
                esp = num(es, 0);
                dif = (d > 0 ? "+" : "") + num(d, 0);
                if (atip) est = chipHtml("Atípico (quitado)", false);
                else if (base && fueraDe2Sigma(e, x)) est = "<span style='" + CHIP + "background:#E3EEFD;color:#1f5fae;box-shadow:inset 0 0 0 2px #E0B50F'>Línea base, fuera de 2 sigma</span>";
                else if (base) est = chipHtml("Línea base", null);
                else if (d > 2 * r.se()) est = chipHtml("Sobre el máximo", false);
                else if (d < -2 * r.se()) est = "<span style='" + CHIP + "background:#FBF3D5;color:#7a6205'>Bajo el mínimo</span>";
                else est = chipHtml("Dentro", true);
            } else {
                est = atip ? chipHtml("Atípico (quitado)", false) : base ? chipHtml("Línea base", null) : "<span style='" + CHIP + "background:#eceef3;color:#4a4f5c'>Sin línea base válida</span>";
            }
            t.append("<tr>").append(td(String.valueOf(x.anio()))).append(td(x.inicio().format(FH))).append(td(x.fin().format(FH)))
                    .append(p.esLinea() ? td(esc(x.item())) : "")
                    .append(td(num(x.horas(), 1), true)).append(td(num(x.kg(), 0), true)).append(td(num(x.kwh(), 0), true))
                    .append(td(num(x.kwh() / x.kg(), 4), true)).append(td(esp, true)).append(td(dif, true)).append(td(est)).append("</tr>");
        }
        lotes.removeAll();
        lotes.add(new Html(t.append("</table></div>").toString()));

        grafico.removeAll();
        java.util.Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("valida", e.valida());
        d.put("r", r == null ? null : java.util.Map.of("m", r.m(), "b", r.b(), "sig", r.se() != null ? 2 * r.se() : 0.0));
        d.put("xm", todos.stream().mapToDouble(LoteExcel::kg).max().orElse(1) * 1.1);
        d.put("texto", e.valida() ? recta(r) + " · R² " + num(r.r2(), 3) : null);
        List<java.util.Map<String, Object>> pts = new ArrayList<>();
        for (LoteExcel x : todos) {
            boolean b0 = p.lotes().contains(x);
            boolean atip = b0 && e.atipico(p.lotes().indexOf(x));
            // Tres marcas: azul = línea base; azul con borde amarillo = línea base fuera de ± 2 sigma que se mantiene (R² ya
            // cumplía); rojo = atípico quitado. Naranja = lote de este año que se compara.
            boolean fuera = b0 && !atip && fueraDe2Sigma(e, x);
            String nota = atip ? " · atípico quitado" : fuera ? " · fuera de 2 sigma, se mantiene" : "";
            pts.add(java.util.Map.of("x", x.kg(), "y", x.kwh(), "c", !b0 ? "comp" : atip ? "atip" : fuera ? "fuera" : "base",
                    "t", x.anio() + " · " + x.item() + "\n" + x.inicio().format(FH) + " a " + x.fin().format(FH) + "\n" + num(x.kg(), 0) + " kg · "
                            + num(x.kwh(), 0) + " kWh · " + num(x.kwh() / x.kg(), 4) + " kWh/kg" + nota));
        }
        d.put("pts", pts);
        d.put("ley", (e.valida() ? "<span style='color:" + VERDE + "'>━</span> objetivo &nbsp; <span style='color:" + GRIS + "'>┅</span> máximo y mínimo (± 2 sigma) &nbsp; "
                : r != null ? "<span style='color:" + GRIS + "'>┅</span> recta sin validez &nbsp; " : "")
                + "<span style='color:" + AZUL + "'>●</span> línea base " + p.anio() + " (" + e.n() + ")"
                + (fueraMantenidos(p) == 0 ? "" : " &nbsp; <span style='color:" + AZUL + ";-webkit-text-stroke:1px #E0B50F'>●</span> fuera de 2 sigma, se mantiene (" + fueraMantenidos(p) + ")")
                + (e.atipicos().isEmpty() ? "" : " &nbsp; <span style='color:#E8505B'>●</span> atípico quitado (" + e.atipicos().size() + ")")
                + (comp.isEmpty() ? "" : " &nbsp; <span style='color:" + NARANJA + "'>●</span> " + comp.getFirst().anio() + ": se compara (" + comp.size() + ")"));
        try {
            grafico.getElement().executeJs(SCRIPT + "\nwindow.DispersionLB.render(this, JSON.parse($0));", JSON.writeValueAsString(d));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            mensaje("No se pudo dibujar la dispersión: " + ex.getMessage());
        }
    }

    /** Lote fuera de ± 2 sigma de la recta (solo con línea base válida, que tiene banda). */
    private static boolean fueraDe2Sigma(Evaluacion e, LoteExcel x) {
        return e.valida() && e.r().se() != null && Math.abs(x.kwh() - e.objetivo(x.kg())) > 2 * e.r().se();
    }

    /** Lotes de la línea base fuera de ± 2 sigma que se mantienen (no son atípicos quitados). */
    private static long fueraMantenidos(Producto p) {
        long n = 0;
        for (int i = 0; i < p.lotes().size(); i++) if (!p.ev().atipico(i) && fueraDe2Sigma(p.ev(), p.lotes().get(i))) n++;
        return n;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------- Helpers de presentación ----------------

    private static final String TABLA = "border-collapse:collapse;width:100%;font-size:12.5px";
    private static final String CHIP = "display:inline-block;padding:1px 7px;border-radius:10px;font-size:11px;white-space:nowrap;";

    static boolean conF(Evaluacion e) {
        return e.r() != null && e.r().p() != null;
    }

    private static String recta(Regresion r) {
        if (r == null) return "—";
        if (r.origen()) return "kWh = " + num(r.m(), 4) + " × kg (por el origen)";
        return "kWh = " + num(r.m(), 4) + " × kg " + (r.b() < 0 ? "− " : "+ ") + num(Math.abs(r.b()), 0);
    }

    private static String caja(String fondo, String color) {
        return "border-radius:10px;padding:10px 12px;margin-top:10px;font-size:14px;background:" + fondo + ";color:" + color;
    }

    private static String th(String t) { return th(t, false); }

    private static String th(String t, boolean num) {
        return "<th style='border-bottom:1px solid #E4E6EC;padding:5px 7px;background:#fafbfd;position:sticky;top:0;text-align:" + (num ? "right" : "left") + "'>" + t + "</th>";
    }

    private static String td(String t) { return td(t, false); }

    private static String td(String t, boolean num) {
        return "<td style='border-bottom:1px solid #E4E6EC;padding:5px 7px;" + (num ? "text-align:right;white-space:nowrap" : "") + "'>" + t + "</td>";
    }

    /** ok: true verde, false rojo, null azul. */
    private static String chipHtml(String t, Boolean ok) {
        String c = ok == null ? "background:#E3EEFD;color:#1f5fae" : ok ? "background:#E3F5EA;color:#1d7a46" : "background:#FCE4E6;color:#a3222c";
        return "<span style='" + CHIP + c + "'>" + t + "</span>";
    }

    static Span chip(boolean ok) {
        Span s = new Span(ok ? "Con línea base" : "Sin referencia válida");
        s.getStyle().set("padding", "1px 7px").set("border-radius", "10px").set("font-size", "11px")
                .set("background", ok ? "#E3F5EA" : "#FCE4E6").set("color", ok ? "#1d7a46" : "#a3222c");
        return s;
    }

    private static String kpis(List<String[]> kp) {
        StringBuilder s = new StringBuilder("<div style='display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px'>");
        for (String[] k : kp) {
            s.append("<div style='border:1px solid #E4E6EC;border-radius:10px;padding:8px 10px'><div style='font-size:")
                    .append(k[1].length() > 18 ? "14" : "19").append("px;font-weight:600'>").append(k[1])
                    .append("</div><div style='font-size:12px;color:#6b6f80'>").append(k[0]).append("</div></div>");
        }
        return s.append("</div>").toString();
    }

    static Span nota(String t) {
        Span s = new Span(t);
        s.getStyle().set("font-size", "12px").set("color", "#6b6f80").set("display", "block");
        return s;
    }

    static Div tarjeta(com.vaadin.flow.component.Component... c) {
        Div d = new Div(c);
        d.getStyle().set("background", "#fff").set("border", "1px solid #E4E6EC").set("border-radius", "12px")
                .set("padding", "14px").set("margin-bottom", "12px").set("box-sizing", "border-box").set("width", "100%");
        return d;
    }

    /** Subtítulo de tarjeta. */
    static final class H2Chico extends H2 {
        H2Chico(String t) {
            super(t);
            getStyle().set("font-size", "16px").set("margin", "0 0 8px");
        }
    }

    /** amCharts: ayudas comunes de SGEn + la dispersión (lineabase/*.js). */
    static final String SCRIPT = recurso("/lineabase/sgen-graficas.js") + "\n" + recurso("/lineabase/dispersion-lb.js");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    static String recurso(String ruta) {
        try (java.io.InputStream in = LineaBaseProductoPanel.class.getResourceAsStream(ruta)) {
            return in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            return "";
        }
    }

    private static final String COMO = "<div style='font-size:12px;color:#6b6f80'><b>Cómo se calcula.</b> Regresión lineal kWh = m × kg + b con los "
            + "lotes del año base, el año calendario anterior completo (ISO 50006); los lotes de este año se comparan contra ella y nunca la "
            + "modifican. Por producto: los lotes del producto en su línea. Por línea: todos los lotes de la línea. Se usa la del producto si es "
            + "válida; si no, la de su línea. Con R² ≥ 0,75 no se quita ningún lote; si R² &lt; 0,75 se quita el lote más alejado de la recta si "
            + "está a más de 2 sigma y se vuelve a ajustar, hasta llegar a 0,75 o quedar 3 lotes; cada atípico quitado debe tener su causa. Es "
            + "válida si pasa las cinco pruebas: al menos 3 lotes, R² ≥ 0,75, prueba F con p &lt; 0,05 (la relación kWh–kg no es casualidad), "
            + "pendiente m &gt; 0 e intercepto b ≥ 0 (la energía fija del lote no puede ser negativa). Si solo falla el intercepto y no es "
            + "distinto de cero (prueba t, p &gt; 0,05), se ajusta por el origen (kWh = m × kg) y se vuelven a pedir las pruebas. Objetivo = "
            + "m × kg + b; máximo y mínimo = objetivo ± 2 sigma (sigma = error estándar de la regresión). Lotes de la hoja MEDICION_XTR de los "
            + "Excel de medición (kg = total de material procesado de PRODUCCIÓN).</div>";
}
