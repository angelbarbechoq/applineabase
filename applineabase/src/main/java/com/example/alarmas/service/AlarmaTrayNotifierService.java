package com.example.alarmas.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * Notificacion nativa de Windows (globo del system tray) para alarmas que pasan a urgente:
 * avisa aunque la app este minimizada o no haya ningun navegador abierto, mientras el .exe
 * siga corriendo en la PC. Si el entorno no soporta system tray (por ejemplo, corriendo
 * headless), se ignora en silencio: no debe romper el arranque de la app.
 */
@Service
public class AlarmaTrayNotifierService {

    private static final Logger logger = LoggerFactory.getLogger(AlarmaTrayNotifierService.class);

    private TrayIcon trayIcon;

    public AlarmaTrayNotifierService() {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            logger.info("System tray no disponible en este entorno: no habra notificacion nativa de Windows para alarmas urgentes");
            return;
        }
        try {
            trayIcon = new TrayIcon(crearIcono(), "LineaBase - Alarmas");
            trayIcon.setImageAutoSize(true);
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException | RuntimeException e) {
            logger.warn("No se pudo registrar el icono de bandeja del sistema: {}", e.getMessage());
            trayIcon = null;
        }
    }

    /** Dispara el globo nativo de Windows. No hace nada si el tray no esta disponible. */
    public void notificarUrgente(String lineaMaquina, String mensaje) {
        if (trayIcon == null) {
            return;
        }
        trayIcon.displayMessage("Alarma urgente: " + lineaMaquina, mensaje, TrayIcon.MessageType.WARNING);
    }

    /** Icono simple generado en memoria (circulo rojo con "!") para no depender de un archivo
     * de imagen empaquetado aparte. */
    private static Image crearIcono() {
        int size = 32;
        BufferedImage imagen = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = imagen.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(220, 38, 38));
        g.fillOval(2, 2, size - 4, size - 4);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 20));
        g.drawString("!", size / 2 - 4, size / 2 + 7);
        g.dispose();
        return imagen;
    }
}
