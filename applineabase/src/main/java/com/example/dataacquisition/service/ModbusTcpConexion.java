package com.example.dataacquisition.service;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Cliente Modbus TCP mínimo (solo función 03, Read Holding Registers) para leer medidores a
 * través de pasarelas (PAS600L, Link150).
 *
 * Reemplaza a EasyModbus en {@link PASReaderService} porque EasyModbus compara el código de
 * función de la respuesta (byte con signo) contra 131, así que nunca detecta una respuesta de
 * excepción Modbus: cuando la pasarela contesta "el medidor no respondió" (excepción 0x0A/0x0B),
 * EasyModbus devuelve registros armados con basura/ceros como si fueran datos válidos.
 *
 * Una conexión por pasarela por ciclo; el Unit ID va en cada pedido. Si un pedido vence por
 * tiempo, la conexión queda descartada (una respuesta tardía se mezclaría con el pedido siguiente)
 * y el llamador debe reconectar.
 */
final class ModbusTcpConexion implements Closeable {

    /** La pasarela contestó con una excepción Modbus (medidor sin respuesta, dirección inválida...). */
    static final class ExcepcionModbus extends Exception {
        private final int codigo;

        ExcepcionModbus(int codigo) {
            super("excepción Modbus 0x" + Integer.toHexString(codigo).toUpperCase() + " (" + descripcion(codigo) + ")");
            this.codigo = codigo;
        }

        int getCodigo() {
            return codigo;
        }

        private static String descripcion(int codigo) {
            return switch (codigo) {
                case 0x01 -> "función no soportada";
                case 0x02 -> "dirección de registro inválida";
                case 0x03 -> "cantidad inválida";
                case 0x04 -> "falla del dispositivo";
                case 0x0A -> "pasarela sin ruta al dispositivo";
                case 0x0B -> "el medidor no respondió a la pasarela";
                default -> "código desconocido";
            };
        }
    }

    private static final int PUERTO = 502;

    private final String ip;
    private final int timeoutMs;
    private Socket socket;
    private DataInputStream entrada;
    private OutputStream salida;
    private int transaccion = 0;

    ModbusTcpConexion(String ip, int timeoutMs) {
        this.ip = ip;
        this.timeoutMs = timeoutMs;
    }

    void conectar() throws IOException {
        close();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(ip, PUERTO), timeoutMs);
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);
        socket = s;
        entrada = new DataInputStream(s.getInputStream());
        salida = s.getOutputStream();
    }

    boolean estaConectada() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * Lee {@code cantidad} holding registers desde {@code direccion} (base 0, como en el cable).
     *
     * @throws ExcepcionModbus si la pasarela/medidor contestó con excepción (la conexión sigue útil)
     * @throws IOException si venció el tiempo o se cortó la conexión (hay que reconectar)
     */
    int[] leerHolding(int unitId, int direccion, int cantidad) throws IOException, ExcepcionModbus {
        if (!estaConectada()) {
            throw new IOException("conexión cerrada");
        }
        int tid = (++transaccion) & 0xFFFF;
        byte[] pedido = {
                (byte) (tid >> 8), (byte) tid,
                0, 0,          // protocolo Modbus
                0, 6,          // largo: unit + función + dirección + cantidad
                (byte) unitId,
                0x03,
                (byte) (direccion >> 8), (byte) direccion,
                (byte) (cantidad >> 8), (byte) cantidad
        };
        try {
            salida.write(pedido);
            salida.flush();

            byte[] cabecera = new byte[7];
            entrada.readFully(cabecera);
            int tidRespuesta = ((cabecera[0] & 0xFF) << 8) | (cabecera[1] & 0xFF);
            int largo = ((cabecera[4] & 0xFF) << 8) | (cabecera[5] & 0xFF);
            if (largo < 2 || largo > 260) {
                throw new IOException("largo de respuesta inválido: " + largo);
            }
            byte[] pdu = new byte[largo - 1];
            entrada.readFully(pdu);
            if (tidRespuesta != tid) {
                throw new IOException("respuesta de otro pedido (transacción " + tidRespuesta + ", esperada " + tid + ")");
            }

            int funcion = pdu[0] & 0xFF;
            if ((funcion & 0x80) != 0) {
                throw new ExcepcionModbus(pdu.length > 1 ? pdu[1] & 0xFF : 0);
            }
            int bytes = pdu[1] & 0xFF;
            if (funcion != 0x03 || bytes != cantidad * 2 || pdu.length < 2 + bytes) {
                throw new IOException("respuesta mal formada (función " + funcion + ", " + bytes + " bytes)");
            }
            int[] registros = new int[cantidad];
            for (int i = 0; i < cantidad; i++) {
                registros[i] = ((pdu[2 + i * 2] & 0xFF) << 8) | (pdu[3 + i * 2] & 0xFF);
            }
            return registros;
        } catch (IOException e) {
            close(); // una respuesta tardía no debe confundirse con la del pedido siguiente
            throw e;
        }
    }

    @Override
    public void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // ya no sirve; nada que hacer
            }
        }
        socket = null;
        entrada = null;
        salida = null;
    }
}
