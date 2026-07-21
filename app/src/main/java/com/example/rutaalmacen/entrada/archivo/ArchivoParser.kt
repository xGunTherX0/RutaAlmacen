package com.example.rutaalmacen.entrada.archivo

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.Xml
import com.example.rutaalmacen.entrada.ocr.BoletaParser
import com.example.rutaalmacen.entrada.ocr.CacheCatalogoLocal
import com.example.rutaalmacen.entrada.ocr.ProductoEscaneado
import com.example.rutaalmacen.entrada.ocr.ProductoFiltroDuplicados
import com.example.rutaalmacen.entrada.ocr.ProductoLocal
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Motor de lectura multiformato para importación masiva de productos.
 *
 * Soporta:
 *   - .csv  (separadores: , ;  o \t)
 *   - .txt  (mismas reglas que .csv, asume tabulación o coma)
 *   - .xlsx (Office Open XML: ZIP con xl/sharedStrings.xml + xl/worksheets/sheet1.xml)
 *   - .ods  (OpenDocument: ZIP con content.xml)
 *
 * Cero dependencias externas: usa ZipInputStream + XmlPullParser nativos del SDK.
 * Toda la lectura ocurre en Dispatchers.IO.
 */
object ArchivoParser {

    private const val TAG = "ArchivoParser"

    /**
     * Resultado del procesamiento de un archivo de importación masiva.
     *
     * Puede representar un procesamiento exitoso con la lista de productos
     * y el formato detectado, o un error con un mensaje descriptivo.
     */
    sealed class Resultado {
        /**
         * El archivo se procesó correctamente.
         *
         * @property productos Lista de productos extraídos y normalizados.
         * @property formato Extensión del archivo procesado (ej. "CSV", "XLSX").
         */
        data class Exito(val productos: List<ProductoEscaneado>, val formato: String) : Resultado()

        /**
         * El procesamiento del archivo falló.
         *
         * @property mensaje Descripción del error ocurrido.
         */
        data class Error(val mensaje: String) : Resultado()
    }

    private val sinonimosNombre = setOf(
        "nombre", "producto", "descripcion", "descripción", "item", "ítem",
        "articulo", "artículo", "name", "product", "description",
        "detalle", "detalle del producto", "descripcion del producto",
        "descripción del producto", "nombre del producto",
    )

    private val sinonimosPrecio = setOf(
        "precio", "valor", "costo", "monto", "price", "amount", "value", "cost",
        "precio de costo", "precio unitario", "precio venta",
    )

    /**
     * Parsea un archivo de importación masiva y extrae los productos contenidos.
     *
     * Detecta automáticamente el formato del archivo (CSV, TXT, XLSX u ODS)
     * a partir de su extensión o, en su defecto, del tipo MIME reportado por
     * el [android.content.ContentResolver]. Todo el procesamiento se realiza
     * en [Dispatchers.IO].
     *
     * @param context Contexto de la aplicación, necesario para acceder al contenido del archivo.
     * @param uri URI de contenido que apunta al archivo a procesar.
     * @return [Resultado.Exito] con los productos extraídos y el formato detectado,
     *         o [Resultado.Error] si el formato no es soportado o el archivo no contiene
     *         filas válidas con nombre y precio.
     */
    suspend fun parsear(context: Context, uri: Uri): Resultado = withContext(Dispatchers.IO) {
        val nombreArchivo = obtenerNombreArchivo(context, uri).lowercase(Locale.ROOT)
        val extension = nombreArchivo.substringAfterLast('.', "")
        Log.i(TAG, "parsear() archivo='$nombreArchivo' extension='$extension'")
        try {
            val productos = when (extension) {
                "csv" -> parsearTextoSeparado(context, uri)
                "txt" -> parsearTextoSeparado(context, uri)
                "xlsx" -> parsearXlsx(context, uri)
                "ods" -> parsearOds(context, uri)
                else -> {
                    // Fallback por mimeType o intento heurístico
                    val mime = context.contentResolver.getType(uri).orEmpty()
                    Log.w(TAG, "Extensión desconocida, mime=$mime")
                    when {
                        mime.contains("spreadsheetml") -> parsearXlsx(context, uri)
                        mime.contains("opendocument") -> parsearOds(context, uri)
                        mime.startsWith("text/") -> parsearTextoSeparado(context, uri)
                        else -> return@withContext Resultado.Error(
                            "Formato no soportado. Usa .xlsx, .ods, .csv o .txt.",
                        )
                    }
                }
            }
            val limpios = limpiarYNormalizar(productos)
            if (limpios.isEmpty()) {
                Resultado.Error("No se encontraron filas con nombre y precio válidos en el archivo.")
            } else {
                Resultado.Exito(limpios, extension.uppercase(Locale.ROOT))
            }
        } catch (excepcion: Exception) {
            Log.e(TAG, "Falló parsear archivo", excepcion)
            Resultado.Error("No se pudo leer el archivo: ${excepcion.message ?: excepcion.javaClass.simpleName}")
        }
    }

