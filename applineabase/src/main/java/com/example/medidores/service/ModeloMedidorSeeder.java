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
        // ION8600 y PAC1020 se crearon vacíos en una versión anterior: se completan solo si siguen sin registros.
        ModeloMedidor ion = repository.findByNombreIgnoreCase("ION8600")
                .orElseGet(() -> new ModeloMedidor("ION8600", null));
        if (ion.getRegistros().isEmpty()) {
            sembrarIon8600(ion);
            repository.save(ion);
        }
        ModeloMedidor pac = repository.findByNombreIgnoreCase("PAC1020")
                .orElseGet(() -> new ModeloMedidor("PAC1020", null));
        if (pac.getRegistros().isEmpty()) {
            sembrarPac1020(pac);
            repository.save(pac);
        }
    }

    /**
     * ION8600 (KWhPlanta1): mapa por defecto del manual "Modbus Protocol and Register Map for ION
     * Devices" (70022-0124-00, 04/2009), cruzado con las direcciones que usa el PLC (ION_ADD) y
     * con los datos guardados. Escalas para guardar en las mismas unidades que el historico por
     * PLC: kW/kVAR/kVA en W/VAR/VA (escala 1; el medidor entrega x1000), PF en % (x100 -> 0.01),
     * corrientes, frecuencia y desbalance x10 (-> 0.1). Las tensiones 40166-40170 que lee el PLC
     * son FASE-NEUTRO (Vln); las fase-fase estan en 40178-40182. Los THD del mapa por defecto son
     * maximos ("mx"), no instantaneos: quedan No disponible.
     */
    private static void sembrarIon8600(ModeloMedidor m) {
        m.setDescripcion("Schneider ION8600 (KWhPlanta1). Mapa por defecto del manual; unidades como el historico (W, PF en %)");
        agregar(m, ParametroMedidor.KWH, 230, TipoDato.INT32, 1);
        agregar(m, ParametroMedidor.KWH_RETORNO, 232, TipoDato.INT32, 1);
        agregar(m, ParametroMedidor.KVARH, 234, TipoDato.INT32, 1);
        agregar(m, ParametroMedidor.KVAH, 238, TipoDato.INT32, 1);
        agregar(m, ParametroMedidor.VAN, 166, TipoDato.UINT32, 1);
        agregar(m, ParametroMedidor.VBN, 168, TipoDato.UINT32, 1);
        agregar(m, ParametroMedidor.VCN, 170, TipoDato.UINT32, 1);
        agregar(m, ParametroMedidor.VAB, 178, TipoDato.UINT32, 1);
        agregar(m, ParametroMedidor.VBC, 180, TipoDato.UINT32, 1);
        agregar(m, ParametroMedidor.VCA, 182, TipoDato.UINT32, 1);
        agregar(m, ParametroMedidor.IA, 150, TipoDato.UINT16, 0.1);
        agregar(m, ParametroMedidor.IB, 151, TipoDato.UINT16, 0.1);
        agregar(m, ParametroMedidor.IC, 152, TipoDato.UINT16, 0.1);
        agregar(m, ParametroMedidor.IN, 153, TipoDato.UINT16, 0.1);
        agregar(m, ParametroMedidor.FRECUENCIA, 159, TipoDato.UINT16, 0.1);
        agregar(m, ParametroMedidor.DESBALANCE_V, 163, TipoDato.UINT16, 0.1);
        agregar(m, ParametroMedidor.DESBALANCE_I, 164, TipoDato.UINT16, 0.1);
        agregar(m, ParametroMedidor.KW_A, 198, TipoDato.INT32, 1);
        agregar(m, ParametroMedidor.KW_B, 200, TipoDato.INT32, 1);
        agregar(m, ParametroMedidor.KW_C, 202, TipoDato.INT32, 1);
        agregar(m, ParametroMedidor.KW_TOTAL, 204, TipoDato.INT32, 1);
        agregar(m, ParametroMedidor.KVAR_TOTAL, 214, TipoDato.INT32, 1);
        agregar(m, ParametroMedidor.KVA_TOTAL, 224, TipoDato.INT32, 1);
        agregar(m, ParametroMedidor.PF_A, 262, TipoDato.INT16, 0.01);
        agregar(m, ParametroMedidor.PF_B, 263, TipoDato.INT16, 0.01);
        agregar(m, ParametroMedidor.PF_C, 264, TipoDato.INT16, 0.01);
        agregar(m, ParametroMedidor.PF_TOTAL, 265, TipoDato.INT16, 0.01);
    }

    /**
     * PAC1020 (TDGeneradorSA): direcciones del bloque PAC_ADD del PLC (captura del usuario,
     * 2026-10-07), Float32. Escalas como el historico por PLC: kWh llega en Wh (-> 0.001), kW en
     * W y kWh de retorno en Wh (escala 1, sin conversion en el PLC).
     */
    private static void sembrarPac1020(ModeloMedidor m) {
        m.setDescripcion("Siemens SENTRON PAC1020 (TDGeneradorSA). Registros del PLC; unidades como el historico (kW en W)");
        agregar(m, ParametroMedidor.KWH, 2804, TipoDato.FLOAT32, 0.001);
        agregar(m, ParametroMedidor.KWH_RETORNO, 2806, TipoDato.FLOAT32, 1);
        agregar(m, ParametroMedidor.VAB, 8, TipoDato.FLOAT32, 1);
        agregar(m, ParametroMedidor.VBC, 10, TipoDato.FLOAT32, 1);
        agregar(m, ParametroMedidor.VCA, 12, TipoDato.FLOAT32, 1);
        agregar(m, ParametroMedidor.IA, 14, TipoDato.FLOAT32, 1);
        agregar(m, ParametroMedidor.IB, 16, TipoDato.FLOAT32, 1);
        agregar(m, ParametroMedidor.IC, 18, TipoDato.FLOAT32, 1);
        agregar(m, ParametroMedidor.KW_TOTAL, 20, TipoDato.FLOAT32, 1);
        agregar(m, ParametroMedidor.PF_TOTAL, 46, TipoDato.FLOAT32, 1);
    }

    private static RegistroModelo agregar(ModeloMedidor modelo, ParametroMedidor parametro, int registroManual) {
        return agregar(modelo, parametro, registroManual, TipoDato.FLOAT32, 1);
    }

    private static RegistroModelo agregar(ModeloMedidor modelo, ParametroMedidor parametro, int registroManual,
                                          TipoDato tipo, double escala) {
        RegistroModelo r = new RegistroModelo(parametro, registroManual, tipo);
        r.setEscala(escala);
        modelo.ponerRegistro(r);
        return r;
    }
}
