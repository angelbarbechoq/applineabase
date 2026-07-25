package com.example.base.ui;

import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;

/**
 * Notificación de error estándar de la app (fondo rojo, esquina inferior derecha, 3s) — antes
 * repetida igual en ConfiguracionView y AlarmasConfigView, cada una con su propio método privado.
 */
public final class NotificacionesUtil {

    private NotificacionesUtil() {
    }

    public static void mostrarError(String mensaje) {
        Notification.show(mensaje, 3000, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_ERROR);
    }
}
