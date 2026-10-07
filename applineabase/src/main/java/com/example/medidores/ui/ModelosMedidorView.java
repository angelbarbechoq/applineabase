package com.example.medidores.ui;

import com.example.base.ui.MainLayout;
import com.example.base.ui.NotificacionesUtil;
import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.dataacquisition.service.ModbusTcpConexion;
import com.example.medidores.model.FuncionLectura;
import com.example.medidores.model.ModeloMedidor;
import com.example.medidores.model.OrdenPalabras;
import com.example.medidores.model.ParametroMedidor;
import com.example.medidores.model.RegistroModelo;
import com.example.medidores.model.TipoDato;
import com.example.medidores.service.DefinicionModelo;
import com.example.medidores.service.LectorMedidorService;
import com.example.medidores.service.ModeloMedidorService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.shared.Tooltip;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Catálogo de modelos de medidor (Configuracion > Modelos de medidor): por modelo, en qué
 * registro y con qué formato está cada parámetro. El lector de pasarelas lo toma en el ciclo
 * siguiente, sin recompilar ni reiniciar. La pestaña "Probar lectura" lee un medidor real con
 * el modelo elegido, sin guardar nada, para compararlo con su pantalla.
 */
@PageTitle("Modelos de medidor | LineaBase")
@Route(value = "configuracion/medidores", layout = MainLayout.class)
@RolesAllowed("ADMIN")
public class ModelosMedidorView extends VerticalLayout {

    private static final int TIMEOUT_PRUEBA_MS = 3000;

    private final ModeloMedidorService service;
    private final LectorMedidorService lector;
    private final ConfigLoaderService configLoaderService;

    private final ComboBox<ModeloMedidor> modeloCombo = new ComboBox<>("Modelo");
    private final Span estadoModelo = new Span();
    private final Span usoModelo = new Span();
    private final Grid<ParametroMedidor> registrosGrid = new Grid<>();
    private final Grid<FilaPrueba> pruebaGrid = new Grid<>();
    private final Span resumenPrueba = new Span();

    private ModeloMedidor modelo;

    private record FilaPrueba(ParametroMedidor parametro, String registro, String valor, String observacion) {
    }

    public ModelosMedidorView(ModeloMedidorService service, LectorMedidorService lector,
                              ConfigLoaderService configLoaderService) {
        this.service = service;
        this.lector = lector;
        this.configLoaderService = configLoaderService;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Modelos de medidor"));
        add(aviso("Los cambios rigen desde el siguiente ciclo de lectura (1 minuto), sin reiniciar. "
                + "Los registros se cargan como figuran en el manual del medidor. Un medidor solo se lee por "
                + "pasarela si su modelo tiene completos los parametros basicos."));

        modeloCombo.setItemLabelGenerator(ModeloMedidor::getNombre);
        modeloCombo.setWidth("220px");
        modeloCombo.addValueChangeListener(e -> seleccionar(e.getValue()));

        Button nuevo = new Button("Nuevo modelo", VaadinIcon.PLUS.create(), e -> abrirDialogoModelo(null));
        nuevo.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button editar = new Button("Editar", VaadinIcon.EDIT.create(), e -> {
            if (modelo != null) abrirDialogoModelo(modelo);
        });
        Button duplicar = new Button("Duplicar", VaadinIcon.COPY.create(), e -> {
            if (modelo != null) abrirDialogoDuplicar();
        });
        Button eliminar = new Button("Eliminar", VaadinIcon.TRASH.create(), e -> {
            if (modelo != null) confirmarEliminar();
        });
        eliminar.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout barra = new HorizontalLayout(modeloCombo, nuevo, editar, duplicar, eliminar);
        barra.setAlignItems(Alignment.END);
        barra.getStyle().set("flex-wrap", "wrap");
        HorizontalLayout info = new HorizontalLayout(estadoModelo, usoModelo);
        info.setAlignItems(Alignment.CENTER);
        add(barra, info);

        TabSheet tabs = new TabSheet();
        tabs.setSizeFull();
        tabs.add("Registros", crearPanelRegistros());
        tabs.add("Probar lectura", crearPanelPrueba());
        add(tabs);
        setFlexGrow(1, tabs);

        recargar(null);
    }

    // ================= Modelo seleccionado =================

    private void recargar(Long idSeleccionar) {
        List<ModeloMedidor> modelos = service.listar();
        modeloCombo.setItems(modelos);
        ModeloMedidor elegido = modelos.stream()
                .filter(m -> idSeleccionar != null && idSeleccionar.equals(m.getId()))
                .findFirst()
                .orElse(modelos.isEmpty() ? null : modelos.get(0));
        modeloCombo.setValue(elegido);
        seleccionar(elegido);
    }

