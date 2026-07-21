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
import java.util.Locale

/**
 * Actividad que muestra el stock de productos de un almacén específico visto por el comprador.
 * Permite buscar productos por nombre, filtrar por categoría y ver información de pagos
 * y disponibilidad. También ofrece navegación al almacén mediante Google Maps.
 */
class StockAlmacenActivity : AppCompatActivity() {

    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

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

    /**
     * Ciclo de vida: inicializa la interfaz, configura el RecyclerView, los filtros
     * de búsqueda y categoría, y carga los productos del almacén desde Firestore.
     *
     * @param savedInstanceState Estado guardado de la instancia anterior, o null si es nueva.
     */
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
        val metodosPago = intent.getStringArrayExtra(EXTRA_METODOS_PAGO)?.toList().orEmpty()
        val tieneCajaVecina = intent.getBooleanExtra(EXTRA_TIENE_CAJA_VECINA, false)

        val textoNombre = findViewById<android.widget.TextView>(R.id.texto_nombre_almacen)
        val textoHorario = findViewById<android.widget.TextView>(R.id.texto_horario_almacen)
        val campoBusqueda = findViewById<TextInputEditText>(R.id.campo_busqueda_stock)
        val campoCategoria = findViewById<AutoCompleteTextView>(R.id.campo_categoria_stock)
        val recyclerStock = findViewById<RecyclerView>(R.id.recycler_stock)
        val botonLlegar = findViewById<MaterialButton>(R.id.boton_llegar_almacen_stock)

        textoNombre.text = nombreAlmacen
        textoHorario.text = "Horario: $horarioAtencion"

