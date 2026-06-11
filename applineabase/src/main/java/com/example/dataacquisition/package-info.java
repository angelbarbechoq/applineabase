/**
 * Data acquisition feature for reading PLC and PAS600L devices.
 *
 * Responsible for:
 * - Scheduled data collection from Siemens S7-200 PLCs via Modbus TCP/IP
 * - Scheduled data collection from Schneider PAS600L meters via Modbus TCP/IP
 * - Persistent storage of acquired data to SQLite
 */
package com.example.dataacquisition;
