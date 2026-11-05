package com.example.generador.ui;

import com.example.base.ui.CsvUtil;
import com.example.base.ui.MainLayout;
import com.example.generador.model.Generador;
import com.example.generador.model.RegistroMf13;
import com.example.generador.service.RegistroMf13Service;
import com.example.generador.service.RegistroMf13Service.Consumo;
import com.example.generador.service.RegistroMf13Service.Controlador;
import com.example.generador.service.RegistroMf13Service.FilaMes;
import com.example.generador.service.RegistroMf13Service.Marcha;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Control de generadores (formato MF-13): pestaña Registro (chequeos e inspección, mismas columnas que la planilla de
 * los operadores) y pestaña Consumo y rendimiento (diésel y kWh por mes, por generador y total, períodos de marcha y
 * observaciones). Ver {@link RegistroMf13Service}.
 */
@PageTitle("Control MF-13 | LineaBase")
@Route(value = "generador/mf13", layout = MainLayout.class)
@PermitAll
public class ControlMf13View extends VerticalLayout implements BeforeEnterObserver {

    private static final Locale ES = Locale.forLanguageTag("es-EC");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DIA_HORA = DateTimeFormatter.ofPattern("dd-MM HH:mm");
    private static final String[] COLORES = {"#F29F67", "#6F42C1", "#8E95A3", "#34B1AA"};

    private final RegistroMf13Service svc;
    private final LineaAccessService acceso;
    private final List<Generador> generadores;

    private String genSel;
    private YearMonth mesSel = YearMonth.now();

    // Registro
    private final HorizontalLayout botonesGen = new HorizontalLayout();
    private final ComboBox<YearMonth> mesCombo = new ComboBox<>("Mes");
    private final Grid<RegistroMf13> grid = new Grid<>(RegistroMf13.class, false);
    private final Span tituloGrid = new Span();
    private final Div descarga = new Div();
    private final DateTimePicker fecha = new DateTimePicker("Fecha y hora");
    private final ComboBox<String> responsable = new ComboBox<>("Responsable");
    private final ComboBox<String> momento = new ComboBox<>("Momento");
    private final ComboBox<String> modo = new ComboBox<>("Modo");
    private final ComboBox<String> nivLub = chequeo("Niv. lubricante");
    private final ComboBox<String> nivAgua = chequeo("Niv. agua");
    private final ComboBox<String> precalentador = chequeo("Precalentador");
    private final ComboBox<String> filtros = chequeo("Filtros");
    private final ComboBox<String> correas = chequeo("Correas");
    private final NumberField bateria = numero("Batería (V)");
    private final NumberField nivel = numero("Nivel del tanque (cm)");
    private final NumberField contador = numero("Contador de diésel (gal)");
    private final NumberField cargado = numero("Diésel cargado (gal)");
    private final NumberField presAceite = numero("Pres. aceite (bar)");
    private final NumberField temperatura = numero("Temperatura (°C)");
    private final NumberField cargaKw = numero("Carga (kW)");
    private final NumberField prodKwh = numero("Prod. kWh");
    private final NumberField horas = numero("Horas");
    private final Span estadoControlador = new Span();
    private boolean leidoDelControlador;
    private final Checkbox obsCarga = new Checkbox("Carga de diésel");
    private final Checkbox obsAceite = new Checkbox("Completa aceite");
    private final Checkbox obsRefrigerante = new Checkbox("Completa refrigerante");
    private final Checkbox obsAutomatico = new Checkbox("Queda en automático");
    private final Checkbox obsFalla = new Checkbox("Falla o aviso");
    private final TextField detalle = new TextField("Detalle");

    // Consumo
    private final ComboBox<Integer> anioCombo = new ComboBox<>("Año");
    private final Div tablaAnual = new Div();
    private final Div graficosAnuales = new Div();
    private final Div notasAnuales = new Div();
    private final ComboBox<YearMonth> mesConsumo = new ComboBox<>("Mes");
    private final Div detalleMes = new Div();

    public ControlMf13View(RegistroMf13Service svc, LineaAccessService acceso) {
        this.svc = svc;
        this.acceso = acceso;
        // Transformador 1 primero
        this.generadores = svc.generadores().stream()
                .sorted(Comparator.comparing(g -> g.redAsociada() == null ? "~" : g.redAsociada())).toList();
        this.genSel = generadores.isEmpty() ? null : generadores.get(0).nombre();
        setSizeFull();
        setPadding(true);
        TabSheet tabs = new TabSheet();
        tabs.setSizeFull();
        tabs.add("Registro MF-13", crearRegistro());
        tabs.add("Consumo y rendimiento", crearConsumo());
        add(tabs);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        AccesoGeneradores.verificar(event, acceso);
    }

    // ================================================================== Registro

