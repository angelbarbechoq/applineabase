package com.example.dataacquisition.controller;

import com.example.dataacquisition.service.PLCDataQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/plc")
public class PLCDataController {

    private static final Logger logger = LoggerFactory.getLogger(PLCDataController.class);

    private final PLCDataQueryService plcDataQueryService;

    public PLCDataController(PLCDataQueryService plcDataQueryService) {
        this.plcDataQueryService = plcDataQueryService;
    }
    @GetMapping("/latest/kwh/{maquina}/{fecha}")
    public Map<String, Object> getKWhByFecha(
            @PathVariable String maquina,
            @PathVariable String fecha) {
        logger.info("Request for KWh data: {} on {}", maquina, fecha);
        return plcDataQueryService.getKWhByFechaExacta(maquina, fecha);
    }
    @GetMapping("/latest/vip/{maquina}")
    public Map<String, Object> getLatestVIPData(@PathVariable String maquina) {
        logger.info("Request for latest VIP data: {}", maquina);
        return plcDataQueryService.getLatestVIPDataByMaquina(maquina);
    }

    @GetMapping("/latest/kwh/{maquina}")
    public Map<String, Object> getLatestKWhData(@PathVariable String maquina) {
        logger.info("Request for latest KWh data: {}", maquina);
        return plcDataQueryService.getLatestKWhDataByMaquina(maquina);
    }

    @GetMapping("/today/{maquina}")
    public java.util.List<Map<String, Object>> getTodayData(@PathVariable String maquina) {
        logger.info("Request for today data: {}", maquina);
        return plcDataQueryService.getTodayDataByMaquina(maquina);
    }

    @GetMapping("/today-kwh/{maquina}")
    public java.util.List<Map<String, Object>> getTodayKWhData(@PathVariable String maquina) {
        logger.info("Request for today KWh data: {}", maquina);
        return plcDataQueryService.getTodayKWhDataByMaquina(maquina);
    }

    @GetMapping("/historico/vip/{maquina}")
    public java.util.List<Map<String, Object>> getHistoricoVIP(
            @PathVariable String maquina,
            @RequestParam String desde,
            @RequestParam String hasta) {
        LocalDate dDesde = LocalDate.parse(desde);
        LocalDate dHasta = LocalDate.parse(hasta);
        logger.info("Historico VIP {} desde {} hasta {}", maquina, dDesde, dHasta);
        return plcDataQueryService.getHistoricoVIPByRango(maquina, dDesde, dHasta);
    }

    @GetMapping("/historico/kwh/{maquina}")
    public java.util.List<Map<String, Object>> getHistoricoKWh(
            @PathVariable String maquina,
            @RequestParam String desde,
            @RequestParam String hasta) {
        LocalDate dDesde = LocalDate.parse(desde);
        LocalDate dHasta = LocalDate.parse(hasta);
        logger.info("Historico KWh {} desde {} hasta {}", maquina, dDesde, dHasta);
        return plcDataQueryService.getHistoricoKWhByRango(maquina, dDesde, dHasta);
    }
}
