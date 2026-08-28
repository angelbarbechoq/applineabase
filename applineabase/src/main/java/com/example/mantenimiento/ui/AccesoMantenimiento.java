package com.example.mantenimiento.ui;

import com.example.base.ui.ChartsView;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.router.BeforeEnterEvent;

/** Gate de acceso a MantenimientoView: solo ADMIN y usuarios con "Ver Mantenimiento". */
final class AccesoMantenimiento {

    private AccesoMantenimiento() {
    }

    static void verificar(BeforeEnterEvent event, LineaAccessService lineaAccessService) {
        if (!lineaAccessService.puedeVerMantenimiento()) {
            Notification.show("No tienes permiso para ver el registro de mantenimiento", 3000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            event.forwardTo(ChartsView.class);
        }
    }
}
