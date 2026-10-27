package com.example.generador.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Texto de una alarma de la lista de alarmas de un controlador ComAp. El controlador antepone "!" si
 * la alarma está activa ahora y "*" si todavía no se confirmó (sin reset). Visto el 2026-10-12 en el
 * GenPower: "*Emergency Stop" con el pulsador ya suelto y sin reset, y "!*Emergency Stop" + "!*E-STOP"
 * al volver a pulsarlo. Una alarma se identifica por su nombre sin esos signos.
 */
public final class AlarmaComap {

    private static final Pattern SIGNOS = Pattern.compile("^[!*\\s]*");

    private AlarmaComap() {
    }

    /** Nombre sin los signos de estado ("!*E-STOP" = "E-STOP"). */
    public static String nombre(String texto) {
        return texto == null ? "" : texto.replaceFirst("^[!*\\s]+", "").trim();
    }

    private static String signos(String texto) {
        if (texto == null) return "";
        Matcher m = SIGNOS.matcher(texto);
        return m.find() ? m.group() : "";
    }

    /** Activa ahora ("!"). */
    public static boolean activa(String texto) {
        return signos(texto).contains("!");
    }

    /** Todavía sin confirmar / sin reset ("*"). */
    public static boolean sinConfirmar(String texto) {
        return signos(texto).contains("*");
    }

    /** Estado en palabras: "activa ahora, sin reset", "ya no esta activa, falta el reset en el controlador"... */
    public static String estado(String texto) {
        boolean activa = activa(texto), sinReset = sinConfirmar(texto);
        if (activa) return sinReset ? "activa ahora, sin reset" : "activa ahora, confirmada";
        return sinReset ? "ya no está activa, falta el reset en el controlador" : "en la lista del controlador";
    }

    /**
     * Impide arrancar el generador: parada de emergencia o parada por protección (prefijo "Sd" de ComAp).
     * Se avisa como urgente.
     */
    public static boolean impideArrancar(String texto) {
        String t = nombre(texto).toLowerCase(Locale.ROOT);
        return t.contains("emerg") || t.contains("e-stop") || t.startsWith("sd ");
    }

    /**
     * Qué significa, por el texto: la parada de emergencia y el tipo que indica el prefijo ComAp (Sd = parada
     * por protección, Wrn = aviso, Stp = parada lenta, BOC = abre el interruptor y enfría). Lo demás: revisar
     * en el controlador.
     */
    public static String explicacion(String texto) {
        String t = nombre(texto).toLowerCase(Locale.ROOT);
        if (t.contains("emergency stop") || t.contains("emerg") || t.contains("e-stop")) {
            return "Parada de emergencia: el generador no arranca, ni siquiera por un corte de red, hasta soltar el pulsador "
                    + "de emergencia y hacer el reset de fallas en el controlador.";
        }
        if (t.startsWith("sd ")) {
            return "Parada por protección: el generador no arranca hasta resolver la causa y hacer el reset en el controlador.";
        }
        if (t.startsWith("stp ")) return "Parada lenta por protección, con enfriamiento: revisar la causa y hacer el reset en el controlador.";
        if (t.startsWith("boc ")) return "Protección que abre el interruptor del generador y lo enfría: revisar la causa en el controlador.";
        if (t.startsWith("wrn ")) return "Aviso: el generador puede seguir trabajando, pero hay que revisar la causa.";
        return "Revisar en la pantalla del controlador.";
    }
}
