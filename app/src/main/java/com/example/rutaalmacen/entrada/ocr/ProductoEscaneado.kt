package com.example.rutaalmacen.entrada.ocr

/**
 * Modelo de datos que representa un producto identificado durante el escaneo OCR
 * o la importación desde archivo.
 *
 * Cada instancia posee un identificador único generado automáticamente y conserva
 * la información mínima necesaria para su visualización, edición y persistencia
 * en el catálogo local o remoto.
 *
 * @property id Identificador numérico único, generado a partir de la marca de tiempo
 *              y un valor aleatorio para evitar colisiones en memoria.
 * @property nombre Nombre legible del producto, potencialmente corregido post-OCR.
 * @property categoria Categoría comercial asignada (ej. "Despensa", "Lácteos y Huevos").
 * @property precio Precio unitario del producto en pesos chilenos. Cero si no se ha ingresado.
 * @property existeEnCatalogo Indica si el producto ya se encuentra registrado en el catálogo
 *                            del vendedor (local o remoto).
 * @property idProductoExistente Identificador del producto existente en el catálogo,
 *                               o `null` si se trata de un producto nuevo.
 */
data class ProductoEscaneado(
    val id: Long = System.nanoTime() + (Math.random() * 1_000_000).toLong(),
    var nombre: String,
    var categoria: String,
    var precio: Double = 0.0,
    val existeEnCatalogo: Boolean = false,
    val idProductoExistente: String? = null,
)
