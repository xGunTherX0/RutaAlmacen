package com.example.rutaalmacen

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * Actividad de administración que permite al vendedor gestionar el stock de productos
 * de su almacén. Ofrece búsqueda por texto, filtro por categoría, creación, edición
 * y eliminación de productos. Los cambios se sincronizan con Firestore tanto en el
 * inventario privado como en el público.
 */
class StockVendedorAdminActivity : AppCompatActivity() {

    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val productosBase: MutableList<ProductoAdmin> = mutableListOf()
    private val productosFiltrados: MutableList<ProductoAdmin> = mutableListOf()
    private lateinit var adaptador: AdaptadorStockAdmin
    private lateinit var textoSinStock: android.widget.TextView
    private var filtroCategoria = FILTRO_TODAS
    private var textoBusqueda = ""
    private lateinit var vendedorId: String

    /**
     * Ciclo de vida: inicializa la interfaz, configura el RecyclerView, los filtros
     * de búsqueda y categoría, y carga los productos del vendedor desde Firestore.
     *
     * @param savedInstanceState Estado guardado de la instancia anterior, o null si es nueva.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_stock_vendedor_admin)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_stock_admin)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                barrasDelSistema.top,
                barrasDelSistema.right,
                barrasDelSistema.bottom,
            )
            insets
        }

        vendedorId = intent.getStringExtra(EXTRA_USUARIO_ID).orEmpty()
        if (vendedorId.isBlank()) {
            mostrarMensaje("No se pudo identificar el vendedor")
            finish()
            return
        }

        val nombre = intent.getStringExtra(EXTRA_NOMBRE).orEmpty().ifBlank { "Vendedor" }
        val correo = intent.getStringExtra(EXTRA_CORREO).orEmpty()
        val nombreAlmacen = intent.getStringExtra(EXTRA_NOMBRE_ALMACEN).orEmpty()

        findViewById<android.widget.TextView>(R.id.texto_detalle_stock_admin).text =
            "Vendedor: $nombre ${if (correo.isNotBlank()) "($correo)" else ""}"
        findViewById<android.widget.TextView>(R.id.texto_nombre_almacen_admin).text =
            if (nombreAlmacen.isNotBlank()) "Almacén: $nombreAlmacen" else "Almacén: sin nombre"

        val recycler = findViewById<RecyclerView>(R.id.recycler_stock_admin)
        textoSinStock = findViewById(R.id.texto_sin_stock_admin)
        val campoBusqueda = findViewById<TextInputEditText>(R.id.campo_busqueda_stock_admin)
        val campoCategoria = findViewById<AutoCompleteTextView>(R.id.campo_filtro_categoria_admin)

        adaptador = AdaptadorStockAdmin(
            productos = productosFiltrados,
            onEditar = { producto -> mostrarDialogoEditar(producto) },
            onEliminar = { producto -> confirmarEliminarProducto(producto) },
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adaptador

        configurarFiltroCategoria(campoCategoria)
        campoBusqueda.doOnTextChanged { texto, _, _, _ ->
            textoBusqueda = texto?.toString().orEmpty()
            aplicarFiltros()
        }

        lifecycleScope.launch { cargarProductos(vendedorId) }
    }

    /**
     * Configura el campo de texto con autocompletado para filtrar productos por categoría.
     *
     * @param campoCategoria Campo de texto donde se muestra el selector de categorías.
     */
    private fun configurarFiltroCategoria(campoCategoria: AutoCompleteTextView) {
        val opciones = listOf(
            FILTRO_TODAS,
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
        val adaptador = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, opciones)
        campoCategoria.setAdapter(adaptador)
        campoCategoria.setText(FILTRO_TODAS, false)
        campoCategoria.setOnItemClickListener { _, _, posicion, _ ->
            filtroCategoria = opciones.getOrNull(posicion) ?: FILTRO_TODAS
            aplicarFiltros()
        }
    }

