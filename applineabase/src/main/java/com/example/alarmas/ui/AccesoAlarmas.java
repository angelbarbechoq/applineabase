package com.example.alarmas.ui;

import com.example.base.ui.ChartsView;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.router.BeforeEnterEvent;

/**
 * Gate de acceso compartido entre AlarmasHistorialView y AlarmasHistorialCompletoView: ambas
 * tenían el mismo beforeEnter (redirigir a ChartsView si el usuario no puede ver alarmas)
 * copiado igual en las dos.
 */
final class AccesoAlarmas {

    private AccesoAlarmas() {
    }

    static void verificar(BeforeEnterEvent event, LineaAccessService lineaAccessService) {
        if (!lineaAccessService.puedeVerAlarmas()) {
            Notification.show("No tienes permiso para ver las alarmas", 3000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            event.forwardTo(ChartsView.class);
        }
    }
}
