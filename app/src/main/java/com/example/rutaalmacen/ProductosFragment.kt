package com.example.rutaalmacen

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.Normalizer
import java.util.Locale

class ProductosFragment : Fragment(R.layout.fragment_productos) {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val productos: MutableList<Producto> = mutableListOf()
    private lateinit var adaptadorProductos: AdaptadorProductos
    private val categorias = listOf(
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
    private val unidadesPrecio = listOf("Por unidad", "Por kilo")
    private val coleccionInventarioPublico = "InventarioPublico"
    private var avisoPublicacionFallidaMostrado = false

    private lateinit var campoNombre: TextInputEditText
    private lateinit var spinnerCategoria: Spinner
    private lateinit var campoPrecio: TextInputEditText
    private lateinit var campoDescripcion: TextInputEditText
    private lateinit var spinnerUnidadPrecio: Spinner
    private var soloLista: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        soloLista = arguments?.getBoolean(ARG_SOLO_LISTA, false) == true

        val contenedorNuevoProducto = view.findViewById<View>(R.id.contenedor_nuevo_producto)
        val textoLista = view.findViewById<TextView>(R.id.texto_lista_productos)
        val recyclerProductos = view.findViewById<RecyclerView>(R.id.recycler_productos)
        val titulo = view.findViewById<TextView>(R.id.texto_titulo_productos)

        if (soloLista) {
            contenedorNuevoProducto.visibility = View.GONE
            titulo.text = "Lista de productos"
        } else {
            textoLista.visibility = View.GONE
            recyclerProductos.visibility = View.GONE
            titulo.text = "Agregar productos"

            campoNombre = view.findViewById(R.id.campo_nombre_producto)
            spinnerCategoria = view.findViewById(R.id.spinner_categoria)
            campoPrecio = view.findViewById(R.id.campo_precio_producto)
            campoDescripcion = view.findViewById(R.id.campo_descripcion_producto)
            spinnerUnidadPrecio = view.findViewById(R.id.spinner_unidad_precio)
            val botonGuardar = view.findViewById<MaterialButton>(R.id.boton_guardar_producto)

            val adaptadorCategorias = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                categorias,
            )
            adaptadorCategorias.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerCategoria.adapter = adaptadorCategorias

            val adaptadorUnidades = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                unidadesPrecio,
            )
            adaptadorUnidades.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerUnidadPrecio.adapter = adaptadorUnidades

