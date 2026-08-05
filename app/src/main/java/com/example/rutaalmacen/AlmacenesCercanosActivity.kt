package com.example.rutaalmacen

import android.Manifest
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * Actividad que muestra la lista de almacenes cercanos al comprador, ordenados por
 * estado de apertura y distancia. Permite filtrar por categoría, ver el stock disponible
 * y abrir la navegación hacia el almacén seleccionado.
 *
 * Utiliza la ubicación del dispositivo para calcular distancias y cachea los resultados
 * para optimizar el rendimiento de las consultas a Firestore.
 */
class AlmacenesCercanosActivity : AppCompatActivity() {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val proveedorUbicacion by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private val almacenesBase: MutableList<AlmacenCercano> = mutableListOf()
    private val almacenes: MutableList<AlmacenCercano> = mutableListOf()
    private lateinit var adaptador: AdaptadorAlmacenes
    private var categoriaSeleccionada = "Todas"
    private var categoriaChipSeleccionada = "Todas"
    private var filtroCajaVecina = false
    private var filtroAbiertoAhora = false
    private var filtroDistanciaActiva = false
    private var distanciaMaximaMetros: Double = 5000.0
    private var textoBusqueda = ""
    private lateinit var contenedorCarga: View
    private var ubicacionCache: Location? = null
    private var ubicacionCacheTiempo = 0L
    private var ultimaCargaAlmacenes = 0L
    private var avisoSinUbicacionMostrado = false
    private var cargaPendiente = false
    private var tareaCarga: Job? = null

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

    /**
     * Ciclo de vida: inicializa la interfaz, el RecyclerView, el filtro de categorías
     * y carga la lista de almacenes cercanos.
     *
     * @param savedInstanceState Estado guardado de la instancia anterior, o null si es nueva.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_almacenes_cercanos)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        val header = findViewById<View>(R.id.header_almacenes)
        val paddingHeaderBase = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_almacenes)) { vista, insets ->
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

        configurarCategoria(campoCategoria)
        configurarBotonVolver()
        configurarChipsFiltro()
        configurarBusqueda()

        filtroCajaVecina = intent.getBooleanExtra("filtro_caja_vecina", false)

        cargarAlmacenes()
    }

    override fun onResume() {
        super.onResume()
        cargarAlmacenes()
    }

    private fun configurarBotonVolver() {
        findViewById<MaterialButton>(R.id.boton_volver_home).setOnClickListener {
            val intent = Intent(this, CompradorActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
            finish()
        }
    }

    /**
     * Configura el campo de texto con autocompletado para filtrar almacenes por categoría.
     *
     * @param campoCategoria Campo de texto donde se muestra el selector de categorías.
     */
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

    private fun configurarChipsFiltro() {
        val chipTodos = findViewById<MaterialButton>(R.id.chip_todos)
        val chipAbierto = findViewById<MaterialButton>(R.id.chip_abierto)
        val chipCategoria = findViewById<MaterialButton>(R.id.chip_categoria)
        val chipDistancia = findViewById<MaterialButton>(R.id.chip_distancia)
        val chipCajaVecina = findViewById<MaterialButton>(R.id.chip_caja_vecina)

        chipTodos.setOnClickListener {
            categoriaChipSeleccionada = "Todas"
            filtroAbiertoAhora = false
            filtroDistanciaActiva = false
            filtroCajaVecina = false
            actualizarEstadoChips(chipTodos, chipAbierto, chipCategoria, chipDistancia, chipCajaVecina)
            aplicarFiltros()
        }

        chipAbierto.setOnClickListener {
            filtroAbiertoAhora = !filtroAbiertoAhora
            if (filtroAbiertoAhora) {
                filtroDistanciaActiva = false
                filtroCajaVecina = false
            }
            actualizarEstadoChips(chipTodos, chipAbierto, chipCategoria, chipDistancia, chipCajaVecina)
            aplicarFiltros()
        }

        chipCategoria.setOnClickListener {
            mostrarDialogoCategoria()
        }

        chipDistancia.setOnClickListener {
            mostrarDialogoDistancia()
        }

        chipCajaVecina.setOnClickListener {
            filtroCajaVecina = !filtroCajaVecina
            if (filtroCajaVecina) {
                filtroAbiertoAhora = false
                filtroDistanciaActiva = false
            }
            actualizarEstadoChips(chipTodos, chipAbierto, chipCategoria, chipDistancia, chipCajaVecina)
            aplicarFiltros()
        }
    }

