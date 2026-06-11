package com.example.dataacquisition.event;

import org.springframework.context.ApplicationEvent;
import java.util.Map;

public class MaquinaDataUpdateEvent extends ApplicationEvent {

    private final String nombreMaquina;
    private final Map<String, Object> datos;

    public MaquinaDataUpdateEvent(Object source, String nombreMaquina, Map<String, Object> datos) {
        super(source);
        this.nombreMaquina = nombreMaquina;
        this.datos = datos;
    }

    public String getNombreMaquina() {
        return nombreMaquina;
    }

    public Map<String, Object> getDatos() {
        return datos;
    }
}
