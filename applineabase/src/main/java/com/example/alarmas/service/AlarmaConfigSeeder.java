package com.example.alarmas.service;

import com.example.alarmas.model.AlarmaConfig;
import com.example.alarmas.model.TipoAlarma;
import com.example.alarmas.repository.AlarmaConfigRepository;
import com.example.dataacquisition.service.ConfigLoaderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Crea las filas de configuración de alarma por defecto la primera vez que arranca
 * la aplicación (o cuando aparece una línea nueva en linea-id-config.json). No
 * sobrescribe filas ya existentes, para no pisar ajustes hechos por el ADMIN.
 *
 * Corre con prioridad alta (@Order(1)) porque el backfill del horómetro depende de
 * que las reglas de alarma (umbralMinimoKw, ventanaCiclos) ya existan.
 */
@Component
@Order(1)
public class AlarmaConfigSeeder implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(AlarmaConfigSeeder.class);

    /**
     * Default de arranque en kW. El registro PW no viene en la misma escala en todas
     * las máquinas (medidores PM5110 vs ION8600 vs PAC1020), así que este valor es solo
     * un punto de partida: el ADMIN debe ajustarlo por línea desde AlarmasConfigView /
     * HorometroView observando el PW real de cada una antes de confiar en la alarma.
     */
    private static final double UMBRAL_MINIMO_KW_DEFAULT = 15.0;

    private static final Set<String> COMPRESORES_CICLICOS = Set.of("CompAP", "Sauer");
    private static final Set<String> SENSORES_SIN_KWH = Set.of(
            "TemperaturaAmbiente", "PsiAireP1", "TemperaturaAgua", "PsiAgua", "BarCompHP");
    private static final String SENSOR_TEMPERATURA_AGUA = "TemperaturaAgua";
    private static final Set<String> LINEAS_CON_FACTOR_POTENCIA = Set.of("KWhPlanta1", "Trafo1", "Trafo2");

    private final AlarmaConfigRepository configRepository;
    private final ConfigLoaderService configLoaderService;

    public AlarmaConfigSeeder(AlarmaConfigRepository configRepository, ConfigLoaderService configLoaderService) {
        this.configRepository = configRepository;
        this.configLoaderService = configLoaderService;
    }

    @Override
    public void run(String... args) {
        for (Map<String, Object> linea : configLoaderService.loadLineaIDConfig()) {
            String nombre = (String) linea.get("lineaMaquina");
            if (nombre == null || nombre.isBlank()) {
                continue;
            }

            if (COMPRESORES_CICLICOS.contains(nombre)) {
                seedSiNoExiste(nombre, TipoAlarma.CICLO_COMPRESOR, config -> {
                    config.setUmbralMinimoKw(UMBRAL_MINIMO_KW_DEFAULT);
                    config.setMinutosMaxEncendido(15);
                });
            } else if (!SENSORES_SIN_KWH.contains(nombre)) {
                seedSiNoExiste(nombre, TipoAlarma.DETENCION, config -> {
                    config.setUmbralMinimoKw(UMBRAL_MINIMO_KW_DEFAULT);
                    config.setVentanaCiclos(5);
                });
            }

            if (SENSOR_TEMPERATURA_AGUA.equals(nombre)) {
                seedSiNoExiste(nombre, TipoAlarma.TEMPERATURA_ALTA, config -> config.setTemperaturaMaxima(13.0));
            }

            if (LINEAS_CON_FACTOR_POTENCIA.contains(nombre)) {
                seedSiNoExiste(nombre, TipoAlarma.FACTOR_POTENCIA_BAJO, config -> config.setFactorPotenciaMinimo(0.94));
            }
        }
    }

    private void seedSiNoExiste(String lineaMaquina, TipoAlarma tipo, java.util.function.Consumer<AlarmaConfig> ajustar) {
        if (configRepository.existsByLineaMaquinaAndTipoAlarma(lineaMaquina, tipo)) {
            return;
        }
        AlarmaConfig config = new AlarmaConfig();
        config.setLineaMaquina(lineaMaquina);
        config.setTipoAlarma(tipo);
        config.setHabilitada(true);
        ajustar.accept(config);
        configRepository.save(config);
        logger.info("Config. de alarma creada por defecto: {} / {}", lineaMaquina, tipo);
    }
}
