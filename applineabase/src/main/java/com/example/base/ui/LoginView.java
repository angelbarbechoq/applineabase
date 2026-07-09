package com.example.base.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.login.LoginOverlay;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@PageTitle("Iniciar sesión | LineaBase")
@Route(value = "login", autoLayout = false)
@AnonymousAllowed
public class LoginView extends LoginOverlay implements BeforeEnterObserver {

    public LoginView() {
        LoginI18n i18n = LoginI18n.createDefault();
        i18n.setHeader(new LoginI18n.Header());
        i18n.getHeader().setTitle("LineaBaseX");
        i18n.getHeader().setDescription("Monitorización energética de planta");
        i18n.getForm().setUsername("Usuario");
        i18n.getForm().setPassword("Contraseña");
        i18n.getForm().setSubmit("Ingresar");
        i18n.getForm().setTitle("Iniciar sesión");
        i18n.getErrorMessage().setTitle("Usuario o contraseña incorrectos");
        i18n.getErrorMessage().setMessage("Verifica tus credenciales e intenta nuevamente.");
        setI18n(i18n);

        setAction("login");
        setOpened(true);
        setForgotPasswordButtonVisible(false);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (event.getLocation().getQueryParameters().getParameters().containsKey("error")) {
            setError(true);
        }
    }
}
