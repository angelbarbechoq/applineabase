package com.example.base.ui;

import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.security.LineaAccessService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.Unit;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.avatar.AvatarVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.SvgIcon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.*;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.Layout;
import com.vaadin.flow.server.menu.MenuConfiguration;
import com.vaadin.flow.server.menu.MenuEntry;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;

import java.time.LocalDateTime;
import java.util.List;

@Layout
@PermitAll
public final class MainLayout extends AppLayout {

    // Los datos se leen cada 60s (DataAcquisitionTask.CYCLE_INTERVAL), así que revisar
    // más seguido que eso no aporta nada nuevo; 30s da como máximo esa misma latencia
    // sin duplicar las peticiones del cliente en cada ciclo de lectura.
    private static final int ALARMA_POLL_INTERVAL_MS = 30000;

    private Div statusIndicator;
    private Div ultimoClickCard;
    private Div clickAnteriorCard;
    private final AuthenticationContext authenticationContext;
    private final LineaAccessService lineaAccessService;
    private final AlarmaEventoRepository alarmaEventoRepository;
    private LocalDateTime ultimaRevisionAlarmas = LocalDateTime.now();

    MainLayout(AuthenticationContext authenticationContext, LineaAccessService lineaAccessService,
               AlarmaEventoRepository alarmaEventoRepository) {
        this.authenticationContext = authenticationContext;
        this.lineaAccessService = lineaAccessService;
        this.alarmaEventoRepository = alarmaEventoRepository;
        setPrimarySection(Section.DRAWER);
        setDrawerOpened(false);  // ← Abierto inicialmente

        // ← Agregar esta línea
        getElement().getStyle().set("--vaadin-app-layout-drawer-overlay", "true");

        // ← Agregar esto (el icono de 3 líneas)
        DrawerToggle toggle = new DrawerToggle();
        addToNavbar(toggle);

        addToDrawer(createApplicationHeader(), createApplicationDrawer(), createApplicationFooter());

        // ← CSS para animaciones suaves
        getElement().executeJs(
                "const style = document.createElement('style'); " +
                        "style.textContent = `" +
                        "  [part=\"drawer\"] { " +
                        "    transition: transform 0.3s ease-in-out, opacity 0.3s ease-in-out !important; " +
                        "  } " +
                        "`; " +
                        "document.head.appendChild(style);"
        );

        // ← Auto-cierre y cierre por click fuera
        getElement().executeJs(
                "let closeTimeout; " +
                        "const appLayout = $0; " +
                        "const drawer = this.shadowRoot.querySelector('[part=\"drawer\"]'); " +
                        "const backdrop = this.shadowRoot.querySelector('[part=\"overlay\"]'); " +
                        "" +
                        "if (backdrop) { " +
                        "  backdrop.addEventListener('click', () => { " +
                        "    appLayout.setDrawerOpened(false); " +
                        "  }); " +
                        "} " +
                        "" +
                        "const observer = new MutationObserver(() => { " +
                        "  if (appLayout.drawerOpened) { " +
                        "    clearTimeout(closeTimeout); " +
                        "    closeTimeout = setTimeout(() => { " +
                        "      appLayout.setDrawerOpened(false); " +
                        "    }, 4000); " +
                        "  } " +
                        "}); " +
                        "" +
                        "observer.observe(appLayout, { attributes: true, attributeFilter: ['drawer-opened'] });",
                this.getElement()
        );
        // Agregar indicador de status en la esquina superior derecha
        clickAnteriorCard = new Div();
        clickAnteriorCard.setVisible(true);
        clickAnteriorCard.getStyle()
                .set("padding", "0px")
                .set("margin-bottom", "0px")
                .set("margin-left", "8px");
        ultimoClickCard = new Div();
        ultimoClickCard.setVisible(true);
        ultimoClickCard.getStyle()
                .set("padding", "0px")
                .set("margin-bottom", "0px")
                .set("margin-left", "16px");

        statusIndicator = createStatusIndicator();

        Span userSpan = new Span(nombreUsuarioActual());
        userSpan.getStyle().set("font-size", "12px").set("color", "#666").set("margin-right", "8px");

        Button logoutBtn = new Button("Salir", e -> authenticationContext.logout());
        logoutBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);

        HorizontalLayout statusLayout = new HorizontalLayout(statusIndicator, userSpan, logoutBtn);
        statusLayout.setAlignItems(Alignment.CENTER);
        statusLayout.setSpacing(false);
        statusLayout.setPadding(false);
        statusLayout.setMargin(false);
        statusLayout.getStyle()
            .set("margin-left", "auto")
            .set("margin-right", "20px");
        addToNavbar(clickAnteriorCard);
        addToNavbar(ultimoClickCard);
        addToNavbar(statusLayout);

