package com.example.medidores.service;

import com.example.medidores.model.ModeloMedidor;
import com.example.medidores.model.ParametroMedidor;
import com.example.medidores.model.RegistroModelo;
import com.example.medidores.model.TipoDato;
import com.example.medidores.repository.ModeloMedidorRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Carga los modelos de medidor en uso la primera vez. Idempotente por modelo: si un modelo ya
 * existe no se toca (pudo corregirse desde la pantalla).
 *
 * Solo se siembran registros confirmados con datos reales: los básicos de PM5110 (GA752 por
 * GteWay01) y PM710 (OrientadoraL2 y HornoL3 por GteWay01), los mismos que usaba el lector
 * anterior de pasarelas. ION8600 y PAC1020 se crean vacíos (hoy se leen por PLC): sus
 * registros los carga el usuario desde la pantalla. Los parámetros de calidad no se siembran
 * hasta que el usuario los confirme.
 */
@Component
@Order(10)
public class ModeloMedidorSeeder implements CommandLineRunner {

    private final ModeloMedidorRepository repository;

    public ModeloMedidorSeeder(ModeloMedidorRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        if (!repository.existsByNombreIgnoreCase("PM5110")) {
            ModeloMedidor pm5110 = new ModeloMedidor("PM5110", "Schneider PowerLogic PM5110");
            agregar(pm5110, ParametroMedidor.KWH, 2700);
            agregar(pm5110, ParametroMedidor.IA, 3000);
            agregar(pm5110, ParametroMedidor.IB, 3002);
            agregar(pm5110, ParametroMedidor.IC, 3004);
            agregar(pm5110, ParametroMedidor.VAB, 3020);
            agregar(pm5110, ParametroMedidor.VBC, 3022);
            agregar(pm5110, ParametroMedidor.VCA, 3024);
            agregar(pm5110, ParametroMedidor.KW_TOTAL, 3060);
            agregar(pm5110, ParametroMedidor.PF_TOTAL, 3084).setPf4Cuadrantes(true);
            repository.save(pm5110);
        }
        if (!repository.existsByNombreIgnoreCase("PM710")) {
            ModeloMedidor pm710 = new ModeloMedidor("PM710", "Schneider PowerLogic PM710");
            agregar(pm710, ParametroMedidor.KWH, 1000);
            agregar(pm710, ParametroMedidor.KW_TOTAL, 1006);
            agregar(pm710, ParametroMedidor.PF_TOTAL, 1012);
            agregar(pm710, ParametroMedidor.IA, 1034);
            agregar(pm710, ParametroMedidor.IB, 1036);
            agregar(pm710, ParametroMedidor.IC, 1038);
            agregar(pm710, ParametroMedidor.VAB, 1054);
            agregar(pm710, ParametroMedidor.VBC, 1056);
            agregar(pm710, ParametroMedidor.VCA, 1058);
            repository.save(pm710);
        }
        if (!repository.existsByNombreIgnoreCase("ION8600")) {
            repository.save(new ModeloMedidor("ION8600", "Schneider ION8600 (KWhPlanta1, hoy por PLC)"));
        }
        if (!repository.existsByNombreIgnoreCase("PAC1020")) {
            repository.save(new ModeloMedidor("PAC1020", "Siemens SENTRON PAC1020 (TDGeneradorSA, hoy por PLC)"));
        }
    }

    private static RegistroModelo agregar(ModeloMedidor modelo, ParametroMedidor parametro, int registroManual) {
        RegistroModelo r = new RegistroModelo(parametro, registroManual, TipoDato.FLOAT32);
        modelo.ponerRegistro(r);
        return r;
    }
}
