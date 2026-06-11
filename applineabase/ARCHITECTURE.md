# Arquitectura LineaBaseX

## Estructura del Proyecto

```
applineabase/
│
├── src/main/java/com/example/
│   │
│   ├── Application.java
│   │   └── SpringBoot App + ThreadPoolTaskScheduler (2 threads)
│   │
│   ├── base/
│   │   └── ui/
│   │       ├── MainLayout.java (AppLayout con navegación)
│   │       └── ViewTitle.java
│   │
│   ├── dataacquisition/                    ← NUEVA FEATURE
│   │   │
│   │   ├── service/
│   │   │   ├── DataAcquisitionTask.java
│   │   │   │   └── Tarea Scheduler (cada 1 segundo)
│   │   │   │       ├── Lee PLCs
│   │   │   │       ├── Lee PAS600L
│   │   │   │       └── Persiste datos
│   │   │   │
│   │   │   ├── PLCReaderService.java
│   │   │   │   └── Lectura Siemens S7-200 vía Modbus TCP/IP
│   │   │   │
│   │   │   ├── PASReaderService.java
│   │   │   │   └── Lectura Schneider PAS600L vía Modbus TCP/IP
│   │   │   │
│   │   │   └── DataStorageService.java
│   │   │       └── Persistencia en SQLite
│   │   │
│   │   ├── model/
│   │   │   ├── PLCDevice.java
│   │   │   │   └── Propiedades: IP, nombre, arrays de valores
│   │   │   │
│   │   │   └── PASDevice.java
│   │   │       └── Propiedades: IP, nombre, voltajes, corrientes, etc.
│   │   │
│   │   └── package-info.java
│   │
│   └── examplefeature/                     ← Se elimina después
│       ├── Task.java
│       ├── TaskRepository.java
│       ├── TaskService.java
│       └── ui/
│           └── TaskListView.java
│
├── src/main/resources/
│   ├── application.properties
│   ├── styles.css
│   └── icons/
│
├── pom.xml
│   └── Vaadin 25.1.3 + Spring Boot 4.0.5 + Java 21
│
└── README.md
```

## Flujo de Datos

```
DataAcquisitionTask (cada 1 segundo)
    │
    ├─→ PLCReaderService.readAll()
    │   └─→ ModbusClient → Siemens S7-200
    │       └─→ Registros Modbus (voltajes, corrientes, KWh)
    │
    ├─→ PASReaderService.readAll()
    │   └─→ ModbusClient → Schneider PAS600L
    │       └─→ Registros Modbus (3019, 2999, 3059, 3083)
    │
    └─→ DataStorageService.persist()
        └─→ SQLite (YYYY/MM/DD)
```

## Parámetros Modbus (del análisis)

| Dispositivo | Registro | Parámetro |
|-------------|----------|-----------|
| PAS600L | 3019 | Voltaje |
| PAS600L | 2999 | Corriente |
| PAS600L | 3059 | Potencia (KW) |
| PAS600L | 3083 | Factor de Potencia |

