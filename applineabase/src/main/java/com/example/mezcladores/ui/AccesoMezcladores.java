package com.example.mezcladores.ui;

import com.example.base.ui.ChartsView;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.router.BeforeEnterEvent;

/** Gate de acceso a MezcladoresConfigView: solo ADMIN y zona Mezcla. */
final class AccesoMezcladores {

    private AccesoMezcladores() {
    }

    static void verificar(BeforeEnterEvent event, LineaAccessService lineaAccessService) {
        if (!lineaAccessService.puedeVerMezcladores()) {
            Notification.show("No tienes permiso para ver la configuración de mezcladores", 3000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            event.forwardTo(ChartsView.class);
        }
    }
}
