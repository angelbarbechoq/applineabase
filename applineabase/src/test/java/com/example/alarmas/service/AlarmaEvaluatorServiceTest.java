package com.example.alarmas.service;

import com.example.alarmas.model.AlarmaConfig;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaConfigRepository;
import com.example.alarmas.repository.AlarmaEventoRepository;
import com.example.dataacquisition.event.MaquinaDataUpdateEvent;
import com.example.dataacquisition.event.MaquinaEstadoCambioEvent;
import com.example.dataacquisition.event.SensorDataUpdateEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ejercita AlarmaEvaluatorService publicando los mismos eventos que emite el
 * pipeline real de adquisición (PLCDataAcquisitionService / PASReaderService),
 * ya que no hay PLCs físicos disponibles en este entorno para generar una
 * secuencia real de lecturas Modbus.
 *
 * AlarmaConfigSeeder ya crea, al levantar el contexto, las filas por defecto
 * para las líneas reales de linea-id-config.json (Sauer, CompAP, TemperaturaAgua,
 * KWhPlanta1, Trafo1, Trafo2), así que estas pruebas reutilizan esas filas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class AlarmaEvaluatorServiceTest {

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    AlarmaConfigRepository configRepository;

    @Autowired
    AlarmaEventoRepository eventoRepository;

    @Autowired
    CapturadorEstadoCambio capturador;

    private static Map<String, Object> datosPW(double pw) {
        Map<String, Object> datos = new HashMap<>();
        datos.put("PW", pw);
        return datos;
    }

    @Test
    void detencion_confirma_tras_la_ventana_de_ciclos_sin_generar_alarma_y_se_resuelve_al_reanudar() {
        String linea = "Linea02"; // sembrada por AlarmaConfigSeeder como DETENCION (umbral=0.5 kW, ventana=5)
        capturador.eventos.clear();

        for (int i = 0; i < 4; i++) {
            eventPublisher.publishEvent(new MaquinaDataUpdateEvent(this, linea, datosPW(0.0)));
        }
        assertThat(capturador.eventos)
                .as("no debe confirmar el apagado antes de completar la ventana")
                .noneMatch(e -> e.getNombreMaquina().equals(linea) && !e.isEncendida());

        eventPublisher.publishEvent(new MaquinaDataUpdateEvent(this, linea, datosPW(0.0)));
        assertThat(capturador.eventos)
                .as("debe publicar el cambio de estado a apagada al 5to ciclo consecutivo bajo el umbral")
                .anyMatch(e -> e.getNombreMaquina().equals(linea) && !e.isEncendida());
        assertThat(eventoRepository.findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(linea, TipoAlarma.DETENCION))
                .as("DETENCION ya no debe generar AlarmaEvento: es una advertencia del horómetro, no una alarma")
                .isEmpty();

        eventPublisher.publishEvent(new MaquinaDataUpdateEvent(this, linea, datosPW(5.0)));
        assertThat(capturador.eventos)
                .as("debe publicar el cambio de estado a encendida en cuanto vuelve a consumir por encima del umbral")
                .anyMatch(e -> e.getNombreMaquina().equals(linea) && e.isEncendida());
        assertThat(eventoRepository.findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(linea, TipoAlarma.DETENCION))
                .as("sigue sin haber ningún AlarmaEvento de DETENCION")
                .isEmpty();
    }

    @Test
    void ciclo_compresor_se_dispara_al_exceder_minutos_max_encendido() {
        String linea = "Sauer"; // sembrada como CICLO_COMPRESOR
        AlarmaConfig config = configRepository.findByLineaMaquinaAndTipoAlarma(linea, TipoAlarma.CICLO_COMPRESOR).orElseThrow();
        config.setMinutosMaxEncendido(0); // dispara en cuanto transcurre >= 0 minutos, sin esperar en tiempo real
        configRepository.save(config);

        eventPublisher.publishEvent(new MaquinaDataUpdateEvent(this, linea, datosPW(5.0)));
        assertThat(eventoRepository.findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(linea, TipoAlarma.CICLO_COMPRESOR))
                .as("con minutosMax=0 debe dispararse en el primer ciclo encendido")
                .isPresent();

        eventPublisher.publishEvent(new MaquinaDataUpdateEvent(this, linea, datosPW(0.0)));
        assertThat(eventoRepository.findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(linea, TipoAlarma.CICLO_COMPRESOR))
                .as("debe resolverse cuando el compresor se apaga")
                .isEmpty();
    }

    @Test
    void factor_potencia_bajo_usa_valor_absoluto() {
        String linea = "KWhPlanta1"; // sembrada como FACTOR_POTENCIA_BAJO (mínimo 0.94)

        Map<String, Object> datosMalos = new HashMap<>();
        datosMalos.put("PF", -0.5); // negativo, como reporta KWhPlanta1
        eventPublisher.publishEvent(new MaquinaDataUpdateEvent(this, linea, datosMalos));
        assertThat(eventoRepository.findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(linea, TipoAlarma.FACTOR_POTENCIA_BAJO))
                .as("|-0.5| < 0.94 debe disparar la alarma")
                .isPresent();

        Map<String, Object> datosBuenos = new HashMap<>();
        datosBuenos.put("PF", -0.98);
        eventPublisher.publishEvent(new MaquinaDataUpdateEvent(this, linea, datosBuenos));
        assertThat(eventoRepository.findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(linea, TipoAlarma.FACTOR_POTENCIA_BAJO))
                .as("|-0.98| >= 0.94 debe resolver la alarma")
                .isEmpty();
    }

    @Test
    void temperatura_alta_se_dispara_sobre_el_maximo_configurado() {
        String sensor = "TemperaturaAgua"; // sembrada como TEMPERATURA_ALTA (máximo 13.0)

        eventPublisher.publishEvent(new SensorDataUpdateEvent(this, sensor, 15.0, "fecha"));
        assertThat(eventoRepository.findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(sensor, TipoAlarma.TEMPERATURA_ALTA))
                .as("15.0 > 13.0 debe disparar la alarma")
                .isPresent();

        eventPublisher.publishEvent(new SensorDataUpdateEvent(this, sensor, 9.0, "fecha"));
        assertThat(eventoRepository.findFirstByLineaMaquinaAndTipoAlarmaAndActivaTrue(sensor, TipoAlarma.TEMPERATURA_ALTA))
                .as("9.0 <= 13.0 debe resolver la alarma")
                .isEmpty();
    }

    @TestConfiguration
    static class HorometroEventosTestConfig {
        @Bean
        CapturadorEstadoCambio capturador() {
            return new CapturadorEstadoCambio();
        }
    }

    /** Captura los MaquinaEstadoCambioEvent publicados durante el test para poder verificarlos. */
    static class CapturadorEstadoCambio {
        final List<MaquinaEstadoCambioEvent> eventos = new ArrayList<>();

        @EventListener
        void onEstadoCambio(MaquinaEstadoCambioEvent event) {
            eventos.add(event);
        }
    }
}
