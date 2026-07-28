package com.example.rutaalmacen

import android.Manifest
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rutaalmacen.productos.OfertaUtil
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * Actividad principal del módulo de comprador.
 *
 * Permite buscar productos por nombre o categoría en el inventario público y privado,
 * muestra resultados ordenados por cercanía y estado de apertura del almacén,
 * gestiona permisos de ubicación, calcula distancias, notifica a vendedores sobre
 * productos no encontrados y ofrece navegación hacia Google Maps.
 *
 * Implementa un sistema de caché en memoria y Firestore para optimizar las consultas,
 * y registra búsquedas fallidas e historial para análisis posterior.
 */
class CompradorActivity : AppCompatActivity() {

    /** Instancia de [FirebaseAuth] para obtener el usuario autenticado actual. */
    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    /** Instancia de [FirebaseFirestore] para acceder a la base de datos. */
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /** Proveedor de ubicación combinada de Google Play Services. */
    private val proveedorUbicacion: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    /** Lista mutable de resultados actualmente mostrados en el adaptador. */
    private val resultados: MutableList<ResultadoBusqueda> = mutableListOf()

    /** Lista base de resultados antes de aplicar filtros de categoría. */
    private val resultadosBase: MutableList<ResultadoBusqueda> = mutableListOf()

    /** Adaptador del [RecyclerView] que muestra los resultados de búsqueda. */
    private lateinit var adaptadorResultados: AdaptadorResultados

    /** Texto de búsqueda pendiente que requiere permiso de ubicación antes de ejecutarse. */
    private var busquedaPendiente: String? = null

    /** Categoría pendiente que requiere permiso de ubicación antes de ejecutarse. */
    private var categoriaPendiente: String? = null

    /** Bandera para evitar mostrar múltiples avisos de falta de ubicación. */
    private var avisoSinUbicacionMostrado = false

    /** Categoría actualmente seleccionada en el filtro de la interfaz. */
    private var categoriaSeleccionada = "Todas"

    /** Vista de carga que se muestra u oculta durante las operaciones asíncronas. */
    private lateinit var contenedorCarga: View

    /** Caché en memoria de documentos de usuarios (vendedores) para evitar lecturas repetidas. */
    private val cacheUsuarios: MutableMap<String, DocumentSnapshot> = mutableMapOf()

    /** Caché en memoria del inventario público obtenido desde Firestore. */
    private var inventarioPublicoCache: List<DocumentSnapshot>? = null

    /** Caché en memoria del inventario privado (subcolecciones «Inventario») obtenido desde Firestore. */
    private var inventarioPrivadoCache: List<DocumentSnapshot>? = null

    /** Última ubicación conocida del comprador, utilizada para cálculos de distancia. */
    private var ubicacionCache: Location? = null

    /** Marca de tiempo de la última actualización de [ubicacionCache]. */
    private var ubicacionCacheTiempo = 0L

    /** Categorías disponibles para el filtro de productos en la interfaz. */
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

    /**
     * Lanzador de actividad que procesa el resultado de la solicitud de permisos de ubicación.
     *
     * Si el permiso es concedido, ejecuta la búsqueda o el filtro por categoría que
     * estaba pendiente. Si es denegado, muestra un mensaje al usuario.
     */
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

