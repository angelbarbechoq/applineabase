package com.example.transformadores.ui;

import com.example.base.ui.ChartsView;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.router.BeforeEnterEvent;

/** Gate de acceso a Transformadores: solo ADMIN y usuarios con "Ver Transformadores". */
final class AccesoTransformadores {

    private AccesoTransformadores() {
    }

    static void verificar(BeforeEnterEvent event, LineaAccessService lineaAccessService) {
        if (!lineaAccessService.puedeVerTransformadores()) {
            Notification.show("No tienes permiso para ver los transformadores", 3000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            event.forwardTo(ChartsView.class);
        }
    }
}
