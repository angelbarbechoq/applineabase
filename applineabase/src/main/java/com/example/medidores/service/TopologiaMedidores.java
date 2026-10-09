package com.example.medidores.service;

import com.example.dataacquisition.service.ConfigLoaderService;
import com.example.medidores.TipoMedidor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * De qué transformador cuelga cada medidor y si es submedidor de otro (campos "tipo",
 * "transformador" y "dentroDe" de linea-id-config.json, editables en Configuración de hardware).
 * Ej.: GA752, HornoL3 y OrientadoraL2 están dentro de Inyeccion; ServicioAux y ServAuxP3LabCalid
 * dentro de TDGeneradorSA: al sumar un transformador se cuentan solo los de arriba, para no
 * contar dos veces.
 */
@Service
public class TopologiaMedidores {

    public record Medidor(String nombre, String zona, TipoMedidor tipo, String transformador, String dentroDe) {

        /** Máquina que se suma en el balance de su transformador (no es submedidor de otra). */
        public boolean sumaEnBalance() {
            return tipo == TipoMedidor.MAQUINA && dentroDe == null;
        }
    }

    private final ConfigLoaderService configLoaderService;

    public TopologiaMedidores(ConfigLoaderService configLoaderService) {
        this.configLoaderService = configLoaderService;
    }

    /** En el orden de linea-id-config.json. */
    public List<Medidor> medidores() {
        List<Medidor> lista = new ArrayList<>();
        for (Map<String, Object> l : configLoaderService.loadLineaIDConfig()) {
            String nombre = String.valueOf(l.get("lineaMaquina"));
            if (nombre.isBlank() || "null".equals(nombre)) continue;
            String zona = l.get("grupo") != null ? String.valueOf(l.get("grupo"))
                    : l.get("zona") != null ? String.valueOf(l.get("zona")) : "";
            lista.add(new Medidor(nombre, zona, TipoMedidor.de(l.get("tipo")), texto(l.get("transformador")), texto(l.get("dentroDe"))));
        }
        return lista;
    }

    /** Nombres de los medidores de tipo transformador (para listas cerradas). */
    public List<String> transformadores() {
        return medidores().stream().filter(m -> m.tipo() == TipoMedidor.TRANSFORMADOR).map(Medidor::nombre).toList();
    }

    private static String texto(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }
}
