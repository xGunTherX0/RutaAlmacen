package com.example.rutaalmacen.entrada.ocr

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel que coordina el flujo completo de reconocimiento OCR de productos.
 *
 * Gestiona el ciclo de vida de las operaciones de análisis de imágenes,
 * la comunicación con [ServicioGeminiOcr], el filtrado de duplicados contra
 * el catálogo existente en Firestore y la caché local, y la publicación
 * de estados reactivos hacia la interfaz de usuario.
 *
 * @param application Instancia de [Application] para acceso al contexto de la aplicación.
 */
class OcrViewModel(application: Application) : AndroidViewModel(application) {

    private val contextoApp: Application get() = getApplication()
    private val servicioGemini = ServicioGeminiOcr(contextoApp)
    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    /** Estado actual del proceso OCR, observable desde la interfaz de usuario. */
    val estado = MutableLiveData<EstadoOcr>(EstadoOcr.Inactivo)

    /** Lista de productos detectados tras el análisis OCR y el filtrado de duplicados. */
    val productosDetectados = MutableLiveData<List<ProductoEscaneado>>(emptyList())

    /** Texto crudo devuelto por el motor OCR, útil para depuración. */
    val textoCrudoOcr = MutableLiveData<String>("")

    /** Cantidad de productos omitidos por ya existir en el catálogo del vendedor. */
    val duplicadosOmitidos = MutableLiveData<Int>(0)

