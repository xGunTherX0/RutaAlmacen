package com.example.rutaalmacen.entrada.ocr

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Procesador de texto OCR para boletas y listas de compras chilenas.
 *
 * Proporciona utilidades para limpiar, normalizar, deduplicar y clasificar
 * los textos extraídos mediante OCR. Incluye un diccionario de correcciones
 * ortográficas frecuentes, un clasificador heurístico de categorías comerciales
 * y funciones de similitud textual basadas en distancia de Levenshtein
 * y coeficiente de Jaccard sobre tokens.
 */
object BoletaParser {

    private val palabrasAdministrativas = listOf(
        "RUT", "R.U.T", "GIRO", "BOLETA", "FACTURA", "TOTAL", "SUBTOTAL",
        "IVA", "NETO", "DESCUENTO", "DESC", "CAMBIO", "EFECTIVO", "TARJETA",
        "TRANSFERENCIA", "DEBITO", "CRÉDITO", "DEBITO", "CREDITO", "VUELTO",
        "FECHA", "HORA", "CAJA", "LOCAL", "TIENDA", "SUCURSAL", "DIRECCION",
        "DIRECCIÓN", "TEL", "TELÉFONO", "TELEFONO", "FAX", "RUTA", "FOLIO",
        "NRO", "NÚMERO", "NUMERO", "CLIENTE", "RUTA:", "PAGAR", "VENDEDOR",
        "BOLETA ELECTRONICA", "BOLETA ELECTRÓNICA", "DTE", "SII",
    )

    private val patronLineasAdministrativas = Regex(
        "^(?:" + palabrasAdministrativas.joinToString("|") { Regex.escape(it) } +
            ").*\$",
        RegexOption.IGNORE_CASE,
    )

    private val patronNumeros = Regex("""\d+([.,]\d+)?""")
    private val patronLetrasPuras = Regex("""^[a-zA-ZáéíóúñüÁÉÍÓÚÑÜ\s]{3,}\$""")

