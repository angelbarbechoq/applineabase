package com.example.alarmas.ui;

import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.base.ui.CsvUtil;
import com.example.base.ui.MainLayout;
import com.example.dataacquisition.RutaArchivosEnergia;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Historial completo de alarmas (activas + resueltas), filtrable por
 * línea/máquina. Las alarmas nunca se borran de aquí; solo AlarmasHistorialView
 * (/alarmas) deja de mostrarlas cuando se resuelven.
 *
 * Solo ADMIN por el momento (a diferencia de /alarmas, que también ve Mantenimiento vía
 * AccesoAlarmas/puedeVerAlarmas) — incluye la descarga CSV, que se restringe de la misma forma
 * al restringir la vista completa.
 */
@PageTitle("Historial de Alarmas | LineaBase")
@Route(value = "alarmas/historial", layout = MainLayout.class)
@RolesAllowed("ADMIN")
public class AlarmasHistorialCompletoView extends VerticalLayout {

    private static final String TODAS = "Todas las líneas";
    private static final DateTimeFormatter FORMATO_FECHA_CSV = DateTimeFormatter.ofPattern(RutaArchivosEnergia.FORMATO_FECHA_HORA);

    private final AlarmaEventoRepository eventoRepository;
    private final Grid<AlarmaEvento> grid = new Grid<>(AlarmaEvento.class, false);
    private final ComboBox<String> lineaFiltro = new ComboBox<>("Línea/Máquina");

    public AlarmasHistorialCompletoView(AlarmaEventoRepository eventoRepository) {
        this.eventoRepository = eventoRepository;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Historial de Alarmas"));

        List<String> lineas = eventoRepository.findDistinctLineaMaquina();
        lineaFiltro.setItems(concatenarTodas(lineas));
        lineaFiltro.setValue(TODAS);
        lineaFiltro.setWidth("220px");
        lineaFiltro.addValueChangeListener(e -> refrescarGrid());

        Button refrescarBtn = new Button("Refrescar", e -> refrescarGrid());
        refrescarBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout filtros = new HorizontalLayout(lineaFiltro, refrescarBtn, crearLinkDescargaCsv());
        filtros.setAlignItems(Alignment.END);

        GrillaAlarmaEventoUtil.agregarColumnasInicioLineaTipo(grid);
        grid.addColumn(e -> e.isActiva() ? "Activa" : "Resuelta").setHeader("Estado").setAutoWidth(true).setFlexGrow(0);
        GrillaAlarmaEventoUtil.agregarColumnaUrgente(grid);
        GrillaAlarmaEventoUtil.agregarColumnaDetalle(grid);
        grid.setSizeFull();

        add(filtros, grid);
        setFlexGrow(1, grid);

        refrescarGrid();
    }

    private List<String> concatenarTodas(List<String> lineas) {
        List<String> items = new java.util.ArrayList<>();
        items.add(TODAS);
        items.addAll(lineas);
        return items;
    }

    private void refrescarGrid() {
        grid.setItems(obtenerEventosFiltrados());
    }

    /** Misma línea (o "todas") que ve la grilla — la usa también la exportación CSV, para que
     * el archivo descargado coincida siempre con lo que se ve filtrado en pantalla. */
    private List<AlarmaEvento> obtenerEventosFiltrados() {
        String linea = lineaFiltro.getValue();
        return (linea == null || TODAS.equals(linea))
                ? eventoRepository.findAllByOrderByFechaInicioDesc()
                : eventoRepository.findByLineaMaquinaOrderByFechaInicioDesc(linea);
    }

    private Anchor crearLinkDescargaCsv() {
        return CsvUtil.crearLinkDescarga("alarmas-historial.csv", this::generarCsv, "Descargar CSV");
    }

    private InputStream generarCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append((char) 0xFEFF); // BOM UTF-8, para que Excel muestre bien tildes/ñ
        sb.append("Inicio;Línea/Máquina;Tipo;Estado;Urgente;Detalle\r\n");
        for (AlarmaEvento evento : obtenerEventosFiltrados()) {
            sb.append(evento.getFechaInicio() == null ? "" : evento.getFechaInicio().format(FORMATO_FECHA_CSV)).append(';')
                    .append(CsvUtil.escape(evento.getLineaMaquina())).append(';')
                    .append(evento.getTipoAlarma()).append(';')
                    .append(evento.isActiva() ? "Activa" : "Resuelta").append(';')
                    .append(evento.isUrgente() ? "Sí" : "No").append(';')
                    .append(CsvUtil.escape(evento.getMensaje())).append("\r\n");
        }
        return new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
