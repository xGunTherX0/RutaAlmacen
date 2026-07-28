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
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

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
                0,
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

        cargarAlmacenes()
    }

    override fun onResume() {
        super.onResume()
        cargarAlmacenes()
    }

    private fun configurarBotonVolver() {
        findViewById<ImageView>(R.id.boton_volver).setOnClickListener {
            finish()
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

    private fun mostrarCarga(mostrar: Boolean) {
        contenedorCarga.visibility = if (mostrar) View.VISIBLE else View.GONE
    }

    private fun cargarAlmacenes() {
        if (tareaCarga?.isActive == true) return
        tareaCarga = lifecycleScope.launch {
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

    private suspend fun obtenerVendedoresServidor(): List<DocumentSnapshot> {
        return baseDatos.collection(Constantes.COLECCION_USUARIOS)
            .whereEqualTo("rol", Constantes.ROL_VENDEDOR)
            .get()
            .await()
            .documents
    }

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
            )
        }
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

    private fun aplicarFiltros() {
        val filtrados = almacenesBase.filter { almacen ->
            categoriaSeleccionada == "Todas" || almacen.categoriaAlmacen == categoriaSeleccionada
        }

        val ordenados = filtrados.sortedWith(
            compareBy<AlmacenCercano> { !it.abiertoAhora }
                .thenBy { it.distanciaMetros ?: Double.MAX_VALUE },
        )

        almacenes.clear()
        almacenes.addAll(ordenados)
        adaptador.notifyDataSetChanged()

        val textoSinAlmacenes = findViewById<TextView>(R.id.texto_sin_almacenes)
        textoSinAlmacenes.visibility = if (almacenes.isEmpty()) View.VISIBLE else View.GONE
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
            putExtra(StockAlmacenActivity.EXTRA_METODOS_PAGO, almacen.metodosPago.toTypedArray())
            putExtra(StockAlmacenActivity.EXTRA_TIENE_CAJA_VECINA, almacen.tieneCajaVecina)
        }
        startActivity(intent)
    }

    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }

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
    )

    private class AdaptadorAlmacenes(
        private val almacenes: List<AlmacenCercano>,
        private val onVerStock: (AlmacenCercano) -> Unit,
        private val onLlegar: (AlmacenCercano) -> Unit,
    ) : RecyclerView.Adapter<AdaptadorAlmacenes.VistaAlmacen>() {

        class VistaAlmacen(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val textoNombre: TextView = itemView.findViewById(R.id.texto_nombre_almacen)
            val chipDistancia: TextView = itemView.findViewById(R.id.chip_distancia)
            val chipEstado: TextView = itemView.findViewById(R.id.chip_estado)
            val chipCajaVecina: TextView = itemView.findViewById(R.id.chip_caja_vecina)
            val textoHorario: TextView = itemView.findViewById(R.id.texto_horario_almacen)
            val textoCategoria: TextView = itemView.findViewById(R.id.texto_categoria_almacen)
            val botonStock: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.boton_ver_stock_almacen)
            val botonLlegar: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.boton_llegar_almacen)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaAlmacen {
            val vista = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_almacen_cercano, parent, false)
            return VistaAlmacen(vista)
        }

        override fun onBindViewHolder(holder: VistaAlmacen, position: Int) {
            val almacen = almacenes[position]
            val contexto = holder.itemView.context

            holder.textoNombre.text = almacen.nombreAlmacen
            holder.chipDistancia.text = formatearDistancia(almacen.distanciaMetros)

            holder.chipEstado.text = if (almacen.abiertoAhora) "Abierto" else "Cerrado"
            val bgEstado = if (almacen.abiertoAhora) R.drawable.bg_chip_abierto else R.drawable.bg_chip_cerrado
            val colorEstado = if (almacen.abiertoAhora) R.color.estado_abierto else R.color.estado_cerrado
            holder.chipEstado.setBackgroundResource(bgEstado)
            holder.chipEstado.setTextColor(ContextCompat.getColor(contexto, colorEstado))

            if (almacen.tieneCajaVecina) {
                holder.chipCajaVecina.visibility = View.VISIBLE
            } else {
                holder.chipCajaVecina.visibility = View.GONE
            }

            holder.textoHorario.text = almacen.horarioAtencion
            holder.textoCategoria.text = almacen.categoriaAlmacen

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
}