    private fun actualizarEstadoChips(
        chipTodos: MaterialButton,
        chipAbierto: MaterialButton,
        chipCategoria: MaterialButton,
        chipDistancia: MaterialButton,
        chipCajaVecina: MaterialButton,
    ) {
        val colorPrimario = ContextCompat.getColor(this, R.color.colorPrimary)

        chipTodos.setBackgroundColor(
            if (categoriaChipSeleccionada == "Todas" && !filtroAbiertoAhora && !filtroDistanciaActiva && !filtroCajaVecina) colorPrimario else android.graphics.Color.TRANSPARENT
        )
        chipTodos.setTextColor(
            if (categoriaChipSeleccionada == "Todas" && !filtroAbiertoAhora && !filtroDistanciaActiva && !filtroCajaVecina) android.graphics.Color.WHITE else colorPrimario
        )

        chipAbierto.setBackgroundColor(
            if (filtroAbiertoAhora) colorPrimario else android.graphics.Color.TRANSPARENT
        )
        chipAbierto.setTextColor(
            if (filtroAbiertoAhora) android.graphics.Color.WHITE else colorPrimario
        )

        val textoCategoria = if (categoriaChipSeleccionada == "Todas") "Categoría" else categoriaChipSeleccionada
        chipCategoria.text = textoCategoria
        chipCategoria.setBackgroundColor(
            if (categoriaChipSeleccionada != "Todas") colorPrimario else android.graphics.Color.TRANSPARENT
        )
        chipCategoria.setTextColor(
            if (categoriaChipSeleccionada != "Todas") android.graphics.Color.WHITE else colorPrimario
        )

        val textoDistancia = if (filtroDistanciaActiva) "Hasta ${distanciaMaximaMetros.toInt()} m" else "Distancia"
        chipDistancia.text = textoDistancia
        chipDistancia.setBackgroundColor(
            if (filtroDistanciaActiva) colorPrimario else android.graphics.Color.TRANSPARENT
        )
        chipDistancia.setTextColor(
            if (filtroDistanciaActiva) android.graphics.Color.WHITE else colorPrimario
        )

        chipCajaVecina.setBackgroundColor(
            if (filtroCajaVecina) colorPrimario else android.graphics.Color.TRANSPARENT
        )
        chipCajaVecina.setTextColor(
            if (filtroCajaVecina) android.graphics.Color.WHITE else colorPrimario
        )
    }

