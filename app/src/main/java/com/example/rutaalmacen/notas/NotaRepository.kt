package com.example.rutaalmacen.notas

import com.example.rutaalmacen.Constantes
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * Repositorio responsable de las operaciones CRUD de notas contra Firestore.
 *
 * Cada usuario posee una subcolección de notas aislada bajo su documento en la
 * colección de usuarios. Todas las operaciones son suspendidas y seguras frente
 * a la ausencia de sesión activa.
 *
 * @property baseDatos Instancia de [FirebaseFirestore] utilizada para acceder a las colecciones.
 * @property autenticacion Instancia de [FirebaseAuth] para obtener el identificador del usuario activo.
 */
class NotaRepository(
    private val baseDatos: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val autenticacion: FirebaseAuth = FirebaseAuth.getInstance(),
) {

    /**
     * Obtiene la referencia a la subcolección de notas del usuario autenticado.
     *
     * @return Referencia a la subcolección de notas, o `null` si no existe un usuario autenticado.
     */
    private fun coleccionNotas() = autenticacion.currentUser?.uid?.let { uid ->
        baseDatos.collection(Constantes.COLECCION_USUARIOS)
            .document(uid)
            .collection(Constantes.SUBCOLECCION_NOTAS)
    }

    /**
     * Recupera la totalidad de notas del usuario autenticado, ordenadas por fecha descendente.
     *
     * @return Lista de [Nota] ordenada desde la más reciente. Retorna una lista vacía
     *         si no hay sesión activa o si ocurre algún error de lectura.
     */
    suspend fun obtenerTodas(): List<Nota> {
        val coleccion = coleccionNotas() ?: return emptyList()
        return try {
            val resultado = coleccion
                .orderBy("fechaLong", Query.Direction.DESCENDING)
                .get()
                .await()
            // Se asigna el ID del documento al campo id del modelo
            resultado.documents.mapNotNull { documento ->
                val base = documento.toObject(Nota::class.java) ?: return@mapNotNull null
                base.copy(id = documento.id)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Busca notas cuyo título o contenido contengan el texto de consulta.
     *
     * La búsqueda se realiza sobre el conjunto completo de notas del usuario,
     * aplicando comparación insensible a mayúsculas y configuración regional chilena.
     *
     * @param consulta Texto a buscar. Si está en blanco, se devuelven todas las notas.
     * @return Lista de [Nota] que coinciden con el criterio de búsqueda.
     */
    suspend fun buscarPorTexto(consulta: String): List<Nota> {
        val texto = consulta.trim().lowercase(Locale.forLanguageTag("es-CL"))
        if (texto.isBlank()) {
            return obtenerTodas()
        }
        return obtenerTodas().filter { nota ->
            nota.titulo.lowercase(Locale.forLanguageTag("es-CL")).contains(texto) ||
                nota.contenido.lowercase(Locale.forLanguageTag("es-CL")).contains(texto)
        }
    }

    /**
     * Guarda una nota nueva o actualiza una existente en Firestore.
     *
     * Si el campo [Nota.id] está vacío se crea un documento nuevo; de lo contrario
     * se actualiza el documento existente mediante fusión parcial ([SetOptions.merge]).
     * La marca de tiempo se actualiza automáticamente al momento de guardar.
     *
     * @param nota Objeto [Nota] con los datos a persistir.
     * @return [Resultado] indicando el éxito o fracaso de la operación,
     *         incluyendo el ID del documento en caso de éxito.
     */
    suspend fun guardar(nota: Nota): Resultado {
        val coleccion = coleccionNotas()
            ?: return Resultado(exitoso = false, mensaje = "No hay sesión activa")
        if (nota.titulo.isBlank() && nota.contenido.isBlank()) {
            return Resultado(exitoso = false, mensaje = "La nota no puede estar vacía")
        }
        return try {
            val ahora = System.currentTimeMillis()
            // Si el ID está vacío se genera un nuevo documento; si no, se reutiliza el existente
            val referencia = if (nota.id.isBlank()) {
                coleccion.document()
            } else {
                coleccion.document(nota.id)
            }
            val datos = mapOf(
                "titulo" to nota.titulo.trim(),
                "contenido" to nota.contenido.trim(),
                "fechaLong" to ahora,
                "colorHex" to nota.colorHex,
            )
            referencia.set(datos, SetOptions.merge()).await()
            Resultado(exitoso = true, id = referencia.id)
        } catch (excepcion: Exception) {
            Resultado(exitoso = false, mensaje = excepcion.message ?: "Error al guardar")
        }
    }

    /**
     * Elimina una nota del repositorio Firestore.
     *
     * @param notaId Identificador del documento a eliminar.
     * @return [Resultado] indicando el éxito o fracaso de la operación.
     */
    suspend fun eliminar(notaId: String): Resultado {
        val coleccion = coleccionNotas()
            ?: return Resultado(exitoso = false, mensaje = "No hay sesión activa")
        if (notaId.isBlank()) {
            return Resultado(exitoso = false, mensaje = "Identificador inválido")
        }
        return try {
            coleccion.document(notaId).delete().await()
            Resultado(exitoso = true, id = notaId)
        } catch (excepcion: Exception) {
            Resultado(exitoso = false, mensaje = excepcion.message ?: "Error al eliminar")
        }
    }

    /**
     * Representa el resultado de una operación de escritura sobre el repositorio de notas.
     *
     * @property exitoso Indica si la operación se completó sin errores.
     * @property id Identificador del documento afectado, presente solo en operaciones exitosas.
     * @property mensaje Descripción del error en caso de fallo, o `null` si la operación fue exitosa.
     */
    data class Resultado(
        val exitoso: Boolean,
        val id: String? = null,
        val mensaje: String? = null,
    )
}