    /**
     * Inicia el análisis OCR de una única imagen.
     *
     * Envía la imagen apuntada por [uri] al servicio Gemini, filtra los productos
     * resultantes contra el catálogo existente y actualiza los LiveData reactivos
     * con el estado, los productos detectados y la cantidad de duplicados omitidos.
     *
     * @param uri URI de contenido que apunta a la imagen a analizar.
     */
    fun procesarImagen(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                estado.value = EstadoOcr.Procesando("Analizando con Gemini...")
            }
            val resultado = servicioGemini.analizarImagen(uri)
            when (resultado) {
                is ServicioGeminiOcr.ResultadoAnalisis.Exito -> {
                    withContext(Dispatchers.Main) {
                        textoCrudoOcr.value = resultado.jsonCrudo
                    }
                    val (filtrados, omitidos) = filtrarDuplicadosContraFirestore(resultado.productos)
                    withContext(Dispatchers.Main) {
                        productosDetectados.value = filtrados
                        duplicadosOmitidos.value = omitidos
                        estado.value = when {
                            filtrados.isEmpty() && omitidos > 0 -> EstadoOcr.Error(
                                "Todos los productos detectados ya están en tu catálogo ($omitidos omitidos).",
                            )
                            filtrados.isEmpty() -> EstadoOcr.Error("No se detectaron productos. Intenta con otra foto.")
                            omitidos > 0 -> EstadoOcr.Listo(
                                "${filtrados.size} nuevo(s) • $omitidos ya en tu catálogo",
                            )
                            else -> EstadoOcr.Listo("${filtrados.size} producto(s) detectado(s)")
                        }
                    }
                }
                is ServicioGeminiOcr.ResultadoAnalisis.Error -> {
                    withContext(Dispatchers.Main) {
                        estado.value = EstadoOcr.Error(resultado.mensaje)
                    }
                }
            }
        }
    }

    /**
     * Analiza secuencialmente múltiples imágenes y consolida los resultados.
     *
     * Procesa cada URI de la lista [uris] una a la vez, acumulando los productos
     * detectados y los registros de texto crudo. Al finalizar, aplica el mismo
     * filtrado de duplicados que [procesarImagen].
     *
     * @param uris Lista de URI de imágenes a analizar.
     */
    fun cargarImagenes(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                estado.value = EstadoOcr.Procesando("Analizando ${uris.size} imagen(es) con Gemini...")
            }
            val acumulado = mutableListOf<ProductoEscaneado>()
            val logs = StringBuilder()
            for ((indice, uri) in uris.withIndex()) {
                withContext(Dispatchers.Main) {
                    estado.value = EstadoOcr.Procesando("Analizando imagen ${indice + 1} de ${uris.size}...")
                }
                val resultado = servicioGemini.analizarImagen(uri)
                if (resultado is ServicioGeminiOcr.ResultadoAnalisis.Exito) {
                    acumulado.addAll(resultado.productos)
                    logs.appendLine("--- Imagen ${indice + 1} ---")
                    logs.appendLine(resultado.jsonCrudo)
                }
            }
            withContext(Dispatchers.Main) {
                textoCrudoOcr.value = logs.toString()
            }
            val (filtrados, omitidos) = filtrarDuplicadosContraFirestore(acumulado)
            withContext(Dispatchers.Main) {
                productosDetectados.value = filtrados
                duplicadosOmitidos.value = omitidos
                estado.value = when {
                    filtrados.isEmpty() && omitidos > 0 -> EstadoOcr.Error(
                        "Todos los productos detectados ya están en tu catálogo ($omitidos omitidos).",
                    )
                    filtrados.isEmpty() -> EstadoOcr.Error("No se detectaron productos. Intenta con otra foto.")
                    omitidos > 0 -> EstadoOcr.Listo(
                        "${filtrados.size} nuevo(s) • $omitidos ya en tu catálogo",
                    )
                    else -> EstadoOcr.Listo("${filtrados.size} producto(s) detectado(s)")
                }
            }
        }
    }

    /**
     * Filtra los productos detectados contra los nombres existentes en Firestore
     * y en la caché local del catálogo.
     *
     * Combina los nombres remotos (obtenidos mediante [ProductoFiltroDuplicados])
     * con los nombres locales (obtenidos mediante [CacheCatalogoLocal]) para
     * determinar qué productos son duplicados.
     *
     * @param productos Lista de productos a evaluar.
     * @return Par con la lista de productos nuevos y la cantidad de duplicados omitidos.
     */
    private suspend fun filtrarDuplicadosContraFirestore(
        productos: List<ProductoEscaneado>,
    ): Pair<List<ProductoEscaneado>, Int> = withContext(Dispatchers.IO) {
        val vendedorId = autenticacion.currentUser?.uid.orEmpty()
        val nombresExistentes = ProductoFiltroDuplicados.obtenerNombresExistentes(vendedorId)
        val cacheLocal = CacheCatalogoLocal.obtener(contextoApp)
            .map { BoletaParser.normalizar(it.nombre) }
            .filter { it.isNotBlank() }
            .toSet()
        val nombresTotales = nombresExistentes + cacheLocal
        Log.d("OcrDebug", "Filtrando ${productos.size} productos contra ${nombresTotales.size} existentes")
        ProductoFiltroDuplicados.filtrarDuplicados(productos, nombresTotales)
    }

    /**
     * Actualiza un producto dentro de la lista de productos detectados.
     *
     * Busca el producto por su [ProductoEscaneado.id] y lo reemplaza con la
     * versión modificada. No tiene efecto si el ID no se encuentra.
     *
     * @param producto Producto con los datos actualizados.
     */
    fun actualizarProducto(producto: ProductoEscaneado) {
        val lista = productosDetectados.value.orEmpty().toMutableList()
        val indice = lista.indexOfFirst { it.id == producto.id }
        if (indice >= 0) {
            lista[indice] = producto
            productosDetectados.value = lista
        }
    }

    /**
     * Elimina un producto de la lista de productos detectados.
     *
     * @param id Identificador único del producto a eliminar.
     */
    fun eliminarProducto(id: Long) {
        val lista = productosDetectados.value.orEmpty().filter { it.id != id }
        productosDetectados.value = lista
    }

    /**
     * Reinicia el estado del ViewModel, limpiando todos los productos detectados
     * y restableciendo el estado a [EstadoOcr.Inactivo].
     */
    fun limpiar() {
        viewModelScope.launch(Dispatchers.Main) {
            productosDetectados.value = emptyList()
            estado.value = EstadoOcr.Inactivo
            duplicadosOmitidos.value = 0
        }
    }

    /**
     * Persiste los productos confirmados en la caché local del catálogo.
     *
     * Para cada producto, si ya existe en el catálogo (por [ProductoEscaneado.idProductoExistente]),
     * actualiza el registro existente; en caso contrario, crea un nuevo registro local
     * con un identificador generado. Omite productos sin nombre o con precio no positivo.
     *
     * @param vendedorId Identificador del vendedor propietario del catálogo.
     * @param productos Lista de productos a persistir.
     * @return Cantidad total de productos en la caché después de la operación.
     */
    suspend fun guardarEnCacheLocal(vendedorId: String, productos: List<ProductoEscaneado>): Int {
        if (productos.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            val actuales = CacheCatalogoLocal.obtener(contextoApp).toMutableList()
            for (producto in productos) {
                val normalizado = BoletaParser.normalizar(producto.nombre)
                if (normalizado.isBlank() || producto.precio <= 0.0) continue
                if (producto.existeEnCatalogo && producto.idProductoExistente != null) {
                    val indice = actuales.indexOfFirst { it.id == producto.idProductoExistente }
                    if (indice >= 0) {
                        actuales[indice] = ProductoLocal(
                            id = producto.idProductoExistente,
                            nombre = producto.nombre,
                            categoria = producto.categoria,
                            precio = producto.precio,
                        )
                    }
                } else {
                    actuales.add(
                        ProductoLocal(
                            id = "local-${System.currentTimeMillis()}-${actuales.size}",
                            nombre = producto.nombre,
                            categoria = producto.categoria,
                            precio = producto.precio,
                        ),
                    )
                }
            }
            CacheCatalogoLocal.guardar(contextoApp, actuales)
            actuales.size
        }
    }

    /**
     * Representa los estados posibles del proceso de reconocimiento OCR.
     *
     * El ciclo de vida típico es: [Inactivo] → [Procesando] → [Listo] o [Error].
     */
    sealed class EstadoOcr {
        /** No hay ninguna operación en curso. */
        object Inactivo : EstadoOcr()

        /**
         * El análisis OCR se encuentra en progreso.
         * @property mensaje Texto descriptivo del paso actual del procesamiento.
         */
        data class Procesando(val mensaje: String) : EstadoOcr()

        /**
         * El análisis finalizó exitosamente y hay productos disponibles.
         * @property mensaje Resumen del resultado (cantidad de productos detectados).
         */
        data class Listo(val mensaje: String) : EstadoOcr()

        /**
         * Ocurrió un error durante el análisis o el filtrado.
         * @property mensaje Descripción del error.
         */
        data class Error(val mensaje: String) : EstadoOcr()
    }
}
