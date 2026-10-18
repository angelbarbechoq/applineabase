package com.example.franjahoraria.service;

import com.example.franjahoraria.model.Franja;
import com.example.franjahoraria.model.TarifaFranja;
import com.example.franjahoraria.repository.TarifaFranjaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/** Tarifas por franja horaria, una por mes. */
@Service
public class TarifaFranjaService {

    public static final double PRECIO_MAXIMO = 10;

    private final TarifaFranjaRepository repository;

    public TarifaFranjaService(TarifaFranjaRepository repository) {
        this.repository = repository;
    }

    /**
     * Tarifa con la que se calcula un mes: la propia, o si no tiene, la del último mes cargado antes
     * (heredada). tarifa == null = no hay ninguna cargada hasta ese mes.
     */
    public record TarifaDelMes(YearMonth mes, TarifaFranja tarifa) {
        public boolean sinTarifa() {
            return tarifa == null;
        }

        public boolean propia() {
            return tarifa != null && YearMonth.from(tarifa.getMes()).equals(mes);
        }

        /** Mes del que sale la tarifa (el propio o el heredado). */
        public YearMonth origen() {
            return tarifa == null ? null : YearMonth.from(tarifa.getMes());
        }

        public Double precio(Franja f) {
            return tarifa == null ? null : tarifa.precio(f);
        }
    }

    /** Del mes más nuevo al más viejo. */
    public List<TarifaFranja> listar() {
        return repository.findAllByOrderByMesDesc();
    }

    /** tarifas ordenadas del mes más nuevo al más viejo (como las devuelve {@link #listar()}). */
    public static TarifaDelMes delMes(List<TarifaFranja> tarifas, YearMonth mes) {
        LocalDate inicio = mes.atDay(1);
        return new TarifaDelMes(mes, tarifas.stream().filter(t -> !t.getMes().isAfter(inicio)).findFirst().orElse(null));
    }

    @Transactional
    public TarifaFranja guardar(YearMonth mes, Map<Franja, Double> precios, String observacion, String usuario) {
        for (Franja f : Franja.values()) {
            Double p = precios.get(f);
            if (p == null || p <= 0 || p > PRECIO_MAXIMO) {
                throw new IllegalArgumentException("El precio de la franja " + f.name() + " debe ser mayor que 0 y menor que "
                        + (int) PRECIO_MAXIMO + " $/kWh");
            }
        }
        LocalDate inicio = mes.atDay(1);
        TarifaFranja t = repository.findByMes(inicio).orElseGet(() -> new TarifaFranja(inicio));
        for (Franja f : Franja.values()) t.setPrecio(f, precios.get(f));
        t.setObservacion(observacion == null || observacion.isBlank() ? null : observacion.trim());
        t.setUsuario(usuario);
        t.setFechaRegistro(LocalDateTime.now().withNano(0));
        return repository.save(t);
    }

    @Transactional
    public void borrar(TarifaFranja t) {
        repository.deleteById(t.getId());
    }
}
