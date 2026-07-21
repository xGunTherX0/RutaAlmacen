package com.example.rutaalmacen.productos

import com.example.rutaalmacen.ProductosFragment
import com.google.firebase.firestore.DocumentSnapshot
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Objeto de utilidades para el cálculo, validación y presentación de ofertas de productos.
 *
 * Centraliza la lógica de negocio relacionada con ofertas: cálculo de precios con descuento,
 * determinación de vigencia, generación de textos de tiempo restante y lectura de datos
 * de oferta desde documentos de Firestore.
 */
object OfertaUtil {

    /**
     * Enumeración de unidades de tiempo disponibles para definir la vigencia de una oferta.
     *
     * @property etiqueta Nombre legible de la unidad para presentación en la interfaz.
     * @property milisegundos Equivalencia de una unidad en milisegundos.
     */
    enum class UnidadTiempo(val etiqueta: String, val milisegundos: Long) {
        /** Unidad de horas. */
        HORAS("Horas", TimeUnit.HOURS.toMillis(1)),
        /** Unidad de días. */
        DIAS("Días", TimeUnit.DAYS.toMillis(1)),
        /** Unidad de semanas (equivalente a 7 días). */
        SEMANAS("Semanas", TimeUnit.DAYS.toMillis(7)),
    }

    /**
     * Enumeración de los tipos de descuento que se pueden aplicar a una oferta.
     */
    enum class TipoDescuento {
        /** El vendedor ingresa directamente el precio final con descuento. */
        PRECIO_FINAL,
        /** El vendedor ingresa un porcentaje de descuento sobre el precio original. */
        PORCENTAJE,
    }

    /**
     * Resultado del cálculo de una oferta, que incluye el precio final y el porcentaje de descuento.
     *
     * @property precioOferta Precio final del producto después de aplicar el descuento.
     * @property descuentoPorcentaje Porcentaje de descuento aplicado, restringido al rango 1-99.
     */
    data class CalculoOferta(
        val precioOferta: Double,
        val descuentoPorcentaje: Int,
    )

    /**
     * Calcula la fecha de vencimiento de una oferta a partir de la unidad y cantidad especificadas.
     *
     * La cantidad mínima válida es 1; valores inferiores se ajustan automáticamente.
     *
     * @param unidad Unidad de tiempo en la que se expresa la vigencia.
     * @param cantidad Cantidad de unidades de tiempo que dura la oferta.
     * @return Marca de tiempo en milisegundos desde epoch que representa el vencimiento.
     */
    fun calcularFechaFin(unidad: UnidadTiempo, cantidad: Int): Long {
        // Se asegura que la cantidad sea al menos 1 para evitar ofertas con vencimiento pasado
        val cantidadValida = max(1, cantidad)
        return System.currentTimeMillis() + (unidad.milisegundos * cantidadValida)
    }

    /**
     * Calcula el resultado de una oferta a partir de un precio final deseado.
     *
     * Determina el porcentaje de descuento equivalente respecto al precio original.
     *
     * @param precioOriginal Precio original del producto. Debe ser mayor a cero.
     * @param precioFinal Precio final con descuento. Debe ser mayor o igual a cero y menor al precio original.
     * @return [CalculoOferta] con el precio final y el porcentaje de descuento calculado,
     *         o `null` si los parámetros son inválidos.
     */
    fun calcularPorPrecioFinal(precioOriginal: Double, precioFinal: Double): CalculoOferta? {
        if (precioOriginal <= 0.0 || precioFinal < 0.0 || precioFinal >= precioOriginal) {
            return null
        }
        // Se calcula el porcentaje de descuento y se restringe al rango válido 1-99
        val porcentaje = (((precioOriginal - precioFinal) / precioOriginal) * 100.0).roundToInt()
        return CalculoOferta(
            precioOferta = precioFinal,
            descuentoPorcentaje = porcentaje.coerceIn(1, 99),
        )
    }

    /**
     * Calcula el resultado de una oferta a partir de un porcentaje de descuento.
     *
     * Determina el precio final aplicando el porcentaje sobre el precio original.
     *
     * @param precioOriginal Precio original del producto. Debe ser mayor a cero.
     * @param porcentaje Porcentaje de descuento a aplicar. Debe estar en el rango 1-99.
     * @return [CalculoOferta] con el precio final y el porcentaje de descuento,
     *         o `null` si los parámetros son inválidos.
     */
    fun calcularPorPorcentaje(precioOriginal: Double, porcentaje: Int): CalculoOferta? {
        if (precioOriginal <= 0.0 || porcentaje !in 1..99) {
            return null
        }
        val precioOferta = precioOriginal * (1.0 - porcentaje / 100.0)
        return CalculoOferta(
            precioOferta = precioOferta,
            descuentoPorcentaje = porcentaje,
        )
    }

