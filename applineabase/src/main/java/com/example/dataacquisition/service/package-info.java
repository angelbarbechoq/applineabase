/**
 * Data acquisition services.
 *
 * Contains:
 * - DataAcquisitionTask: Scheduled task executor (runs every second)
 * - PLCReaderService: Reads data from Siemens S7-200 PLCs
 * - PASReaderService: Reads data from Schneider PAS600L meters
 * - DataStorageService: Persists data to SQLite
 */
package com.example.dataacquisition.service;
