package com.example.traspaso.ui;

import com.example.base.ui.MainLayout;
import com.example.security.LineaAccessService;
import com.example.traspaso.service.TraspasoConfiguracionService;
import com.example.traspaso.service.TraspasoConfiguracionService.Diferencia;
import com.example.traspaso.service.TraspasoConfiguracionService.Grupo;
import com.example.traspaso.service.TraspasoConfiguracionService.Paquete;
import com.example.traspaso.service.TraspasoConfiguracionService.Resultado;
import com.example.traspaso.service.TraspasoConfiguracionService.Revision;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.AttachmentType;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

import java.io.OutputStream;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Configuracion > Pasar configuracion: exportar lo configurado en este equipo (la PC de diseño) a un
 * ZIP e importarlo en otro (el servidor), revisando antes qué cambia. Nunca toca el historial.
 */
@PageTitle("Pasar configuracion | LineaBase")
@Route(value = "configuracion/traspaso", layout = MainLayout.class)
@RolesAllowed("ADMIN")
public class TraspasoConfiguracionView extends VerticalLayout {

    private final TraspasoConfiguracionService service;
    private final LineaAccessService acceso;

    private final Anchor descarga = new Anchor();
    private final ComboBox<Path> archivo = new ComboBox<>("Archivo a importar");
    private final Grid<Grupo> grupos = new Grid<>();
    private final Grid<Diferencia> diferencias = new Grid<>();
    private final Span estado = new Span();
    private final Button importar = new Button("Importar", VaadinIcon.UPLOAD.create());
    private Revision revision;

    public TraspasoConfiguracionView(TraspasoConfiguracionService service, LineaAccessService acceso) {
        this.service = service;
        this.acceso = acceso;
        setPadding(true);
        setSpacing(true);

        add(new H3("Pasar configuracion a otro equipo"));
        add(texto("Lleva lo configurado en este equipo (por ejemplo, la PC de diseno) a otro (el servidor), sin tocar el "
                + "historial del que lo recibe. Se usa asi: aqui se configura y se prueba, se exporta, se empaca la app y en el "
                + "servidor se importa. Cuando se cambie algo mas adelante, se vuelve a exportar e importar."));

        // 1. Exportar
        add(new H4("1. Exportar (en el equipo donde se configuro)"));
        add(texto("El archivo lleva: los archivos de configuracion (PLC, lineas y medidores, mezcladores, TAG, generadores y "
                + "sus mapas de registros); usuarios y permisos; umbrales de alarmas y del horometro; modelos de medidor con "
                + "sus registros; limites de calidad y tensiones nominales; planes de mantenimiento y tecnicos; tarifas de la "
                + "red; y los eventos copiados a mano del historial de los controladores de los generadores. No lleva "
                + "historial: energia, horometro, alarmas ocurridas, mantenimientos realizados, stock, arranques."));
        descarga.setText("Exportar configuracion");
        descarga.getElement().getThemeList().add("button");
        descarga.getElement().getThemeList().add("primary");
        // Un solo enlace fijo: el archivo y su nombre (con la hora) se arman al pedirlo. Antes se cambiaba el enlace
        // en cada clic y, si el pedido llegaba despues del cambio, el navegador recibia una respuesta vacia ("f.txt").
        String usuario = acceso.usuarioActual();
        descarga.setHref(event -> {
            event.setFileName(TraspasoConfiguracionService.nombreArchivoExportacion());
            event.setContentType("application/zip");
            try (OutputStream out = event.getOutputStream()) {
                service.exportar(out, usuario);
            }
        }, AttachmentType.DOWNLOAD);
        add(descarga);
        add(texto("El archivo contiene las claves cifradas de los usuarios: guardarlo y pasarlo con cuidado."));

        // 2. Importar
        add(new H4("2. Importar (en el equipo que recibe)"));
        add(texto("Copie el archivo exportado a la carpeta " + TraspasoConfiguracionService.CARPETA_IMPORTAR
                + " de este equipo, elijalo y pulse Revisar: se muestra que cambia, sin aplicar nada. Al importar se guarda "
                + "antes una copia de la configuracion y de la base de este equipo en C:\\LineaBaseX\\backup. Usuarios: se "
                + "crean los que faltan y en los que existen se actualizan rol, zona y permisos, nunca la clave. Lo que esta "
                + "solo en este equipo se deja como esta."));
        archivo.setItemLabelGenerator(p -> p.getFileName().toString() + "  ("
                + new SimpleDateFormat("dd-MM-yyyy HH:mm").format(new Date(p.toFile().lastModified())) + ")");
        archivo.setWidth("560px");
        archivo.addValueChangeListener(e -> limpiarRevision());
        Button actualizar = new Button("Actualizar lista", VaadinIcon.REFRESH.create(), e -> cargarArchivos());
        Button revisar = new Button("Revisar", VaadinIcon.SEARCH.create(), e -> revisar());
        revisar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        importar.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);
        importar.setEnabled(false);
        importar.addClickListener(e -> confirmar());
        HorizontalLayout fila = new HorizontalLayout(archivo, actualizar, revisar, importar);
        fila.setAlignItems(Alignment.END);
        fila.getStyle().set("flex-wrap", "wrap");
        add(fila);