    private fun mostrarDialogoCategoria() {
        val categoriasParaMostrar = categoriasAlmacen.filter { it != "Todas" }
        val seleccionActual = categoriasParaMostrar.indexOf(categoriaChipSeleccionada)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Categoría del almacén")
            .setSingleChoiceItems(categoriasParaMostrar.toTypedArray(), if (seleccionActual >= 0) seleccionActual else -1) { dialogo, posicion ->
                categoriaChipSeleccionada = categoriasParaMostrar[posicion]
                filtroAbiertoAhora = false
                filtroDistanciaActiva = false
                filtroCajaVecina = false
                dialogo.dismiss()
                val chipTodos = findViewById<MaterialButton>(R.id.chip_todos)
                val chipAbierto = findViewById<MaterialButton>(R.id.chip_abierto)
                val chipCategoria = findViewById<MaterialButton>(R.id.chip_categoria)
                val chipDistancia = findViewById<MaterialButton>(R.id.chip_distancia)
                val chipCajaVecina = findViewById<MaterialButton>(R.id.chip_caja_vecina)
                actualizarEstadoChips(chipTodos, chipAbierto, chipCategoria, chipDistancia, chipCajaVecina)
                aplicarFiltros()
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
                filtroCajaVecina = false
                dialogo.dismiss()
                val chipTodos = findViewById<MaterialButton>(R.id.chip_todos)
                val chipAbierto = findViewById<MaterialButton>(R.id.chip_abierto)
                val chipCategoria = findViewById<MaterialButton>(R.id.chip_categoria)
                val chipDistancia = findViewById<MaterialButton>(R.id.chip_distancia)
                val chipCajaVecina = findViewById<MaterialButton>(R.id.chip_caja_vecina)
                actualizarEstadoChips(chipTodos, chipAbierto, chipCategoria, chipDistancia, chipCajaVecina)
                aplicarFiltros()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun configurarBusqueda() {
        val campoBusqueda = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.campo_busqueda_almacen)
        campoBusqueda?.doOnTextChanged { texto, _, _, _ ->
            textoBusqueda = texto?.toString().orEmpty()
            aplicarFiltros()
        }
    }

    /**
     * Muestra u oculta el indicador de carga en la interfaz.
     *
     * @param mostrar True para mostrar el indicador, false para ocultarlo.
     */
    private fun mostrarCarga(mostrar: Boolean) {
        contenedorCarga.visibility = if (mostrar) View.VISIBLE else View.GONE
    }

    /**
     * Carga la lista de almacenes desde Firestore, utilizando caché local cuando es posible
     * para reducir latencia. Solicita permisos de ubicación si no están concedidos y
     * calcula la distancia a cada almacén.
     */
    private fun cargarAlmacenes() {
        if (tareaCarga?.isActive == true) return
        tareaCarga = lifecycleScope.launch {
            val usuario = autenticacion.currentUser
            if (usuario == null) {
                Log.w("AlmacenesCercanos", "Sin usuario autenticado, cargando almacenes públicos...")
            }

            val permisoUbicacion = UbicacionUtil.tienePermisoUbicacion(this@AlmacenesCercanosActivity)
            if (!permisoUbicacion && !cargaPendiente) {
                cargaPendiente = true
                solicitudPermisoUbicacion.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }

            val ubicacionRapida = if (permisoUbicacion) obtenerUbicacionCacheada() else null
            val ahora = System.currentTimeMillis()
            val usarCache = almacenesBase.isNotEmpty() &&
                ahora - ultimaCargaAlmacenes < HorarioUtil.TIEMPO_CACHE_ALMACENES_MS
            if (usarCache) {
                actualizarDistanciasAlmacenes(ubicacionRapida)
                mostrarCarga(false)
                return@launch
            }

            val documentosCache = obtenerVendedoresCache()
            if (documentosCache.isNotEmpty()) {
                val nuevosCache = construirAlmacenes(documentosCache, ubicacionRapida)
                almacenesBase.clear()
                almacenesBase.addAll(nuevosCache)
                aplicarFiltros()
            }

            if (almacenesBase.isEmpty()) {
                mostrarCarga(true)
            }

            try {
                val documentosServidor = obtenerVendedoresServidor()
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

                    val nuevos = construirAlmacenes(documentosServidor, ubicacionFinal)
                    almacenesBase.clear()
                    almacenesBase.addAll(nuevos)
                    ultimaCargaAlmacenes = System.currentTimeMillis()
                    aplicarFiltros()
                } else if (almacenesBase.isEmpty()) {
                    almacenes.clear()
                    adaptador.notifyDataSetChanged()
                }
            } catch (_: Exception) {
                if (almacenesBase.isEmpty()) {
                    mostrarMensaje("No se pudieron cargar los almacenes")
                }
            } finally {
                mostrarCarga(false)
            }
        }
    }

