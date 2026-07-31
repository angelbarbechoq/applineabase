package com.example.dataacquisition.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.InetAddress;

/**
 * Utilidades Modbus compartidas por {@link PASReaderService} y {@link PLCDataAcquisitionService}
 * (antes duplicadas letra por letra en ambas clases).
 */
final class ModbusUtil {

    private static final Logger logger = LoggerFactory.getLogger(ModbusUtil.class);

    private ModbusUtil() {
    }

    /** Convierte dos registros Modbus (hi/lo) a BigDecimal (IEEE754 float). */
    static BigDecimal registroIntToBigDecimal(int[] dataMbTcIp) {
        if (dataMbTcIp == null || dataMbTcIp.length != 2) {
            return BigDecimal.ZERO;
        }

        int combinado = (dataMbTcIp[0] << 16) | (dataMbTcIp[1] & 0xFFFF);
        float floatValue = Float.intBitsToFloat(combinado);

        if (Float.isNaN(floatValue)) {
            return BigDecimal.ZERO;
        }

        return new BigDecimal(floatValue);
    }

    /** Chequea si una IP responde a ping antes de intentar conectar por Modbus. */
    static boolean isIPAvailable(String ipAddress) {
        try {
            return InetAddress.getByName(ipAddress).isReachable(3000);
        } catch (Exception e) {
            logger.debug("Ping failed for IP {}: {}", ipAddress, e.getMessage());
            return false;
        }
    }
}
