package com.example.rutaalmacen.entrada.ocr

import android.content.Context
import com.example.rutaalmacen.seguridad.PreferenciasCifradas
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object CacheCatalogoLocal {

    private const val ARCHIVO = "cache_catalogo_local"
    private const val CLAVE = "productos"

    fun obtener(context: Context): List<ProductoLocal> {
        val prefs = PreferenciasCifradas.crear(ARCHIVO)
        val json = prefs.getString(CLAVE, null) ?: return emptyList()
        return try {
            val tipo = object : TypeToken<List<ProductoLocal>>() {}.type
            Gson().fromJson(json, tipo) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun guardar(context: Context, productos: List<ProductoLocal>) {
        val prefs = PreferenciasCifradas.crear(ARCHIVO)
        val json = Gson().toJson(productos)
        prefs.edit().putString(CLAVE, json).apply()
    }
}

/**
 * Representación mínima de un producto almacenado en la caché local.
 *
 * Se utiliza como intermediario entre la persistencia en [CacheCatalogoLocal]
 * y los componentes de presentación, conteniendo únicamente los campos
 * necesarios para la identificación y clasificación del producto.
 *
 * @property id Identificador único del producto (puede ser un ID remoto o un ID local generado).
 * @property nombre Nombre legible del producto.
 * @property categoria Categoría comercial a la que pertenece el producto.
 * @property precio Precio unitario del producto en pesos chilenos.
 */
data class ProductoLocal(
    val id: String,
    val nombre: String,
    val categoria: String,
    val precio: Double,
)
