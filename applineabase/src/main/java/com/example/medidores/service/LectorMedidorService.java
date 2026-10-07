package com.example.medidores.service;

import com.example.dataacquisition.service.ModbusTcpConexion;
import com.example.dataacquisition.service.ModbusTcpConexion.ExcepcionModbus;
import com.example.medidores.model.FuncionLectura;
import com.example.medidores.model.OrdenPalabras;
import com.example.medidores.model.ParametroMedidor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lee parámetros de un medidor según su modelo del catálogo, por una conexión Modbus ya abierta
 * (pasarela). Agrupa los registros cercanos en un solo pedido (PM710: los 9 básicos en 1 pedido;
 * PM5110: 2) para ocupar menos tiempo del bus RS-485.
 *
 * Si un bloque abarca registros que el medidor no tiene (excepción 0x02/0x03), se lee ese bloque
 * parámetro por parámetro y el modelo queda marcado para no agrupar más (hasta reiniciar), así un
 * modelo con huecos raros no paga el doble de pedidos en cada ciclo.
 *
 * Thread-safe: no guarda estado por medidor (solo el conjunto de modelos sin agrupar).
 */
@Service
public class LectorMedidorService {

    private static final Logger logger = LoggerFactory.getLogger(LectorMedidorService.class);

    /** Registros sin usar tolerados entre dos parámetros para pedirlos juntos. */
    private static final int MAX_HUECO = 40;
    /** Tamaño máximo de un pedido (el estándar Modbus permite 125). */
    private static final int MAX_BLOQUE = 100;

    private static final int EXC_DIRECCION_INVALIDA = 0x02;
    private static final int EXC_VALOR_INVALIDO = 0x03;

    private final Set<String> modelosSinAgrupar = ConcurrentHashMap.newKeySet();

    /**
     * Resultado de una lectura: valores ya decodificados y escalados (PF tal como lo entrega el
     * medidor, sin decodificar 4 cuadrantes) y, por separado, los parámetros que no se pudieron
     * leer con el motivo.
     */
    public record Lectura(Map<ParametroMedidor, Double> valores, Map<ParametroMedidor, String> errores) {
    }

    /**
     * @throws ExcepcionModbus si el medidor no está disponible (no respondió a la pasarela, falla
     *                         del dispositivo...): no tiene sentido seguir pidiéndole cosas
     * @throws IOException     si la pasarela no respondió (hay que reconectar)
     */
    public Lectura leer(ModbusTcpConexion conexion, int unitId, DefinicionModelo modelo,
                        Collection<ParametroMedidor> parametros) throws IOException, ExcepcionModbus {
        Map<ParametroMedidor, Double> valores = new EnumMap<>(ParametroMedidor.class);
        Map<ParametroMedidor, String> errores = new EnumMap<>(ParametroMedidor.class);

        List<DefinicionModelo.Registro> pedidos = new ArrayList<>();
        for (ParametroMedidor p : parametros) {
            DefinicionModelo.Registro r = modelo.registros().get(p);
            if (r == null) {
                errores.put(p, "no disponible en el modelo " + modelo.nombre());
            } else {
                pedidos.add(r);
            }
        }
        pedidos.sort(Comparator.comparingInt(DefinicionModelo.Registro::direccion));

        boolean agrupar = !modelosSinAgrupar.contains(modelo.nombre());
        for (List<DefinicionModelo.Registro> bloque : agrupar ? armarBloques(pedidos) : individuales(pedidos)) {
            if (bloque.size() == 1) {
                leerUno(conexion, unitId, modelo.funcion(), bloque.get(0), valores, errores);
                continue;
            }
            int inicio = bloque.get(0).direccion();
            int fin = bloque.stream().mapToInt(DefinicionModelo.Registro::fin).max().orElse(inicio);
            try {
                int[] datos = pedir(conexion, unitId, modelo.funcion(), inicio, fin - inicio);
                for (DefinicionModelo.Registro r : bloque) {
                    decodificarEn(datos, r.direccion() - inicio, r, valores, errores);
                }
            } catch (ExcepcionModbus e) {
                if (!esErrorDeDireccion(e)) {
                    throw e;
                }
                int leidosAntes = valores.size();
                for (DefinicionModelo.Registro r : bloque) {
                    leerUno(conexion, unitId, modelo.funcion(), r, valores, errores);
                }
                // Solo si algún parámetro salió por separado el problema era el rango del bloque; si
                // ninguno sale (Unit ID equivocado, otro equipo) no se castiga al modelo entero.
                if (valores.size() > leidosAntes && modelosSinAgrupar.add(modelo.nombre())) {
                    logger.info("Modelo {}: el bloque {}-{} incluye registros que el medidor no tiene ({}); "
                            + "desde ahora se lee parametro por parametro", modelo.nombre(), inicio, fin - 1, e.getMessage());
                }
            }
        }
        return new Lectura(valores, errores);
    }

