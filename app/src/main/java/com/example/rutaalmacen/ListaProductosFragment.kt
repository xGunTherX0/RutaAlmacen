package com.example.rutaalmacen

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.example.rutaalmacen.productos.OfertaDialogFragment
import com.example.rutaalmacen.productos.OfertaUtil
import com.example.rutaalmacen.pagos.EstadoSuscripcion
import com.example.rutaalmacen.pagos.PlanManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

import com.example.rutaalmacen.ProductosFragment.Producto

class ListaProductosFragment : Fragment(R.layout.fragment_lista_productos) {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val planManager = PlanManager()
    private var estadoSuscripcion: EstadoSuscripcion? = null
    private val productos: MutableList<Producto> = mutableListOf()
    private val productosBase: MutableList<Producto> = mutableListOf()
    private var consultaBusquedaLista: String = ""
    private var textoVacioLista: TextView? = null
    private lateinit var adaptadorProductos: AdaptadorProductos
    private var avisoPublicacionFallidaMostrado = false

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerProductos = view.findViewById<RecyclerView>(R.id.recycler_productos)
        val textoLimiteProductos = view.findViewById<TextView>(R.id.texto_limite_productos)
        textoVacioLista = view.findViewById(R.id.texto_vacio_lista_productos)

        val campoBusqueda = view.findViewById<TextInputEditText>(R.id.campo_busqueda_lista_productos)
        campoBusqueda.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                consultaBusquedaLista = s?.toString().orEmpty()
                aplicarFiltroLista()
            }
        })

        adaptadorProductos = AdaptadorProductos(
            productos = productos,
            onEditar = { producto -> mostrarDialogoEditar(producto) },
            onEliminar = { producto -> confirmarEliminarProducto(producto) },
            onProgramarOferta = { producto -> mostrarDialogoOferta(producto) },
            onCambiarDisponibilidad = { producto, disponible ->
                val actualizado = producto.copy(disponible = disponible)
                val indiceBase = productosBase.indexOfFirst { it.id == producto.id }
                if (indiceBase >= 0) {
                    productosBase[indiceBase] = actualizado
                }
                val indice = productos.indexOfFirst { it.id == producto.id }
                if (indice >= 0) {
                    productos[indice] = actualizado
                    adaptadorProductos.notifyItemChanged(indice)
                }
                actualizarDisponibilidad(producto, disponible)
            },
        )
        recyclerProductos.layoutManager = LinearLayoutManager(requireContext())
        recyclerProductos.adapter = adaptadorProductos

        viewLifecycleOwner.lifecycleScope.launch {
            estadoSuscripcion = planManager.cargarEstadoActual()
            actualizarContadorProductos(textoLimiteProductos)
            cargarProductos()
        }
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            estadoSuscripcion = planManager.cargarEstadoActual()
            val textoLimiteProductos = view?.findViewById<TextView>(R.id.texto_limite_productos)
            if (textoLimiteProductos != null) {
                actualizarContadorProductos(textoLimiteProductos)
            }
            cargarProductos()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            viewLifecycleOwner.lifecycleScope.launch {
                estadoSuscripcion = planManager.cargarEstadoActual()
                val textoLimiteProductos = view?.findViewById<TextView>(R.id.texto_limite_productos)
                if (textoLimiteProductos != null) {
                    actualizarContadorProductos(textoLimiteProductos)
                }
                cargarProductos()
            }
        }
    }

    private suspend fun cargarProductos() {
        val usuario = autenticacion.currentUser ?: return
        try {
            val resultado = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(usuario.uid)
                .collection("Inventario")
                .orderBy("fechaActualizacion", Query.Direction.DESCENDING)
                .get()
                .await()

            val nuevosProductos = resultado.documents.mapNotNull { documento ->
                val base = documento.toObject(Producto::class.java) ?: return@mapNotNull null
                val disponible = documento.getBoolean("disponible") ?: true
                val datosOferta = OfertaUtil.leerProducto(documento)
                base.copy(
                    id = documento.id,
                    disponible = disponible,
                    enOferta = datosOferta.enOferta,
                    precioOferta = datosOferta.precioOferta,
                    descuentoPorcentaje = datosOferta.descuentoPorcentaje,
                    fechaFinOferta = datosOferta.fechaFinOferta,
                )
            }

            val productosVigentes = limpiarOfertasExpiradas(usuario.uid, nuevosProductos)

            productosBase.clear()
            productosBase.addAll(productosVigentes)
            aplicarFiltroLista()

            sincronizarInventarioPublico(usuario.uid, productosVigentes)

            estadoSuscripcion = estadoSuscripcion?.copy(productosActuales = productosVigentes.size)
            val textoLimiteProductos = view?.findViewById<TextView>(R.id.texto_limite_productos)
            if (textoLimiteProductos != null) {
                actualizarContadorProductos(textoLimiteProductos)
            }
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudieron cargar los productos")
        }
    }

    private fun actualizarContadorProductos(textoLimite: TextView) {
        val estado = estadoSuscripcion
        if (estado == null) {
            textoLimite.text = "0 / 20"
            return
        }
        val limiteTexto = if (estado.plan.maxProductos == Int.MAX_VALUE) {
            "∞"
        } else {
            estado.plan.maxProductos.toString()
        }
        textoLimite.text = "${estado.productosActuales} / $limiteTexto"
    }

    private fun aplicarFiltroLista() {
        val consulta = consultaBusquedaLista.trim()
        val filtrados = if (consulta.isBlank()) {
            productosBase.toList()
        } else {
            val consultaNormalizada = FiltroContenido.normalizar(consulta)
            productosBase.filter { producto ->
                val nombreNormalizado = producto.nombreNormalizado.ifBlank {
                    FiltroContenido.normalizar(producto.nombre)
                }
                nombreNormalizado.contains(consultaNormalizada) ||
                    producto.categoria.lowercase(Locale.forLanguageTag("es-CL"))
                        .contains(consulta.lowercase(Locale.forLanguageTag("es-CL")))
            }
        }
        productos.clear()
        productos.addAll(filtrados)
        if (::adaptadorProductos.isInitialized) {
            adaptadorProductos.notifyDataSetChanged()
        }
        val vacio = textoVacioLista
        if (vacio != null) {
            if (productosBase.isEmpty()) {
                vacio.visibility = View.GONE
            } else if (filtrados.isEmpty()) {
                vacio.visibility = View.VISIBLE
                vacio.text = "Sin resultados para \"$consulta\""
            } else {
                vacio.visibility = View.GONE
            }
        }
    }

    private suspend fun limpiarOfertasExpiradas(
        uid: String,
        productos: List<Producto>,
    ): List<Producto> {
        val ahora = System.currentTimeMillis()
        val expirados = productos.filter { producto ->
            producto.enOferta &&
                (producto.fechaFinOferta == null || producto.fechaFinOferta <= ahora)
        }
        if (expirados.isEmpty()) {
            return productos
        }
        val datosReset = mapOf(
            "enOferta" to false,
            "precioOferta" to null,
            "descuentoPorcentaje" to null,
            "fechaFinOferta" to null,
            "fechaActualizacion" to ahora,
        )
        expirados.forEach { producto ->
            if (producto.id.isBlank()) return@forEach
            try {
                baseDatos.collection(Constantes.COLECCION_USUARIOS)
                    .document(uid)
                    .collection("Inventario")
                    .document(producto.id)
                    .set(datosReset, SetOptions.merge())
                    .await()
            } catch (_: Exception) {
            }
        }
        return productos.map { producto ->
            if (expirados.any { it.id == producto.id }) {
                producto.copy(
                    enOferta = false,
                    precioOferta = null,
                    descuentoPorcentaje = null,
                    fechaFinOferta = null,
                    fechaActualizacion = ahora,
                )
            } else {
                producto
            }
        }
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
            "nombreNormalizado" to FiltroContenido.normalizar(nombre),
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
                baseDatos.collection(Constantes.COLECCION_USUARIOS)
                    .document(usuario.uid)
                    .collection("Inventario")
                    .document(producto.id)
                    .set(datos, SetOptions.merge())
                    .await()

                val productoActualizado = producto.copy(
                    nombre = nombre,
                    nombreNormalizado = FiltroContenido.normalizar(nombre),
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

    private fun mostrarDialogoOferta(producto: Producto) {
        if (producto.id.isBlank()) {
            mostrarMensaje("No se pudo identificar el producto")
            return
        }
        if (producto.precio <= 0.0) {
            mostrarMensaje("El producto debe tener un precio mayor a $0 para crear una oferta")
            return
        }
        val dialogo = OfertaDialogFragment.nuevaInstancia(producto)
        dialogo.setListener(object : OfertaDialogFragment.Listener {
            override fun onOfertaConfirmada(
                producto: Producto,
                precioOferta: Double,
                descuentoPorcentaje: Int,
                fechaFinOferta: Long,
            ) {
                aplicarOferta(producto, precioOferta, descuentoPorcentaje, fechaFinOferta)
            }

            override fun onOfertaCancelada(producto: Producto) {
                cancelarOferta(producto)
            }
        })
        dialogo.show(parentFragmentManager, OfertaDialogFragment.TAG)
    }

    private fun aplicarOferta(
        producto: Producto,
        precioOferta: Double,
        descuentoPorcentaje: Int,
        fechaFinOferta: Long,
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

        val ahora = System.currentTimeMillis()
        val datos = mapOf(
            "enOferta" to true,
            "precioOferta" to precioOferta,
            "descuentoPorcentaje" to descuentoPorcentaje,
            "fechaFinOferta" to fechaFinOferta,
            "fechaActualizacion" to ahora,
        )

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                baseDatos.collection(Constantes.COLECCION_USUARIOS)
                    .document(usuario.uid)
                    .collection("Inventario")
                    .document(producto.id)
                    .set(datos, SetOptions.merge())
                    .await()

                val actualizado = producto.copy(
                    enOferta = true,
                    precioOferta = precioOferta,
                    descuentoPorcentaje = descuentoPorcentaje,
                    fechaFinOferta = fechaFinOferta,
                    fechaActualizacion = ahora,
                )
                val indiceBase = productosBase.indexOfFirst { it.id == producto.id }
                if (indiceBase >= 0) {
                    productosBase[indiceBase] = actualizado
                }
                val indice = productos.indexOfFirst { it.id == producto.id }
                if (indice >= 0) {
                    productos[indice] = actualizado
                    adaptadorProductos.notifyItemChanged(indice)
                }
                sincronizarProductoPublico(usuario.uid, actualizado)
                mostrarMensaje("Oferta activada: $descuentoPorcentaje% de descuento")
            } catch (excepcion: Exception) {
                mostrarMensaje("No se pudo activar la oferta")
            }
        }
    }

    private fun cancelarOferta(producto: Producto) {
        val usuario = autenticacion.currentUser
        if (usuario == null) {
            mostrarMensaje("No hay un usuario activo")
            return
        }
        if (producto.id.isBlank()) {
            mostrarMensaje("No se pudo identificar el producto")
            return
        }

        val ahora = System.currentTimeMillis()
        val datos = mapOf(
            "enOferta" to false,
            "precioOferta" to null,
            "descuentoPorcentaje" to null,
            "fechaFinOferta" to null,
            "fechaActualizacion" to ahora,
        )

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                baseDatos.collection(Constantes.COLECCION_USUARIOS)
                    .document(usuario.uid)
                    .collection("Inventario")
                    .document(producto.id)
                    .set(datos, SetOptions.merge())
                    .await()

                val actualizado = producto.copy(
                    enOferta = false,
                    precioOferta = null,
                    descuentoPorcentaje = null,
                    fechaFinOferta = null,
                    fechaActualizacion = ahora,
                )
                val indiceBase = productosBase.indexOfFirst { it.id == producto.id }
                if (indiceBase >= 0) {
                    productosBase[indiceBase] = actualizado
                }
                val indice = productos.indexOfFirst { it.id == producto.id }
                if (indice >= 0) {
                    productos[indice] = actualizado
                    adaptadorProductos.notifyItemChanged(indice)
                }
                sincronizarProductoPublico(usuario.uid, actualizado)
                mostrarMensaje("Oferta cancelada")
            } catch (excepcion: Exception) {
                mostrarMensaje("No se pudo cancelar la oferta")
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
                baseDatos.collection(Constantes.COLECCION_USUARIOS)
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
                baseDatos.collection(Constantes.COLECCION_USUARIOS)
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
        val documentoPublico = baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
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
        val documentoPublico = baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
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
            val documentoPublico = baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
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
            val documento = baseDatos.collection(Constantes.COLECCION_USUARIOS)
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
            "enOferta" to producto.enOferta,
        )
        producto.precioOferta?.let { datos["precioOferta"] = it }
        producto.descuentoPorcentaje?.let { datos["descuentoPorcentaje"] = it }
        producto.fechaFinOferta?.let { datos["fechaFinOferta"] = it }
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

    private fun obtenerUnidadPrecio(texto: String): String {
        return if (texto.contains("kilo", ignoreCase = true)) "kilo" else "unidad"
    }

    private class AdaptadorProductos(
        private val productos: List<Producto>,
        private val onEditar: (Producto) -> Unit,
        private val onEliminar: (Producto) -> Unit,
        private val onProgramarOferta: (Producto) -> Unit,
        private val onCambiarDisponibilidad: (Producto, Boolean) -> Unit,
    ) : RecyclerView.Adapter<AdaptadorProductos.VistaProducto>() {

        class VistaProducto(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tarjeta: com.google.android.material.card.MaterialCardView =
                itemView.findViewById(R.id.tarjeta_producto)
            val textoNombre: TextView = itemView.findViewById(R.id.texto_nombre_producto)
            val chipCategoria: com.google.android.material.chip.Chip =
                itemView.findViewById(R.id.chip_categoria_producto)
            val textoPrecio: TextView = itemView.findViewById(R.id.texto_precio_producto)
            val badgeOferta: TextView = itemView.findViewById(R.id.badge_oferta_activa)
            val textoResumenOferta: TextView = itemView.findViewById(R.id.texto_oferta_resumen)
            val botonEditar: MaterialButton = itemView.findViewById(R.id.boton_editar_producto)
            val botonOferta: MaterialButton = itemView.findViewById(R.id.boton_oferta_producto)
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
            val contexto = holder.itemView.context
            holder.textoNombre.text = producto.nombre
            holder.chipCategoria.text = producto.categoria
            holder.textoPrecio.text = "Precio: $${String.format(Locale.forLanguageTag("es-CL"), "%.0f", producto.precio)} / " +
                etiquetaUnidadPrecio(producto.unidadPrecio)

            val ofertaVigente = OfertaUtil.estaVigente(producto.enOferta, producto.fechaFinOferta)
            if (ofertaVigente) {
                holder.tarjeta.setCardBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(contexto, R.color.oferta_card_fondo),
                )
                holder.badgeOferta.visibility = View.VISIBLE
                holder.textoResumenOferta.visibility = View.VISIBLE
                holder.textoResumenOferta.text = OfertaUtil.resumenVendedor(producto)
                holder.botonOferta.text = "Editar oferta"
            } else {
                holder.tarjeta.setCardBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(contexto, R.color.fondo_card),
                )
                holder.badgeOferta.visibility = View.GONE
                holder.textoResumenOferta.visibility = View.GONE
                holder.botonOferta.text = "Oferta"
            }

            holder.switchDisponible.setOnCheckedChangeListener(null)
            holder.switchDisponible.isChecked = producto.disponible
            holder.switchDisponible.text = if (producto.disponible) "Disponible" else "Agotado"
            holder.switchDisponible.setOnCheckedChangeListener { _, disponible ->
                holder.switchDisponible.text = if (disponible) "Disponible" else "Agotado"
                onCambiarDisponibilidad(producto, disponible)
            }

            holder.botonEditar.setOnClickListener { onEditar(producto) }
            holder.botonOferta.setOnClickListener { onProgramarOferta(producto) }
            holder.botonEliminar.setOnClickListener { onEliminar(producto) }
        }

        override fun getItemCount(): Int = productos.size

        private fun etiquetaUnidadPrecio(unidad: String): String {
            return if (unidad == "kilo") "kg" else "unidad"
        }
    }
}