        pintarInfoPagos(metodosPago, tieneCajaVecina)

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
        lifecycleScope.launch { cargarInfoPagos(vendedorId, metodosPago, tieneCajaVecina) }
    }

    /**
     * Configura el campo de texto con autocompletado para filtrar productos por categoría.
     *
     * @param campoCategoria Campo de texto donde se muestra el selector de categorías.
     */
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

    /**
     * Carga los productos del inventario público del vendedor desde Firestore
     * y actualiza la lista mostrada en el RecyclerView.
     *
     * @param vendedorId Identificador único del vendedor en Firestore.
     * @throws Exception Si ocurre un error de red o de consulta a Firestore.
     */
    private suspend fun cargarProductos(vendedorId: String) {
        try {
            val documentos = baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
                .whereEqualTo("vendedorId", vendedorId)
                .get()
                .await()
                .documents

            val nuevosProductos = documentos.map { documento ->
                val nombre = documento.getString("nombre").orEmpty()
                val nombreNormalizado = documento.getString("nombreNormalizado")
                    ?.takeIf { it.isNotBlank() }
                    ?: FiltroContenido.normalizar(nombre)
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

    /**
     * Carga la información de métodos de pago y Caja Vecina directamente desde Firestore,
     * utilizando valores de respaldo si la consulta falla.
     *
     * @param vendedorId Identificador único del vendedor en Firestore.
     * @param fallback Lista de métodos de pago de respaldo.
     * @param fallbackCaja Valor de respaldo para Caja Vecina.
     */
    private suspend fun cargarInfoPagos(vendedorId: String, fallback: List<String>, fallbackCaja: Boolean) {
        try {
            val snap = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(vendedorId)
                .get()
                .await()
            val metodos = (snap.get("metodosPago") as? List<String>).orEmpty()
            val caja = snap.getBoolean("tieneCajaVecina") ?: false
            pintarInfoPagos(metodos, caja)
        } catch (_: Exception) {
            pintarInfoPagos(fallback, fallbackCaja)
        }
    }

    /**
     * Actualiza las vistas de información de pagos y Caja Vecina en la interfaz.
     *
     * @param metodos Lista de métodos de pago aceptados por el almacén.
     * @param caja Indica si el almacén acepta Caja Vecina.
     */
    private fun pintarInfoPagos(metodos: List<String>, caja: Boolean) {
        val textoPagos = findViewById<android.widget.TextView>(R.id.texto_pagos_almacen_stock)
        val textoCaja = findViewById<android.widget.TextView>(R.id.texto_caja_vecina_stock)
        textoPagos.text = if (metodos.isEmpty()) "💳 Pagos: No especificado" else "💳 Pagos: ${metodos.joinToString(", ")}"
        textoCaja.text = if (caja) "🏪 Caja Vecina: ✓ Acepta" else "🏪 Caja Vecina: ✗ No acepta"
        textoCaja.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                this,
                if (caja) R.color.stock_verde else R.color.stock_rojo,
            ),
        )
    }

    /**
     * Aplica los filtros de búsqueda por texto y categoría a la lista de productos,
     * normalizando el texto para una comparación sin sensibilidad a mayúsculas ni acentos.
     *
     * @param consulta Texto de búsqueda ingresado por el usuario.
     */
    private fun aplicarFiltros(consulta: String) {
        val consultaNormalizada = FiltroContenido.normalizar(consulta)
        val filtrados = productosBase.filter { producto ->
            val cumpleCategoria = categoriaSeleccionada == "Todas" || producto.categoria == categoriaSeleccionada
            val cumpleTexto = consultaNormalizada.isBlank() || producto.nombreNormalizado.startsWith(consultaNormalizada)
            cumpleCategoria && cumpleTexto
        }

        productosFiltrados.clear()
        productosFiltrados.addAll(filtrados)
        adaptador.notifyDataSetChanged()
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
     * Abre la aplicación de Google Maps para navegar hacia la ubicación del almacén.
     * Si Maps no está instalado, abre la versión web en el navegador.
     *
     * @param latitud Coordenada de latitud del almacén, o null si no está disponible.
     * @param longitud Coordenada de longitud del almacén, o null si no está disponible.
     * @param nombreAlmacen Nombre del almacén a mostrar como etiqueta en el mapa.
     */
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

    /**
     * Modelo de datos que representa un producto del stock de un almacén.
     *
     * @property nombre Nombre del producto.
     * @property nombreNormalizado Nombre normalizado para búsquedas sin sensibilidad a mayúsculas ni acentos.
     * @property categoria Categoría del producto.
     * @property precio Precio del producto.
     * @property unidadPrecio Unidad de venta del producto (unidad o kilo).
     * @property descripcion Descripción del producto.
     * @property disponible Indica si el producto está disponible.
     */
    data class ProductoAlmacen(
        val nombre: String,
        val nombreNormalizado: String,
        val categoria: String,
        val precio: Double,
        val unidadPrecio: String,
        val descripcion: String,
        val disponible: Boolean,
    )

    /**
     * Constantes utilizadas para pasar datos entre actividades mediante extras del Intent.
     */
    companion object {
        const val EXTRA_VENDEDOR_ID = "extra_vendedor_id"
        const val EXTRA_NOMBRE_ALMACEN = "extra_nombre_almacen"
        const val EXTRA_HORARIO_ATENCION = "extra_horario_atencion"
        const val EXTRA_LATITUD_ALMACEN = "extra_latitud_almacen"
        const val EXTRA_LONGITUD_ALMACEN = "extra_longitud_almacen"
        const val EXTRA_METODOS_PAGO = "extra_metodos_pago"
        const val EXTRA_TIENE_CAJA_VECINA = "extra_tiene_caja_vecina"
        private const val HORARIO_ATENCION_POR_DEFECTO = "09:00 - 13:00 / 16:00 - 22:00"
    }
}

/**
 * Adaptador del RecyclerView que muestra la lista de productos del stock del almacén,
 * enlazando cada producto con su vista correspondiente.
 *
 * @property productos Lista de productos a mostrar.
 */
private class AdaptadorStockAlmacen(
    private val productos: List<StockAlmacenActivity.ProductoAlmacen>,
) : RecyclerView.Adapter<AdaptadorStockAlmacen.VistaProducto>() {

    /**
     * ViewHolder que contiene las referencias a las vistas de cada elemento
     * de la lista de productos del stock.
     *
     * @param itemView Vista raíz del elemento de la lista.
     */
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

    /**
     * Convierte la unidad de precio a una etiqueta corta para mostrar en la interfaz.
     *
     * @param unidad Unidad de precio (unidad o kilo).
     * @return Etiqueta corta: «kg» para kilo, «unidad» para cualquier otro valor.
     */
    private fun etiquetaUnidadPrecio(unidad: String): String {
        return if (unidad == "kilo") "kg" else "unidad"
    }
}
