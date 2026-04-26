package com.example.rutaalmacen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.resume

class AlmacenesCercanosActivity : AppCompatActivity() {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private lateinit var proveedorUbicacion: FusedLocationProviderClient
    private val almacenesBase: MutableList<AlmacenCercano> = mutableListOf()
    private val almacenes: MutableList<AlmacenCercano> = mutableListOf()
    private lateinit var adaptador: AdaptadorAlmacenes
    private lateinit var textoDistanciaFiltro: TextView
    private var distanciaMaximaSeleccionada = DISTANCIA_MAXIMA_METROS
    private var categoriaSeleccionada = "Todas"
    private lateinit var contenedorCarga: View
    private var ubicacionCache: Location? = null
    private var ubicacionCacheTiempo = 0L
    private var ultimaCargaAlmacenes = 0L
    private var avisoSinUbicacionMostrado = false
    private var cargaPendiente = false
    private var tareaCarga: Job? = null

    private val opcionesDistancia = listOf(
        OpcionDistancia("0,5 km", 500f),
        OpcionDistancia("1 km", 1000f),
        OpcionDistancia("2 km", 2000f),
        OpcionDistancia("5 km", 5000f),
        OpcionDistancia("10 km", 10000f),
        OpcionDistancia("20 km", 20000f),
        OpcionDistancia("50 km", 50000f),
        OpcionDistancia("100 km", DISTANCIA_MAXIMA_METROS),
    )

    private val categoriasAlmacen = listOf(
        "Todas",
        "Almacén",
        "Verdulería",
        "Panadería",
        "Botillería",
        "Carnicería",
        "Bazar",
        "Pescadería",
        "Ferretería",
        "Otro",
    )