    /**
     * Inicializa la actividad del comprador: configura el diseño edge-to-edge,
     * el [RecyclerView] de resultados, los filtros de categoría, la barra de navegación
     * inferior, el botón de búsqueda y el botón de información de alertas.
     * Carga los productos iniciales de forma asíncrona.
     *
     * @param savedInstanceState Estado guardado previamente, o `null` si es la primera creación.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_comprador)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_comprador)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                barrasDelSistema.top,
                barrasDelSistema.right,
                barrasDelSistema.bottom,
            )
            insets
        }

        val campoBusqueda = findViewById<TextInputEditText>(R.id.campo_busqueda_producto)
        val recyclerResultados = findViewById<RecyclerView>(R.id.recycler_resultados)
        val campoCategoria = findViewById<AutoCompleteTextView>(R.id.campo_categoria)
        val navegacion = findViewById<BottomNavigationView>(R.id.nav_comprador)
        contenedorCarga = findViewById(R.id.contenedor_carga_comprador)
        val botonBuscar = findViewById<com.google.android.material.button.MaterialButton>(R.id.boton_buscar_producto)

        adaptadorResultados = AdaptadorResultados(
            resultados = resultados,
            onLlegar = { resultado -> abrirNavegacion(resultado) },
            onVerStock = { resultado -> abrirStockAlmacen(resultado) },
        )
        recyclerResultados.layoutManager = LinearLayoutManager(this)
        recyclerResultados.adapter = adaptadorResultados

        configurarFiltros(campoCategoria)
        configurarNavegacion(navegacion)

        botonBuscar.setOnClickListener {
            ejecutarBusquedaManual(campoBusqueda.text?.toString().orEmpty())
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.boton_info_alertas).setOnClickListener {
            startActivity(Intent(this, InfoAlertasActivity::class.java))
        }
        lifecycleScope.launch { cargarProductosIniciales() }
    }

    /**
     * Configura la barra de navegación inferior del comprador.
     *
     * Permite alternar entre la vista de productos (actual) y la vista de almacenes cercanos.
     * Al navegar a almacenes cercanos, se finaliza esta actividad para evitar acumulación en la pila.
     *
     * @param navegacion Barra de navegación inferior a configurar.
     */
    private fun configurarNavegacion(navegacion: BottomNavigationView) {
        navegacion.selectedItemId = R.id.nav_productos_comprador
        navegacion.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_productos_comprador -> true
                R.id.nav_almacenes_comprador -> {
                    val intent = Intent(this, AlmacenesCercanosActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(intent)
                    finish()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Busca productos en el inventario público y, si no hay resultados, en el inventario privado.
     *
     * Solicita permiso de ubicación si no está concedido, obtiene la ubicación del comprador,
     * normaliza la consulta, construye los resultados con distancia y notifica a los vendedores
     * que no tienen el producto buscado. Registra la búsqueda si no se encontraron resultados.
     *
     * @param consulta Texto ingresado por el comprador para buscar productos.
     */
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
            if (ubicacionComprador == null) {
                if (!avisoSinUbicacionMostrado) {
                    mostrarMensaje("No se pudo obtener la ubicación, se enviará la alerta sin coordenadas")
                    avisoSinUbicacionMostrado = true
                }
            } else {
                avisoSinUbicacionMostrado = false
            }

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

    /**
     * Carga los productos iniciales mostrando primero la caché disponible
     * y luego refrescando con datos del servidor.
     *
     * Utiliza la ubicación en caché si está disponible para evitar retrasos.
     * Si no hay caché, muestra el indicador de carga hasta obtener los datos.
     */
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

    /**
     * Busca productos filtrados por categoría en el inventario público local.
     *
     * Solicita permiso de ubicación si no está concedido, notifica a los vendedores
     * sobre la búsqueda de categoría y construye los resultados con información de distancia.
     *
     * @param categoria Nombre de la categoría por la cual filtrar los productos.
     */
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
            if (ubicacionComprador == null) {
                if (!avisoSinUbicacionMostrado) {
                    mostrarMensaje("No se pudo obtener la ubicación, se mostrará sin distancia")
                    avisoSinUbicacionMostrado = true
                }
            } else {
                avisoSinUbicacionMostrado = false
            }

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

    /**
     * Configura el campo de autocompletado de categorías con un adaptador
     * que muestra las opciones disponibles y aplica el filtro al seleccionar una.
     *
     * @param campoCategoria Campo de texto con autocompletado donde se muestra el selector de categorías.
     */
    private fun configurarFiltros(
        campoCategoria: AutoCompleteTextView,
    ) {
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

    /**
     * Ejecuta una búsqueda manual iniciada por el usuario desde el botón de buscar.
     *
     * Si el texto está vacío y no hay categoría seleccionada, recarga los productos iniciales.
     * Si el texto está vacío pero hay categoría, busca por categoría.
     * Oculta el teclado antes de iniciar la búsqueda.
     *
     * @param consulta Texto ingresado en el campo de búsqueda.
     */
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

    /** Oculta el teclado en pantalla y limpia el foco de la vista actual. */
    private fun ocultarTeclado() {
        val vista = currentFocus ?: View(this)
        val gestor = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        gestor.hideSoftInputFromWindow(vista.windowToken, 0)
        vista.clearFocus()
    }

    /**
     * Construye la lista de [ResultadoBusqueda] a partir de los documentos de Firestore.
     *
     * Para cada documento, obtiene los datos del vendedor desde la caché o Firestore,
     * calcula la distancia al almacén, evalúa el estado de apertura y las ofertas vigentes.
     * Detecta ofertas expiradas y programa su reseteo automático.
     *
     * @param documentos Colección de documentos de producto obtenidos desde Firestore.
     * @param ubicacionComprador Ubicación actual del comprador, o `null` si no está disponible.
     * @return Lista de resultados de búsqueda listos para ser mostrados.
     */
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

    /**
     * Resetea las ofertas expiradas detectadas durante la construcción de resultados.
     *
     * Limpia los campos de oferta en el inventario público y, si el comprador actual
     * es también el vendedor propietario, en el inventario privado.
     *
     * @param ofertas Lista de pares (vendedorId, productoId) con ofertas expiradas.
     */
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
                // El vendedor que sea dueño limpiará el privado en su propio refresh
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
                    // Silenciar
                }
            }
        }
    }

