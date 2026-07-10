package com.example.base.ui;

import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.security.Usuario;
import com.example.security.UsuarioRepository;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.stream.Collectors;

@PageTitle("Usuarios | LineaBase")
@Route(value = "usuarios", layout = MainLayout.class)
@RolesAllowed("ADMIN")
public class UsuariosView extends VerticalLayout {

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;

    private final Grid<Usuario> grid = new Grid<>(Usuario.class, false);

    private final TextField usernameField = new TextField("Usuario");
    private final PasswordField passwordField = new PasswordField("Contraseña");
    private final Select<Usuario.Rol> rolSelect = new Select<>();
    private final ComboBox<String> zonaCombo = new ComboBox<>("Zona");
    private final Checkbox habilitadoCheckbox = new Checkbox("Habilitado", true);
    private final Span formTitle = new Span("Nuevo usuario");

    private Usuario usuarioEnEdicion;

    public UsuariosView(UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder,
                         ConfigLoaderService configLoaderService) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H3("Gestión de Usuarios"));

        List<String> zonas = configLoaderService.loadLineaIDConfig().stream()
                .map(l -> (String) l.get("zona"))
                .filter(z -> z != null && !z.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        zonaCombo.setItems(zonas);
        zonaCombo.setWidth("220px");

        rolSelect.setLabel("Rol");
        rolSelect.setItems(Usuario.Rol.values());
        rolSelect.setValue(Usuario.Rol.USUARIO);
        rolSelect.addValueChangeListener(e -> {
            boolean esAdmin = e.getValue() == Usuario.Rol.ADMIN;
            zonaCombo.setEnabled(!esAdmin);
            if (esAdmin) {
                zonaCombo.clear();
            }
        });

        usernameField.setWidth("200px");
        passwordField.setWidth("200px");
        passwordField.setHelperText("Vacío = no cambiar (al editar)");

        Button guardarBtn = new Button("Guardar", e -> guardar());
        guardarBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button nuevoBtn = new Button("Nuevo", e -> limpiarFormulario());
        nuevoBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout formLayout = new HorizontalLayout(
                usernameField, passwordField, rolSelect, zonaCombo, habilitadoCheckbox, guardarBtn, nuevoBtn
        );
        formLayout.setAlignItems(Alignment.END);
        formLayout.getStyle().set("flex-wrap", "wrap");

        add(formTitle, formLayout);

        grid.addColumn(Usuario::getUsername).setHeader("Usuario").setAutoWidth(true);
        grid.addColumn(Usuario::getRol).setHeader("Rol").setAutoWidth(true);
        grid.addColumn(u -> u.getRol() == Usuario.Rol.ADMIN ? "Todas" : (u.getZona() == null ? "-" : u.getZona()))
                .setHeader("Zona").setAutoWidth(true);
        grid.addColumn(u -> u.isHabilitado() ? "Sí" : "No").setHeader("Habilitado").setAutoWidth(true);
        grid.addComponentColumn(this::crearAccionesColumna).setHeader("Acciones").setAutoWidth(true);
        grid.setSizeFull();

        add(grid);
        setFlexGrow(1, grid);

        refrescarGrid();
    }

    private HorizontalLayout crearAccionesColumna(Usuario usuario) {
        Button editarBtn = new Button("Editar", e -> cargarEnFormulario(usuario));
        Button eliminarBtn = new Button("Eliminar", e -> eliminar(usuario));
        eliminarBtn.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        return new HorizontalLayout(editarBtn, eliminarBtn);
    }

    private void cargarEnFormulario(Usuario usuario) {
        usuarioEnEdicion = usuario;
        formTitle.setText("Editando: " + usuario.getUsername());
        usernameField.setValue(usuario.getUsername());
        usernameField.setReadOnly(true);
        passwordField.clear();
        rolSelect.setValue(usuario.getRol());
        zonaCombo.setValue(usuario.getZona() == null ? "" : usuario.getZona());
        zonaCombo.setEnabled(usuario.getRol() != Usuario.Rol.ADMIN);
        habilitadoCheckbox.setValue(usuario.isHabilitado());
    }

    private void limpiarFormulario() {
        usuarioEnEdicion = null;
        formTitle.setText("Nuevo usuario");
        usernameField.clear();
        usernameField.setReadOnly(false);
        passwordField.clear();
        rolSelect.setValue(Usuario.Rol.USUARIO);
        zonaCombo.clear();
        zonaCombo.setEnabled(true);
        habilitadoCheckbox.setValue(true);
    }

    private void guardar() {
        String username = usernameField.getValue();
        if (username == null || username.isBlank()) {
            mostrarError("El usuario es obligatorio");
            return;
        }
        Usuario.Rol rol = rolSelect.getValue();
        if (rol == Usuario.Rol.USUARIO && (zonaCombo.getValue() == null || zonaCombo.getValue().isBlank())) {
            mostrarError("Debes asignar una zona a un usuario que no es ADMIN");
            return;
        }

        Usuario usuario;
        if (usuarioEnEdicion == null) {
            if (usuarioRepository.existsByUsernameIgnoreCase(username)) {
                mostrarError("Ya existe un usuario con ese nombre");
                return;
            }
            if (passwordField.getValue() == null || passwordField.getValue().isBlank()) {
                mostrarError("La contraseña es obligatoria para un usuario nuevo");
                return;
            }
            usuario = new Usuario();
            usuario.setUsername(username);
            usuario.setPassword(passwordEncoder.encode(passwordField.getValue()));
        } else {
            usuario = usuarioEnEdicion;
            if (passwordField.getValue() != null && !passwordField.getValue().isBlank()) {
                usuario.setPassword(passwordEncoder.encode(passwordField.getValue()));
            }
        }

        usuario.setRol(rol);
        usuario.setZona(rol == Usuario.Rol.ADMIN ? null : zonaCombo.getValue());
        usuario.setHabilitado(habilitadoCheckbox.getValue());

        usuarioRepository.save(usuario);
        Notification.show("Usuario guardado", 2500, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);

        limpiarFormulario();
        refrescarGrid();
    }

    private void eliminar(Usuario usuario) {
        usuarioRepository.delete(usuario);
        refrescarGrid();
        if (usuarioEnEdicion != null && usuarioEnEdicion.getId().equals(usuario.getId())) {
            limpiarFormulario();
        }
    }

    private void mostrarError(String mensaje) {
        Notification.show(mensaje, 3000, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    private void refrescarGrid() {
        grid.setItems(usuarioRepository.findAll());
    }
}
