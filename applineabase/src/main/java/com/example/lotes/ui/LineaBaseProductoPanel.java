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
 * Contenido de la línea base de un producto: estado, dispersión kWh contra kg con la recta y ± 2 sigma, validez, línea
 * base, línea base mensual de la línea y lotes. Lo usan la pestaña «Línea base» de Tiempo Real (producto del lote en
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
    private final Div mensual = new Div();
    private final Div lotes = new Div();
    private final HorizontalLayout fila;
    private final Div tarjetaLotes;

    public LineaBaseProductoPanel(LineaBaseProductoService servicio, boolean conTitulo) {
        this.servicio = servicio;
        setPadding(false);
        setSpacing(false);
        setWidthFull();
        titulo.setVisible(conTitulo);
        Div izq = tarjeta(new H2Chico("kWh contra kg procesados"), nota("Recta y banda calculadas solo con los lotes del período base. Los lotes posteriores se comparan contra ella y no la modifican."), grafico);
        Div der = new Div(tarjeta(new H2Chico("Validez de la línea base"), validez), tarjeta(new H2Chico("Línea base"), lineaBase), tarjeta(mensual));
        fila = new HorizontalLayout(izq, der);
        fila.setWidthFull();
        fila.setWrap(true);
        izq.getStyle().set("flex", "1.35 1 560px").set("min-width", "0");
        der.getStyle().set("flex", "1 1 380px").set("min-width", "0");
        tarjetaLotes = tarjeta(new H2Chico("Lotes del producto"), lotes);
        Div como = tarjeta(new Html(COMO));
        add(tarjeta(titulo, estado), fila, tarjetaLotes, como);
        mensaje("Elija un producto.");
    }

    /** Solo un aviso (sin producto, «Sin ítem» o producto sin lotes en el período base). */
    public void mensaje(String texto) {
        titulo.removeAll();
        estado.removeAll();
        estado.add(new Html("<div style='" + caja("#eceef3", "#4a4f5c") + "'>" + texto + "</div>"));
        fila.setVisible(false);
        tarjetaLotes.setVisible(false);
    }

    /** Línea base del producto; comparar = agrega los lotes del año de comparación. */
    public void mostrar(Producto p, boolean comparar) {
        fila.setVisible(true);
        tarjetaLotes.setVisible(true);
        titulo.removeAll();
        titulo.add(new Html("<div style='font-size:16px;font-weight:600'>" + p.nombre() + " · " + p.linea().replace("Linea", "Línea ") + "</div>"));
        Evaluacion e = p.ev();
        Regresion r = e.r();
        String nl = p.linea().replace("Linea", "Línea ");
        List<LoteExcel> comp = comparar ? servicio.comparacion(p) : List.of();

        String quitados = e.atipicos().isEmpty() ? "" : " R² con todos los lotes " + num(e.r2Inicial(), 3) + " &lt; 0,75: se quitaron "
                + e.atipicos().size() + " atípicos a más de 2 sigma (en rojo); cada uno necesita su causa.";
        String periodo = " Período base: " + p.periodo() + (p.ampliado() ? " (ampliado porque con 2025 no alcanzaba; se comparan los lotes desde octubre de 2026)" : "") + ".";
        String objetivo = r.origen()
                ? num(r.m(), 4) + " kWh/kg × kg, por el origen (el intercepto no es significativo)"
                : num(r.m(), 4) + " kWh/kg × kg + " + num(r.b(), 0) + " kWh";
        estado.removeAll();
        estado.add(new Html(e.valida()
                ? "<div style='" + caja("#E3F5EA", "#1d7a46") + "'><b>Línea base válida.</b> Objetivo = " + objetivo
                + ". Máximo y mínimo: objetivo ± " + num(2 * r.se(), 0) + " kWh." + periodo + quitados + "</div>"
                : "<div style='" + caja("#FCE4E6", "#a3222c") + "'><b>Sin referencia válida.</b> Motivo: " + e.motivo()
                + ". Los lotes de este producto se controlan con la línea base mensual " + LineaBaseProductoService.ANIO_BASE + " de la " + nl + "." + quitados + "</div>"));

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
            kp.add(new String[]{"kWh/kg medio del período base", num(kwhT / kgT, 4)});
        } else {
            kp.add(new String[]{"Recta calculada", recta(r)});
            kp.add(new String[]{"Uso", "No se usa"});
        }
        lineaBase.removeAll();
        lineaBase.add(new Html(kpis(kp)));

        Mensual mm = servicio.mensual(p.linea());
        Evaluacion me = mm.ev();
        mensual.removeAll();
        mensual.add(new H2Chico("Línea base mensual " + LineaBaseProductoService.ANIO_BASE + " de la " + nl),
                nota("Referencia para los productos sin línea base propia. Suma de los lotes de cada mes (" + mm.meses().size() + " meses)."),
                new Html(kpis(List.of(new String[]{"Recta", recta(me.r())},
                        new String[]{"R²", conF(me) ? num(me.r().r2(), 3) : "—"},
                        new String[]{"p (F)", conF(me) ? LineaBaseProductoService.p(me.r().p()) : "—"},
                        new String[]{"Estado", me.valida() ? "Válida" : "No válida: " + me.motivo()}))));

        // Tabla de lotes
        List<LoteExcel> todos = new ArrayList<>(p.lotes());
        todos.addAll(comp);
        todos.sort((a, b) -> a.inicio().compareTo(b.inicio()));
        StringBuilder t = new StringBuilder("<div style='max-height:420px;overflow:auto'><table style='" + TABLA + "'><tr>" + th("Año") + th("Inicio") + th("Fin")
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
                else if (base) est = chipHtml("Línea base", null);
                else if (d > 2 * r.se()) est = chipHtml("Sobre el máximo", false);
                else if (d < -2 * r.se()) est = "<span style='" + CHIP + "background:#FBF3D5;color:#7a6205'>Bajo el mínimo</span>";
                else est = chipHtml("Dentro", true);
            } else {
                est = atip ? chipHtml("Atípico (quitado)", false) : base ? chipHtml("Línea base", null) : "<span style='" + CHIP + "background:#eceef3;color:#4a4f5c'>Control mensual de la línea</span>";
            }
            t.append("<tr>").append(td(String.valueOf(x.anio()))).append(td(x.inicio().format(FH))).append(td(x.fin().format(FH)))
                    .append(td(num(x.horas(), 1), true)).append(td(num(x.kg(), 0), true)).append(td(num(x.kwh(), 0), true))
                    .append(td(num(x.kwh() / x.kg(), 4), true)).append(td(esp, true)).append(td(dif, true)).append(td(est)).append("</tr>");
        }
        lotes.removeAll();
        lotes.add(new Html(t.append("</table></div>").toString()));

        grafico.removeAll();
        getElement().executeJs(AJUSTE, getElement());
        grafico.add(new Html("<div>" + svg(p, comp) + "<div style='font-size:12px;color:#6b6f80'><span style='color:" + AZUL + "'>●</span> "
                + "línea base " + p.periodo() + " (" + e.n() + ")"
                + (e.atipicos().isEmpty() ? "" : " &nbsp; <span style='color:#E8505B'>●</span> atípico quitado (" + e.atipicos().size() + ")")
                + (comp.isEmpty() ? "" : " &nbsp; <span style='color:" + NARANJA + "'>●</span> " + comp.getFirst().anio() + " en adelante: se compara (" + comp.size() + ")")
                + "</div></div>"));
    }

    /** Dispersión kWh contra kg con la recta y ± 2 sigma (SVG). */
    private static String svg(Producto p, List<LoteExcel> comp) {
        Evaluacion e = p.ev();
        Regresion r = e.r();
        List<LoteExcel> todos = new ArrayList<>(p.lotes());
        todos.addAll(comp);
        double W = 640, H = 420, ml = 62, mr = 96, mt = 14, mb = 42;
        double xm = todos.stream().mapToDouble(LoteExcel::kg).max().orElse(1) * 1.1;
        double ym = todos.stream().mapToDouble(LoteExcel::kwh).max().orElse(1);
        double sig = r != null && r.se() != null ? 2 * r.se() : 0;
        if (r != null) ym = Math.max(ym, r.m() * xm + r.b() + sig);
        ym *= 1.08;
        double ymin = Math.min(0, r != null ? r.b() - sig : 0);
        for (LoteExcel x : todos) ymin = Math.min(ymin, x.kwh());
        final double fxm = xm, fym = ym, fymin = ymin;
        java.util.function.DoubleUnaryOperator X = v -> ml + (W - ml - mr) * v / fxm;
        java.util.function.DoubleUnaryOperator Y = v -> H - mb - (H - mt - mb) * (v - fymin) / (fym - fymin);
        StringBuilder s = new StringBuilder("<svg viewBox='0 0 640 420' width='100%' class='lb-disp' preserveAspectRatio='xMidYMid meet' style='display:block;font-family:Segoe UI,Roboto,Arial,sans-serif'>");
        s.append("<defs><clipPath id='clLb'><rect x='").append(ml).append("' y='").append(mt).append("' width='").append(W - ml - mr)
                .append("' height='").append(H - mt - mb).append("'/></clipPath></defs>");
        for (int i = 0; i <= 5; i++) {
            double v = ymin + (ym - ymin) * i / 5, u = xm * i / 5;
            s.append(String.format(Locale.US, "<line x1='%.1f' x2='%.1f' y1='%.1f' y2='%.1f' stroke='#eef0f4'/>", ml, W - mr, Y.applyAsDouble(v), Y.applyAsDouble(v)));
            s.append(String.format(Locale.US, "<text x='%.1f' y='%.1f' font-size='11' text-anchor='end' fill='#6b6f80'>%s</text>", ml - 6, Y.applyAsDouble(v) + 4, num(v, 0)));
            s.append(String.format(Locale.US, "<text x='%.1f' y='%.1f' font-size='11' text-anchor='middle' fill='#6b6f80'>%s</text>", X.applyAsDouble(u), H - mb + 16, num(u, 0)));
        }
        if (ymin < 0) s.append(String.format(Locale.US, "<line x1='%.1f' x2='%.1f' y1='%.1f' y2='%.1f' stroke='#c9ccd6'/>", ml, W - mr, Y.applyAsDouble(0), Y.applyAsDouble(0)));
        s.append(String.format(Locale.US, "<text x='%.1f' y='%.1f' font-size='12' text-anchor='middle' fill='#6b6f80'>kg procesados del lote</text>", (W + ml - mr) / 2, H - 6));
        s.append(String.format(Locale.US, "<text x='14' y='%.1f' font-size='12' fill='#6b6f80' transform='rotate(-90 14 %.1f)' text-anchor='middle'>kWh del lote</text>", H / 2, H / 2));
        if (r != null) {
            String col = e.valida() ? VERDE : GRIS;
            if (e.valida()) {
                s.append(recta(X, Y, r, xm, sig, GRIS, 1.2, true, "MÁXIMO", W - mr));
                s.append(recta(X, Y, r, xm, -sig, GRIS, 1.2, true, "MÍNIMO", W - mr));
            }
            s.append(recta(X, Y, r, xm, 0, col, 2, !e.valida(), e.valida() ? "OBJETIVO" : "sin validez", W - mr));
            if (e.valida()) {
                s.append(String.format(Locale.US, "<text x='%.1f' y='%.1f' font-size='12' fill='#1E1E2C'>%s · R² %s</text>",
                        ml + 8, mt + 14, recta(r), num(r.r2(), 3)));
            }
            if (r.b() < 0) {
                s.append(String.format(Locale.US, "<circle cx='%.1f' cy='%.1f' r='4' fill='#E8505B'/><text x='%.1f' y='%.1f' font-size='11' fill='#a3222c'>b = %s kWh</text>",
                        X.applyAsDouble(0), Y.applyAsDouble(r.b()), X.applyAsDouble(0) + 8, Y.applyAsDouble(r.b()) - 6, num(r.b(), 0)));
            }
        }
        for (LoteExcel x : todos) {
            boolean b0 = p.lotes().contains(x);
            String c = !b0 ? NARANJA : e.atipico(p.lotes().indexOf(x)) ? "#E8505B" : AZUL;
            s.append(String.format(Locale.US, "<circle cx='%.1f' cy='%.1f' r='5.5' fill='%s' stroke='#fff'><title>%d · %s a %s · %s kg · %s kWh · %s kWh/kg</title></circle>",
                    X.applyAsDouble(x.kg()), Y.applyAsDouble(x.kwh()), c, x.anio(), x.inicio().format(FH), x.fin().format(FH),
                    num(x.kg(), 0), num(x.kwh(), 0), num(x.kwh() / x.kg(), 4)));
        }
        return s.append("</svg>").toString();
    }

    private static String recta(java.util.function.DoubleUnaryOperator X, java.util.function.DoubleUnaryOperator Y, Regresion r, double xm,
                                double off, String color, double ancho, boolean guiones, String texto, double xTexto) {
        return String.format(Locale.US, "<line clip-path='url(#clLb)' x1='%.1f' y1='%.1f' x2='%.1f' y2='%.1f' stroke='%s' stroke-width='%.1f'%s/>"
                        + "<text x='%.1f' y='%.1f' font-size='11' fill='%s'>%s</text>",
                X.applyAsDouble(0), Y.applyAsDouble(r.b() + off), X.applyAsDouble(xm), Y.applyAsDouble(r.m() * xm + r.b() + off), color, ancho,
                guiones ? " stroke-dasharray='6 5'" : "", xTexto + 5, Y.applyAsDouble(r.m() * xm + r.b() + off) + 4, color, texto);
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

    /*
     * Auto ajuste, mismo criterio que el unifilar de Transformadores y el tablero de Generadores: la dispersión toma el alto
     * que queda desde su borde de arriba hasta el de abajo del área visible (la ventana o el panel con barra de
     * desplazamiento que la contiene), sin la leyenda; mínimo 380 px. Se vuelve a ajustar al cambiar el tamaño de la
     * ventana, la escala de Windows o el zoom del navegador.
     */
    private static final String AJUSTE = """
            const el = $0;
            const ajustar = () => {
              const svg = el.querySelector('svg.lb-disp');
              if (!svg || !el.isConnected) return;
              svg.style.height = '';
              let desplazado = 0, abajo = window.innerHeight;
              for (let n = svg; n; n = n.assignedSlot || n.parentNode || n.host) {
                if (!(n instanceof Element)) continue;
                const zn = n.currentCSSZoom || 1;
                desplazado += (n.scrollTop || 0) * zn;
                const st = getComputedStyle(n);
                if (n !== svg && n !== document.documentElement && n !== document.body && /auto|scroll/.test(st.overflowY)) {
                  abajo = Math.min(abajo, n.getBoundingClientRect().top + (n.clientTop + n.clientHeight - parseFloat(st.paddingBottom || 0)) * zn);
                }
              }
              const r = svg.getBoundingClientRect();
              const ley = svg.nextElementSibling ? svg.nextElementSibling.getBoundingClientRect().height : 0;
              const disponible = abajo - (r.top + desplazado) - ley - 30;
              const zoom = svg.currentCSSZoom || 1;
              svg.style.height = Math.max(380, disponible / zoom) + 'px';
            };
            if (!el.lbAjuste) {
              el.lbAjuste = true;
              let espera;
              window.addEventListener('resize', () => { clearTimeout(espera); espera = setTimeout(ajustar, 150); });
            }
            setTimeout(ajustar, 0);
            """;

    private static final String COMO = "<div style='font-size:12px;color:#6b6f80'><b>Cómo se calcula.</b> Regresión lineal kWh = m × kg + b con los "
            + "lotes del período base del producto en esa línea (ISO 50006): 2025; si con 2025 no alcanza, 2025 más enero a septiembre de 2026, y "
            + "entonces se comparan los lotes desde octubre de 2026 (revisión de la línea base por datos insuficientes). Con R² ≥ 0,75 no se quita "
            + "ningún lote; si R² &lt; 0,75 se quita el lote más alejado de la recta si está a más de 2 sigma y se vuelve a ajustar, hasta llegar a "
            + "0,75 o quedar 3 lotes; cada atípico quitado debe tener su causa. La línea base se usa solo si pasa las cinco pruebas: al menos 3 lotes, "
            + "R² ≥ 0,75, prueba F con p &lt; 0,05 (la relación kWh–kg no es casualidad), pendiente m &gt; 0 e intercepto b ≥ 0 (la energía fija "
            + "del lote no puede ser negativa). Si solo falla el intercepto y no es distinto de cero (prueba t, p &gt; 0,05), se ajusta por el origen "
            + "(kWh = m × kg) y se vuelven a pedir las pruebas. Si nada alcanza, el producto queda <b>sin referencia válida</b> y se controla con la "
            + "línea base mensual de la línea; el motivo queda registrado. Objetivo = m × kg + b; máximo y mínimo = objetivo ± 2 sigma (sigma = error "
            + "estándar de la regresión). Lotes de la hoja MEDICION_XTR de los Excel de medición (kg = total de material procesado de PRODUCCIÓN).</div>";
}
