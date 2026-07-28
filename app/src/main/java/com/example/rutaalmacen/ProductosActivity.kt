package com.example.rutaalmacen

import android.Manifest
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rutaalmacen.productos.OfertaUtil
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

class ProductosActivity : AppCompatActivity() {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val proveedorUbicacion: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val resultados: MutableList<ResultadoBusqueda> = mutableListOf()
    private val resultadosBase: MutableList<ResultadoBusqueda> = mutableListOf()
    private lateinit var adaptadorResultados: AdaptadorResultados

    private var busquedaPendiente: String? = null
    private var categoriaPendiente: String? = null
    private var avisoSinUbicacionMostrado = false
    private var categoriaSeleccionada = "Todas"
    private lateinit var contenedorCarga: View
    private val cacheUsuarios: MutableMap<String, DocumentSnapshot> = mutableMapOf()
    private var inventarioPublicoCache: List<DocumentSnapshot>? = null
    private var inventarioPrivadoCache: List<DocumentSnapshot>? = null
    private var ubicacionCache: Location? = null
    private var ubicacionCacheTiempo = 0L

    private val categorias = listOf(
        "Todas",
        "Despensa",
        "Lácteos y Huevos",
        "Cecinas y Quesos",
        "Bebidas y Jugos",
        "Pan y Pastelería",
        "Frutas y Verduras",
        "Snacks y Dulces",
        "Congelados",
        "Aseo Hogar",
        "Higiene Personal",
    )

    private val solicitudPermisoUbicacion = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { resultados ->
        val concedido = resultados[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            resultados[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (concedido) {
            val textoPendiente = busquedaPendiente
            val categoriaPendienteActual = categoriaPendiente
            busquedaPendiente = null
            categoriaPendiente = null
            if (!textoPendiente.isNullOrBlank()) {
                lifecycleScope.launch { buscarProductos(textoPendiente) }
            } else if (!categoriaPendienteActual.isNullOrBlank()) {
                lifecycleScope.launch { buscarProductosPorCategoria(categoriaPendienteActual) }
            }
        } else {
            mostrarMensaje("Permiso de ubicación denegado")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_productos)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_productos)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                barrasDelSistema.top,
                barrasDelSistema.right,
                0,
            )
            insets
        }

        val campoBusqueda = findViewById<TextInputEditText>(R.id.campo_busqueda_producto)
        val recyclerResultados = findViewById<RecyclerView>(R.id.recycler_resultados)
        val campoCategoria = findViewById<AutoCompleteTextView>(R.id.campo_categoria)
        contenedorCarga = findViewById(R.id.contenedor_carga_productos)

        adaptadorResultados = AdaptadorResultados(
            resultados = resultados,
            onLlegar = { resultado -> abrirNavegacion(resultado) },
            onVerStock = { resultado -> abrirStockAlmacen(resultado) },
        )
        recyclerResultados.layoutManager = LinearLayoutManager(this)
        recyclerResultados.adapter = adaptadorResultados

        configurarFiltros(campoCategoria)
        configurarBusqueda(campoBusqueda)
        configurarBotonVolver()
        configurarBotonAlertas()

