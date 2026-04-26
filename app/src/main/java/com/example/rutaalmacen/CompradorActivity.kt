package com.example.rutaalmacen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.view.View
import java.util.Calendar
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.resume

class CompradorActivity : AppCompatActivity() {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val proveedorUbicacion: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private val coleccionInventarioPublico = "InventarioPublico"

    private val resultados: MutableList<ResultadoBusqueda> = mutableListOf()
    private val resultadosBase: MutableList<ResultadoBusqueda> = mutableListOf()
    private lateinit var adaptadorResultados: AdaptadorResultados
    private var tareaBusqueda: Job? = null
    private var busquedaPendiente: String? = null
    private var categoriaPendiente: String? = null
    private var avisoSinUbicacionMostrado = false
    private var categoriaSeleccionada = "Todas"
    private var distanciaMaximaSeleccionada = DISTANCIA_MAXIMA_METROS
    private lateinit var textoDistanciaFiltro: android.widget.TextView
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

    private val opcionesDistancia = listOf(
        OpcionDistancia("0,5 km", 500f),
        OpcionDistancia("1 km", 1000f),
        OpcionDistancia("2 km", 2000f),
        OpcionDistancia("5 km", 5000f),
        OpcionDistancia("10 km", 10000f),
        OpcionDistancia("20 km", 20000f),
        OpcionDistancia("50 km", 50000f),
        OpcionDistancia("100 km", 100000f),
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
        val grupoDistancia = findViewById<ChipGroup>(R.id.grupo_distancia)
        textoDistanciaFiltro = findViewById(R.id.texto_distancia_filtro)
        val encabezadoFiltros = findViewById<android.view.View>(R.id.encabezado_filtros)
        val contenidoFiltros = findViewById<android.view.View>(R.id.contenido_filtros)
        val iconoFiltros = findViewById<ImageView>(R.id.icono_filtros)
        val encabezadoDistancia = findViewById<android.view.View>(R.id.encabezado_distancia)
        val contenidoDistancia = findViewById<android.view.View>(R.id.contenido_distancia)
        val navegacion = findViewById<BottomNavigationView>(R.id.nav_comprador)
        val iconoDistancia = findViewById<ImageView>(R.id.icono_distancia)
        contenedorCarga = findViewById(R.id.contenedor_carga_comprador)

        adaptadorResultados = AdaptadorResultados(
            resultados = resultados,
            onLlegar = { resultado -> abrirNavegacion(resultado) },
            onVerStock = { resultado -> abrirStockAlmacen(resultado) },
        )
        recyclerResultados.layoutManager = LinearLayoutManager(this)
        recyclerResultados.adapter = adaptadorResultados

        configurarDesplegable(encabezadoFiltros, contenidoFiltros, iconoFiltros, expandidoInicial = false)
        configurarDesplegable(encabezadoDistancia, contenidoDistancia, iconoDistancia, expandidoInicial = false)
        configurarFiltros(campoCategoria, grupoDistancia, campoBusqueda)
        configurarNavegacion(navegacion)

        campoBusqueda.doOnTextChanged { texto, _, _, _ ->
            val consulta = texto?.toString()?.trim().orEmpty()
            lanzarBusqueda(consulta, conDelay = true)
        }
    }

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

    private suspend fun buscarProductos(consulta: String) {
        val usuario = autenticacion.currentUser
        if (usuario == null) {
            mostrarMensaje("No hay un usuario activo")
            return
        }

        if (!tienePermisoUbicacion()) {
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
            val ubicacionComprador = obtenerUbicacionActual()
            if (ubicacionComprador == null) {
                if (!avisoSinUbicacionMostrado) {
                    mostrarMensaje("No se pudo obtener la ubicación, se mostrará sin distancia")
                    avisoSinUbicacionMostrado = true
                }
            } else {
                avisoSinUbicacionMostrado = false
            }

            val consultaNormalizada = normalizarTexto(consulta)
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
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo completar la búsqueda")
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

        if (!tienePermisoUbicacion()) {
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
            val ubicacionComprador = obtenerUbicacionActual()
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
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo completar la búsqueda")
        } finally {
            mostrarCarga(false)
        }
    }

    private fun configurarFiltros(
        campoCategoria: AutoCompleteTextView,
        grupoDistancia: ChipGroup,
        campoBusqueda: TextInputEditText,
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
            val consulta = campoBusqueda.text?.toString()?.trim().orEmpty()
            lanzarBusqueda(consulta, conDelay = false)
        }

        grupoDistancia.removeAllViews()
        opcionesDistancia.forEach { opcion ->
            val chip = Chip(this).apply {
                text = opcion.etiqueta
                isCheckable = true
                isClickable = true
                isChecked = opcion.metros == DISTANCIA_MAXIMA_METROS
                setTextAppearanceResource(R.style.TextAppearance_RutaAlmacen_Comprador_BodyMedium)
            }
            grupoDistancia.addView(chip)
        }
        distanciaMaximaSeleccionada = DISTANCIA_MAXIMA_METROS
        actualizarEtiquetaDistancia()
        grupoDistancia.setOnCheckedStateChangeListener { grupo, ids ->
            val seleccionado = ids.firstOrNull()
            val chipSeleccionado = if (seleccionado != null) {
                grupo.findViewById<Chip>(seleccionado)
            } else {
                null
            }
            val etiqueta = chipSeleccionado?.text?.toString().orEmpty()
            val opcion = opcionesDistancia.firstOrNull { it.etiqueta == etiqueta }
            distanciaMaximaSeleccionada = opcion?.metros ?: DISTANCIA_MAXIMA_METROS
            actualizarEtiquetaDistancia()
            aplicarFiltros()
        }
    }