    /**
     * Carga los productos del inventario privado del vendedor desde Firestore,
     * ordenados por fecha de actualización descendente.
     *
     * @param uid Identificador único del vendedor en Firestore.
     */
    private suspend fun cargarProductos(uid: String) {
        try {
            val resultado = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(uid)
                .collection("Inventario")
                .orderBy("fechaActualizacion", Query.Direction.DESCENDING)
                .get()
                .await()

            val nuevos = resultado.documents.mapNotNull { documento ->
                val nombre = documento.getString("nombre").orEmpty()
                if (nombre.isBlank()) return@mapNotNull null
                val unidadPrecio = documento.getString("unidadPrecio").orEmpty().ifBlank { "unidad" }
                ProductoAdmin(
                    id = documento.id,
                    nombre = nombre,
                    nombreNormalizado = FiltroContenido.normalizar(nombre),
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
            productosBase.addAll(nuevos)
            aplicarFiltros()
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo cargar el stock del vendedor")
        }
    }

    /**
     * Aplica los filtros de búsqueda por texto y categoría a la lista de productos.
     * Muestra un mensaje si no hay productos que coincidan con los filtros.
     */
    private fun aplicarFiltros() {
        val texto = FiltroContenido.normalizar(textoBusqueda)
        val filtrados = productosBase.filter { producto ->
            val cumpleCategoria = filtroCategoria == FILTRO_TODAS || producto.categoria == filtroCategoria
            val cumpleTexto = texto.isBlank() || producto.nombreNormalizado.contains(texto)
            cumpleCategoria && cumpleTexto
        }
        productosFiltrados.clear()
        productosFiltrados.addAll(filtrados)
        adaptador.notifyDataSetChanged()
        textoSinStock.visibility = if (productosFiltrados.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    /**
     * Muestra un diálogo de edición para modificar los datos de un producto existente.
     * Valida los campos ingresados antes de guardar los cambios.
     *
     * @param producto Producto que se desea editar.
     */
    private fun mostrarDialogoEditar(producto: ProductoAdmin) {
        val vista = layoutInflater.inflate(R.layout.dialog_editar_producto, null)
        val campoNombre = vista.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.campo_nombre_producto_editar,
        )
        val spinnerCategoria = vista.findViewById<android.widget.Spinner>(R.id.spinner_categoria_editar)
        val spinnerUnidadPrecio = vista.findViewById<android.widget.Spinner>(R.id.spinner_unidad_precio_editar)
        val campoPrecio = vista.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.campo_precio_producto_editar,
        )
        val campoDescripcion = vista.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.campo_descripcion_producto_editar,
        )

        campoNombre.setText(producto.nombre)
        campoPrecio.setText(String.format(Locale.forLanguageTag("es-CL"), "%.0f", producto.precio))
        campoDescripcion.setText(producto.descripcion)

        val categorias = listOf(
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
        val adaptadorCategorias = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            categorias,
        )
        adaptadorCategorias.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCategoria.adapter = adaptadorCategorias
        val indice = categorias.indexOf(producto.categoria)
        if (indice >= 0) {
            spinnerCategoria.setSelection(indice)
        }

        val unidadesPrecio = listOf("Por unidad", "Por kilo")
        val adaptadorUnidades = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            unidadesPrecio,
        )
        adaptadorUnidades.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerUnidadPrecio.adapter = adaptadorUnidades
        val indiceUnidad = if (producto.unidadPrecio == "kilo") 1 else 0
        spinnerUnidadPrecio.setSelection(indiceUnidad)

        val dialogo = MaterialAlertDialogBuilder(this)
            .setTitle("Editar producto")
            .setView(vista)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar", null)
            .create()

        dialogo.setOnShowListener {
            val botonGuardar = dialogo.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            botonGuardar.setOnClickListener {
                val nombre = campoNombre.text?.toString()?.trim().orEmpty()
                val categoria = spinnerCategoria.selectedItem?.toString().orEmpty()
                val unidadPrecio = if (
                    spinnerUnidadPrecio.selectedItem?.toString()?.contains("kilo", ignoreCase = true) == true
                ) {
                    "kilo"
                } else {
                    "unidad"
                }
                val precioTexto = campoPrecio.text?.toString()?.trim().orEmpty()
                val descripcion = campoDescripcion.text?.toString()?.trim().orEmpty()

                val validacionNombre = FiltroContenido.validarNombreProducto(nombre)
                if (!validacionNombre.esValido) {
                    mostrarMensaje(validacionNombre.mensaje)
                    return@setOnClickListener
                }
                val validacionDescripcion = FiltroContenido.validarDescripcion(descripcion)
                if (!validacionDescripcion.esValido) {
                    mostrarMensaje(validacionDescripcion.mensaje)
                    return@setOnClickListener
                }
                val precio = precioTexto.toDoubleOrNull()
                if (precio == null || precio < 0) {
                    mostrarMensaje("Ingresa un precio válido")
                    return@setOnClickListener
                }

                lifecycleScope.launch {
                    actualizarProducto(producto, nombre, categoria, precio, unidadPrecio, descripcion)
                    dialogo.dismiss()
                }
            }
        }
        dialogo.show()
    }

    /**
     * Actualiza los datos de un producto en el inventario privado y en el inventario público.
     * Recarga la lista de productos tras la actualización exitosa.
     *
     * @param producto Producto original con sus datos anteriores.
     * @param nombre Nuevo nombre del producto.
     * @param categoria Nueva categoría del producto.
     * @param precio Nuevo precio del producto.
     * @param unidadPrecio Nueva unidad de venta (unidad o kilo).
     * @param descripcion Nueva descripción del producto.
     */
    private suspend fun actualizarProducto(
        producto: ProductoAdmin,
        nombre: String,
        categoria: String,
        precio: Double,
        unidadPrecio: String,
        descripcion: String,
    ) {
        try {
            val datos = mapOf(
                "nombre" to nombre,
                "nombreNormalizado" to FiltroContenido.normalizar(nombre),
                "categoria" to categoria,
                "precio" to precio,
                "unidadPrecio" to unidadPrecio,
                "descripcion" to descripcion,
                "fechaActualizacion" to System.currentTimeMillis(),
            )
            val referencia = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(vendedorId)
                .collection("Inventario")
                .document(producto.id)
            referencia.set(datos, SetOptions.merge()).await()

            val documentoPublico = baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
                .whereEqualTo("vendedorId", vendedorId)
                .whereEqualTo("productoId", producto.id)
                .get()
                .await()
            if (!documentoPublico.isEmpty) {
                documentoPublico.documents.forEach { doc ->
                    doc.reference.set(datos, SetOptions.merge()).await()
                }
            }

            mostrarMensaje("Producto actualizado")
            cargarProductos(vendedorId)
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo actualizar el producto")
        }
    }

