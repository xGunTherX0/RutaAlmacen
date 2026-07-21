package com.example.rutaalmacen.entrada.voz

/**
 * Resultado del análisis sintáctico de texto dictado por voz.
 *
 * Contiene la lista de productos extraídos del texto dictado y conserva
 * el texto original para referencia o depuración.
 *
 * @param productos Lista de productos detectados a partir del texto dictado.
 * @param textoOriginal Texto original sin procesar tal como fue dictado por el usuario.
 */
data class ResultadoVoz(
    val productos: List<ProductoDetectado> = emptyList(),
    val textoOriginal: String = "",
) {
    /** Indica si el resultado contiene al menos un producto detectado. */
    val esValido: Boolean
        get() = productos.isNotEmpty()

    /** Cantidad total de productos detectados en el resultado. */
    val cantidadTotal: Int
        get() = productos.size
}

/**
 * Representa un producto individual detectado mediante reconocimiento de voz.
 *
 * Contiene toda la información necesaria para persistir el producto:
 * identificador único, nombre, precio, categoría y tipo de precio.
 *
 * @param id Identificador único generado automáticamente al momento de la creación.
 * @param nombre Nombre del producto dictado o ingresado por el usuario.
 * @param precio Precio unitario o por kilo del producto. Cero si no fue dictado.
 * @param categoria Categoría comercial asignada automáticamente o por el usuario.
 * @param tipoPrecio Tipo de precio del producto: `"unidad"` o `"kilo"`.
 */
data class ProductoDetectado(
    val id: Long = System.nanoTime() + (Math.random() * 1_000_000).toLong(),
    var nombre: String,
    var precio: Double = 0.0,
    var categoria: String = "Despensa",
    var tipoPrecio: String = "unidad",
) {
    /** Indica si el producto tiene un precio asignado mayor a cero. */
    val tienePrecio: Boolean get() = precio > 0

    companion object {
        /** Lista de categorías comerciales disponibles para clasificación de productos. */
        val CATEGORIAS: List<String> = listOf(
            "Despensa",
            "LÃ¡cteos y Huevos",
            "Cecinas y Quesos",
            "Bebidas y Jugos",
            "Pan y PastelerÃ­a",
            "Frutas y Verduras",
            "Snacks y Dulces",
            "Congelados",
            "Aseo Hogar",
            "Higiene Personal",
        )

        /** Tipos de precio admitidos: por unidad o por kilo. */
        val TIPOS_PRECIO: List<String> = listOf("unidad", "kilo")
    }
}
