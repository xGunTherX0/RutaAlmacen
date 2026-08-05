package com.example.rutaalmacen

import android.Manifest
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rutaalmacen.productos.OfertaUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
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
    private var filtroAbiertoAhora = false
    private var filtroDistanciaActiva = false
    private var distanciaMaximaMetros: Double = 5000.0
    private var filtroOfertas = false
    private lateinit var contenedorCarga: View
    private val cacheUsuarios: MutableMap<String, DocumentSnapshot> = mutableMapOf()
    private var inventarioPublicoCache: List<DocumentSnapshot>? = null
    private var inventarioPrivadoCache: List<DocumentSnapshot>? = null
    private var ubicacionCache: Location? = null
    private var ubicacionCacheTiempo = 0L

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

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        val header = findViewById<View>(R.id.header_resultados)
        val paddingHeaderBase = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_resultados)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                0,
                barrasDelSistema.right,
                barrasDelSistema.bottom,
            )
            header.setPadding(
                header.paddingLeft,
                paddingHeaderBase + barrasDelSistema.top,
                header.paddingRight,
                header.paddingBottom,
            )
            insets
        }

        val recyclerResultados = findViewById<RecyclerView>(R.id.recycler_resultados)
        contenedorCarga = findViewById(R.id.contenedor_carga_comprador)
        val campoBusqueda = findViewById<TextInputEditText>(R.id.campo_busqueda_producto)

        adaptadorResultados = AdaptadorResultados(
            resultados = resultados,
            onLlegar = { resultado -> abrirNavegacion(resultado) },
            onVerStock = { resultado -> abrirStockAlmacen(resultado) },
            onReportar = { resultado -> confirmarReportarProducto(resultado) },
        )
        recyclerResultados.layoutManager = LinearLayoutManager(this)
        recyclerResultados.adapter = adaptadorResultados

        configurarBotonVolver()
        configurarChipsFiltro()
        configurarCampoBusqueda(campoBusqueda)

        findViewById<MaterialButton>(R.id.boton_info_alertas).setOnClickListener {
            startActivity(Intent(this, InfoAlertasActivity::class.java))
        }

        filtroAbiertoAhora = intent.getBooleanExtra("filtro_abierto", false)
        filtroDistanciaActiva = intent.getBooleanExtra("filtro_distancia", false)
        distanciaMaximaMetros = intent.getDoubleExtra("distancia_maxima", 5000.0)
        filtroOfertas = intent.getBooleanExtra("filtro_ofertas", false)

        val consultaIntent = intent.getStringExtra("consulta_inicial")
        val categoriaIntent = intent.getStringExtra("categoria")

        actualizarEstadoChips(
            findViewById(R.id.chip_todos),
            findViewById(R.id.chip_abierto),
            findViewById(R.id.chip_distancia),
            findViewById(R.id.chip_ofertas),
        )

        if (!categoriaIntent.isNullOrBlank()) {
            categoriaSeleccionada = categoriaIntent
            campoBusqueda.setText(categoriaIntent)
            lifecycleScope.launch { buscarProductosPorCategoria(categoriaIntent) }
        } else if (!consultaIntent.isNullOrBlank()) {
            campoBusqueda.setText(consultaIntent)
            lifecycleScope.launch { buscarProductos(consultaIntent) }
        } else {
            campoBusqueda.setHint("Todos los productos")
            lifecycleScope.launch { cargarProductosIniciales() }
        }
    }

    private fun configurarBotonVolver() {
        findViewById<MaterialButton>(R.id.boton_volver_buscar).setOnClickListener {
            val intent = Intent(this, ListaComprasActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
            finish()
        }
    }

    private fun configurarCampoBusqueda(campoBusqueda: TextInputEditText) {
        campoBusqueda.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val texto = campoBusqueda.text?.toString().orEmpty().trim()
                if (texto.isNotBlank()) {
                    ocultarTeclado()
                    lifecycleScope.launch { buscarProductos(texto) }
                }
                true
            } else {
                false
            }
        }
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

    private fun configurarChipsFiltro() {
        val chipTodos = findViewById<MaterialButton>(R.id.chip_todos)
        val chipAbierto = findViewById<MaterialButton>(R.id.chip_abierto)
        val chipDistancia = findViewById<MaterialButton>(R.id.chip_distancia)
        val chipOfertas = findViewById<MaterialButton>(R.id.chip_ofertas)

        chipTodos.setOnClickListener {
            filtroAbiertoAhora = false
            filtroDistanciaActiva = false
            filtroOfertas = false
            actualizarEstadoChips(chipTodos, chipAbierto, chipDistancia, chipOfertas)
            aplicarFiltros()
        }

        chipAbierto.setOnClickListener {
            filtroAbiertoAhora = !filtroAbiertoAhora
            if (filtroAbiertoAhora) {
                filtroDistanciaActiva = false
                filtroOfertas = false
            }
            actualizarEstadoChips(chipTodos, chipAbierto, chipDistancia, chipOfertas)
            aplicarFiltros()
        }

        chipDistancia.setOnClickListener {
            mostrarDialogoDistancia()
        }

        chipOfertas.setOnClickListener {
            filtroOfertas = !filtroOfertas
            if (filtroOfertas) {
                filtroAbiertoAhora = false
                filtroDistanciaActiva = false
            }
            actualizarEstadoChips(chipTodos, chipAbierto, chipDistancia, chipOfertas)
            aplicarFiltros()
        }
    }

    private fun actualizarEstadoChips(
        chipTodos: MaterialButton,
        chipAbierto: MaterialButton,
        chipDistancia: MaterialButton,
        chipOfertas: MaterialButton,
    ) {
        val colorPrimario = androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimary)

        chipTodos.setBackgroundColor(
            if (!filtroAbiertoAhora && !filtroDistanciaActiva && !filtroOfertas) colorPrimario else android.graphics.Color.TRANSPARENT
        )
        chipTodos.setTextColor(
            if (!filtroAbiertoAhora && !filtroDistanciaActiva && !filtroOfertas) android.graphics.Color.WHITE else colorPrimario
        )

        chipAbierto.setBackgroundColor(
            if (filtroAbiertoAhora) colorPrimario else android.graphics.Color.TRANSPARENT
        )
        chipAbierto.setTextColor(
            if (filtroAbiertoAhora) android.graphics.Color.WHITE else colorPrimario
        )

        val textoDistancia = if (filtroDistanciaActiva) "Hasta ${distanciaMaximaMetros.toInt()} m" else "Distancia"
        chipDistancia.text = textoDistancia
        chipDistancia.setBackgroundColor(
            if (filtroDistanciaActiva) colorPrimario else android.graphics.Color.TRANSPARENT
        )
        chipDistancia.setTextColor(
            if (filtroDistanciaActiva) android.graphics.Color.WHITE else colorPrimario
        )

        chipOfertas.setBackgroundColor(
            if (filtroOfertas) colorPrimario else android.graphics.Color.TRANSPARENT
        )
        chipOfertas.setTextColor(
            if (filtroOfertas) android.graphics.Color.WHITE else colorPrimario
        )
    }

    private fun mostrarDialogoDistancia() {
        val opciones = listOf("500 m", "1 km", "3 km", "5 km", "10 km", "Sin límite")
        val seleccionActual = when {
            distanciaMaximaMetros <= 500 -> 0
            distanciaMaximaMetros <= 1000 -> 1
            distanciaMaximaMetros <= 3000 -> 2
            distanciaMaximaMetros <= 5000 -> 3
            distanciaMaximaMetros <= 10000 -> 4
            else -> 5
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Seleccionar rango de distancia")
            .setSingleChoiceItems(opciones.toTypedArray(), seleccionActual) { dialogo, posicion ->
                distanciaMaximaMetros = when (posicion) {
                    0 -> 500.0
                    1 -> 1000.0
                    2 -> 3000.0
                    3 -> 5000.0
                    4 -> 10000.0
                    else -> 50000.0
                }
                filtroDistanciaActiva = true
                filtroAbiertoAhora = false
                filtroOfertas = false
                dialogo.dismiss()
                val chipTodos = findViewById<MaterialButton>(R.id.chip_todos)
                val chipAbierto = findViewById<MaterialButton>(R.id.chip_abierto)
                val chipDistancia = findViewById<MaterialButton>(R.id.chip_distancia)
                val chipOfertas = findViewById<MaterialButton>(R.id.chip_ofertas)
                actualizarEstadoChips(chipTodos, chipAbierto, chipDistancia, chipOfertas)
                aplicarFiltros()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun ocultarTeclado() {
        val vista = currentFocus ?: View(this)
        val gestor = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        gestor.hideSoftInputFromWindow(vista.windowToken, 0)
        vista.clearFocus()
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
                    motivoOferta = if (ofertaVigente) datosOferta.motivoOferta else "",
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
            "motivoOferta" to "",
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
            val cumpleCategoria = categoria == "Todas" || resultado.categoria == categoria
            val cumpleAbierto = !filtroAbiertoAhora || resultado.abiertoAhora
            val cumpleDistancia = !filtroDistanciaActiva || 
                (resultado.distanciaMetros != null && resultado.distanciaMetros <= distanciaMaximaMetros)
            val cumpleOferta = !filtroOfertas || resultado.enOferta
            
            cumpleCategoria && cumpleAbierto && cumpleDistancia && cumpleOferta
        }

        val ordenados = filtrados.sortedWith(
            compareBy<ResultadoBusqueda> { !it.abiertoAhora }
                .thenBy { it.distanciaMetros ?: Double.MAX_VALUE },
        )

        resultados.clear()
        resultados.addAll(ordenados)
        adaptadorResultados.notifyDataSetChanged()
        
        val textoContador = findViewById<android.widget.TextView>(R.id.texto_contador_resultados)
        val texto = if (resultados.size == 1) "1 resultado cerca de ti" else "${resultados.size} resultados cerca de ti"
        textoContador.text = texto
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
        if (!vendedorId.isNullOrBlank()) return vendedorId
        return documento.reference.parent.parent?.id
    }

    private suspend fun notificarVendedoresSinProducto(
        producto: String,
        documentosConProducto: Collection<DocumentSnapshot>,
        ubicacionComprador: Location?,
        esCategoria: Boolean,
    ) {
        val validacion = FiltroContenido.validarNombreProducto(producto)
        if (!validacion.esValido) return

        val productoNormalizado = FiltroContenido.normalizar(producto)
        val productoId = FiltroContenido.normalizarParaFiltro(producto).replace(" ", "_")
        val vendedoresConProducto = documentosConProducto.mapNotNull { obtenerVendedorIdDocumento(it) }.toSet()

        val vendedores = baseDatos.collection(Constantes.COLECCION_USUARIOS)
            .whereEqualTo("rol", Constantes.ROL_VENDEDOR)
            .get()
            .await()
            .documents

        if (vendedores.isEmpty()) return

        val encontroAlgo = vendedoresConProducto.isNotEmpty()
        val mensaje = if (esCategoria) {
            if (encontroAlgo) "¡Oportunidad! Hay compradores buscando la categoría $producto."
            else "¡Oportunidad! Un comprador buscó la categoría $producto y no encontró productos en tu stock."
        } else {
            if (encontroAlgo) "¡Oportunidad! Hay compradores buscando $producto cerca de ti."
            else "¡Oportunidad! Un comprador buscó $producto y no lo encontró en tu stock."
        }

        val radioMetros = calcularRadioCobertura(documentosConProducto, ubicacionComprador)
        val fechaCreacion = System.currentTimeMillis()
        val compradorId = autenticacion.currentUser?.uid ?: ""

        vendedores.forEach { vendedor ->
            val vendedorId = vendedor.id
            if (vendedorId.isBlank() || vendedoresConProducto.contains(vendedorId)) return@forEach
            val idAlerta = "${productoId}_${vendedorId}_$fechaCreacion"
            val datos = mapOf(
                "producto" to producto,
                "productoNormalizado" to productoNormalizado,
                "vendedorId" to vendedorId,
                "compradorId" to compradorId,
                "mensaje" to mensaje,
                "latitudCentro" to ubicacionComprador?.latitude,
                "longitudCentro" to ubicacionComprador?.longitude,
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
        if (ubicacionComprador == null) return RADIO_DEFAULT_METROS
        var radioMaximo = 0.0
        var hayAlmacen = false
        for (documento in documentosConProducto) {
            val lat = documento.getDouble("latitud")
            val lon = documento.getDouble("longitud")
            if (lat == null || lon == null) continue
            hayAlmacen = true
            val distancia = UbicacionUtil.calcularDistancia(
                ubicacionComprador.latitude, ubicacionComprador.longitude, lat, lon
            )
            if (distancia > radioMaximo) radioMaximo = distancia
        }
        val radioCalculado = if (hayAlmacen) radioMaximo + RADIO_MARGEN_METROS else RADIO_DEFAULT_METROS
        return radioCalculado.coerceIn(RADIO_MINIMO_METROS, RADIO_MAXIMO_METROS)
    }

    private suspend fun obtenerUbicacionParaAlerta(): Location? {
        val cacheada = obtenerUbicacionCacheada()
        if (cacheada != null) return cacheada
        return obtenerUbicacionActual()
    }

    private suspend fun obtenerUbicacionCacheada(): Location? {
        val cacheHit = UbicacionUtil.obtenerUbicacionConCache(
            ubicacionCache, ubicacionCacheTiempo, HorarioUtil.TIEMPO_CACHE_UBICACION_MS
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
        val ahora = System.currentTimeMillis()
        val cacheHit = UbicacionUtil.obtenerUbicacionConCache(
            ubicacionCache, ubicacionCacheTiempo, HorarioUtil.TIEMPO_CACHE_UBICACION_MS
        )
        if (cacheHit != null) return cacheHit
        val fused = UbicacionUtil.obtenerUbicacionFused(proveedorUbicacion)
        if (fused != null) {
            ubicacionCache = fused
            ubicacionCacheTiempo = ahora
            return fused
        }
        val sistema = UbicacionUtil.obtenerUbicacionSistema(this)
        if (sistema != null) {
            ubicacionCache = sistema
            ubicacionCacheTiempo = ahora
            return sistema
        }
        return null
    }

    private fun mostrarMensaje(mensaje: String) {
        android.widget.Toast.makeText(this, mensaje, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun abrirNavegacion(resultado: ResultadoBusqueda) {
        val lat = resultado.latitudAlmacen
        val lon = resultado.longitudAlmacen
        if (lat == null || lon == null) {
            mostrarMensaje("Ubicación del almacén no disponible")
            return
        }
        val etiqueta = resultado.nombreAlmacen.ifBlank { "Almacén" }
        val uri = Uri.parse("google.navigation:q=$lat,$lon($etiqueta)")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")))
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

    private fun confirmarReportarProducto(resultado: ResultadoBusqueda) {
        if (resultado.vendedorId.isBlank()) {
            mostrarMensaje("No se puede reportar este producto")
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Reportar producto")
            .setMessage("¿Deseas reportar \"${resultado.nombreProducto}\" como inapropiado? El administrador revisará este reporte y podrá eliminar el producto o bloquear al vendedor.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Reportar") { _, _ ->
                lifecycleScope.launch { reportarProducto(resultado) }
            }
            .show()
    }

    private suspend fun reportarProducto(resultado: ResultadoBusqueda) {
        try {
            val compradorId = autenticacion.currentUser?.uid.orEmpty()
            val fechaReporte = System.currentTimeMillis()
            val idReporte = "prod_${resultado.vendedorId}_${resultado.productoId}_$fechaReporte"
            val datos = mapOf(
                "producto" to resultado.nombreProducto,
                "productoId" to resultado.productoId,
                "vendedorId" to resultado.vendedorId,
                "compradorId" to compradorId,
                "mensaje" to "Producto reportado como inapropiado por comprador",
                "fechaReporte" to fechaReporte,
                "estado" to "pendiente",
                "tipoReporte" to "producto",
            )
            baseDatos.collection(Constantes.COLECCION_ALERTAS_REPORTADAS)
                .document(idReporte)
                .set(datos)
                .await()
            mostrarMensaje("Producto reportado. El administrador lo revisará.")
        } catch (_: Exception) {
            mostrarMensaje("No se pudo enviar el reporte")
        }
    }

    private suspend fun buscarInventarioLocalPublico(consulta: String): List<DocumentSnapshot> {
        if (consulta.isBlank()) return emptyList()
        return obtenerInventarioPublicoCache().filter {
            val nombre = it.getString("nombre").orEmpty()
            val normalizado = it.getString("nombreNormalizado")?.takeIf { s -> s.isNotBlank() } ?: FiltroContenido.normalizar(nombre)
            normalizado.startsWith(consulta)
        }
    }

    private suspend fun buscarInventarioLocalPrivado(consulta: String): List<DocumentSnapshot> {
        if (consulta.isBlank()) return emptyList()
        return try {
            obtenerInventarioPrivadoCache().filter {
                val nombre = it.getString("nombre").orEmpty()
                val normalizado = it.getString("nombreNormalizado")?.takeIf { s -> s.isNotBlank() } ?: FiltroContenido.normalizar(nombre)
                normalizado.startsWith(consulta)
            }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun buscarInventarioLocalPublicoPorCategoria(categoria: String): List<DocumentSnapshot> {
        if (categoria.isBlank() || categoria == "Todas") return emptyList()
        return obtenerInventarioPublicoCache().filter { it.getString("categoria") == categoria }
    }

    private suspend fun obtenerInventarioPublicoCache(): List<DocumentSnapshot> {
        val cache = inventarioPublicoCache
        if (cache != null) return cache
        val cacheFirestore = try {
            baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO).get(Source.CACHE).await().documents
        } catch (_: Exception) { emptyList() }
        if (cacheFirestore.isNotEmpty()) {
            inventarioPublicoCache = cacheFirestore
            return cacheFirestore
        }
        val resultado = baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO).get().await()
        return resultado.documents.also { inventarioPublicoCache = it }
    }

    private suspend fun obtenerInventarioPublicoServidor(): List<DocumentSnapshot> {
        val resultado = baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO).get().await()
        return resultado.documents.also { inventarioPublicoCache = it }
    }

    private suspend fun obtenerInventarioPrivadoCache(): List<DocumentSnapshot> {
        val cache = inventarioPrivadoCache
        if (cache != null) return cache
        val cacheFirestore = try {
            baseDatos.collectionGroup("Inventario").get(Source.CACHE).await().documents
        } catch (_: Exception) { emptyList() }
        if (cacheFirestore.isNotEmpty()) {
            inventarioPrivadoCache = cacheFirestore
            return cacheFirestore
        }
        val resultado = baseDatos.collectionGroup("Inventario").get().await()
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
        val motivoOferta: String = "",
    )

    private class AdaptadorResultados(
        private val resultados: List<ResultadoBusqueda>,
        private val onLlegar: (ResultadoBusqueda) -> Unit,
        private val onVerStock: (ResultadoBusqueda) -> Unit,
        private val onReportar: (ResultadoBusqueda) -> Unit,
    ) : RecyclerView.Adapter<AdaptadorResultados.VistaResultado>() {

        class VistaResultado(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tarjeta: com.google.android.material.card.MaterialCardView = itemView.findViewById(R.id.tarjeta_resultado)
            val textoNombre: android.widget.TextView = itemView.findViewById(R.id.texto_nombre_producto)
            val textoPrecio: android.widget.TextView = itemView.findViewById(R.id.texto_precio_producto)
            val contenedorPrecioOferta: android.widget.LinearLayout = itemView.findViewById(R.id.contenedor_precio_oferta)
            val textoPrecioOferta: android.widget.TextView = itemView.findViewById(R.id.texto_precio_oferta)
            val textoUnidadOferta: android.widget.TextView = itemView.findViewById(R.id.texto_unidad_oferta)
            val badgeDescuento: android.widget.TextView = itemView.findViewById(R.id.badge_descuento)
            val textoTiempoRestante: android.widget.TextView = itemView.findViewById(R.id.texto_tiempo_restante)
            val textoMotivoOferta: android.widget.TextView = itemView.findViewById(R.id.texto_motivo_oferta)
            val textoDescripcion: android.widget.TextView = itemView.findViewById(R.id.texto_descripcion_producto)
            val textoAlmacen: android.widget.TextView = itemView.findViewById(R.id.texto_almacen_producto)
            val textoHorario: android.widget.TextView = itemView.findViewById(R.id.texto_horario_almacen)
            val textoEstadoHorario: android.widget.TextView = itemView.findViewById(R.id.texto_estado_horario)
            val textoDistancia: android.widget.TextView = itemView.findViewById(R.id.texto_distancia_producto)
            val textoEstado: android.widget.TextView = itemView.findViewById(R.id.texto_estado_producto)
            val botonVerStock: android.widget.TextView = itemView.findViewById(R.id.boton_ver_stock)
            val botonLlegar: android.widget.TextView = itemView.findViewById(R.id.boton_llegar_almacen)
            val botonReportar: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.boton_reportar_producto)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaResultado {
            val vista = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_resultado_producto, parent, false)
            return VistaResultado(vista)
        }

        override fun onBindViewHolder(holder: VistaResultado, position: Int) {
            val r = resultados[position]
            val ctx = holder.itemView.context
            holder.textoNombre.text = r.nombreProducto

            val precioTxt = "$${String.format(Locale.forLanguageTag("es-CL"), "%.0f", r.precio)} / ${if (r.unidadPrecio == "kilo") "kg" else "unidad"}"
            val ofertaVigente = r.precioOferta != null && OfertaUtil.estaVigente(r.enOferta, r.fechaFinOferta)

            if (ofertaVigente) {
                holder.tarjeta.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.oferta_card_fondo))
                holder.textoPrecio.text = "Antes: $precioTxt"
                holder.textoPrecio.paintFlags = holder.textoPrecio.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                holder.textoPrecio.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.oferta_precio_tachado))
                holder.textoPrecio.textSize = 13f
                holder.contenedorPrecioOferta.visibility = View.VISIBLE
                holder.textoPrecioOferta.text = "$${String.format(Locale.forLanguageTag("es-CL"), "%.0f", r.precioOferta)}"
                holder.textoUnidadOferta.text = "/ ${if (r.unidadPrecio == "kilo") "kg" else "unidad"}"
                holder.badgeDescuento.visibility = View.VISIBLE
                holder.badgeDescuento.text = "-${r.descuentoPorcentaje ?: 0}% OFF"
                holder.textoTiempoRestante.visibility = View.VISIBLE
                holder.textoTiempoRestante.text = OfertaUtil.tiempoRestanteTexto(r.fechaFinOferta)
                if (r.motivoOferta.isNotBlank()) {
                    holder.textoMotivoOferta.visibility = View.VISIBLE
                    holder.textoMotivoOferta.text = r.motivoOferta
                } else {
                    holder.textoMotivoOferta.visibility = View.GONE
                }
            } else {
                holder.tarjeta.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.fondo_card))
                holder.textoPrecio.text = precioTxt
                holder.textoPrecio.paintFlags = holder.textoPrecio.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                holder.textoPrecio.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.colorPrimary))
                holder.textoPrecio.textSize = 14f
                holder.contenedorPrecioOferta.visibility = View.GONE
                holder.badgeDescuento.visibility = View.GONE
                holder.textoTiempoRestante.visibility = View.GONE
                holder.textoMotivoOferta.visibility = View.GONE
            }

            holder.textoDescripcion.visibility = if (r.descripcion.isBlank()) View.GONE else View.VISIBLE
            if (r.descripcion.isNotBlank()) holder.textoDescripcion.text = r.descripcion
            holder.textoAlmacen.text = r.nombreAlmacen
            holder.textoHorario.text = r.horarioAtencion
            holder.textoEstadoHorario.text = if (r.abiertoAhora) "Abierto" else "Cerrado"
            holder.textoEstadoHorario.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    ctx,
                    if (r.abiertoAhora) R.color.stock_verde else R.color.stock_rojo,
                ),
            )
            holder.textoEstadoHorario.setBackgroundResource(
                if (r.abiertoAhora) R.drawable.bg_chip_estado_ok else R.drawable.bg_chip_estado_mal,
            )
            holder.textoDistancia.text = formatearDistancia(r.distanciaMetros)
            holder.textoEstado.text = if (r.disponible) "Disponible" else "Agotado"
            holder.textoEstado.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    ctx,
                    if (r.disponible) R.color.stock_verde else R.color.stock_rojo,
                ),
            )
            holder.textoEstado.setBackgroundResource(
                if (r.disponible) R.drawable.bg_chip_estado_ok else R.drawable.bg_chip_estado_mal,
            )

            holder.botonLlegar.setOnClickListener { onLlegar(r) }
            holder.botonVerStock.isEnabled = r.vendedorId.isNotBlank()
            holder.botonVerStock.alpha = if (r.vendedorId.isNotBlank()) 1f else 0.5f
            holder.botonVerStock.setOnClickListener { onVerStock(r) }
            holder.botonReportar.setOnClickListener { onReportar(r) }
        }

        override fun getItemCount(): Int = resultados.size

        private fun formatearDistancia(metros: Double?): String {
            if (metros == null) return "Sin ubicación"
            return if (metros >= 1000) "${String.format(Locale.forLanguageTag("es-CL"), "%.1f", metros / 1000.0)} km" else "${metros.toInt()} m"
        }
    }

    companion object {
        private const val RADIO_DEFAULT_METROS = 5_000.0
        private const val RADIO_MARGEN_METROS = 2_000.0
        private const val RADIO_MINIMO_METROS = 1_000.0
        private const val RADIO_MAXIMO_METROS = 50_000.0
    }
}
