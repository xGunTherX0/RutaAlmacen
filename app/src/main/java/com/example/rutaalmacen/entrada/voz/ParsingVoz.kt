package com.example.rutaalmacen.entrada.voz

import java.util.Locale

/**
 * Motor de análisis sintáctico para texto dictado por voz.
 *
 * Procesa texto en lenguaje natural dictado por el usuario en español chileno,
 * extrayendo nombres de productos, precios, categorías y tipos de precio.
 * Todas las operaciones son independientes del estado.
 */
object ParsingVoz {

    /**
     * Analiza el texto dictado por voz y extrae los productos mencionados.
     *
     * El proceso de análisis incluye: normalización del texto a minúsculas,
     * división en segmentos individuales, extracción de precios y tipos de precio,
     * detección automática de categorías y limpieza de nombres.
     *
     * @param textoCrudo Texto sin procesar tal como fue reconocido por el motor de voz.
     * @return Un [ResultadoVoz] con la lista de productos detectados y el texto original.
     */
    fun parsear(textoCrudo: String): ResultadoVoz {
        if (textoCrudo.isBlank()) return ResultadoVoz()

        val textoNormalizado = textoCrudo.lowercase(Locale.forLanguageTag("es-CL")).trim()
        val segmentos = dividirEnProductos(textoNormalizado)

        val productos = mutableListOf<ProductoDetectado>()
        for (segmento in segmentos) {
            val producto = parsearSegmento(segmento.trim())
            if (producto != null) productos.add(producto)
        }

        // Si ningún separador produjo segmentos válidos, se intenta con el texto completo como un solo producto
        if (productos.isEmpty()) {
            val nombre = limpiarNombre(textoNormalizado)
            if (nombre.isNotBlank()) {
                productos.add(
                    ProductoDetectado(
                        nombre = capitalizar(nombre),
                        categoria = detectarCategoria(nombre),
                        tipoPrecio = detectarTipoPrecio(textoNormalizado),
                    )
                )
            }
        }

        return ResultadoVoz(productos = productos, textoOriginal = textoCrudo)
    }

    private val separadores = listOf(
        ", y ", " y luego ", " luego ", " después ", " despues ",
        " también ", " tambien ", " además ", " ademas ", " y ", " e ", ", ",
    )

    private val palabrasPrecio = listOf(
        "pesos", "peso", "luca", "lucas", "luke", "lukes", "clp", "el precio", "vale", "cuesta",
    )

    private val palabrasTipoKilo = listOf(
        "por kilo", "por kilogramo", "por kilos", "por kilogramos",
        "el kilo", "los kilos", "al kilo", "kilo", "kilos", "kg",
    )

    private val palabrasTipoUnidad = listOf(
        "por unidad", "por unidades", "por uni",
        "la unidad", "las unidades", "cada uno", "cada una",
        "unidad", "unidades", "c/u", "por u",
    )

    private val palabrasCantidad = listOf(
        "cajas", "caja", "paquetes", "paquete",
        "bolsas", "bolsa", "latas", "lata",
        "botellas", "botella", "frascos", "frasco",
        "unidades", "unidad", "kilos", "kilo", "kg",
        "gramos", "gramo", "litros", "litro",
    )

    private val mapaCategorias: List<Pair<List<String>, String>> = listOf(
        listOf("leche", "leches", "queso", "quesos", "yogurt", "huevo", "huevos",
            "mantequilla", "crema", "manjar", "ricotta") to "Lácteos y Huevos",
        listOf("pollo", "carne", "carnes", "cerdo", "res", "salchicha", "salchichas",
            "chorizo", "jamon", "jamón", "pescado", "atun", "atún", "longaniza",
            "cecina", "cecinas", "vienesas") to "Cecinas y Quesos",
        listOf("bebida", "bebidas", "jugo", "jugos", "agua", "aguas", "coca",
            "pepsi", "sprite", "fanta", "cerveza", "cervezas", "vino", "vinos",
            "energy", "energía", "redbull", "monster", "gaseosa") to "Bebidas y Jugos",
        listOf("pan", "panes", "marraqueta", "hallulla", "queque", "torta", "galleta",
            "galletas", "croissant", "dona", "donas", "biscocho", "biscochos") to "Pan y Pastelería",
        listOf("manzana", "manzanas", "naranja", "naranjas", "platano", "plátano",
            "platanos", "plátanos", "banana", "frutilla", "frutillas", "frutilla",
            "uva", "uvas", "papa", "papas", "tomate", "tomates", "cebolla",
            "cebollas", "zanahoria", "zanahorias", "lechuga", "lechugas", "limon",
            "limones", "palta", "paltas", "frutilla", "frutillas", "durazno",
            "frambuesa", "arándano", "arandanos", "kiwi", "mango", "piña",
            "pina", "sandia", "sandía", "melon", "melón", "pera", "fruta") to "Frutas y Verduras",
        listOf("chocolate", "chocolates", "snack", "snacks", "papa frita", "papas fritas",
            "galleta", "galletas", "caramelo", "caramelos", "golosina", "golosinas",
            "helado", "helados", "paleta", "paletas", "confite", "confites",
            "lúcuma", "lucuma", "mermelada", "dulce", "dulces") to "Snacks y Dulces",
        listOf("detergente", "detergentes", "cloro", "lavaloza", "lava loza",
            "papel", "papel higienico", "esponja", "esponjas", "bolsa de basura",
            "bolsas de basura", "limpia pisos", "limpiapisos", "cera") to "Aseo Hogar",
        listOf("shampoo", "shampoos", "jabon", "jabón", "jabones", "pasta de dientes",
            "dentifrico", "desodorante", "desodorantes", "toalla", "toallas",
            "papel higienico", "crema dental", "enjuague", "peine", "cepillo",
            "perfume", "colonias") to "Higiene Personal",
    )

