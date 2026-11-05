package com.example.generador.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Una fila del formato MF-13 "Control de generador": chequeos preliminares e inspección del generador en un momento
 * (arranque, control de funcionamiento, parada o revisión sin encender). Reemplaza a la planilla de Excel de los
 * operadores. Presión de aceite, temperatura, carga, energía y horas se toman del controlador al guardar
 * ({@link #valoresDelControlador}); las filas importadas de la planilla los traen tal como se anotaron.
 */
@Entity
@Table(name = "registro_mf13", indexes = @Index(name = "idx_registro_mf13_gen_fecha", columnList = "generador, fecha_hora"))
public class RegistroMf13 {

    /** Valores del momento del registro (lista cerrada). */
    public static final String ARRANQUE = "Arranque";
    public static final String CONTROL = "Control de funcionamiento";
    public static final String PARADA = "Parada";
    public static final String REVISION = "Revisión sin encender";
    public static final String[] MOMENTOS = {ARRANQUE, CONTROL, PARADA, REVISION};
    public static final String[] MODOS = {"Sincronismo", "Isla", "Automático (AMF)", "Manual"};
    public static final String[] CHEQUEO = {"√", "X"};

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String generador;

    @Column(name = "fecha_hora", nullable = false)
    private LocalDateTime fechaHora;

    @Column(length = 30)
    private String momento;

    @Column(length = 30)
    private String modo;

    @Column(length = 80)
    private String responsable;

    @Column(name = "niv_lubricante", length = 10)
    private String nivLubricante;
    @Column(name = "niv_agua", length = 10)
    private String nivAgua;
    @Column(length = 10)
    private String precalentador;
    @Column(length = 10)
    private String filtros;
    @Column(length = 10)
    private String correas;

    private Double bateria;
    /** Nivel del tanque de combustible en cm. */
    @Column(name = "nivel_cm")
    private Double nivelCm;
    /** Contador de galones consumidos del motor (solo los que lo tienen, ej. Caterpillar). */
    @Column(name = "contador_diesel")
    private Double contadorDiesel;
    /** Galones de diésel cargados al tanque desde el registro anterior. */
    @Column(name = "diesel_cargado")
    private Double dieselCargado;
    /** La cantidad cargada no estaba anotada en la planilla: se estimó con el nivel y el rendimiento de la marcha. */
    @Column(name = "diesel_cargado_estimado", nullable = false, columnDefinition = "boolean default false")
    private boolean dieselCargadoEstimado = false;

    @Column(name = "pres_aceite")
    private Double presAceite;
    private Double temperatura;
    @Column(name = "carga_kw")
    private Double cargaKw;
    @Column(name = "prod_kwh")
    private Double prodKwh;
    private Double horas;
    /** Presión, temperatura, carga, energía y horas leídas del controlador al guardar (false = anotadas a mano o importadas). */
    @Column(name = "valores_del_controlador", nullable = false, columnDefinition = "boolean default false")
    private boolean valoresDelControlador = false;
    /** Valores que el controlador no da (ej. el Caterpillar no publica presión de aceite ni temperatura) y se anotaron a mano. */
    @Column(name = "campos_a_mano", length = 120)
    private String camposAMano;

    @Column(name = "obs_carga_diesel", nullable = false, columnDefinition = "boolean default false")
    private boolean obsCargaDiesel = false;
    @Column(name = "obs_aceite", nullable = false, columnDefinition = "boolean default false")
    private boolean obsAceite = false;
    @Column(name = "obs_refrigerante", nullable = false, columnDefinition = "boolean default false")
    private boolean obsRefrigerante = false;
    @Column(name = "obs_automatico", nullable = false, columnDefinition = "boolean default false")
    private boolean obsAutomatico = false;
    @Column(name = "obs_falla", nullable = false, columnDefinition = "boolean default false")
    private boolean obsFalla = false;

    @Column(length = 500)
    private String observacion;

    /** De dónde viene: registro en la app o la planilla importada (archivo y fila). */
    @Column(length = 160)
    private String origen;

    @Column(length = 60)
    private String usuario;

    @Column(name = "fecha_registro", nullable = false)
    private LocalDateTime fechaRegistro;

    public RegistroMf13() {
    }

    public Long getId() { return id; }
    public String getGenerador() { return generador; }
    public void setGenerador(String generador) { this.generador = generador; }
    public LocalDateTime getFechaHora() { return fechaHora; }
    public void setFechaHora(LocalDateTime fechaHora) { this.fechaHora = fechaHora; }
    public String getMomento() { return momento; }
    public void setMomento(String momento) { this.momento = momento; }
    public String getModo() { return modo; }
    public void setModo(String modo) { this.modo = modo; }
    public String getResponsable() { return responsable; }
    public void setResponsable(String responsable) { this.responsable = responsable; }
    public String getNivLubricante() { return nivLubricante; }
    public void setNivLubricante(String v) { this.nivLubricante = v; }
    public String getNivAgua() { return nivAgua; }
    public void setNivAgua(String v) { this.nivAgua = v; }
    public String getPrecalentador() { return precalentador; }
    public void setPrecalentador(String v) { this.precalentador = v; }
    public String getFiltros() { return filtros; }
    public void setFiltros(String v) { this.filtros = v; }
    public String getCorreas() { return correas; }
    public void setCorreas(String v) { this.correas = v; }
    public Double getBateria() { return bateria; }
    public void setBateria(Double bateria) { this.bateria = bateria; }
    public Double getNivelCm() { return nivelCm; }
    public void setNivelCm(Double nivelCm) { this.nivelCm = nivelCm; }
    public Double getContadorDiesel() { return contadorDiesel; }
    public void setContadorDiesel(Double contadorDiesel) { this.contadorDiesel = contadorDiesel; }
    public Double getDieselCargado() { return dieselCargado; }
    public void setDieselCargado(Double dieselCargado) { this.dieselCargado = dieselCargado; }
    public boolean isDieselCargadoEstimado() { return dieselCargadoEstimado; }
    public void setDieselCargadoEstimado(boolean v) { this.dieselCargadoEstimado = v; }
    public Double getPresAceite() { return presAceite; }
    public void setPresAceite(Double presAceite) { this.presAceite = presAceite; }
    public Double getTemperatura() { return temperatura; }
    public void setTemperatura(Double temperatura) { this.temperatura = temperatura; }
    public Double getCargaKw() { return cargaKw; }
    public void setCargaKw(Double cargaKw) { this.cargaKw = cargaKw; }
    public Double getProdKwh() { return prodKwh; }
    public void setProdKwh(Double prodKwh) { this.prodKwh = prodKwh; }
    public Double getHoras() { return horas; }
    public void setHoras(Double horas) { this.horas = horas; }
    public boolean isValoresDelControlador() { return valoresDelControlador; }
    public void setValoresDelControlador(boolean v) { this.valoresDelControlador = v; }
    public String getCamposAMano() { return camposAMano; }
    public void setCamposAMano(String camposAMano) { this.camposAMano = camposAMano; }
    /** true si el valor de ese campo salió del controlador (no anotado a mano). */
    public boolean delControlador(String campo) {
        return valoresDelControlador && (camposAMano == null || !camposAMano.contains(campo));
    }
    public boolean isObsCargaDiesel() { return obsCargaDiesel; }
    public void setObsCargaDiesel(boolean v) { this.obsCargaDiesel = v; }
    public boolean isObsAceite() { return obsAceite; }
    public void setObsAceite(boolean v) { this.obsAceite = v; }
    public boolean isObsRefrigerante() { return obsRefrigerante; }
    public void setObsRefrigerante(boolean v) { this.obsRefrigerante = v; }
    public boolean isObsAutomatico() { return obsAutomatico; }
    public void setObsAutomatico(boolean v) { this.obsAutomatico = v; }
    public boolean isObsFalla() { return obsFalla; }
    public void setObsFalla(boolean v) { this.obsFalla = v; }
    public String getObservacion() { return observacion; }
    public void setObservacion(String observacion) { this.observacion = observacion; }
    public String getOrigen() { return origen; }
    public void setOrigen(String origen) { this.origen = origen; }
    public String getUsuario() { return usuario; }
    public void setUsuario(String usuario) { this.usuario = usuario; }
    public LocalDateTime getFechaRegistro() { return fechaRegistro; }
    public void setFechaRegistro(LocalDateTime fechaRegistro) { this.fechaRegistro = fechaRegistro; }
}
