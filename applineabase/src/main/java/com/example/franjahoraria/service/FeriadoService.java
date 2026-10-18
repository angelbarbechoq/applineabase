package com.example.franjahoraria.service;

import com.example.franjahoraria.model.Feriado;
import com.example.franjahoraria.repository.FeriadoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Feriados cargados a mano (opcional): esos días cuentan como sábado o domingo en las franjas. */
@Service
public class FeriadoService {

    private final FeriadoRepository repository;

    public FeriadoService(FeriadoRepository repository) {
        this.repository = repository;
    }

    /** Del más nuevo al más viejo. */
    public List<Feriado> listar() {
        return repository.findAllByOrderByFechaDesc();
    }

    /** Feriados de un mes: fecha -> nombre. */
    public Map<LocalDate, String> delMes(YearMonth mes) {
        Map<LocalDate, String> m = new TreeMap<>();
        for (Feriado f : repository.findByFechaBetween(mes.atDay(1), mes.atEndOfMonth())) m.put(f.getFecha(), f.getNombre());
        return m;
    }

    /** Fechas de los feriados entre dos días (inclusive). */
    public Set<LocalDate> fechasEntre(LocalDate desde, LocalDate hasta) {
        Set<LocalDate> s = new HashSet<>();
        for (Feriado f : repository.findByFechaBetween(desde, hasta)) s.add(f.getFecha());
        return s;
    }

    /** Agrega el feriado, o le cambia el nombre si esa fecha ya estaba. */
    @Transactional
    public Feriado guardar(LocalDate fecha, String nombre, String usuario) {
        if (fecha == null) throw new IllegalArgumentException("Elija la fecha del feriado");
        if (nombre == null || nombre.isBlank()) throw new IllegalArgumentException("Escriba el nombre del feriado");
        Feriado f = repository.findByFecha(fecha).orElseGet(() -> new Feriado(fecha));
        f.setNombre(nombre.trim());
        f.setUsuario(usuario);
        f.setFechaRegistro(LocalDateTime.now().withNano(0));
        return repository.save(f);
    }

    @Transactional
    public void borrar(Feriado f) {
        repository.deleteById(f.getId());
    }
}