        if (lineaAccessService.puedeVerAlarmas()) {
            addAttachListener(e -> {
                UI ui = e.getUI();
                ui.setPollInterval(ALARMA_POLL_INTERVAL_MS);
                ui.addPollListener(pollEvent -> revisarAlarmasNuevas());
            });
        }
    }

    private void revisarAlarmasNuevas() {
        LocalDateTime desde = ultimaRevisionAlarmas;
        ultimaRevisionAlarmas = LocalDateTime.now();

        List<AlarmaEvento> nuevas = alarmaEventoRepository.findByFechaInicioAfterOrderByFechaInicioDesc(desde);
        for (AlarmaEvento alarma : nuevas) {
            Notification.show("🚨 " + alarma.getMensaje(), 8000, Notification.Position.TOP_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private String nombreUsuarioActual() {
        return authenticationContext.getAuthenticatedUser(org.springframework.security.core.userdetails.UserDetails.class)
                .map(org.springframework.security.core.userdetails.UserDetails::getUsername)
                .map(u -> lineaAccessService.esAdmin() ? u + " (Admin)" : u + " (" + lineaAccessService.zonaUsuarioActual() + ")")
                .orElse("");
    }

    private Component createApplicationHeader() {
        var appLogo = new Avatar("My Application");
        appLogo.addClassName("app-logo");
        appLogo.addThemeVariants(AvatarVariant.AURA_FILLED, AvatarVariant.XSMALL);

        var appName = new Span("My Application");
        appName.addClassName("app-name");

        var header = new HorizontalLayout(appLogo, appName);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setPadding(true);
        return header;
    }

    private Component createApplicationDrawer() {
        var scroller = new Scroller(createSideNav());
        scroller.addThemeVariants(ScrollerVariant.OVERFLOW_INDICATORS);
        return scroller;
    }

    private Component createApplicationFooter() {
        var footer = new VerticalLayout(new Span("Made with ❤️ with Vaadin"));
        footer.setAlignItems(FlexComponent.Alignment.CENTER);
        footer.addClassName("app-footer");
        return footer;
    }

    private SideNav createSideNav() {
        var nav = new SideNav();
        nav.setMinWidth(200, Unit.PIXELS);
        MenuConfiguration.getMenuEntries().forEach(entry -> nav.addItem(createSideNavItem(entry)));

        nav.addItem(new SideNavItem("Charts", "grafica", VaadinIcon.CHART.create()));
        nav.addItem(new SideNavItem("Consulta de Datos", "query", VaadinIcon.LIST.create()));
        nav.addItem(new SideNavItem("Historico", "historico", VaadinIcon.CLOCK.create()));
        if (lineaAccessService.puedeVerAlarmas()) {
            nav.addItem(new SideNavItem("Alarmas", "alarmas", VaadinIcon.BELL.create()));
        }
        if (lineaAccessService.esAdmin()) {
            nav.addItem(new SideNavItem("Config. Alarmas", "alarmas/config", VaadinIcon.COG.create()));
            nav.addItem(new SideNavItem("Usuarios", "usuarios", VaadinIcon.USERS.create()));
        }
        //nav.addSelectionListener(e -> setDrawerOpened(false));
        return nav;
    }

    private SideNavItem createSideNavItem(MenuEntry menuEntry) {
        if (menuEntry.icon() != null) {
            Component icon = null;
            if (menuEntry.icon().contains(".svg")) {
                icon = new SvgIcon(menuEntry.icon());
            } else {
                icon = new Icon(menuEntry.icon());
            }
            return new SideNavItem(menuEntry.title(), menuEntry.path(), icon);
        } else {
            return new SideNavItem(menuEntry.title(), menuEntry.path());
        }
    }

    private Div createStatusIndicator() {
        Div indicator = new Div();
        indicator.setId("backend-status-indicator");
        indicator.getStyle()
            .set("width", "12px")
            .set("height", "12px")
            .set("border-radius", "50%")
            .set("background-color", "#4CAF50")
            .set("margin-right", "8px")
            .set("margin-top", "8px")
            .set("box-shadow", "0 0 8px rgba(76, 175, 80, 0.6)")
            .set("animation", "blink-status 1s infinite");

        indicator.getElement().executeJs(
            "let style = document.createElement('style');" +
            "style.textContent = '@keyframes blink-status { 0%, 49% { opacity: 1; } 50%, 100% { opacity: 0.3; } }';" +
            "document.head.appendChild(style);"
        );

        return indicator;
    }
    public Div getUltimoClickCard() {
        return ultimoClickCard;
    }
    public Div getClickAnteriorCard() {
        return clickAnteriorCard;
    }
}