    private fun configurarDesplegable(
        encabezado: android.view.View,
        contenido: android.view.View,
        icono: ImageView,
        expandidoInicial: Boolean,
    ) {
        var expandido = expandidoInicial
        fun aplicarEstado() {
            contenido.visibility = if (expandido) android.view.View.VISIBLE else android.view.View.GONE
            icono.rotation = if (expandido) 180f else 0f
        }
        aplicarEstado()
        encabezado.setOnClickListener {
            expandido = !expandido
            aplicarEstado()
        }
    }

    private fun lanzarBusqueda(consulta: String, conDelay: Boolean) {
        tareaBusqueda?.cancel()
        tareaBusqueda = lifecycleScope.launch {
            if (conDelay) {
                delay(200)
            }
            val texto = consulta.trim()
            if (texto.isBlank()) {
                if (categoriaSeleccionada == "Todas") {
                    resultadosBase.clear()
                    resultados.clear()
                    adaptadorResultados.notifyDataSetChanged()
                    return@launch
                }
                buscarProductosPorCategoria(categoriaSeleccionada)
                return@launch
            }
            buscarProductos(texto)
        }
    }

    private suspend fun construirResultados(
        documentos: Collection<DocumentSnapshot>,
        ubicacionComprador: Location?,
    ): List<ResultadoBusqueda> {
        val nuevosResultados = mutableListOf<ResultadoBusqueda>()

        for (documento in documentos) {
            val vendedorId = documento.getString("vendedorId").orEmpty()
            val documentoUsuario = if (!vendedorId.isNullOrBlank()) {
                cacheUsuarios[vendedorId]
                    ?: baseDatos.collection("Usuarios")
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
            val nombreAlmacen = documento.getString("nombreAlmacen")
                ?: documentoUsuario?.getString("nombreAlmacen")
                ?: documentoUsuario?.getString("nombre")
                ?: "Almacén sin nombre"
            val latitudAlmacen = documento.getDouble("latitud")
                ?: documentoUsuario?.getDouble("latitud")
            val longitudAlmacen = documento.getDouble("longitud")
                ?: documentoUsuario?.getDouble("longitud")

            val horarioMananaInicio = documento.getString("horarioMananaInicio") ?: HORARIO_MANANA_INICIO_TEXTO
            val horarioMananaFin = documento.getString("horarioMananaFin") ?: HORARIO_MANANA_FIN_TEXTO
            val horarioTardeInicio = documento.getString("horarioTardeInicio") ?: HORARIO_TARDE_INICIO_TEXTO
            val horarioTardeFin = documento.getString("horarioTardeFin") ?: HORARIO_TARDE_FIN_TEXTO
            val horarioAtencion = documento.getString("horarioAtencion")
                ?: "$horarioMananaInicio - $horarioMananaFin / $horarioTardeInicio - $horarioTardeFin"
            val abiertoAhora = estaAlmacenAbiertoAhora(
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
                calcularDistancia(
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
                    nombreAlmacen = nombreAlmacen,
                    horarioAtencion = horarioAtencion,
                    abiertoAhora = abiertoAhora,
                    distanciaMetros = distancia,
                    latitudAlmacen = latitudAlmacen,
                    longitudAlmacen = longitudAlmacen,
                    disponible = disponibleProducto,
                ),
            )
        }

        return nuevosResultados
    }

    private fun mostrarCarga(mostrar: Boolean) {
        contenedorCarga.visibility = if (mostrar) View.VISIBLE else View.GONE
    }

    private fun aplicarFiltros() {
        val categoria = categoriaSeleccionada
        val max = distanciaMaximaSeleccionada.toDouble()
        val filtrarDistancia = distanciaMaximaSeleccionada < DISTANCIA_MAXIMA_METROS
        val filtrados = resultadosBase.filter { resultado ->
            val cumpleCategoria = categoria == "Todas" || resultado.categoria == categoria
            val distancia = resultado.distanciaMetros
            val cumpleDistancia = if (distancia == null) {
                !filtrarDistancia
            } else {
                distancia <= max
            }
            cumpleCategoria && cumpleDistancia
        }

        val ordenados = filtrados.sortedWith(
            compareBy<ResultadoBusqueda> { !it.abiertoAhora }
                .thenBy { it.distanciaMetros ?: Double.MAX_VALUE },
        )

        resultados.clear()
        resultados.addAll(ordenados)
        adaptadorResultados.notifyDataSetChanged()
    }

    private fun actualizarEtiquetaDistancia() {
        val textoMax = formatearDistanciaFiltro(distanciaMaximaSeleccionada)
        textoDistanciaFiltro.text = "Distancia: hasta $textoMax"
    }

    private fun estaAlmacenAbiertoAhora(
        mananaInicioTexto: String,
        mananaFinTexto: String,
        tardeInicioTexto: String,
        tardeFinTexto: String,
    ): Boolean {
        val calendario = Calendar.getInstance()
        val minutosActuales = calendario.get(Calendar.HOUR_OF_DAY) * 60 + calendario.get(Calendar.MINUTE)
        val mananaInicio = convertirHoraAMinutos(mananaInicioTexto) ?: HORARIO_MANANA_INICIO
        val mananaFin = convertirHoraAMinutos(mananaFinTexto) ?: HORARIO_MANANA_FIN
        val tardeInicio = convertirHoraAMinutos(tardeInicioTexto) ?: HORARIO_TARDE_INICIO
        val tardeFin = convertirHoraAMinutos(tardeFinTexto) ?: HORARIO_TARDE_FIN
        val enTurnoManana = minutosActuales in mananaInicio until mananaFin
        val enTurnoTarde = minutosActuales in tardeInicio until tardeFin
        return enTurnoManana || enTurnoTarde
    }

    private fun convertirHoraAMinutos(hora: String): Int? {
        val partes = hora.split(":")
        if (partes.size != 2) {
            return null
        }
        val horas = partes[0].toIntOrNull() ?: return null
        val minutos = partes[1].toIntOrNull() ?: return null
        if (horas !in 0..23 || minutos !in 0..59) {
            return null
        }
        return horas * 60 + minutos
    }

    private fun formatearDistanciaFiltro(metros: Float): String {
        return if (metros >= 1000f) {
            val km = metros / 1000f
            "${String.format(Locale.forLanguageTag("es-CL"), "%.1f", km)} km"
        } else {
            "${metros.toInt()} m"
        }
    }

    private suspend fun registrarBusquedaFallida(consulta: String, ubicacion: Location?) {
        val datos = mapOf(
            "nombreProducto" to consulta,
            "latitud" to ubicacion?.latitude,
            "longitud" to ubicacion?.longitude,
            "resultadoExitoso" to false,
        )

        baseDatos.collection("Busquedas_Historicas")
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

        val vendedores = baseDatos.collection("Usuarios")
            .whereEqualTo("rol", "vendedor")
            .get()
            .await()
            .documents

        if (vendedores.isEmpty()) {
            return
        }

        val mensaje = if (esCategoria) {
            "¡Oportunidad! Un comprador buscó la categoría $producto y no encontró productos en tu stock."
        } else {
            "¡Oportunidad! Un comprador buscó $producto y no lo encontró en tu stock."
        }
        val radioMetros = if (distanciaMaximaSeleccionada < DISTANCIA_MAXIMA_METROS) {
            distanciaMaximaSeleccionada.toDouble()
        } else {
            null
        }
        val fechaCreacion = System.currentTimeMillis()
        val latitud = ubicacionComprador?.latitude
        val longitud = ubicacionComprador?.longitude

        vendedores.forEach { vendedor ->
            val vendedorId = vendedor.id
            if (vendedorId.isBlank() || vendedoresConProducto.contains(vendedorId)) {
                return@forEach
            }
            val idAlerta = "${productoId}_$vendedorId"
            val datos = mapOf(
                "producto" to producto,
                "productoNormalizado" to productoNormalizado,
                "vendedorId" to vendedorId,
                "mensaje" to mensaje,
                "latitudCentro" to latitud,
                "longitudCentro" to longitud,
                "radioMetros" to radioMetros,
                "fechaCreacion" to fechaCreacion,
                "totalBusquedas" to FieldValue.increment(1),
            )
            baseDatos.collection("Notificaciones_IA")
                .document(idAlerta)
                .set(datos, SetOptions.merge())
                .await()
        }
    }

    private suspend fun obtenerUbicacionActual(): Location? {
        val ahora = System.currentTimeMillis()
        val cache = ubicacionCache
        if (cache != null && ahora - ubicacionCacheTiempo < TIEMPO_CACHE_UBICACION_MS) {
            return cache
        }

        val ubicacionFused = obtenerUbicacionFused()
        if (ubicacionFused != null) {
            ubicacionCache = ubicacionFused
            ubicacionCacheTiempo = ahora
            return ubicacionFused
        }

        val ubicacionSistema = obtenerUbicacionSistema()
        if (ubicacionSistema != null) {
            ubicacionCache = ubicacionSistema
            ubicacionCacheTiempo = ahora
            return ubicacionSistema
        }

        return null
    }

    private suspend fun obtenerUbicacionFused(): Location? {
        return try {
            val ubicacionActual = proveedorUbicacion
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .await()

            if (ubicacionActual != null) {
                return ubicacionActual
            }

            val ultimaUbicacion = proveedorUbicacion.lastLocation.await()
            if (ultimaUbicacion != null) {
                return ultimaUbicacion
            }

            solicitarUbicacionUnica()
        } catch (excepcion: Exception) {
            null
        }
    }

    private suspend fun obtenerUbicacionSistema(): Location? {
        val manejador = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val proveedores = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        val ultimaUbicacion = proveedores
            .filter { proveedor -> manejador.isProviderEnabled(proveedor) }
            .mapNotNull { proveedor -> manejador.getLastKnownLocation(proveedor) }
            .maxByOrNull { ubicacion -> ubicacion.time }

        if (ultimaUbicacion != null) {
            return ultimaUbicacion
        }

        val proveedorActivo = proveedores.firstOrNull { proveedor -> manejador.isProviderEnabled(proveedor) }
            ?: return null

        return solicitarUbicacionUnicaSistema(manejador, proveedorActivo)
    }

    private suspend fun solicitarUbicacionUnica(): Location? {
        return suspendCancellableCoroutine { continuacion ->
            val solicitud = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .setMaxUpdates(1)
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(resultado: LocationResult) {
                    proveedorUbicacion.removeLocationUpdates(this)
                    if (!continuacion.isCompleted) {
                        continuacion.resume(resultado.lastLocation)
                    }
                }
            }

            proveedorUbicacion.requestLocationUpdates(
                solicitud,
                callback,
                Looper.getMainLooper(),
            ).addOnFailureListener {
                proveedorUbicacion.removeLocationUpdates(callback)
                if (!continuacion.isCompleted) {
                    continuacion.resume(null)
                }
            }

            continuacion.invokeOnCancellation {
                proveedorUbicacion.removeLocationUpdates(callback)
            }
        }
    }

