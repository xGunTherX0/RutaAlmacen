package com.example.rutaalmacen.entrada.ocr

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Servicio de filtrado de productos duplicados contra el inventario existente en Firestore.
 *
 * Consulta la colección «InventarioPublico» para obtener los nombres de productos
 * ya registrados por un vendedor y determina cuáles de los productos recién escaneados
 * son duplicados, utilizando comparación normalizada y distancia de Levenshtein
 * como tolerancia de coincidencia.
 */
object ProductoFiltroDuplicados {

    private const val COLECCION_INVENTARIO_PUBLICO = "InventarioPublico"

    /**
     * Obtiene el conjunto de nombres de productos ya existentes para un vendedor determinado,
     * consultando Firestore y normalizando cada nombre para comparación insensible a mayúsculas,
     * tildes y caracteres especiales.
     *
     * @param vendedorId Identificador único del vendedor (UID de Firebase Auth).
     * @return Conjunto de nombres normalizados. Vacío si el ID es nulo, está en blanco,
     *         o si ocurre un error de red.
     */
    suspend fun obtenerNombresExistentes(vendedorId: String): Set<String> {
        if (vendedorId.isBlank()) return emptySet()
        return try {
            val base = FirebaseFirestore.getInstance()
            val docs = base.collection(COLECCION_INVENTARIO_PUBLICO)
                .whereEqualTo("vendedorId", vendedorId)
                .get()
                .await()
            docs.mapNotNull { it.getString("nombre") }
                .map { BoletaParser.normalizar(it) }
                .filter { it.isNotBlank() }
                .toSet()
        } catch (e: Exception) {
            Log.e("OcrDebug", "obtenerNombresExistentes fallo", e)
            emptySet()
        }
    }

    /**
     * Filtra los productos escaneados, separando los nuevos de los duplicados respecto al
     * catálogo existente.
     *
     * Un producto se considera duplicado si su nombre normalizado coincide exactamente
     * con alguno existente o si la distancia de Levenshtein entre ambos es menor o igual a 2.
     *
     * @param productos Lista de productos escaneados que se desea evaluar.
     * @param nombresNormalizadosExistentes Conjunto de nombres ya registrados, previamente normalizados.
     * @return Un par donde el primer elemento es la lista de productos nuevos (no duplicados)
     *         y el segundo es la cantidad de productos omitidos por ser duplicados.
     */
    fun filtrarDuplicados(
        productos: List<ProductoEscaneado>,
        nombresNormalizadosExistentes: Set<String>,
    ): Pair<List<ProductoEscaneado>, Int> {
        if (nombresNormalizadosExistentes.isEmpty()) {
            return productos to 0
        }
        val nuevos = mutableListOf<ProductoEscaneado>()
        var omitidos = 0
        for (producto in productos) {
            val normalizado = BoletaParser.normalizar(producto.nombre)
            val esDuplicadoExacto = normalizado.isNotBlank() &&
                nombresNormalizadosExistentes.any { existente ->
                    existente == normalizado ||
                        BoletaParser.distanciaLevenshtein(existente, normalizado) <= 2
                }
            if (esDuplicadoExacto) {
                omitidos++
            } else {
                nuevos.add(producto)
            }
        }
        return nuevos to omitidos
    }
}
