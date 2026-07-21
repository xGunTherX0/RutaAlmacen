package com.example.rutaalmacen.productos

import com.example.rutaalmacen.Constantes
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * Repositorio responsable de las operaciones de persistencia de productos contra Firestore.
 *
 * Gestiona el almacenamiento de productos en la subcolección "Inventario" del usuario
 * autenticado y su sincronización con la colección pública de inventario para
 * visibilidad entre usuarios.
 *
 * @property baseDatos Instancia de [FirebaseFirestore] utilizada para acceder a las colecciones.
 * @property autenticacion Instancia de [FirebaseAuth] para obtener el identificador del usuario activo.
 */
class ProductoRepository(
    private val baseDatos: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val autenticacion: FirebaseAuth = FirebaseAuth.getInstance(),
) {

    /**
     * Resultado de una operación de guardado de producto.
     *
     * @property exitoso Indica si la operación se completó sin errores.
     * @property idProducto Identificador del producto guardado, presente solo en operaciones exitosas.
     * @property mensaje Descripción del resultado o del error ocurrido.
     */
    data class ResultadoGuardar(
        val exitoso: Boolean,
        val idProducto: String? = null,
        val mensaje: String? = null,
    )

    /**
     * Guarda un nuevo producto en el inventario privado del usuario y lo sincroniza
     * con el inventario público.
     *
     * Valida que exista una sesión activa, que el nombre no esté en blanco y que el
     * precio sea mayor a cero. Genera automáticamente un campo de nombre normalizado
     * para facilitar las búsquedas.
     *
     * @param nombre Nombre del producto. No puede estar en blanco.
     * @param categoria Categoría a la que pertenece el producto.
     * @param precio Precio unitario del producto. Debe ser mayor a cero.
     * @param unidadPrecio Unidad de medida del precio (por ejemplo, "unidad" o "kilo").
     * @param descripcion Descripción opcional del producto.
     * @return [ResultadoGuardar] indicando el éxito o fracaso de la operación.
     */
    suspend fun guardar(
        nombre: String,
        categoria: String,
        precio: Double,
        unidadPrecio: String = "unidad",
        descripcion: String = "",
    ): ResultadoGuardar {
        val usuario = autenticacion.currentUser
            ?: return ResultadoGuardar(false, mensaje = "No hay sesion activa")

        if (nombre.isBlank()) {
            return ResultadoGuardar(false, mensaje = "El nombre es obligatorio")
        }
        if (precio <= 0) {
            return ResultadoGuardar(false, mensaje = "El precio debe ser mayor a 0")
        }

        return try {
            // Se normalizan los espacios múltiples a un solo espacio
            val nombreLimpio = nombre.trim().replace(Regex("\\s+"), " ")
            val referencia = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(usuario.uid)
                .collection("Inventario")
                .document()

            val producto = hashMapOf(
                "id" to referencia.id,
                "nombre" to nombreLimpio,
                "nombreNormalizado" to normalizar(nombreLimpio),
                "categoria" to categoria,
                "precio" to precio,
                "unidadPrecio" to unidadPrecio,
                "descripcion" to descripcion.trim(),
                "disponible" to true,
                "fechaActualizacion" to System.currentTimeMillis(),
                "origen" to "manual",
                "enOferta" to false,
            )

            referencia.set(producto).await()
            // Se sincroniza la versión pública del producto para visibilidad global
            sincronizarProductoPublico(usuario.uid, referencia.id, nombreLimpio, categoria, precio, unidadPrecio, descripcion)

            ResultadoGuardar(
                exitoso = true,
                idProducto = referencia.id,
                mensaje = "Producto guardado",
            )
        } catch (e: Exception) {
            ResultadoGuardar(false, mensaje = "Error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Sincroniza los datos del producto con la colección pública de inventario.
     *
     * Incluye información adicional del vendedor como el nombre del almacén y sus
     * coordenadas geográficas, obtenidas del documento de usuario. Los errores de
     * sincronización se silencian para no afectar el guardado principal.
     *
     * @param uid Identificador del usuario propietario del producto.
     * @param productoId Identificador del documento del producto en el inventario privado.
     * @param nombre Nombre del producto.
     * @param categoria Categoría del producto.
     * @param precio Precio del producto.
     * @param unidadPrecio Unidad de medida del precio.
     * @param descripcion Descripción del producto.
     */
    private suspend fun sincronizarProductoPublico(
        uid: String,
        productoId: String,
        nombre: String,
        categoria: String,
        precio: Double,
        unidadPrecio: String,
        descripcion: String,
    ) {
        // Se obtiene el documento del usuario para extraer nombre del almacén y ubicación
        val documentoAlmacen = try {
            baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(uid)
                .get()
                .await()
        } catch (e: Exception) {
            null
        }

        // Se prioriza el nombre del almacén; si no existe, se usa el nombre del usuario
        val nombreAlmacen = documentoAlmacen?.getString("nombreAlmacen")
            ?.takeIf { it.isNotBlank() }
            ?: documentoAlmacen?.getString("nombre")?.takeIf { it.isNotBlank() }
            ?: "Almacen"

        // El ID público combina el UID del vendedor y el ID del producto para garantizar unicidad
        val idPublico = "${uid}_$productoId"
        val datosPublicos = hashMapOf(
            "vendedorId" to uid,
            "productoId" to productoId,
            "nombre" to nombre,
            "nombreNormalizado" to normalizar(nombre),
            "categoria" to categoria,
            "precio" to precio,
            "unidadPrecio" to unidadPrecio,
            "descripcion" to descripcion,
            "disponible" to true,
            "fechaActualizacion" to System.currentTimeMillis(),
            "nombreAlmacen" to nombreAlmacen,
            "latitud" to documentoAlmacen?.getDouble("latitud"),
            "longitud" to documentoAlmacen?.getDouble("longitud"),
            "enOferta" to false,
        ).filterValues { it != null }

        try {
            baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
                .document(idPublico)
                .set(datosPublicos, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            // Silenciar error de sync, el producto ya se guardo
        }
    }

    /**
     * Normaliza un texto para facilitar las búsquedas insensibles a mayúsculas y acentos.
     *
     * Convierte a minúsculas, elimina caracteres no alfanuméricos (excepto espacios)
     * y colapsa espacios múltiples en uno solo.
     *
     * @param texto Cadena de texto a normalizar.
     * @return Texto normalizado en minúsculas, sin caracteres especiales ni espacios redundantes.
     */
    private fun normalizar(texto: String): String {
        return texto.lowercase(Locale.forLanguageTag("es-CL"))
            .replace(Regex("[^a-z0-9áéíóúñü\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
