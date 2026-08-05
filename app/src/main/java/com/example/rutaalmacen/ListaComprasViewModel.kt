package com.example.rutaalmacen

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ListaComprasViewModel(application: Application) : AndroidViewModel(application) {

    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val _listaProductos = MutableLiveData<List<String>>(emptyList())
    val listaProductos: LiveData<List<String>> = _listaProductos

    private val _resultados = MutableLiveData<List<ResultadoListaCompras>>(emptyList())
    val resultados: LiveData<List<ResultadoListaCompras>> = _resultados

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _mensajeError = MutableLiveData<String?>()
    val mensajeError: LiveData<String?> = _mensajeError

    private var inventarioCache: List<DocumentSnapshot>? = null
    private val cacheUsuarios: MutableMap<String, DocumentSnapshot> = mutableMapOf()

    fun addProductToList(productName: String) {
        val nombreLimpio = productName.trim()
        if (nombreLimpio.isBlank()) {
            _mensajeError.value = "Escribe el nombre del producto"
            return
        }
        val validacion = FiltroContenido.validarNombreProducto(nombreLimpio)
        if (!validacion.esValido) {
            _mensajeError.value = validacion.mensaje
            return
        }
        val normalizado = FiltroContenido.normalizar(nombreLimpio)
        val duplicado = _listaProductos.value?.any {
            FiltroContenido.normalizar(it) == normalizado
        } ?: false
        if (duplicado) {
            _mensajeError.value = "Este producto ya está en la lista"
            return
        }
        val listaActual = _listaProductos.value.orEmpty().toMutableList()
        listaActual.add(nombreLimpio)
        _listaProductos.value = listaActual
    }

    fun removeProductFromList(productName: String) {
        val listaActual = _listaProductos.value.orEmpty().toMutableList()
        listaActual.remove(productName)
        _listaProductos.value = listaActual
    }

    fun searchStoresForList(ubicacionComprador: Location?) {
        val lista = _listaProductos.value
        if (lista.isNullOrEmpty()) {
            _mensajeError.value = "Agrega al menos un producto a la lista"
            return
        }
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val resultados = buscarAlmacenesParaLista(lista, ubicacionComprador)
                _resultados.value = resultados
                notificarVendedoresProductosFaltantes(resultados, ubicacionComprador)
            } catch (_: Exception) {
                _mensajeError.value = "No se pudo completar la búsqueda"
                _resultados.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun buscarAlmacenesParaLista(
        lista: List<String>,
        ubicacionComprador: Location?,
    ): List<ResultadoListaCompras> = withContext(Dispatchers.IO) {
        val documentosInventario = obtenerInventarioPublico()
        val totalProductos = lista.size

        val almacenesMap = mutableMapOf<String, AlmacenTemp>()

        for (productoNombre in lista) {
            val normalizado = FiltroContenido.normalizar(productoNombre)
            val documentosCoincidentes = documentosInventario.filter { doc ->
                val nombreDoc = doc.getString("nombre").orEmpty()
                val normalizadoDoc = doc.getString("nombreNormalizado")
                    ?.takeIf { s -> s.isNotBlank() }
                    ?: FiltroContenido.normalizar(nombreDoc)
                normalizadoDoc.startsWith(normalizado)
            }

            val vendedoresConProducto = documentosCoincidentes.mapNotNull { doc ->
                doc.getString("vendedorId")?.takeIf { it.isNotBlank() }
            }.toSet()

            val documentosPorVendedor = documentosCoincidentes.groupBy {
                it.getString("vendedorId").orEmpty()
            }

            for ((vendedorId, docs) in documentosPorVendedor) {
                if (vendedorId.isBlank()) continue
                val almacen = almacenesMap.getOrPut(vendedorId) {
                    val documentoUsuario = cacheUsuarios[vendedorId]
                        ?: try {
                            baseDatos.collection(Constantes.COLECCION_USUARIOS)
                                .document(vendedorId)
                                .get()
                                .await()
                                .also { cacheUsuarios[vendedorId] = it }
                        } catch (_: Exception) {
                            null
                        }

                    val nombreAlmacen = documentoUsuario?.getString("nombreAlmacen")
                        ?: documentoUsuario?.getString("nombre")
                        ?: "Almacén sin nombre"
                    val latitud = documentoUsuario?.getDouble("latitud")
                    val longitud = documentoUsuario?.getDouble("longitud")
                    val horarioMananaInicio = documentoUsuario?.getString("horarioMananaInicio")
                        ?: HorarioUtil.HORARIO_MANANA_INICIO_TEXTO
                    val horarioMananaFin = documentoUsuario?.getString("horarioMananaFin")
                        ?: HorarioUtil.HORARIO_MANANA_FIN_TEXTO
                    val horarioTardeInicio = documentoUsuario?.getString("horarioTardeInicio")
                        ?: HorarioUtil.HORARIO_TARDE_INICIO_TEXTO
                    val horarioTardeFin = documentoUsuario?.getString("horarioTardeFin")
                        ?: HorarioUtil.HORARIO_TARDE_FIN_TEXTO
                    val horarioAtencion = documentoUsuario?.getString("horarioAtencion")
                        ?: "$horarioMananaInicio - $horarioMananaFin / $horarioTardeInicio - $horarioTardeFin"
                    val abiertoAhora = HorarioUtil.estaAlmacenAbiertoAhora(
                        horarioMananaInicio, horarioMananaFin,
                        horarioTardeInicio, horarioTardeFin,
                    )
                    val distancia = if (ubicacionComprador != null && latitud != null && longitud != null) {
                        UbicacionUtil.calcularDistancia(
                            ubicacionComprador.latitude, ubicacionComprador.longitude,
                            latitud, longitud,
                        )
                    } else {
                        null
                    }

                    AlmacenTemp(
                        vendedorId = vendedorId,
                        nombreAlmacen = nombreAlmacen,
                        horarioAtencion = horarioAtencion,
                        abiertoAhora = abiertoAhora,
                        latitud = latitud,
                        longitud = longitud,
                        distanciaMetros = distancia,
                    )
                }

                val mejorDoc = docs.firstOrNull()
                val nombreProducto = mejorDoc?.getString("nombre") ?: productoNombre
                if (!almacen.productosEncontrados.contains(nombreProducto)) {
                    almacen.productosEncontrados.add(nombreProducto)
                }
            }

            for (vendedorId in vendedoresConProducto) {
                almacenesMap[vendedorId]?.productosEncontrados?.let { encontrados ->
                    val yaRegistrado = encontrados.any {
                        FiltroContenido.normalizar(it) == normalizado
                    }
                    if (!yaRegistrado) {
                        val doc = documentosCoincidentes.firstOrNull {
                            it.getString("vendedorId") == vendedorId
                        }
                        encontrados.add(doc?.getString("nombre") ?: productoNombre)
                    }
                }
            }
        }

        val listaNormalizada = lista.map { FiltroContenido.normalizar(it) }

        almacenesMap.values.forEach { almacen ->
            val encontradosNormalizados = almacen.productosEncontrados
                .map { FiltroContenido.normalizar(it) }
                .toSet()
            val faltantes = lista.filter { nombre ->
                val norm = FiltroContenido.normalizar(nombre)
                norm !in encontradosNormalizados
            }
            almacen.productosFaltantes.addAll(faltantes)
        }

        almacenesMap.values.map { almacen ->
            val productosEncontrados = almacen.productosEncontrados.distinct()
            val productosFaltantes = almacen.productosFaltantes.distinct()
            val total = productosEncontrados.size + productosFaltantes.size
            val porcentaje = if (total > 0) {
                (productosEncontrados.size.toDouble() / total) * 100.0
            } else {
                0.0
            }

            ResultadoListaCompras(
                almacen = AlmacenConStock(
                    vendedorId = almacen.vendedorId,
                    nombreAlmacen = almacen.nombreAlmacen,
                    horarioAtencion = almacen.horarioAtencion,
                    abiertoAhora = almacen.abiertoAhora,
                    latitud = almacen.latitud,
                    longitud = almacen.longitud,
                    distanciaMetros = almacen.distanciaMetros,
                    productosEncontrados = productosEncontrados,
                    productosFaltantes = productosFaltantes,
                ),
                porcentajeCoincidencia = porcentaje,
                totalProductosLista = total,
            )
        }.sortedWith(
            compareByDescending<ResultadoListaCompras> { it.porcentajeCoincidencia }
                .thenBy { it.almacen.distanciaMetros ?: Double.MAX_VALUE },
        )
    }

    private suspend fun obtenerInventarioPublico(): List<DocumentSnapshot> {
        inventarioCache?.let { return it }
        val cacheFirestore = try {
            baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
                .get(Source.CACHE).await().documents
        } catch (_: Exception) { emptyList() }
        if (cacheFirestore.isNotEmpty()) {
            inventarioCache = cacheFirestore
            return cacheFirestore
        }
        val resultado = baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
            .get().await()
        return resultado.documents.also { inventarioCache = it }
    }

    fun clearResultados() {
        _resultados.value = emptyList()
    }

    fun clearError() {
        _mensajeError.value = null
    }

    private suspend fun notificarVendedoresProductosFaltantes(
        resultados: List<ResultadoListaCompras>,
        ubicacionComprador: Location?,
    ) {
        val autenticacion = FirebaseAuth.getInstance()
        val usuarioId = autenticacion.currentUser?.uid ?: return
        if (usuarioId.isBlank()) return

        val documentoUsuario = baseDatos.collection(Constantes.COLECCION_USUARIOS)
            .document(usuarioId)
            .get()
            .await()
        val rolUsuario = documentoUsuario.getString("rol").orEmpty()
        if (rolUsuario != Constantes.ROL_COMPRADOR) return

        val fechaCreacion = System.currentTimeMillis()

        for (resultado in resultados) {
            val productosFaltantes = resultado.almacen.productosFaltantes
            if (productosFaltantes.isEmpty()) continue

            val vendedorId = resultado.almacen.vendedorId
            if (vendedorId.isBlank()) continue

            val productosTexto = productosFaltantes.joinToString(", ")
            val cantidadFaltantes = productosFaltantes.size
            val mensaje = if (cantidadFaltantes == 1) {
                "¡Oportunidad! Un comprador busca $productosTexto y no lo encontró en tu stock. ¡Súbelo ahora!"
            } else {
                "¡Oportunidad! Un comprador busca $cantidadFaltantes productos que no tienes: $productosTexto. ¡Actualiza tu stock!"
            }

            val latitudCentro = ubicacionComprador?.latitude ?: resultado.almacen.latitud
            val longitudCentro = ubicacionComprador?.longitude ?: resultado.almacen.longitud
            val radioMetros = 5000.0

            val productoId = productosFaltantes.first().lowercase().replace(" ", "_")
            val idAlerta = "${productoId}_${vendedorId}_$fechaCreacion"

            val datos = mapOf(
                "producto" to productosTexto,
                "productoNormalizado" to productosTexto.lowercase(),
                "vendedorId" to vendedorId,
                "compradorId" to usuarioId,
                "mensaje" to mensaje,
                "latitudCentro" to latitudCentro,
                "longitudCentro" to longitudCentro,
                "radioMetros" to radioMetros,
                "fechaCreacion" to fechaCreacion,
                "totalBusquedas" to 1,
                "productosFaltantes" to productosFaltantes,
            )

            try {
                baseDatos.collection(Constantes.COLECCION_NOTIFICACIONES_IA)
                    .document(idAlerta)
                    .set(datos)
                    .await()
            } catch (_: Exception) {
            }
        }
    }

    private class AlmacenTemp(
        val vendedorId: String,
        val nombreAlmacen: String,
        val horarioAtencion: String,
        val abiertoAhora: Boolean,
        val latitud: Double?,
        val longitud: Double?,
        val distanciaMetros: Double?,
        val productosEncontrados: MutableList<String> = mutableListOf(),
        val productosFaltantes: MutableList<String> = mutableListOf(),
    )
}
