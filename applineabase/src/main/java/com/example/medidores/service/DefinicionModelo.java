package com.example.medidores.service;

import com.example.medidores.model.FuncionLectura;
import com.example.medidores.model.ModeloMedidor;
import com.example.medidores.model.OrdenPalabras;
import com.example.medidores.model.ParametroMedidor;
import com.example.medidores.model.RegistroModelo;
import com.example.medidores.model.TipoDato;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Copia inmutable de un modelo del catálogo, lista para leer: las direcciones ya están en la
 * numeración del cable (base 0). Se arma al inicio de cada ciclo y se comparte entre los hilos
 * de las pasarelas sin tocar JPA.
 */
public record DefinicionModelo(String nombre, FuncionLectura funcion, Map<ParametroMedidor, Registro> registros) {

    /** Un parámetro listo para pedir: dirección base 0, tipo, orden de palabras, escala y PF 4Q. */
    public record Registro(ParametroMedidor parametro, int registroManual, int direccion, TipoDato tipoDato,
                           OrdenPalabras ordenPalabras, double escala, boolean pf4Cuadrantes) {

        public int cantidad() {
            return tipoDato.getRegistros();
        }

        public int fin() {
            return direccion + cantidad();
        }
    }

    public static DefinicionModelo de(ModeloMedidor modelo) {
        Map<ParametroMedidor, Registro> mapa = new EnumMap<>(ParametroMedidor.class);
        int resta = modelo.isNumeracionManual() ? 1 : 0;
        for (RegistroModelo r : modelo.getRegistros()) {
            mapa.put(r.getParametro(), new Registro(r.getParametro(), r.getRegistro(), r.getRegistro() - resta,
                    r.getTipoDato(), r.getOrdenPalabras(), r.getEscala(), r.isPf4Cuadrantes()));
        }
        return new DefinicionModelo(modelo.getNombre(), modelo.getFuncionLectura(), Collections.unmodifiableMap(mapa));
    }

    /** Parámetros básicos que el modelo todavía no tiene cargados (vacío = puede leerse). */
    public List<ParametroMedidor> faltantesBasicos() {
        return Arrays.stream(ParametroMedidor.values())
                .filter(ParametroMedidor::isBasico)
                .filter(p -> !registros.containsKey(p))
                .toList();
    }

    public boolean completo() {
        return faltantesBasicos().isEmpty();
    }
}