    private val mapaCategorias = listOf(
        "Despensa" to listOf(
            "arroz", "fideo", "tallarin", "tallarín", "poroto", "lenteja", "garbanzo",
            "azucar", "azúcar", "sal", "aceite", "vinagre", "salsa", "mayonesa",
            "mostaza", "ketchup", "conserva", "atun", "atún", "sardina", "harina",
            "polenta", "avena", "cereal", "cafe", "café", "te", "té", "yerba",
            "yerba mate", "mate", "cocina", "comida",
        ),
        "Lácteos y Huevos" to listOf(
            "leche", "leches", "queso", "quesos", "yogurt", "yoghurt", "huevo",
            "huevos", "mantequilla", "manjar", "crema", "ricotta", "mascarpone",
            "leche condensada", "leche evaporada", "suero",
        ),
        "Cecinas y Quesos" to listOf(
            "cecina", "cecinas", "jamon", "jamón", "salchicha", "salchichas",
            "chorizo", "longaniza", "vienesa", "vienesas", "pepinillo",
            "salame", "mortadela", "pate", "pate", "cecina", "cecinas",
        ),
        "Bebidas y Jugos" to listOf(
            "bebida", "bebidas", "jugo", "jugos", "agua", "coca", "pepsi",
            "sprite", "fanta", "cerveza", "cervezas", "vino", "vinos",
            "redbull", "monster", "energetica", "energética", "gaseosa",
            "coca cola", "coca-cola", "coca-cola zero",
        ),
        "Pan y Pastelería" to listOf(
            "pan", "panes", "marraqueta", "hallulla", "hallullas", "queque",
            "torta", "galleta", "galletas", "croissant", "dona", "donas",
            "biscocho", "biscochos", "empanada", "empanadas", "factura",
            "facturas", "coliza", "colizas", "dobladita",
        ),
        "Frutas y Verduras" to listOf(
            "fruta", "frutas", "manzana", "manzanas", "naranja", "naranjas",
            "platano", "plátano", "banana", "frutilla", "frutillas",
            "uva", "uvas", "papa", "papas", "tomate", "tomates", "cebolla",
            "cebollas", "zanahoria", "zanahorias", "lechuga", "lechugas",
            "limon", "limones", "palta", "paltas", "durazno", "frambuesa",
            "arandano", "arándano", "kiwi", "mango", "pina", "piña",
            "sandia", "sandía", "melon", "melón", "pera", "verdura",
            "verduras", "pimenton", "pimentón", "zapallo", "zucchini",
            "pepino", "espinaca", "apio", "betarraga", "cebollin",
        ),
        "Snacks y Dulces" to listOf(
            "snack", "snacks", "papa frita", "papas fritas", "galleta",
            "galletas", "caramelo", "caramelos", "golosina", "golosinas",
            "helado", "helados", "paleta", "paletas", "confite", "confites",
            "chocolate", "chocolates", "chicle", "chicles", "mermelada",
            "manjar", "lúcuma", "lucuma",
        ),
        "Congelados" to listOf(
            "congelado", "congelados", "helado", "ice", "nugget", "nuggets",
            "hamburguesa", "hamburguesas", "pizza", "pizzas", "mariscos",
            "pescado", "salmon", "salmón", "atun", "atún", "verdura congelada",
        ),
        "Aseo Hogar" to listOf(
            "detergente", "detergentes", "cloro", "lavaloza", "lava loza",
            "papel", "papel higienico", "papel higiénico", "esponja",
            "esponjas", "bolsa de basura", "bolsas de basura", "limpia pisos",
            "limpiapisos", "cera", "desinfectante", "suavizante",
        ),
        "Higiene Personal" to listOf(
            "shampoo", "shampoos", "jabon", "jabón", "jabones", "pasta de dientes",
            "dentifrico", "desodorante", "desodorantes", "toalla", "toallas",
            "papel higienico", "papel higiénico", "crema dental", "enjuague",
            "peine", "cepillo", "perfume", "colonias", "desodorante",
            "shampoo", "acondicionador",
        ),
    )

