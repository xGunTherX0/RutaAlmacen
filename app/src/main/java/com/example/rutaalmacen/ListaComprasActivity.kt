package com.example.rutaalmacen

import android.Manifest
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import java.util.Locale

class ListaComprasActivity : AppCompatActivity() {

    private lateinit var viewModel: ListaComprasViewModel
    private lateinit var adaptadorLista: AdaptadorListaProductos
    private lateinit var proveedorUbicacion: FusedLocationProviderClient

    private var ubicacionCache: Location? = null
    private var ubicacionCacheTiempo = 0L

    private val solicitudPermisoAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) {
            iniciarReconocimientoVoz()
        } else {
            Toast.makeText(this, "Permiso de micrófono denegado", Toast.LENGTH_SHORT).show()
        }
    }

    private val resultadoVoz = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        if (resultado.resultCode == RESULT_OK) {
            val datos = resultado.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!datos.isNullOrEmpty()) {
                val textoReconocido = datos[0]
                val campoProducto = findViewById<TextInputEditText>(R.id.campo_producto)
                campoProducto.setText(textoReconocido)
            }
        }
    }

    private val solicitudPermisoUbicacion = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { resultados ->
        val concedido = resultados[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            resultados[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (concedido) {
            lifecycleScope.launch {
                val ubicacion = obtenerUbicacionCacheada()
                viewModel.searchStoresForList(ubicacion)
            }
        } else {
            Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_lista_compras)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        proveedorUbicacion = LocationServices.getFusedLocationProviderClient(this)
        viewModel = ViewModelProvider(this)[ListaComprasViewModel::class.java]

        val header = findViewById<View>(R.id.header_lista_compras)
        val paddingHeaderBase = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_lista_compras)) { vista, insets ->
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

        val campoProducto = findViewById<TextInputEditText>(R.id.campo_producto)
        val botonAgregar = findViewById<MaterialButton>(R.id.boton_agregar_producto)
        val botonVoz = findViewById<MaterialButton>(R.id.boton_voz_producto)
        val botonBuscar = findViewById<MaterialButton>(R.id.boton_buscar_almacen)
        val recyclerLista = findViewById<RecyclerView>(R.id.recycler_lista_productos)
        val contenedorListaVacia = findViewById<LinearLayout>(R.id.contenedor_lista_vacia)
        val textoContador = findViewById<TextView>(R.id.texto_contador_productos)
        val contenedorCarga = findViewById<MaterialCardView>(R.id.contenedor_carga_lista)

        adaptadorLista = AdaptadorListaProductos { producto ->
            viewModel.removeProductFromList(producto)
        }
        recyclerLista.layoutManager = LinearLayoutManager(this)
        recyclerLista.adapter = adaptadorLista

        findViewById<MaterialButton>(R.id.boton_volver_lista).setOnClickListener {
            finish()
        }

        fun agregarProducto() {
            val texto = campoProducto.text?.toString().orEmpty()
            viewModel.addProductToList(texto)
            campoProducto.text?.clear()
            campoProducto.requestFocus()
        }

        botonAgregar.setOnClickListener { agregarProducto() }

        botonVoz.setOnClickListener {
            verificarPermisoAudio()
        }

        campoProducto.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                agregarProducto()
                true
            } else {
                false
            }
        }

        botonBuscar.setOnClickListener {
            if (!UbicacionUtil.tienePermisoUbicacion(this)) {
                solicitudPermisoUbicacion.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val ubicacion = obtenerUbicacionCacheada()
                viewModel.searchStoresForList(ubicacion)
            }
        }

        viewModel.listaProductos.observe(this) { lista ->
            adaptadorLista.submitList(lista.toList())
            val total = lista.size
            textoContador.text = if (total == 1) "1 producto en tu lista" else "$total productos en tu lista"
            contenedorListaVacia.visibility = if (lista.isEmpty()) View.VISIBLE else View.GONE
            recyclerLista.visibility = if (lista.isEmpty()) View.GONE else View.VISIBLE
            botonBuscar.isEnabled = lista.isNotEmpty()
        }

        viewModel.isLoading.observe(this) { cargando ->
            contenedorCarga.visibility = if (cargando) View.VISIBLE else View.GONE
            botonBuscar.isEnabled = !cargando && (viewModel.listaProductos.value?.isNotEmpty() == true)
        }

        viewModel.resultados.observe(this) { resultados ->
            if (resultados.isNotEmpty()) {
                val intent = Intent(this, ResultadosListaComprasActivity::class.java)
                intent.putExtra(ResultadosListaComprasActivity.EXTRA_RESULTADOS, ArrayList(resultados))
                intent.putExtra(ResultadosListaComprasActivity.EXTRA_TOTAL_PRODUCTOS, viewModel.listaProductos.value?.size ?: 0)
                intent.putStringArrayListExtra(ResultadosListaComprasActivity.EXTRA_LISTA_PRODUCTOS, ArrayList(viewModel.listaProductos.value.orEmpty()))
                startActivity(intent)
                viewModel.clearResultados()
            }
        }

        viewModel.mensajeError.observe(this) { mensaje ->
            if (!mensaje.isNullOrBlank()) {
                Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }
    }

    private fun ocultarTeclado() {
        val vista = currentFocus ?: View(this)
        val gestor = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        gestor.hideSoftInputFromWindow(vista.windowToken, 0)
        vista.clearFocus()
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

    private fun verificarPermisoAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            iniciarReconocimientoVoz()
        } else {
            solicitudPermisoAudio.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun iniciarReconocimientoVoz() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Di el nombre del producto")
        }
        try {
            resultadoVoz.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Reconocimiento de voz no disponible", Toast.LENGTH_SHORT).show()
        }
    }

    private class AdaptadorListaProductos(
        private val onEliminar: (String) -> Unit,
    ) : RecyclerView.Adapter<AdaptadorListaProductos.VistaProducto>() {

        private val productos = mutableListOf<String>()

        fun submitList(nuevaLista: List<String>) {
            productos.clear()
            productos.addAll(nuevaLista)
            notifyDataSetChanged()
        }

        class VistaProducto(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val textoNombre: TextView = itemView.findViewById(R.id.texto_nombre_producto_item)
            val botonEliminar: MaterialButton = itemView.findViewById(R.id.boton_eliminar_producto)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaProducto {
            val vista = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_producto_lista, parent, false)
            return VistaProducto(vista)
        }

        override fun onBindViewHolder(holder: VistaProducto, position: Int) {
            val producto = productos[position]
            holder.textoNombre.text = producto
            holder.botonEliminar.setOnClickListener { onEliminar(producto) }
        }

        override fun getItemCount(): Int = productos.size
    }
}
