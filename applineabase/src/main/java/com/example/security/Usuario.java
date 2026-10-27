package com.example.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "usuarios")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    /**
     * Zona a la que tiene acceso este usuario (ej. "Extrusión", "Mezcla", "Mantenimiento").
     * Ignorado cuando el rol es ADMIN, ya que un ADMIN ve todas las zonas.
     */
    @Column
    private String zona;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Rol rol;

    @Column(nullable = false)
    private boolean habilitado = true;

    /**
     * Acceso a la Configuración y pestaña de Mezcladores (temperatura DTB48), independiente de
     * la zona: la zona "Mezcla" solo filtra qué líneas de energía ve el usuario, no implica
     * automáticamente que deba ver temperatura de mezcladores (puede haber operadores de zona
     * Mezcla que no deban verla). Ignorado cuando el rol es ADMIN, que ya ve todo.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean verMezcladores = false;

    /**
     * Acceso al registro de Mantenimiento Preventivo (mismo criterio que verMezcladores):
     * un usuario puntual, sin necesidad de ser ADMIN ni depender de su zona. Ignorado cuando el
     * rol es ADMIN, que ya ve todo. Solo habilita VER el historial -- registrar una tarea nueva
     * sigue siendo exclusivo de ADMIN.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean verMantenimiento = false;

    /**
     * Acceso a Grupo Electrogeno (Generadores y Analisis) y a sus alarmas en Alarmas Activas y en los
     * avisos del navegador (mismo criterio que verMezcladores). Ignorado cuando el rol es ADMIN.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean verGeneradores = false;

    /**
     * Acceso a Consumo por franja horaria (consumo y costo según la tarifa de la empresa eléctrica), mismo
     * criterio que verMezcladores. Ignorado cuando el rol es ADMIN. Cargar tarifas y feriados sigue siendo ADMIN.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean verFranjaHoraria = false;

    /**
     * Acceso a Transformadores (diagrama unifilar en vivo de la planta 1), mismo criterio que verMezcladores.
     * Ignorado cuando el rol es ADMIN.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean verTransformadores = false;

    public Usuario() {
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getZona() {
        return zona;
    }

    public void setZona(String zona) {
        this.zona = zona;
    }

    public Rol getRol() {
        return rol;
    }

    public void setRol(Rol rol) {
        this.rol = rol;
    }

    public boolean isHabilitado() {
        return habilitado;
    }

    public void setHabilitado(boolean habilitado) {
        this.habilitado = habilitado;
    }

    public boolean isVerMezcladores() {
        return verMezcladores;
    }

    public void setVerMezcladores(boolean verMezcladores) {
        this.verMezcladores = verMezcladores;
    }

    public boolean isVerMantenimiento() {
        return verMantenimiento;
    }

    public void setVerMantenimiento(boolean verMantenimiento) {
        this.verMantenimiento = verMantenimiento;
    }

    public boolean isVerGeneradores() {
        return verGeneradores;
    }

    public void setVerGeneradores(boolean verGeneradores) {
        this.verGeneradores = verGeneradores;
    }

    public boolean isVerFranjaHoraria() {
        return verFranjaHoraria;
    }

    public void setVerFranjaHoraria(boolean verFranjaHoraria) {
        this.verFranjaHoraria = verFranjaHoraria;
    }

    public boolean isVerTransformadores() {
        return verTransformadores;
    }

    public void setVerTransformadores(boolean verTransformadores) {
        this.verTransformadores = verTransformadores;
    }

    public enum Rol {
        ADMIN, USUARIO
    }
}
