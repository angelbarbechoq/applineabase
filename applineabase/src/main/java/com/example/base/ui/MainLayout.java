package com.example.base.ui;

import com.example.alarmas.model.AlarmaEvento;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.dataacquisition.service.DataAcquisitionTask;
import com.example.dataacquisition.service.EstadoBackendService;
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
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;

@Layout
@PermitAll
public final class MainLayout extends AppLayout {

    // Los datos se leen cada 60s (DataAcquisitionTask.CYCLE_INTERVAL), así que revisar
    // más seguido que eso no aporta nada nuevo; 30s da como máximo esa misma latencia
    // sin duplicar las peticiones del cliente en cada ciclo de lectura.
    private static final int ALARMA_POLL_INTERVAL_MS = 30000;
    // Con la etiqueta de origen de datos y la cuenta regresiva visibles se revisa más seguido, para que el estado del
    // backend y el "Leyendo..." se corrijan enseguida. La consulta de alarmas es liviana.
    private static final int BACKEND_POLL_INTERVAL_MS = 5000;

    private Div statusIndicator;
    private Div ultimoClickCard;
    // Lote en curso de la línea de Tiempo Real, después de Fecha, Hora y KWh (lo llena MarcadoLotesPanel; solo ADMIN).
    private final Div loteEnCursoCard = new Div();
    private final AuthenticationContext authenticationContext;
    private final LineaAccessService lineaAccessService;
    private final AlarmaEventoRepository alarmaEventoRepository;
    private LocalDateTime ultimaRevisionAlarmas = LocalDateTime.now();
    // De dónde salen los datos: la app lee los equipos, o (app.adquisicion.habilitada=false) los toma del backend
    // recolector; en ese caso, su estado. Solo para quien ve las alarmas.
    private final boolean verOrigenDatos;
    private final EstadoBackendService estadoBackend;
    private final Span backendLabel = new Span();
    // Cuenta regresiva hasta la próxima lectura (de la app o del backend); la baja el navegador de a un segundo y se
    // corrige con cada revisión del servidor.
    private final DataAcquisitionTask lecturaApp;
    private final Span proximaLectura = new Span();
    private boolean cercaDeLeer;

    MainLayout(AuthenticationContext authenticationContext, LineaAccessService lineaAccessService,
               AlarmaEventoRepository alarmaEventoRepository, ObjectProvider<EstadoBackendService> estadoBackend,
               ObjectProvider<DataAcquisitionTask> lecturaApp) {
        this.lecturaApp = lecturaApp.getIfAvailable();
        // Lo ve quien ve las alarmas (ADMIN y zona Mantenimiento), igual que la alarma de backend fuera de línea.
        this.verOrigenDatos = lineaAccessService.puedeVerAlarmas();
        this.estadoBackend = estadoBackend.getIfAvailable();
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

        backendLabel.getStyle().set("font-size", "12px").set("font-weight", "600").set("margin-right", "12px")
                .set("padding", "2px 8px").set("border-radius", "10px");
        backendLabel.setVisible(verOrigenDatos);
        actualizarEstadoBackend();
        proximaLectura.getStyle().set("font-size", "12px").set("color", "#666").set("margin-right", "12px")
                .set("min-width", "150px");
        proximaLectura.setVisible(false);
        HorizontalLayout statusLayout = new HorizontalLayout(backendLabel, proximaLectura, statusIndicator, userSpan, logoutBtn);
        statusLayout.setAlignItems(Alignment.CENTER);
        statusLayout.setSpacing(false);
        statusLayout.setPadding(false);
        statusLayout.setMargin(false);
        statusLayout.getStyle()
            .set("margin-left", "auto")
            .set("margin-right", "20px");
        addToNavbar(ultimoClickCard);
        loteEnCursoCard.setVisible(false);
        loteEnCursoCard.getStyle().set("margin-left", "16px");
        addToNavbar(loteEnCursoCard);
        addToNavbar(statusLayout);

        // Avisos de alarmas nuevas: las de planta para quien ve Alarmas, las de los generadores para quien
        // tiene "Ver Generadores" (ver revisarAlarmasNuevas).
        if (lineaAccessService.puedeVerAlarmas() || lineaAccessService.puedeVerGeneradores()) {
            addAttachListener(e -> {
                UI ui = e.getUI();
                ui.setPollInterval(verOrigenDatos ? BACKEND_POLL_INTERVAL_MS : ALARMA_POLL_INTERVAL_MS);
                ui.addPollListener(pollEvent -> {
                    revisarAlarmasNuevas();
                    actualizarEstadoBackend();
                    // Mientras se lee (o falta 1 s), refrescar cada segundo para que el fin de la lectura se vea enseguida.
                    if (verOrigenDatos) ui.setPollInterval(cercaDeLeer ? 1000 : BACKEND_POLL_INTERVAL_MS);
                });
            });
        }
    }