    private fun calcularDistancia(
        latitudOrigen: Double,
        longitudOrigen: Double,
        latitudDestino: Double,
        longitudDestino: Double,
    ): Double {
        val resultados = FloatArray(1)
        Location.distanceBetween(
            latitudOrigen,
            longitudOrigen,
            latitudDestino,
            longitudDestino,
            resultados,
        )
        return resultados.first().toDouble()
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
                ?: normalizarTexto(nombre)
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
                    ?: normalizarTexto(nombre)
                nombreNormalizado.startsWith(consultaNormalizada)
            }
        } catch (excepcion: Exception) {
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
        val resultado = baseDatos.collection(coleccionInventarioPublico)
            .get()
            .await()
        return resultado.documents.also { inventarioPublicoCache = it }
    }

    private suspend fun obtenerInventarioPrivadoCache(): List<DocumentSnapshot> {
        val cache = inventarioPrivadoCache
        if (cache != null) {
            return cache
        }
        val resultado = baseDatos.collectionGroup("Inventario")
            .get()
            .await()
        return resultado.documents.also { inventarioPrivadoCache = it }
    }

    private fun normalizarTexto(texto: String): String {
        val limpio = texto.trim().lowercase(Locale.getDefault())
        val normalizado = Normalizer.normalize(limpio, Normalizer.Form.NFD)
        return normalizado.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    private fun tienePermisoUbicacion(): Boolean {
        val permisoFino = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val permisoAproximado = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return permisoFino || permisoAproximado
    }

    private suspend fun solicitarUbicacionUnicaSistema(
        manejador: LocationManager,
        proveedor: String,
    ): Location? {
        return suspendCancellableCoroutine { continuacion ->
            val listener = object : LocationListener {
                override fun onLocationChanged(ubicacion: Location) {
                    manejador.removeUpdates(this)
                    if (!continuacion.isCompleted) {
                        continuacion.resume(ubicacion)
                    }
                }

                override fun onProviderDisabled(provider: String) {
                    manejador.removeUpdates(this)
                    if (!continuacion.isCompleted) {
                        continuacion.resume(null)
                    }
                }

                override fun onProviderEnabled(provider: String) = Unit

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }

            try {
                manejador.requestLocationUpdates(
                    proveedor,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
            } catch (excepcion: SecurityException) {
                manejador.removeUpdates(listener)
                if (!continuacion.isCompleted) {
                    continuacion.resume(null)
                }
            } catch (excepcion: IllegalArgumentException) {
                manejador.removeUpdates(listener)
                if (!continuacion.isCompleted) {
                    continuacion.resume(null)
                }
            }

            continuacion.invokeOnCancellation {
                manejador.removeUpdates(listener)
            }
        }
    }

    data class ResultadoBusqueda(
        val nombreProducto: String,
        val precio: Double,
        val unidadPrecio: String,
        val descripcion: String,
        val categoria: String,
        val vendedorId: String,
        val nombreAlmacen: String,
        val horarioAtencion: String,
        val abiertoAhora: Boolean,
        val distanciaMetros: Double?,
        val latitudAlmacen: Double?,
        val longitudAlmacen: Double?,
        val disponible: Boolean,
    )

    data class OpcionDistancia(
        val etiqueta: String,
        val metros: Float,
    )

    companion object {
        private const val DISTANCIA_MAXIMA_METROS = 100000f
        private const val HORARIO_MANANA_INICIO_TEXTO = "09:00"
        private const val HORARIO_MANANA_FIN_TEXTO = "13:00"
        private const val HORARIO_TARDE_INICIO_TEXTO = "16:00"
        private const val HORARIO_TARDE_FIN_TEXTO = "22:00"
        private const val HORARIO_MANANA_INICIO = 9 * 60
        private const val HORARIO_MANANA_FIN = 13 * 60
        private const val HORARIO_TARDE_INICIO = 16 * 60
        private const val HORARIO_TARDE_FIN = 22 * 60
        private const val TIEMPO_CACHE_UBICACION_MS = 60_000L
    }

    private class AdaptadorResultados(
        private val resultados: List<ResultadoBusqueda>,
        private val onLlegar: (ResultadoBusqueda) -> Unit,
        private val onVerStock: (ResultadoBusqueda) -> Unit,
    ) : RecyclerView.Adapter<AdaptadorResultados.VistaResultado>() {

        class VistaResultado(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
            val textoNombre: android.widget.TextView = itemView.findViewById(R.id.texto_nombre_producto)
            val textoPrecio: android.widget.TextView = itemView.findViewById(R.id.texto_precio_producto)
            val textoDescripcion: android.widget.TextView = itemView.findViewById(R.id.texto_descripcion_producto)
            val textoAlmacen: android.widget.TextView = itemView.findViewById(R.id.texto_almacen_producto)
            val textoHorario: android.widget.TextView = itemView.findViewById(R.id.texto_horario_almacen)
            val textoEstadoHorario: android.widget.TextView = itemView.findViewById(R.id.texto_estado_horario)
            val textoDistancia: android.widget.TextView = itemView.findViewById(R.id.texto_distancia_producto)
            val textoEstado: android.widget.TextView = itemView.findViewById(R.id.texto_estado_producto)
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
            holder.textoNombre.text = resultado.nombreProducto
            holder.textoPrecio.text = "Precio: $${String.format(Locale.forLanguageTag("es-CL"), "%.0f", resultado.precio)} / " +
                etiquetaUnidadPrecio(resultado.unidadPrecio)
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
}
