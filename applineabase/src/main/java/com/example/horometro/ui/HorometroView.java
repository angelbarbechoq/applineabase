package com.example.horometro.ui;

import com.example.alarmas.model.AlarmaConfig;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaConfigRepository;
import com.example.alarmas.ui.AlarmasConfigView;
import com.example.base.ui.ChartsView;
import com.example.base.ui.MainLayout;
import com.example.horometro.service.HorometroService;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.List;

/**
 * Dashboard con todas las máquinas a la vez: estado ON/OFF, PW en vivo junto al umbral
 * configurado (para facilitar el ajuste por máquina desde AlarmasConfigView), y horas
 * de funcionamiento en tres granularidades (hoy, mes, sin límite de tiempo).
 *
 * Se refresca con el mismo mecanismo de poll que ya usan MainLayout/AlarmasHistorialView
 * (cada 30s) en vez de empujar datos por SSE hacia el Grid — como los datos de origen
 * solo cambian una vez por minuto (ciclo de lectura de PLCs), no hay ganancia real en
 * empujar más rápido, y así se evita la complejidad de UI.access() para actualizar
 * un componente server-side desde un evento asíncrono.
 */
@PageTitle("Horómetro | LineaBase")
@Route(value = "horometro", layout = MainLayout.class)
@PermitAll
public class HorometroView extends VerticalLayout implements BeforeEnterObserver {

    private static final int POLL_INTERVAL_MS = 30000;

    private final HorometroService horometroService;
    private final LineaAccessService lineaAccessService;
    private final AlarmaConfigRepository alarmaConfigRepository;
    private final AuthenticationContext authenticationContext;

    private final Grid<HorometroRow> grid = new Grid<>(HorometroRow.class, false);

    public HorometroView(HorometroService horometroService, LineaAccessService lineaAccessService,
                          AlarmaConfigRepository alarmaConfigRepository, AuthenticationContext authenticationContext) {
        this.horometroService = horometroService;
        this.lineaAccessService = lineaAccessService;
        this.alarmaConfigRepository = alarmaConfigRepository;
        this.authenticationContext = authenticationContext;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Horómetro de Máquinas"));
        if (lineaAccessService.esAdmin()) {
            add(new RouterLink("Ajustar umbrales de encendido/apagado", AlarmasConfigView.class));
        }

        grid.addColumn(HorometroRow::maquina).setHeader("Línea/Máquina").setAutoWidth(true).setSortable(true);
        grid.addComponentColumn(this::estadoBadge).setHeader("Estado").setAutoWidth(true);
        grid.addColumn(r -> String.format("%.2f kW", r.pwActual())).setHeader("PW actual").setAutoWidth(true);
        grid.addColumn(r -> String.format("%.2f kW", r.umbralKw())).setHeader("Umbral configurado").setAutoWidth(true);
        grid.addColumn(r -> formatoHoras(r.horasHoy())).setHeader("Horas hoy").setAutoWidth(true).setSortable(true);
        grid.addColumn(r -> formatoHoras(r.horasMes())).setHeader("Horas del mes").setAutoWidth(true).setSortable(true);
        grid.addColumn(r -> formatoHoras(r.horasTotal())).setHeader("Horas totales").setAutoWidth(true).setSortable(true);
        if (lineaAccessService.esAdmin()) {
            grid.addComponentColumn(this::botonReiniciar).setHeader("Reiniciar total").setAutoWidth(true);
        }
        grid.setSizeFull();

        add(grid);
        setFlexGrow(1, grid);

        refrescarGrid();

        addAttachListener(e -> {
            UI ui = e.getUI();
            ui.setPollInterval(POLL_INTERVAL_MS);
            ui.addPollListener(pollEvent -> refrescarGrid());
        });
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (lineaAccessService.getMaquinasPermitidas().isEmpty()) {
            Notification.show("No tienes máquinas asignadas", 3000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            event.forwardTo(ChartsView.class);
        }
    }

    // "Parada" ya no es una alarma (a pedido: no queremos que dispare alarma/notificación),
    // así que se muestra como advertencia amarilla, no en rojo como el resto de las alarmas.
    private static final String COLOR_ADVERTENCIA_FONDO = "#fff3cd";
    private static final String COLOR_ADVERTENCIA_TEXTO = "#856404";

    private Span estadoBadge(HorometroRow r) {
        if (r.encendida()) {
            Span badge = new Span("ENCENDIDA");
            badge.getElement().getThemeList().add("badge");
            badge.getElement().getThemeList().add("success");
            return badge;
        }
        Span badge = new Span("⚠ PARADA");
        badge.getElement().getThemeList().add("badge");
        badge.getStyle()
                .set("background-color", COLOR_ADVERTENCIA_FONDO)
                .set("color", COLOR_ADVERTENCIA_TEXTO);
        return badge;
    }

    private Button botonReiniciar(HorometroRow r) {
        Button btn = new Button("Reiniciar", e -> confirmarReinicio(r.maquina()));
        btn.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        return btn;
    }

    private void confirmarReinicio(String maquina) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Reiniciar horómetro total de " + maquina);
        dialog.setText("Esto pone en cero las horas acumuladas sin límite de tiempo. Queda registrado en el "
                + "historial de reinicios (con las horas que tenía justo antes) para trazabilidad de mantenimiento. "
                + "Esta acción no se puede deshacer.");
        dialog.setCancelable(true);
        dialog.setConfirmText("Reiniciar");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> {
            horometroService.reiniciarHorometroTotal(maquina, nombreUsuarioActual());
            Notification.show("Horómetro de " + maquina + " reiniciado", 2500, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            refrescarGrid();
        });
        dialog.open();
    }

    private String nombreUsuarioActual() {
        return authenticationContext.getAuthenticatedUser(UserDetails.class)
                .map(UserDetails::getUsername)
                .orElse("desconocido");
    }

    private void refrescarGrid() {
        List<HorometroRow> filas = new ArrayList<>();
        for (String maquina : lineaAccessService.getMaquinasPermitidas()) {
            AlarmaConfig config = alarmaConfigRepository.findByLineaMaquinaAndTipoAlarma(maquina, TipoAlarma.DETENCION)
                    .or(() -> alarmaConfigRepository.findByLineaMaquinaAndTipoAlarma(maquina, TipoAlarma.CICLO_COMPRESOR))
                    .orElse(null);
            if (config == null) {
                continue; // línea sin regla de encendido/apagado configurada: el horómetro no aplica
            }
            HorometroService.HorometroSnapshot snap = horometroService.obtenerSnapshot(maquina);
            Double pw = horometroService.getUltimoPw(maquina);
            double umbral = config.getUmbralMinimoKw() != null ? config.getUmbralMinimoKw() : 0.0;
            filas.add(new HorometroRow(maquina, snap.encendida(), pw == null ? 0.0 : pw, umbral,
                    snap.horasHoy(), snap.horasMes(), snap.horasTotal()));
        }
        grid.setItems(filas);
    }

    private String formatoHoras(double horas) {
        long totalMinutos = Math.round(horas * 60);
        long h = totalMinutos / 60;
        long m = totalMinutos % 60;
        return String.format("%d:%02d", h, m);
    }

    private record HorometroRow(String maquina, boolean encendida, double pwActual, double umbralKw,
                                 double horasHoy, double horasMes, double horasTotal) {
    }
}
