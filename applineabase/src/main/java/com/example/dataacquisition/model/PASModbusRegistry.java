package com.example.dataacquisition.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of Modbus holding register addresses for different PAS600L meter models.
 *
 * Maps:
 * - Model (PM5110, PM710, etc.)
 * - Variable type (V=Voltage, I=Current, KW=Power, PF=PowerFactor, KWh=Energy)
 * - To: [startAddress, quantity]
 *
 * Based on the original TimerMbTcIp1.java startAddQuatity() method.
 */
public class PASModbusRegistry {

    private static final Logger logger = LoggerFactory.getLogger(PASModbusRegistry.class.getName());

    /**
     * Get Modbus register information for a specific meter model and variable type.
     *
     * @param modeloPM Meter model (e.g., "PM5110", "PM710")
     * @param variableType Variable type: "V" (Voltage), "I" (Current), "KW" (Power), "PF" (PowerFactor), "KWh" (Energy)
     * @return int[2] with [startAddress, quantity], or null if not found
     */
    public static int[] getRegisterInfo(String modeloPM, String variableType) {
        int[] result = new int[2];

        switch (variableType) {
            case "V": // Voltage: VAB, VBC, VAC (6 registers total)
                switch (modeloPM) {
                    case "PM5110":
                        result[0] = 3019;  // Starting address for voltage
                        result[1] = 6;     // 6 registers (3 voltages × 2 registers each)
                        break;
                    case "PM710":
                        result[0] = 1053;
                        result[1] = 6;
                        break;
                    default:
                        logger.warn("Unknown meter model for Voltage: {}", modeloPM);
                        return null;
                }
                break;

            case "I": // Current: IA, IB, IC (6 registers total)
                switch (modeloPM) {
                    case "PM5110":
                        result[0] = 2999;  // Starting address for current
                        result[1] = 6;     // 6 registers (3 currents × 2 registers each)
                        break;
                    case "PM710":
                        result[0] = 1033;
                        result[1] = 6;
                        break;
                    default:
                        logger.warn("Unknown meter model for Current: {}", modeloPM);
                        return null;
                }
                break;

            case "KW": // Active Power (2 registers)
                switch (modeloPM) {
                    case "PM5110":
                        result[0] = 3059;  // Starting address for power
                        result[1] = 2;     // 2 registers (1 power value)
                        break;
                    case "PM710":
                        result[0] = 1005;
                        result[1] = 2;
                        break;
                    default:
                        logger.warn("Unknown meter model for Power: {}", modeloPM);
                        return null;
                }
                break;

            case "PF": // Power Factor (2 registers)
                switch (modeloPM) {
                    case "PM5110":
                        result[0] = 3083;  // Starting address for PF
                        result[1] = 2;     // 2 registers (1 PF value)
                        break;
                    case "PM710":
                        result[0] = 1011;
                        result[1] = 2;
                        break;
                    default:
                        logger.warn("Unknown meter model for PowerFactor: {}", modeloPM);
                        return null;
                }
                break;

            case "KWh": // Energy (2 registers)
                switch (modeloPM) {
                    case "PM5110":
                        result[0] = 2699;  // Starting address for KWh
                        result[1] = 2;     // 2 registers (1 KWh value)
                        break;
                    case "PM710":
                        result[0] = 999;
                        result[1] = 2;
                        break;
                    default:
                        logger.warn("Unknown meter model for Energy: {}", modeloPM);
                        return null;
                }
                break;

            default:
                logger.warn("Unknown variable type: {}", variableType);
                return null;
        }

        return result;
    }
}
