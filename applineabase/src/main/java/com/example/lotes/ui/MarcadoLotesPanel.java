package com.example.lotes.ui;

import com.example.base.model.GraficaModel;
import com.example.lotes.model.LoteProduccion;
import com.example.lotes.model.ProductoLinea;
import com.example.lotes.service.CatalogoProductosService;
import com.example.lotes.service.LineaBaseProductoService;
import com.example.lotes.service.LotesService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.shared.Registration;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import com.example.base.ui.CsvUtil;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.Locale;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Marcado de lotes de producción sobre una gráfica de energía. Es el mismo componente en Tiempo Real y en Histórico:
 * la vista lo agrega, le dice qué línea se ve ({@link #setLinea}) y le pasa cada clic ({@link #alClic}) y el doble clic
 * ({@link #limpiar}). Toma el kWh del contador guardado en el minuto clicado, se elige el producto del catálogo de la
 * línea (o de todas, o «Sin ítem») y se valida como inicio o fin. Solo ADMIN (la vista no lo crea para el resto).
 */
public class MarcadoLotesPanel extends HorizontalLayout {

    private static final DateTimeFormatter FH = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final String POR_LINEA = "Por línea";
    private static final String TODOS = "Todos";
    private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MMMM yyyy", new Locale("es"));
    private static final DateTimeFormatter F_CSV = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter H_CSV = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String COLOR_INICIO = "2e7d32";
    private static final String COLOR_FIN = "1565c0";

    private final CatalogoProductosService catalogo;
    private final LotesService lotes;
    private final LineaAccessService acceso;
    private final Component host;
    private final List<String> graficas;

    private final Span punto = new Span();
    private final ComboBox<String> alcance = new ComboBox<>();
    private final ComboBox<String> producto = new ComboBox<>();
    private final Span enCurso = new Span();
    private final Map<String, Long> idPorNombre = new LinkedHashMap<>();

    // Línea base por producto: banda y control del lote en curso (solo Tiempo Real); null en Histórico.
    private LineaBaseProductoService lineaBase;
    private final com.vaadin.flow.component.html.Div control = new com.vaadin.flow.component.html.Div();
    private Registration poll;
    private com.vaadin.flow.component.html.Div destinoEnCurso;
    // Productos de la lista sin línea base propia válida (se pintan en rojo).
    private final java.util.Set<String> sinPropia = new java.util.HashSet<>();
    // Avisa a la vista que cambió la línea o el producto (pestaña «Línea base» de Tiempo Real).
    private Runnable alCambiarProducto = () -> { };

    private String linea;
    private LocalDateTime momento;
    private Double kwh;

    /**
     * @param host     componente de la vista donde se ejecuta el JS de las gráficas
     * @param graficas ids de los contenedores de amCharts donde se dibujan las marcas validadas
     */
    public MarcadoLotesPanel(CatalogoProductosService catalogo, LotesService lotes, LineaAccessService acceso,
                             Component host, String... graficas) {
        this.catalogo = catalogo;
        this.lotes = lotes;
        this.acceso = acceso;
        this.host = host;
        this.graficas = List.of(graficas);

        setAlignItems(FlexComponent.Alignment.BASELINE);
        setWrap(true);
        setSpacing(true);
        getStyle().set("padding", "4px 8px").set("border", "1px solid #e4e6ec").set("border-radius", "8px")
                .set("background", "#fafbfc");


        alcance.setItems(POR_LINEA, TODOS);
        alcance.setValue(POR_LINEA);
        alcance.setWidth("130px");
        alcance.setAllowCustomValue(false);
        alcance.addValueChangeListener(e -> cargarProductos());

        producto.setPlaceholder("Producto");
        // Nombres largos: selector ancho y letra chica para que entren en una línea (también en la lista).
        producto.setWidth("480px");
        producto.getStyle().set("--vaadin-input-field-value-font-size", "12px").set("font-size", "12px")
                .set("--vaadin-combo-box-overlay-width", "560px");
        // En rojo los productos sin línea base propia válida (se usa la de su línea).
        producto.setRenderer(com.vaadin.flow.data.renderer.LitRenderer.<String>of(
                        "<span style='font-size:12px;white-space:nowrap;color:${item.c}'>${item.n}</span>")
                .withProperty("n", s -> s)
                .withProperty("c", s -> sinPropia.contains(s) ? "#c62828" : "inherit"));
        producto.setAllowCustomValue(false);
        producto.setClearButtonVisible(true);
        producto.addValueChangeListener(e -> {
            banda(false);
            alCambiarProducto.run();
            if (e.isFromClient()) avisarSinPropia(e.getValue());
        });

        Button inicio = new Button("Validar inicio", e -> validarInicio());
        inicio.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button fin = new Button("Validar fin", e -> validarFin());
        fin.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button lista = new Button("Lotes", e -> abrirLista());

        punto.getStyle().set("font-size", "13px").set("min-width", "230px");
        enCurso.getStyle().set("font-size", "12px").set("color", "#555");

        control.getStyle().set("width", "100%").set("font-size", "13px");
        add(alcance, producto, inicio, fin, lista, enCurso, punto);
        limpiar();
        setVisible(false);
    }

    /** Línea que muestra la gráfica. Solo se marca en líneas de extrusión ("LineaNN"). */
    public void setLinea(String maquina) {
        boolean esLinea = maquina != null && maquina.matches("Linea\\d+");
        this.linea = esLinea ? maquina : null;
        setVisible(esLinea);
        limpiar();
        if (esLinea) {
            cargarProductos();
            mostrarEnCurso();
        } else {
            control.removeAll();
            if (destinoEnCurso != null) destinoEnCurso.setVisible(false);
        }
        banda(false);
        alCambiarProducto.run();
    }

    public void setAlCambiarProducto(Runnable r) {
        this.alCambiarProducto = r == null ? () -> { } : r;
    }

    /** Línea de la gráfica (null si no es una línea de extrusión). */
    public String getLinea() {
        return linea;
    }

    /** Producto elegido en la franja; si no hay, el del lote en curso. */
    public String productoElegido() {
        if (producto.getValue() != null) return producto.getValue();
        return linea == null ? null : lotes.enCurso(linea).map(LoteProduccion::getProducto).orElse(null);
    }

    /** Activa la banda objetivo / máximo / mínimo y el cuadro del lote en curso (Tiempo Real). */
    public void conLineaBase(LineaBaseProductoService lineaBase) {
        this.lineaBase = lineaBase;
    }

    @Override
    protected void onAttach(AttachEvent e) {
        super.onAttach(e);
        // Refresca el cuadro del lote en curso con el poll de la aplicación (avisos de alarmas, cada 30 s).
        if (lineaBase != null) poll = e.getUI().addPollListener(p -> { if (linea != null) mostrarControl(); });
    }

    @Override
    protected void onDetach(DetachEvent e) {
        super.onDetach(e);
        if (poll != null) poll.remove();
        poll = null;
    }

    /** Dibuja la banda del lote en curso en la gráfica (la vista lo llama después de crear la gráfica). */
    public void redibujarBanda() {
        banda(true);
    }

    private void banda(boolean conMarcaInicio) {
        if (lineaBase == null || graficas.isEmpty()) return;
        // Producto elegido en el selector (o el del lote en curso): la banda aparece al elegirlo, haya o no lote abierto.
        String prod = productoElegido();
        Optional<LineaBaseProductoService.Banda> b = linea == null || prod == null ? Optional.empty() : lineaBase.banda(linea, prod);
        host.getElement().executeJs(b.map(x -> GraficaModel.scriptBandaLote(graficas.getFirst(), x.objetivo(), x.maximo(), x.minimo()))
                .orElse(GraficaModel.scriptBandaLote(graficas.getFirst(), null, 0, 0)));
        Optional<LoteProduccion> l = linea == null ? Optional.empty() : lotes.enCurso(linea);
        if (conMarcaInicio) l.ifPresent(x -> dibujar(x.getInicio(), "Inicio: " + x.getProducto(), COLOR_INICIO));
    }

    /** Cuadro del lote en curso: kWh acumulado contra lo esperado al ritmo objetivo del producto. */
    private void mostrarControl() {
        control.removeAll();
        if (lineaBase == null || linea == null) return;
        Optional<LoteProduccion> lo = lotes.enCurso(linea);
        if (lo.isEmpty() || LotesService.SIN_ITEM.equals(lo.get().getProducto())) return;
        LoteProduccion l = lo.get();
        Optional<LineaBaseProductoService.Banda> ob = lineaBase.banda(linea, l.getProducto());
        if (ob.isEmpty()) {
            control.add(new Html("<div style='border-radius:8px;padding:6px 10px;background:#FCE4E6;color:#a3222c'><b>Sin línea base válida</b> "
                    + "para este producto ni para la " + linea.replace("Linea", "Línea ") + ".</div>"));
            return;
        }
        LineaBaseProductoService.Banda b = ob.get();
        // Último minuto con contador guardado (la lectura es cada 60 s).
        LocalDateTime ahora = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        Double kwhAhora = null;
        LocalDateTime cuando = null;
        for (int i = 0; i <= 5 && kwhAhora == null; i++) {
            cuando = ahora.minusMinutes(i);
            kwhAhora = lotes.kwhGuardado(linea, cuando).orElse(null);
        }
        if (kwhAhora == null || !cuando.isAfter(l.getInicio())) return;
        double min = ChronoUnit.SECONDS.between(l.getInicio(), cuando) / 60.0;
        double kwh = kwhAhora - l.getKwhInicio(), esp = b.objetivo() * min, rango = b.delta() * min, dif = kwh - esp;
        String fondo, color, texto;
        if (dif > rango) { fondo = "#FCE4E6"; color = "#a3222c"; texto = "Sobre el máximo"; }
        else if (dif < -rango) { fondo = "#FBF3D5"; color = "#7a6205"; texto = "Bajo el mínimo"; }
        else { fondo = "#E3F5EA"; color = "#1d7a46"; texto = "Dentro de la banda"; }
        String kp = kpi(b.aplicada().dePropia() ? "Del producto" : "De la " + linea.replace("Linea", "Línea "), "línea base usada")
                + kpi(horas(min / 60), "transcurrido") + kpi(LineaBaseProductoService.num(kwh, 0) + " kWh", "acumulado")
                + kpi(LineaBaseProductoService.num(kwh / min, 3) + " kWh/min", "promedio del lote")
                + kpi(LineaBaseProductoService.num(esp, 0) + " kWh", "esperado al ritmo objetivo")
                + kpi(LineaBaseProductoService.num(esp - rango, 0) + " a " + LineaBaseProductoService.num(esp + rango, 0), "rango aceptable (kWh)")
                + kpi((dif > 0 ? "+" : "") + LineaBaseProductoService.num(dif, 0) + " kWh (" + (dif > 0 ? "+" : "")
                + LineaBaseProductoService.num(100 * dif / esp, 1) + " %)", "diferencia")
                + "<div style='align-self:center;border-radius:8px;padding:6px 10px;background:" + fondo + ";color:" + color + ";font-weight:600'>" + texto + "</div>";
        control.add(new Html("<div style='display:flex;flex-wrap:wrap;gap:8px;align-items:stretch'>" + kp + "</div>"));
    }

    private static String kpi(String v, String l) {
        return "<div style='border:1px solid #E4E6EC;border-radius:8px;padding:4px 10px;background:#fff'><div style='font-weight:600'>" + v
                + "</div><div style='font-size:11px;color:#6b6f80'>" + l + "</div></div>";
    }

    /** Clic en la gráfica: fecha, hora y kWh del contador guardado en ese minuto. */
    public void alClic(long timestamp) {
        if (linea == null) return;
        momento = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()).truncatedTo(ChronoUnit.MINUTES);
        kwh = lotes.kwhGuardado(linea, momento).orElse(null);
        punto.setText(momento.format(FH) + " · " + (kwh == null ? "sin dato de kWh en ese minuto" : "contador " + GraficaModel.formatearDecimal(kwh) + " kWh"));
        punto.getStyle().set("color", kwh == null ? "#721c24" : "#1e1e2c");
    }

    /** Doble clic: borra el punto elegido y la banda de la gráfica (vuelve al cambiar el producto o recargar la gráfica). */
    public void limpiar() {
        if (lineaBase != null && !graficas.isEmpty()) {
            host.getElement().executeJs(GraficaModel.scriptBandaLote(graficas.getFirst(), null, 0, 0));
        }
        momento = null;
        kwh = null;
        punto.setText("");
        punto.getStyle().set("color", "#777");
    }

    /** Al elegir un producto sin línea base propia: aviso con el motivo y la línea base que se usa. */
    private void avisarSinPropia(String p) {
        if (lineaBase == null || linea == null || p == null || LotesService.SIN_ITEM.equals(p)) return;
        lineaBase.aplicable(linea, p).filter(a -> !a.dePropia()).ifPresent(a -> aviso(
                p + " no tiene línea base propia (" + a.motivo() + "). Se usa la línea base de la " + linea.replace("Linea", "Línea ") + "."));
    }

    private void cargarProductos() {
        if (linea == null) return;
        List<ProductoLinea> l = TODOS.equals(alcance.getValue()) ? catalogo.todos() : catalogo.productosDe(linea);
        idPorNombre.clear();
        for (ProductoLinea p : l) idPorNombre.putIfAbsent(p.getNombre(), p.getId());
        List<String> items = new ArrayList<>(idPorNombre.keySet());
        sinPropia.clear();
        if (lineaBase != null) for (String n : items) if (!lineaBase.tienePropia(linea, n)) sinPropia.add(n);
        items.add(LotesService.SIN_ITEM);
        String antes = producto.getValue();
        producto.setItems(items);
        if (antes != null && items.contains(antes)) producto.setValue(antes);
    }

    private boolean puntoListo() {
        if (momento == null) {
            aviso("Haga clic en la gráfica para elegir el minuto.");
            return false;
        }
        if (kwh == null) {
            aviso("No hay kWh guardado en ese minuto. Elija otro punto.");
            return false;
        }
        return true;
    }

    private void validarInicio() {
        if (!puntoListo()) return;
        String p = producto.getValue();
        if (p == null || p.isBlank()) {
            aviso("Elija el producto (o «Sin ítem»).");
            return;
        }
        try {
            LoteProduccion l = lotes.validarInicio(linea, p, idPorNombre.get(p), momento, kwh, acceso.usuarioActual());
            dibujar(l.getInicio(), "Inicio: " + l.getProducto(), COLOR_INICIO);
            Notification.show("Inicio del lote validado: " + l.getProducto() + ", " + l.getInicio().format(FH), 4000, Notification.Position.MIDDLE);
            limpiar();
            mostrarEnCurso();
            banda(false);
        } catch (IllegalArgumentException ex) {
            aviso(ex.getMessage());
        }
    }

    private void validarFin() {
        if (!puntoListo()) return;
        try {
            LoteProduccion l = lotes.validarFin(linea, momento, kwh, acceso.usuarioActual());
            dibujar(l.getFin(), "Fin: " + l.getProducto(), COLOR_FIN);
            Dialog d = new Dialog();
            d.setHeaderTitle("Lote validado");
            VerticalLayout v = new VerticalLayout(
                    new Span("Línea: " + l.getLinea()),
                    new Span("Producto: " + l.getProducto()),
                    new Span("Inicio: " + l.getInicio().format(FH) + "  ·  Fin: " + l.getFin().format(FH)),
                    negrita("Energía del lote: " + GraficaModel.formatearDecimal(l.kwhLote()) + " kWh"),
                    negrita("Duración: " + horas(l.horas())));
            v.setPadding(false);
            v.setSpacing(false);
            d.add(v);
            d.getFooter().add(new Button("Cerrar", e -> d.close()));
            d.open();
            limpiar();
            mostrarEnCurso();
            banda(false);
        } catch (IllegalArgumentException ex) {
            aviso(ex.getMessage());
        }
    }

    /**
     * Dónde se muestra el lote en curso: en Tiempo Real va en la franja de arriba, después de Fecha, Hora y KWh (la vista lo
     * pasa al entrar y null al salir); sin destino, en la propia franja (Histórico).
     */
    public void setDestinoEnCurso(com.vaadin.flow.component.html.Div destino) {
        this.destinoEnCurso = destino;
        enCurso.setVisible(destino == null);
        mostrarEnCurso();
    }

    /** Cuadro del lote en curso contra la línea base (la vista lo ubica debajo de la fila de selectores). */
    public com.vaadin.flow.component.html.Div getControl() {
        return control;
    }

    private void mostrarEnCurso() {
        Optional<LoteProduccion> l = linea == null ? Optional.empty() : lotes.enCurso(linea);
        enCurso.setText(l.map(x -> "En curso: " + x.getProducto() + " desde " + x.getInicio().format(FH)).orElse("Sin lote en curso"));
        if (destinoEnCurso != null) {
            destinoEnCurso.setVisible(linea != null);
            String v = l.map(x -> x.getProducto() + " · desde " + x.getInicio().format(FH)).orElse("Sin lote");
            destinoEnCurso.getElement().setProperty("innerHTML", "<div style='font-size:12px;white-space:nowrap'><span style='color:#898781'>Lote en curso: </span>"
                    + "<span style='font-weight:600;color:#0b0b0b'>" + v.replace("&", "&amp;").replace("<", "&lt;") + "</span></div>");
        }
        l.ifPresent(x -> {
            if (idPorNombre.containsKey(x.getProducto()) || LotesService.SIN_ITEM.equals(x.getProducto())) producto.setValue(x.getProducto());
        });
        mostrarControl();
    }

    private void dibujar(LocalDateTime cuando, String texto, String color) {
        long ts = cuando.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        for (String g : graficas) host.getElement().executeJs(GraficaModel.scriptMarcaLote(g, ts, texto, color));
    }

    private void abrirLista() {
        Dialog d = new Dialog();
        d.setHeaderTitle("Lotes marcados");
        d.setWidth("1100px");
        Grid<LoteProduccion> grid = new Grid<>(LoteProduccion.class, false);
        grid.addColumn(LoteProduccion::getLinea).setHeader("Línea").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(LoteProduccion::getProducto).setHeader("Producto").setFlexGrow(1);
        grid.addColumn(l -> l.getInicio().format(FH)).setHeader("Inicio").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(l -> l.getFin() == null ? "En curso" : l.getFin().format(FH)).setHeader("Fin").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(l -> GraficaModel.formatearDecimal(l.getKwhInicio())).setHeader("kWh inicio").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(l -> l.getKwhFin() == null ? "" : GraficaModel.formatearDecimal(l.getKwhFin())).setHeader("kWh fin").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(l -> l.kwhLote() == null ? "" : GraficaModel.formatearDecimal(l.kwhLote())).setHeader("kWh del lote").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(l -> l.horas() == null ? "" : horas(l.horas())).setHeader("Horas").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(l -> l.getUsuarioFin() != null ? l.getUsuarioFin() : l.getUsuarioInicio()).setHeader("Validó").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(l -> {
            Button b = new Button("Borrar", e -> confirmarBorrar(l, grid));
            b.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
            return b;
        }).setAutoWidth(true).setFlexGrow(0);
        grid.setItems(lotes.todos());
        grid.setHeight("480px");

        Span info = new Span(catalogo.cantidad() + " productos en el catálogo");
        info.getStyle().set("font-size", "12px").set("color", "#555");
        Button actualizar = new Button("Actualizar catálogo", e -> {
            CatalogoProductosService.Resultado r = catalogo.actualizar();
            String msg = r.nuevos() + " productos nuevos de " + String.join(", ", r.archivos())
                    + (r.error() == null ? "" : ". " + r.error());
            Notification.show(msg, 6000, Notification.Position.MIDDLE);
            info.setText(catalogo.cantidad() + " productos en el catálogo");
            cargarProductos();
        });
        ComboBox<YearMonth> mes = new ComboBox<>();
        mes.setPlaceholder("Mes");
        mes.setWidth("150px");
        mes.setItemLabelGenerator(m -> m.format(MES));
        List<YearMonth> meses = lotes.todos().stream().map(l -> YearMonth.from(l.getInicio())).distinct()
                .sorted(Comparator.reverseOrder()).toList();
        mes.setItems(meses);
        HorizontalLayout descarga = new HorizontalLayout(mes);
        descarga.setAlignItems(FlexComponent.Alignment.BASELINE);
        mes.addValueChangeListener(e -> {
            while (descarga.getComponentCount() > 1) descarga.remove(descarga.getComponentAt(1));
            YearMonth m = e.getValue();
            if (m != null) {
                descarga.add(CsvUtil.crearLinkDescarga("lotes_" + m + ".csv",
                        () -> new ByteArrayInputStream(csvDelMes(m).getBytes(StandardCharsets.UTF_8)), "Descargar CSV"));
            }
        });
        if (!meses.isEmpty()) mes.setValue(meses.getFirst());
        d.add(grid);
        d.getFooter().add(descarga, info, actualizar, new Button("Cerrar", e -> d.close()));
        d.open();
    }

    /** Lotes que empiezan en el mes, para pegar en el Excel: separador ";" y coma decimal. */
    private String csvDelMes(YearMonth m) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("LINEA;ITEM;Fecha Inicio;Hora Inicio;KWh Inicio;Fecha Fin;Hora Fin;KWh Fin;KWh Lote;Horas;Validó\n");
        List<LoteProduccion> l = new ArrayList<>(lotes.todos());
        l.sort(Comparator.comparing(LoteProduccion::getLinea).thenComparing(LoteProduccion::getInicio));
        for (LoteProduccion x : l) {
            if (!YearMonth.from(x.getInicio()).equals(m)) continue;
            sb.append(x.getLinea().toUpperCase(Locale.ROOT)).append(';')
                    .append(CsvUtil.escape(x.getProducto())).append(';')
                    .append(x.getInicio().format(F_CSV)).append(';').append(x.getInicio().format(H_CSV)).append(';')
                    .append(num(x.getKwhInicio(), 3)).append(';')
                    .append(x.getFin() == null ? "" : x.getFin().format(F_CSV)).append(';')
                    .append(x.getFin() == null ? "" : x.getFin().format(H_CSV)).append(';')
                    .append(x.getKwhFin() == null ? "" : num(x.getKwhFin(), 3)).append(';')
                    .append(x.kwhLote() == null ? "" : num(x.kwhLote(), 3)).append(';')
                    .append(x.horas() == null ? "" : num(x.horas(), 3)).append(';')
                    .append(CsvUtil.escape(x.getUsuarioFin() != null ? x.getUsuarioFin() : String.valueOf(x.getUsuarioInicio())))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String num(double v, int dec) {
        return String.format(Locale.US, "%." + dec + "f", v).replace('.', ',');
    }

    private void confirmarBorrar(LoteProduccion l, Grid<LoteProduccion> grid) {
        Dialog c = new Dialog();
        c.setHeaderTitle("¿Borrar el lote?");
        c.add(new Span(l.getLinea() + " · " + l.getProducto() + " · " + l.getInicio().format(FH)));
        Button si = new Button("Borrar", e -> {
            lotes.borrar(l);
            grid.setItems(lotes.todos());
            mostrarEnCurso();
            banda(false);
            c.close();
        });
        si.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);
        c.getFooter().add(new Button("Cancelar", e -> c.close()), si);
        c.open();
    }

    private static String horas(Double h) {
        if (h == null) return "";
        long min = Math.round(h * 60);
        return String.format("%d:%02d h", min / 60, min % 60);
    }

    private static Span negrita(String t) {
        Span s = new Span(t);
        s.getStyle().set("font-weight", "bold");
        return s;
    }

    private static void aviso(String t) {
        Notification.show(t, 5000, Notification.Position.MIDDLE);
    }
}