    /**
     * Origen de los datos: gris si la app lee los equipos; si los toma del backend, verde en línea, amarillo sin leer y
     * rojo fuera de línea, con el detalle al pasar el mouse.
     */
    private void actualizarEstadoBackend() {
        if (!verOrigenDatos) return;
        if (estadoBackend == null) {
            backendLabel.setText("La app lee los equipos");
            backendLabel.getStyle().set("background-color", "#e2e3e5").set("color", "#383d41");
            backendLabel.setTitle("Los datos los lee y guarda esta aplicación");
            if (lecturaApp != null) cuentaRegresiva(lecturaApp.segundosParaProximaLectura(), lecturaApp.leyendo());
            return;
        }
        EstadoBackendService.Estado e = estadoBackend.estado();
        String[] c = switch (e.nivel()) {
            case EN_LINEA -> new String[]{"Datos del backend: en línea", "#d4edda", "#155724"};
            case SIN_LEER -> new String[]{"Datos del backend: sin leer", "#fff3cd", "#856404"};
            case FUERA_DE_LINEA -> new String[]{"Datos del backend: fuera de línea", "#f8d7da", "#721c24"};
        };
        backendLabel.setText(c[0]);
        backendLabel.getStyle().set("background-color", c[1]).set("color", c[2]);
        backendLabel.setTitle(e.detalle());
        Integer segundos = e.nivel() == EstadoBackendService.Nivel.FUERA_DE_LINEA ? null : e.segundosAhora();
        cuentaRegresiva(segundos, e.leyendo());
    }

    /**
     * "Próxima lectura en N s": el navegador la baja de a un segundo; en 0, o si el servidor dice que está leyendo,
     * "Leyendo...". Si se queda ahí, la lectura no llega. null = no se sabe (se oculta).
     */
    private void cuentaRegresiva(Integer segundos, boolean leyendo) {
        cercaDeLeer = segundos != null && (leyendo || segundos <= 1);
        proximaLectura.setVisible(segundos != null);
        if (segundos == null) return;
        proximaLectura.getElement().executeJs(
                "const el = this;"
                        + "el.__fin = Date.now() + $0 * 1000; el.__leyendo = $1;"
                        + "const pintar = () => {"
                        + "  const s = Math.ceil((el.__fin - Date.now()) / 1000);"
                        + "  el.textContent = (el.__leyendo || s <= 0) ? 'Leyendo...' : 'Próxima lectura en ' + s + ' s';"
                        + "};"
                        + "pintar();"
                        + "if (!el.__timer) el.__timer = setInterval(pintar, 250);",
                segundos, leyendo);
    }