    private void leerUno(ModbusTcpConexion conexion, int unitId, FuncionLectura funcion, DefinicionModelo.Registro r,
                         Map<ParametroMedidor, Double> valores, Map<ParametroMedidor, String> errores)
            throws IOException, ExcepcionModbus {
        try {
            int[] datos = pedir(conexion, unitId, funcion, r.direccion(), r.cantidad());
            decodificarEn(datos, 0, r, valores, errores);
        } catch (ExcepcionModbus e) {
            if (!esErrorDeDireccion(e)) {
                throw e;
            }
            errores.put(r.parametro(), "registro " + r.registroManual() + ": " + e.getMessage());
        }
    }

    private static int[] pedir(ModbusTcpConexion conexion, int unitId, FuncionLectura funcion, int direccion, int cantidad)
            throws IOException, ExcepcionModbus {
        return funcion == FuncionLectura.INPUT
                ? conexion.leerInput(unitId, direccion, cantidad)
                : conexion.leerHolding(unitId, direccion, cantidad);
    }

    private static void decodificarEn(int[] datos, int desde, DefinicionModelo.Registro r,
                                      Map<ParametroMedidor, Double> valores, Map<ParametroMedidor, String> errores) {
        try {
            valores.put(r.parametro(), decodificar(datos, desde, r));
        } catch (IllegalArgumentException e) {
            errores.put(r.parametro(), "registro " + r.registroManual() + ": " + e.getMessage());
        }
    }

    private static boolean esErrorDeDireccion(ExcepcionModbus e) {
        return e.getCodigo() == EXC_DIRECCION_INVALIDA || e.getCodigo() == EXC_VALOR_INVALIDO;
    }

    private static List<List<DefinicionModelo.Registro>> armarBloques(List<DefinicionModelo.Registro> ordenados) {
        List<List<DefinicionModelo.Registro>> bloques = new ArrayList<>();
        List<DefinicionModelo.Registro> actual = new ArrayList<>();
        int inicio = 0;
        int fin = 0;
        for (DefinicionModelo.Registro r : ordenados) {
            boolean entra = !actual.isEmpty()
                    && r.direccion() - fin <= MAX_HUECO
                    && Math.max(fin, r.fin()) - inicio <= MAX_BLOQUE;
            if (!entra && !actual.isEmpty()) {
                bloques.add(actual);
                actual = new ArrayList<>();
            }
            if (actual.isEmpty()) {
                inicio = r.direccion();
                fin = r.fin();
            } else {
                fin = Math.max(fin, r.fin());
            }
            actual.add(r);
        }
        if (!actual.isEmpty()) {
            bloques.add(actual);
        }
        return bloques;
    }

    private static List<List<DefinicionModelo.Registro>> individuales(List<DefinicionModelo.Registro> registros) {
        return registros.stream().map(List::of).toList();
    }

    /**
     * Convierte los registros crudos de un parámetro a su valor (tipo, orden de palabras, escala).
     *
     * @throws IllegalArgumentException si el valor no es un número (Float NaN/infinito)
     */
    public static double decodificar(int[] datos, int desde, DefinicionModelo.Registro r) {
        int n = r.cantidad();
        if (desde < 0 || desde + n > datos.length) {
            throw new IllegalArgumentException("respuesta incompleta");
        }
        long bits = 0;
        for (int i = 0; i < n; i++) {
            int palabra = r.ordenPalabras() == OrdenPalabras.INVERTIDO ? datos[desde + n - 1 - i] : datos[desde + i];
            bits = (bits << 16) | (palabra & 0xFFFF);
        }
        double valor = switch (r.tipoDato()) {
            case FLOAT32 -> {
                float f = Float.intBitsToFloat((int) bits);
                if (Float.isNaN(f) || Float.isInfinite(f)) {
                    throw new IllegalArgumentException("valor no numerico (el medidor no tiene el dato)");
                }
                yield f;
            }
            case INT16 -> (short) bits;
            case UINT16 -> bits & 0xFFFF;
            case INT32 -> (int) bits;
            case UINT32 -> bits & 0xFFFFFFFFL;
            case INT64 -> bits;
            case UINT64 -> bits >= 0 ? bits : (double) (bits >>> 1) * 2.0 + (bits & 1);
        };
        return valor * r.escala();
    }

    /**
     * Factor de potencia en formato de 4 cuadrantes (Schneider PM5xxx) al valor real con signo:
     * mayor que 1 -> 2 - valor; menor que -1 -> -2 - valor; entre -1 y 1 queda igual.
     */
    public static double decodificarPf4Cuadrantes(double valor) {
        if (valor > 1.0) {
            return 2.0 - valor;
        }
        if (valor < -1.0) {
            return -2.0 - valor;
        }
        return valor;
    }
}
