package com.example.dataacquisition.service;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.ModbusSlaveException;
import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;

import java.io.Closeable;
import java.io.IOException;

/**
 * Único punto de acceso Modbus TCP de la app (PLC, pasarelas PAS600L/Link150, mezcladores), sobre
 * la librería j2mod. Antes se usaba EasyModbus, que nunca detectaba las respuestas de excepción
 * Modbus (comparaba un byte con signo contra 131): cuando la pasarela contestaba "el medidor no
 * respondió" (0x0B), EasyModbus devolvía registros en cero como si fueran datos válidos.
 *
 * Separa los dos tipos de falla, porque se manejan distinto:
 * - {@link ExcepcionModbus}: el equipo/pasarela contestó con un código de excepción. La conexión
 *   sigue sirviendo para el próximo Unit ID.
 * - {@link IOException}: no hubo respuesta válida (tiempo vencido, conexión cortada, respuesta de
 *   otra transacción). La conexión se descarta y se reconecta en el próximo pedido, para que una
 *   respuesta tardía no se confunda con la del pedido siguiente.
 *
 * No es thread-safe: una instancia por hilo (una por pasarela/PLC por ciclo).
 */
final class ModbusTcpConexion implements Closeable {

    /** El equipo contestó con una excepción Modbus (código estándar 0x01-0x0B). */
    static final class ExcepcionModbus extends Exception {
        private final int codigo;

        ExcepcionModbus(int codigo) {
            super("excepción Modbus 0x" + String.format("%02X", codigo) + " (" + descripcion(codigo) + ")");
            this.codigo = codigo;
        }

        int getCodigo() {
            return codigo;
        }

        static String descripcion(int codigo) {
            return switch (codigo) {
                case 0x01 -> "función no soportada";
                case 0x02 -> "dirección de registro inválida";
                case 0x03 -> "valor o cantidad inválida";
                case 0x04 -> "falla del dispositivo";
                case 0x05 -> "pedido aceptado, en proceso";
                case 0x06 -> "dispositivo ocupado";
                case 0x08 -> "error de paridad de memoria";
                case 0x0A -> "pasarela sin ruta al dispositivo";
                case 0x0B -> "el dispositivo no respondió a la pasarela";
                default -> "código no estándar";
            };
        }
    }

    static final int PUERTO_MODBUS = 502;
    /** Sin reintentos dentro de un mismo ciclo: el próximo ciclo ya es el reintento. Con los 5 que
     * trae j2mod por defecto, un equipo caído multiplicaría por 6 su espera. */
    private static final int REINTENTOS = 0;

    private final String ip;
    private final int puerto;
    private final int timeoutMs;
    private ModbusTCPMaster master;

    ModbusTcpConexion(String ip, int timeoutMs) {
        this(ip, PUERTO_MODBUS, timeoutMs);
    }

    ModbusTcpConexion(String ip, int puerto, int timeoutMs) {
        this.ip = ip;
        this.puerto = puerto;
        this.timeoutMs = timeoutMs;
    }

    String getIp() {
        return ip;
    }

    void conectar() throws IOException {
        close();
        ModbusTCPMaster m = new ModbusTCPMaster(ip, puerto, timeoutMs, false);
        m.setRetries(REINTENTOS);
        m.setCheckingValidity(true);
        try {
            m.connect();
        } catch (Exception e) {
            m.disconnect();
            throw new IOException("no se pudo conectar a " + ip + ":" + puerto + ": " + e.getMessage(), e);
        }
        master = m;
    }

    boolean estaConectada() {
        return master != null && master.isConnected();
    }

    /** Función 03. {@code direccion} base 0 (como viaja en el cable). Valores 0-65535. */
    int[] leerHolding(int unitId, int direccion, int cantidad) throws IOException, ExcepcionModbus {
        return valores(ejecutar(() -> master.readMultipleRegisters(unitId, direccion, cantidad)), cantidad);
    }

    /** Función 04. {@code direccion} base 0. Valores 0-65535. */
    int[] leerInput(int unitId, int direccion, int cantidad) throws IOException, ExcepcionModbus {
        return valores(ejecutar(() -> master.readInputRegisters(unitId, direccion, cantidad)), cantidad);
    }

    /** Función 16. {@code direccion} base 0. */
    void escribirHolding(int unitId, int direccion, int[] valores) throws IOException, ExcepcionModbus {
        Register[] registros = new Register[valores.length];
        for (int i = 0; i < valores.length; i++) {
            registros[i] = new SimpleRegister(valores[i] & 0xFFFF);
        }
        ejecutar(() -> master.writeMultipleRegisters(unitId, direccion, registros));
    }

    @FunctionalInterface
    private interface Pedido<T> {
        T ejecutar() throws ModbusException;
    }

    private <T> T ejecutar(Pedido<T> pedido) throws IOException, ExcepcionModbus {
        if (!estaConectada()) {
            conectar();
        }
        try {
            return pedido.ejecutar();
        } catch (ModbusSlaveException e) {
            throw new ExcepcionModbus(e.getType());
        } catch (ModbusException e) {
            close(); // sin respuesta válida: no reusar este socket
            throw new IOException(e.getMessage(), e);
        }
    }

    private static int[] valores(InputRegister[] registros, int esperados) throws IOException {
        if (registros == null || registros.length != esperados) {
            throw new IOException("respuesta con " + (registros == null ? 0 : registros.length)
                    + " registros, se esperaban " + esperados);
        }
        int[] valores = new int[registros.length];
        for (int i = 0; i < registros.length; i++) {
            valores[i] = registros[i].getValue();
        }
        return valores;
    }

    @Override
    public void close() {
        if (master != null) {
            master.disconnect();
            master = null;
        }
    }
}
