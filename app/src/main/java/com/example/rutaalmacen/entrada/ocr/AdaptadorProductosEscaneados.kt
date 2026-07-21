package com.example.rutaalmacen.entrada.ocr

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.rutaalmacen.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * Adaptador de [RecyclerView] para la presentación editable de productos escaneados.
 *
 * Cada elemento de la lista muestra campos editables para el nombre y el precio,
 * un selector desplegable para la categoría, un indicador visual de duplicado
 * y un botón de eliminación. Los cambios en cada campo se notifican al propietario
 * mediante las lambdas [onCambio] y [onEliminar].
 *
 * @property productosIniciales Lista inicial de productos a mostrar.
 * @property categorias Lista de nombres de categorías disponibles en el selector.
 * @property onCambio Lambda invocada cada vez que el usuario modifica un producto.
 * @property onEliminar Lambda invocada cuando el usuario solicita eliminar un producto.
 */
class AdaptadorProductosEscaneados(
    private val productosIniciales: List<ProductoEscaneado>,
    private val categorias: List<String>,
    private val onCambio: (ProductoEscaneado) -> Unit,
    private val onEliminar: (ProductoEscaneado) -> Unit,
) : RecyclerView.Adapter<AdaptadorProductosEscaneados.VistaProducto>() {

    private val productos: MutableList<ProductoEscaneado> = productosIniciales.toMutableList()

    /**
     * Retorna una copia inmutable de la lista actual de productos.
     *
     * @return Lista de [ProductoEscaneado] actualmente mostrados en el adaptador.
     */
    fun obtener(): List<ProductoEscaneado> = productos.toList()

    /**
     * Reemplaza la lista completa de productos y notifica al RecyclerView
     * para que redibuje todos los elementos.
     *
     * @param nuevaLista Lista de productos que sustituye a la actual.
     */
    fun reemplazar(nuevaLista: List<ProductoEscaneado>) {
        productos.clear()
        productos.addAll(nuevaLista)
        notifyDataSetChanged()
    }

    /**
     * Elimina un producto de la lista interna por su identificador y notifica
     * al RecyclerView de la remoción.
     *
     * @param id Identificador único del producto a eliminar.
     */
    fun eliminar(id: Long) {
        val indice = productos.indexOfFirst { it.id == id }
        if (indice >= 0) {
            productos.removeAt(indice)
            notifyItemRemoved(indice)
        }
    }

    override fun getItemCount(): Int = productos.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VistaProducto {
        val vista = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_producto_escaneado, parent, false)
        return VistaProducto(vista)
    }

    override fun onBindViewHolder(holder: VistaProducto, position: Int) {
        holder.vincular(productos[position], position)
    }

    /**
     * Vista de elemento (ViewHolder) que representa un producto individual
     * dentro del RecyclerView.
     *
     * Contiene los campos editables de nombre y precio, el selector de categoría,
     * el indicador de duplicado y el botón de eliminación. Gestiona la vinculación
     * de datos y la suscripción a cambios de texto mediante [TextWatcher].
     *
     * @param vista Vista raíz del elemento de lista inflada desde el layout XML.
     */
    inner class VistaProducto(vista: View) : RecyclerView.ViewHolder(vista) {
        private val campoNombre: TextInputEditText = vista.findViewById(R.id.campo_nombre_escaneado)
        private val spinnerCategoria: Spinner = vista.findViewById(R.id.spinner_categoria_escaneado)
        private val campoPrecio: TextInputEditText = vista.findViewById(R.id.campo_precio_escaneado)
        private val botonEliminar: MaterialButton = vista.findViewById(R.id.boton_eliminar_escaneado)
        private val textoDuplicado: TextView = vista.findViewById(R.id.texto_estado_duplicado)

        private var watcherNombre: TextWatcher? = null
        private var watcherPrecio: TextWatcher? = null
        private var categoriaListenerActivo = false

        /**
         * Vincula los datos de un producto a los controles visuales de esta vista.
         *
         * Configura los campos de nombre, precio y categoría, aplica el estilo visual
         * diferenciado para productos duplicados (borde naranja), y registra los
         * listeners de edición. Previene retroalimentación infinita desactivando
         * temporalmente los listeners durante la carga inicial de datos.
         *
         * @param producto Producto a mostrar en esta posición.
         * @param posicion Índice actual del elemento dentro del adaptador.
         */
        fun vincular(producto: ProductoEscaneado, posicion: Int) {
            watcherNombre?.let { campoNombre.removeTextChangedListener(it) }
            watcherPrecio?.let { campoPrecio.removeTextChangedListener(it) }

            val nuevoNombre = producto.nombre
            if (campoNombre.text?.toString() != nuevoNombre) {
                campoNombre.setText(nuevoNombre)
            }
            val precioTexto = if (producto.precio > 0) {
                if (producto.precio % 1.0 == 0.0) producto.precio.toLong().toString() else producto.precio.toString()
            } else ""
            if (campoPrecio.text?.toString() != precioTexto) {
                campoPrecio.setText(precioTexto)
            }

            val adaptadorCategorias = ArrayAdapter(
                itemView.context,
                android.R.layout.simple_spinner_item,
                categorias,
            )
            adaptadorCategorias.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerCategoria.adapter = adaptadorCategorias
            val indiceCategoria = categorias.indexOf(producto.categoria).coerceAtLeast(0)
            categoriaListenerActivo = false
            spinnerCategoria.setSelection(indiceCategoria)

            textoDuplicado.visibility = if (producto.existeEnCatalogo) View.VISIBLE else View.GONE
            val cardView = itemView as? com.google.android.material.card.MaterialCardView
            if (producto.existeEnCatalogo) {
                cardView?.strokeWidth = 3
                cardView?.strokeColor = androidx.core.content.ContextCompat.getColor(
                    itemView.context,
                    android.R.color.holo_orange_dark,
                )
                cardView?.setCardBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(
                        itemView.context,
                        android.R.color.holo_orange_light,
                    )
                )
            } else {
                cardView?.strokeWidth = 0
                cardView?.setCardBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(
                        itemView.context,
                        com.example.rutaalmacen.R.color.fondo_card,
                    )
                )
            }

            categoriaListenerActivo = false
            spinnerCategoria.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (!categoriaListenerActivo) return
                    if (posicion >= productos.size) return
                    if (pos >= categorias.size) return
                    val productoActual = productos[posicion]
                    if (productoActual.categoria != categorias[pos]) {
                        productos[posicion] = productoActual.copy(categoria = categorias[pos])
                        onCambio(productos[posicion])
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
            categoriaListenerActivo = true

            watcherNombre = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (posicion >= productos.size) return
                    val nuevo = s?.toString().orEmpty().trim()
                    val actual = productos[posicion]
                    if (actual.nombre != nuevo) {
                        productos[posicion] = actual.copy(nombre = nuevo)
                        onCambio(productos[posicion])
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }
            campoNombre.addTextChangedListener(watcherNombre)

            watcherPrecio = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (posicion >= productos.size) return
                    val valor = s?.toString()?.toDoubleOrNull() ?: 0.0
                    val actual = productos[posicion]
                    if (actual.precio != valor) {
                        productos[posicion] = actual.copy(precio = valor)
                        onCambio(productos[posicion])
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }
            campoPrecio.addTextChangedListener(watcherPrecio)

            botonEliminar.setOnClickListener { onEliminar(producto) }
        }
    }
}