    private fun dividirEnProductos(texto: String): List<String> {
        // División iterativa: aplica cada separador sucesivamente sobre los segmentos resultantes
        var segmentos = listOf(texto)
        for (separador in separadores) {
            val nuevos = mutableListOf<String>()
            for (s in segmentos) {
                if (s.contains(separador)) {
                    nuevos.addAll(s.split(separador))
                } else {
                    nuevos.add(s)
                }
            }
            segmentos = nuevos
        }
        return segmentos.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parsearSegmento(segmento: String): ProductoDetectado? {
        if (segmento.length < 2) return null

        val precio = extraerPrecio(segmento)
        val tipoPrecio = detectarTipoPrecio(segmento)
        val nombre = limpiarNombre(segmento)

        if (nombre.isBlank()) return null

        return ProductoDetectado(
            nombre = capitalizar(nombre),
            precio = precio,
            tipoPrecio = tipoPrecio,
            categoria = detectarCategoria(nombre),
        )
    }

    private fun extraerPrecio(texto: String): Double {
        // Prioridad: precio con moneda explícita > precio con signo $ > primer número en rango válido
        val regexConMoneda = Regex("""(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})?)\s*(?:pesos|luca|lucas|luke|lukes|clp)""")
        val regexConSigno = Regex("""\$\s*(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})?)""")
        val regexNumero = Regex("""\b(\d{2,7})\b""")

        regexConMoneda.find(texto)?.let { match ->
            return match.groupValues[1].replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
        }
        regexConSigno.find(texto)?.let { match ->
            return match.groupValues[1].replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
        }
        val matches = regexNumero.findAll(texto).toList()
        for (match in matches) {
            val valor = match.groupValues[1].toDoubleOrNull() ?: continue
            if (valor in 1.0..9_999_999.0) return valor
        }
        return 0.0
    }

    private fun detectarTipoPrecio(texto: String): String {
        for (frase in palabrasTipoKilo) {
            if (texto.contains(frase)) return "kilo"
        }
        for (frase in palabrasTipoUnidad) {
            if (texto.contains(frase)) return "unidad"
        }
        return "unidad"
    }

    private fun detectarCategoria(nombre: String): String {
        for ((keywords, categoria) in mapaCategorias) {
            for (keyword in keywords) {
                if (nombre.contains(keyword)) return categoria
            }
        }
        return "Despensa"
    }

    private fun limpiarNombre(texto: String): String {
        // Eliminación progresiva: precios, palabras de moneda, tipos de precio, cantidades, dígitos sueltos y caracteres especiales
        var limpio = texto
        limpio = limpio.replace(Regex("""\$\s*\d+(?:[.,]\d+)*"""), " ")
        limpio = limpio.replace(Regex("""\b\d+(?:[.,]\d+)?\s*(?:pesos|luca|lucas|luke|lukes|clp)?\b"""), " ")
        for (palabra in palabrasPrecio) {
            limpio = limpio.replace(Regex("""\b${Regex.escape(palabra)}\b"""), " ")
        }
        for (frase in palabrasTipoKilo + palabrasTipoUnidad) {
            limpio = limpio.replace(Regex("""\b${Regex.escape(frase)}\b"""), " ")
        }
        for (palabra in palabrasCantidad) {
            limpio = limpio.replace(Regex("""\b${Regex.escape(palabra)}\b"""), " ")
        }
        limpio = limpio.replace(Regex("""\b\d+\b"""), " ")
        limpio = limpio.replace(Regex("""[^\wáéíóúñüÁÉÍÓÚÑÜ]"""), " ")
        limpio = limpio.replace(Regex("""\s+"""), " ").trim()
        return if (limpio.length < 2) "" else limpio
    }

    private fun capitalizar(texto: String): String {
        if (texto.isEmpty()) return texto
        return texto.split(" ").joinToString(" ") { palabra ->
            if (palabra.isEmpty()) palabra
            else palabra[0].uppercaseChar() + palabra.substring(1)
        }
    }
}