    /**
     * Reemplaza los resultados base con los nuevos documentos y aplica los filtros activos.
     *
     * @param documentos Colección de documentos de producto obtenidos desde Firestore.
     * @param ubicacionComprador Ubicación actual del comprador para cálculo de distancias.
     */
    private suspend fun actualizarResultados(
        documentos: Collection<DocumentSnapshot>,
        ubicacionComprador: Location?,
    ) {
        val nuevosResultados = construirResultados(documentos, ubicacionComprador)
        resultadosBase.clear()
        resultadosBase.addAll(nuevosResultados)
        aplicarFiltros()
    }

    /**
     * Muestra u oculta el indicador visual de carga.
     *
     * @param mostrar `true` para mostrar el indicador, `false` para ocultarlo.
     */
    private fun mostrarCarga(mostrar: Boolean) {
        contenedorCarga.visibility = if (mostrar) View.VISIBLE else View.GONE
    }

    /**
     * Aplica el filtro de categoría y ordena los resultados por estado de apertura
     * (abiertos primero) y por distancia ascendente.
     *
     * Actualiza la lista mostrada en el adaptador y notifica los cambios.
     */
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

    /**
     * Registra una búsqueda fallida en la colección de búsquedas históricas de Firestore.
     *
     * Incluye el texto consultado, la ubicación del comprador y el identificador del comprador.
     *
     * @param consulta Texto que el comprador buscó sin resultados.
     * @param ubicación Ubicación del comprador al momento de la búsqueda, o `null`.
     */
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

    /**
     * Extrae el identificador del vendedor desde un documento de producto.
     *
     * Intenta leer el campo `vendedorId`; si no está presente, obtiene el identificador
     * del documento padre (subcolección del usuario).
     *
     * @param documento Documento de producto desde Firestore.
     * @return Identificador del vendedor, o `null` si no se pudo determinar.
     */
    private fun obtenerVendedorIdDocumento(documento: DocumentSnapshot): String? {
        val vendedorId = documento.getString("vendedorId")
        if (!vendedorId.isNullOrBlank()) {
            return vendedorId
        }
        return documento.reference.parent.parent?.id
    }

