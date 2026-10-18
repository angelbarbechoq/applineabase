package com.example.franjahoraria.ui;

import com.example.base.model.GraficaModel;
import com.example.base.ui.MainLayout;
import com.example.franjahoraria.model.Feriado;
import com.example.franjahoraria.model.Franja;
import com.example.franjahoraria.model.TarifaFranja;
import com.example.franjahoraria.service.ConsumoFranjaService;
import com.example.franjahoraria.service.ConsumoFranjaService.Dia;
import com.example.franjahoraria.service.ConsumoFranjaService.Mes;
import com.example.franjahoraria.service.FeriadoService;
import com.example.franjahoraria.service.TarifaFranjaService;
import com.example.franjahoraria.service.TarifaFranjaService.TarifaDelMes;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.FooterRow;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.HeaderRow;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.AttachmentType;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Consumo de la planta por franja horaria (A, B, C, D) y su costo con la tarifa de cada mes:
 * - Detalle del mes: tarjetas por franja, dona de kWh, barras por día y tabla por día.
 * - Histórico: tabla por mes del año elegido con el mismo formato que la planilla de la empresa (kWh, precio
 *   facturado y $/kWh por franja). Solo consulta: el clic en un mes no lleva al detalle (pedido del usuario).
 * - Tarifas: los 4 precios de cada mes (cargar/editar solo ADMIN). Un mes sin tarifa propia usa la del
 *   último mes cargado antes, marcado en amarillo.
 * - Feriados (opcional, cargar solo ADMIN): esos días cuentan como sábado o domingo.
 * Acceso: ADMIN y los usuarios con "Ver Franja Horaria" (lo marca el ADMIN en Usuarios).
 */
@PageTitle("Consumo por franja horaria | LineaBase")
@Route(value = "energia/franja-horaria", layout = MainLayout.class)
@PermitAll
public class ConsumoFranjaView extends VerticalLayout implements BeforeEnterObserver {

