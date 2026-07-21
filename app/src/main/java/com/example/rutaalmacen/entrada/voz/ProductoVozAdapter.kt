package com.example.rutaalmacen.entrada.voz

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rutaalmacen.databinding.ItemProductoVozBinding

/**
 * Adaptador de [RecyclerView] para la lista de productos detectados por voz.
 *
 * Muestra cada producto en una tarjeta expandible que permite editar nombre,
 * precio, categoría y tipo de precio. Notifica las eliminaciones mediante
 * el callback [onEliminar].
 *
 * @param onEliminar Función callback invocada cuando el usuario solicita eliminar un producto.
 */
class ProductoVozAdapter(
    private val onEliminar: (ProductoDetectado) -> Unit,
) : RecyclerView.Adapter<ProductoVozAdapter.VistaProducto>() {

    private val productos = mutableListOf<ProductoDetectado>()
    private val expandido = mutableSetOf<Long>()

    /**
     * Reemplaza la lista completa de productos y notifica al adaptador.
     *
     * @param nuevaLista Nueva lista de productos que sustituye a la anterior.
     */
    fun actualizar(nuevaLista: List<ProductoDetectado>) {
        productos.clear()
        productos.addAll(nuevaLista)
        notifyDataSetChanged()
    }

    /**
     * Obtiene una copia de la lista actual de productos mostrados en el adaptador.
     *
     * @return Lista inmutable de los productos actualmente visibles.
     */
    fun obtenerProductos(): List<ProductoDetectado> = productos.toList()

    /** {@inheritDoc} */
    override fun getItemCount(): Int = productos.size

    /**
     * {@inheritDoc}
     *
     * Infla el diseño de elemento de producto por voz y crea una instancia
     * de [VistaProducto] para vincular los datos.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VistaProducto {
        val binding = ItemProductoVozBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return VistaProducto(binding)
    }

    /**
     * {@inheritDoc}
     *
     * Vincula los datos del producto en la posición indicada con la vista correspondiente.
     */
    override fun onBindViewHolder(holder: VistaProducto, position: Int) {
        if (position < productos.size) {
            holder.vincular(productos[position], position)
        }
    }

    /**
     * Contenedor de vistas para un elemento individual de producto en el listado.
     *
     * Gestiona la vinculación de datos, la edición en línea de nombre y precio,
     * la selección de categoría y tipo de precio, y el comportamiento de expansión
     * del panel de edición.
     *
     * @param binding Enlace de vistas generado para el elemento de producto por voz.
     */
    inner class VistaProducto(private val binding: ItemProductoVozBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var watcherNombre: TextWatcher? = null
        private var watcherPrecio: TextWatcher? = null
        private var tipoListenerActivo = false
        private var categoriaListenerActivo = false
        private var vinculado = false

        /**
         * Vincula los datos de un producto con las vistas de este contenedor.
         *
         * Configura los campos de texto, selectores, botones y escucha de cambios
         * para permitir la edición en línea de todas las propiedades del producto.
         * Previene retroalimentación infinita desactivando temporalmente los
         * listeners durante la vinculación inicial.
         *
         * @param producto Producto detectado cuyos datos se mostrarán.
         * @param posicion Índice del producto dentro de la lista del adaptador.
         */
        fun vincular(producto: ProductoDetectado, posicion: Int) {
            vinculado = true

            // Se desactivan los listeners temporalmente para evitar retroalimentación durante la vinculación inicial
            watcherNombre?.let { binding.campoNombreVoz.removeTextChangedListener(it) }
            watcherPrecio?.let { binding.campoPrecioVoz.removeTextChangedListener(it) }

            binding.campoNombreVoz.setText(producto.nombre)
            binding.campoPrecioVoz.setText(if (producto.precio > 0) producto.precio.toInt().toString() else "")
            binding.textoCategoria.text = producto.categoria

            val adaptadorCategorias = ArrayAdapter(
                binding.root.context,
                android.R.layout.simple_spinner_item,
                ProductoDetectado.CATEGORIAS,
            )
            adaptadorCategorias.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerCategoriaVoz.adapter = adaptadorCategorias
            val idxCat = ProductoDetectado.CATEGORIAS.indexOf(producto.categoria).coerceAtLeast(0)
            binding.spinnerCategoriaVoz.setSelection(idxCat)

            val estaExpandido = expandido.contains(producto.id)
            binding.panelEdicion.visibility = if (estaExpandido) View.VISIBLE else View.GONE
            binding.botonExpandir.text = if (estaExpandido) "▼" else "▶"

            if (producto.tipoPrecio == "kilo") {
                binding.grupoTipoPrecio.check(binding.botonTipoKilo.id)
            } else {
                binding.grupoTipoPrecio.check(binding.botonTipoUnidad.id)
            }

            binding.botonExpandir.setOnClickListener {
                if (expandido.contains(producto.id)) expandido.remove(producto.id)
                else expandido.add(producto.id)
                notifyItemChanged(posicion)
            }

            binding.botonEliminar.setOnClickListener {
                onEliminar(producto)
            }

            tipoListenerActivo = false
            binding.grupoTipoPrecio.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!vinculado || !isChecked || !tipoListenerActivo) return@addOnButtonCheckedListener
                if (posicion >= productos.size) return@addOnButtonCheckedListener
                val nuevoTipo = if (checkedId == binding.botonTipoKilo.id) "kilo" else "unidad"
                if (productos[posicion].tipoPrecio != nuevoTipo) {
                    productos[posicion] = productos[posicion].copy(tipoPrecio = nuevoTipo)
                }
            }
            tipoListenerActivo = true

            categoriaListenerActivo = false
            binding.spinnerCategoriaVoz.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!vinculado || categoriaListenerActivo) return
                    if (posicion >= productos.size || position >= ProductoDetectado.CATEGORIAS.size) return
                    val nuevaCategoria = ProductoDetectado.CATEGORIAS[position]
                    if (productos[posicion].categoria != nuevaCategoria) {
                        productos[posicion] = productos[posicion].copy(categoria = nuevaCategoria)
                        binding.textoCategoria.text = nuevaCategoria
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            categoriaListenerActivo = true

            watcherNombre = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (!vinculado) return
                    val txt = s?.toString().orEmpty().trim()
                    if (txt.isNotEmpty() && posicion < productos.size) {
                        val p = productos[posicion]
                        if (p.nombre != txt) {
                            productos[posicion] = p.copy(nombre = txt)
                        }
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }
            binding.campoNombreVoz.addTextChangedListener(watcherNombre)

            watcherPrecio = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (!vinculado) return
                    if (posicion >= productos.size) return
                    val valor = s?.toString()?.toDoubleOrNull() ?: 0.0
                    val p = productos[posicion]
                    if (p.precio != valor) {
                        productos[posicion] = p.copy(precio = valor)
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }
            binding.campoPrecioVoz.addTextChangedListener(watcherPrecio)
        }
    }
}