    private void seleccionar(ModeloMedidor m) {
        modelo = m;
        registrosGrid.getDataProvider().refreshAll();
        pruebaGrid.setItems(List.of());
        resumenPrueba.setText("");
        if (m == null) {
            estadoModelo.setText("");
            usoModelo.setText("");
            return;
        }
        List<ParametroMedidor> faltan = DefinicionModelo.de(m).faltantesBasicos();
        if (faltan.isEmpty()) {
            badge(estadoModelo, "Completo: se puede leer por pasarela", "#d4edda", "#155724");
        } else {
            badge(estadoModelo, "Incompleto: faltan " + faltan.size() + " parametros basicos", "#fff3cd", "#856404");
        }
        List<String> usan = service.lineasQueLoUsan(m.getNombre());
        usoModelo.setText(usan.isEmpty() ? "Sin lineas asignadas" : "Lo usan: " + String.join(", ", usan));
        usoModelo.getStyle().set("font-size", "12px").set("color", "#555");
    }

    // ================= Pestana Registros =================

    private VerticalLayout crearPanelRegistros() {
        registrosGrid.addColumn(p -> p.isBasico() ? "Basico" : "Calidad").setHeader("Grupo").setAutoWidth(true);
        registrosGrid.addColumn(ParametroMedidor::getEtiqueta).setHeader("Parametro").setAutoWidth(true);
        registrosGrid.addColumn(ParametroMedidor::getUnidad).setHeader("Unidad").setAutoWidth(true);
        registrosGrid.addComponentColumn(this::celdaRegistro).setHeader("Registro").setAutoWidth(true);
        registrosGrid.addColumn(p -> texto(p, r -> r.getTipoDato().getEtiqueta())).setHeader("Tipo de dato").setAutoWidth(true);
        registrosGrid.addColumn(p -> texto(p, r -> r.getOrdenPalabras().getEtiqueta())).setHeader("Orden").setAutoWidth(true);
        registrosGrid.addColumn(p -> texto(p, r -> formatear(r.getEscala()))).setHeader("Escala").setAutoWidth(true);
        registrosGrid.addColumn(p -> texto(p, r -> r.isPf4Cuadrantes() ? "Si" : "")).setHeader("PF 4 cuadrantes").setAutoWidth(true);
        registrosGrid.setItems(Arrays.asList(ParametroMedidor.values()));
        registrosGrid.addItemClickListener(e -> {
            if (modelo != null) abrirDialogoRegistro(e.getItem());
        });
        registrosGrid.setSizeFull();

        VerticalLayout panel = new VerticalLayout(
                new Span("Clic en un parametro para cargar o corregir su registro."), registrosGrid);
        panel.setSizeFull();
        panel.setPadding(false);
        panel.setFlexGrow(1, registrosGrid);
        return panel;
    }

    private Span celdaRegistro(ParametroMedidor p) {
        RegistroModelo r = modelo == null ? null : modelo.registroDe(p);
        Span s = new Span();
        if (r != null) {
            s.setText(String.valueOf(r.getRegistro()));
        } else if (p.isBasico()) {
            badge(s, "Falta", "#f8d7da", "#721c24");
        } else {
            badge(s, "No disponible", "#e2e3e5", "#383d41");
        }
        return s;
    }

    private String texto(ParametroMedidor p, java.util.function.Function<RegistroModelo, String> campo) {
        RegistroModelo r = modelo == null ? null : modelo.registroDe(p);
        return r == null ? "" : campo.apply(r);
    }

