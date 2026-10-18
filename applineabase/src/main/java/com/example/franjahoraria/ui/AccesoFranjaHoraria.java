package com.example.franjahoraria.ui;

import com.example.base.ui.ChartsView;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.router.BeforeEnterEvent;

/** Gate de acceso a Consumo por franja horaria: solo ADMIN y usuarios con "Ver Franja Horaria". */
final class AccesoFranjaHoraria {

    private AccesoFranjaHoraria() {
    }

    static void verificar(BeforeEnterEvent event, LineaAccessService lineaAccessService) {
        if (!lineaAccessService.puedeVerFranjaHoraria()) {
            Notification.show("No tienes permiso para ver el consumo por franja horaria", 3000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            event.forwardTo(ChartsView.class);
        }
    }
}