    private val solicitudPermisoUbicacion =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resultados ->
            val concedido = resultados[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                resultados[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (concedido) {
                if (cargaPendiente) {
                    cargaPendiente = false
                    cargarAlmacenes()
                }
            } else {
                mostrarMensaje("Necesitas permiso de ubicación para ver almacenes cercanos")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_almacenes_cercanos)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_almacenes)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                barrasDelSistema.top,
                barrasDelSistema.right,
                barrasDelSistema.bottom,
            )
            insets
        }

        proveedorUbicacion = LocationServices.getFusedLocationProviderClient(this)

        textoDistanciaFiltro = findViewById(R.id.texto_distancia_almacenes)
        val grupoDistancia = findViewById<ChipGroup>(R.id.grupo_distancia_almacenes)
        val encabezadoDistancia = findViewById<android.view.View>(R.id.encabezado_distancia_almacenes)
        val contenidoDistancia = findViewById<android.view.View>(R.id.contenido_distancia_almacenes)
        val iconoDistancia = findViewById<ImageView>(R.id.icono_distancia_almacenes)
        val recycler = findViewById<RecyclerView>(R.id.recycler_almacenes)
        val campoCategoria = findViewById<AutoCompleteTextView>(R.id.campo_categoria_almacenes)
        contenedorCarga = findViewById(R.id.contenedor_carga_almacenes)

        adaptador = AdaptadorAlmacenes(
            almacenes = almacenes,
            onVerStock = { almacen -> abrirStockAlmacen(almacen) },
            onLlegar = { almacen -> abrirNavegacion(almacen) },
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adaptador

        configurarDesplegable(encabezadoDistancia, contenidoDistancia, iconoDistancia, expandidoInicial = false)
        configurarFiltros(grupoDistancia)
        configurarCategoria(campoCategoria)
        configurarNavegacion()

        cargarAlmacenes()
    }

    override fun onResume() {
        super.onResume()
        cargarAlmacenes()
    }

    private fun configurarNavegacion() {
        val navegacion = findViewById<BottomNavigationView>(R.id.nav_comprador)
        navegacion.selectedItemId = R.id.nav_almacenes_comprador
        navegacion.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_productos_comprador -> {
                    val intent = Intent(this, CompradorActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(intent)
                    finish()
                    true
                }
                R.id.nav_almacenes_comprador -> true
                else -> false
            }
        }
    }

    private fun configurarFiltros(grupoDistancia: ChipGroup) {
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

    private fun configurarCategoria(campoCategoria: AutoCompleteTextView) {
        val adaptadorCategorias = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            categoriasAlmacen,
        )
        campoCategoria.setAdapter(adaptadorCategorias)
        campoCategoria.setText(categoriaSeleccionada, false)
        campoCategoria.setOnItemClickListener { _, _, posicion, _ ->
            categoriaSeleccionada = categoriasAlmacen.getOrNull(posicion) ?: "Todas"
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

    private fun mostrarCarga(mostrar: Boolean) {
        contenedorCarga.visibility = if (mostrar) View.VISIBLE else View.GONE
    }

    private fun cargarAlmacenes() {
        if (tareaCarga?.isActive == true) return
        tareaCarga = lifecycleScope.launch {
            val usuario = autenticacion.currentUser
            if (usuario == null) {
                mostrarMensaje("No hay un usuario activo")
                return@launch
            }

            if (!tienePermisoUbicacion()) {
                cargaPendiente = true
                solicitudPermisoUbicacion.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
                return@launch
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

                val ahora = System.currentTimeMillis()
                val usarCache = almacenesBase.isNotEmpty() &&
                    ahora - ultimaCargaAlmacenes < TIEMPO_CACHE_ALMACENES_MS
                if (usarCache) {
                    actualizarDistanciasAlmacenes(ubicacionComprador)
                    return@launch
                }

                val documentos = baseDatos.collection("Usuarios")
                    .whereEqualTo("rol", "vendedor")
                    .get()
                    .await()
                    .documents

                val nuevos = documentos.mapNotNull { documento ->
                    val latitud = documento.getDouble("latitud")
                    val longitud = documento.getDouble("longitud")
                    val nombreAlmacen = documento.getString("nombreAlmacen")
                        ?.takeIf { it.isNotBlank() }
                        ?: documento.getString("nombre")?.takeIf { it.isNotBlank() }
                        ?: "Almacén"

                    val horarioMananaInicio = documento.getString("horarioMananaInicio")
                        ?: HORARIO_MANANA_INICIO_TEXTO
                    val horarioMananaFin = documento.getString("horarioMananaFin")
                        ?: HORARIO_MANANA_FIN_TEXTO
                    val horarioTardeInicio = documento.getString("horarioTardeInicio")
                        ?: HORARIO_TARDE_INICIO_TEXTO
                    val horarioTardeFin = documento.getString("horarioTardeFin")
                        ?: HORARIO_TARDE_FIN_TEXTO
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
                        latitud != null &&
                        longitud != null
                    ) {
                        calcularDistancia(
                            ubicacionComprador.latitude,
                            ubicacionComprador.longitude,
                            latitud,
                            longitud,
                        )
                    } else {
                        null
                    }
                    val categoriaAlmacen = documento.getString("categoriaAlmacen")
                        ?.takeIf { it.isNotBlank() }
                        ?: "Sin definir"

                    AlmacenCercano(
                        vendedorId = documento.id,
                        nombreAlmacen = nombreAlmacen,
                        horarioAtencion = horarioAtencion,
                        abiertoAhora = abiertoAhora,
                        distanciaMetros = distancia,
                        categoriaAlmacen = categoriaAlmacen,
                        latitud = latitud,
                        longitud = longitud,
                    )
                }

                almacenesBase.clear()
                almacenesBase.addAll(nuevos)
                ultimaCargaAlmacenes = System.currentTimeMillis()
                aplicarFiltros()
            } catch (excepcion: Exception) {
                mostrarMensaje("No se pudieron cargar los almacenes")
            } finally {
                mostrarCarga(false)
            }
        }
    }

    private fun actualizarDistanciasAlmacenes(ubicacionComprador: Location?) {
        val actualizados = almacenesBase.map { almacen ->
            val distancia = if (
                ubicacionComprador != null &&
                almacen.latitud != null &&
                almacen.longitud != null
            ) {
                calcularDistancia(
                    ubicacionComprador.latitude,
                    ubicacionComprador.longitude,
                    almacen.latitud,
                    almacen.longitud,
                )
            } else {
                null
            }
            almacen.copy(distanciaMetros = distancia)
        }

        almacenesBase.clear()
        almacenesBase.addAll(actualizados)
        aplicarFiltros()
    }

    private fun aplicarFiltros() {
        val max = distanciaMaximaSeleccionada.toDouble()
        val filtrarDistancia = distanciaMaximaSeleccionada < DISTANCIA_MAXIMA_METROS
        val filtrados = almacenesBase.filter { almacen ->
            val distancia = almacen.distanciaMetros
            if (distancia == null) {
                !filtrarDistancia
            } else {
                distancia <= max
            }
        }.filter { almacen ->
            categoriaSeleccionada == "Todas" || almacen.categoriaAlmacen == categoriaSeleccionada
        }

        val ordenados = filtrados.sortedWith(
            compareBy<AlmacenCercano> { !it.abiertoAhora }
                .thenBy { it.distanciaMetros ?: Double.MAX_VALUE },
        )

        almacenes.clear()
        almacenes.addAll(ordenados)
        adaptador.notifyDataSetChanged()
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
            } catch (excepcion: Exception) {
                if (!continuacion.isCompleted) {
                    continuacion.resume(null)
                }
                return@suspendCancellableCoroutine
            }

            continuacion.invokeOnCancellation {
                manejador.removeUpdates(listener)
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

    private fun abrirNavegacion(almacen: AlmacenCercano) {
        val latitud = almacen.latitud
        val longitud = almacen.longitud
        if (latitud == null || longitud == null) {
            mostrarMensaje("Ubicación del almacén no disponible")
            return
        }

        val etiqueta = almacen.nombreAlmacen.ifBlank { "Almacén" }
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

    private fun abrirStockAlmacen(almacen: AlmacenCercano) {
        val intent = Intent(this, StockAlmacenActivity::class.java).apply {
            putExtra(StockAlmacenActivity.EXTRA_VENDEDOR_ID, almacen.vendedorId)
            putExtra(StockAlmacenActivity.EXTRA_NOMBRE_ALMACEN, almacen.nombreAlmacen)
            putExtra(StockAlmacenActivity.EXTRA_HORARIO_ATENCION, almacen.horarioAtencion)
            putExtra(StockAlmacenActivity.EXTRA_LATITUD_ALMACEN, almacen.latitud)
            putExtra(StockAlmacenActivity.EXTRA_LONGITUD_ALMACEN, almacen.longitud)
        }
        startActivity(intent)
    }

    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
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

    data class OpcionDistancia(val etiqueta: String, val metros: Float)

    data class AlmacenCercano(
        val vendedorId: String,
        val nombreAlmacen: String,
        val horarioAtencion: String,
        val abiertoAhora: Boolean,
        val distanciaMetros: Double?,
        val categoriaAlmacen: String,
        val latitud: Double?,
        val longitud: Double?,
    )

    private class AdaptadorAlmacenes(
        private val almacenes: List<AlmacenCercano>,
        private val onVerStock: (AlmacenCercano) -> Unit,
        private val onLlegar: (AlmacenCercano) -> Unit,
    ) : RecyclerView.Adapter<AdaptadorAlmacenes.VistaAlmacen>() {

        class VistaAlmacen(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
            val textoNombre: TextView = itemView.findViewById(R.id.texto_nombre_almacen)
            val textoEstado: TextView = itemView.findViewById(R.id.texto_estado_almacen)
            val textoDistancia: TextView = itemView.findViewById(R.id.texto_distancia_almacen)
            val textoCategoria: TextView = itemView.findViewById(R.id.texto_categoria_almacen)
            val textoHorario: TextView = itemView.findViewById(R.id.texto_horario_almacen)
            val botonStock: MaterialButton = itemView.findViewById(R.id.boton_ver_stock_almacen)
            val botonLlegar: MaterialButton = itemView.findViewById(R.id.boton_llegar_almacen)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaAlmacen {
            val vista = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_almacen_cercano, parent, false)
            return VistaAlmacen(vista)
        }

        override fun onBindViewHolder(holder: VistaAlmacen, position: Int) {
            val almacen = almacenes[position]
            holder.textoNombre.text = almacen.nombreAlmacen
            holder.textoEstado.text = if (almacen.abiertoAhora) "Abierto" else "Cerrado"
            val colorEstado = if (almacen.abiertoAhora) {
                R.color.stock_verde
            } else {
                R.color.stock_rojo
            }
            holder.textoEstado.setTextColor(
                ContextCompat.getColor(holder.itemView.context, colorEstado),
            )
            holder.textoDistancia.text = "Distancia: ${formatearDistancia(almacen.distanciaMetros)}"
            holder.textoCategoria.text = "Categoría: ${almacen.categoriaAlmacen}"
            holder.textoHorario.text = "Horario: ${almacen.horarioAtencion}"
            holder.botonStock.setOnClickListener { onVerStock(almacen) }
            holder.botonLlegar.setOnClickListener { onLlegar(almacen) }
        }

        override fun getItemCount(): Int = almacenes.size

        private fun formatearDistancia(distanciaMetros: Double?): String {
            if (distanciaMetros == null) {
                return "Sin ubicación"
            }
            return if (distanciaMetros >= 1000) {
                val km = distanciaMetros / 1000.0
                String.format(Locale.forLanguageTag("es-CL"), "%.1f km", km)
            } else {
                "${distanciaMetros.toInt()} m"
            }
        }
    }

    private companion object {
        private const val DISTANCIA_MAXIMA_METROS = 100000f
        private const val HORARIO_MANANA_INICIO_TEXTO = "09:00"
        private const val HORARIO_MANANA_FIN_TEXTO = "13:00"
        private const val HORARIO_TARDE_INICIO_TEXTO = "15:00"
        private const val HORARIO_TARDE_FIN_TEXTO = "19:00"
        private const val HORARIO_MANANA_INICIO = 9 * 60
        private const val HORARIO_MANANA_FIN = 13 * 60
        private const val HORARIO_TARDE_INICIO = 15 * 60
        private const val HORARIO_TARDE_FIN = 19 * 60
        private const val TIEMPO_CACHE_UBICACION_MS = 60_000L
        private const val TIEMPO_CACHE_ALMACENES_MS = 60_000L
    }
}
