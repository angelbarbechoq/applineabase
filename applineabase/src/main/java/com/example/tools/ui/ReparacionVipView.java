package com.example.tools.ui;

import com.example.base.ui.MainLayout;
import com.example.tools.MergeVipMensualTool;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.function.Function;

/**
 * Corre MergeVipMensualTool desde la app, sin tener que abrir IntelliJ. Solo ADMIN,
 * porque escribe directo sobre los archivos SQLite de energía.
 */
@PageTitle("Reparar VIP Mensual | LineaBase")
@Route(value = "admin/reparar-vip", layout = MainLayout.class)
@RolesAllowed("ADMIN")
public class ReparacionVipView extends VerticalLayout {

    private final MergeVipMensualTool herramienta;
    private final DatePicker mesPicker = new DatePicker("Mes a reparar (cualquier día de ese mes)");
    private final TextArea resultado = new TextArea("Resultado");

    public ReparacionVipView(MergeVipMensualTool herramienta) {
        this.herramienta = herramienta;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Reparar VIP Mensual"));
        add(new Paragraph("Copia hacia el archivo mensual los datos de todos los archivos diarios "
                + "que existan en disco para el mes elegido. Útil cuando el mensual quedó incompleto pero "
                + "el diario sí tiene los datos. Es seguro correrlo varias veces: no duplica filas."));

        mesPicker.setValue(LocalDate.now());
        mesPicker.setWidth("280px");

        Button repararVipBtn = new Button("Reparar VIP mensual", e -> ejecutarReparacion(herramienta::reparar));
        repararVipBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button repararKwhBtn = new Button("Reparar KWh mensual", e -> ejecutarReparacion(herramienta::repararNormal));
        repararKwhBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout controles = new HorizontalLayout(mesPicker, repararVipBtn, repararKwhBtn);
        controles.setAlignItems(Alignment.END);

        resultado.setWidthFull();
        resultado.setHeight("420px");
        resultado.setReadOnly(true);
        resultado.getStyle().set("font-family", "monospace");

        add(controles, resultado);
        setFlexGrow(1, resultado);
    }

    private void ejecutarReparacion(Function<YearMonth, MergeVipMensualTool.ResultadoReparacion> accion) {
        LocalDate fecha = mesPicker.getValue();
        if (fecha == null) {
            Notification.show("Selecciona una fecha", 3000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }
        YearMonth mes = YearMonth.from(fecha);
        resultado.setValue("Procesando " + mes + "...");

        MergeVipMensualTool.ResultadoReparacion r = accion.apply(mes);
        resultado.setValue(String.join("\n", r.log()));

        if (r.ok()) {
            Notification.show("Reparación completada (" + r.archivosProcesados() + " archivos procesados)",
                            3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        } else {
            Notification.show("No se completó — revisa el detalle abajo", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }
}