    /**
     * Obtiene la lista de documentos de vendedores desde la caché local de Firestore.
     *
     * @return Lista de documentos de vendedores, o lista vacía en caso de error.
     */
    private suspend fun obtenerVendedoresCache(): List<DocumentSnapshot> {
        return try {
            baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .whereEqualTo("rol", Constantes.ROL_VENDEDOR)
                .get(Source.SERVER)
                .await()
                .documents
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Obtiene la lista de documentos de vendedores directamente desde el servidor de Firestore.
     *
     * @return Lista de documentos de vendedores obtenidos del servidor.
     * @throws Exception Si ocurre un error de red o de consulta a Firestore.
     */
    private suspend fun obtenerVendedoresServidor(): List<DocumentSnapshot> {
        return baseDatos.collection(Constantes.COLECCION_USUARIOS)
            .whereEqualTo("rol", Constantes.ROL_VENDEDOR)
            .get()
            .await()
            .documents
    }

    /**
     * Construye la lista de almacenes cercanos a partir de los documentos de Firestore,
     * calculando el horario de atención, el estado de apertura y la distancia al comprador.
     *
     * @param documentos Lista de documentos de vendedores obtenidos de Firestore.
     * @param ubicacionComprador Ubicación actual del comprador, o null si no está disponible.
     * @return Lista de [AlmacenCercano] construidos a partir de los documentos.
     */
    private fun construirAlmacenes(
        documentos: List<DocumentSnapshot>,
        ubicacionComprador: Location?,
    ): List<AlmacenCercano> {
        return documentos.mapNotNull { documento ->
            val latitud = documento.getDouble("latitud")
            val longitud = documento.getDouble("longitud")
            val nombreAlmacen = documento.getString("nombreAlmacen")
                ?.takeIf { it.isNotBlank() }
                ?: documento.getString("nombre")?.takeIf { it.isNotBlank() }
                ?: "Almacén"

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
                latitud != null &&
                longitud != null
            ) {
                UbicacionUtil.calcularDistancia(
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
            val metodosPago = (documento.get("metodosPago") as? List<String>).orEmpty()
            val tieneCajaVecina = documento.getBoolean("tieneCajaVecina") ?: false
            val saldoCajaVecina = documento.getDouble("saldoCajaVecina") ?: 0.0

            AlmacenCercano(
                vendedorId = documento.id,
                nombreAlmacen = nombreAlmacen,
                horarioAtencion = horarioAtencion,
                abiertoAhora = abiertoAhora,
                distanciaMetros = distancia,
                categoriaAlmacen = categoriaAlmacen,
                latitud = latitud,
                longitud = longitud,
                metodosPago = metodosPago,
                tieneCajaVecina = tieneCajaVecina,
                saldoCajaVecina = saldoCajaVecina,
            )
        }
    }

    /**
     * Obtiene la ubicación del dispositivo utilizando la caché interna o una ubicación rápida.
     *
     * @return Ubicación cacheada o rápida, o null si no se pudo obtener.
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
     * Obtiene la ubicación actual del dispositivo intentando múltiples fuentes:
     * caché, proveedor Fused y ubicación del sistema.
     *
     * @return Ubicación más precisa disponible, o null si ninguna fuente responde.
     */
    private suspend fun obtenerUbicacionActual(): Location? {
        val ahora = System.currentTimeMillis()
        val cacheHit = UbicacionUtil.obtenerUbicacionConCache(
            ubicacionCache,
            ubicacionCacheTiempo,
            HorarioUtil.TIEMPO_CACHE_UBICACION_MS,
        )
        if (cacheHit != null) return cacheHit

        val ubicacionFused = UbicacionUtil.obtenerUbicacionFused(proveedorUbicacion)
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
     * Recalcula las distancias de todos los almacenes respecto a la ubicación actual
     * del comprador y actualiza la lista mostrada.
     *
     * @param ubicacionComprador Ubicación actual del comprador, o null si no está disponible.
     */
    private fun actualizarDistanciasAlmacenes(ubicacionComprador: Location?) {
        val actualizados = almacenesBase.map { almacen ->
            val distancia = if (
                ubicacionComprador != null &&
                almacen.latitud != null &&
                almacen.longitud != null
            ) {
                UbicacionUtil.calcularDistancia(
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

    /**
     * Aplica el filtro de categoría seleccionado y ordena los almacenes por estado
     * de apertura (abiertos primero) y luego por distancia ascendente.
     */
    private fun aplicarFiltros() {
        val textoBusquedaNormalizado = FiltroContenido.normalizar(textoBusqueda)
        val categoriaActiva = if (categoriaChipSeleccionada != "Todas") categoriaChipSeleccionada else categoriaSeleccionada
        val filtrados = almacenesBase.filter { almacen ->
            val coincideCategoria = categoriaActiva == "Todas" || almacen.categoriaAlmacen == categoriaActiva
            val coincideCajaVecina = !filtroCajaVecina || almacen.tieneCajaVecina
            val coincideAbierto = !filtroAbiertoAhora || almacen.abiertoAhora
            val coincideDistancia = !filtroDistanciaActiva ||
                (almacen.distanciaMetros != null && almacen.distanciaMetros <= distanciaMaximaMetros)
            val coincideBusqueda = textoBusquedaNormalizado.isBlank() ||
                FiltroContenido.normalizar(almacen.nombreAlmacen).contains(textoBusquedaNormalizado)
            coincideCategoria && coincideCajaVecina && coincideAbierto && coincideDistancia && coincideBusqueda
        }

        val ordenados = filtrados.sortedWith(
            compareBy<AlmacenCercano> { !it.abiertoAhora }
                .thenBy { it.distanciaMetros ?: Double.MAX_VALUE },
        )

        almacenes.clear()
        almacenes.addAll(ordenados)
        adaptador.notifyDataSetChanged()

        val textoContador = findViewById<TextView>(R.id.texto_contador_resultados)
        val texto = if (almacenes.size == 1) "1 almacén cerca de ti" else "${almacenes.size} almacenes cerca de ti"
        textoContador.text = texto

        val textoSinAlmacenes = findViewById<TextView>(R.id.texto_sin_almacenes)
        textoSinAlmacenes.visibility = if (almacenes.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Abre la aplicación de Google Maps para navegar hacia la ubicación del almacén.
     * Si Maps no está instalado, abre la versión web en el navegador.
     *
     * @param almacen Almacén destino de la navegación.
     */
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

    /**
     * Abre la actividad de stock del almacén seleccionado, pasando todos los datos
     * necesarios mediante extras del Intent.
     *
     * @param almacen Almacén cuyo stock se desea consultar.
     */
    private fun abrirStockAlmacen(almacen: AlmacenCercano) {
        val intent = Intent(this, StockAlmacenActivity::class.java).apply {
            putExtra(StockAlmacenActivity.EXTRA_VENDEDOR_ID, almacen.vendedorId)
            putExtra(StockAlmacenActivity.EXTRA_NOMBRE_ALMACEN, almacen.nombreAlmacen)
            putExtra(StockAlmacenActivity.EXTRA_HORARIO_ATENCION, almacen.horarioAtencion)
            putExtra(StockAlmacenActivity.EXTRA_LATITUD_ALMACEN, almacen.latitud)
            putExtra(StockAlmacenActivity.EXTRA_LONGITUD_ALMACEN, almacen.longitud)
            putExtra(StockAlmacenActivity.EXTRA_METODOS_PAGO, almacen.metodosPago.toTypedArray())
            putExtra(StockAlmacenActivity.EXTRA_TIENE_CAJA_VECINA, almacen.tieneCajaVecina)
        }
        startActivity(intent)
    }

    /**
     * Muestra un mensaje breve en pantalla mediante un Toast.
     *
     * @param mensaje Texto a mostrar al usuario.
     */
    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }

    /**
     * Modelo de datos que representa un almacén cercano con toda su información
     * relevante para la visualización en la lista.
     *
     * @property vendedorId Identificador único del vendedor en Firestore.
     * @property nombreAlmacen Nombre comercial del almacén.
     * @property horarioAtencion Texto descriptivo del horario de atención.
     * @property abiertoAhora Indica si el almacén está abierto en este momento.
     * @property distanciaMetros Distancia en metros desde el comprador, o null si no se pudo calcular.
     * @property categoriaAlmacen Categoría comercial del almacén.
     * @property latitud Coordenada de latitud del almacén, o null si no está disponible.
     * @property longitud Coordenada de longitud del almacén, o null si no está disponible.
     * @property metodosPago Lista de métodos de pago aceptados por el almacén.
     * @property tieneCajaVecina Indica si el almacén acepta Caja Vecina.
     * @property saldoCajaVecina Saldo disponible en la Caja Vecina del almacén.
     */
    data class AlmacenCercano(
        val vendedorId: String,
        val nombreAlmacen: String,
        val horarioAtencion: String,
        val abiertoAhora: Boolean,
        val distanciaMetros: Double?,
        val categoriaAlmacen: String,
        val latitud: Double?,
        val longitud: Double?,
        val metodosPago: List<String> = emptyList(),
        val tieneCajaVecina: Boolean = false,
        val saldoCajaVecina: Double = 0.0,
    )

    /**
     * Adaptador del RecyclerView que muestra la lista de almacenes cercanos,
     * enlazando cada elemento con su vista correspondiente.
     *
     * @property almacenes Lista de almacenes a mostrar.
     * @property onVerStock Acción a ejecutar cuando el usuario pulsa «Ver stock».
     * @property onLlegar Acción a ejecutar cuando el usuario pulsa «Llegar».
     */
    private class AdaptadorAlmacenes(
        private val almacenes: List<AlmacenCercano>,
        private val onVerStock: (AlmacenCercano) -> Unit,
        private val onLlegar: (AlmacenCercano) -> Unit,
    ) : RecyclerView.Adapter<AdaptadorAlmacenes.VistaAlmacen>() {

        /**
         * ViewHolder que contiene las referencias a las vistas de cada elemento
         * de la lista de almacenes cercanos.
         *
         * @param itemView Vista raíz del elemento de la lista.
         */
        class VistaAlmacen(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
            val textoNombre: TextView = itemView.findViewById(R.id.texto_nombre_almacen)
            val textoEstado: TextView = itemView.findViewById(R.id.texto_estado_almacen)
            val textoDistancia: TextView = itemView.findViewById(R.id.texto_distancia_almacen)
            val textoCategoria: TextView = itemView.findViewById(R.id.texto_categoria_almacen)
            val textoHorario: TextView = itemView.findViewById(R.id.texto_horario_almacen)
            val textoPagos: TextView = itemView.findViewById(R.id.texto_pagos_almacen)
            val textoCajaVecina: TextView = itemView.findViewById(R.id.texto_caja_vecina_almacen)
            val textoSaldoCajaVecina: TextView = itemView.findViewById(R.id.texto_saldo_caja_vecina)
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
            holder.textoPagos.text = if (almacen.metodosPago.isEmpty()) {
                "💳 Pagos: No especificado"
            } else {
                "💳 Pagos: ${almacen.metodosPago.joinToString(", ")}"
            }
            holder.textoCajaVecina.text = if (almacen.tieneCajaVecina) {
                "🏪 Caja Vecina: ✓ Acepta"
            } else {
                " Caja Vecina: ✗ No acepta"
            }
            val colorCaja = if (almacen.tieneCajaVecina) R.color.stock_verde else R.color.stock_rojo
            holder.textoCajaVecina.setTextColor(
                ContextCompat.getColor(holder.itemView.context, colorCaja),
            )
            if (almacen.tieneCajaVecina) {
                holder.textoSaldoCajaVecina.visibility = android.view.View.VISIBLE
                if (almacen.saldoCajaVecina > 0) {
                    holder.textoSaldoCajaVecina.text = "💰 Saldo disponible: $${almacen.saldoCajaVecina.toInt()}"
                    holder.textoSaldoCajaVecina.setTextColor(
                        ContextCompat.getColor(holder.itemView.context, R.color.colorPrimary),
                    )
                } else {
                    holder.textoSaldoCajaVecina.text = "💰 Saldo: $0 (sin saldo)"
                    holder.textoSaldoCajaVecina.setTextColor(
                        ContextCompat.getColor(holder.itemView.context, R.color.stock_rojo),
                    )
                }
            } else {
                holder.textoSaldoCajaVecina.visibility = android.view.View.GONE
            }
            holder.botonStock.setOnClickListener { onVerStock(almacen) }
            holder.botonLlegar.setOnClickListener { onLlegar(almacen) }
        }

        override fun getItemCount(): Int = almacenes.size

        /**
         * Formatea la distancia en metros a una cadena legible, convirtiéndola
         * a kilómetros si supera los 1000 metros.
         *
         * @param distanciaMetros Distancia en metros, o null si no está disponible.
         * @return Cadena formateada con la distancia o «Sin ubicación».
         */
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
}