    /**
     * Muestra un diálogo de confirmación antes de eliminar un producto.
     * Si el usuario confirma, ejecuta la eliminación.
     *
     * @param producto Producto que se desea eliminar.
     */
    private fun confirmarEliminarProducto(producto: ProductoAdmin) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminar producto")
            .setMessage("¿Deseas eliminar ${producto.nombre}?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch { eliminarProducto(producto) }
            }
            .show()
    }

    /**
     * Elimina un producto del inventario privado y del inventario público.
     * Utiliza una escritura por lotes para eliminar los documentos públicos.
     * Recarga la lista de productos tras la eliminación exitosa.
     *
     * @param producto Producto que se desea eliminar.
     */
    private suspend fun eliminarProducto(producto: ProductoAdmin) {
        try {
            baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(vendedorId)
                .collection("Inventario")
                .document(producto.id)
                .delete()
                .await()

            val documentoPublico = baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
                .whereEqualTo("vendedorId", vendedorId)
                .whereEqualTo("productoId", producto.id)
                .get()
                .await()
            if (!documentoPublico.isEmpty) {
                val lote = baseDatos.batch()
                documentoPublico.documents.forEach { doc ->
                    lote.delete(doc.reference)
                }
                lote.commit().await()
            }

            mostrarMensaje("Producto eliminado")
            cargarProductos(vendedorId)
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo eliminar el producto")
        }
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
     * Modelo de datos que representa un producto del inventario del vendedor
     * en la vista de administración.
     *
     * @property id Identificador único del producto en Firestore.
     * @property nombre Nombre del producto.
     * @property nombreNormalizado Nombre normalizado para búsquedas sin sensibilidad a mayúsculas ni acentos.
     * @property categoria Categoría del producto.
     * @property precio Precio del producto.
     * @property unidadPrecio Unidad de venta del producto (unidad o kilo).
     * @property descripcion Descripción del producto.
     * @property disponible Indica si el producto está disponible.
     */
    data class ProductoAdmin(
        val id: String,
        val nombre: String,
        val nombreNormalizado: String,
        val categoria: String,
        val precio: Double,
        val unidadPrecio: String,
        val descripcion: String,
        val disponible: Boolean,
    )

    /**
     * Constantes utilizadas para pasar datos entre actividades mediante extras del Intent
     * y para definir el filtro de categoría por defecto.
     */
    companion object {
        const val EXTRA_USUARIO_ID = "extra_usuario_id"
        const val EXTRA_NOMBRE = "extra_nombre"
        const val EXTRA_CORREO = "extra_correo"
        const val EXTRA_NOMBRE_ALMACEN = "extra_nombre_almacen"
        private const val FILTRO_TODAS = "Todas"
    }
}

/**
 * Adaptador del RecyclerView que muestra la lista de productos en la vista de administración,
 * enlazando cada producto con su vista correspondiente y permitiendo acciones de edición
 * y eliminación.
 *
 * @property productos Lista de productos a mostrar.
 * @property onEditar Acción a ejecutar cuando el usuario pulsa «Editar».
 * @property onEliminar Acción a ejecutar cuando el usuario pulsa «Eliminar».
 */
private class AdaptadorStockAdmin(
    private val productos: List<StockVendedorAdminActivity.ProductoAdmin>,
    private val onEditar: (StockVendedorAdminActivity.ProductoAdmin) -> Unit,
    private val onEliminar: (StockVendedorAdminActivity.ProductoAdmin) -> Unit,
) : RecyclerView.Adapter<AdaptadorStockAdmin.VistaProducto>() {

    /**
     * ViewHolder que contiene las referencias a las vistas de cada elemento
     * de la lista de productos en la administración.
     *
     * @param itemView Vista raíz del elemento de la lista.
     */
    class VistaProducto(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val textoNombre: android.widget.TextView = itemView.findViewById(R.id.texto_nombre_producto_admin)
        val chipCategoria: com.google.android.material.chip.Chip =
            itemView.findViewById(R.id.chip_categoria_producto_admin)
        val textoPrecio: android.widget.TextView = itemView.findViewById(R.id.texto_precio_producto_admin)
        val textoDescripcion: android.widget.TextView = itemView.findViewById(R.id.texto_descripcion_producto_admin)
        val textoEstado: android.widget.TextView = itemView.findViewById(R.id.texto_estado_producto_admin)
        val botonEditar: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.boton_editar_producto_admin)
        val botonEliminar: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.boton_eliminar_producto_admin)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaProducto {
        val vista = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_producto_admin, parent, false)
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
        holder.botonEditar.setOnClickListener { onEditar(producto) }
        holder.botonEliminar.setOnClickListener { onEliminar(producto) }
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
