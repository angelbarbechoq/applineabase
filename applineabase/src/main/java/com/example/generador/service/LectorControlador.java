package com.example.generador.service;

import com.example.dataacquisition.service.ModbusTcpConexion;
import com.example.generador.model.LecturaGenerador;
import com.example.generador.model.ModeloControlador;
import com.example.generador.model.ModeloControlador.Registro;
import com.example.generador.model.ParametroGenerador;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Lectura de un controlador de generador según el mapa de su modelo. **SOLO LECTURA**: únicamente
 * función 03; nunca se le escribe al controlador (pedido explícito del usuario).
 *
 * Agrupa los registros del mapa en pedidos (contiguos, o con huecos de hasta
 * {@link ModeloControlador#maxHueco()}). Si un pedido agrupado recibe una excepción Modbus, se
 * repite parámetro por parámetro para que un registro mal mapeado no deje sin dato a los demás.
 * Los de 32 bits se piden siempre enteros (ComAp contesta 0x02 si se pide la mitad).
 */
public final class LectorControlador {

    static final int TIMEOUT_MS = 3000;
    /** Máximo de registros por pedido (Modbus admite 125). */
    private static final int MAX_POR_PEDIDO = 100;
    private static final int TAMANO_EXPLORACION = 25;

    private LectorControlador() {
    }

    public static LecturaGenerador leer(String ip, int unitId, ModeloControlador modelo) throws IOException {
        try (ModbusTcpConexion c = new ModbusTcpConexion(ip, TIMEOUT_MS)) {
            return leer(c, unitId, modelo);
        }
    }

    static LecturaGenerador leer(ModbusTcpConexion c, int unitId, ModeloControlador modelo) throws IOException {
        Map<ParametroGenerador, Double> valores = new EnumMap<>(ParametroGenerador.class);
        Map<ParametroGenerador, String> errores = new EnumMap<>(ParametroGenerador.class);
        int respondidos = 0;
        for (List<Registro> bloque : bloques(modelo)) {
            int inicio = bloque.get(0).direccion();
            int fin = bloque.stream().mapToInt(r -> r.direccion() + r.cantidad()).max().orElse(inicio + 1);
            try {
                int[] regs = c.leerHolding(unitId, inicio, fin - inicio);
                for (Registro r : bloque) {
                    poner(valores, r.parametro(), decodificar(regs, r.direccion() - inicio, r, modelo.noDisponible8000()));
                    respondidos++;
                }
            } catch (ModbusTcpConexion.ExcepcionModbus e) {
                if (bloque.size() == 1) {
                    errores.put(bloque.get(0).parametro(), e.getMessage());
                    continue;
                }
                for (Registro r : bloque) {
                    try {
                        int[] regs = c.leerHolding(unitId, r.direccion(), r.cantidad());
                        poner(valores, r.parametro(), decodificar(regs, 0, r, modelo.noDisponible8000()));
                        respondidos++;
                    } catch (ModbusTcpConexion.ExcepcionModbus e2) {
                        errores.put(r.parametro(), e2.getMessage());
                    }
                }
            }
        }
        if (respondidos == 0 && !modelo.registros().isEmpty()) {
            String motivo = errores.values().stream().findFirst().orElse("sin registros");
            throw new IOException("ningun registro del mapa " + modelo.modelo() + " respondio (" + motivo + ")");
        }
        calcularPfSiFalta(modelo, valores);
        return new LecturaGenerador(LocalDateTime.now().withNano(0), valores, errores);
    }

    /**
     * Si el modelo no tiene un registro de PF confirmado (InteliGen 500: en paralelo regula a PF 1.000 y los
     * candidatos no se pueden distinguir), el PF se calcula con kW / kVA, dos registros confirmados.
     */
    static void calcularPfSiFalta(ModeloControlador modelo, Map<ParametroGenerador, Double> valores) {
        if (modelo.registro(ParametroGenerador.PF).isPresent()) return;
        Double kw = valores.get(ParametroGenerador.KW), kva = valores.get(ParametroGenerador.KVA);
        if (kw != null && kva != null && kva > 0) {
            valores.put(ParametroGenerador.PF, Math.round(Math.min(1.0, Math.abs(kw) / kva) * 1000) / 1000.0);
        }
    }

    private static void poner(Map<ParametroGenerador, Double> valores, ParametroGenerador p, Double v) {
        if (v != null) valores.put(p, v);
    }

    static List<List<Registro>> bloques(ModeloControlador modelo) {
        List<Registro> ordenados = new ArrayList<>(modelo.registros());
        ordenados.sort(Comparator.comparingInt(Registro::direccion));
        List<List<Registro>> bloques = new ArrayList<>();
        List<Registro> actual = null;
        int inicio = 0, fin = 0;
        for (Registro r : ordenados) {
            int finR = r.direccion() + r.cantidad();
            if (actual == null || r.direccion() > fin + modelo.maxHueco() || Math.max(fin, finR) - inicio > MAX_POR_PEDIDO) {
                actual = new ArrayList<>();
                bloques.add(actual);
                inicio = r.direccion();
                fin = finR;
            } else {
                fin = Math.max(fin, finR);
            }
            actual.add(r);
        }
        return bloques;
    }

    /** Divide (no multiplica por 0.1) para que 272 salga 27.2 y no 27.200000000000003. */
    static Double decodificar(int[] regs, int off, Registro r, boolean noDisponible8000) {
        int a = regs[off] & 0xFFFF;
        switch (r.tipo()) {
            case UINT16 -> {
                return noDisponible8000 && a == 0x8000 ? null : a / r.divisor();
            }
            case INT16 -> {
                return noDisponible8000 && a == 0x8000 ? null : (short) a / r.divisor();
            }
            default -> {
                int hi = r.palabraBajaPrimero() ? regs[off + 1] : regs[off];
                int lo = r.palabraBajaPrimero() ? regs[off] : regs[off + 1];
                long u = ((long) (hi & 0xFFFF) << 16) | (lo & 0xFFFF);
                if (noDisponible8000 && u == 0x80000000L) return null;
                return switch (r.tipo()) {
                    case UINT32 -> u / r.divisor();
                    case INT32 -> (int) u / r.divisor();
                    default -> {
                        float f = Float.intBitsToFloat((int) u);
                        yield Float.isNaN(f) || Float.isInfinite(f) ? null : f / r.divisor();
                    }
                };
            }
        }
    }

    /**
     * Un registro de la exploración. valor null = el controlador no lo entrega (nota = motivo).
     * ancho = 1 si se leyó solo; 2 o 4 si solo se pudo leer junto con los siguientes (valor de 32
     * o 64 bits); posicion = 0 para el primero de ese grupo.
     */
    public record Celda(int registro, Integer valor, int ancho, int posicion, String nota) {
    }

    /** Lee un rango de registros (función 03, solo lectura) para buscar o verificar un mapa. */
    public static List<Celda> explorar(String ip, int unitId, int desde, int cantidad) throws IOException {
        int base = desde - 40001;
        Celda[] celdas = new Celda[cantidad];
        try (ModbusTcpConexion c = new ModbusTcpConexion(ip, TIMEOUT_MS)) {
            for (int i = 0; i < cantidad; i += TAMANO_EXPLORACION) {
                int n = Math.min(TAMANO_EXPLORACION, cantidad - i);
                try {
                    int[] v = c.leerHolding(unitId, base + i, n);
                    for (int k = 0; k < n; k++) celdas[i + k] = new Celda(desde + i + k, v[k], 1, 0, null);
                } catch (ModbusTcpConexion.ExcepcionModbus e) {
                    for (int k = 0; k < n; k++) {
                        if (celdas[i + k] == null) uno(c, unitId, desde, base, i + k, celdas);
                    }
                }
            }
        }
        List<Celda> lista = new ArrayList<>();
        for (Celda celda : celdas) lista.add(celda);
        return lista;
    }

    private static void uno(ModbusTcpConexion c, int unitId, int desde, int base, int j, Celda[] celdas) throws IOException {
        String motivo = null;
        for (int ancho : new int[]{1, 2, 4}) {
            try {
                int[] v = c.leerHolding(unitId, base + j, ancho);
                for (int k = 0; k < ancho && j + k < celdas.length; k++) {
                    if (celdas[j + k] == null) {
                        celdas[j + k] = new Celda(desde + j + k, v[k], ancho, k, ancho == 1 ? null : (ancho * 16) + " bits desde " + (desde + j));
                    }
                }
                return;
            } catch (ModbusTcpConexion.ExcepcionModbus e) {
                if (motivo == null) motivo = e.getMessage();
            }
        }
        celdas[j] = new Celda(desde + j, null, 1, 0, motivo);
    }
}