    // -------------------- CSV / TXT --------------------

    private fun parsearTextoSeparado(context: Context, uri: Uri): List<FilaCruda> {
        val resultado = mutableListOf<FilaCruda>()
        context.contentResolver.openInputStream(uri).use { stream ->
            if (stream == null) return emptyList()
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val lineas = reader.readLines()
                if (lineas.isEmpty()) return emptyList()

                val separador = detectarSeparador(lineas.first())
                val filas = lineas.map { dividir(it, separador) }
                val (indiceNombre, indicePrecio, saltarPrimera) = detectarColumnas(filas.first())
                val desde = if (saltarPrimera) 1 else 0
                for (i in desde until filas.size) {
                    val fila = filas[i]
                    if (fila.all { it.isBlank() }) continue
                    val nombre = fila.getOrNull(indiceNombre)?.trim().orEmpty()
                    val precioTexto = fila.getOrNull(indicePrecio)?.trim().orEmpty()
                    if (nombre.isBlank()) continue
                    val precio = parsearPrecio(precioTexto)
                    if (esFilaDeProducto(nombre, precio)) {
                        resultado.add(FilaCruda(nombre, precio))
                    }
                }
            }
        }
        return resultado
    }

    private fun detectarSeparador(primeraLinea: String): Char {
        val cuentaTabs = primeraLinea.count { it == '\t' }
        val cuentaPyc = primeraLinea.count { it == ';' }
        val cuentaComas = primeraLinea.count { it == ',' }
        return when {
            cuentaTabs > 0 && cuentaTabs >= cuentaComas && cuentaTabs >= cuentaPyc -> '\t'
            cuentaPyc > 0 && cuentaPyc >= cuentaComas -> ';'
            cuentaComas > 0 -> ','
            else -> '\t'
        }
    }

    private fun dividir(linea: String, separador: Char): List<String> {
        // Manejo simple de comillas: "valor con, coma"
        val tokens = mutableListOf<String>()
        val actual = StringBuilder()
        var dentroComillas = false
        for (c in linea) {
            when {
                c == '"' -> dentroComillas = !dentroComillas
                c == separador && !dentroComillas -> {
                    tokens.add(actual.toString())
                    actual.clear()
                }
                else -> actual.append(c)
            }
        }
        tokens.add(actual.toString())
        return tokens.map { it.trim() }
    }

    // -------------------- XLSX (OOXML) --------------------

    private fun parsearXlsx(context: Context, uri: Uri): List<FilaCruda> {
        var sharedStrings: List<String> = emptyList()
        val filas = mutableListOf<List<String>>()

        context.contentResolver.openInputStream(uri).use { stream ->
            if (stream == null) return emptyList()
            ZipInputStream(stream).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "xl/sharedStrings.xml") {
                        sharedStrings = leerSharedStrings(zip)
                        Log.d(TAG, "sharedStrings leídos: ${sharedStrings.size}")
                        break
                    }
                    entry = zip.nextEntry
                }
            }
        }

        context.contentResolver.openInputStream(uri).use { stream ->
            if (stream == null) return emptyList()
            ZipInputStream(stream).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val nombre = entry.name
                    if (nombre == "xl/worksheets/sheet1.xml" || nombre.startsWith("xl/worksheets/sheet1.")) {
                        filas.addAll(leerHojaXlsx(zip, sharedStrings))
                        Log.d(TAG, "Filas leídas de sheet1: ${filas.size}")
                        break
                    }
                    entry = zip.nextEntry
                }
            }
        }

        return convertirFilasACrudas(filas)
    }

    private fun leerSharedStrings(input: InputStream): List<String> {
        val resultado = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        var dentroDeSi = false
        var dentroDeT = false
        val acumulado = StringBuilder()
        var evento = parser.eventType
        while (evento != XmlPullParser.END_DOCUMENT) {
            when (evento) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> {
                        dentroDeSi = true
                        acumulado.setLength(0)
                    }
                    "t" -> if (dentroDeSi) dentroDeT = true
                }
                XmlPullParser.TEXT -> if (dentroDeT) acumulado.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> dentroDeT = false
                    "si" -> {
                        resultado.add(acumulado.toString())
                        dentroDeSi = false
                    }
                }
            }
            evento = parser.next()
        }
        return resultado
    }

    private fun leerHojaXlsx(input: InputStream, sharedStrings: List<String>): List<List<String>> {
        val filas = mutableListOf<List<String>>()
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")

        val filaActual = mutableListOf<Pair<Int, String>>()
        var tipoCelda: String? = null
        var refCelda: String? = null
        var valorAcumulado = StringBuilder()
        var dentroDeV = false
        var dentroDeT = false
        var indiceFila = 0

        var evento = parser.eventType
        while (evento != XmlPullParser.END_DOCUMENT) {
            when (evento) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        filaActual.clear()
                        indiceFila++
                    }
                    "c" -> {
                        tipoCelda = parser.getAttributeValue(null, "t")
                        refCelda = parser.getAttributeValue(null, "r")
                        valorAcumulado.setLength(0)
                    }
                    "v" -> dentroDeV = true
                    "t" -> dentroDeT = (tipoCelda == "inlineStr" || tipoCelda == "str")
                }
                XmlPullParser.TEXT -> if (dentroDeV || dentroDeT) valorAcumulado.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> dentroDeV = false
                    "t" -> dentroDeT = false
                    "c" -> {
                        val texto = valorAcumulado.toString()
                        val valor = when (tipoCelda) {
                            "s" -> sharedStrings.getOrNull(texto.toIntOrNull() ?: -1).orEmpty()
                            "b" -> if (texto == "1") "true" else "false"
                            "e" -> ""
                            else -> texto
                        }
                        val columna = columnaDesdeRef(refCelda)
                        filaActual.add(columna to valor)
                        Log.d(TAG, "Celda [$indiceFila,$columna] tipo=$tipoCelda valor='$valor'")
                    }
                    "row" -> {
                        if (filaActual.isNotEmpty()) {
                            val maxCol = filaActual.maxOf { it.first }
                            val arregladaPorColumna = MutableList(maxCol + 1) { "" }
                            for ((col, valor) in filaActual) arregladaPorColumna[col] = valor
                            filas.add(arregladaPorColumna)
                        } else {
                            filas.add(emptyList())
                        }
                        filaActual.clear()
                    }
                }
            }
            evento = parser.next()
        }
        Log.d(TAG, "Total filas procesadas: ${filas.size}")
        return filas
    }

    /** Convierte "A1" → 0, "B1" → 1, "AA1" → 26, etc. */
    private fun columnaDesdeRef(ref: String?): Int {
        if (ref.isNullOrBlank()) return 0
        var indice = 0
        for (c in ref) {
            if (!c.isLetter()) break
            indice = indice * 26 + (c.uppercaseChar() - 'A' + 1)
        }
        return (indice - 1).coerceAtLeast(0)
    }

    // -------------------- ODS --------------------

    private fun parsearOds(context: Context, uri: Uri): List<FilaCruda> {
        val filas = mutableListOf<List<String>>()
        context.contentResolver.openInputStream(uri).use { stream ->
            if (stream == null) return emptyList()
            ZipInputStream(stream).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "content.xml") {
                        filas.addAll(leerContenidoOds(zip))
                        break
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return convertirFilasACrudas(filas)
    }

    private fun leerContenidoOds(input: InputStream): List<List<String>> {
        val filas = mutableListOf<List<String>>()
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")

        var dentroDeTablaPrimera = false
        var nivelTabla = 0

        val filaActual = mutableListOf<String>()
        var dentroDeFila = false
        var dentroDeCelda = false
        var valorCelda = StringBuilder()
        var repeticionCelda = 1
        var officeValue: String? = null

        var evento = parser.eventType
        while (evento != XmlPullParser.END_DOCUMENT) {
            when (evento) {
                XmlPullParser.START_TAG -> {
                    val nombre = parser.name
                    if (nombre == "table" && parser.namespace?.contains("table") == true) {
                        nivelTabla++
                        if (nivelTabla == 1) dentroDeTablaPrimera = true
                    } else if (dentroDeTablaPrimera) {
                        when (nombre) {
                            "table-row" -> {
                                filaActual.clear()
                                dentroDeFila = true
                            }
                            "table-cell" -> {
                                dentroDeCelda = true
                                valorCelda.setLength(0)
                                officeValue = parser.getAttributeValue(
                                    "urn:oasis:names:tc:opendocument:xmlns:office:1.0",
                                    "value",
                                )
                                val rep = parser.getAttributeValue(
                                    "urn:oasis:names:tc:opendocument:xmlns:table:1.0",
                                    "number-columns-repeated",
                                )
                                repeticionCelda = rep?.toIntOrNull() ?: 1
                                if (repeticionCelda > 200) repeticionCelda = 1
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> if (dentroDeCelda) valorCelda.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val nombre = parser.name
                    if (nombre == "table" && parser.namespace?.contains("table") == true) {
                        if (nivelTabla == 1) dentroDeTablaPrimera = false
                        nivelTabla--
                    } else if (dentroDeTablaPrimera) {
                        when (nombre) {
                            "table-cell" -> {
                                val texto = officeValue ?: valorCelda.toString()
                                repeat(repeticionCelda) { filaActual.add(texto.trim()) }
                                dentroDeCelda = false
                                repeticionCelda = 1
                                officeValue = null
                            }
                            "table-row" -> {
                                filas.add(filaActual.toList())
                                dentroDeFila = false
                            }
                        }
                    }
                }
            }
            evento = parser.next()
        }
        return filas
    }

    // -------------------- Detección de columnas y limpieza --------------------

    /**
     * Decide qué columna es "nombre" y cuál es "precio" mirando la primera fila.
     * Si hay encabezados de texto, los usa y salta la primera fila.
     * Si no, asume columna 0 = nombre, columna 1 = precio.
     */
    private fun detectarColumnas(primeraFila: List<String>): Triple<Int, Int, Boolean> {
        var indiceNombre = -1
        var indicePrecio = -1
        primeraFila.forEachIndexed { indice, valor ->
            val normalizado = normalizarHeader(valor)
            if (indiceNombre == -1 && sinonimosNombre.any { normalizado.contains(it) }) {
                indiceNombre = indice
            }
            if (indicePrecio == -1 && sinonimosPrecio.any { normalizado.contains(it) }) {
                indicePrecio = indice
            }
        }
        val tieneHeader = indiceNombre >= 0 || indicePrecio >= 0
        return Triple(
            if (indiceNombre >= 0) indiceNombre else 0,
            if (indicePrecio >= 0) indicePrecio else 1,
            tieneHeader,
        )
    }

    private fun normalizarHeader(texto: String): String {
        return texto.lowercase(Locale.ROOT)
            .replace("á", "a").replace("é", "e").replace("í", "i")
            .replace("ó", "o").replace("ú", "u").replace("ñ", "n")
            .trim()
    }

    private fun convertirFilasACrudas(filas: List<List<String>>): List<FilaCruda> {
        if (filas.isEmpty()) {
            Log.w(TAG, "convertirFilasACrudas: no hay filas para procesar")
            return emptyList()
        }
        
        Log.d(TAG, "convertirFilasACrudas: procesando ${filas.size} filas")
        filas.forEachIndexed { indice, fila ->
            Log.d(TAG, "  Fila $indice: $fila")
        }
        
        val (indiceNombre, indicePrecio, saltarPrimera) = detectarColumnas(filas.first())
        Log.d(TAG, "Columnas detectadas: nombre=$indiceNombre, precio=$indicePrecio, saltarPrimera=$saltarPrimera")
        
        val desde = if (saltarPrimera) 1 else 0
        val resultado = mutableListOf<FilaCruda>()
        
        for (i in desde until filas.size) {
            val fila = filas[i]
            if (fila.all { it.isBlank() }) {
                Log.d(TAG, "  Fila $i: vacía, saltando")
                continue
            }
            val nombre = fila.getOrNull(indiceNombre)?.trim().orEmpty()
            val precioTexto = fila.getOrNull(indicePrecio)?.trim().orEmpty()
            
            if (nombre.isBlank()) {
                Log.d(TAG, "  Fila $i: nombre vacío, saltando")
                continue
            }
            
            val precio = parsearPrecio(precioTexto)
            Log.d(TAG, "  Fila $i: nombre='$nombre', precioTexto='$precioTexto', precio=$precio")
            
            if (esFilaDeProducto(nombre, precio)) {
                resultado.add(FilaCruda(nombre, precio))
                Log.d(TAG, "    ✓ Producto válido agregado")
            } else {
                Log.d(TAG, "    ✗ Filtrada por esFilaDeProducto")
            }
        }
        
        Log.d(TAG, "convertirFilasACrudas: ${resultado.size} productos válidos de ${filas.size} filas")
        return resultado
    }

    private fun esFilaDeProducto(nombre: String, precio: Double): Boolean {
        val nombreNormalizado = nombre.lowercase(Locale.ROOT).trim()
        
        if (nombreNormalizado.length < 3) return false
        if (precio <= 0) return false
        
        val patronesNoProducto = listOf(
            Regex("^total\\s"),
            Regex("^subtotal\\s"),
            Regex("^documento\\s"),
            Regex("^boleta\\s"),
            Regex("^factura\\s"),
            Regex("^fecha\\s"),
            Regex("^hora\\s"),
            Regex("^rut\\s"),
            Regex("^direccion\\s"),
            Regex("^tel[eé]fono\\s"),
            Regex("^cliente\\s"),
            Regex("^vendedor\\s"),
            Regex("^caja\\s"),
            Regex("^folio\\s"),
            Regex("^n[úu]mero\\s"),
            Regex("^nota\\s"),
            Regex("^simulaci[oó]n\\s"),
            Regex("^inventario\\s"),
            Regex("^cargado"),
            Regex("^items?\\s"),
        )
        
        if (patronesNoProducto.any { it.containsMatchIn(nombreNormalizado) }) {
            return false
        }
        
        val palabrasInicialesNoProducto = setOf(
            "total", "subtotal", "documento", "boleta", "factura",
            "fecha", "hora", "rut", "direccion", "dirección",
            "telefono", "teléfono", "cliente", "vendedor",
            "caja", "folio", "numero", "número", "nota",
            "simulación", "simulacion", "inventario",
        )
        
        val primeraPalabra = nombreNormalizado.split(" ").firstOrNull().orEmpty()
        if (primeraPalabra in palabrasInicialesNoProducto) {
            return false
        }
        
        return true
    }

    /**
     * Convierte cadenas como "$3.990", "3990", "3,990.50", "3990,50" a Double.
     * Heurística chilena: si hay un solo separador, asume miles si tiene 3 dígitos después; si no, decimal.
     */
    private fun parsearPrecio(texto: String): Double {
        if (texto.isBlank()) return 0.0
        val limpio = texto.replace(Regex("[^0-9.,\\-]"), "")
        if (limpio.isBlank()) return 0.0
        return when {
            limpio.contains('.') && limpio.contains(',') -> {
                // Detectar formato: el último separador es decimal
                val ultimoPunto = limpio.lastIndexOf('.')
                val ultimaComa = limpio.lastIndexOf(',')
                if (ultimoPunto > ultimaComa) {
                    limpio.replace(",", "").toDoubleOrNull() ?: 0.0
                } else {
                    limpio.replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
                }
            }
            limpio.count { it == '.' } > 1 -> limpio.replace(".", "").toDoubleOrNull() ?: 0.0
            limpio.count { it == ',' } > 1 -> limpio.replace(",", "").toDoubleOrNull() ?: 0.0
            limpio.contains(',') -> {
                val partes = limpio.split(",")
                if (partes.last().length == 3 && partes.size == 2) {
                    // Formato miles: "3,990"
                    limpio.replace(",", "").toDoubleOrNull() ?: 0.0
                } else {
                    limpio.replace(",", ".").toDoubleOrNull() ?: 0.0
                }
            }
            limpio.contains('.') -> {
                val partes = limpio.split(".")
                if (partes.last().length == 3 && partes.size == 2) {
                    limpio.replace(".", "").toDoubleOrNull() ?: 0.0
                } else {
                    limpio.toDoubleOrNull() ?: 0.0
                }
            }
            else -> limpio.toDoubleOrNull() ?: 0.0
        }
    }

    private fun limpiarYNormalizar(filas: List<FilaCruda>): List<ProductoEscaneado> {
        return filas
            .filter { it.nombre.isNotBlank() }
            .map { fila ->
                val nombreLimpio = fila.nombre
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .split(" ")
                    .joinToString(" ") { palabra ->
                        if (palabra.isEmpty()) palabra
                        else palabra[0].uppercaseChar() + palabra.substring(1).lowercase(Locale.ROOT)
                    }
                ProductoEscaneado(
                    nombre = nombreLimpio,
                    categoria = "Despensa",
                    precio = fila.precio,
                )
            }
            .distinctBy { it.nombre.lowercase(Locale.ROOT) }
    }

    private fun obtenerNombreArchivo(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val indice = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (indice >= 0 && cursor.moveToFirst()) {
                    val nombre = cursor.getString(indice)
                    if (!nombre.isNullOrBlank()) return nombre
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment.orEmpty()
    }

    private const val UMBRAL_SIMILITUD_DUPLICADO = 0.75

    /**
     * Enriquece una lista de productos crudos comparándolos contra el catálogo existente
     * del vendedor (remoto en Firestore y local en caché).
     *
     * Para cada producto, busca la mejor coincidencia por similitud de nombre.
     * Si la similitud supera el [UMBRAL_SIMILITUD_DUPLICADO], marca el producto como
     * existente y adopta la categoría y el nombre del catálogo; en caso contrario,
     * le asigna una categoría estimada mediante [BoletaParser.adivinarCategoria].
     *
     * @param context Contexto de la aplicación para acceder a la caché local.
     * @param productosCrudos Lista de productos sin enriquecer.
     * @return Lista de productos enriquecidos con información de coincidencia y categoría.
     */
    suspend fun enriquecerProductos(
        context: Context,
        productosCrudos: List<ProductoEscaneado>,
    ): List<ProductoEscaneado> = withContext(Dispatchers.IO) {
        if (productosCrudos.isEmpty()) return@withContext emptyList()

        val vendedorId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val productosExistentes = cargarProductosExistentes(context, vendedorId)

        Log.i(TAG, "enriquecerProductos() procesando=${productosCrudos.size} existentes=${productosExistentes.size}")

        productosCrudos.map { producto ->
            val mejorCoincidencia = productosExistentes
                .map { existente -> existente to BoletaParser.similitud(producto.nombre, existente.nombre) }
                .filter { it.second >= UMBRAL_SIMILITUD_DUPLICADO }
                .maxByOrNull { it.second }

            if (mejorCoincidencia != null) {
                val (existente, _) = mejorCoincidencia
                producto.copy(
                    existeEnCatalogo = true,
                    idProductoExistente = existente.id,
                    categoria = existente.categoria,
                    nombre = BoletaParser.capitalizar(existente.nombre),
                )
            } else {
                val categoriaDeducida = BoletaParser.adivinarCategoria(producto.nombre)
                producto.copy(categoria = categoriaDeducida)
            }
        }
    }

    private suspend fun cargarProductosExistentes(
        context: Context,
        vendedorId: String,
    ): List<ProductoLocal> = withContext(Dispatchers.IO) {
        val existentes = mutableListOf<ProductoLocal>()

        if (vendedorId.isNotBlank()) {
            try {
                val nombresRemotos = ProductoFiltroDuplicados.obtenerNombresExistentes(vendedorId)
                nombresRemotos.forEach { nombre ->
                    existentes.add(
                        ProductoLocal(
                            id = "remoto-${nombre.hashCode()}",
                            nombre = nombre,
                            categoria = BoletaParser.adivinarCategoria(nombre),
                            precio = 0.0,
                        ),
                    )
                }
            } catch (excepcion: Exception) {
                Log.w(TAG, "No se pudieron cargar productos remotos", excepcion)
            }
        }

        val cacheLocal = CacheCatalogoLocal.obtener(context)
        existentes.addAll(cacheLocal)

        existentes.distinctBy { BoletaParser.normalizar(it.nombre) }
    }

    private data class FilaCruda(val nombre: String, val precio: Double)
}
