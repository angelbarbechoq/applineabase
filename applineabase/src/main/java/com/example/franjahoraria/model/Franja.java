package com.example.franjahoraria.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Franjas horarias de la tarifa de la empresa eléctrica. Días especiales = sábado, domingo y feriados
 * cargados a mano: ahí no hay A ni B. Las horas son las que fija la empresa; si cambian, se cambian acá.
 */
public enum Franja {
    A("L-V 08h00-18h00"),
    B("L-V 18h00-22h00"),
    C("L-V 22h00-08h00; S, D y F 22h00-18h00"),
    D("S, D y F 18h00-22h00");

    private final String horario;

    Franja(String horario) {
        this.horario = horario;
    }

    public String horario() {
        return horario;
    }

    /** "A (L-V 08h00-18h00)". */
    public String etiqueta() {
        return name() + " (" + horario + ")";
    }

    /** Franja de un instante; diaEspecial = sábado, domingo o feriado. */
    public static Franja de(LocalDateTime t, boolean diaEspecial) {
        int h = t.getHour();
        boolean noche = h >= 18 && h < 22;
        if (diaEspecial) return noche ? D : C;
        if (h >= 8 && h < 18) return A;
        return noche ? B : C;
    }

    public static boolean finDeSemana(LocalDate dia) {
        return dia.getDayOfWeek() == DayOfWeek.SATURDAY || dia.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    /** Próximo cambio posible de franja después de t: 08h00, 18h00, 22h00 o medianoche. */
    public static LocalDateTime siguienteCorte(LocalDateTime t) {
        LocalDate dia = t.toLocalDate();
        for (int h : new int[]{8, 18, 22}) {
            LocalDateTime corte = dia.atTime(h, 0);
            if (corte.isAfter(t)) return corte;
        }
        return dia.plusDays(1).atStartOfDay();
    }
}
