package com.example.generador.ui;

import com.example.base.ui.ChartsView;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.router.BeforeEnterEvent;

/** Gate de acceso a Generadores y Analisis: solo ADMIN y usuarios con "Ver Generadores". */
final class AccesoGeneradores {

    private AccesoGeneradores() {
    }

    static void verificar(BeforeEnterEvent event, LineaAccessService lineaAccessService) {
        if (!lineaAccessService.puedeVerGeneradores()) {
            Notification.show("No tienes permiso para ver el grupo electrogeno", 3000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            event.forwardTo(ChartsView.class);
        }
    }
}
