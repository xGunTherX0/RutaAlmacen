package com.example.rutaalmacen

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.chip.Chip
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.Normalizer
import java.util.Locale

class StockAlmacenActivity : AppCompatActivity() {

    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val coleccionInventarioPublico = "InventarioPublico"

    private val productosBase: MutableList<ProductoAlmacen> = mutableListOf()
    private val productosFiltrados: MutableList<ProductoAlmacen> = mutableListOf()
    private lateinit var adaptador: AdaptadorStockAlmacen
    private var tareaFiltro: Job? = null
    private var categoriaSeleccionada = "Todas"

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_stock_almacen)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_stock_almacen)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                barrasDelSistema.top,
                barrasDelSistema.right,
                barrasDelSistema.bottom,
            )
            insets
        }

        val vendedorId = intent.getStringExtra(EXTRA_VENDEDOR_ID).orEmpty()
        if (vendedorId.isBlank()) {
            mostrarMensaje("No se pudo identificar el almacén")
            finish()
            return
        }

        val nombreAlmacen = intent.getStringExtra(EXTRA_NOMBRE_ALMACEN)
            ?.takeIf { it.isNotBlank() }
            ?: "Almacén"
        val horarioAtencion = intent.getStringExtra(EXTRA_HORARIO_ATENCION)
            ?.takeIf { it.isNotBlank() }
            ?: HORARIO_ATENCION_POR_DEFECTO
        val latitudAlmacen = if (intent.hasExtra(EXTRA_LATITUD_ALMACEN)) {
            intent.getDoubleExtra(EXTRA_LATITUD_ALMACEN, 0.0)
        } else {
            null
        }
        val longitudAlmacen = if (intent.hasExtra(EXTRA_LONGITUD_ALMACEN)) {
            intent.getDoubleExtra(EXTRA_LONGITUD_ALMACEN, 0.0)
        } else {
            null
        }

        val textoNombre = findViewById<android.widget.TextView>(R.id.texto_nombre_almacen)
        val textoHorario = findViewById<android.widget.TextView>(R.id.texto_horario_almacen)
        val campoBusqueda = findViewById<TextInputEditText>(R.id.campo_busqueda_stock)
        val campoCategoria = findViewById<AutoCompleteTextView>(R.id.campo_categoria_stock)
        val recyclerStock = findViewById<RecyclerView>(R.id.recycler_stock)
        val botonLlegar = findViewById<MaterialButton>(R.id.boton_llegar_almacen_stock)

        textoNombre.text = nombreAlmacen
        textoHorario.text = "Horario: $horarioAtencion"
        botonLlegar.setOnClickListener {
            abrirNavegacion(latitudAlmacen, longitudAlmacen, nombreAlmacen)
        }

        adaptador = AdaptadorStockAlmacen(productosFiltrados)
        recyclerStock.layoutManager = LinearLayoutManager(this)
        recyclerStock.adapter = adaptador

        configurarCategoria(campoCategoria)

        campoBusqueda.doOnTextChanged { texto, _, _, _ ->
            val consulta = texto?.toString()?.trim().orEmpty()
            tareaFiltro?.cancel()
            tareaFiltro = lifecycleScope.launch {
                delay(300)
                aplicarFiltros(consulta)
            }
        }

        lifecycleScope.launch { cargarProductos(vendedorId) }
    }

    private fun configurarCategoria(campoCategoria: AutoCompleteTextView) {
        val adaptadorCategorias = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            categorias,
        )
        campoCategoria.setAdapter(adaptadorCategorias)
        campoCategoria.setText(categoriaSeleccionada, false)
        campoCategoria.setOnItemClickListener { _, _, posicion, _ ->
            categoriaSeleccionada = categorias.getOrNull(posicion) ?: "Todas"
            aplicarFiltros(findViewById<TextInputEditText>(R.id.campo_busqueda_stock).text?.toString().orEmpty())
        }
    }

    private suspend fun cargarProductos(vendedorId: String) {
        try {
            val documentos = baseDatos.collection(coleccionInventarioPublico)
                .whereEqualTo("vendedorId", vendedorId)
                .get()
                .await()
                .documents

            val nuevosProductos = documentos.map { documento ->
                val nombre = documento.getString("nombre").orEmpty()
                val nombreNormalizado = documento.getString("nombreNormalizado")
                    ?.takeIf { it.isNotBlank() }
                    ?: normalizarTexto(nombre)
                val unidadPrecio = documento.getString("unidadPrecio").orEmpty().ifBlank { "unidad" }
                ProductoAlmacen(
                    nombre = nombre,
                    nombreNormalizado = nombreNormalizado,
                    categoria = documento.getString("categoria").orEmpty(),
                    precio = documento.getDouble("precio")
                        ?: documento.getLong("precio")?.toDouble()
                        ?: 0.0,
                    unidadPrecio = unidadPrecio,
                    descripcion = documento.getString("descripcion").orEmpty(),
                    disponible = documento.getBoolean("disponible") ?: true,
                )
            }

            productosBase.clear()
            productosBase.addAll(nuevosProductos)
            aplicarFiltros(findViewById<TextInputEditText>(R.id.campo_busqueda_stock).text?.toString().orEmpty())
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo cargar el stock del almacén")
        }
    }

    private fun aplicarFiltros(consulta: String) {
        val consultaNormalizada = normalizarTexto(consulta)
        val filtrados = productosBase.filter { producto ->
            val cumpleCategoria = categoriaSeleccionada == "Todas" || producto.categoria == categoriaSeleccionada
            val cumpleTexto = consultaNormalizada.isBlank() || producto.nombreNormalizado.startsWith(consultaNormalizada)
            cumpleCategoria && cumpleTexto
        }

        productosFiltrados.clear()
        productosFiltrados.addAll(filtrados)
        adaptador.notifyDataSetChanged()
    }

    private fun normalizarTexto(texto: String): String {
        val limpio = texto.trim().lowercase(Locale.getDefault())
        val normalizado = Normalizer.normalize(limpio, Normalizer.Form.NFD)
        return normalizado.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }

    private fun abrirNavegacion(latitud: Double?, longitud: Double?, nombreAlmacen: String) {
        if (latitud == null || longitud == null) {
            mostrarMensaje("Ubicación del almacén no disponible")
            return
        }

        val etiqueta = nombreAlmacen.ifBlank { "Almacén" }
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

    data class ProductoAlmacen(
        val nombre: String,
        val nombreNormalizado: String,
        val categoria: String,
        val precio: Double,
        val unidadPrecio: String,
        val descripcion: String,
        val disponible: Boolean,
    )

    companion object {
        const val EXTRA_VENDEDOR_ID = "extra_vendedor_id"
        const val EXTRA_NOMBRE_ALMACEN = "extra_nombre_almacen"
        const val EXTRA_HORARIO_ATENCION = "extra_horario_atencion"
        const val EXTRA_LATITUD_ALMACEN = "extra_latitud_almacen"
        const val EXTRA_LONGITUD_ALMACEN = "extra_longitud_almacen"
        private const val HORARIO_ATENCION_POR_DEFECTO = "09:00 - 13:00 / 16:00 - 22:00"
    }
}