    private Component crearRegistro() {
        VerticalLayout v = new VerticalLayout();
        v.setPadding(false);
        dibujarBotonesGen();
        List<YearMonth> meses = new ArrayList<>();
        for (int i = 0; i < 24; i++) meses.add(YearMonth.now().minusMonths(i));
        mesCombo.setItems(meses);
        mesCombo.setItemLabelGenerator(ControlMf13View::nombreMes);
        mesCombo.setValue(mesSel);
        mesCombo.setWidth("190px");
        mesCombo.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                mesSel = e.getValue();
                refrescarGrid();
            }
        });
        HorizontalLayout barra = new HorizontalLayout(botonesGen, mesCombo);
        barra.setAlignItems(FlexComponent.Alignment.END);
        barra.getStyle().set("flex-wrap", "wrap");
        if (acceso.esAdmin()) barra.add(botonImportar());
        barra.add(descarga);
        v.add(barra);
        if (acceso.puedeRegistrarMf13()) v.add(crearFormulario());
        tituloGrid.getStyle().set("font-weight", "600");
        Span ayuda = new Span("Mismas columnas que el formato MF-13. En rojo, lecturas que no siguen la secuencia del contador: "
                + "no se usan en los cálculos. En gris, los valores tomados del controlador.");
        ayuda.getStyle().set("font-size", "12px").set("color", "#6b7080");
        configurarGrid();
        v.add(tituloGrid, ayuda, grid);
        v.setFlexGrow(1, grid);
        refrescarGrid();
        return v;
    }

    private void dibujarBotonesGen() {
        botonesGen.removeAll();
        for (int i = 0; i < generadores.size(); i++) {
            Generador g = generadores.get(i);
            Button b = new Button(g.nombre() + (g.redAsociada() == null ? "" : " · " + nombreTrafo(g.redAsociada())));
            b.getElement().setProperty("innerHTML", "<span style=\"display:inline-block;width:10px;height:10px;border-radius:50%;background:"
                    + COLORES[i % COLORES.length] + ";margin-right:6px\"></span>" + esc(b.getText()));
            if (g.nombre().equals(genSel)) b.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            b.addClickListener(e -> {
                genSel = g.nombre();
                dibujarBotonesGen();
                contador.setVisible(svc.usaContador(genSel));
                limpiarControlador();
                refrescarGrid();
            });
            botonesGen.add(b);
        }
    }

    private Component crearFormulario() {
        fecha.setValue(LocalDateTime.now().withSecond(0).withNano(0));
        fecha.setStep(java.time.Duration.ofMinutes(1));
        responsable.setItems(svc.tecnicos());
        responsable.setWidth("230px");
        momento.setItems(RegistroMf13.MOMENTOS);
        momento.setWidth("220px");
        modo.setItems(RegistroMf13.MODOS);
        modo.setWidth("170px");
        HorizontalLayout fila1 = fila(fecha, responsable, momento, modo);
        HorizontalLayout chequeos = fila(nivLub, nivAgua, precalentador, filtros, correas, bateria);
        HorizontalLayout combustible = fila(nivel, contador, cargado);
        contador.setVisible(svc.usaContador(genSel));
        cargado.setHelperText("Obligatorio si el nivel sube");
        Button leer = new Button("Leer controlador", VaadinIcon.REFRESH.create(), e -> leerControlador());
        estadoControlador.getStyle().set("font-size", "12px");
        leer.setTooltipText("Carga los valores que da el controlador y reemplaza los anotados a mano; lo que no da queda como está");
        HorizontalLayout ctrl = fila(presAceite, temperatura, cargaKw, prodKwh, horas, leer);
        detalle.setWidthFull();
        detalle.setMaxLength(500);
        detalle.setPlaceholder("Texto libre, ej.: se observa fuga de aceite, se completan 2 gal de refrigerante");
        HorizontalLayout obs = fila(obsCarga, obsAceite, obsRefrigerante, obsAutomatico, obsFalla);
        Button guardar = new Button("Guardar registro", e -> guardar());
        guardar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        VerticalLayout f = new VerticalLayout(new H4("Nuevo registro"), fila1,
                grupo("Chequeos preliminares", chequeos), grupo("Combustible", combustible),
                grupo("Del controlador (anótelos a mano o use Leer controlador)", new VerticalLayout(ctrl, estadoControlador)),
                grupo("Observaciones", new VerticalLayout(obs, detalle)), guardar);
        f.setPadding(true);
        f.setSpacing(false);
        f.getStyle().set("background", "#fff").set("border", "1px solid #E4E6EC").set("border-radius", "12px");
        return f;
    }

    private static HorizontalLayout fila(Component... c) {
        HorizontalLayout h = new HorizontalLayout(c);
        h.setAlignItems(FlexComponent.Alignment.END);
        h.getStyle().set("flex-wrap", "wrap");
        return h;
    }

    private static Component grupo(String titulo, Component contenido) {
        Span t = new Span(titulo);
        t.getStyle().set("font-size", "12px").set("font-weight", "700").set("color", "#3a3f4c").set("text-transform", "uppercase");
        VerticalLayout g = new VerticalLayout(t, contenido);
        g.setPadding(false);
        g.setSpacing(false);
        g.getStyle().set("margin-top", "8px");
        return g;
    }

    private static ComboBox<String> chequeo(String nombre) {
        ComboBox<String> c = new ComboBox<>(nombre);
        c.setItems(RegistroMf13.CHEQUEO);
        c.setValue("√");
        c.setWidth("110px");
        return c;
    }

    private static NumberField numero(String nombre) {
        NumberField n = new NumberField(nombre);
        n.setWidth("150px");
        return n;
    }

    /** Valores tal como los dio el controlador en la última lectura (para saber cuáles se cambiaron a mano). */
    private final Map<NumberField, Double> leidos = new java.util.HashMap<>();

    private void limpiarControlador() {
        leidoDelControlador = false;
        leidos.clear();
        for (NumberField n : List.of(presAceite, temperatura, cargaKw, prodKwh, horas)) n.clear();
        estadoControlador.setText("");
    }

    /**
     * Por defecto los valores se anotan a mano; este botón los lee del controlador y reemplaza lo anotado en los que el
     * controlador da. Los que no da (el Caterpillar no publica presión de aceite ni temperatura) quedan como estaban.
     */
    private void leerControlador() {
        Controlador c = svc.leerControlador(genSel);
        if (c.error() != null) {
            estadoControlador.setText(c.error() + ". Anote los valores de la pantalla del controlador.");
            estadoControlador.getStyle().set("color", "#9b1c26");
            return;
        }
        List<String> noDa = new ArrayList<>();
        poner(presAceite, c.presAceite(), noDa);
        poner(temperatura, c.temperatura(), noDa);
        poner(cargaKw, c.cargaKw(), noDa);
        poner(prodKwh, c.prodKwh(), noDa);
        poner(horas, c.horas(), noDa);
        if (c.bateria() != null) bateria.setValue(c.bateria());
        leidoDelControlador = !leidos.isEmpty();
        estadoControlador.setText("Leído del controlador a las " + LocalDateTime.now().format(HORA)
                + (noDa.isEmpty() ? "" : ". El controlador no da " + String.join(", ", noDa).toLowerCase(ES) + ": anótelos a mano."));
        estadoControlador.getStyle().set("color", noDa.isEmpty() ? "#155724" : "#856404");
    }

    private void poner(NumberField campo, Double valor, List<String> noDa) {
        if (valor == null) {
            leidos.remove(campo);
            noDa.add(nombreCampo(campo));
        } else {
            campo.setValue(valor);
            leidos.put(campo, valor);
        }
    }

    private static String nombreCampo(NumberField n) {
        return n.getLabel().replaceAll(" \\(.*\\)", "");
    }

    /** Valores anotados a mano: con dato y distinto de lo que dio el controlador (o sin lectura). */
    private String camposAMano() {
        List<String> l = new ArrayList<>();
        for (NumberField n : List.of(presAceite, temperatura, cargaKw, prodKwh, horas)) {
            if (n.getValue() != null && !n.getValue().equals(leidos.get(n))) l.add(nombreCampo(n));
        }
        return l.isEmpty() ? null : String.join(", ", l);
    }

    private void guardar() {
        if (!acceso.puedeRegistrarMf13()) return;
        RegistroMf13 r = new RegistroMf13();
        r.setGenerador(genSel);
        r.setFechaHora(fecha.getValue());
        r.setResponsable(responsable.getValue());
        r.setMomento(momento.getValue());
        r.setModo(modo.getValue());
        r.setNivLubricante(nivLub.getValue());
        r.setNivAgua(nivAgua.getValue());
        r.setPrecalentador(precalentador.getValue());
        r.setFiltros(filtros.getValue());
        r.setCorreas(correas.getValue());
        r.setBateria(bateria.getValue());
        r.setNivelCm(nivel.getValue());
        r.setContadorDiesel(contador.isVisible() ? contador.getValue() : null);
        r.setDieselCargado(cargado.getValue());
        r.setPresAceite(presAceite.getValue());
        r.setTemperatura(temperatura.getValue());
        r.setCargaKw(cargaKw.getValue());
        r.setProdKwh(prodKwh.getValue());
        r.setHoras(horas.getValue());
        r.setValoresDelControlador(leidoDelControlador && camposAMano() == null
                || leidos.entrySet().stream().anyMatch(e -> e.getValue().equals(e.getKey().getValue())));
        r.setCamposAMano(camposAMano());
        r.setObsCargaDiesel(obsCarga.getValue());
        r.setObsAceite(obsAceite.getValue());
        r.setObsRefrigerante(obsRefrigerante.getValue());
        r.setObsAutomatico(obsAutomatico.getValue());
        r.setObsFalla(obsFalla.getValue());
        r.setObservacion(detalle.getValue() == null || detalle.getValue().isBlank() ? null : detalle.getValue().trim());
        List<String> errores = svc.validar(r);
        if (!errores.isEmpty()) {
            Notification n = Notification.show(String.join(" ", errores), 7000, Notification.Position.MIDDLE);
            n.addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }
        svc.guardar(r, acceso.usuarioActual());
        Notification.show("Registro guardado", 2500, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        nivel.clear();
        contador.clear();
        cargado.clear();
        detalle.clear();
        for (Checkbox c : List.of(obsCarga, obsAceite, obsRefrigerante, obsAutomatico, obsFalla)) c.setValue(false);
        limpiarControlador();
        mesSel = YearMonth.from(r.getFechaHora());
        mesCombo.setValue(mesSel);
        refrescarGrid();
    }

    private Set<Long> malKwh = new HashSet<>(), malContador = new HashSet<>();

    private void configurarGrid() {
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grid.setHeight("560px");
        grid.addColumn(r -> r.getResponsable() == null ? "" : r.getResponsable()).setHeader("Responsable").setAutoWidth(true);
        grid.addColumn(r -> r.getFechaHora().format(FECHA)).setHeader("Fecha").setAutoWidth(true);
        grid.addColumn(RegistroMf13::getNivLubricante).setHeader("Niv lub").setAutoWidth(true);
        grid.addColumn(r -> f(r.getNivelCm(), 1)).setHeader("Niv comb (cm)").setAutoWidth(true);
        grid.addColumn(RegistroMf13::getNivAgua).setHeader("Niv agu").setAutoWidth(true);
        grid.addColumn(RegistroMf13::getPrecalentador).setHeader("Precale").setAutoWidth(true);
        grid.addColumn(RegistroMf13::getFiltros).setHeader("Filtros").setAutoWidth(true);
        grid.addColumn(RegistroMf13::getCorreas).setHeader("Correas").setAutoWidth(true);
        grid.addColumn(r -> f(r.getBateria(), 1)).setHeader("Bat (V)").setAutoWidth(true);
        grid.addColumn(r -> RegistroMf13.PARADA.equals(r.getMomento()) ? "" : r.getFechaHora().format(HORA)).setHeader("Hora ini").setAutoWidth(true);
        grid.addColumn(r -> RegistroMf13.PARADA.equals(r.getMomento()) ? r.getFechaHora().format(HORA) : "").setHeader("Hora fin").setAutoWidth(true);
        grid.addComponentColumn(r -> celda(f(r.getPresAceite(), 2), false, r.delControlador("Pres. aceite"))).setHeader("Pres. oil").setAutoWidth(true);
        grid.addComponentColumn(r -> celda(f(r.getTemperatura(), 0), false, r.delControlador("Temperatura"))).setHeader("Temp").setAutoWidth(true);
        grid.addComponentColumn(r -> celda(f(r.getCargaKw(), 0), false, r.delControlador("Carga"))).setHeader("Carga kW").setAutoWidth(true);
        grid.addComponentColumn(r -> celda(f(r.getContadorDiesel(), 0), malContador.contains(r.getId()), false)).setHeader("Diésel gl").setAutoWidth(true)
                .setKey("contador");
        grid.addColumn(r -> r.getDieselCargado() == null ? "" : f(r.getDieselCargado(), 1) + (r.isDieselCargadoEstimado() ? " (estimado)" : ""))
                .setHeader("Diésel cargado").setAutoWidth(true);
        grid.addComponentColumn(r -> celda(f(r.getProdKwh(), 0), malKwh.contains(r.getId()), r.delControlador("Prod. kWh"))).setHeader("Prod kWh").setAutoWidth(true);
        grid.addComponentColumn(r -> celda(f(r.getHoras(), 1), false, r.delControlador("Horas"))).setHeader("Horas").setAutoWidth(true);
        grid.addComponentColumn(r -> {
            Span s = new Span();
            s.getElement().setProperty("innerHTML", etiquetas(r) + " " + esc(r.getObservacion() == null ? "" : r.getObservacion()));
            s.getStyle().set("white-space", "normal").set("font-size", "12px");
            s.setTitle((r.getMomento() == null ? "" : r.getMomento()) + (r.getModo() == null ? "" : " · " + r.getModo())
                    + " · " + (r.getOrigen() == null ? "" : r.getOrigen()) + (r.getUsuario() == null ? "" : " · cargado por " + r.getUsuario()));
            return s;
        }).setHeader("Observaciones").setWidth("360px").setFlexGrow(1);
        if (acceso.esAdmin()) {
            grid.addComponentColumn(r -> {
                Button b = new Button(VaadinIcon.TRASH.create(), e -> confirmarBorrar(r));
                b.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ERROR);
                b.setTooltipText("Borrar este registro");
                return b;
            }).setHeader("").setAutoWidth(true);
        }
    }

    private static Span celda(String texto, boolean mala, boolean delControlador) {
        Span s = new Span(texto);
        if (mala) s.getStyle().set("background", "#fde8ea").set("color", "#9b1c26").set("font-weight", "600").set("padding", "0 4px");
        else if (delControlador) s.getStyle().set("background", "#f1f3f7").set("padding", "0 4px");
        return s;
    }

    private void refrescarGrid() {
        if (genSel == null) return;
        List<RegistroMf13> rs = svc.registros(genSel, mesSel);
        malKwh = invalidos(rs, RegistroMf13::getProdKwh);
        malContador = invalidos(rs, RegistroMf13::getContadorDiesel);
        grid.getColumnByKey("contador").setVisible(svc.usaContador(genSel));
        grid.setItems(rs);
        tituloGrid.setText("Registros de " + nombreMes(mesSel) + " · " + genSel + " (" + rs.size() + ")");
        descarga.removeAll();
        descarga.add(CsvUtil.crearLinkDescarga("MF-13_" + genSel + "_" + mesSel + ".csv", () -> csv(rs), "Descargar CSV del mes"));
    }

    private static Set<Long> invalidos(List<RegistroMf13> rs, Function<RegistroMf13, Double> campo) {
        Set<Long> ok = new HashSet<>();
        RegistroMf13Service.validos(rs, campo).forEach(r -> ok.add(r.getId()));
        Set<Long> mal = new HashSet<>();
        rs.stream().filter(r -> campo.apply(r) != null && !ok.contains(r.getId())).forEach(r -> mal.add(r.getId()));
        return mal;
    }

    private ByteArrayInputStream csv(List<RegistroMf13> rs) {
        StringBuilder s = new StringBuilder("﻿Responsable;Fecha;Momento;Modo;Niv lub;Niv comb (cm);Niv agu;Precale;Filtros;Correas;Bat;"
                + "Hora ini;Hora fin;Pres oil;Temp;Carga kW;Diesel gl;Diesel cargado;Cargado estimado;Prod kWh;Horas;Observaciones;Origen\n");
        for (RegistroMf13 r : rs) {
            boolean parada = RegistroMf13.PARADA.equals(r.getMomento());
            List<String> c = List.of(t(r.getResponsable()), r.getFechaHora().format(FECHA), t(r.getMomento()), t(r.getModo()),
                    t(r.getNivLubricante()), f(r.getNivelCm(), 1), t(r.getNivAgua()), t(r.getPrecalentador()), t(r.getFiltros()),
                    t(r.getCorreas()), f(r.getBateria(), 1), parada ? "" : r.getFechaHora().format(HORA), parada ? r.getFechaHora().format(HORA) : "",
                    f(r.getPresAceite(), 2), f(r.getTemperatura(), 0), f(r.getCargaKw(), 0), f(r.getContadorDiesel(), 0),
                    f(r.getDieselCargado(), 1), r.isDieselCargadoEstimado() ? "Sí" : "", f(r.getProdKwh(), 0), f(r.getHoras(), 1),
                    t(r.getObservacion()), t(r.getOrigen()));
            s.append(String.join(";", c.stream().map(CsvUtil::escape).toList())).append('\n');
        }
        return new ByteArrayInputStream(s.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String t(String s) {
        return s == null ? "" : s;
    }

    private void confirmarBorrar(RegistroMf13 r) {
        if (!acceso.esAdmin()) return;
        Dialog d = new Dialog();
        d.setHeaderTitle("Borrar registro");
        d.add(new Span("¿Borrar el registro de " + r.getGenerador() + " del " + r.getFechaHora().format(DIA_HORA) + "?"));
        Button si = new Button("Borrar", e -> {
            svc.borrar(r);
            d.close();
            refrescarGrid();
        });
        si.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);
        d.getFooter().add(new Button("Cancelar", e -> d.close()), si);
        d.open();
    }

    /** El .xlsx se lee en el navegador y llega en base64 (sin el componente Upload, que no está en el bundle). */
    private Component botonImportar() {
        Button b = new Button("Importar planilla MF-13", VaadinIcon.FILE_TABLE.create());
        b.setTooltipText("Importa del .xlsx de los operadores los registros del mes elegido (más el último del mes anterior como referencia)");
        b.getElement().executeJs("""
                const vista = $0;
                const input = document.createElement('input');
                input.type = 'file';
                input.accept = '.xlsx';
                input.style.display = 'none';
                this.after(input);
                this.addEventListener('click', () => { input.value = ''; input.click(); });
                input.addEventListener('change', () => {
                  const f = input.files[0];
                  if (!f) return;
                  const r = new FileReader();
                  r.onload = () => vista.$server.recibirPlanilla(f.name, String(r.result).split(',')[1] || '');
                  r.readAsDataURL(f);
                });""", getElement());
        return b;
    }

    @ClientCallable
    public void recibirPlanilla(String nombre, String base64) {
        if (!acceso.esAdmin()) return;
        try {
            RegistroMf13Service.ResultadoImportacion r = svc.importar(Base64.getDecoder().decode(base64), nombre, mesSel, acceso.usuarioActual());
            StringBuilder s = new StringBuilder("Importado de " + nombre + " (" + nombreMes(mesSel) + "): ");
            r.importados().forEach((g, n) -> s.append(g).append(" ").append(n).append(" registros; "));
            if (r.repetidos() > 0) s.append(r.repetidos()).append(" ya estaban; ");
            if (r.estimadas() > 0) s.append(r.estimadas()).append(" cargas de diésel sin cantidad, estimadas con el nivel; ");
            r.avisos().forEach(a -> s.append(a).append(" "));
            Notification.show(s.toString(), 10000, Notification.Position.MIDDLE).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        } catch (Exception e) {
            Notification.show("No se pudo importar " + nombre + ": " + e.getMessage(), 8000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
        refrescarGrid();
        refrescarConsumo();
    }

    // ================================================================== Consumo y rendimiento

    private Component crearConsumo() {
        VerticalLayout v = new VerticalLayout();
        v.setPadding(false);
        List<Integer> anios = new ArrayList<>();
        for (int a = YearMonth.now().getYear(); a >= 2026; a--) anios.add(a);
        anioCombo.setItems(anios);
        anioCombo.setValue(anios.get(0));
        anioCombo.setWidth("120px");
        anioCombo.addValueChangeListener(e -> refrescarConsumo());
        mesConsumo.setItemLabelGenerator(ControlMf13View::nombreMes);
        mesConsumo.setWidth("190px");
        mesConsumo.addValueChangeListener(e -> refrescarMes());
        for (Div d : List.of(tablaAnual, graficosAnuales, detalleMes, notasAnuales)) d.setWidthFull();
        Span tMes = titulo("Consumo por mes");
        Span tDet = titulo("Detalle del mes");
        v.add(anioCombo, tMes, tablaAnual, graficosAnuales, tDet, mesConsumo, detalleMes, notasAnuales);
        refrescarConsumo();
        return v;
    }

    private static Span titulo(String t) {
        Span s = new Span(t);
        s.getStyle().set("font-size", "16px").set("font-weight", "700").set("margin-top", "10px");
        return s;
    }

    private String color(String generador) {
        for (int i = 0; i < generadores.size(); i++) if (generadores.get(i).nombre().equals(generador)) return COLORES[i % COLORES.length];
        return COLORES[2];
    }

    private void refrescarConsumo() {
        int anio = anioCombo.getValue() == null ? YearMonth.now().getYear() : anioCombo.getValue();
        List<String> nombres = ordenar(svc.nombresConsumo(anio));
        List<FilaMes> filas = svc.consumoAnual(anio);
        // tabla
        StringBuilder h = new StringBuilder("<div style=\"overflow:auto\"><table style=\"border-collapse:collapse;font-size:13px;background:#fff;"
                + "border:1px solid #E4E6EC;border-radius:12px\"><thead><tr style=\"background:#e9ecf2\"><th style=\"" + TH + "\"></th>");
        for (String n : nombres) h.append("<th colspan=\"3\" style=\"").append(TH).append("text-align:center\">")
                .append(punto(color(n))).append(esc(n)).append("</th>");
        h.append("<th colspan=\"3\" style=\"").append(TH).append("text-align:center\">Total</th><th style=\"").append(TH).append("\"></th></tr>")
                .append("<tr style=\"background:#f4f6fa\"><th style=\"").append(TH).append("\">Mes</th>");
        for (int i = 0; i <= nombres.size(); i++) h.append(thn("Diésel (gal)")).append(thn("kWh")).append(thn("kWh/gal"));
        h.append("<th style=\"").append(TH).append("\">Origen</th></tr></thead><tbody>");
        double[] tg = new double[nombres.size() + 1], tk = new double[nombres.size() + 1];
        boolean[] sinKwh = new boolean[nombres.size()];
        for (FilaMes fm : filas) {
            h.append("<tr><td style=\"").append(TD).append("\">").append(nombreMes(fm.mes())).append("</td>");
            Set<String> origenes = new java.util.TreeSet<>();
            for (int i = 0; i < nombres.size(); i++) {
                Consumo c = fm.porGenerador().get(nombres.get(i));
                if (c == null) {
                    h.append(tdn("—")).append(tdn("—")).append(tdn("—"));
                    continue;
                }
                origenes.add(c.origen());
                tg[i] += c.galones();
                if (c.kwh() != null) tk[i] += c.kwh();
                else sinKwh[i] = true;
                h.append(tdn(f(c.galones(), 1))).append(tdn(c.kwh() == null ? "sin medidor" : f(c.kwh(), 0)))
                        .append(tdn(f(c.kwhPorGalon(), 1)));
            }
            double g = fm.galones(), k = fm.kwh();
            tg[nombres.size()] += g;
            tk[nombres.size()] += k;
            h.append(tdn("<b>" + f(g, 1) + "</b>")).append(tdn("<b>" + f(k, 0) + "</b>")).append(tdn("<b>" + f(g > 0 && k > 0 ? k / g : null, 1) + "</b>"))
                    .append("<td style=\"").append(TD).append("color:#6b7080\">").append(esc(String.join(", ", origenes))).append("</td></tr>");
        }
        h.append("<tr style=\"background:#f4f6fa;font-weight:700\"><td style=\"").append(TD).append("\">Total ").append(anio).append("</td>");
        for (int i = 0; i <= nombres.size(); i++) {
            boolean sk = i < nombres.size() && sinKwh[i] && tk[i] == 0;
            h.append(tdn(f(tg[i], 1))).append(tdn(sk ? "sin medidor" : f(tk[i], 0)))
                    .append(tdn(f(tg[i] > 0 && tk[i] > 0 ? tk[i] / tg[i] : null, 1)));
        }
        h.append("<td style=\"").append(TD).append("\"></td></tr></tbody></table></div>");
        tablaAnual.removeAll();
        tablaAnual.add(new Html(h.toString()));
        // nota final: solo lo estimado y cómo se estimó
        Map<String, List<String>> est = new LinkedHashMap<>();
        java.util.regex.Pattern pe = java.util.regex.Pattern.compile("([0-9.,]+) gal estimados");
        for (FilaMes fm : filas) {
            fm.porGenerador().forEach((n, c) -> {
                Double g = c.galonesEstimados() > 0 ? Double.valueOf(c.galonesEstimados()) : null;
                if (g == null && c.nota() != null) {
                    java.util.regex.Matcher m = pe.matcher(c.nota());
                    if (m.find()) g = Double.parseDouble(m.group(1).replace(".", "").replace(',', '.'));
                }
                if (RegistroMf13Service.KOHLER.equals(n)) est.computeIfAbsent(n, x -> new ArrayList<>()).add(nombreMesCorto(fm.mes()) + " " + f(c.galones(), 2) + " gal");
                else if (g != null) est.computeIfAbsent(n, x -> new ArrayList<>()).add(nombreMesCorto(fm.mes()) + " " + f(g, 1) + " gal");
            });
        }
        StringBuilder nh = new StringBuilder("<div style=\"font-size:12px;color:#555b69;margin-top:10px;background:#fff;border:1px solid #E4E6EC;"
                + "border-radius:12px;padding:10px 14px\"><b>Valores estimados</b><ul style=\"margin:4px 0\">");
        est.forEach((n, l) -> nh.append("<li><b>").append(esc(n)).append("</b> (").append(esc(String.join("; ", l))).append("): ")
                .append(RegistroMf13Service.KOHLER.equals(n)
                        ? "no tiene medidor de diésel ni de energía; el diésel es las horas de marcha del horómetro × 2,7 gal/h."
                        : "cargas de diésel sin cantidad anotada en la planilla. Lo cargado se estimó con el nivel del tanque antes y después de "
                        + "la carga más lo que consumió en ese tramo (kWh del tramo ÷ kWh por galón medido en la misma marcha). Ya está incluido "
                        + "en el diésel del mes.").append("</li>"));
        nh.append("</ul></div>");
        notasAnuales.removeAll();
        if (!est.isEmpty()) notasAnuales.add(new Html(nh.toString()));
        // gráficos
        List<String> etiquetas = filas.stream().map(fm -> nombreMesCorto(fm.mes())).toList();
        List<Serie> dz = new ArrayList<>(), rd = new ArrayList<>();
        for (String n : nombres) {
            List<Double> g = new ArrayList<>(), r = new ArrayList<>();
            for (FilaMes fm : filas) {
                Consumo c = fm.porGenerador().get(n);
                g.add(c == null ? null : c.galones());
                r.add(c == null ? null : c.kwhPorGalon());
            }
            dz.add(new Serie(n, color(n), g));
            if (r.stream().anyMatch(x -> x != null)) rd.add(new Serie(n, color(n), r));
        }
        graficosAnuales.removeAll();
        graficosAnuales.add(rejilla(
                tarjeta("Diésel por mes (galones)", svg(barras(etiquetas, dz, "gal"))),
                tarjeta("Rendimiento por mes (kWh por galón)", svg(lineas(etiquetas, rd, "kWh/gal")))));
        // meses con registro para el detalle
        List<YearMonth> conRegistro = filas.stream().filter(fm -> fm.porGenerador().values().stream()
                .anyMatch(c -> "Registro MF-13".equals(c.origen()))).map(FilaMes::mes).sorted(Comparator.reverseOrder()).toList();
        YearMonth antes = mesConsumo.getValue();
        mesConsumo.setItems(conRegistro);
        mesConsumo.setValue(conRegistro.contains(antes) ? antes : conRegistro.isEmpty() ? null : conRegistro.get(0));
        refrescarMes();
    }

    private List<String> ordenar(List<String> nombres) {
        List<String> out = new ArrayList<>();
        generadores.forEach(g -> {
            if (nombres.contains(g.nombre())) out.add(g.nombre());
        });
        nombres.stream().filter(n -> !out.contains(n)).forEach(out::add);
        return out;
    }

    private void refrescarMes() {
        detalleMes.removeAll();
        YearMonth mes = mesConsumo.getValue();
        if (mes == null) {
            detalleMes.add(new Span("Todavía no hay meses con registros MF-13 en la app."));
            return;
        }
        // tarjetas por generador y total
        HorizontalLayout tarj = new HorizontalLayout();
        tarj.setWidthFull();
        tarj.getStyle().set("flex-wrap", "wrap");
        double tg = 0, tk = 0, th = 0;
        for (Generador g : generadores) {
            Consumo c = svc.consumoDelMes(g.nombre(), mes);
            tg += c.galones();
            tk += c.kwh() == null ? 0 : c.kwh();
            th += c.horas() == null ? 0 : c.horas();
            String extra = "<span>Cargas de diésel</span><span>" + c.cargas() + "</span>"
                    + (c.galonesEstimados() > 0 ? "<span>Estimado (cargas sin cantidad)</span><span>" + f(c.galonesEstimados(), 1) + " gal</span>" : "")
                    + (c.cargasSinCantidad() > 0 ? "<span style=\"color:#9b1c26\">Cargas sin cantidad</span><span style=\"color:#9b1c26\">" + c.cargasSinCantidad() + "</span>" : "");
            tarj.add(tarjetaConsumo(g.nombre() + (g.redAsociada() == null ? "" : " · " + nombreTrafo(g.redAsociada())), color(g.nombre()),
                    c.galones(), c.kwh(), c.horas(), extra));
        }
        tarj.add(tarjetaConsumo("Total de los generadores", null, tg, tk, th, ""));
        detalleMes.add(tarj);
        // marchas
        List<Marcha> marchas = new ArrayList<>();
        for (Generador g : generadores) marchas.addAll(svc.marchas(g.nombre(), mes));
        StringBuilder h = new StringBuilder("<div style=\"max-height:420px;overflow:auto;border:1px solid #E4E6EC;border-radius:10px;background:#fff\">"
                + "<table style=\"border-collapse:collapse;font-size:12.5px;width:100%\"><thead><tr style=\"background:#f4f6fa\">"
                + "<th style=\"" + TH + "\">Generador</th><th style=\"" + TH + "\">Inicio</th><th style=\"" + TH + "\">Fin</th>"
                + thn("Horas") + thn("kWh") + thn("Diésel (gal)") + thn("kWh/gal") + "</tr></thead><tbody>");
        for (Marcha m : marchas) {
            h.append("<tr><td style=\"").append(TD).append("\">").append(punto(color(m.generador()))).append(esc(m.generador())).append("</td><td style=\"")
                    .append(TD).append("\">").append(m.inicio().format(DIA_HORA)).append("</td><td style=\"").append(TD).append("\">")
                    .append(m.fin().format(DIA_HORA)).append("</td>").append(tdn(f(m.horas(), 1))).append(tdn(f(m.kwh(), 0)))
                    .append(tdn(f(m.galones(), 1) + (m.incluyeEstimado() ? " *" : ""))).append(tdn(f(m.kwhPorGalon(), 1))).append("</tr>");
        }
        h.append("</tbody></table></div><div style=\"font-size:12px;color:#6b7080\">* incluye una carga de diésel estimada.</div>");
        List<Serie> rend = new ArrayList<>();
        int n = 0;
        for (Generador g : generadores) {
            List<Double> r = marchas.stream().filter(m -> m.generador().equals(g.nombre())).map(Marcha::kwhPorGalon).toList();
            n = Math.max(n, r.size());
            if (!r.isEmpty()) rend.add(new Serie(g.nombre(), color(g.nombre()), r));
        }
        List<String> idx = new ArrayList<>();
        for (int i = 1; i <= n; i++) idx.add(String.valueOf(i));
        detalleMes.add(rejilla(
                tarjeta("Períodos de marcha", new Html("<div>" + h + "</div>")),
                tarjeta("Rendimiento por período de marcha (kWh por galón)", svg(lineas(idx, rend, "kWh/gal")))));
        // observaciones
        StringBuilder o = new StringBuilder("<div style=\"max-height:360px;overflow:auto;border:1px solid #E4E6EC;border-radius:10px;background:#fff\">"
                + "<table style=\"border-collapse:collapse;font-size:12.5px;width:100%\"><thead><tr style=\"background:#f4f6fa\"><th style=\""
                + TH + "\">Fecha</th><th style=\"" + TH + "\">Generador</th><th style=\"" + TH + "\">Observación</th></tr></thead><tbody>");
        Map<String, int[]> conteo = new LinkedHashMap<>();
        for (Generador g : generadores) {
            int[] c = new int[4];
            for (RegistroMf13 r : svc.registros(g.nombre(), mes)) {
                if (r.isObsCargaDiesel()) c[0]++;
                if (r.isObsAceite()) c[1]++;
                if (r.isObsRefrigerante()) c[2]++;
                if (r.isObsFalla()) c[3]++;
                String et = etiquetas(r);
                if (!et.isEmpty() || (r.getObservacion() != null && (r.isObsFalla() || r.isObsAceite() || r.isObsRefrigerante()))) {
                    o.append("<tr><td style=\"").append(TD).append("\">").append(r.getFechaHora().format(DIA_HORA)).append("</td><td style=\"")
                            .append(TD).append("\">").append(esc(g.nombre())).append("</td><td style=\"").append(TD).append("white-space:normal\">")
                            .append(et).append(" ").append(esc(r.getObservacion() == null ? "" : r.getObservacion())).append("</td></tr>");
                }
            }
            conteo.put(g.nombre(), c);
        }
        o.append("</tbody></table></div>");
        StringBuilder res = new StringBuilder("<table style=\"border-collapse:collapse;font-size:13px;background:#fff;margin-bottom:8px\"><thead><tr><th style=\""
                + TH + "\">Tipo</th>");
        conteo.keySet().forEach(k -> res.append(thn(esc(k))));
        res.append("</tr></thead><tbody>");
        String[] tipos = {badge("Carga de diésel", "#e3efff", "#1f4f9c"), badge("Aceite", "#fff3cd", "#856404"),
                badge("Refrigerante", "#d4f3ef", "#0d5f56"), badge("Falla o aviso", "#f8d7da", "#721c24")};
        for (int i = 0; i < tipos.length; i++) {
            res.append("<tr><td style=\"").append(TD).append("\">").append(tipos[i]).append("</td>");
            for (int[] c : conteo.values()) res.append(tdn(String.valueOf(c[i])));
            res.append("</tr>");
        }
        res.append("</tbody></table>");
        detalleMes.add(tarjeta("Observaciones del mes", new Html("<div>" + res + o + "</div>")));
    }

    private Component tarjetaConsumo(String titulo, String color, double gal, Double kwh, Double hrs, String extra) {
        Double rend = kwh == null || gal <= 0 ? null : kwh / gal;
        String html = "<div style=\"min-width:250px\"><div style=\"font-weight:700;margin-bottom:6px\">" + (color == null ? "" : punto(color)) + esc(titulo) + "</div>"
                + "<div style=\"font-size:24px;font-weight:700\">" + f(rend, 1) + " <span style=\"font-size:13px;font-weight:400;color:#6b7080\">kWh por galón</span></div>"
                + "<div style=\"display:grid;grid-template-columns:1fr auto;gap:3px 12px;font-size:13px;margin-top:6px\">"
                + "<span>Diésel</span><span><b>" + f(gal, 1) + " gal</b></span><span>Energía generada</span><span><b>" + f(kwh, 0) + " kWh</b></span>"
                + "<span>Horas de marcha</span><span><b>" + f(hrs, 1) + " h</b></span><span>Diésel por hora</span><span><b>"
                + f(hrs == null || hrs <= 0 ? null : gal / hrs, 1) + " gal/h</b></span>" + extra + "</div></div>";
        return tarjeta(null, new Html(html));
    }

    /** Dos columnas del mismo ancho que ocupan toda la ventana; una debajo de la otra si no entran. */
    private static Component rejilla(Component... c) {
        Div d = new Div(c);
        d.setWidthFull();
        d.getStyle().set("display", "grid").set("grid-template-columns", "repeat(auto-fit, minmax(460px, 1fr))")
                .set("gap", "12px").set("margin", "8px 0").set("box-sizing", "border-box");
        return d;
    }

    /**
     * Gráfico SVG como HTML del navegador: el componente Html pasa los atributos a minúsculas ("viewbox") y el SVG queda
     * de 300 px sin escalar; con innerHTML el navegador lo arma bien y se ajusta al ancho de la tarjeta.
     */
    private static Component svg(String html) {
        Div d = new Div();
        d.setWidthFull();
        d.getElement().setProperty("innerHTML", html);
        return d;
    }

    private static Component tarjeta(String titulo, Component contenido) {
        VerticalLayout c = new VerticalLayout();
        c.setDefaultHorizontalComponentAlignment(FlexComponent.Alignment.STRETCH);
        c.setPadding(true);
        c.setSpacing(false);
        c.getStyle().set("background", "#fff").set("border", "1px solid #E4E6EC").set("border-radius", "12px").set("flex", "1 1 420px")
                .set("min-width", "0").set("box-sizing", "border-box");
        if (titulo != null) {
            Span t = new Span(titulo);
            t.getStyle().set("font-weight", "700").set("margin-bottom", "6px");
            c.add(t);
        }
        c.add(contenido);
        return c;
    }

    // ================================================================== utilidades de presentación

    private static final String TH = "padding:5px 8px;border-bottom:1px solid #e3e5ea;font-weight:600;color:#3a3f4c;white-space:nowrap;text-align:left;";
    private static final String TD = "padding:4px 8px;border-bottom:1px solid #eef0f3;white-space:nowrap;";

    private static String thn(String t) {
        return "<th style=\"" + TH + "text-align:right\">" + t + "</th>";
    }

    private static String tdn(String t) {
        return "<td style=\"" + TD + "text-align:right;font-variant-numeric:tabular-nums\">" + t + "</td>";
    }

    private static String punto(String color) {
        return "<span style=\"display:inline-block;width:10px;height:10px;border-radius:50%;background:" + color + ";margin-right:6px\"></span>";
    }

    private static String badge(String t, String fondo, String color) {
        return "<span style=\"display:inline-block;padding:1px 7px;border-radius:10px;font-size:11px;font-weight:600;background:" + fondo
                + ";color:" + color + "\">" + t + "</span>";
    }

    private static String etiquetas(RegistroMf13 r) {
        List<String> e = new ArrayList<>();
        if (r.isObsCargaDiesel()) e.add(badge("Carga de diésel", "#e3efff", "#1f4f9c"));
        if (r.isObsAceite()) e.add(badge("Aceite", "#fff3cd", "#856404"));
        if (r.isObsRefrigerante()) e.add(badge("Refrigerante", "#d4f3ef", "#0d5f56"));
        if (r.isObsFalla()) e.add(badge("Falla o aviso", "#f8d7da", "#721c24"));
        return String.join(" ", e);
    }

    private static String nombreTrafo(String red) {
        return red.replace("Trafo", "Transformador ");
    }

    private static String nombreMes(YearMonth m) {
        String n = m.getMonth().getDisplayName(TextStyle.FULL, ES);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1) + " " + m.getYear();
    }

    private static String nombreMesCorto(YearMonth m) {
        String n = m.getMonth().getDisplayName(TextStyle.SHORT, ES).replace(".", "");
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static String f(Double v, int dec) {
        if (v == null || v.isNaN() || v.isInfinite()) return "";
        NumberFormat nf = NumberFormat.getNumberInstance(ES);
        nf.setMinimumFractionDigits(dec);
        nf.setMaximumFractionDigits(dec);
        return nf.format(v);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private record Serie(String nombre, String color, List<Double> valores) {
    }

    private static final int W = 820, H = 330, ML = 52, MR = 14, MT = 14, MB = 34;

    private static String leyenda(List<Serie> series) {
        StringBuilder s = new StringBuilder("<div style=\"display:flex;gap:14px;font-size:12px;margin-bottom:4px\">");
        series.forEach(x -> s.append("<span>").append(punto(x.color())).append(esc(x.nombre())).append("</span>"));
        return s.append("</div>").toString();
    }

    /** Barras agrupadas por etiqueta (SVG), con el valor en el tooltip. */
    private static String barras(List<String> etiquetas, List<Serie> series, String unidad) {
        double max = series.stream().flatMap(s -> s.valores().stream()).filter(v -> v != null).mapToDouble(Double::doubleValue).max().orElse(1);
        if (max <= 0) max = 1;
        int n = Math.max(1, etiquetas.size());
        double paso = (W - ML - MR) / (double) n, bw = Math.max(3, (paso - 6) / Math.max(1, series.size()) - 2);
        StringBuilder s = new StringBuilder("<div>" + leyenda(series) + "<svg viewBox=\"0 0 " + W + " " + H + "\" width=\"100%\" style=\"display:block;height:auto\">");
        ejes(s, 0, max * 1.05, etiquetas);
        for (int si = 0; si < series.size(); si++) {
            Serie x = series.get(si);
            for (int i = 0; i < x.valores().size(); i++) {
                Double v = x.valores().get(i);
                if (v == null || v <= 0) continue;
                double y = yv(v, 0, max * 1.05), x0 = ML + i * paso + 3 + si * (bw + 2);
                s.append(String.format(Locale.ROOT, "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"2\" fill=\"%s\"><title>%s · %s: %s %s</title></rect>",
                        x0, y, bw, H - MB - y, x.color(), esc(x.nombre()), esc(etiquetas.get(i)), f(v, 1), unidad));
            }
        }
        return s.append("</svg></div>").toString();
    }

    /** Líneas por etiqueta (SVG); un valor nulo corta la línea. */
    private static String lineas(List<String> etiquetas, List<Serie> series, String unidad) {
        List<Double> todos = series.stream().flatMap(s -> s.valores().stream()).filter(v -> v != null).toList();
        if (todos.isEmpty()) return "<div style=\"color:#6b7080;font-size:13px\">Sin datos</div>";
        double lo = Math.floor(todos.stream().mapToDouble(Double::doubleValue).min().orElse(0) - 1);
        double hi = Math.ceil(todos.stream().mapToDouble(Double::doubleValue).max().orElse(1) + 1);
        if (lo < 0) lo = 0;
        int n = Math.max(1, etiquetas.size());
        double paso = (W - ML - MR) / (double) n;
        StringBuilder s = new StringBuilder("<div>" + leyenda(series) + "<svg viewBox=\"0 0 " + W + " " + H + "\" width=\"100%\" style=\"display:block;height:auto\">");
        ejes(s, lo, hi, etiquetas);
        for (Serie x : series) {
            StringBuilder p = new StringBuilder();
            StringBuilder c = new StringBuilder();
            for (int i = 0; i < x.valores().size(); i++) {
                Double v = x.valores().get(i);
                if (v == null) {
                    if (p.length() > 0) s.append("<polyline fill=\"none\" stroke=\"").append(x.color()).append("\" stroke-width=\"2\" points=\"").append(p).append("\"/>");
                    p.setLength(0);
                    continue;
                }
                double cx = ML + (i + 0.5) * paso, cy = yv(v, lo, hi);
                p.append(String.format(Locale.ROOT, "%.1f,%.1f ", cx, cy));
                c.append(String.format(Locale.ROOT, "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"4\" fill=\"%s\" stroke=\"#fff\" stroke-width=\"2\"><title>%s · %s: %s %s</title></circle>",
                        cx, cy, x.color(), esc(x.nombre()), esc(etiquetas.get(i)), f(v, 1), unidad));
            }
            if (p.length() > 0) s.append("<polyline fill=\"none\" stroke=\"").append(x.color()).append("\" stroke-width=\"2\" points=\"").append(p).append("\"/>");
            s.append(c);
        }
        return s.append("</svg></div>").toString();
    }

    private static double yv(double v, double lo, double hi) {
        return H - MB - (v - lo) / (hi - lo) * (H - MT - MB);
    }

    private static void ejes(StringBuilder s, double lo, double hi, List<String> etiquetas) {
        for (int k = 0; k <= 4; k++) {
            double v = lo + (hi - lo) * k / 4, y = yv(v, lo, hi);
            s.append(String.format(Locale.ROOT, "<line x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\" stroke=\"#eceef2\"/>", ML, W - MR, y, y))
                    .append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%.1f\" text-anchor=\"end\" font-size=\"13\" fill=\"#6b7080\">%s</text>", ML - 6, y + 4, f(v, 0)));
        }
        int n = Math.max(1, etiquetas.size());
        double paso = (W - ML - MR) / (double) n;
        int cada = n > 16 ? (int) Math.ceil(n / 16.0) : 1;
        for (int i = 0; i < etiquetas.size(); i += cada) {
            s.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%d\" text-anchor=\"middle\" font-size=\"13\" fill=\"#6b7080\">%s</text>",
                    ML + (i + 0.5) * paso, H - 10, esc(etiquetas.get(i))));
        }
    }
}