        lifecycleScope.launch { cargarProductosIniciales() }
    }

    private fun configurarBotonVolver() {
        findViewById<ImageView>(R.id.boton_volver).setOnClickListener {
            finish()
        }
    }

    private fun configurarBotonAlertas() {
        findViewById<MaterialButton>(R.id.boton_info_alertas).setOnClickListener {
            startActivity(Intent(this, InfoAlertasActivity::class.java))
        }
    }

    private fun configurarBusqueda(campoBusqueda: TextInputEditText) {
        findViewById<ImageView>(R.id.icono_busqueda).setOnClickListener {
            ejecutarBusquedaManual(campoBusqueda.text?.toString().orEmpty())
        }
        campoBusqueda.setOnEditorActionListener { _, _, _ ->
            ejecutarBusquedaManual(campoBusqueda.text?.toString().orEmpty())
            true
        }
    }

    private fun configurarFiltros(campoCategoria: AutoCompleteTextView) {
        val adaptadorCategorias = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            categorias,
        )
        campoCategoria.setAdapter(adaptadorCategorias)
        campoCategoria.setText(categoriaSeleccionada, false)
        campoCategoria.setOnItemClickListener { _, _, posicion, _ ->
            categoriaSeleccionada = categorias.getOrNull(posicion) ?: "Todas"
            aplicarFiltros()
        }
    }

    private fun ejecutarBusquedaManual(consulta: String) {
        ocultarTeclado()
        lifecycleScope.launch {
            val texto = consulta.trim()
            if (texto.isBlank()) {
                if (categoriaSeleccionada == "Todas") {
                    cargarProductosIniciales()
                    return@launch
                }
                buscarProductosPorCategoria(categoriaSeleccionada)
                return@launch
            }
            buscarProductos(texto)
        }
    }

    private fun ocultarTeclado() {
        val vista = currentFocus ?: View(this)
        val gestor = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        gestor.hideSoftInputFromWindow(vista.windowToken, 0)
        vista.clearFocus()
    }

    private suspend fun buscarProductos(consulta: String) {
        val usuario = autenticacion.currentUser
        if (usuario == null) {
            mostrarMensaje("No hay un usuario activo")
            return
        }

        if (!UbicacionUtil.tienePermisoUbicacion(this)) {
            busquedaPendiente = consulta
            solicitudPermisoUbicacion.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            return
        }

        mostrarCarga(true)
        try {
            val ubicacionComprador = obtenerUbicacionParaAlerta()

            val consultaNormalizada = FiltroContenido.normalizar(consulta)
            val documentos = linkedMapOf<String, DocumentSnapshot>()

            val documentosLocal = buscarInventarioLocalPublico(consultaNormalizada)
            documentosLocal.forEach { documento ->
                documentos[documento.reference.path] = documento
            }

            if (documentos.isEmpty()) {
                val documentosPrivados = buscarInventarioLocalPrivado(consultaNormalizada)
                documentosPrivados.forEach { documento ->
                    documentos[documento.reference.path] = documento
                }
            }

            notificarVendedoresSinProducto(
                producto = consulta,
                documentosConProducto = documentos.values,
                ubicacionComprador = ubicacionComprador,
                esCategoria = false,
            )

            if (documentos.isEmpty()) {
                registrarBusquedaFallida(consulta, ubicacionComprador)
                resultadosBase.clear()
                resultados.clear()
                adaptadorResultados.notifyDataSetChanged()
                mostrarMensaje("No se encontraron productos")
                return
            }

            val nuevosResultados = construirResultados(documentos.values, ubicacionComprador)
            resultadosBase.clear()
            resultadosBase.addAll(nuevosResultados)
            aplicarFiltros()
        } catch (_: Exception) {
            mostrarMensaje("No se pudo completar la búsqueda")
        } finally {
            mostrarCarga(false)
        }
    }

    private suspend fun cargarProductosIniciales() {
        val permisoUbicacion = UbicacionUtil.tienePermisoUbicacion(this)
        val ubicacionRapida = if (permisoUbicacion) obtenerUbicacionCacheada() else null
        val documentosCache = try {
            obtenerInventarioPublicoCache()
        } catch (_: Exception) {
            emptyList()
        }
        val hayCache = documentosCache.isNotEmpty()
        if (!hayCache) {
            mostrarCarga(true)
        } else {
            actualizarResultados(documentosCache, ubicacionRapida)
        }
        try {
            val documentosServidor = obtenerInventarioPublicoServidor()
            if (documentosServidor.isNotEmpty()) {
                val ubicacionFinal = if (permisoUbicacion) {
                    obtenerUbicacionCacheada() ?: obtenerUbicacionActual()
                } else {
                    null
                }
                if (permisoUbicacion) {
                    if (ubicacionFinal == null) {
                        if (!avisoSinUbicacionMostrado) {
                            mostrarMensaje("No se pudo obtener la ubicación, se mostrará sin distancia")
                            avisoSinUbicacionMostrado = true
                        }
                    } else {
                        avisoSinUbicacionMostrado = false
                    }
                }
                actualizarResultados(documentosServidor, ubicacionFinal)
            } else if (!hayCache) {
                resultadosBase.clear()
                resultados.clear()
                adaptadorResultados.notifyDataSetChanged()
            }
        } catch (_: Exception) {
            if (!hayCache) {
                mostrarMensaje("No se pudieron cargar los productos")
            }
        } finally {
            mostrarCarga(false)
        }
    }

    private suspend fun buscarProductosPorCategoria(categoria: String) {
        val usuario = autenticacion.currentUser
        if (usuario == null) {
            mostrarMensaje("No hay un usuario activo")
            return
        }

        if (!UbicacionUtil.tienePermisoUbicacion(this)) {
            categoriaPendiente = categoria
            solicitudPermisoUbicacion.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            return
        }

        mostrarCarga(true)
        try {
            val ubicacionComprador = obtenerUbicacionCacheada()

            val documentos = buscarInventarioLocalPublicoPorCategoria(categoria)

            notificarVendedoresSinProducto(
                producto = categoria,
                documentosConProducto = documentos,
                ubicacionComprador = ubicacionComprador,
                esCategoria = true,
            )

            if (documentos.isEmpty()) {
                resultadosBase.clear()
                resultados.clear()
                adaptadorResultados.notifyDataSetChanged()
                mostrarMensaje("No se encontraron productos")
                return
            }

            val nuevosResultados = construirResultados(documentos, ubicacionComprador)
            resultadosBase.clear()
            resultadosBase.addAll(nuevosResultados)
            aplicarFiltros()
        } catch (_: Exception) {
            mostrarMensaje("No se pudo completar la búsqueda")
        } finally {
            mostrarCarga(false)
        }
    }

    private suspend fun construirResultados(
        documentos: Collection<DocumentSnapshot>,
        ubicacionComprador: Location?,
    ): List<ResultadoBusqueda> {
        val nuevosResultados = mutableListOf<ResultadoBusqueda>()
        val ofertasExpiradas = mutableListOf<Pair<String, String>>()

        for (documento in documentos) {
            val vendedorId = documento.getString("vendedorId").orEmpty()
            val productoId = documento.getString("productoId").orEmpty()
            val documentoUsuario = if (vendedorId.isNotBlank()) {
                cacheUsuarios[vendedorId]
                    ?: baseDatos.collection(Constantes.COLECCION_USUARIOS)
                        .document(vendedorId)
                        .get()
                        .await()
                        .also { cacheUsuarios[vendedorId] = it }
            } else {
                null
            }

            val nombreProducto = documento.getString("nombre").orEmpty()
            val descripcionProducto = documento.getString("descripcion").orEmpty()
            val categoriaProducto = documento.getString("categoria").orEmpty()
            val precioProducto = documento.getDouble("precio")
                ?: documento.getLong("precio")?.toDouble()
                ?: 0.0
            val unidadPrecio = documento.getString("unidadPrecio").orEmpty().ifBlank { "unidad" }
            val disponibleProducto = documento.getBoolean("disponible") ?: true
            val datosOferta = OfertaUtil.leerProducto(documento)
            val ofertaVigente = OfertaUtil.estaVigente(datosOferta.enOferta, datosOferta.fechaFinOferta)
            if (datosOferta.enOferta && !ofertaVigente && vendedorId.isNotBlank() && productoId.isNotBlank()) {
                ofertasExpiradas.add(vendedorId to productoId)
            }
            val nombreAlmacen = documento.getString("nombreAlmacen")
                ?: documentoUsuario?.getString("nombreAlmacen")
                ?: documentoUsuario?.getString("nombre")
                ?: "Almacén sin nombre"
            val latitudAlmacen = documento.getDouble("latitud")
                ?: documentoUsuario?.getDouble("latitud")
            val longitudAlmacen = documento.getDouble("longitud")
                ?: documentoUsuario?.getDouble("longitud")

            val horarioMananaInicio = documento.getString("horarioMananaInicio")
                ?: HorarioUtil.HORARIO_MANANA_INICIO_TEXTO
            val horarioMananaFin = documento.getString("horarioMananaFin")
                ?: HorarioUtil.HORARIO_MANANA_FIN_TEXTO
            val horarioTardeInicio = documento.getString("horarioTardeInicio")
                ?: HorarioUtil.HORARIO_TARDE_INICIO_TEXTO
            val horarioTardeFin = documento.getString("horarioTardeFin")
                ?: HorarioUtil.HORARIO_TARDE_FIN_TEXTO
            val horarioAtencion = documento.getString("horarioAtencion")
                ?: "$horarioMananaInicio - $horarioMananaFin / $horarioTardeInicio - $horarioTardeFin"
            val abiertoAhora = HorarioUtil.estaAlmacenAbiertoAhora(
                horarioMananaInicio,
                horarioMananaFin,
                horarioTardeInicio,
                horarioTardeFin,
            )
            val distancia = if (
                ubicacionComprador != null &&
                latitudAlmacen != null &&
                longitudAlmacen != null
            ) {
                UbicacionUtil.calcularDistancia(
                    ubicacionComprador.latitude,
                    ubicacionComprador.longitude,
                    latitudAlmacen,
                    longitudAlmacen,
                )
            } else {
                null
            }

            nuevosResultados.add(
                ResultadoBusqueda(
                    nombreProducto = nombreProducto,
                    precio = precioProducto,
                    unidadPrecio = unidadPrecio,
                    descripcion = descripcionProducto,
                    categoria = categoriaProducto,
                    vendedorId = vendedorId,
                    productoId = productoId,
                    nombreAlmacen = nombreAlmacen,
                    horarioAtencion = horarioAtencion,
                    abiertoAhora = abiertoAhora,
                    distanciaMetros = distancia,
                    latitudAlmacen = latitudAlmacen,
                    longitudAlmacen = longitudAlmacen,
                    disponible = disponibleProducto,
                    enOferta = ofertaVigente,
                    precioOferta = if (ofertaVigente) datosOferta.precioOferta else null,
                    descuentoPorcentaje = if (ofertaVigente) datosOferta.descuentoPorcentaje else null,
                    fechaFinOferta = if (ofertaVigente) datosOferta.fechaFinOferta else null,
                ),
            )
        }

        if (ofertasExpiradas.isNotEmpty()) {
            lifecycleScope.launch { resetearOfertasExpiradas(ofertasExpiradas) }
        }

        return nuevosResultados
    }

    private suspend fun resetearOfertasExpiradas(ofertas: List<Pair<String, String>>) {
        val ahora = System.currentTimeMillis()
        val datosReset = mapOf(
            "enOferta" to false,
            "precioOferta" to null,
            "descuentoPorcentaje" to null,
            "fechaFinOferta" to null,
            "fechaActualizacion" to ahora,
        )
        val uidActual = autenticacion.currentUser?.uid
        ofertas.forEach { (vendedorId, productoId) ->
            if (vendedorId.isBlank() || productoId.isBlank()) return@forEach
            val docPublicoId = "${vendedorId}_$productoId"
            try {
                baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
                    .document(docPublicoId)
                    .set(datosReset, SetOptions.merge())
                    .await()
            } catch (_: Exception) {
            }
            if (uidActual != null && uidActual == vendedorId) {
                try {
                    baseDatos.collection(Constantes.COLECCION_USUARIOS)
                        .document(vendedorId)
                        .collection("Inventario")
                        .document(productoId)
                        .set(datosReset, SetOptions.merge())
                        .await()
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun actualizarResultados(
        documentos: Collection<DocumentSnapshot>,
        ubicacionComprador: Location?,
    ) {
        val nuevosResultados = construirResultados(documentos, ubicacionComprador)
        resultadosBase.clear()
        resultadosBase.addAll(nuevosResultados)
        aplicarFiltros()
    }

    private fun mostrarCarga(mostrar: Boolean) {
        contenedorCarga.visibility = if (mostrar) View.VISIBLE else View.GONE
    }

    private fun aplicarFiltros() {
        val categoria = categoriaSeleccionada
        val filtrados = resultadosBase.filter { resultado ->
            categoria == "Todas" || resultado.categoria == categoria
        }

        val ordenados = filtrados.sortedWith(
            compareBy<ResultadoBusqueda> { !it.abiertoAhora }
                .thenBy { it.distanciaMetros ?: Double.MAX_VALUE },
        )

        resultados.clear()
        resultados.addAll(ordenados)
        adaptadorResultados.notifyDataSetChanged()
    }

    private suspend fun registrarBusquedaFallida(consulta: String, ubicacion: Location?) {
        val datos = mapOf(
            "nombreProducto" to consulta,
            "latitud" to ubicacion?.latitude,
            "longitud" to ubicacion?.longitude,
            "resultadoExitoso" to false,
            "compradorId" to (autenticacion.currentUser?.uid ?: ""),
        )

        baseDatos.collection(Constantes.COLECCION_BUSQUEDAS_HISTORICAS)
            .add(datos)
            .await()
    }

    private fun obtenerVendedorIdDocumento(documento: DocumentSnapshot): String? {
        val vendedorId = documento.getString("vendedorId")
        if (!vendedorId.isNullOrBlank()) {
            return vendedorId
        }
        return documento.reference.parent.parent?.id
    }

    private suspend fun notificarVendedoresSinProducto(
        producto: String,
        documentosConProducto: Collection<DocumentSnapshot>,
        ubicacionComprador: Location?,
        esCategoria: Boolean,
    ) {
        val validacion = FiltroContenido.validarNombreProducto(producto)
        if (!validacion.esValido) {
            return
        }

        val productoNormalizado = FiltroContenido.normalizar(producto)
        val productoId = FiltroContenido.normalizarParaFiltro(producto).replace(" ", "_")
        val vendedoresConProducto = documentosConProducto.mapNotNull { documento ->
            obtenerVendedorIdDocumento(documento)
        }.toSet()

        val vendedores = baseDatos.collection(Constantes.COLECCION_USUARIOS)
            .whereEqualTo("rol", Constantes.ROL_VENDEDOR)
            .get()
            .await()
            .documents

        if (vendedores.isEmpty()) {
            return
        }

        val encontroAlgo = vendedoresConProducto.isNotEmpty()
        val mensaje = if (esCategoria) {
            if (encontroAlgo) {
                "¡Oportunidad! Hay compradores buscando la categoría $producto."
            } else {
                "¡Oportunidad! Un comprador buscó la categoría $producto y no encontró productos en tu stock."
            }
        } else {
            if (encontroAlgo) {
                "¡Oportunidad! Hay compradores buscando $producto cerca de ti."
            } else {
                "¡Oportunidad! Un comprador buscó $producto y no lo encontró en tu stock."
            }
        }

        val radioMetros = calcularRadioCobertura(documentosConProducto, ubicacionComprador)

        val fechaCreacion = System.currentTimeMillis()
        val latitud = ubicacionComprador?.latitude
        val longitud = ubicacionComprador?.longitude
        val compradorId = autenticacion.currentUser?.uid ?: ""

        vendedores.forEach { vendedor ->
            val vendedorId = vendedor.id
            if (vendedorId.isBlank() || vendedoresConProducto.contains(vendedorId)) {
                return@forEach
            }
            val idAlerta = "${productoId}_${vendedorId}_$fechaCreacion"
            val datos = mapOf(
                "producto" to producto,
                "productoNormalizado" to productoNormalizado,
                "vendedorId" to vendedorId,
                "compradorId" to compradorId,
                "mensaje" to mensaje,
                "latitudCentro" to latitud,
                "longitudCentro" to longitud,
                "radioMetros" to radioMetros,
                "fechaCreacion" to fechaCreacion,
                "totalBusquedas" to 1,
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

    private fun calcularRadioCobertura(
        documentosConProducto: Collection<DocumentSnapshot>,
        ubicacionComprador: Location?,
    ): Double {
        if (ubicacionComprador == null) {
            return RADIO_DEFAULT_METROS
        }
        var radioMaximo = 0.0
        var hayAlmacen = false
        for (documento in documentosConProducto) {
            val latitudAlmacen = documento.getDouble("latitud")
            val longitudAlmacen = documento.getDouble("longitud")
            if (latitudAlmacen == null || longitudAlmacen == null) continue
            hayAlmacen = true
            val distancia = UbicacionUtil.calcularDistancia(
                ubicacionComprador.latitude,
                ubicacionComprador.longitude,
                latitudAlmacen,
                longitudAlmacen,
            )
            if (distancia > radioMaximo) radioMaximo = distancia
        }
        val margen = RADIO_MARGEN_METROS
        val radioCalculado = if (hayAlmacen) radioMaximo + margen else RADIO_DEFAULT_METROS
        return radioCalculado.coerceIn(RADIO_MINIMO_METROS, RADIO_MAXIMO_METROS)
    }

    private suspend fun obtenerUbicacionParaAlerta(): Location? {
        val cacheada = obtenerUbicacionCacheada()
        if (cacheada != null) return cacheada
        return obtenerUbicacionActual()
    }

    private suspend fun obtenerUbicacionCacheada(): Location? {
        val cacheHit = UbicacionUtil.obtenerUbicacionConCache(
            ubicacionCache,
            ubicacionCacheTiempo,
            HorarioUtil.TIEMPO_CACHE_UBICACION_MS,
        )
        if (cacheHit != null) return cacheHit
        val rapida = UbicacionUtil.obtenerUbicacionRapida(proveedorUbicacion)
        if (rapida != null) {
            ubicacionCache = rapida
            ubicacionCacheTiempo = System.currentTimeMillis()
        }
        return rapida
    }

    private suspend fun obtenerUbicacionActual(): Location? {
        val proveedor = proveedorUbicacion
        val ahora = System.currentTimeMillis()
        val cacheHit = UbicacionUtil.obtenerUbicacionConCache(
            ubicacionCache,
            ubicacionCacheTiempo,
            HorarioUtil.TIEMPO_CACHE_UBICACION_MS,
        )
        if (cacheHit != null) return cacheHit

        val ubicacionFused = UbicacionUtil.obtenerUbicacionFused(proveedor)
        if (ubicacionFused != null) {
            ubicacionCache = ubicacionFused
            ubicacionCacheTiempo = ahora
            return ubicacionFused
        }

        val ubicacionSistema = UbicacionUtil.obtenerUbicacionSistema(this)
        if (ubicacionSistema != null) {
            ubicacionCache = ubicacionSistema
            ubicacionCacheTiempo = ahora
            return ubicacionSistema
        }

        return null
    }

    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }

    private fun abrirNavegacion(resultado: ResultadoBusqueda) {
        val latitud = resultado.latitudAlmacen
        val longitud = resultado.longitudAlmacen
        if (latitud == null || longitud == null) {
            mostrarMensaje("Ubicación del almacén no disponible")
            return
        }

        val etiqueta = resultado.nombreAlmacen.ifBlank { "Almacén" }
        val uriNavegacion = Uri.parse("google.navigation:q=$latitud,$longitud($etiqueta)")
        val intentMapa = Intent(Intent.ACTION_VIEW, uriNavegacion)
        intentMapa.setPackage("com.google.android.apps.maps")

        val gestorPaquetes = packageManager
        if (intentMapa.resolveActivity(gestorPaquetes) != null) {
            startActivity(intentMapa)
        } else {
            val uriWeb = Uri.parse(
                "https://www.google.com/maps/dir/?api=1&destination=$latitud,$longitud",
            )
            startActivity(Intent(Intent.ACTION_VIEW, uriWeb))
        }
    }

    private fun abrirStockAlmacen(resultado: ResultadoBusqueda) {
        if (resultado.vendedorId.isBlank()) {
            mostrarMensaje("No se pudo identificar el almacén")
            return
        }
        val intent = Intent(this, StockAlmacenActivity::class.java).apply {
            putExtra(StockAlmacenActivity.EXTRA_VENDEDOR_ID, resultado.vendedorId)
            putExtra(StockAlmacenActivity.EXTRA_NOMBRE_ALMACEN, resultado.nombreAlmacen)
            putExtra(StockAlmacenActivity.EXTRA_HORARIO_ATENCION, resultado.horarioAtencion)
            putExtra(StockAlmacenActivity.EXTRA_LATITUD_ALMACEN, resultado.latitudAlmacen)
            putExtra(StockAlmacenActivity.EXTRA_LONGITUD_ALMACEN, resultado.longitudAlmacen)
        }
        startActivity(intent)
    }

    private suspend fun buscarInventarioLocalPublico(consultaNormalizada: String): List<DocumentSnapshot> {
        if (consultaNormalizada.isBlank()) {
            return emptyList()
        }
        val documentos = obtenerInventarioPublicoCache()
        return documentos.filter { documento ->
            val nombre = documento.getString("nombre").orEmpty()
            val nombreNormalizado = documento.getString("nombreNormalizado")
                ?.takeIf { it.isNotBlank() }
                ?: FiltroContenido.normalizar(nombre)
            nombreNormalizado.startsWith(consultaNormalizada)
        }
    }

    private suspend fun buscarInventarioLocalPrivado(consultaNormalizada: String): List<DocumentSnapshot> {
        if (consultaNormalizada.isBlank()) {
            return emptyList()
        }
        return try {
            val documentos = obtenerInventarioPrivadoCache()
            documentos.filter { documento ->
                val nombre = documento.getString("nombre").orEmpty()
                val nombreNormalizado = documento.getString("nombreNormalizado")
                    ?.takeIf { it.isNotBlank() }
                    ?: FiltroContenido.normalizar(nombre)
                nombreNormalizado.startsWith(consultaNormalizada)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun buscarInventarioLocalPublicoPorCategoria(categoria: String): List<DocumentSnapshot> {
        if (categoria.isBlank() || categoria == "Todas") {
            return emptyList()
        }
        val documentos = obtenerInventarioPublicoCache()
        return documentos.filter { documento ->
            documento.getString("categoria") == categoria
        }
    }

    private suspend fun obtenerInventarioPublicoCache(): List<DocumentSnapshot> {
        val cache = inventarioPublicoCache
        if (cache != null) {
            return cache
        }
        val cacheFirestore = try {
            baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
                .get(Source.CACHE)
                .await()
                .documents
        } catch (_: Exception) {
            emptyList()
        }
        if (cacheFirestore.isNotEmpty()) {
            inventarioPublicoCache = cacheFirestore
            return cacheFirestore
        }
        val resultado = baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
            .get()
            .await()
        return resultado.documents.also { inventarioPublicoCache = it }
    }

    private suspend fun obtenerInventarioPublicoServidor(): List<DocumentSnapshot> {
        val resultado = baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
            .get()
            .await()
        return resultado.documents.also { inventarioPublicoCache = it }
    }

    private suspend fun obtenerInventarioPrivadoCache(): List<DocumentSnapshot> {
        val cache = inventarioPrivadoCache
        if (cache != null) {
            return cache
        }
        val cacheFirestore = try {
            baseDatos.collectionGroup("Inventario")
                .get(Source.CACHE)
                .await()
                .documents
        } catch (_: Exception) {
            emptyList()
        }
        if (cacheFirestore.isNotEmpty()) {
            inventarioPrivadoCache = cacheFirestore
            return cacheFirestore
        }
        val resultado = baseDatos.collectionGroup("Inventario")
            .get()
            .await()
        return resultado.documents.also { inventarioPrivadoCache = it }
    }

    data class ResultadoBusqueda(
        val nombreProducto: String,
        val precio: Double,
        val unidadPrecio: String,
        val descripcion: String,
        val categoria: String,
        val vendedorId: String,
        val productoId: String,
        val nombreAlmacen: String,
        val horarioAtencion: String,
        val abiertoAhora: Boolean,
        val distanciaMetros: Double?,
        val latitudAlmacen: Double?,
        val longitudAlmacen: Double?,
        val disponible: Boolean,
        val enOferta: Boolean = false,
        val precioOferta: Double? = null,
        val descuentoPorcentaje: Int? = null,
        val fechaFinOferta: Long? = null,
    )

    private class AdaptadorResultados(
        private val resultados: List<ResultadoBusqueda>,
        private val onLlegar: (ResultadoBusqueda) -> Unit,
        private val onVerStock: (ResultadoBusqueda) -> Unit,
    ) : RecyclerView.Adapter<AdaptadorResultados.VistaResultado>() {

        class VistaResultado(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tarjeta: MaterialCardView = itemView.findViewById(R.id.tarjeta_resultado)
            val textoNombre: TextView = itemView.findViewById(R.id.texto_nombre_producto)
            val textoPrecio: TextView = itemView.findViewById(R.id.texto_precio_producto)
            val contenedorPrecioOferta: android.widget.LinearLayout = itemView.findViewById(R.id.contenedor_precio_oferta)
            val textoPrecioOferta: TextView = itemView.findViewById(R.id.texto_precio_oferta)
            val textoUnidadOferta: TextView = itemView.findViewById(R.id.texto_unidad_oferta)
            val badgeDescuento: TextView = itemView.findViewById(R.id.badge_descuento)
            val textoTiempoRestante: TextView = itemView.findViewById(R.id.texto_tiempo_restante)
            val textoDescripcion: TextView = itemView.findViewById(R.id.texto_descripcion_producto)
            val textoAlmacen: TextView = itemView.findViewById(R.id.texto_almacen_producto)
            val textoHorario: TextView = itemView.findViewById(R.id.texto_horario_almacen)
            val textoEstadoHorario: TextView = itemView.findViewById(R.id.texto_estado_horario)
            val textoDistancia: TextView = itemView.findViewById(R.id.texto_distancia_producto)
            val textoEstado: TextView = itemView.findViewById(R.id.texto_estado_producto)
            val botonVerStock: MaterialButton = itemView.findViewById(R.id.boton_ver_stock)
            val botonLlegar: MaterialButton = itemView.findViewById(R.id.boton_llegar_almacen)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaResultado {
            val vista = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_resultado_producto, parent, false)
            return VistaResultado(vista)
        }

        override fun onBindViewHolder(holder: VistaResultado, position: Int) {
            val resultado = resultados[position]
            val contexto = holder.itemView.context
            holder.textoNombre.text = resultado.nombreProducto

            val precioOriginalTexto = "$${String.format(Locale.forLanguageTag("es-CL"), "%.0f", resultado.precio)} / " +
                etiquetaUnidadPrecio(resultado.unidadPrecio)

            val precioOfertaValor = resultado.precioOferta
            val ofertaVigente = precioOfertaValor != null &&
                OfertaUtil.estaVigente(resultado.enOferta, resultado.fechaFinOferta)
            if (ofertaVigente) {
                holder.tarjeta.setCardBackgroundColor(
                    ContextCompat.getColor(contexto, R.color.oferta_card_fondo),
                )

                holder.textoPrecio.text = "Antes: $precioOriginalTexto"
                holder.textoPrecio.paintFlags =
                    holder.textoPrecio.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                holder.textoPrecio.setTextColor(
                    ContextCompat.getColor(contexto, R.color.oferta_precio_tachado),
                )
                holder.textoPrecio.textSize = 13f

                holder.contenedorPrecioOferta.visibility = View.VISIBLE
                holder.textoPrecioOferta.text =
                    "$${String.format(Locale.forLanguageTag("es-CL"), "%.0f", precioOfertaValor)}"
                holder.textoUnidadOferta.text = "/ ${etiquetaUnidadPrecio(resultado.unidadPrecio)}"

                holder.badgeDescuento.visibility = View.VISIBLE
                holder.badgeDescuento.text = "-${resultado.descuentoPorcentaje ?: 0}% OFF"

                holder.textoTiempoRestante.visibility = View.VISIBLE
                holder.textoTiempoRestante.text = OfertaUtil.tiempoRestanteTexto(resultado.fechaFinOferta)
            } else {
                holder.tarjeta.setCardBackgroundColor(
                    ContextCompat.getColor(contexto, R.color.fondo_card),
                )
                holder.textoPrecio.text = "Precio: $precioOriginalTexto"
                holder.textoPrecio.paintFlags =
                    holder.textoPrecio.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                holder.textoPrecio.setTextColor(
                    ContextCompat.getColor(contexto, R.color.texto_secundario),
                )
                holder.textoPrecio.textSize = 14f

                holder.contenedorPrecioOferta.visibility = View.GONE
                holder.badgeDescuento.visibility = View.GONE
                holder.textoTiempoRestante.visibility = View.GONE
            }

            if (resultado.descripcion.isBlank()) {
                holder.textoDescripcion.visibility = View.GONE
            } else {
                holder.textoDescripcion.visibility = View.VISIBLE
                holder.textoDescripcion.text = "Descripción: ${resultado.descripcion}"
            }
            holder.textoAlmacen.text = "Almacén: ${resultado.nombreAlmacen}"
            holder.textoHorario.text = "Horario: ${resultado.horarioAtencion}"
            holder.textoEstadoHorario.text = if (resultado.abiertoAhora) {
                "Atención: Abierto"
            } else {
                "Atención: Cerrado"
            }
            holder.textoEstadoHorario.setTextColor(
                ContextCompat.getColor(
                    holder.itemView.context,
                    if (resultado.abiertoAhora) R.color.stock_verde else R.color.stock_rojo,
                ),
            )
            holder.textoDistancia.text = "Distancia: ${formatearDistancia(resultado.distanciaMetros)}"
            holder.textoEstado.text = if (resultado.disponible) {
                "Estado: Disponible"
            } else {
                "Estado: Agotado"
            }
            holder.textoEstado.setTextColor(
                ContextCompat.getColor(
                    holder.itemView.context,
                    if (resultado.disponible) R.color.stock_verde else R.color.stock_rojo,
                ),
            )

            holder.botonLlegar.isEnabled = true
            holder.botonLlegar.alpha = 1f
            holder.botonLlegar.setOnClickListener { onLlegar(resultado) }

            val puedeVerStock = resultado.vendedorId.isNotBlank()
            holder.botonVerStock.isEnabled = puedeVerStock
            holder.botonVerStock.alpha = if (puedeVerStock) 1f else 0.5f
            holder.botonVerStock.setOnClickListener { onVerStock(resultado) }
        }

        override fun getItemCount(): Int = resultados.size

        private fun formatearDistancia(distanciaMetros: Double?): String {
            if (distanciaMetros == null) {
                return "Sin ubicación"
            }
            return if (distanciaMetros >= 1000) {
                val km = distanciaMetros / 1000.0
                "${String.format(Locale.forLanguageTag("es-CL"), "%.1f", km)} km"
            } else {
                "${distanciaMetros.toInt()} m"
            }
        }

        private fun etiquetaUnidadPrecio(unidad: String): String {
            return if (unidad == "kilo") "kg" else "unidad"
        }
    }

    companion object {
        private const val RADIO_DEFAULT_METROS = 5_000.0
        private const val RADIO_MARGEN_METROS = 2_000.0
        private const val RADIO_MINIMO_METROS = 1_000.0
        private const val RADIO_MAXIMO_METROS = 50_000.0
    }
}