    /**
     * Limpia y normaliza una lista de líneas de texto OCR, eliminando ruido
     * y artefactos comunes de las boletas.
     *
     * El proceso incluye:
     *   1. Eliminación de precios embebidos (ej. "$500", "3.990 pesos").
     *   2. Eliminación de símbolos y caracteres no alfabéticos.
     *   3. Filtrado de líneas administrativas (RUT, TOTAL, IVA, etc.).
     *   4. Corrección ortográfica mediante diccionario y distancia de Levenshtein.
     *   5. Capitalización de cada palabra.
     *
     * @param lineas Líneas de texto crudo provenientes del OCR.
     * @return Lista de líneas limpias, normalizadas y capitalizadas.
     */
    fun limpiar(lineas: List<String>): List<String> {
        return lineas
            .map { it.trim() }
            .filter { it.length >= 2 }
            .map { linea ->
                linea
                    .replace(Regex("""\$\s*\d+([.,]\d+)*"""), " ")
                    .replace(Regex("""\d+([.,]\d+)?\s*(pesos|peso|luca|lucas|clp)""", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("""\d+([.,]\d+)?\%"""), " ")
            }
            .map { linea ->
                linea
                    .replace(Regex("""^[^\wáéíóúñüÁÉÍÓÚÑÜ]+"""), " ")
                    .replace(Regex("""[^\wáéíóúñüÁÉÍÓÚÑÜ\s]+"""), " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
            }
            .filter { it.length >= 2 }
            .filter { linea ->
                if (patronLineasAdministrativas.matches(linea)) return@filter false
                true
            }
            .map { corregirPalabraOcr(it) }
            .map { capitalizar(it) }
    }

    private val diccionarioCorrecciones: List<Pair<String, String>> = listOf(
        "huevb" to "Huevos", "hueubs" to "Huevos", "hueb" to "Huevos",
        "huevs" to "Huevos", "hoevos" to "Huevos", "hueoos" to "Huevos",
        "huevs" to "Huevos", "huievos" to "Huevos", "huev" to "Huevos",
        "hoev" to "Huevos", "hoebs" to "Huevos", "heuvos" to "Huevos",
        "leche" to "Leche", "lecne" to "Leche", "lecue" to "Leche",
        "salchicha" to "Salchicha", "slalchicha" to "Salchicha",
        "salchich" to "Salchicha", "salchca" to "Salchicha",
        "salchichs" to "Salchicha", "slachicha" to "Salchicha",
        "zanahoria" to "Zanahoria", "zauaHoio" to "Zanahoria",
        "zauahoria" to "Zanahoria", "zanahori" to "Zanahoria",
        "zanhoria" to "Zanahoria", "znahoria" to "Zanahoria",
        "tomate" to "Tomate", "toMte" to "Tomate", "tojate" to "Tomate",
        "tomte" to "Tomate", "tomte" to "Tomate", "tojate" to "Tomate",
        "cebolla" to "Cebolla", "ceboya" to "Cebolla", "ceboya" to "Cebolla",
        "pann" to "Pan", "pan" to "Pan", "pna" to "Pan", "pan." to "Pan",
        "leche" to "Leche", "lecne" to "Leche", "lecue" to "Leche",
        "avena" to "Avena", "abena" to "Avena", "abna" to "Avena",
        "azucar" to "Azúcar", "azuca" to "Azúcar", "asucar" to "Azúcar",
        "arroz" to "Arroz", "arros" to "Arroz", "arrz" to "Arroz",
        "fideo" to "Fideo", "fideos" to "Fideos", "fido" to "Fideo",
        "queso" to "Queso", "quesso" to "Queso", "qso" to "Queso",
        "jamón" to "Jamón", "jamon" to "Jamón", "jamo" to "Jamón",
        "mantequilla" to "Mantequilla", "manteqilla" to "Mantequilla",
        "yogur" to "Yogur", "yogurt" to "Yogur", "yoguth" to "Yogur",
        "manzana" to "Manzana", "manza" to "Manzana", "manzn" to "Manzana",
        "naranja" to "Naranja", "naranj" to "Naranja", "naranj" to "Naranja",
        "platano" to "Plátano", "platno" to "Plátano", "platano" to "Plátano",
        "platano" to "Plátano", "platno" to "Plátano", "plátano" to "Plátano",
        "papa" to "Papa", "papa." to "Papa", "ppas" to "Papas",
        "pimie" to "Pimienta", "piminienta" to "Pimienta",
        "aceite" to "Aceite", "acete" to "Aceite", "acei" to "Aceite",
        "vinagre" to "Vinagre", "vinagr" to "Vinagre",
        "harina" to "Harina", "harna" to "Harina", "haria" to "Harina",
        "mayone" to "Mayonesa", "mayo" to "Mayonesa",
        "mostaza" to "Mostaza", "mstaza" to "Mostaza",
        "ketchup" to "Ketchup", "ketcup" to "Ketchup",
        "atun" to "Atún", "atún" to "Atún", "atun" to "Atún",
        "sardina" to "Sardina", "sardin" to "Sardina",
        "pizza" to "Pizza", "piz" to "Pizza",
        "hamburg" to "Hamburguesa", "hamburgues" to "Hamburguesa",
        "nugget" to "Nugget", "nuggets" to "Nuggets",
        "galleta" to "Galleta", "gallet" to "Galleta", "galeta" to "Galleta",
        "chocolate" to "Chocolate", "choclate" to "Chocolate",
        "caramelo" to "Caramelo", "caramelo" to "Caramelo",
        "chicle" to "Chicle", "chicl" to "Chicle",
        "helado" to "Helado", "hela" to "Helado", "heldo" to "Helado",
        "cerveza" to "Cerveza", "cerbeza" to "Cerveza", "cervza" to "Cerveza",
        "vino" to "Vino", "bino" to "Vino",
        "agua" to "Agua", "agau" to "Agua", "ahua" to "Agua",
        "jugo" to "Jugo", "jhuo" to "Jugo",
        "gaseosa" to "Gaseosa", "gaseo" to "Gaseosa",
        "pan" to "Pan", "pan." to "Pan",
        "leche" to "Leche", "leche." to "Leche",
    )

    private fun corregirPalabraOcr(palabra: String): String {
        val lower = palabra.lowercase().trim()
        if (lower.isBlank()) return palabra
        val correccionExacta = diccionarioCorrecciones.firstOrNull { it.first == lower }
        if (correccionExacta != null) return correccionExacta.second
        var mejorCoincidencia: Pair<String, Int>? = null
        for ((ocrErroneo, correcto) in diccionarioCorrecciones) {
            val distancia = distanciaLevenshtein(lower, ocrErroneo)
            if (distancia <= 2) {
                if (mejorCoincidencia == null || distancia < mejorCoincidencia.second) {
                    mejorCoincidencia = correcto to distancia
                }
            }
        }
        return mejorCoincidencia?.first ?: palabra
    }

    /**
     * Elimina productos duplicados de una lista, conservando la primera ocurrencia
     * de cada nombre normalizado.
     *
     * Además de la coincidencia exacta, detecta duplicados por contención de tokens:
     * si un nombre ya aceptado contiene todos los tokens significativos (≥ 3 caracteres)
     * de un nombre posterior y posee más tokens, se considera duplicado.
     *
     * @param productos Lista de productos escaneados a deduplicar.
     * @return Lista de productos sin duplicados.
     */
    fun deduplicar(productos: List<ProductoEscaneado>): List<ProductoEscaneado> {
        val aceptados = mutableListOf<ProductoEscaneado>()
        val clavesAceptadas = mutableSetOf<String>()
        for (producto in productos) {
            val clave = normalizar(producto.nombre)
            if (clave.isBlank()) continue
            if (clave in clavesAceptadas) continue
            val yaAceptadoComoToken = clavesAceptadas.any { aceptada ->
                val tokensAceptada = aceptada.split(" ").filter { it.length >= 3 }.toSet()
                val tokensActual = clave.split(" ").filter { it.length >= 3 }.toSet()
                if (tokensAceptada.isEmpty() || tokensActual.isEmpty()) return@any false
                tokensActual.any { it in tokensAceptada } && tokensAceptada.size > tokensActual.size
            }
            if (yaAceptadoComoToken) continue
            clavesAceptadas.add(clave)
            aceptados.add(producto)
        }
        return aceptados
    }

    /**
     * Deduce la categoría comercial más probable para un producto a partir de su nombre.
     *
     * Busca coincidencias de palabras clave dentro del nombre normalizado contra
     * un mapa de categorías predefinido. Si no encuentra coincidencia exacta,
     * intenta una coincidencia parcial por los primeros 4 caracteres.
     *
     * @param nombre Nombre del producto a clasificar.
     * @return Nombre de la categoría comercial (ej. "Despensa", "Lácteos y Huevos").
     *         Retorna "Despensa" como valor predeterminado si no hay coincidencia.
     */
    fun adivinarCategoria(nombre: String): String {
        val nombreNormalizado = normalizar(nombre)
        if (nombreNormalizado.isBlank()) return "Despensa"
        for ((categoria, palabras) in mapaCategorias) {
            for (palabra in palabras) {
                if (nombreNormalizado.contains(palabra)) return categoria
            }
        }
        val primerPalabra = nombreNormalizado.split(" ").firstOrNull() ?: return "Despensa"
        if (primerPalabra.length >= 4) {
            for ((categoria, palabras) in mapaCategorias) {
                for (palabra in palabras) {
                    if (palabra.startsWith(primerPalabra.take(4)) ||
                        primerPalabra.startsWith(palabra.take(4))
                    ) return categoria
                }
            }
        }
        return "Despensa"
    }

    /**
     * Normaliza un texto para comparación insensible a mayúsculas, tildes,
     * la ñ y caracteres especiales.
     *
     * Convierte a minúsculas con locale chileno, reemplaza vocales acentuadas
     * y la ñ por sus equivalentes sin tilde, elimina todo carácter no alfanumérico
     * y colapsa espacios múltiples.
     *
     * @param texto Texto de entrada a normalizar.
     * @return Texto normalizado en minúsculas, sin tildes ni signos de puntuación.
     */
    fun normalizar(texto: String): String {
        val lower = texto.lowercase(Locale.forLanguageTag("es-CL"))
        val sinTildes = lower
            .replace("á", "a").replace("é", "e").replace("í", "i")
            .replace("ó", "o").replace("ú", "u").replace("ü", "u")
            .replace("ñ", "n")
        val sinSignos = sinTildes.replace(Regex("[^a-z0-9 ]"), " ")
        return sinSignos.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Calcula el grado de similitud entre dos cadenas de texto, utilizando
     * una combinación de coincidencia por subcadena y coeficiente de Jaccard
     * sobre tokens normalizados.
     *
     * El resultado es un valor entre 0.0 (sin similitud) y 1.0 (idénticos).
     * Si una cadena está contenida dentro de la otra, se retorna 0.85.
     *
     * @param a Primera cadena a comparar.
     * @param b Segunda cadena a comparar.
     * @return Valor de similitud en el rango [0.0, 1.0].
     */
    fun similitud(a: String, b: String): Double {
        val na = normalizar(a)
        val nb = normalizar(b)
        if (na.isBlank() || nb.isBlank()) return 0.0
        if (na == nb) return 1.0
        if (na.contains(nb) || nb.contains(na)) return 0.85
        val tokensA = na.split(" ").filter { it.length >= 3 }.toSet()
        val tokensB = nb.split(" ").filter { it.length >= 3 }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val interseccion = tokensA.intersect(tokensB).size
        val union = tokensA.union(tokensB).size
        return if (union == 0) 0.0 else interseccion.toDouble() / union
    }

    /**
     * Capitaliza cada palabra de un texto, convirtiendo la primera letra
     * de cada token a mayúscula.
     *
     * @param texto Texto de entrada.
     * @return Texto con cada palabra capitalizada. Retorna la cadena vacía si la entrada está vacía.
     */
    fun capitalizar(texto: String): String {
        if (texto.isEmpty()) return texto
        return texto.split(" ").joinToString(" ") { palabra ->
            if (palabra.isEmpty()) palabra
            else palabra[0].uppercaseChar() + palabra.substring(1)
        }
    }

    /**
     * Extrae fragmentos de texto crudo a partir de un bloque de texto completo,
     * dividiendo por líneas y, dentro de cada línea con más de dos palabras,
     * separando additionally por delimitadores comunes (coma, punto y coma, guion, etc.).
     *
     * Este método preserva tanto las líneas completas como las palabras individuales
     * resultantes de la tokenización, para maximizar las oportunidades de coincidencia
     * en etapas posteriores de procesamiento.
     *
     * @param textoCompleto Bloque de texto completo proveniente del OCR.
     * @return Lista de fragmentos de texto (líneas y palabras individuales).
     */
    fun extraerTextoCrudo(textoCompleto: String): List<String> {
        val lineas = textoCompleto
            .split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val resultado = mutableListOf<String>()
        for (linea in lineas) {
            resultado.add(linea)
            if (linea.split(" ").size > 2) {
                val palabras = linea.split(Regex("""[\s,;.\-:]+"""))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (palabras.size > 1) {
                    resultado.addAll(palabras)
                }
            }
        }
        return resultado
    }

    /**
     * Asocia cada producto escaneado con su contraparte existente en el catálogo local,
     * si existe una coincidencia suficiente.
     *
     * Para cada producto, busca el mejor candidato dentro de [resultadosExistentes]
     * cuya similitud supere el [UMBRAL_SIMILITUD]. Si lo encuentra, marca el producto
     * como existente y adopta los datos del catálogo; en caso contrario, le asigna
     * una categoría estimada.
     *
     * @param productos Lista de productos escaneados a vincular.
     * @param resultadosExistentes Lista de productos del catálogo local contra los cuales comparar.
     * @return Lista de productos actualizados con la información de coincidencia y categoría.
     */
    fun unirProductosConCantidad(
        productos: List<ProductoEscaneado>,
        resultadosExistentes: List<ProductoLocal>,
    ): List<ProductoEscaneado> {
        return productos.map { producto ->
            val mejorCoincidencia = resultadosExistentes
                .map { existente -> existente to similitud(producto.nombre, existente.nombre) }
                .filter { it.second >= UMBRAL_SIMILITUD }
                .maxByOrNull { it.second }

            if (mejorCoincidencia != null) {
                val (existente, _) = mejorCoincidencia
                producto.copy(
                    existeEnCatalogo = true,
                    idProductoExistente = existente.id,
                    categoria = existente.categoria,
                    nombre = existente.nombre,
                )
            } else {
                producto.copy(categoria = adivinarCategoria(producto.nombre))
            }
        }
    }

    /**
     * Convierte una cadena de texto que representa una cantidad a un valor entero.
     *
     * Extrae el primer número encontrado en la cadena y lo limita al rango [1, 999].
     * Si la cadena es nula, vacía o no contiene un número válido, retorna 1.
     *
     * @param cantidadTexto Cadena de texto que contiene la cantidad a interpretar.
     * @return Valor entero de la cantidad, entre 1 y 999.
     */
    fun recortarCantidad(cantidadTexto: String?): Int {
        if (cantidadTexto.isNullOrBlank()) return 1
        val match = patronNumeros.find(cantidadTexto.trim())
        return match?.value?.toIntOrNull()?.coerceIn(1, 999) ?: 1
    }

    /**
     * Calcula la distancia de edición de Levenshtein entre dos cadenas de texto.
     *
     * La distancia representa el número mínimo de operaciones de inserción,
     * eliminación o sustitución de caracteres para transformar una cadena en otra.
     * Las cadenas se comparan en minúsculas con locale chileno.
     *
     * @param a Primera cadena de entrada.
     * @param b Segunda cadena de entrada.
     * @return Número entero no negativo que representa la distancia de edición.
     */
    fun distanciaLevenshtein(a: String, b: String): Int {
        val na = a.lowercase(Locale.forLanguageTag("es-CL"))
        val nb = b.lowercase(Locale.forLanguageTag("es-CL"))
        if (na == nb) return 0
        if (na.isEmpty()) return nb.length
        if (nb.isEmpty()) return na.length
        val matriz = Array(na.length + 1) { IntArray(nb.length + 1) }
        for (i in 0..na.length) matriz[i][0] = i
        for (j in 0..nb.length) matriz[0][j] = j
        for (i in 1..na.length) {
            for (j in 1..nb.length) {
                val costo = if (na[i - 1] == nb[j - 1]) 0 else 1
                matriz[i][j] = min(
                    min(matriz[i - 1][j] + 1, matriz[i][j - 1] + 1),
                    matriz[i - 1][j - 1] + costo,
                )
            }
        }
        val maxLen = max(na.length, nb.length)
        return if (maxLen == 0) 0 else matriz[na.length][nb.length]
    }

    /**
     * Umbral mínimo de similitud para considerar que dos productos son el mismo.
     *
     * Valores por encima de este umbral (0.5) indican que los nombres normalizados
     * comparten al menos la mitad de sus tokens significativos.
     */
    const val UMBRAL_SIMILITUD: Double = 0.5
}