    private void abrirDialogoRegistro(ParametroMedidor parametro) {
        RegistroModelo actual = modelo.registroDe(parametro);

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(modelo.getNombre() + ": " + parametro.getEtiqueta());
        dialog.setWidth("520px");

        IntegerField registroField = new IntegerField("Registro (como en el manual)");
        registroField.setMin(0);
        registroField.setMax(65535);
        registroField.setStepButtonsVisible(false);
        ComboBox<TipoDato> tipoField = new ComboBox<>("Tipo de dato");
        tipoField.setItems(TipoDato.values());
        tipoField.setItemLabelGenerator(TipoDato::getEtiqueta);
        ComboBox<OrdenPalabras> ordenField = new ComboBox<>("Orden de palabras");
        ordenField.setItems(OrdenPalabras.values());
        ordenField.setItemLabelGenerator(OrdenPalabras::getEtiqueta);
        ordenField.setHelperText("Solo importa en valores de 32/64 bits");
        NumberField escalaField = new NumberField("Escala (multiplicador)");
        escalaField.setHelperText("1 = sin cambio. Ej: 0.001 para pasar Wh a kWh, 0.1 si el valor viene x10");
        Checkbox pfField = new Checkbox("Factor de potencia en formato de 4 cuadrantes");
        pfField.setVisible(parametro.esFactorPotencia());

        if (actual != null) {
            registroField.setValue(actual.getRegistro());
            tipoField.setValue(actual.getTipoDato());
            ordenField.setValue(actual.getOrdenPalabras());
            escalaField.setValue(actual.getEscala());
            pfField.setValue(actual.isPf4Cuadrantes());
        } else {
            tipoField.setValue(TipoDato.FLOAT32);
            ordenField.setValue(OrdenPalabras.NORMAL);
            escalaField.setValue(1.0);
        }

        FormLayout form = new FormLayout(registroField, tipoField, ordenField, escalaField, pfField);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("320px", 2));
        form.setColspan(escalaField, 2);
        form.setColspan(pfField, 2);
        dialog.add(form);

