package com.example.security;

import com.example.dataacquisition.service.ConfigLoaderService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Filtra la lista de líneas/máquinas (linea-id-config.json) según la zona
 * asignada al usuario autenticado. Un ADMIN ve todas las líneas, y la zona
 * "Mantenimiento" también ve todas (mantenimiento da soporte a toda la planta).
 */
@Service
public class LineaAccessService {

    private static final String ZONA_MANTENIMIENTO = "Mantenimiento";

    private final ConfigLoaderService configLoaderService;

    public LineaAccessService(ConfigLoaderService configLoaderService) {
        this.configLoaderService = configLoaderService;
    }

    public List<Map<String, Object>> getLineasPermitidas() {
        List<Map<String, Object>> todas = configLoaderService.loadLineaIDConfig();

        String zona = zonaUsuarioActual();
        if (esAdmin() || ZONA_MANTENIMIENTO.equalsIgnoreCase(zona)) {
            return todas;
        }

        if (zona == null) {
            return List.of();
        }

        return todas.stream()
                .filter(l -> zona.equalsIgnoreCase(String.valueOf(l.get("zona"))))
                .collect(Collectors.toList());
    }

    public List<String> getMaquinasPermitidas() {
        return getLineasPermitidas().stream()
                .map(l -> (String) l.get("lineaMaquina"))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public boolean tieneAccesoAMaquina(String maquina) {
        if (esAdmin()) {
            return true;
        }
        return getMaquinasPermitidas().contains(maquina);
    }

    /** Las alarmas solo son visibles para ADMIN y usuarios de la zona Mantenimiento. */
    public boolean puedeVerAlarmas() {
        return esAdmin() || ZONA_MANTENIMIENTO.equalsIgnoreCase(zonaUsuarioActual());
    }

    public boolean esAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UsuarioPrincipal principal)) {
            return false;
        }
        return principal.getUsuario().getRol() == Usuario.Rol.ADMIN;
    }

    public String zonaUsuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UsuarioPrincipal principal)) {
            return null;
        }
        return principal.getUsuario().getZona();
    }
}
