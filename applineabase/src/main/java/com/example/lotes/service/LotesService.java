package com.example.lotes.service;

import com.example.dataacquisition.service.PLCDataQueryService;
import com.example.lotes.model.LoteProduccion;
import com.example.lotes.repository.LoteProduccionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Marcado y validación de lotes de producción. El kWh de cada marca es el del contador guardado en la base de energía
 * en el minuto clicado (archivo mensual), no la lectura actual del medidor.
 */
@Service
public class LotesService {

    public static final String SIN_ITEM = "Sin ítem";
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private final LoteProduccionRepository repo;
    private final PLCDataQueryService plcDataQueryService;

    public LotesService(LoteProduccionRepository repo, PLCDataQueryService plcDataQueryService) {
        this.repo = repo;
        this.plcDataQueryService = plcDataQueryService;
    }

    /** kWh del contador guardado en ese minuto; vacío si no hay dato. */
    public Optional<Double> kwhGuardado(String linea, LocalDateTime momento) {
        String f = momento.format(FECHA_HORA);
        Map<String, Object> d = plcDataQueryService.getKWhByFechaExactaHistorico(linea, f);
        if (!(d.get("kwh") instanceof Number)) d = plcDataQueryService.getKWhByFechaExacta(linea, f);
        Object v = d.get("kwh");
        return v instanceof Number n ? Optional.of(n.doubleValue()) : Optional.empty();
    }

    public Optional<LoteProduccion> enCurso(String linea) {
        return repo.findFirstByLineaAndFinIsNullOrderByInicioDesc(linea);
    }

    @Transactional
    public LoteProduccion validarInicio(String linea, String producto, Long productoId, LocalDateTime inicio,
                                         double kwh, String usuario) {
        if (producto == null || producto.isBlank()) throw new IllegalArgumentException("Elija el producto (o «Sin ítem»).");
        Optional<LoteProduccion> abierto = enCurso(linea);
        if (abierto.isPresent()) {
            throw new IllegalArgumentException("La línea ya tiene un lote en curso (" + abierto.get().getProducto()
                    + ", desde " + abierto.get().getInicio().format(FECHA_HORA) + "). Marque primero su fin.");
        }
        return repo.save(new LoteProduccion(linea, producto, productoId, inicio, kwh, usuario));
    }

    @Transactional
    public LoteProduccion validarFin(String linea, LocalDateTime fin, double kwh, String usuario) {
        LoteProduccion l = enCurso(linea).orElseThrow(() ->
                new IllegalArgumentException("La línea no tiene un lote en curso. Marque primero el inicio."));
        if (!fin.isAfter(l.getInicio())) {
            throw new IllegalArgumentException("El fin debe ser posterior al inicio (" + l.getInicio().format(FECHA_HORA) + ").");
        }
        if (kwh < l.getKwhInicio()) {
            throw new IllegalArgumentException("El contador en el fin es menor que en el inicio. Revise el punto marcado.");
        }
        l.cerrar(fin, kwh, usuario);
        return repo.save(l);
    }

    public List<LoteProduccion> todos() {
        return repo.findAllByOrderByInicioDesc();
    }

    @Transactional
    public void borrar(LoteProduccion l) {
        repo.deleteById(l.getId());
    }
}
