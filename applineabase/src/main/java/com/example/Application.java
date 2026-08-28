package com.example;

import com.vaadin.flow.theme.aura.Aura;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.dependency.Uses;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.NumberField;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.annotation.Bean;

/**
 * @Uses fuerza a Grid/ComboBox/NumberField a empaquetarse en el bundle principal (carga
 * eager) en vez de en un modulo separado que se pide por demanda. Esto evita una carrera de
 * tiempos real observada en produccion: la primera vez que una ruta nueva necesita varios
 * modulos lazy en simultaneo, alguno puede perder la carrera y el componente nunca se registra
 * como custom element (confirmado con customElements.get(...) devolviendo false pese a que el
 * servidor si mando el elemento). Rutas viejas no lo sufren porque el navegador ya tiene esos
 * modulos cacheados de sesiones anteriores; una ruta nueva con un bundle recien generado no.
 */
@SpringBootApplication
@EnableScheduling
@StyleSheet(Aura.STYLESHEET)
@StyleSheet("styles.css") // Your custom styles
@Uses(Grid.class)
@Uses(ComboBox.class)
@Uses(NumberField.class)
@Uses(DateTimePicker.class)
@Uses(IntegerField.class)
public class Application implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("core-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);
        return scheduler;
    }

}