        estado.getStyle().set("font-size", "14px").set("white-space", "pre-line");
        add(estado);

        grupos.addColumn(Grupo::grupo).setHeader("Que").setAutoWidth(true).setFlexGrow(1);
        grupos.addColumn(Grupo::nuevos).setHeader("Nuevos").setAutoWidth(true).setFlexGrow(0);
        grupos.addColumn(Grupo::cambian).setHeader("Cambian").setAutoWidth(true).setFlexGrow(0);
        grupos.addColumn(Grupo::iguales).setHeader("Iguales").setAutoWidth(true).setFlexGrow(0);
        grupos.addColumn(Grupo::seDejan).setHeader("Solo en este equipo (se dejan)").setAutoWidth(true).setFlexGrow(0);
        grupos.addColumn(Grupo::seQuitan).setHeader("Se quitan").setAutoWidth(true).setFlexGrow(0);
        grupos.setAllRowsVisible(true);
        grupos.setVisible(false);
        add(grupos);

        diferencias.addColumn(Diferencia::grupo).setHeader("Que").setAutoWidth(true).setFlexGrow(0);
        diferencias.addColumn(Diferencia::elemento).setHeader("Elemento").setAutoWidth(true).setFlexGrow(0);
        diferencias.addColumn(Diferencia::cambio).setHeader("Cambio").setFlexGrow(1);
        diferencias.setHeight("420px");
        diferencias.setVisible(false);
        add(diferencias);

        cargarArchivos();
    }

    private void cargarArchivos() {
        Path elegido = archivo.getValue();
        var lista = service.archivosParaImportar();
        archivo.setItems(lista);
        if (elegido != null && lista.contains(elegido)) archivo.setValue(elegido);
        else if (!lista.isEmpty()) archivo.setValue(lista.get(0));
        if (lista.isEmpty()) {
            estado.setText("No hay archivos en " + TraspasoConfiguracionService.CARPETA_IMPORTAR + ". Copie ahi el archivo exportado "
                    + "y pulse Actualizar lista.");
        }
    }

    private void limpiarRevision() {
        revision = null;
        importar.setEnabled(false);
        grupos.setVisible(false);
        diferencias.setVisible(false);
        estado.setText("");
    }

    private void revisar() {
        limpiarRevision();
        if (archivo.getValue() == null) {
            estado.setText("Elija un archivo.");
            return;
        }
        try {
            Paquete p = service.leer(archivo.getValue());
            revision = service.revisar(p);
        } catch (Exception ex) {
            estado.setText("No se pudo leer el archivo: " + ex.getMessage());
            return;
        }
        mostrar(revision, "Archivo de " + revision.paquete().origen() + ".\n"
                + (revision.hayCambios() ? "Esto es lo que cambiaria en este equipo. Todavia no se aplico nada."
                : "Este equipo ya tiene esta configuracion: no hay nada para importar."));
        importar.setEnabled(revision.hayCambios());
    }

    private void mostrar(Revision r, String encabezado) {
        StringBuilder sb = new StringBuilder(encabezado);
        for (String aviso : r.avisos()) sb.append("\n").append(aviso);
        estado.setText(sb.toString());
        grupos.setItems(r.grupos());
        grupos.setVisible(true);
        diferencias.setItems(r.diferencias());
        diferencias.setVisible(!r.diferencias().isEmpty());
    }

    private void confirmar() {
        if (revision == null) return;
        Dialog d = new Dialog();
        d.setHeaderTitle("Importar configuracion");
        d.add(texto("Se va a aplicar en este equipo la configuracion del archivo " + revision.paquete().archivo()
                + " (" + revision.paquete().origen() + "). Antes se guarda una copia de la configuracion y de la base de este "
                + "equipo. El historial no se toca. Despues hay que reiniciar la app."));
        Button si = new Button("Importar", e -> {
            d.close();
            aplicar();
        });
        si.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);
        d.getFooter().add(new Button("Cancelar", e -> d.close()), si);
        d.open();
    }

    private void aplicar() {
        Paquete p = revision.paquete();
        importar.setEnabled(false);
        try {
            Resultado res = service.importar(p, acceso.usuarioActual());
            mostrar(res.revision(), "Configuracion importada desde " + p.archivo() + ".\nCopia previa en " + res.respaldo()
                    + " (para volver atras, importar el archivo configuracion-de-este-equipo.zip de esa carpeta).\n"
                    + "Reinicie la app para que todo tome la configuracion nueva (lectura de PLC y medidores, alarmas, usuarios).");
            revision = null;
        } catch (Exception ex) {
            estado.setText("No se pudo importar: " + ex.getMessage());
        }
    }

    private static Span texto(String t) {
        Span s = new Span(t);
        s.getStyle().set("font-size", "14px").set("color", "#444").set("max-width", "110ch");
        return s;
    }
}
