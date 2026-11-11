package com.example.lotes.service;

import com.example.lotes.model.ProductoLinea;
import com.example.lotes.repository.ProductoLineaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Catálogo de productos por línea para marcar lotes. Se carga de la hoja PRODUCCION de los Excel de medición de
 * {@link #CARPETA} ("09MedicionSeptiembre26.xlsx": mes y año en el nombre). De cada año se toma el del mes más alto;
 * primero el año más reciente, así el nombre que queda es el último usado. Solo agrega lo que falta (línea + producto
 * sin importar mayúsculas ni espacios); nunca borra. Se carga solo al arrancar si el catálogo está vacío, y con el
 * botón "Actualizar catálogo".
 */
@Service
public class CatalogoProductosService {

    private static final Logger log = LoggerFactory.getLogger(CatalogoProductosService.class);

    public static final String CARPETA = "C:/LineaBaseX/datos/lineabase";
    static final Pattern ARCHIVO = Pattern.compile("^(\\d{2})Medicion[A-Za-z]+(\\d{2})\\.xlsx$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINEA = Pattern.compile("^LINEA\\s*0*(\\d+)\\s*[AB]?$", Pattern.CASE_INSENSITIVE);
    private static final String HOJA = "PRODUCCION";

    private final ProductoLineaRepository repo;

    public CatalogoProductosService(ProductoLineaRepository repo) {
        this.repo = repo;
    }

    public record Resultado(int nuevos, List<String> archivos, String error) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cargarSiVacio() {
        if (repo.count() > 0) return;
        Thread.ofVirtual().start(() -> {
            Resultado r = actualizar();
            log.info("Catálogo de productos inicial: {} productos de {} {}", r.nuevos(), r.archivos(),
                    r.error() == null ? "" : r.error());
        });
    }

    /** Busca los Excel más recientes de cada año y agrega los productos que faltan. */
    public synchronized Resultado actualizar() {
        List<File> archivos = archivosMasRecientes();
        if (archivos.isEmpty()) {
            return new Resultado(0, List.of(), "No se encontraron archivos de medición en " + CARPETA);
        }
        Set<String> existentes = new HashSet<>();
        for (ProductoLinea p : repo.findAll()) existentes.add(p.getLinea() + "|" + p.getClave());
        int nuevos = 0;
        List<String> nombres = new ArrayList<>();
        StringBuilder errores = new StringBuilder();
        for (File f : archivos) {
            nombres.add(f.getName());
            try {
                List<ProductoLinea> lista = new ArrayList<>();
                for (String[] fila : leerProduccion(f)) {
                    String linea = linea(fila[0]);
                    String nombre = limpiar(fila[1]);
                    if (linea == null || nombre.isEmpty() || nombre.equalsIgnoreCase("s/pro")) continue;
                    String clave = nombre.toUpperCase(Locale.ROOT);
                    if (existentes.add(linea + "|" + clave)) {
                        lista.add(new ProductoLinea(linea, nombre, clave, limpiar(fila[2]), f.getName()));
                    }
                }
                repo.saveAll(lista);
                nuevos += lista.size();
            } catch (Exception e) {
                log.warn("No se pudo leer {}: {}", f, e.toString());
                errores.append("No se pudo leer ").append(f.getName()).append(": ").append(e.getMessage()).append(". ");
            }
        }
        return new Resultado(nuevos, nombres, errores.isEmpty() ? null : errores.toString().trim());
    }

    /** Productos de una línea, en orden alfabético. */
    public List<ProductoLinea> productosDe(String linea) {
        List<ProductoLinea> l = new ArrayList<>(repo.findByLinea(linea));
        l.sort(Comparator.comparing(ProductoLinea::getNombre, String.CASE_INSENSITIVE_ORDER));
        return l;
    }

    /** Todos los productos, sin repetir el mismo nombre en distintas líneas, en orden alfabético. */
    public List<ProductoLinea> todos() {
        Map<String, ProductoLinea> unicos = new TreeMap<>();
        for (ProductoLinea p : repo.findAll()) unicos.putIfAbsent(p.getClave(), p);
        List<ProductoLinea> l = new ArrayList<>(unicos.values());
        l.sort(Comparator.comparing(ProductoLinea::getNombre, String.CASE_INSENSITIVE_ORDER));
        return l;
    }

    public long cantidad() {
        return repo.count();
    }

    // ---------------- Archivos ----------------

    /** De cada año, el archivo con el mes más alto; el año más reciente primero. Los borradores (" - ...") no entran. */
    static List<File> archivosMasRecientes() {
        File[] fs = new File(CARPETA).listFiles();
        if (fs == null) return List.of();
        Map<Integer, File> porAnio = new TreeMap<>(Comparator.reverseOrder());
        Map<Integer, Integer> mesDe = new HashMap<>();
        for (File f : fs) {
            Matcher m = ARCHIVO.matcher(f.getName());
            if (!f.isFile() || !m.matches()) continue;
            int mes = Integer.parseInt(m.group(1));
            int anio = Integer.parseInt(m.group(2));
            if (mes > mesDe.getOrDefault(anio, 0)) {
                mesDe.put(anio, mes);
                porAnio.put(anio, f);
            }
        }
        return new ArrayList<>(porAnio.values());
    }

    /** "LINEA09", "LINEA 08 A" -> "Linea09". */
    static String linea(String s) {
        if (s == null) return null;
        Matcher m = LINEA.matcher(s.trim());
        if (!m.matches()) return null;
        return String.format("Linea%02d", Integer.parseInt(m.group(1)));
    }

    private static String limpiar(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    // ---------------- Lectura de la hoja (StAX: los libros pesan varios MB, solo se lee una hoja) ----------------

    /** Filas de la hoja PRODUCCION: {LINEA, ITEM, CODIGO PRODUCTO}. */
    static List<String[]> leerProduccion(File archivo) throws Exception {
        try (ZipFile zip = new ZipFile(archivo)) {
            String ruta = rutaHoja(zip, HOJA);
            if (ruta == null) throw new IllegalStateException("no tiene la hoja " + HOJA);
            List<String> textos = textosCompartidos(zip);
            List<String[]> salida = new ArrayList<>();
            XMLInputFactory xf = fabrica();
            try (InputStream in = zip.getInputStream(zip.getEntry(ruta))) {
                XMLStreamReader r = xf.createXMLStreamReader(in);
                Map<String, String> fila = new LinkedHashMap<>();
                String col = null, tipo = null;
                StringBuilder valor = null;
                String cLinea = null, cItem = null, cCodigo = null;
                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        switch (r.getLocalName()) {
                            case "row" -> fila.clear();
                            case "c" -> {
                                col = r.getAttributeValue(null, "r").replaceAll("\\d", "");
                                tipo = r.getAttributeValue(null, "t");
                            }
                            case "v", "t" -> valor = new StringBuilder();
                            default -> { }
                        }
                    } else if (ev == XMLStreamConstants.CHARACTERS && valor != null) {
                        valor.append(r.getText());
                    } else if (ev == XMLStreamConstants.END_ELEMENT) {
                        switch (r.getLocalName()) {
                            case "v", "t" -> {
                                if (valor != null && col != null) {
                                    String v = valor.toString();
                                    if ("s".equals(tipo)) {
                                        int k = Integer.parseInt(v.trim());
                                        v = k < textos.size() ? textos.get(k) : null;
                                    }
                                    if (v != null) fila.merge(col, v, String::concat);
                                }
                                valor = null;
                            }
                            case "row" -> {
                                if (cLinea == null) {
                                    for (Map.Entry<String, String> e : fila.entrySet()) {
                                        String t = e.getValue().trim().toUpperCase(Locale.ROOT);
                                        if (t.equals("LINEA")) cLinea = e.getKey();
                                        else if (t.equals("ITEM")) cItem = e.getKey();
                                        else if (t.equals("CODIGO PRODUCTO")) cCodigo = e.getKey();
                                    }
                                    if (cLinea == null || cItem == null) cLinea = null;
                                } else {
                                    salida.add(new String[]{fila.get(cLinea), fila.get(cItem), cCodigo == null ? null : fila.get(cCodigo)});
                                }
                            }
                            default -> { }
                        }
                    }
                }
                r.close();
            }
            if (salida.isEmpty()) throw new IllegalStateException("la hoja " + HOJA + " no tiene las columnas LINEA e ITEM");
            return salida;
        }
    }

    /** Ruta dentro del ZIP de la hoja con ese nombre (sin importar mayúsculas); null si no está. */
    static String rutaHoja(ZipFile zip, String hoja) throws Exception {
        String rid = null;
        XMLInputFactory xf = fabrica();
        try (InputStream in = zip.getInputStream(zip.getEntry("xl/workbook.xml"))) {
            XMLStreamReader r = xf.createXMLStreamReader(in);
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("sheet")
                        && hoja.equalsIgnoreCase(String.valueOf(r.getAttributeValue(null, "name")).trim())) {
                    for (int i = 0; i < r.getAttributeCount(); i++) {
                        if (r.getAttributeLocalName(i).equals("id")) rid = r.getAttributeValue(i);
                    }
                }
            }
        }
        if (rid == null) return null;
        ZipEntry rels = zip.getEntry("xl/_rels/workbook.xml.rels");
        try (InputStream in = zip.getInputStream(rels)) {
            XMLStreamReader r = xf.createXMLStreamReader(in);
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("Relationship")
                        && rid.equals(r.getAttributeValue(null, "Id"))) {
                    String t = r.getAttributeValue(null, "Target");
                    return t.startsWith("/") ? t.substring(1) : "xl/" + t;
                }
            }
        }
        return null;
    }

    static List<String> textosCompartidos(ZipFile zip) throws Exception {
        List<String> textos = new ArrayList<>();
        ZipEntry e = zip.getEntry("xl/sharedStrings.xml");
        if (e == null) return textos;
        try (InputStream in = zip.getInputStream(e)) {
            XMLStreamReader r = fabrica().createXMLStreamReader(in);
            StringBuilder si = null;
            boolean enT = false, enRph = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "si" -> si = new StringBuilder();
                        case "t" -> enT = true;
                        case "rPh" -> enRph = true;
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS && enT && !enRph && si != null) {
                    si.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "si" -> { textos.add(si == null ? "" : si.toString()); si = null; }
                        case "t" -> enT = false;
                        case "rPh" -> enRph = false;
                        default -> { }
                    }
                }
            }
        }
        return textos;
    }

    static XMLInputFactory fabrica() {
        XMLInputFactory xf = XMLInputFactory.newInstance();
        xf.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xf.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return xf;
    }
}