    /**
     * Determina si una oferta se encuentra vigente en el momento actual.
     *
     * @param enOferta Indicador de si el producto tiene una oferta activa.
     * @param fechaFinOferta Marca de tiempo de vencimiento de la oferta, o `null` si no existe.
     * @return `true` si la oferta está activa y no ha vencido; `false` en caso contrario.
     */
    fun estaVigente(enOferta: Boolean, fechaFinOferta: Long?): Boolean {
        if (!enOferta || fechaFinOferta == null) return false
        return System.currentTimeMillis() < fechaFinOferta
    }

    /**
     * Genera un texto descriptivo del tiempo restante de una oferta.
     *
     * El texto se adapta según la magnitud del tiempo restante:
     * - 7 o más días: muestra semanas restantes.
     * - 1 o más días: muestra días restantes.
     * - 1 o más horas: muestra horas restantes.
     * - 10 o más minutos: muestra minutos restantes.
     * - Menos de 10 minutos: muestra "¡Vence pronto!".
     *
     * @param fechaFinOferta Marca de tiempo de vencimiento de la oferta, o `null`.
     * @return Cadena de texto con el tiempo restante formateado, o cadena vacía si no hay fecha.
     */
    fun tiempoRestanteTexto(fechaFinOferta: Long?): String {
        if (fechaFinOferta == null) return ""
        val restanteMs = fechaFinOferta - System.currentTimeMillis()
        if (restanteMs <= 0) return "Vencida"
        val minutos = restanteMs / 60_000L
        val horas = minutos / 60L
        val dias = horas / 24L
        return when {
            dias >= 7 -> {
                val semanas = dias / 7
                if (semanas == 1L) "¡Queda 1 semana!" else "¡Quedan $semanas semanas!"
            }
            dias >= 1 -> {
                if (dias == 1L) "¡Queda 1 día!" else "¡Quedan $dias días!"
            }
            horas >= 1 -> {
                if (horas == 1L) "¡Queda 1 hora!" else "¡Quedan $horas horas!"
            }
            minutos >= 10 -> "¡Quedan $minutos min!"
            else -> "¡Vence pronto!"
        }
    }

    /**
     * Lee los datos de oferta desde un documento de Firestore.
     *
     * Extrae los campos de estado de oferta, precio de oferta, porcentaje de descuento
     * y fecha de vencimiento desde el documento proporcionado.
     *
     * @param documento Documento de [DocumentSnapshot] que contiene los campos de oferta.
     * @return [DatosOferta] con los valores extraídos del documento.
     */
    fun leerProducto(documento: DocumentSnapshot): DatosOferta {
        val enOferta = documento.getBoolean("enOferta") ?: false
        val precioOferta = documento.getDouble("precioOferta")
            ?: documento.getLong("precioOferta")?.toDouble()
        val descuento = documento.getLong("descuentoPorcentaje")?.toInt()
        val fechaFin = documento.getLong("fechaFinOferta")
        return DatosOferta(enOferta, precioOferta, descuento, fechaFin)
    }

    /**
     * Datos de oferta extraídos de un documento de Firestore.
     *
     * @property enOferta Indica si el producto tiene una oferta activa.
     * @property precioOferta Precio con descuento, o `null` si no hay oferta.
     * @property descuentoPorcentaje Porcentaje de descuento, o `null` si no hay oferta.
     * @property fechaFinOferta Marca de tiempo de vencimiento, o `null` si no hay oferta.
     */
    data class DatosOferta(
        val enOferta: Boolean,
        val precioOferta: Double?,
        val descuentoPorcentaje: Int?,
        val fechaFinOferta: Long?,
    )

    /**
     * Genera un texto resumen de la oferta de un producto para presentación al vendedor.
     *
     * Incluye el precio de oferta, el porcentaje de descuento y el tiempo restante
     * si la oferta aún no ha vencido.
     *
     * @param producto Producto del cual se genera el resumen de oferta.
     * @return Cadena con el resumen formateado, o cadena vacía si el producto no tiene oferta activa.
     */
    fun resumenVendedor(producto: ProductosFragment.Producto): String {
        if (!producto.enOferta || producto.precioOferta == null) return ""
        // Se eliminan los signos de exclamación para el resumen compacto
        val tiempo = tiempoRestanteTexto(producto.fechaFinOferta).removePrefix("¡").removeSuffix("!")
        val precio = "$${String.format(java.util.Locale.forLanguageTag("es-CL"), "%.0f", producto.precioOferta)}"
        val descuento = producto.descuentoPorcentaje?.let { " (-$it%)" }.orEmpty()
        return if (tiempo.isNotBlank() && tiempo != "Vencida") {
            "Oferta: $precio$descuento · $tiempo"
        } else {
            "Oferta: $precio$descuento"
        }
    }
}