private class AdaptadorStockAlmacen(
    private val productos: List<StockAlmacenActivity.ProductoAlmacen>,
) : RecyclerView.Adapter<AdaptadorStockAlmacen.VistaProducto>() {

    class VistaProducto(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val textoNombre: android.widget.TextView = itemView.findViewById(R.id.texto_nombre_producto)
        val chipCategoria: Chip = itemView.findViewById(R.id.chip_categoria_producto)
        val textoPrecio: android.widget.TextView = itemView.findViewById(R.id.texto_precio_producto)
        val textoDescripcion: android.widget.TextView = itemView.findViewById(R.id.texto_descripcion_producto)
        val textoEstado: android.widget.TextView = itemView.findViewById(R.id.texto_estado_producto)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaProducto {
        val vista = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stock_almacen, parent, false)
        return VistaProducto(vista)
    }

    override fun onBindViewHolder(holder: VistaProducto, position: Int) {
        val producto = productos[position]
        holder.textoNombre.text = producto.nombre
        holder.chipCategoria.text = producto.categoria
        holder.textoPrecio.text = "Precio: $${String.format(Locale.forLanguageTag("es-CL"), "%.0f", producto.precio)} / " +
            etiquetaUnidadPrecio(producto.unidadPrecio)
        if (producto.descripcion.isBlank()) {
            holder.textoDescripcion.visibility = android.view.View.GONE
        } else {
            holder.textoDescripcion.visibility = android.view.View.VISIBLE
            holder.textoDescripcion.text = "Descripción: ${producto.descripcion}"
        }
        holder.textoEstado.text = if (producto.disponible) {
            "Estado: Disponible"
        } else {
            "Estado: Agotado"
        }
        holder.textoEstado.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                holder.itemView.context,
                if (producto.disponible) R.color.stock_verde else R.color.stock_rojo,
            ),
        )
    }

    override fun getItemCount(): Int = productos.size

    private fun etiquetaUnidadPrecio(unidad: String): String {
        return if (unidad == "kilo") "kg" else "unidad"
    }
}