            botonGuardar.setOnClickListener { guardarProducto() }
        }

        if (soloLista) {
            adaptadorProductos = AdaptadorProductos(
                productos = productos,
                onEditar = { producto -> mostrarDialogoEditar(producto) },
                onEliminar = { producto -> confirmarEliminarProducto(producto) },
                onCambiarDisponibilidad = { producto, disponible ->
                    val indice = productos.indexOfFirst { it.id == producto.id }
                    if (indice >= 0) {
                        productos[indice] = producto.copy(disponible = disponible)
                        adaptadorProductos.notifyItemChanged(indice)
                    }
                    actualizarDisponibilidad(producto, disponible)
                },
            )
            recyclerProductos.layoutManager = LinearLayoutManager(requireContext())
            recyclerProductos.adapter = adaptadorProductos

            viewLifecycleOwner.lifecycleScope.launch { cargarProductos() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (soloLista) {
            viewLifecycleOwner.lifecycleScope.launch { cargarProductos() }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && soloLista) {
            viewLifecycleOwner.lifecycleScope.launch { cargarProductos() }
        }
    }

    private fun guardarProducto() {
        val nombre = campoNombre.text?.toString()?.trim().orEmpty()
        val categoria = spinnerCategoria.selectedItem?.toString().orEmpty()
        val precioTexto = campoPrecio.text?.toString()?.trim().orEmpty()
        val descripcion = campoDescripcion.text?.toString()?.trim().orEmpty()
        val unidadPrecio = obtenerUnidadPrecio(spinnerUnidadPrecio.selectedItem?.toString().orEmpty())

        val validacionNombre = FiltroContenido.validarNombreProducto(nombre)
        if (!validacionNombre.esValido) {
            mostrarMensaje(validacionNombre.mensaje)
            return
        }
        val validacionDescripcion = FiltroContenido.validarDescripcion(descripcion)
        if (!validacionDescripcion.esValido) {
            mostrarMensaje(validacionDescripcion.mensaje)
            return
        }

        val precio = precioTexto.toDoubleOrNull()
        if (precio == null || precio < 0) {
            mostrarMensaje("Ingresa un precio válido")
            return
        }

        val cantidad = 1

        val usuario = autenticacion.currentUser
        if (usuario == null) {
            mostrarMensaje("No hay un usuario activo")
            return
        }

        val referenciaProducto = baseDatos.collection("Usuarios")
            .document(usuario.uid)
            .collection("Inventario")
            .document()

        val producto = Producto(
            id = referenciaProducto.id,
            nombre = nombre,
            nombreNormalizado = normalizarTexto(nombre),
            categoria = categoria,
            precio = precio,
            unidadPrecio = unidadPrecio,
            cantidad = cantidad,
            descripcion = descripcion,
            disponible = true,
            fechaActualizacion = System.currentTimeMillis(),
        )

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                referenciaProducto.set(producto).await()
                sincronizarProductoPublico(usuario.uid, producto)

                mostrarMensaje("Producto guardado correctamente")
                limpiarFormulario()
            } catch (excepcion: Exception) {
                mostrarMensaje("No se pudo guardar el producto")
            }
        }
    }

    private suspend fun cargarProductos() {
        val usuario = autenticacion.currentUser ?: return
        try {
            val resultado = baseDatos.collection("Usuarios")
                .document(usuario.uid)
                .collection("Inventario")
                .orderBy("fechaActualizacion", Query.Direction.DESCENDING)
                .get()
                .await()

            val nuevosProductos = resultado.documents.mapNotNull { documento ->
                val base = documento.toObject(Producto::class.java) ?: return@mapNotNull null
                val disponible = documento.getBoolean("disponible") ?: true
                base.copy(id = documento.id, disponible = disponible)
            }

            productos.clear()
            productos.addAll(nuevosProductos)
            adaptadorProductos.notifyDataSetChanged()

            sincronizarInventarioPublico(usuario.uid, nuevosProductos)
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudieron cargar los productos")
        }
    }

    private fun limpiarFormulario() {
        campoNombre.setText("")
        campoPrecio.setText("")
        campoDescripcion.setText("")
        spinnerCategoria.setSelection(0)
        spinnerUnidadPrecio.setSelection(0)
    }

    private fun mostrarDialogoEditar(producto: Producto) {
        if (producto.id.isBlank()) {
            mostrarMensaje("No se pudo identificar el producto")
            return
        }

        val vista = layoutInflater.inflate(R.layout.dialog_editar_producto, null)
        val campoNombre = vista.findViewById<TextInputEditText>(R.id.campo_nombre_producto_editar)
        val spinnerCategoriaDialogo = vista.findViewById<Spinner>(R.id.spinner_categoria_editar)
        val campoPrecio = vista.findViewById<TextInputEditText>(R.id.campo_precio_producto_editar)
        val campoDescripcion = vista.findViewById<TextInputEditText>(R.id.campo_descripcion_producto_editar)
        val spinnerUnidadPrecioDialogo = vista.findViewById<Spinner>(R.id.spinner_unidad_precio_editar)

        campoNombre.setText(producto.nombre)
        campoPrecio.setText(String.format(Locale.forLanguageTag("es-CL"), "%.0f", producto.precio))
        campoDescripcion.setText(producto.descripcion)

        val adaptadorCategorias = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            categorias,
        )
        adaptadorCategorias.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCategoriaDialogo.adapter = adaptadorCategorias
        val indiceCategoria = categorias.indexOf(producto.categoria)
        if (indiceCategoria >= 0) {
            spinnerCategoriaDialogo.setSelection(indiceCategoria)
        }

        val adaptadorUnidades = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            unidadesPrecio,
        )
        adaptadorUnidades.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerUnidadPrecioDialogo.adapter = adaptadorUnidades
        val indiceUnidad = if (producto.unidadPrecio == "kilo") 1 else 0
        spinnerUnidadPrecioDialogo.setSelection(indiceUnidad)

        val dialogo = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Editar producto")
            .setView(vista)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar", null)
            .create()

        dialogo.setOnShowListener {
            val botonGuardar = dialogo.getButton(AlertDialog.BUTTON_POSITIVE)
            botonGuardar.setOnClickListener {
                val nombre = campoNombre.text?.toString()?.trim().orEmpty()
                val categoria = spinnerCategoriaDialogo.selectedItem?.toString().orEmpty()
                val precioTexto = campoPrecio.text?.toString()?.trim().orEmpty()
                val descripcion = campoDescripcion.text?.toString()?.trim().orEmpty()
                val unidadPrecio = obtenerUnidadPrecio(
                    spinnerUnidadPrecioDialogo.selectedItem?.toString().orEmpty(),
                )

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

                actualizarProducto(
                    producto = producto,
                    nombre = nombre,
                    categoria = categoria,
                    precio = precio,
                    unidadPrecio = unidadPrecio,
                    descripcion = descripcion,
                )
                dialogo.dismiss()
            }
        }

        dialogo.show()
    }

    private fun actualizarProducto(
        producto: Producto,
        nombre: String,
        categoria: String,
        precio: Double,
        unidadPrecio: String,
        descripcion: String,
    ) {
        val usuario = autenticacion.currentUser
        if (usuario == null) {
            mostrarMensaje("No hay un usuario activo")
            return
        }
        if (producto.id.isBlank()) {
            mostrarMensaje("No se pudo identificar el producto")
            return
        }

        val datos = mapOf(
            "nombre" to nombre,
            "nombreNormalizado" to normalizarTexto(nombre),
            "categoria" to categoria,
            "precio" to precio,
            "unidadPrecio" to unidadPrecio,
            "cantidad" to 1,
            "descripcion" to descripcion,
            "disponible" to producto.disponible,
            "fechaActualizacion" to System.currentTimeMillis(),
        )

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                baseDatos.collection("Usuarios")
                    .document(usuario.uid)
                    .collection("Inventario")
                    .document(producto.id)
                    .set(datos, SetOptions.merge())
                    .await()

                val productoActualizado = producto.copy(
                    nombre = nombre,
                    nombreNormalizado = normalizarTexto(nombre),
                    categoria = categoria,
                    precio = precio,
                    unidadPrecio = unidadPrecio,
                    cantidad = 1,
                    descripcion = descripcion,
                    disponible = producto.disponible,
                    fechaActualizacion = System.currentTimeMillis(),
                )
                sincronizarProductoPublico(usuario.uid, productoActualizado)

                mostrarMensaje("Producto actualizado")
                cargarProductos()
            } catch (excepcion: Exception) {
                mostrarMensaje("No se pudo actualizar el producto")
            }
        }
    }

    private fun confirmarEliminarProducto(producto: Producto) {
        if (producto.id.isBlank()) {
            mostrarMensaje("No se pudo identificar el producto")
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Eliminar producto")
            .setMessage("¿Deseas eliminar este producto?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                eliminarProducto(producto)
            }
            .show()
    }

    private fun eliminarProducto(producto: Producto) {
        val usuario = autenticacion.currentUser
        if (usuario == null) {
            mostrarMensaje("No hay un usuario activo")
            return
        }
        if (producto.id.isBlank()) {
            mostrarMensaje("No se pudo identificar el producto")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                baseDatos.collection("Usuarios")
                    .document(usuario.uid)
                    .collection("Inventario")
                    .document(producto.id)
                    .delete()
                    .await()

                eliminarProductoPublico(usuario.uid, producto.id)

                mostrarMensaje("Producto eliminado")
                cargarProductos()
            } catch (excepcion: Exception) {
                mostrarMensaje("No se pudo eliminar el producto")
            }
        }
    }

    private fun normalizarTexto(texto: String): String {
        val limpio = texto.trim().lowercase(Locale.getDefault())
        val normalizado = Normalizer.normalize(limpio, Normalizer.Form.NFD)
        return normalizado.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    private fun actualizarDisponibilidad(producto: Producto, disponible: Boolean) {
        val usuario = autenticacion.currentUser
        if (usuario == null) {
            mostrarMensaje("No hay un usuario activo")
            return
        }
        if (producto.id.isBlank()) {
            mostrarMensaje("No se pudo identificar el producto")
            return
        }

        val datos = mapOf(
            "disponible" to disponible,
            "fechaActualizacion" to System.currentTimeMillis(),
        )

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                baseDatos.collection("Usuarios")
                    .document(usuario.uid)
                    .collection("Inventario")
                    .document(producto.id)
                    .set(datos, SetOptions.merge())
                    .await()

                val actualizado = producto.copy(disponible = disponible)
                sincronizarProductoPublico(usuario.uid, actualizado)
            } catch (excepcion: Exception) {
                mostrarMensaje("No se pudo actualizar la disponibilidad")
            }
        }
    }

    private suspend fun sincronizarProductoPublico(uid: String, producto: Producto) {
        val datosAlmacen = obtenerDatosAlmacenPublicos(uid)
        val datosPublicos = construirDatosPublicos(uid, producto, datosAlmacen)
        val documentoPublico = baseDatos.collection(coleccionInventarioPublico)
            .document(obtenerIdPublico(uid, producto.id))
        try {
            documentoPublico.set(datosPublicos, SetOptions.merge()).await()
        } catch (excepcion: Exception) {
            if (!avisoPublicacionFallidaMostrado) {
                mostrarMensaje("No se pudo publicar el producto para búsquedas")
                avisoPublicacionFallidaMostrado = true
            }
        }
    }

    private suspend fun eliminarProductoPublico(uid: String, productoId: String) {
        val documentoPublico = baseDatos.collection(coleccionInventarioPublico)
            .document(obtenerIdPublico(uid, productoId))
        try {
            documentoPublico.delete().await()
        } catch (excepcion: Exception) {
            if (!avisoPublicacionFallidaMostrado) {
                mostrarMensaje("No se pudo eliminar el producto publicado")
                avisoPublicacionFallidaMostrado = true
            }
        }
    }

    private suspend fun sincronizarInventarioPublico(uid: String, productos: List<Producto>) {
        if (productos.isEmpty()) {
            return
        }
        val datosAlmacen = obtenerDatosAlmacenPublicos(uid)
        val lote = baseDatos.batch()
        productos.forEach { producto ->
            val datosPublicos = construirDatosPublicos(uid, producto, datosAlmacen)
            val documentoPublico = baseDatos.collection(coleccionInventarioPublico)
                .document(obtenerIdPublico(uid, producto.id))
            lote.set(documentoPublico, datosPublicos, SetOptions.merge())
        }
        try {
            lote.commit().await()
        } catch (excepcion: Exception) {
            if (!avisoPublicacionFallidaMostrado) {
                mostrarMensaje("No se pudo actualizar el inventario público")
                avisoPublicacionFallidaMostrado = true
            }
        }
    }

    private suspend fun obtenerDatosAlmacenPublicos(uid: String): Map<String, Any?> {
        return try {
            val documento = baseDatos.collection("Usuarios")
                .document(uid)
                .get()
                .await()
            val nombreAlmacen = documento.getString("nombreAlmacen")
                ?.takeIf { it.isNotBlank() }
                ?: documento.getString("nombre")?.takeIf { it.isNotBlank() }
            mapOf(
                "nombreAlmacen" to nombreAlmacen,
                "latitud" to documento.getDouble("latitud"),
                "longitud" to documento.getDouble("longitud"),
                "horarioAtencion" to documento.getString("horarioAtencion"),
                "horarioMananaInicio" to documento.getString("horarioMananaInicio"),
                "horarioMananaFin" to documento.getString("horarioMananaFin"),
                "horarioTardeInicio" to documento.getString("horarioTardeInicio"),
                "horarioTardeFin" to documento.getString("horarioTardeFin"),
            )
        } catch (excepcion: Exception) {
            emptyMap()
        }
    }

    private fun construirDatosPublicos(
        uid: String,
        producto: Producto,
        datosAlmacen: Map<String, Any?>,
    ): Map<String, Any> {
        val datos = mutableMapOf<String, Any>(
            "vendedorId" to uid,
            "productoId" to producto.id,
            "nombre" to producto.nombre,
            "nombreNormalizado" to producto.nombreNormalizado,
            "categoria" to producto.categoria,
            "precio" to producto.precio,
            "unidadPrecio" to producto.unidadPrecio,
            "cantidad" to producto.cantidad,
            "descripcion" to producto.descripcion,
            "disponible" to producto.disponible,
            "fechaActualizacion" to producto.fechaActualizacion,
        )
        datosAlmacen.forEach { (clave, valor) ->
            if (valor != null) {
                datos[clave] = valor
            }
        }
        return datos
    }

    private fun obtenerIdPublico(uid: String, productoId: String): String {
        return "${uid}_$productoId"
    }

    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(requireContext(), mensaje, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ARG_SOLO_LISTA = "solo_lista"

        fun nuevaInstancia(soloLista: Boolean): ProductosFragment {
            val fragmento = ProductosFragment()
            fragmento.arguments = Bundle().apply {
                putBoolean(ARG_SOLO_LISTA, soloLista)
            }
            return fragmento
        }
    }

    data class Producto(
        val id: String = "",
        val nombre: String = "",
        val nombreNormalizado: String = "",
        val categoria: String = "",
        val precio: Double = 0.0,
        val unidadPrecio: String = "unidad",
        val cantidad: Int = 0,
        val descripcion: String = "",
        val disponible: Boolean = true,
        val fechaActualizacion: Long = 0L,
    )

    private class AdaptadorProductos(
        private val productos: List<Producto>,
        private val onEditar: (Producto) -> Unit,
        private val onEliminar: (Producto) -> Unit,
        private val onCambiarDisponibilidad: (Producto, Boolean) -> Unit,
    ) : RecyclerView.Adapter<AdaptadorProductos.VistaProducto>() {

        class VistaProducto(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val textoNombre: android.widget.TextView = itemView.findViewById(R.id.texto_nombre_producto)
            val chipCategoria: com.google.android.material.chip.Chip =
                itemView.findViewById(R.id.chip_categoria_producto)
            val textoPrecio: android.widget.TextView = itemView.findViewById(R.id.texto_precio_producto)
            val botonEditar: MaterialButton = itemView.findViewById(R.id.boton_editar_producto)
            val botonEliminar: MaterialButton = itemView.findViewById(R.id.boton_eliminar_producto)
            val switchDisponible: com.google.android.material.materialswitch.MaterialSwitch =
                itemView.findViewById(R.id.switch_disponible_producto)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaProducto {
            val vista = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_producto, parent, false)
            return VistaProducto(vista)
        }

        override fun onBindViewHolder(holder: VistaProducto, position: Int) {
            val producto = productos[position]
            holder.textoNombre.text = producto.nombre
            holder.chipCategoria.text = producto.categoria
            holder.textoPrecio.text = "Precio: $${String.format(Locale.forLanguageTag("es-CL"), "%.0f", producto.precio)} / " +
                etiquetaUnidadPrecio(producto.unidadPrecio)

            holder.switchDisponible.setOnCheckedChangeListener(null)
            holder.switchDisponible.isChecked = producto.disponible
            holder.switchDisponible.text = if (producto.disponible) "Disponible" else "Agotado"
            holder.switchDisponible.setOnCheckedChangeListener { _, disponible ->
                holder.switchDisponible.text = if (disponible) "Disponible" else "Agotado"
                onCambiarDisponibilidad(producto, disponible)
            }

            holder.botonEditar.setOnClickListener { onEditar(producto) }
            holder.botonEliminar.setOnClickListener { onEliminar(producto) }
        }

        override fun getItemCount(): Int = productos.size

        private fun etiquetaUnidadPrecio(unidad: String): String {
            return if (unidad == "kilo") "kg" else "unidad"
        }
    }

    private fun obtenerUnidadPrecio(texto: String): String {
        return if (texto.contains("kilo", ignoreCase = true)) "kilo" else "unidad"
    }
}
