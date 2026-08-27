package com.example.mantenimiento.ui;

import com.example.base.ui.MainLayout;
import com.example.base.ui.NotificacionesUtil;
import com.example.mantenimiento.model.EstadoPlanDTO;
import com.example.mantenimiento.model.PlanMantenimiento;
import com.example.mantenimiento.service.MantenimientoService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Vista operativa: registrar que una tarea de mantenimiento preventivo se realizo. Aparte de
 * MantenimientoConfigView (que solo define la regla: tag, tarea, intervalo) porque este es un
 * evento que ocurre cada vez que alguien hace la tarea en planta, no un dato de la regla en si.
 * Cada registro es nuevo (no se edita el anterior), para conservar el historial completo.
 */
@PageTitle("Mantenimiento Preventivo | LineaBase")
@Route(value = "mantenimiento", layout = MainLayout.class)
@RolesAllowed("ADMIN")
public class MantenimientoView extends VerticalLayout {

    private static final String SIN_REGISTRO = "Sin mantenimiento registrado";
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final MantenimientoService mantenimientoService;
    private final Grid<EstadoPlanDTO> grid = new Grid<>(EstadoPlanDTO.class, false);

    public MantenimientoView(MantenimientoService mantenimientoService) {
        this.mantenimientoService = mantenimientoService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Mantenimiento Preventivo"));

        grid.addColumn(e -> e.plan().getTag()).setHeader("TAG").setAutoWidth(true).setSortable(true);
        grid.addColumn(e -> e.plan().getTarea()).setHeader("Tarea").setAutoWidth(true).setSortable(true);
        grid.addColumn(e -> e.plan().getIntervaloHoras()).setHeader("Intervalo (h)").setAutoWidth(true);
        grid.addColumn(this::formatearAvisoAnticipado).setHeader("Aviso anticipado (h)").setAutoWidth(true);
        grid.addColumn(this::formatearHorasTranscurridas).setHeader("Horas transcurridas").setAutoWidth(true);
        grid.addColumn(this::formatearEstado).setHeader("Estado").setAutoWidth(true);
        grid.addComponentColumn(this::botonMarcarRealizado).setHeader("Accion").setAutoWidth(true);
        grid.setSizeFull();

        add(grid);
        setFlexGrow(1, grid);

        refrescarGrid();
    }

    private Button botonMarcarRealizado(EstadoPlanDTO estado) {
        Button boton = new Button("Marcar realizado", e -> abrirDialogoMarcarRealizado(estado.plan()));
        boton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        return boton;
    }

    private void abrirDialogoMarcarRealizado(PlanMantenimiento plan) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Registrar mantenimiento: " + plan.getTarea());

        DateTimePicker fechaField = new DateTimePicker("Fecha y hora en que se realizo la tarea");
        fechaField.setValue(LocalDateTime.now());
        fechaField.setMax(LocalDateTime.now());
        fechaField.setWidth("280px");
        fechaField.setHelperText("Puede ser una fecha pasada, si la tarea ya se hizo y recien ahora se registra");

        TextArea notasField = new TextArea("Notas (opcional)");
        notasField.setWidth("300px");

        Button confirmarBtn = new Button("Confirmar", e -> {
            if (fechaField.getValue() == null) {
                NotificacionesUtil.mostrarError("Indica la fecha en que se realizo el mantenimiento");
                return;
            }
            mantenimientoService.registrarMantenimientoRealizado(plan, fechaField.getValue(), notasField.getValue());
            Notification.show("Mantenimiento registrado", 2500, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            dialog.close();
            refrescarGrid();
        });
        confirmarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button cancelarBtn = new Button("Cancelar", e -> dialog.close());

        dialog.add(new VerticalLayout(fechaField, notasField));
        dialog.getFooter().add(cancelarBtn, confirmarBtn);
        dialog.open();
    }

    private String formatearAvisoAnticipado(EstadoPlanDTO estado) {
        Double aviso = estado.plan().getHorasAvisoAnticipado();
        return aviso == null ? "—" : String.format("%.1f", aviso);
    }

    private String formatearHorasTranscurridas(EstadoPlanDTO estado) {
        return estado.sinRegistro() ? SIN_REGISTRO : String.format("%.1f", estado.horasTranscurridas());
    }

    private String formatearEstado(EstadoPlanDTO estado) {
        if (estado.sinRegistro()) {
            return SIN_REGISTRO;
        }
        if (estado.vencido()) {
            return "Vencido";
        }
        if (estado.proximoAVencer()) {
            return "Proximo a vencer";
        }
        if (estado.proximoAvisoEstimado() != null) {
            return "OK - proximo aviso " + estado.proximoAvisoEstimado().format(FORMATO_FECHA);
        }
        return "OK";
    }

    private void refrescarGrid() {
        grid.setItems(mantenimientoService.listarEstadoPlanes());
    }
}
