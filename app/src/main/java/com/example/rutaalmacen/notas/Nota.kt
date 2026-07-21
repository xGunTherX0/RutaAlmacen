package com.example.rutaalmacen.notas

/**
 * Modelo de datos que representa una nota del usuario.
 *
 * Cada nota se almacena en Firestore como un documento dentro de la subcolección
 * de notas del usuario autenticado.
 *
 * @property id Identificador único del documento en Firestore.
 *              Se genera automáticamente si está vacío al momento de guardar.
 * @property titulo Título descriptivo de la nota.
 * @property contenido Cuerpo o texto principal de la nota.
 * @property fechaLong Marca de tiempo en milisegundos desde epoch,
 *                     utilizada para ordenar las notas por fecha de actualización.
 * @property colorHex Código de color en formato hexadecimal con canal alfa (AARRGGBB)
 *                     que define el color de fondo de la tarjeta visual de la nota.
 */
data class Nota(
    val id: String = "",
    val titulo: String = "",
    val contenido: String = "",
    val fechaLong: Long = 0L,
    val colorHex: String = "#FFFEF3C7",
)

/**
 * Objeto que centraliza los colores predefinidos disponibles para las notas.
 *
 * Cada color se expresa en formato hexadecimal con canal alfa (AARRGGBB).
 * La lista [opciones] contiene todos los colores disponibles para el selector visual.
 */
object NotaColores {
    /** Color amarillo predeterminado para notas nuevas. */
    const val AMARILLO = "#FFFEF3C7"
    /** Color verde suave. */
    const val VERDE = "#FFDCFCE7"
    /** Color rosa suave. */
    const val ROSA = "#FFFCE7F3"
    /** Color azul suave. */
    const val AZUL = "#FFDBEAFE"
    /** Color lila suave. */
    const val LILA = "#FFEDE9FE"
    /** Color naranja suave. */
    const val NARANJA = "#FFFFEDD5"

    /** Lista ordenada con todas las opciones de color disponibles para el selector. */
    val opciones: List<String> = listOf(AMARILLO, VERDE, ROSA, AZUL, LILA, NARANJA)
}
