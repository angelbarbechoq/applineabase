package com.example.base.ui;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.server.StreamResource;

import java.io.InputStream;
import java.util.function.Supplier;

/**
 * Escapado de campos CSV (RFC 4180: si el valor contiene el separador ";", comillas o salto de
 * línea, se envuelve entre comillas dobles, duplicando las internas) — antes repetido igual en
 * HorometroView.
 */
public final class CsvUtil {

    private CsvUtil() {
    }

    public static String escape(String valor) {
        if (valor.contains(";") || valor.contains("\"") || valor.contains("\n")) {
            return "\"" + valor.replace("\"", "\"\"") + "\"";
        }
        return valor;
    }

    /**
     * Link de descarga de un CSV generado al vuelo (StreamResource, no un botón que dispare la
     * descarga por otro lado — así el navegador la maneja de forma nativa) — antes repetido casi
     * igual en HistoricoView, HorometroView (dos veces) y AlarmasHistorialCompletoView.
     */
    public static Anchor crearLinkDescarga(String nombreArchivo, Supplier<InputStream> generador, String etiqueta) {
        StreamResource recurso = new StreamResource(nombreArchivo, generador::get);
        recurso.setContentType("text/csv; charset=UTF-8");
        Anchor link = new Anchor(recurso, etiqueta);
        link.getElement().setAttribute("download", true);
        return link;
    }
}