        Button cancelar = new Button("Cancelar", e -> dialog.close());
        Button guardar = new Button("Guardar", e -> {
            if (registroField.getValue() == null || tipoField.getValue() == null || ordenField.getValue() == null
                    || escalaField.getValue() == null) {
                NotificacionesUtil.mostrarError("Completa registro, tipo, orden y escala");
                return;
            }
            RegistroModelo r = new RegistroModelo(parametro, registroField.getValue(), tipoField.getValue());
            r.setOrdenPalabras(ordenField.getValue());
            r.setEscala(escalaField.getValue());
            r.setPf4Cuadrantes(Boolean.TRUE.equals(pfField.getValue()));
            ejecutar(() -> service.guardarRegistro(modelo.getId(), r), "Registro guardado", dialog);
        });
        guardar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        if (actual != null) {
            Button noDisponible = new Button("Marcar No disponible", e -> {
                if (parametro.isBasico() && !service.lineasQueLoUsan(modelo.getNombre()).isEmpty()) {
                    NotificacionesUtil.mostrarError("Es un parametro basico y hay lineas con este modelo: dejarian de leerse");
                    return;
                }
                ejecutar(() -> service.quitarRegistro(modelo.getId(), parametro), "Marcado como No disponible", dialog);
            });
            noDisponible.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
            dialog.getFooter().add(noDisponible);
        }
        dialog.getFooter().add(cancelar, guardar);
        dialog.open();
    }

    // ================= Alta / edicion / duplicado / baja de modelos =================

    private void abrirDialogoModelo(ModeloMedidor enEdicion) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(enEdicion == null ? "Nuevo modelo de medidor" : "Editar modelo " + enEdicion.getNombre());
        dialog.setWidth("520px");

        TextField nombreField = new TextField("Nombre (ej. PM5560)");
        TextField descripcionField = new TextField("Descripcion (marca, modelo, notas)");
        ComboBox<FuncionLectura> funcionField = new ComboBox<>("Funcion Modbus de lectura");
        funcionField.setItems(FuncionLectura.values());
        funcionField.setItemLabelGenerator(FuncionLectura::getEtiqueta);
        Checkbox manualField = new Checkbox("Registros numerados como en el manual (base 1: se resta 1 al leer)");
        // Checkbox no implementa HasTooltip: se usa Tooltip.forComponent como en UsuariosView.
        Tooltip.forComponent(manualField)
                .withText("Schneider y la mayoria de los fabricantes publican los registros en base 1. "
                        + "Desmarcar solo si el manual da las direcciones tal como viajan en el cable (base 0).")
                .withHoverDelay(200)
                .withHideDelay(5000);

        if (enEdicion != null) {
            nombreField.setValue(enEdicion.getNombre());
            descripcionField.setValue(enEdicion.getDescripcion() == null ? "" : enEdicion.getDescripcion());
            funcionField.setValue(enEdicion.getFuncionLectura());
            manualField.setValue(enEdicion.isNumeracionManual());
        } else {
            funcionField.setValue(FuncionLectura.HOLDING);
            manualField.setValue(true);
        }

        FormLayout form = new FormLayout(nombreField, funcionField, descripcionField, manualField);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("320px", 2));
        form.setColspan(descripcionField, 2);
        form.setColspan(manualField, 2);
        dialog.add(form);

        Button cancelar = new Button("Cancelar", e -> dialog.close());
        Button guardar = new Button("Guardar", e -> {
            ModeloMedidor m = enEdicion != null ? enEdicion : new ModeloMedidor();
            m.setNombre(nombreField.getValue());
            m.setDescripcion(descripcionField.getValue());
            m.setFuncionLectura(funcionField.getValue() == null ? FuncionLectura.HOLDING : funcionField.getValue());
            m.setNumeracionManual(Boolean.TRUE.equals(manualField.getValue()));
            ejecutar(() -> service.guardarModelo(m), "Modelo guardado", dialog);
        });
        guardar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(cancelar, guardar);
        dialog.open();
    }

    private void abrirDialogoDuplicar() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Duplicar " + modelo.getNombre());
        dialog.setWidth("420px");
        TextField nombreField = new TextField("Nombre del modelo nuevo");
        TextField descripcionField = new TextField("Descripcion");
        nombreField.setWidthFull();
        descripcionField.setWidthFull();
        dialog.add(new VerticalLayout(
                new Span("Copia todos los registros; despues se corrigen los que cambien."),
                nombreField, descripcionField));

        Button cancelar = new Button("Cancelar", e -> dialog.close());
        Button duplicar = new Button("Duplicar", e ->
                ejecutar(() -> service.duplicar(modelo, nombreField.getValue(), descripcionField.getValue()),
                        "Modelo duplicado", dialog));
        duplicar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(cancelar, duplicar);
        dialog.open();
    }

    private void confirmarEliminar() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Eliminar modelo " + modelo.getNombre());
        dialog.add(new Span("Se borra el modelo y todos sus registros. Solo se puede si ninguna linea lo usa."));
        Button cancelar = new Button("Cancelar", e -> dialog.close());
        Button eliminar = new Button("Eliminar", e -> {
            try {
                service.eliminar(modelo);
                dialog.close();
                exito("Modelo eliminado");
                recargar(null);
            } catch (IllegalArgumentException ex) {
                NotificacionesUtil.mostrarError(ex.getMessage());
            }
        });
        eliminar.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(cancelar, eliminar);
        dialog.open();
    }

    private void ejecutar(java.util.function.Supplier<ModeloMedidor> accion, String mensaje, Dialog dialog) {
        try {
            ModeloMedidor guardado = accion.get();
            dialog.close();
            exito(mensaje);
            recargar(guardado.getId());
        } catch (IllegalArgumentException ex) {
            NotificacionesUtil.mostrarError(ex.getMessage());
        }
    }

    // ================= Pestana Probar lectura =================

    private VerticalLayout crearPanelPrueba() {
        List<Map<String, Object>> gateways = configLoaderService.loadGatewayConfig();
        List<String> nombresGateways = gateways.stream().map(g -> String.valueOf(g.get("nombre"))).toList();
        List<Map<String, Object>> lineasEnGateways = configLoaderService.loadLineaIDConfig().stream()
                .filter(l -> nombresGateways.contains(String.valueOf(l.get("nombrePLC"))))
                .toList();

        ComboBox<Map<String, Object>> lineaCombo = new ComboBox<>("Medidor ya configurado (opcional)");
        lineaCombo.setItems(lineasEnGateways);
        lineaCombo.setItemLabelGenerator(l -> l.get("lineaMaquina") + " (" + l.get("nombrePLC") + ", ID " + l.get("id") + ")");
        lineaCombo.setWidth("320px");

        ComboBox<Map<String, Object>> gatewayCombo = new ComboBox<>("Pasarela");
        gatewayCombo.setItems(gateways);
        gatewayCombo.setItemLabelGenerator(g -> g.get("nombre") + " (" + g.get("ipAddress") + ")");
        gatewayCombo.setWidth("260px");

        IntegerField unitIdField = new IntegerField("Unit ID");
        unitIdField.setMin(0);
        unitIdField.setMax(255);
        unitIdField.setWidth("110px");

        lineaCombo.addValueChangeListener(e -> {
            Map<String, Object> l = e.getValue();
            if (l == null) return;
            gateways.stream().filter(g -> String.valueOf(g.get("nombre")).equals(String.valueOf(l.get("nombrePLC"))))
                    .findFirst().ifPresent(gatewayCombo::setValue);
            unitIdField.setValue(((Number) l.get("id")).intValue());
            String nombreModelo = String.valueOf(l.get("modeloMedidor"));
            service.listar().stream().filter(m -> m.getNombre().equalsIgnoreCase(nombreModelo)).findFirst()
                    .ifPresentOrElse(modeloCombo::setValue,
                            () -> NotificacionesUtil.mostrarError("El modelo " + nombreModelo + " no existe en el catalogo"));
        });

        Button probar = new Button("Probar lectura", VaadinIcon.PLAY.create(),
                e -> probar(gatewayCombo.getValue(), unitIdField.getValue()));
        probar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout form = new HorizontalLayout(lineaCombo, gatewayCombo, unitIdField, probar);
        form.setAlignItems(Alignment.END);
        form.getStyle().set("flex-wrap", "wrap");

        pruebaGrid.addColumn(f -> f.parametro().getEtiqueta()).setHeader("Parametro").setAutoWidth(true);
        pruebaGrid.addColumn(FilaPrueba::registro).setHeader("Registro").setAutoWidth(true);
        pruebaGrid.addColumn(FilaPrueba::valor).setHeader("Valor leido").setAutoWidth(true);
        pruebaGrid.addColumn(FilaPrueba::observacion).setHeader("Observacion").setFlexGrow(1);
        pruebaGrid.setSizeFull();

        VerticalLayout panel = new VerticalLayout(
                new Span("Lee el medidor con el modelo elegido arriba, sin guardar nada. "
                        + "Comparar cada valor con la pantalla del medidor."),
                form, resumenPrueba, pruebaGrid);
        panel.setSizeFull();
        panel.setPadding(false);
        panel.setFlexGrow(1, pruebaGrid);
        return panel;
    }

    private void probar(Map<String, Object> gateway, Integer unitId) {
        if (modelo == null || gateway == null || unitId == null) {
            NotificacionesUtil.mostrarError("Elija modelo, pasarela y Unit ID");
            return;
        }
        DefinicionModelo def = DefinicionModelo.de(modelo);
        if (def.registros().isEmpty()) {
            NotificacionesUtil.mostrarError("El modelo " + modelo.getNombre() + " no tiene registros cargados");
            return;
        }
        String ip = String.valueOf(gateway.get("ipAddress"));
        long inicio = System.currentTimeMillis();
        try (ModbusTcpConexion conexion = new ModbusTcpConexion(ip, TIMEOUT_PRUEBA_MS)) {
            LectorMedidorService.Lectura lectura = lector.leer(conexion, unitId, def, def.registros().keySet());
            List<FilaPrueba> filas = new ArrayList<>();
            for (ParametroMedidor p : ParametroMedidor.values()) {
                DefinicionModelo.Registro r = def.registros().get(p);
                if (r == null) {
                    continue;
                }
                String registro = r.registroManual() + " (" + r.tipoDato().name() + ")";
                Double valor = lectura.valores().get(p);
                if (valor == null) {
                    filas.add(new FilaPrueba(p, registro, "-", "Error: " + lectura.errores().get(p)));
                    continue;
                }
                String observacion = "";
                if (r.pf4Cuadrantes()) {
                    observacion = "Valor real decodificado: " + formatear(LectorMedidorService.decodificarPf4Cuadrantes(valor));
                }
                filas.add(new FilaPrueba(p, registro, formatear(valor) + " " + p.getUnidad(), observacion));
            }
            pruebaGrid.setItems(filas);
            long ms = System.currentTimeMillis() - inicio;
            badge(resumenPrueba, String.format("Leido en %d ms: %d valores, %d con error",
                    ms, lectura.valores().size(), lectura.errores().size()),
                    lectura.errores().isEmpty() ? "#d4edda" : "#fff3cd",
                    lectura.errores().isEmpty() ? "#155724" : "#856404");
        } catch (ModbusTcpConexion.ExcepcionModbus ex) {
            pruebaGrid.setItems(List.of());
            badge(resumenPrueba, "El medidor no contesto: " + ex.getMessage(), "#f8d7da", "#721c24");
        } catch (IOException ex) {
            pruebaGrid.setItems(List.of());
            badge(resumenPrueba, "Sin comunicacion con la pasarela " + ip + ": " + ex.getMessage(), "#f8d7da", "#721c24");
        }
    }

    // ================= Utilidades =================

    private static String formatear(double valor) {
        return String.format(Locale.ROOT, "%.3f", valor);
    }

    private static Span aviso(String texto) {
        Span s = new Span(texto);
        s.getStyle().set("background", "#fff3cd").set("color", "#7a5b00").set("padding", "6px 12px")
                .set("border-radius", "6px").set("font-size", "12px");
        return s;
    }

    private static void badge(Span s, String texto, String fondo, String color) {
        s.setText(texto);
        s.getStyle().set("background-color", fondo).set("color", color).set("padding", "2px 10px")
                .set("border-radius", "10px").set("font-weight", "600").set("font-size", "12px");
    }

    private static void exito(String mensaje) {
        Notification.show(mensaje, 2500, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }
}