    private static final String CHART_ID = "chartdiv_franja_dias";
    private static final String[] MESES = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
            "agosto", "septiembre", "octubre", "noviembre", "diciembre"};
    private static final String[] DIAS = {"Lunes", "Martes", "Miercoles", "Jueves", "Viernes", "Sabado", "Domingo"};
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter CORTA = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    // Paleta categórica de referencia, slots 1 a 4 en orden fijo (validada para daltonismo en pares vecinos):
    // azul, naranja, aqua, amarillo. El color sigue a la franja en todas las pestañas.
    private static final String[] COLORES = {"#2a78d6", "#eb6834", "#1baf7a", "#eda100"};
    private static final Locale CSV = Locale.of("es", "EC");

    private final ConsumoFranjaService consumo;
    private final TarifaFranjaService tarifaService;
    private final FeriadoService feriadoService;
    private final LineaAccessService acceso;
    private final boolean admin;
    private List<TarifaFranja> tarifas = List.of();

    private final TabSheet tabs = new TabSheet();
    private Tab tabDetalle, tabAnual;

    // Detalle del mes
    private final ComboBox<YearMonth> mesCombo = new ComboBox<>("Mes");
    private final Span tarifaInfo = new Span();
    private final Span mensaje = new Span();
    private final HorizontalLayout tarjetas = new HorizontalLayout();
    private final HorizontalLayout dona = new HorizontalLayout();
    private final Div chart = new Div();
    private final Grid<Dia> diasGrid = new Grid<>();
    private final Map<Franja, Grid.Column<Dia>> colDiaFranja = new EnumMap<>(Franja.class);
    private Grid.Column<Dia> colDia, colDiaTotal, colDiaCosto, colDiaEstimado, colDiaSinDatos;
    private FooterRow pieDias;
    private TarifaDelMes tarifaMes;
    private String scriptGrafico;

    // Histórico
    private final ComboBox<Integer> anioCombo = new ComboBox<>("Anio");
    private final Grid<FilaMes> mesesGrid = new Grid<>();
    private final Map<Franja, Grid.Column<FilaMes>[]> colMesFranja = new EnumMap<>(Franja.class);
    private Grid.Column<FilaMes> colMes, colMesTotal, colMesCosto, colMesMedio;
    private FooterRow pieMeses;
    private volatile List<FilaMes> filasAnio = List.of();
    private volatile int anioMostrado;
    private boolean anualAlDia;

    // Tarifas y feriados
    private final Grid<TarifaDelMes> tarifasGrid = new Grid<>();
    private final Grid<Feriado> feriadosGrid = new Grid<>();

    /** Un mes de la tabla anual con la tarifa con que se calcula. */
    record FilaMes(Mes datos, TarifaDelMes tarifa) {
        Double costo(Franja f) {
            return tarifa.sinTarifa() ? null : datos.kwh(f) * tarifa.precio(f);
        }

        Double costoTotal() {
            if (tarifa.sinTarifa()) return null;
            double c = 0;
            for (Franja f : Franja.values()) c += costo(f);
            return c;
        }
    }

    public ConsumoFranjaView(ConsumoFranjaService consumo, TarifaFranjaService tarifaService, FeriadoService feriadoService,
                             LineaAccessService acceso) {
        this.consumo = consumo;
        this.tarifaService = tarifaService;
        this.feriadoService = feriadoService;
        this.acceso = acceso;
        this.admin = acceso.esAdmin();
        setPadding(true);
        setSpacing(true);
        setWidthFull();

        H3 titulo = new H3("Consumo por franja horaria");
        titulo.getStyle().set("margin", "0");
        StringBuilder franjas = new StringBuilder();
        for (Franja f : Franja.values()) franjas.append(franjas.isEmpty() ? "" : "; ").append(f.etiqueta());
        add(titulo, nota("Medidor general de la planta (" + ConsumoFranjaService.MEDIDOR + "), el mismo punto que factura la "
                + "empresa electrica. Franjas: " + franjas + ". S = sabado, D = domingo, F = feriado cargado en la pestana Feriados."));

        tabs.setWidthFull();
        tabDetalle = tabs.add("Detalle del mes", crearDetalle());
        tabAnual = tabs.add("Historico", crearAnual());
        tabs.add("Tarifas", crearTarifas());
        tabs.add("Feriados", crearFeriados());
        // El gráfico se arma con la pestaña visible (con display:none amCharts lo dibuja sin tamaño). La tabla
        // anual lee todos los meses: se calcula recién al abrir su pestaña.
        tabs.addSelectedChangeListener(e -> {
            if (e.getSelectedTab() == tabDetalle && scriptGrafico != null) getElement().executeJs(scriptGrafico);
            if (e.getSelectedTab() == tabAnual && !anualAlDia) verAnual();
        });
        add(tabs);

        tarifas = tarifaService.listar();
        verDetalle();
        verTarifas();
        verFeriados();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        AccesoFranjaHoraria.verificar(event, acceso);
    }

    /** Después de cambiar tarifas o feriados. */
    private void refrescar() {
        tarifas = tarifaService.listar();
        verDetalle();
        verTarifas();
        verFeriados();
        anualAlDia = false;
        if (tabs.getSelectedTab() == tabAnual) verAnual();
    }

    // ================= Detalle del mes =================

    private VerticalLayout crearDetalle() {
        List<YearMonth> meses = new ArrayList<>();
        for (YearMonth m = YearMonth.now(); meses.size() < 36; m = m.minusMonths(1)) meses.add(m);
        mesCombo.setItems(meses);
        mesCombo.setItemLabelGenerator(ConsumoFranjaView::nombreMes);
        mesCombo.setValue(YearMonth.now());
        mesCombo.setWidth("190px");
        mesCombo.addValueChangeListener(e -> {
            if (e.getValue() != null) verDetalle();
        });
        Button recalcular = new Button("Recalcular", VaadinIcon.REFRESH.create(), e -> {
            YearMonth mes = mesCombo.getValue() == null ? YearMonth.now() : mesCombo.getValue();
            consumo.olvidar(mes);
            verDetalle();
            anualAlDia = false;
        });
        recalcular.setTooltipText("Vuelve a leer los archivos de energia del mes (por ejemplo, despues de copiar datos historicos)");
        HorizontalLayout filtros = new HorizontalLayout(mesCombo, recalcular, tarifaInfo);
        filtros.setAlignItems(Alignment.END);
        filtros.getStyle().set("flex-wrap", "wrap");
        mensaje.getStyle().set("font-size", "12px").set("color", "#555");

        tarjetas.setWidthFull();
        tarjetas.getStyle().set("flex-wrap", "wrap");
        dona.setAlignItems(Alignment.CENTER);
        dona.getStyle().set("flex", "0 0 auto");
        chart.setId(CHART_ID);
        chart.setHeight("340px");
        chart.getStyle().set("flex", "1 1 480px").set("min-width", "320px");
        HorizontalLayout graficos = new HorizontalLayout(dona, chart);
        graficos.setWidthFull();
        graficos.setAlignItems(Alignment.CENTER);
        graficos.getStyle().set("flex-wrap", "wrap");

        colDia = diasGrid.addColumn(d -> DIAS[d.fecha().getDayOfWeek().getValue() - 1].substring(0, 3) + " "
                + d.fecha().format(FECHA)).setHeader("Dia").setAutoWidth(true).setFlexGrow(0).setFrozen(true);
        diasGrid.addColumn(ConsumoFranjaView::tipoDeDia).setHeader("Tipo de dia").setAutoWidth(true).setFlexGrow(0);
        for (Franja f : Franja.values()) {
            colDiaFranja.put(f, columna(diasGrid, f.name() + " (kWh)", d -> d.conDatos() ? kwhNum(d.kwh(f)) : "-"));
        }
        colDiaTotal = columna(diasGrid, "Total (kWh)", d -> d.conDatos() ? kwhNum(d.total()) : "-");
        colDiaCosto = columna(diasGrid, "Costo", d -> d.conDatos() && !tarifaMes.sinTarifa() ? dinero(costo(d)) : "-");
        colDiaEstimado = columna(diasGrid, "Estimado (h)", d -> horasSiHay(d.horasEstimadas()));
        colDiaSinDatos = columna(diasGrid, "Sin datos (h)", d -> horasSiHay(d.horasSinDatos()));
        pieDias = diasGrid.appendFooterRow();
        diasGrid.setWidthFull();
        diasGrid.setHeight("520px");

        Span nota = nota("Cada tramo entre dos lecturas del contador se reparte entre las franjas que cruza, en proporcion al tiempo. "
                + "Estimado = horas en las que faltaron lecturas por mas de " + ConsumoFranjaService.MINUTOS_HUECO + " minutos: "
                + "el consumo de ese hueco se reparte por tiempo. Sin datos = horas sin lecturas validas (no se cuentan). "
                + "Costo = kWh de cada franja x precio de la franja del mes (pestana Tarifas). Mes calendario, como la factura. "
                + "Los feriados solo cambian algo si caen de lunes a viernes: ese dia no hay A ni B, todo va a C y D.");
        VerticalLayout v = new VerticalLayout(descripcion("Cuanta energia tomo la planta en cada franja horaria y cuanto cuesta con la "
                + "tarifa del mes. Por defecto, el mes en curso; se puede elegir cualquier mes."), filtros, mensaje, tarjetas, graficos,
                diasGrid, nota);
        v.setPadding(false);
        v.setWidthFull();
        return v;
    }

    private void verDetalle() {
        YearMonth mes = mesCombo.getValue() == null ? YearMonth.now() : mesCombo.getValue();
        long t0 = System.currentTimeMillis();
        Mes datos = consumo.calcular(mes);
        tarifaMes = TarifaFranjaService.delMes(tarifas, mes);
        diasGrid.setItems(datos.dias());
        mostrarTarifa(tarifaMes);

        tarjetas.removeAll();
        dona.removeAll();
        if (!datos.conDatos()) {
            mensaje.setText("No hay lecturas del medidor general en " + nombreMes(mes) + ".");
            scriptGrafico = null;
            chart.setVisible(false);
            pieDias.getCell(colDia).setText("Total");
            for (Franja f : Franja.values()) pieDias.getCell(colDiaFranja.get(f)).setText("-");
            pieDias.getCell(colDiaTotal).setText("-");
            pieDias.getCell(colDiaCosto).setText("-");
            pieDias.getCell(colDiaEstimado).setText("");
            pieDias.getCell(colDiaSinDatos).setText("");
            return;
        }

        double total = datos.total();
        for (Franja f : Franja.values()) {
            Double precio = tarifaMes.precio(f);
            String detalle = (total > 0 ? String.format(Locale.ROOT, "%.0f %% del consumo", datos.kwh(f) / total * 100) : "")
                    + (precio == null ? "" : "; " + dinero(datos.kwh(f) * precio) + " a " + precio(precio) + "/kWh");
            tarjetas.add(tarjetaFranja(f, kwh(datos.kwh(f)), detalle));
        }
        Double costoTotal = costo(datos);
        tarjetas.add(tarjeta("Total del mes" + (datos.enCurso() ? " (en curso)" : ""), kwh(total),
                costoTotal == null ? "sin tarifa: solo kWh" : dinero(costoTotal) + "; medio "
                        + (total > 0 ? precio(costoTotal / total) : "-") + "/kWh"));
        if (datos.horasEstimadas() >= 0.05 || datos.horasSinDatos() >= 0.05) {
            tarjetas.add(tarjeta("Lecturas", String.format(Locale.ROOT, "%.1f h", datos.horasEstimadas() + datos.horasSinDatos()),
                    String.format(Locale.ROOT, "%.1f h estimadas, %.1f h sin datos", datos.horasEstimadas(), datos.horasSinDatos())));
        }
        dona.add(armarDona(datos));

        pieDias.getCell(colDia).setText("Total");
        for (Franja f : Franja.values()) pieDias.getCell(colDiaFranja.get(f)).setText(kwhNum(datos.kwh(f)));
        pieDias.getCell(colDiaTotal).setText(kwhNum(total));
        pieDias.getCell(colDiaCosto).setText(costoTotal == null ? "-" : dinero(costoTotal));
        pieDias.getCell(colDiaEstimado).setText(horasSiHay(datos.horasEstimadas()));
        pieDias.getCell(colDiaSinDatos).setText(horasSiHay(datos.horasSinDatos()));

        List<String> categorias = new ArrayList<>();
        List<Double[]> valores = new ArrayList<>();
        for (Dia d : datos.dias()) {
            categorias.add(d.fecha().format(DateTimeFormatter.ofPattern("dd")));
            Double[] v = new Double[Franja.values().length];
            for (Franja f : Franja.values()) v[f.ordinal()] = d.kwh(f) > 0 ? d.kwh(f) : null;
            valores.add(v);
        }
        String[] series = new String[Franja.values().length];
        String[] colores = new String[series.length];
        for (Franja f : Franja.values()) {
            series[f.ordinal()] = "Franja " + f.name();
            colores[f.ordinal()] = "0x" + COLORES[f.ordinal()].substring(1);
        }
        chart.setVisible(true);
        scriptGrafico = GraficaModel.getBarrasApiladasScript(CHART_ID, categorias, series, colores, valores, "kWh");
        if (tabs.getSelectedTab() == tabDetalle) getElement().executeJs(scriptGrafico);

        Dia ultimo = datos.dias().get(datos.dias().size() - 1);
        mensaje.setText(nombreMes(mes) + (datos.enCurso() ? ", hasta el " + LocalDateTime.now().format(CORTA) : ", completo")
                + (datos.feriados().isEmpty() ? ", sin feriados cargados" : ", feriados: " + String.join(", ",
                datos.feriados().entrySet().stream().map(e -> e.getKey().format(FECHA) + " " + e.getValue()).toList()))
                + " (" + (System.currentTimeMillis() - t0) + " ms)." + (ultimo.conDatos() ? "" : " El ultimo dia no tiene lecturas."));
    }

    private Double costo(Dia d) {
        if (tarifaMes.sinTarifa()) return null;
        double c = 0;
        for (Franja f : Franja.values()) c += d.kwh(f) * tarifaMes.precio(f);
        return c;
    }

    private Double costo(Mes m) {
        if (tarifaMes.sinTarifa()) return null;
        double c = 0;
        for (Franja f : Franja.values()) c += m.kwh(f) * tarifaMes.precio(f);
        return c;
    }

    private void mostrarTarifa(TarifaDelMes t) {
        String texto;
        String[] c;
        if (t.sinTarifa()) {
            texto = "Sin tarifa cargada: solo kWh (pestana Tarifas)";
            c = GRIS;
        } else if (t.propia()) {
            texto = "Tarifa de " + nombreMes(t.mes()) + (t.tarifa().getUsuario() == null ? "" : ", cargada por " + t.tarifa().getUsuario());
            c = VERDE;
        } else {
            texto = nombreMes(t.mes()) + " no tiene tarifa propia: se usa la de " + nombreMes(t.origen()) + " (pestana Tarifas)";
            c = AMARILLO;
        }
        tarifaInfo.setText(texto);
        estiloBadge(tarifaInfo, c);
        tarifaInfo.getStyle().set("font-size", "13px").set("padding", "6px 10px").set("margin-bottom", "4px");
    }

    private static String tipoDeDia(Dia d) {
        if (d.feriado() != null) return "Feriado: " + d.feriado();
        if (Franja.finDeSemana(d.fecha())) return DIAS[d.fecha().getDayOfWeek().getValue() - 1];
        return "Lunes a viernes";
    }

    /** Dona de kWh por franja (CSS, sin amCharts) con la leyenda y los valores al lado. */
    private static List<Component> armarDona(Mes datos) {
        double total = datos.total();
        StringBuilder g = new StringBuilder();
        long conValor = Arrays.stream(Franja.values()).filter(f -> datos.kwh(f) > 0).count();
        double inicio = 0;
        StringBuilder titulo = new StringBuilder();
        for (Franja f : Franja.values()) {
            double kwh = datos.kwh(f);
            titulo.append(f.name()).append(": ").append(kwh(kwh)).append(total > 0
                    ? String.format(Locale.ROOT, " (%.1f %%)", kwh / total * 100) : "").append('\n');
            if (kwh <= 0 || total <= 0) continue;
            double fin = inicio + kwh / total * 360;
            // 1 grado de separación del color del fondo entre porciones.
            double corte = conValor > 1 ? Math.max(inicio, fin - 1) : fin;
            g.append(g.isEmpty() ? "" : ", ").append(String.format(Locale.ROOT, "%s %.2fdeg %.2fdeg, #ffffff %.2fdeg %.2fdeg",
                    COLORES[f.ordinal()], inicio, corte, corte, fin));
            inicio = fin;
        }
        Div anillo = new Div();
        anillo.setWidth("180px");
        anillo.setHeight("180px");
        anillo.getStyle().set("border-radius", "50%").set("position", "relative").set("flex", "0 0 auto")
                .set("background", g.isEmpty() ? "#e1e0d9" : "conic-gradient(" + g + ")");
        anillo.getElement().setAttribute("title", titulo.toString().trim());
        Span valor = new Span(String.format(Locale.ROOT, "%,.0f", total));
        valor.getStyle().set("font-size", "18px").set("font-weight", "700").set("color", "#0b0b0b");
        Span unidad = new Span("kWh");
        unidad.getStyle().set("font-size", "11px").set("color", "#52514e");
        Div hueco = new Div(valor, unidad);
        hueco.getStyle().set("position", "absolute").set("inset", "22%").set("border-radius", "50%").set("background", "#ffffff")
                .set("display", "flex").set("flex-direction", "column").set("align-items", "center").set("justify-content", "center");
        anillo.add(hueco);

        VerticalLayout leyenda = new VerticalLayout();
        leyenda.setPadding(false);
        leyenda.setSpacing(false);
        leyenda.getStyle().set("gap", "6px");
        for (Franja f : Franja.values()) {
            Span cuadro = new Span();
            cuadro.getStyle().set("display", "inline-block").set("width", "10px").set("height", "10px").set("border-radius", "2px")
                    .set("background", COLORES[f.ordinal()]).set("margin-right", "6px");
            Span texto = new Span(f.name() + "  " + kwh(datos.kwh(f)) + (total > 0
                    ? String.format(Locale.ROOT, "  (%.0f %%)", datos.kwh(f) / total * 100) : ""));
            texto.getStyle().set("font-size", "13px").set("color", "#0b0b0b");
            Div fila = new Div(cuadro, texto);
            leyenda.add(fila);
        }
        return List.of(anillo, leyenda);
    }

    // ================= Histórico =================

    @SuppressWarnings("unchecked")
    private VerticalLayout crearAnual() {
        int actual = LocalDate.now().getYear();
        anioCombo.setItems(actual, actual - 1, actual - 2);
        anioCombo.setValue(actual);
        anioCombo.setWidth("110px");
        anioCombo.addValueChangeListener(e -> {
            if (e.getValue() != null) verAnual();
        });
        Anchor descarga = new Anchor();
        descarga.setText("Descargar CSV");
        descarga.getElement().getThemeList().add("button");
        // Un solo enlace fijo: el archivo y su nombre se arman al pedirlo, con el año que se está viendo.
        descarga.setHref(event -> {
            event.setFileName("consumo-franja-horaria-" + anioMostrado + ".csv");
            event.setContentType("text/csv; charset=UTF-8");
            try (OutputStream out = event.getOutputStream()) {
                out.write(csvAnual(filasAnio).getBytes(StandardCharsets.UTF_8));
            }
        }, AttachmentType.DOWNLOAD);
        Button recalcular = new Button("Recalcular", VaadinIcon.REFRESH.create(), e -> {
            int anio = anioCombo.getValue() == null ? LocalDate.now().getYear() : anioCombo.getValue();
            for (int i = 1; i <= 12; i++) consumo.olvidar(YearMonth.of(anio, i));
            verAnual();
            if (mesCombo.getValue() != null && mesCombo.getValue().getYear() == anio) verDetalle();
            Notification.show("Meses de " + anio + " recalculados", 2500, Notification.Position.BOTTOM_START)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        });
        recalcular.setTooltipText("Vuelve a leer los archivos de energia de todos los meses del anio (por ejemplo, despues de "
                + "copiar datos historicos)");
        HorizontalLayout filtros = new HorizontalLayout(anioCombo, recalcular, descarga);
        filtros.setAlignItems(Alignment.END);

        colMes = mesesGrid.addColumn(f -> nombreMes(f.datos().mes()) + (f.datos().enCurso() ? " (en curso)" : ""))
                .setHeader("Mes").setAutoWidth(true).setFlexGrow(0).setFrozen(true);
        HeaderRow arriba = mesesGrid.prependHeaderRow();
        for (Franja f : Franja.values()) {
            Grid.Column<FilaMes>[] cols = new Grid.Column[3];
            cols[0] = columna(mesesGrid, "kWh", r -> r.datos().conDatos() ? kwhNum(r.datos().kwh(f)) : "-");
            cols[1] = columna(mesesGrid, "Precio facturado", r -> r.datos().conDatos() && r.costo(f) != null ? dinero(r.costo(f)) : "-");
            cols[2] = columna(mesesGrid, "Costo kWh", r -> r.tarifa().sinTarifa() ? "-" : precio(r.tarifa().precio(f)));
            colMesFranja.put(f, cols);
            arriba.join(cols).setText(f.etiqueta());
        }
        colMesTotal = columna(mesesGrid, "kWh", r -> r.datos().conDatos() ? kwhNum(r.datos().total()) : "-");
        colMesCosto = columna(mesesGrid, "Precio facturado", r -> r.datos().conDatos() && r.costoTotal() != null ? dinero(r.costoTotal()) : "-");
        colMesMedio = columna(mesesGrid, "Costo medio kWh", r -> r.datos().conDatos() && r.costoTotal() != null && r.datos().total() > 0
                ? precio(r.costoTotal() / r.datos().total()) : "-");
        arriba.join(colMesTotal, colMesCosto, colMesMedio).setText("Total del mes");
        mesesGrid.addComponentColumn(r -> badgeTarifa(r.tarifa())).setHeader("Tarifa").setAutoWidth(true).setFlexGrow(0);
        mesesGrid.addColumn(r -> lecturas(r.datos())).setHeader("Lecturas").setAutoWidth(true);
        pieMeses = mesesGrid.appendFooterRow();
        mesesGrid.setWidthFull();
        mesesGrid.setAllRowsVisible(true);

        Span nota = nota("Mismo formato que la planilla de la empresa electrica. Precio facturado = kWh x costo kWh de la franja; "
                + "cada mes con su tarifa (en amarillo, los meses que no tienen tarifa propia y usan la del ultimo mes cargado). "
                + "El detalle por dia de un mes se ve en la pestana Detalle del mes. Lecturas: horas estimadas o sin datos del medidor general en el mes "
                + "(por ejemplo, el medidor general tiene datos desde el 08-01-2026). Los feriados cargados cambian los kWh de A/B a C/D. "
                + "Los meses cerrados se guardan en memoria para no releerlos; Recalcular los vuelve a leer de los archivos.");
        VerticalLayout v = new VerticalLayout(descripcion("Consumo y costo por franja horaria de cada mes del anio elegido."),
                filtros, mesesGrid, nota);
        v.setPadding(false);
        v.setWidthFull();
        return v;
    }

    private void verAnual() {
        int anio = anioCombo.getValue() == null ? LocalDate.now().getYear() : anioCombo.getValue();
        List<FilaMes> filas = new ArrayList<>();
        for (Mes m : consumo.anio(anio)) filas.add(new FilaMes(m, TarifaFranjaService.delMes(tarifas, m.mes())));
        filasAnio = filas;
        anioMostrado = anio;
        anualAlDia = true;
        mesesGrid.setItems(filas);

        double[] kwh = new double[Franja.values().length];
        double[] costo = new double[kwh.length];
        boolean costoCompleto = true;
        for (FilaMes r : filas) {
            if (!r.datos().conDatos()) continue;
            for (Franja f : Franja.values()) {
                kwh[f.ordinal()] += r.datos().kwh(f);
                if (r.costo(f) == null) costoCompleto = false;
                else costo[f.ordinal()] += r.costo(f);
            }
        }
        double totalKwh = Arrays.stream(kwh).sum(), totalCosto = Arrays.stream(costo).sum();
        pieMeses.getCell(colMes).setText("Total " + anio + (costoCompleto ? "" : " (costo sin los meses sin tarifa)"));
        for (Franja f : Franja.values()) {
            Grid.Column<FilaMes>[] cols = colMesFranja.get(f);
            pieMeses.getCell(cols[0]).setText(kwhNum(kwh[f.ordinal()]));
            pieMeses.getCell(cols[1]).setText(costo[f.ordinal()] > 0 ? dinero(costo[f.ordinal()]) : "-");
            pieMeses.getCell(cols[2]).setText(kwh[f.ordinal()] > 0 && costo[f.ordinal()] > 0 ? precio(costo[f.ordinal()] / kwh[f.ordinal()]) : "-");
        }
        pieMeses.getCell(colMesTotal).setText(kwhNum(totalKwh));
        pieMeses.getCell(colMesCosto).setText(totalCosto > 0 ? dinero(totalCosto) : "-");
        pieMeses.getCell(colMesMedio).setText(totalKwh > 0 && totalCosto > 0 && costoCompleto ? precio(totalCosto / totalKwh) : "-");
    }

    private static String lecturas(Mes m) {
        if (!m.conDatos()) return "sin lecturas";
        List<String> partes = new ArrayList<>();
        if (m.horasEstimadas() >= 0.05) partes.add(String.format(Locale.ROOT, "%.1f h estimadas", m.horasEstimadas()));
        if (m.horasSinDatos() >= 0.05) partes.add(String.format(Locale.ROOT, "%.1f h sin datos", m.horasSinDatos()));
        return partes.isEmpty() ? "completas" : String.join(", ", partes);
    }

    /** Separador ";" y coma decimal para abrirlo con Excel en español. */
    private static String csvAnual(List<FilaMes> filas) {
        StringBuilder sb = new StringBuilder();
        sb.append('﻿'); // BOM UTF-8 para Excel
        sb.append("Mes");
        for (Franja f : Franja.values()) {
            sb.append(';').append(f.name()).append(" kWh;").append(f.name()).append(" Precio facturado ($);")
                    .append(f.name()).append(" Costo kWh ($/kWh)");
        }
        sb.append(";Total kWh;Total precio facturado ($);Tarifa;Lecturas\r\n");
        for (FilaMes r : filas) {
            Mes m = r.datos();
            sb.append(nombreMes(m.mes())).append(m.enCurso() ? " (en curso)" : "");
            for (Franja f : Franja.values()) {
                sb.append(';').append(m.conDatos() ? String.format(CSV, "%.0f", m.kwh(f)) : "")
                        .append(';').append(m.conDatos() && r.costo(f) != null ? String.format(CSV, "%.2f", r.costo(f)) : "")
                        .append(';').append(r.tarifa().sinTarifa() ? "" : String.format(CSV, "%.4f", r.tarifa().precio(f)));
            }
            sb.append(';').append(m.conDatos() ? String.format(CSV, "%.0f", m.total()) : "")
                    .append(';').append(m.conDatos() && r.costoTotal() != null ? String.format(CSV, "%.2f", r.costoTotal()) : "")
                    .append(';').append(textoTarifa(r.tarifa()))
                    .append(';').append(lecturas(m)).append("\r\n");
        }
        return sb.toString();
    }

    // ================= Tarifas =================

    private VerticalLayout crearTarifas() {
        tarifasGrid.addColumn(t -> nombreMes(t.mes())).setHeader("Mes").setAutoWidth(true).setFlexGrow(0).setFrozen(true);
        for (Franja f : Franja.values()) {
            columna(tarifasGrid, f.etiqueta(), t -> t.sinTarifa() ? "-" : precio(t.precio(f)));
        }
        tarifasGrid.addComponentColumn(ConsumoFranjaView::badgeTarifa).setHeader("Estado").setAutoWidth(true).setFlexGrow(0);
        tarifasGrid.addColumn(t -> t.propia() && t.tarifa().getObservacion() != null ? t.tarifa().getObservacion() : "")
                .setHeader("Observacion").setAutoWidth(true);
        tarifasGrid.addColumn(t -> !t.propia() ? "" : (t.tarifa().getUsuario() == null ? "" : t.tarifa().getUsuario() + " ")
                + t.tarifa().getFechaRegistro().format(CORTA)).setHeader("Cargada por").setAutoWidth(true).setFlexGrow(0);
        tarifasGrid.setWidthFull();
        tarifasGrid.setAllRowsVisible(true);

        VerticalLayout v = new VerticalLayout(descripcion("Precio del kWh de cada franja, mes por mes. Cada mes guarda su propia tarifa: "
                + "cambiar un mes no afecta a los demas."));
        v.setPadding(false);
        v.setWidthFull();
        if (admin) {
            Button cargar = new Button("Cargar tarifa de un mes", VaadinIcon.PLUS.create(), e -> abrirDialogoTarifa(YearMonth.now()));
            v.add(cargar);
            tarifasGrid.addItemClickListener(e -> abrirDialogoTarifa(e.getItem().mes()));
        }
        v.add(tarifasGrid, nota("Verde = el mes tiene su tarifa. Amarillo = el mes no tiene tarifa propia y se calcula con la del "
                + "ultimo mes cargado antes (al guardarla queda como propia). Gris = no hay ninguna tarifa cargada hasta ese mes."
                + (admin ? " Clic en un mes para cargar o corregir su tarifa." : " Solo el administrador carga las tarifas.")));
        return v;
    }

    private void verTarifas() {
        YearMonth hasta = YearMonth.now();
        YearMonth desde = YearMonth.of(hasta.getYear(), 1);
        if (!tarifas.isEmpty()) {
            YearMonth ultima = YearMonth.from(tarifas.get(0).getMes());
            YearMonth primera = YearMonth.from(tarifas.get(tarifas.size() - 1).getMes());
            if (ultima.isAfter(hasta)) hasta = ultima;
            if (primera.isBefore(desde)) desde = primera;
        }
        List<TarifaDelMes> filas = new ArrayList<>();
        for (YearMonth m = hasta; !m.isBefore(desde); m = m.minusMonths(1)) filas.add(TarifaFranjaService.delMes(tarifas, m));
        tarifasGrid.setItems(filas);
    }

    private void abrirDialogoTarifa(YearMonth mesInicial) {
        Dialog d = new Dialog();
        d.setHeaderTitle("Tarifa por franja horaria");
        ComboBox<YearMonth> mes = new ComboBox<>("Mes");
        List<YearMonth> meses = new ArrayList<>();
        for (YearMonth m = YearMonth.now().plusMonths(12); meses.size() < 60; m = m.minusMonths(1)) meses.add(m);
        mes.setItems(meses);
        mes.setItemLabelGenerator(ConsumoFranjaView::nombreMes);
        mes.setWidthFull();
        Span estado = new Span();
        estado.getStyle().set("font-size", "12px").set("color", "#555");
        Map<Franja, NumberField> campos = new EnumMap<>(Franja.class);
        VerticalLayout contenido = new VerticalLayout(mes, estado);
        for (Franja f : Franja.values()) {
            NumberField n = new NumberField(f.etiqueta() + " ($/kWh)");
            n.setMin(0.0001);
            n.setMax(TarifaFranjaService.PRECIO_MAXIMO);
            n.setStep(0.0001);
            n.setWidthFull();
            campos.put(f, n);
            contenido.add(n);
        }
        TextField observacion = new TextField("Observacion (opcional)");
        observacion.setMaxLength(255);
        observacion.setPlaceholder("ej. planilla de la empresa electrica");
        observacion.setWidthFull();
        contenido.add(observacion);
        contenido.setPadding(false);
        contenido.setWidth("380px");
        d.add(contenido);

        Button borrar = new Button("Borrar", VaadinIcon.TRASH.create());
        borrar.addThemeVariants(ButtonVariant.LUMO_ERROR);
        Runnable cargar = () -> {
            YearMonth m = mes.getValue();
            if (m == null) return;
            TarifaDelMes t = TarifaFranjaService.delMes(tarifas, m);
            for (Franja f : Franja.values()) campos.get(f).setValue(t.precio(f));
            observacion.setValue(t.propia() && t.tarifa().getObservacion() != null ? t.tarifa().getObservacion() : "");
            estado.setText(t.sinTarifa() ? "No hay tarifas cargadas hasta este mes."
                    : t.propia() ? "Este mes ya tiene su tarifa: al guardar se reemplaza."
                    : "Este mes no tiene tarifa propia: los valores son los de " + nombreMes(t.origen()) + ". Al guardar quedan como propios.");
            borrar.setVisible(t.propia());
        };
        mes.addValueChangeListener(e -> cargar.run());
        mes.setValue(mesInicial);

        Button copiar = new Button("Copiar del mes anterior", VaadinIcon.COPY.create(), e -> {
            if (mes.getValue() == null) return;
            TarifaDelMes anterior = TarifaFranjaService.delMes(tarifas, mes.getValue().minusMonths(1));
            if (anterior.sinTarifa()) {
                Notification.show("No hay tarifa cargada antes de este mes", 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            for (Franja f : Franja.values()) campos.get(f).setValue(anterior.precio(f));
            estado.setText("Valores copiados de " + nombreMes(anterior.origen()) + ". Falta Guardar.");
        });
        contenido.addComponentAtIndex(2, copiar);

        Button guardar = new Button("Guardar", e -> {
            Map<Franja, Double> precios = new EnumMap<>(Franja.class);
            for (Franja f : Franja.values()) {
                NumberField n = campos.get(f);
                if (n.getValue() == null || n.isInvalid()) {
                    Notification.show("Ingrese el precio de la franja " + f.name(), 3000, Notification.Position.MIDDLE)
                            .addThemeVariants(NotificationVariant.LUMO_ERROR);
                    return;
                }
                precios.put(f, n.getValue());
            }
            if (mes.getValue() == null) {
                Notification.show("Elija el mes", 3000, Notification.Position.MIDDLE).addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            try {
                tarifaService.guardar(mes.getValue(), precios, observacion.getValue(), acceso.usuarioActual());
                d.close();
                refrescar();
                Notification.show("Tarifa de " + nombreMes(mes.getValue()) + " guardada", 2500, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } catch (IllegalArgumentException ex) {
                Notification.show(ex.getMessage(), 4000, Notification.Position.MIDDLE).addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        guardar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        borrar.addClickListener(e -> {
            TarifaDelMes t = TarifaFranjaService.delMes(tarifas, mes.getValue());
            if (!t.propia()) return;
            tarifaService.borrar(t.tarifa());
            d.close();
            refrescar();
        });
        d.getFooter().add(borrar, new Button("Cancelar", e -> d.close()), guardar);
        d.open();
    }

    // ================= Feriados =================

    private VerticalLayout crearFeriados() {
        feriadosGrid.addColumn(f -> f.getFecha().format(FECHA)).setHeader("Fecha").setAutoWidth(true).setFlexGrow(0);
        feriadosGrid.addColumn(f -> DIAS[f.getFecha().getDayOfWeek().getValue() - 1]).setHeader("Dia").setAutoWidth(true).setFlexGrow(0);
        feriadosGrid.addColumn(Feriado::getNombre).setHeader("Nombre").setAutoWidth(true);
        feriadosGrid.addComponentColumn(f -> {
            Span s = new Span(Franja.finDeSemana(f.getFecha()) ? "Cae en fin de semana: no cambia nada" : "Cuenta como S, D y F");
            estiloBadge(s, Franja.finDeSemana(f.getFecha()) ? GRIS : VERDE);
            return s;
        }).setHeader("Efecto").setAutoWidth(true).setFlexGrow(0);
        feriadosGrid.addColumn(f -> (f.getUsuario() == null ? "" : f.getUsuario() + " ") + f.getFechaRegistro().format(CORTA))
                .setHeader("Cargado por").setAutoWidth(true).setFlexGrow(0);
        if (admin) {
            feriadosGrid.addComponentColumn(f -> {
                Button b = new Button(VaadinIcon.TRASH.create(), e -> {
                    feriadoService.borrar(f);
                    refrescar();
                });
                b.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
                b.setTooltipText("Quitar este feriado");
                return b;
            }).setHeader("").setAutoWidth(true).setFlexGrow(0);
        }
        feriadosGrid.setWidthFull();
        feriadosGrid.setAllRowsVisible(true);

        VerticalLayout v = new VerticalLayout(descripcion("Opcional. Los feriados que caen de lunes a viernes se cobran como sabado o "
                + "domingo: ese dia no hay franjas A ni B, todo va a C y D. Si no se carga ninguno, la pantalla funciona igual."));
        v.setPadding(false);
        v.setWidthFull();
        if (admin) {
            DatePicker fecha = new DatePicker("Fecha");
            fecha.setLocale(CSV);
            TextField nombre = new TextField("Nombre");
            nombre.setMaxLength(120);
            nombre.setPlaceholder("ej. Dia del Trabajo");
            nombre.setWidth("280px");
            Button agregar = new Button("Agregar", VaadinIcon.PLUS.create(), e -> {
                try {
                    feriadoService.guardar(fecha.getValue(), nombre.getValue(), acceso.usuarioActual());
                    fecha.clear();
                    nombre.clear();
                    refrescar();
                } catch (IllegalArgumentException ex) {
                    Notification.show(ex.getMessage(), 3000, Notification.Position.MIDDLE).addThemeVariants(NotificationVariant.LUMO_ERROR);
                }
            });
            agregar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            HorizontalLayout form = new HorizontalLayout(fecha, nombre, agregar);
            form.setAlignItems(Alignment.END);
            form.getStyle().set("flex-wrap", "wrap");
            v.add(form);
        }
        v.add(feriadosGrid, nota("Cargar solo los dias que la empresa cobra como feriado, no todos los del calendario. Ejemplo "
                + "2026: la factura de mayo coincide con el 1 de mayo como feriado; la de abril, sin feriados (el Viernes Santo se "
                + "cobro como dia normal). Si una fecha ya estaba cargada, Agregar le cambia el nombre."
                + (admin ? "" : " Solo el administrador carga los feriados.")));
        return v;
    }

    private void verFeriados() {
        feriadosGrid.setItems(feriadoService.listar());
    }

    // ================= Utilidades =================

    private static final String[] VERDE = {"#d4edda", "#155724"};
    private static final String[] AMARILLO = {"#fff3cd", "#856404"};
    private static final String[] GRIS = {"#e2e3e5", "#383d41"};

    private static Span badgeTarifa(TarifaDelMes t) {
        Span s = new Span(textoTarifa(t));
        estiloBadge(s, t.sinTarifa() ? GRIS : t.propia() ? VERDE : AMARILLO);
        return s;
    }

    private static String textoTarifa(TarifaDelMes t) {
        if (t.sinTarifa()) return "Sin tarifa";
        return t.propia() ? "Propia" : "Usa la de " + nombreMes(t.origen());
    }

    private static void estiloBadge(Span s, String[] c) {
        s.getStyle().set("background-color", c[0]).set("color", c[1]).set("padding", "2px 8px").set("border-radius", "10px")
                .set("font-size", "12px").set("font-weight", "600").set("white-space", "nowrap");
    }

    private static String nombreMes(YearMonth m) {
        return MESES[m.getMonthValue() - 1] + " " + m.getYear();
    }

    private static <T> Grid.Column<T> columna(Grid<T> g, String titulo, Function<T, String> valor) {
        return g.addColumn(valor::apply).setHeader(titulo).setAutoWidth(true).setFlexGrow(0).setTextAlign(ColumnTextAlign.END);
    }

    private static Component tarjeta(String titulo, String valor, String detalle) {
        Span t = new Span(titulo);
        t.getStyle().set("font-size", "12px").set("color", "#52514e");
        Span v = new Span(valor);
        v.getStyle().set("font-size", "22px").set("font-weight", "700").set("color", "#0b0b0b");
        VerticalLayout c = new VerticalLayout(t, v);
        if (detalle != null) {
            Span d = new Span(detalle);
            d.getStyle().set("font-size", "11px").set("color", "#777");
            c.add(d);
        }
        c.setSpacing(false);
        c.setPadding(false);
        c.setWidth("210px");
        c.getStyle().set("border", "1px solid #ddd").set("border-radius", "8px").set("padding", "10px 14px");
        return c;
    }

    /** Tarjeta de una franja: borde izquierdo del color de la franja (el mismo de la dona y las barras). */
    private static Component tarjetaFranja(Franja f, String valor, String detalle) {
        Component c = tarjeta("Franja " + f.name() + " (" + f.horario() + ")", valor, detalle);
        c.getElement().getStyle().set("border-left", "5px solid " + COLORES[f.ordinal()]);
        return c;
    }

    private static Span nota(String texto) {
        Span s = new Span(texto);
        s.getStyle().set("font-size", "12px").set("color", "#555");
        return s;
    }

    /** Qué muestra la pestaña, en una línea, arriba de todo. */
    private static Span descripcion(String texto) {
        Span s = new Span(texto);
        s.getStyle().set("font-size", "14px").set("color", "#0b0b0b").set("background-color", "#f3f4f6")
                .set("padding", "8px 12px").set("border-radius", "6px").set("display", "block");
        return s;
    }

    private static String kwh(double v) {
        return String.format(Locale.ROOT, "%,.0f kWh", v);
    }

    private static String kwhNum(double v) {
        return String.format(Locale.ROOT, "%,.0f", v);
    }

    private static String dinero(double v) {
        return String.format(Locale.ROOT, "$ %,.2f", v);
    }

    private static String precio(double v) {
        return String.format(Locale.ROOT, "$ %.4f", v);
    }

    private static String horasSiHay(double h) {
        return h >= 0.05 ? String.format(Locale.ROOT, "%.1f", h) : "";
    }
}
