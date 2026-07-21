package com.example.rutaalmacen.entrada.voz

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modelo de vista para el asistente de entrada por voz.
 *
 * Gestiona el estado reactivo del flujo de dictado por voz: texto reconocido,
 * lista de productos detectados y estado de grabación. Delega el análisis
 * sintáctico del texto a [ParsingVoz] mediante corrutinas en el hilo de E/S.
 *
 * @param application Instancia de la aplicación Android proporcionada por el framework.
 */
class VozAsistenteViewModel(application: Application) : AndroidViewModel(application) {

    private val _texto = MutableLiveData<String>("")
    /** Texto reconocido actualmente por el motor de voz, observable por la interfaz. */
    val texto: LiveData<String> = _texto

    private val _productos = MutableLiveData<List<ProductoDetectado>>(emptyList())
    /** Lista de productos detectados hasta el momento, observable por la interfaz. */
    val productos: LiveData<List<ProductoDetectado>> = _productos

    private val _grabando = MutableLiveData<Boolean>(false)
    /** Estado de grabación activo del reconocimiento de voz, observable por la interfaz. */
    val grabando: LiveData<Boolean> = _grabando

    /**
     * Establece el estado de grabación del asistente de voz.
     *
     * @param valor `true` si el reconocimiento de voz está activo, `false` en caso contrario.
     */
    fun setGrabando(valor: Boolean) { _grabando.value = valor }
    /**
     * Actualiza el texto reconocido actualmente por el motor de voz.
     *
     * @param texto Texto reconocido parcial o completo.
     */
    fun actualizarTexto(texto: String) { _texto.value = texto }
    /**
     * Reemplaza completamente la lista de productos detectados.
     *
     * @param productos Nueva lista de productos que sustituye a la anterior.
     */
    fun actualizarProductos(productos: List<ProductoDetectado>) { _productos.value = productos }

    /**
     * Actualiza los productos existentes que coincidan por identificador.
     *
     * Para cada producto en la lista proporcionada, busca uno existente con el mismo
     * [ProductoDetectado.id] y lo reemplaza. Los productos sin coincidencia se ignoran.
     *
     * @param productos Lista de productos con los datos actualizados.
     */
    fun reemplazarProductos(productos: List<ProductoDetectado>) {
        // Fusiona por ID: solo actualiza productos ya existentes, ignora los nuevos sin coincidencia
        val actuales = _productos.value.orEmpty().toMutableList()
        productos.forEach { nuevo ->
            val idx = actuales.indexOfFirst { it.id == nuevo.id }
            if (idx >= 0) actuales[idx] = nuevo
        }
        _productos.value = actuales
    }

    /**
     * Agrega un nuevo producto al final de la lista actual.
     *
     * @param producto Producto a agregar.
     */
    fun agregarProducto(producto: ProductoDetectado) {
        val lista = _productos.value.orEmpty().toMutableList()
        lista.add(producto)
        _productos.value = lista
    }

    /**
     * Elimina un producto de la lista por su identificador único.
     *
     * @param id Identificador del producto a eliminar.
     */
    fun eliminarProducto(id: Long) {
        val lista = _productos.value.orEmpty().toMutableList()
        lista.removeAll { it.id == id }
        _productos.value = lista
    }

    /**
     * Restablece el estado del modelo de vista, vaciando la lista de productos
     * y el texto reconocido.
     */
    fun limpiar() {
        _productos.value = emptyList()
        _texto.value = ""
    }

    /**
     * Procesa el texto dictado mediante [ParsingVoz] y agrega los productos
     * resultantes a la lista actual.
     *
     * El análisis sintáctico se ejecuta en un hilo de E/S para no bloquear
     * el hilo principal de la interfaz.
     *
     * @param texto Texto a analizar y convertir en productos detectados.
     */
    fun procesar(texto: String) {
        _texto.value = texto
        viewModelScope.launch {
            // El análisis sintáctico se ejecuta en hilo de E/S para no bloquear la interfaz
            val resultado = withContext(Dispatchers.Default) { ParsingVoz.parsear(texto) }
            val actuales = _productos.value.orEmpty()
            _productos.value = actuales + resultado.productos
        }
    }

    /**
     * Detiene el estado de grabación, estableciendo [grabando] a `false`.
     */
    fun detener() {
        _grabando.value = false
    }

    /**
     * Obtiene una copia inmutable de la lista actual de productos detectados.
     *
     * @return Lista de productos detectados, o lista vacía si no hay ninguno.
     */
    fun obtenerProductos(): List<ProductoDetectado> = _productos.value.orEmpty()
}