    private void revisarAlarmasNuevas() {
        LocalDateTime desde = ultimaRevisionAlarmas;
        ultimaRevisionAlarmas = LocalDateTime.now();

        boolean alarmasPlanta = lineaAccessService.puedeVerAlarmas();
        boolean alarmasGenerador = lineaAccessService.puedeVerGeneradores();
        List<AlarmaEvento> nuevas = alarmaEventoRepository.findByFechaInicioAfterOrderByFechaInicioDesc(desde);
        for (AlarmaEvento alarma : nuevas) {
            boolean deGenerador = alarma.getTipoAlarma() != null && alarma.getTipoAlarma().deGenerador();
            if (deGenerador ? !alarmasGenerador : !alarmasPlanta) {
                continue;
            }
            if (alarma.isUrgente()) {
                Notification.show("🔴 URGENTE: " + alarma.getMensaje(), 20000, Notification.Position.TOP_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                sonarAlarma();
            } else {
                Notification.show("🚨 " + alarma.getMensaje(), 8000, Notification.Position.TOP_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        }
    }

    /** Doble beep via Web Audio API, sin depender de ningún archivo de sonido empaquetado
     * aparte. Solo para alarmas urgentes: las demás siguen siendo silenciosas, como antes. */
    private void sonarAlarma() {
        getElement().executeJs(
                "try {" +
                        "  const ctx = new (window.AudioContext || window.webkitAudioContext)();" +
                        "  const beep = (start) => {" +
                        "    const o = ctx.createOscillator();" +
                        "    const g = ctx.createGain();" +
                        "    o.connect(g); g.connect(ctx.destination);" +
                        "    o.frequency.value = 880;" +
                        "    g.gain.setValueAtTime(0.3, ctx.currentTime + start);" +
                        "    o.start(ctx.currentTime + start);" +
                        "    o.stop(ctx.currentTime + start + 0.35);" +
                        "  };" +
                        "  beep(0); beep(0.45);" +
                        "} catch (e) { /* audio no disponible en este navegador, ignorar */ }"
        );
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

        SideNavItem graficas = new SideNavItem("Gráficas");
        graficas.setPrefixComponent(VaadinIcon.CHART.create());
        graficas.setExpanded(false);
        graficas.addItem(new SideNavItem("Tiempo Real", "grafica", VaadinIcon.CHART_LINE.create()));
        graficas.addItem(new SideNavItem("Histórico", "historico", VaadinIcon.CLOCK.create()));
        nav.addItem(graficas);
        // SGEn: línea base e índice de consumo (por ahora solo ADMIN, pedido del usuario 2026-11-13).
        if (lineaAccessService.esAdmin()) {
            nav.addItem(new SideNavItem("SGEn", "sgen", VaadinIcon.TRENDING_UP.create()));
        }
        colapsarAlSalirDelMouse(graficas);

        nav.addItem(new SideNavItem("Consulta de Datos", "query", VaadinIcon.LIST.create()));
        nav.addItem(new SideNavItem("Horómetro", "horometro", VaadinIcon.TIMER.create()));
        if (lineaAccessService.puedeVerAlarmas()) {
            SideNavItem alarmas = new SideNavItem("Alarmas");
            alarmas.setPrefixComponent(VaadinIcon.BELL.create());
            alarmas.setExpanded(false);
            alarmas.addItem(new SideNavItem("Alarmas Activas", "alarmas", VaadinIcon.BELL_O.create()));
            // Historial (con descarga CSV) queda solo para ADMIN por el momento, a diferencia de
            // Alarmas Activas que sigue viendo también la zona Mantenimiento.
            if (lineaAccessService.esAdmin()) {
                alarmas.addItem(new SideNavItem("Historial Alarmas", "alarmas/historial", VaadinIcon.CLOCK.create()));
            }
            nav.addItem(alarmas);
            colapsarAlSalirDelMouse(alarmas);
            // Calidad de Energia: mismo alcance que alarmas (ADMIN y zona Mantenimiento).
            nav.addItem(new SideNavItem("Calidad de Energía", "calidad", VaadinIcon.FLASH.create()));
        }
        // Consumo por franja horaria (tarifa de la empresa electrica): permiso propio "Ver Franja Horaria" (ADMIN siempre).
        if (lineaAccessService.puedeVerFranjaHoraria()) {
            nav.addItem(new SideNavItem("Consumo por franja horaria", "energia/franja-horaria", VaadinIcon.DOLLAR.create()));
        }
        // Transformadores (unifilar de la planta 1 en vivo): permiso propio "Ver Transformadores" (ADMIN siempre).
        if (lineaAccessService.puedeVerTransformadores()) {
            nav.addItem(new SideNavItem("Transformadores", "transformadores", VaadinIcon.SITEMAP.create()));
        }
        // Grupo Electrogeno: permiso propio "Ver Generadores" (ADMIN siempre).
        if (lineaAccessService.puedeVerGeneradores()) {
            SideNavItem grupoElectrogeno = new SideNavItem("Grupo Electrógeno");
            grupoElectrogeno.setPrefixComponent(VaadinIcon.COGS.create());
            grupoElectrogeno.setExpanded(false);
            grupoElectrogeno.addItem(new SideNavItem("Generadores", "generador", VaadinIcon.COG.create()));
            grupoElectrogeno.addItem(new SideNavItem("Análisis", "generador/analisis", VaadinIcon.BAR_CHART.create()));
            grupoElectrogeno.addItem(new SideNavItem("Control MF-13", "generador/mf13", VaadinIcon.CLIPBOARD_CHECK.create()));
            nav.addItem(grupoElectrogeno);
            colapsarAlSalirDelMouse(grupoElectrogeno);
        }
        if (lineaAccessService.puedeVerMantenimiento()) {
            SideNavItem mantenimientoPreventivo = new SideNavItem("Mantenimiento Preventivo");
            mantenimientoPreventivo.setPrefixComponent(VaadinIcon.TOOLS.create());
            mantenimientoPreventivo.setExpanded(false);
            mantenimientoPreventivo.addItem(new SideNavItem("Mantenimiento Barril y Tornillos", "mantenimiento", VaadinIcon.TOOLS.create()));
            // Personal de Mantenimiento es gestion de datos (alta/edicion/borrado de tecnicos),
            // no solo consulta -- se mantiene exclusivo ADMIN aunque el resto del modulo ya no lo sea.
            if (lineaAccessService.esAdmin()) {
                mantenimientoPreventivo.addItem(new SideNavItem("Personal de Mantenimiento", "mantenimiento/personal", VaadinIcon.USERS.create()));
            }
            nav.addItem(mantenimientoPreventivo);
            colapsarAlSalirDelMouse(mantenimientoPreventivo);

            SideNavItem reportes = new SideNavItem("Reportes");
            reportes.setPrefixComponent(VaadinIcon.CHART.create());
            reportes.setExpanded(false);
            reportes.addItem(new SideNavItem("Barril y Tornillo", "reportes/mantenimiento", VaadinIcon.TOOLS.create()));
            nav.addItem(reportes);
            colapsarAlSalirDelMouse(reportes);
        }
        if (lineaAccessService.esAdmin()) {
            nav.addItem(new SideNavItem("Usuarios", "usuarios", VaadinIcon.USERS.create()));
            nav.addItem(new SideNavItem("Reparar VIP Mensual", "admin/reparar-vip", VaadinIcon.WRENCH.create()));

            SideNavItem configuracion = new SideNavItem("Configuración");
            configuracion.setPrefixComponent(VaadinIcon.COG.create());
            configuracion.setExpanded(false);
            configuracion.addItem(new SideNavItem("Configuración de alarmas", "alarmas/config", VaadinIcon.BELL.create()));
            configuracion.addItem(new SideNavItem("Configuración de hardware", "configuracion", VaadinIcon.SERVER.create()));
            configuracion.addItem(new SideNavItem("Modelos de medidor", "configuracion/medidores", VaadinIcon.DASHBOARD.create()));
            configuracion.addItem(new SideNavItem("Configuración Mezcladores", "mezcladores/config", VaadinIcon.FIRE.create()));
            configuracion.addItem(new SideNavItem("Configuración Mantenimiento", "mantenimiento/config", VaadinIcon.TOOLS.create()));
            configuracion.addItem(new SideNavItem("Pasar configuración", "configuracion/traspaso", VaadinIcon.EXCHANGE.create()));
            nav.addItem(configuracion);
            colapsarAlSalirDelMouse(configuracion);
        }
        //nav.addSelectionListener(e -> setDrawerOpened(false));
        return nav;
    }

    /**
     * Ademas del click para expandir/contraer que SideNavItem ya trae de fabrica, a pedido el
     * submenu tambien se contrae solo apenas el mouse sale de su zona (el item y sus hijos
     * expandidos, que son parte del mismo elemento via slot="children"). Se aplica igual en los
     * tres menus con submenu (Gráficas, Alarmas, Configuración) para que ninguno se comporte
     * distinto de los demas.
     */
    private void colapsarAlSalirDelMouse(SideNavItem item) {
        item.getElement().executeJs(
                "this.addEventListener('mouseleave', function() { this.expanded = false; });");
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
    public Div getLoteEnCursoCard() {
        return loteEnCursoCard;
    }

    public Div getUltimoClickCard() {
        return ultimoClickCard;
    }
}
