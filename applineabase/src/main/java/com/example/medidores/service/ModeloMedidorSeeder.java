package com.example.medidores.service;

import com.example.medidores.model.ModeloMedidor;
import com.example.medidores.model.OrdenPalabras;
import com.example.medidores.model.ParametroMedidor;
import com.example.medidores.model.RegistroModelo;
import com.example.medidores.model.TensionesHistorico;
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
// Antes que todo (es instantaneo): el recalculo del horometro (@Order(2)) tarda minutos y
// mientras tanto la pantalla de modelos y la lectura de pasarelas verian el catalogo incompleto.
@Order(0)
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
        repository.findByNombreIgnoreCase("PM5110")
                .filter(m -> m.getRegistros().stream().allMatch(r -> r.getParametro().isBasico()))
                .ifPresent(m -> {
                    sembrarCalidadPm5110(m);
                    repository.save(m);
                });
        // Parámetros de calidad del PM710: se agregan una sola vez, si el modelo todavía no tiene
        // ninguno (no pisa lo que el usuario haya cargado o marcado No disponible después).
        repository.findByNombreIgnoreCase("PM710")
                .filter(m -> m.getRegistros().stream().allMatch(r -> r.getParametro().isBasico()))
                .ifPresent(m -> {
                    sembrarCalidadPm710(m);
                    repository.save(m);
                });
        // ION8600 y PAC1020 se crearon vacíos en una versión anterior: se completan solo si siguen sin registros.
        ModeloMedidor ion = repository.findByNombreIgnoreCase("ION8600")
                .orElseGet(() -> new ModeloMedidor("ION8600", null));
        if (ion.getRegistros().isEmpty() || enUnidadesViejas(ion) || copiaConvencionPlc(ion)) {
            sembrarIon8600(ion);
            repository.save(ion);
        }
        ModeloMedidor pac = repository.findByNombreIgnoreCase("PAC1020")
                .orElseGet(() -> new ModeloMedidor("PAC1020", null));
        // También se corrige la semilla anterior (direcciones del PLC, KW = fase L1), si nadie la editó.
        if (pac.getRegistros().isEmpty() || esSemillaViejaPac1020(pac) || enUnidadesViejas(pac) || copiaConvencionPlc(pac)) {
            sembrarPac1020(pac);
            repository.save(pac);
        }
    }

    /**
     * PM5110: "PM51xx_PM53xx_PMC Register List v2011_v2021 R01" (hoja Register List, columna
     * PM5110_11 = Y), todos Float32. Los PF por fase vienen en 4 cuadrantes (4Q_FP_PF) como el total.
     * Energía de retorno = 2702 "Active Energy Received (Out of Load)". Desbalances: "Worst".
     */
    private static void sembrarCalidadPm5110(ModeloMedidor m) {
        agregar(m, ParametroMedidor.KWH_RETORNO, 2702);
        agregar(m, ParametroMedidor.KVARH, 2708);
        agregar(m, ParametroMedidor.KVAH, 2716);
        agregar(m, ParametroMedidor.IN, 3006);
        agregar(m, ParametroMedidor.DESBALANCE_I, 3018);
        agregar(m, ParametroMedidor.VAN, 3028);
        agregar(m, ParametroMedidor.VBN, 3030);
        agregar(m, ParametroMedidor.VCN, 3032);
        agregar(m, ParametroMedidor.DESBALANCE_V, 3044);
        agregar(m, ParametroMedidor.KW_A, 3054);
        agregar(m, ParametroMedidor.KW_B, 3056);
        agregar(m, ParametroMedidor.KW_C, 3058);
        agregar(m, ParametroMedidor.KVAR_TOTAL, 3068);
        agregar(m, ParametroMedidor.KVA_TOTAL, 3076);
        agregar(m, ParametroMedidor.PF_A, 3078).setPf4Cuadrantes(true);
        agregar(m, ParametroMedidor.PF_B, 3080).setPf4Cuadrantes(true);
        agregar(m, ParametroMedidor.PF_C, 3082).setPf4Cuadrantes(true);
        agregar(m, ParametroMedidor.FRECUENCIA, 3110);
        agregar(m, ParametroMedidor.THD_IA, 21300);
        agregar(m, ParametroMedidor.THD_IB, 21302);
        agregar(m, ParametroMedidor.THD_IC, 21304);
        agregar(m, ParametroMedidor.THD_VAB, 21322);
        agregar(m, ParametroMedidor.THD_VBC, 21324);
        agregar(m, ParametroMedidor.THD_VCA, 21326);
        agregar(m, ParametroMedidor.THD_VAN, 21330);
        agregar(m, ParametroMedidor.THD_VBN, 21332);
        agregar(m, ParametroMedidor.THD_VCN, 21334);
    }

    /**
     * PM710: "Power Meter 710, Appendix B - Register List" (63230-501-209A1, 07/2008, firmware
     * 2.020), registros Float32 de la tabla B-2. El PM710 no ofrece PF por fase, desbalances ni
     * energía de retorno (sus energías son absolutas): quedan No disponible. Su PF es absoluto
     * (0-1), sin codificación de 4 cuadrantes.
     */
    private static void sembrarCalidadPm710(ModeloMedidor m) {
        agregar(m, ParametroMedidor.KVAH, 1002);
        agregar(m, ParametroMedidor.KVARH, 1004);
        agregar(m, ParametroMedidor.KVA_TOTAL, 1008);
        agregar(m, ParametroMedidor.KVAR_TOTAL, 1010);
        agregar(m, ParametroMedidor.FRECUENCIA, 1020);
        agregar(m, ParametroMedidor.IN, 1040);
        agregar(m, ParametroMedidor.VAN, 1060);
        agregar(m, ParametroMedidor.VBN, 1062);
        agregar(m, ParametroMedidor.VCN, 1064);
        agregar(m, ParametroMedidor.KW_A, 1066);
        agregar(m, ParametroMedidor.KW_B, 1068);
        agregar(m, ParametroMedidor.KW_C, 1070);
        agregar(m, ParametroMedidor.THD_IA, 1084);
        agregar(m, ParametroMedidor.THD_IB, 1086);
        agregar(m, ParametroMedidor.THD_IC, 1088);
        agregar(m, ParametroMedidor.THD_VAN, 1092);
        agregar(m, ParametroMedidor.THD_VBN, 1094);
        agregar(m, ParametroMedidor.THD_VCN, 1096);
        agregar(m, ParametroMedidor.THD_VAB, 1098);
        agregar(m, ParametroMedidor.THD_VBC, 1100);
        agregar(m, ParametroMedidor.THD_VCA, 1102);
    }

    /**
     * ION8600 (KWhPlanta1): mapa por defecto del manual "Modbus Protocol and Register Map for ION
     * Devices" (70022-0124-00, 04/2009), cruzado con las direcciones que usa el PLC (ION_ADD) y
     * con los datos guardados. Catálogo en unidades estándar: kW/kVAR/kVA (el medidor entrega W x1000 ->
     * escala 0.001), PF -1..1 (entero x100 en % -> 0.0001); el histórico VIP se guarda en W y PF en % (marcas),
     * corrientes, frecuencia y desbalance x10 (-> 0.1). Las tensiones 40166-40170 que lee el PLC
     * son FASE-NEUTRO (Vln); las fase-fase estan en 40178-40182. Los THD del mapa por defecto son
     * maximos ("mx"), no instantaneos: quedan No disponible.
     */
    private static void sembrarIon8600(ModeloMedidor m) {
        m.setDescripcion("Schneider ION8600 (KWhPlanta1). Mapa por defecto del manual; kW y PF -1..1 como los demas medidores");
        // Como dice el manual y como los demás medidores: VIP con tensiones fase-fase, kW y PF -1..1
        // (el PLC guardaba Vln, W y PF en %; decidido con el usuario el 2026-10-08).
        m.setTensionesHistorico(TensionesHistorico.FASE_FASE);
        m.setHistoricoPotenciaEnW(false);
        m.setHistoricoPfEnPorcentaje(false);
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
        agregar(m, ParametroMedidor.KW_A, 198, TipoDato.INT32, 0.001);
        agregar(m, ParametroMedidor.KW_B, 200, TipoDato.INT32, 0.001);
        agregar(m, ParametroMedidor.KW_C, 202, TipoDato.INT32, 0.001);
        agregar(m, ParametroMedidor.KW_TOTAL, 204, TipoDato.INT32, 0.001);
        agregar(m, ParametroMedidor.KVAR_TOTAL, 214, TipoDato.INT32, 0.001);
        agregar(m, ParametroMedidor.KVA_TOTAL, 224, TipoDato.INT32, 0.001);
        agregar(m, ParametroMedidor.PF_A, 262, TipoDato.INT16, 0.0001);
        agregar(m, ParametroMedidor.PF_B, 263, TipoDato.INT16, 0.0001);
        agregar(m, ParametroMedidor.PF_C, 264, TipoDato.INT16, 0.0001);
        agregar(m, ParametroMedidor.PF_TOTAL, 265, TipoDato.INT16, 0.0001);
    }

    /**
     * PAC1020 (TDGeneradorSA): "SENTRON PAC1020 Manual", tabla A-3 "Available measured variables"
     * (FC 0x03/0x04, todos float). Siemens numera por **offset** (base 0, como viaja en el cable):
     * el modelo usa numeración del manual = false y los registros son esos offsets.
     *
     * Cruce con PAC_ADD del PLC (2026-10-07):
     * - "KW" del PLC (40020 = offset 19) es la potencia activa de la FASE L1, no la total (offset
     *   41). Confirmado con datos: 867.7 kWh en 17.9 h = 48.5 kW medios, contra 16.5 kW guardados.
     *   Aquí KW_TOTAL = 41 (correcto); el historico por PLC tiene solo L1 hasta que se corrija PAC_ADD.
     * - "KWh Retorno" del PLC (PAC_ADD 42806 = offset 2805) es la ENERGÍA REACTIVA
     *   (varh). Se mantiene en KWH_RETORNO con escala 1 para que la columna KWhR siga igual que su
     *   historico; KVARH (mismo offset, en kvarh) es el dato correcto para Calidad de Energía.
     * Catálogo en kW/kVAR/kVA (el medidor entrega W, escala 0.001); el histórico VIP en W (marca). Sin THD ni desbalances.
     */
    private static void sembrarPac1020(ModeloMedidor m) {
        m.setDescripcion("Siemens SENTRON PAC1020 (TDGeneradorSA). Offsets del manual (base 0); kW como los demas medidores. Sin energia de retorno");
        m.setNumeracionManual(false);
        m.setHistoricoPotenciaEnW(false); // VIP en kW como los demás (manual), no en W como el PLC
        m.setTensionesHistorico(TensionesHistorico.FASE_FASE);
        agregar(m, ParametroMedidor.KWH, 2803, TipoDato.FLOAT32, 0.001);
        agregar(m, ParametroMedidor.KVARH, 2805, TipoDato.FLOAT32, 0.001);
        m.quitarRegistro(ParametroMedidor.KWH_RETORNO); // el PAC1020 no mide energía de retorno (2805 es reactiva)
        agregar(m, ParametroMedidor.VAN, 1);
        agregar(m, ParametroMedidor.VBN, 3);
        agregar(m, ParametroMedidor.VCN, 5);
        agregar(m, ParametroMedidor.VAB, 7);
        agregar(m, ParametroMedidor.VBC, 9);
        agregar(m, ParametroMedidor.VCA, 11);
        agregar(m, ParametroMedidor.IA, 13);
        agregar(m, ParametroMedidor.IB, 15);
        agregar(m, ParametroMedidor.IC, 17);
        agregar(m, ParametroMedidor.KW_A, 19, TipoDato.FLOAT32, 0.001);
        agregar(m, ParametroMedidor.KW_B, 21, TipoDato.FLOAT32, 0.001);
        agregar(m, ParametroMedidor.KW_C, 23, TipoDato.FLOAT32, 0.001);
        agregar(m, ParametroMedidor.PF_A, 31);
        agregar(m, ParametroMedidor.PF_B, 33);
        agregar(m, ParametroMedidor.PF_C, 35);
        agregar(m, ParametroMedidor.IN, 37);
        agregar(m, ParametroMedidor.FRECUENCIA, 39);
        agregar(m, ParametroMedidor.KW_TOTAL, 41, TipoDato.FLOAT32, 0.001);
        agregar(m, ParametroMedidor.KVAR_TOTAL, 43, TipoDato.FLOAT32, 0.001);
        agregar(m, ParametroMedidor.PF_TOTAL, 45);
        agregar(m, ParametroMedidor.KVA_TOTAL, 4115, TipoDato.FLOAT32, 0.001);
    }

    /**
     * Semilla anterior de ION8600/PAC1020 con potencias en W en el propio catálogo (escala 1).
     * Desde E1 el catálogo va en kW y la conversión a W la hace la marca de histórico.
     */
    private static boolean enUnidadesViejas(ModeloMedidor m) {
        RegistroModelo kw = m.registroDe(ParametroMedidor.KW_TOTAL);
        return kw != null && kw.getEscala() == 1.0 && !m.isHistoricoPotenciaEnW();
    }

    /**
     * Semilla del 2026-10-07 que copiaba la convención del PLC (VIP en W, PF en %, tensiones
     * fase-neutro en el ION8600, KWhR = reactiva en el PAC1020). Desde el 2026-10-08 se carga como
     * dicen los manuales. Se reconoce por la combinación que solo ponía esa semilla (así una marca
     * puesta a mano después no se pisa en cada arranque).
     */
    private static boolean copiaConvencionPlc(ModeloMedidor m) {
        RegistroModelo retorno = m.registroDe(ParametroMedidor.KWH_RETORNO);
        boolean pacConReactivaComoRetorno = retorno != null && retorno.getRegistro() == 2805;
        return m.isHistoricoPotenciaEnW()
                && (m.getTensionesHistorico() == TensionesHistorico.FASE_NEUTRO || pacConReactivaComoRetorno);
    }

    /** Semilla anterior del PAC1020 (direcciones del PLC en base 1, con KW = 20 = fase L1). */
    private static boolean esSemillaViejaPac1020(ModeloMedidor m) {
        RegistroModelo kw = m.registroDe(ParametroMedidor.KW_TOTAL);
        return m.isNumeracionManual() && kw != null && kw.getRegistro() == 20;
    }

    private static RegistroModelo agregar(ModeloMedidor modelo, ParametroMedidor parametro, int registroManual) {
        return agregar(modelo, parametro, registroManual, TipoDato.FLOAT32, 1);
    }

    /**
     * Carga o corrige un parámetro. Si ya existe se actualiza la misma fila (no se borra y se
     * vuelve a insertar: Hibernate inserta antes de borrar y chocaría con la clave única
     * modelo+parámetro).
     */
    private static RegistroModelo agregar(ModeloMedidor modelo, ParametroMedidor parametro, int registroManual,
                                          TipoDato tipo, double escala) {
        RegistroModelo r = modelo.registroDe(parametro);
        if (r == null) {
            r = new RegistroModelo(parametro, registroManual, tipo);
            modelo.ponerRegistro(r);
        }
        r.setRegistro(registroManual);
        r.setTipoDato(tipo);
        r.setOrdenPalabras(OrdenPalabras.NORMAL);
        r.setEscala(escala);
        r.setPf4Cuadrantes(false);
        return r;
    }
}