    /**
     * Notifica a los vendedores que no tienen el producto o categoría buscada por el comprador.
     *
     * Crea documentos de notificación en la colección de notificaciones de IA para cada
     * vendedor que no aparezca en la lista de vendedores con el producto. Calcula el radio
     * de cobertura basado en la ubicación de los almacenes que sí tienen el producto.
     *
     * @param producto Nombre del producto o categoría buscada.
     * @param documentosConProducto Documentos que sí contienen el producto encontrado.
     * @param ubicacionComprador Ubicación del comprador, o `null` si no está disponible.
     * @param esCategoria `true` si la búsqueda fue por categoría, `false` si fue por nombre de producto.
     */
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
                // Continuar con los demas vendedores aunque uno falle
            }
        }
    }

    /**
     * Calcula el radio de cobertura para las notificaciones de oportunidad.
     *
     * Determina la distancia máxima desde el comprador hasta los almacenes que
     * tienen el producto, añadiendo un margen. Si no hay ubicación o almacenes,
     * utiliza un radio predeterminado. El resultado se limita entre los valores
     * mínimo y máximo configurados.
     *
     * @param documentosConProducto Documentos de los almacenes que tienen el producto.
     * @param ubicacionComprador Ubicación del comprador, o `null` si no está disponible.
     * @return Radio de cobertura en metros, acotado entre [RADIO_MINIMO_METROS] y [RADIO_MAXIMO_METROS].
     */
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

    /**
     * Obtiene la ubicación del comprador para enviar en las alertas de oportunidad.
     *
     * Prioriza la ubicación en caché; si no está disponible, solicita la ubicación actual.
     *
     * @return Ubicación del comprador, o `null` si no se pudo obtener.
     */
    private suspend fun obtenerUbicacionParaAlerta(): Location? {
        val cacheada = obtenerUbicacionCacheada()
        if (cacheada != null) return cacheada
        return obtenerUbicacionActual()
    }

    /**
     * Obtiene la ubicación del comprador desde la caché en memoria o, si ha expirado,
     * solicita una ubicación rápida al proveedor de ubicación combinada.
     *
     * @return Ubicación del comprador, o `null` si no se pudo obtener.
     */
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

    /**
     * Obtiene la ubicación actual del comprador intentando múltiples fuentes en cascada:
     * caché en memoria, proveedor Fused de Google Play Services y ubicación del sistema.
     *
     * @return Ubicación más reciente disponible, o `null` si ninguna fuente respondió.
     */
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

    /**
     * Muestra un mensaje breve al usuario mediante un [android.widget.Toast].
     *
     * @param mensaje Texto que se desplegará en pantalla.
     */
    private fun mostrarMensaje(mensaje: String) {
        android.widget.Toast.makeText(this, mensaje, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Abre la aplicación de Google Maps con indicaciones de navegación hacia el almacén.
     *
     * Si Google Maps no está instalado, abre la versión web como alternativa.
     *
     * @param resultado Resultado de búsqueda que contiene las coordenadas y el nombre del almacén.
     */
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

    /**
     * Abre la actividad de detalle de stock de un almacén específico.
     *
     * Transmite el identificador del vendedor, nombre del almacén, horario de atención
     * y coordenadas geográficas como extras del intent.
     *
     * @param resultado Resultado de búsqueda que contiene los datos del almacén.
     */
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

    /**
     * Busca productos en la caché local del inventario público por coincidencia de prefijo
     * sobre el nombre normalizado.
     *
     * @param consultaNormalizada Texto de búsqueda ya normalizado para comparación.
     * @return Lista de documentos cuyo nombre normalizado comienza con la consulta.
     */
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

    /**
     * Busca productos en la caché local del inventario privado (subcolecciones «Inventario»)
     * por coincidencia de prefijo sobre el nombre normalizado.
     *
     * @param consultaNormalizada Texto de búsqueda ya normalizado para comparación.
     * @return Lista de documentos cuyo nombre normalizado comienza con la consulta.
     */
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

    /**
     * Busca productos en la caché local del inventario público filtrados por categoría exacta.
     *
     * @param categoria Nombre de la categoría a filtrar. Se ignora si es «Todas» o está vacía.
     * @return Lista de documentos cuyo campo `categoria` coincide exactamente con la categoría indicada.
     */
    private suspend fun buscarInventarioLocalPublicoPorCategoria(categoria: String): List<DocumentSnapshot> {
        if (categoria.isBlank() || categoria == "Todas") {
            return emptyList()
        }
        val documentos = obtenerInventarioPublicoCache()
        return documentos.filter { documento ->
            documento.getString("categoria") == categoria
        }
    }

    /**
     * Obtiene los documentos del inventario público utilizando caché en memoria,
     * luego caché de Firestore y finalmente el servidor como último recurso.
     *
     * @return Lista de documentos del inventario público.
     */
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

    /**
     * Obtiene los documentos del inventario público directamente desde el servidor de Firestore
     * y actualiza la caché en memoria.
     *
     * @return Lista de documentos del inventario público obtenidos del servidor.
     */
    private suspend fun obtenerInventarioPublicoServidor(): List<DocumentSnapshot> {
        val resultado = baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
            .get()
            .await()
        return resultado.documents.also { inventarioPublicoCache = it }
    }

    /**
     * Obtiene los documentos del inventario privado (subcolecciones «Inventario» de todos
     * los usuarios) utilizando caché en memoria, luego caché de Firestore y finalmente
     * el servidor como último recurso.
     *
     * @return Lista de documentos del inventario privado.
     */
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

    /**
     * Modelo de datos que representa un resultado de búsqueda de producto.
     *
     * Contiene toda la información necesaria para mostrar un producto en la interfaz
     * del comprador, incluyendo datos del producto, del almacén, distancia, estado
     * de apertura y ofertas vigentes.
     *
     * @property nombreProducto Nombre del producto encontrado.
     * @property precio Precio original del producto.
     * @property unidadPrecio Unidad de venta del producto (por unidad o por kilo).
     * @property descripcion Descripción del producto, puede estar vacía.
     * @property categoria Categoría a la que pertenece el producto.
     * @property vendedorId Identificador del vendedor propietario del producto.
     * @property productoId Identificador único del producto.
     * @property nombreAlmacen Nombre del almacén donde se vende el producto.
     * @property horarioAtencion Texto descriptivo del horario de atención del almacén.
     * @property abiertoAhora Indica si el almacén está abierto en este momento.
     * @property distanciaMetros Distancia en metros desde el comprador al almacén, o `null`.
     * @property latitudAlmacen Latitud geográfica del almacén, o `null`.
     * @property longitudAlmacen Longitud geográfica del almacén, o `null`.
     * @property disponible Indica si el producto está disponible.
     * @property enOferta Indica si el producto tiene una oferta vigente.
     * @property precioOferta Precio con descuento aplicado, o `null` si no hay oferta.
     * @property descuentoPorcentaje Porcentaje de descuento de la oferta, o `null`.
     * @property fechaFinOferta Marca de tiempo de finalización de la oferta, o `null`.
     */
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

    /**
     * Adaptador del [RecyclerView] que renderiza los resultados de búsqueda de productos.
     *
     * Muestra información completa de cada producto: nombre, precio (con soporte de ofertas),
     * descripción, almacén, horario, distancia, disponibilidad y botones de acción
     * para navegar al almacén o ver su stock.
     *
     * @param resultados Lista de resultados de búsqueda que se mostrarán.
     * @param onLlegar Acción ejecutada al pulsar el botón «Llegar» de un resultado.
     * @param onVerStock Acción ejecutada al pulsar el botón «Ver stock» de un resultado.
     */
    private class AdaptadorResultados(
        private val resultados: List<ResultadoBusqueda>,
        private val onLlegar: (ResultadoBusqueda) -> Unit,
        private val onVerStock: (ResultadoBusqueda) -> Unit,
    ) : RecyclerView.Adapter<AdaptadorResultados.VistaResultado>() {

        /**
         * Contenedor de vistas para cada elemento de la lista de resultados de productos.
         *
         * Mantiene referencias a todos los elementos visuales de [R.layout.item_resultado_producto]
         * para evitar búsquedas repetidas durante el desplazamiento.
         *
         * @param itemView Vista raíz del elemento de la lista.
         */
        class VistaResultado(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
            /** Tarjeta principal del resultado de producto. */
            val tarjeta: com.google.android.material.card.MaterialCardView =
                itemView.findViewById(R.id.tarjeta_resultado)
            /** Etiqueta con el nombre del producto. */
            val textoNombre: android.widget.TextView = itemView.findViewById(R.id.texto_nombre_producto)
            /** Etiqueta con el precio original del producto. */
            val textoPrecio: android.widget.TextView = itemView.findViewById(R.id.texto_precio_producto)
            /** Contenedor del bloque de precio de oferta. */
            val contenedorPrecioOferta: android.widget.LinearLayout =
                itemView.findViewById(R.id.contenedor_precio_oferta)
            /** Etiqueta con el precio con descuento aplicado. */
            val textoPrecioOferta: android.widget.TextView =
                itemView.findViewById(R.id.texto_precio_oferta)
            /** Etiqueta con la unidad de venta del precio de oferta. */
            val textoUnidadOferta: android.widget.TextView =
                itemView.findViewById(R.id.texto_unidad_oferta)
            /** Insignia que muestra el porcentaje de descuento. */
            val badgeDescuento: android.widget.TextView = itemView.findViewById(R.id.badge_descuento)
            /** Etiqueta con el tiempo restante de la oferta. */
            val textoTiempoRestante: android.widget.TextView =
                itemView.findViewById(R.id.texto_tiempo_restante)
            /** Etiqueta con la descripción del producto. */
            val textoDescripcion: android.widget.TextView = itemView.findViewById(R.id.texto_descripcion_producto)
            /** Etiqueta con el nombre del almacén. */
            val textoAlmacen: android.widget.TextView = itemView.findViewById(R.id.texto_almacen_producto)
            /** Etiqueta con el horario de atención del almacén. */
            val textoHorario: android.widget.TextView = itemView.findViewById(R.id.texto_horario_almacen)
            /** Etiqueta con el estado de apertura actual del almacén. */
            val textoEstadoHorario: android.widget.TextView = itemView.findViewById(R.id.texto_estado_horario)
            /** Etiqueta con la distancia al almacén. */
            val textoDistancia: android.widget.TextView = itemView.findViewById(R.id.texto_distancia_producto)
            /** Etiqueta con el estado de disponibilidad del producto. */
            val textoEstado: android.widget.TextView = itemView.findViewById(R.id.texto_estado_producto)
            /** Botón para ver el stock completo del almacén. */
            val botonVerStock: MaterialButton = itemView.findViewById(R.id.boton_ver_stock)
            /** Botón para abrir la navegación hacia el almacén. */
            val botonLlegar: MaterialButton = itemView.findViewById(R.id.boton_llegar_almacen)
        }

        /**
         * Crea una nueva vista de elemento inflando el diseño [R.layout.item_resultado_producto].
         *
         * @param parent Grupo de vistas padre donde se insertará la nueva vista.
         * @param viewType Tipo de vista (no utilizado en este adaptador).
         * @return Nueva instancia de [VistaResultado] enlazada al diseño inflado.
         */
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaResultado {
            val vista = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_resultado_producto, parent, false)
            return VistaResultado(vista)
        }

        /**
         * Vincula los datos de un resultado de búsqueda con los elementos visuales de la vista.
         *
         * Configura los textos de nombre, precio (normal o con oferta tachada), descripción,
         * almacén, horario, estado de apertura, distancia y disponibilidad. Ajusta los colores
         * de la tarjeta según si hay oferta vigente. Asigna los listeners de los botones
         * «Llegar» y «Ver stock».
         *
         * @param holder Vista del elemento que se va a vincular.
         * @param position Posición del elemento dentro de la lista.
         */
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
                    androidx.core.content.ContextCompat.getColor(contexto, R.color.oferta_card_fondo),
                )

                holder.textoPrecio.text = "Antes: $precioOriginalTexto"
                holder.textoPrecio.paintFlags =
                    holder.textoPrecio.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                holder.textoPrecio.setTextColor(
                    androidx.core.content.ContextCompat.getColor(contexto, R.color.oferta_precio_tachado),
                )
                holder.textoPrecio.textSize = 13f

                holder.contenedorPrecioOferta.visibility = android.view.View.VISIBLE
                holder.textoPrecioOferta.text =
                    "$${String.format(Locale.forLanguageTag("es-CL"), "%.0f", precioOfertaValor)}"
                holder.textoUnidadOferta.text = "/ ${etiquetaUnidadPrecio(resultado.unidadPrecio)}"

                holder.badgeDescuento.visibility = android.view.View.VISIBLE
                holder.badgeDescuento.text = "-${resultado.descuentoPorcentaje ?: 0}% OFF"

                holder.textoTiempoRestante.visibility = android.view.View.VISIBLE
                holder.textoTiempoRestante.text = OfertaUtil.tiempoRestanteTexto(resultado.fechaFinOferta)
            } else {
                holder.tarjeta.setCardBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(contexto, R.color.fondo_card),
                )
                holder.textoPrecio.text = "Precio: $precioOriginalTexto"
                holder.textoPrecio.paintFlags =
                    holder.textoPrecio.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                holder.textoPrecio.setTextColor(
                    androidx.core.content.ContextCompat.getColor(contexto, R.color.texto_secundario),
                )
                holder.textoPrecio.textSize = 14f

                holder.contenedorPrecioOferta.visibility = android.view.View.GONE
                holder.badgeDescuento.visibility = android.view.View.GONE
                holder.textoTiempoRestante.visibility = android.view.View.GONE
            }

            if (resultado.descripcion.isBlank()) {
                holder.textoDescripcion.visibility = android.view.View.GONE
            } else {
                holder.textoDescripcion.visibility = android.view.View.VISIBLE
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
                androidx.core.content.ContextCompat.getColor(
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
                androidx.core.content.ContextCompat.getColor(
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

        /**
         * @return Cantidad total de elementos que muestra el adaptador.
         */
        override fun getItemCount(): Int = resultados.size

        /**
         * Formatea una distancia en metros a una cadena legible.
         *
         * Si la distancia es mayor o igual a 1000 metros, la convierte a kilómetros
         * con un decimal. Si es menor, la muestra en metros enteros.
         * Si la distancia es `null`, devuelve «Sin ubicación».
         *
         * @param distanciaMetros Distancia en metros, o `null` si no está disponible.
         * @return Cadena formateada con la distancia y su unidad.
         */
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

        /**
         * Convierte el valor interno de unidad de precio a su etiqueta corta para la interfaz.
         *
         * @param unidad Valor de unidad de precio (por ejemplo, «kilo» o «unidad»).
         * @return Etiqueta abreviada: «kg» para kilo, «unidad» para cualquier otro valor.
         */
        private fun etiquetaUnidadPrecio(unidad: String): String {
            return if (unidad == "kilo") "kg" else "unidad"
        }
    }

    /** Constantes utilizadas para el cálculo del radio de cobertura de notificaciones. */
    companion object {
        /** Radio predeterminado en metros cuando no se puede calcular uno específico. */
        private const val RADIO_DEFAULT_METROS = 5_000.0
        /** Margen adicional en metros que se suma al radio máximo calculado. */
        private const val RADIO_MARGEN_METROS = 2_000.0
        /** Radio mínimo permitido en metros para las notificaciones de cobertura. */
        private const val RADIO_MINIMO_METROS = 1_000.0
        /** Radio máximo permitido en metros para las notificaciones de cobertura. */
        private const val RADIO_MAXIMO_METROS = 50_000.0
    }
}
