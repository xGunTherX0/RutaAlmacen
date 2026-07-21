package com.example.rutaalmacen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.rutaalmacen.entrada.archivo.ArchivoParser
import com.example.rutaalmacen.entrada.ocr.OcrActivity
import com.example.rutaalmacen.entrada.ocr.PrevisualizacionActivity
import com.example.rutaalmacen.entrada.voz.VozAsistenteActivity
import com.example.rutaalmacen.pagos.EstadoSuscripcion
import com.example.rutaalmacen.pagos.PlanManager
import com.example.rutaalmacen.pagos.PlanSuscripcionActivity
import com.example.rutaalmacen.pagos.ResultadoValidacion
import com.example.rutaalmacen.productos.OfertaDialogFragment
import com.example.rutaalmacen.productos.OfertaUtil
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
import java.util.Locale

/**
 * Fragmento que gestiona el catálogo de productos del vendedor.
 *
 * Ofrece dos modos de operación:
 * - **Modo agregar**: formulario para registrar productos nuevos mediante texto,
 *   dictado de voz, escaneo OCR de boletas o importación desde archivos (Excel, CSV, ODS).
 * - **Modo lista**: visualización del inventario completo con búsqueda, edición,
 *   eliminación, programación de ofertas y control de disponibilidad.
 *
 * Los productos se almacenan en Firestore (subcolección `Inventario`) y se sincronizan
 * de forma automática con la colección de inventario público para las búsquedas de
 * compradores. Integra validación de contenido, control de límites del plan de suscripción
 * y limpieza de ofertas expiradas.
 */
class ProductosFragment : Fragment(R.layout.fragment_productos) {

    /** Instancia de Firebase Authentication obtenida de forma perezosa. */
    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    /** Instancia de Firestore obtenida de forma perezosa para lecturas y escrituras remotas. */
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    /** Administrador del plan de suscripción del vendedor. */
    private val planManager = PlanManager()
    /** Estado actual de la suscripción, cargado de forma asíncrona. */
    private var estadoSuscripcion: EstadoSuscripcion? = null
    /** Lista mutable de productos visibles tras aplicar el filtro de búsqueda. */
    private val productos: MutableList<Producto> = mutableListOf()
    /** Lista maestra de todos los productos cargados, sin filtrar. */
    private val productosBase: MutableList<Producto> = mutableListOf()
    /** Texto de búsqueda actual para el filtrado de la lista de productos. */
    private var consultaBusquedaLista: String = ""
    /** Referencia a la vista de mensaje cuando la lista está vacía. */
    private var textoVacioLista: TextView? = null
    /** Adaptador del [RecyclerView] que presenta los productos en tarjetas individuales. */
    private lateinit var adaptadorProductos: AdaptadorProductos
    /** Categorías disponibles para la clasificación de productos. */
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
    /** Unidades de precio disponibles para la venta de productos. */
    private val unidadesPrecio = listOf("Por unidad", "Por kilo")
    /** Indica si ya se mostró el aviso de publicación fallida para evitar mensajes repetidos. */
    private var avisoPublicacionFallidaMostrado = false

    /** Campo de entrada para el nombre del producto. */
    private lateinit var campoNombre: TextInputEditText
    /** Selector de categoría del producto. */
    private lateinit var spinnerCategoria: Spinner
    /** Campo de entrada para el precio del producto. */
    private lateinit var campoPrecio: TextInputEditText
    /** Campo de entrada para la descripción del producto. */
    private lateinit var campoDescripcion: TextInputEditText
    /** Selector de unidad de precio (por unidad o por kilo). */
    private lateinit var spinnerUnidadPrecio: Spinner
    /** Indica si el fragmento está en modo solo lista (sin formulario de agregado). */
    private var soloLista: Boolean = false

    /**
     * Lanzador de actividad para dictado de productos por voz.
     *
     * Al recibir un resultado exitoso, recarga la lista de productos y muestra
     * un mensaje de confirmación.
     */
    private val lanzadorVoz = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { resultado ->
        if (resultado.resultCode == Activity.RESULT_OK) {
            viewLifecycleOwner.lifecycleScope.launch { cargarProductos() }
            Toast.makeText(requireContext(), "Productos guardados por voz", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Lanzador de selección de contenido para importar archivos de productos.
     *
     * Al seleccionar un archivo, invoca [procesarArchivo] para su análisis.
     */
    private val lanzadorArchivo = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            procesarArchivo(uri)
        }
    }

    /**
     * Lanzador de apertura de documentos para importar archivos de productos.
     *
     * Al seleccionar un archivo, invoca [procesarArchivo] para su análisis.
     */
    private val lanzadorArchivoMultiproposito = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            procesarArchivo(uri)
        }
    }

    /** Tipos MIME aceptados para la importación de archivos de productos. */
    private val tiposMimeArchivos = arrayOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "text/csv",
        "text/comma-separated-values",
        "application/vnd.oasis.opendocument.spreadsheet",
        "text/plain",
    )

    /**
     * Inicializa las vistas del fragmento según el modo de operación (agregar o lista).
     *
     * En modo lista, configura el [RecyclerView], el campo de búsqueda y carga los productos.
     * En modo agregar, configura el formulario con sus selectores de categoría y unidad de precio,
     * el botón de guardado y el menú de métodos de entrada alternativos (voz, OCR, archivo).
     *
     * @param view Vista raíz inflada del fragmento.
     * @param savedInstanceState Estado guardado previamente, o `null` si es un inicio nuevo.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        soloLista = arguments?.getBoolean(ARG_SOLO_LISTA, false) == true

        val contenedorNuevoProducto = view.findViewById<View>(R.id.contenedor_nuevo_producto)
        val textoLista = view.findViewById<TextView>(R.id.texto_lista_productos)
        val recyclerProductos = view.findViewById<RecyclerView>(R.id.recycler_productos)
        val titulo = view.findViewById<TextView>(R.id.texto_titulo_productos)
        val botonMenuAgregar = view.findViewById<MaterialButton>(R.id.boton_menu_agregar)
        val textoLimiteProductos = view.findViewById<TextView>(R.id.texto_limite_productos)

        if (soloLista) {
            contenedorNuevoProducto.visibility = View.GONE
            view.findViewById<View>(R.id.boton_ver_planes).visibility = View.GONE
            botonMenuAgregar.visibility = View.GONE
            titulo.text = "Lista de productos"
            textoLista.visibility = View.GONE
            val contenedorBusqueda = view.findViewById<com.google.android.material.textfield.TextInputLayout>(
                R.id.contenedor_busqueda_lista_productos,
            )
            contenedorBusqueda.visibility = View.VISIBLE
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
            viewLifecycleOwner.lifecycleScope.launch {
                estadoSuscripcion = planManager.cargarEstadoActual()
                actualizarContadorProductos(textoLimiteProductos)
            }
        } else {
            textoLista.visibility = View.GONE
            recyclerProductos.visibility = View.GONE
            titulo.text = "Agregar productos"
            textoLimiteProductos.visibility = View.VISIBLE

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

            botonMenuAgregar.setOnClickListener { boton ->
                boton.animate().rotationBy(180f).setDuration(300).start()
                
                val popup = androidx.appcompat.widget.PopupMenu(requireContext(), boton)
                popup.menuInflater.inflate(R.menu.menu_productos_agregar, popup.menu)
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.opcion_dictar_voz -> {
                            lanzadorVoz.launch(Intent(requireContext(), VozAsistenteActivity::class.java))
                            true
                        }
                        R.id.opcion_escanear_boleta -> {
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Abriendo escáner de boleta...",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            startActivity(Intent(requireContext(), OcrActivity::class.java))
                            true
                        }
                        R.id.opcion_importar_archivo -> {
                            lanzadorArchivoMultiproposito.launch(tiposMimeArchivos)
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }

            view.findViewById<MaterialButton>(R.id.boton_ver_planes).setOnClickListener {
                startActivity(Intent(requireContext(), PlanSuscripcionActivity::class.java))
            }

            viewLifecycleOwner.lifecycleScope.launch {
                estadoSuscripcion = planManager.cargarEstadoActual()
                actualizarContadorProductos(textoLimiteProductos)
            }
        }

        if (soloLista) {
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

            viewLifecycleOwner.lifecycleScope.launch { cargarProductos() }
        }
    }

    /**
     * Recarga el estado de suscripción y los productos al volver a primer plano.
     *
     * En modo lista, actualiza el contador de productos y recarga el inventario.
     * En modo agregar, solo actualiza el contador de productos.
     */
    override fun onResume() {
        super.onResume()
        if (soloLista) {
            viewLifecycleOwner.lifecycleScope.launch {
                estadoSuscripcion = planManager.cargarEstadoActual()
                val textoLimiteProductos = view?.findViewById<TextView>(R.id.texto_limite_productos)
                if (textoLimiteProductos != null) {
                    actualizarContadorProductos(textoLimiteProductos)
                }
                cargarProductos()
            }
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                estadoSuscripcion = planManager.cargarEstadoActual()
                val textoLimiteProductos = view?.findViewById<TextView>(R.id.texto_limite_productos)
                if (textoLimiteProductos != null) {
                    actualizarContadorProductos(textoLimiteProductos)
                }
            }
        }
    }

    /**
     * Recarga los datos del fragmento cuando cambia su visibilidad dentro del contenedor.
     *
     * Si el fragmento pasa a estar visible y está en modo lista, actualiza el estado
     * de suscripción, el contador y recarga los productos.
     *
     * @param hidden `true` si el fragmento está oculto; `false` si es visible.
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && soloLista) {
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

    /**
     * Valida y guarda un producto nuevo en Firestore.
     *
     * Lee los campos del formulario, valida el nombre y la descripción con [FiltroContenido],
     * verifica el límite del plan de suscripción y, si todo es correcto, persiste el producto
     * mediante [com.example.rutaalmacen.productos.ProductoRepository.guardar].
     * Tras el guardado exitoso, limpia el formulario y actualiza el contador de productos.
     */
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

        viewLifecycleOwner.lifecycleScope.launch {
            val estado = estadoSuscripcion ?: planManager.cargarEstadoActual().also { estadoSuscripcion = it }
            when (val resultado = planManager.validarGuardarProducto(estado)) {
                is ResultadoValidacion.Bloqueado -> {
                    mostrarDialogoLimite(resultado)
                    return@launch
                }
                is ResultadoValidacion.Permitido -> Unit
            }

            val repositorio = com.example.rutaalmacen.productos.ProductoRepository()
            val resultado = repositorio.guardar(
                nombre = nombre,
                categoria = categoria,
                precio = precio,
                unidadPrecio = unidadPrecio,
                descripcion = descripcion,
            )

            if (resultado.exitoso) {
                mostrarMensaje("Producto guardado correctamente")
                limpiarFormulario()
                estadoSuscripcion = estado.copy(productosActuales = estado.productosActuales + 1)
                if (soloLista) cargarProductos()
            } else {
                mostrarMensaje(resultado.mensaje ?: "No se pudo guardar el producto")
            }
        }
    }

    /**
     * Muestra un diálogo informativo cuando el vendedor alcanza el límite de productos del plan.
     *
     * Ofrece la opción de ver los planes disponibles para actualizar la suscripción.
     *
     * @param resultado Resultado de validación que contiene el mensaje de bloqueo.
     */
    private fun mostrarDialogoLimite(resultado: ResultadoValidacion.Bloqueado) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Límite del plan ${estadoSuscripcion?.plan?.nombre ?: "actual"} alcanzado")
            .setMessage(resultado.mensaje)
            .setNegativeButton("Ahora no", null)
            .setPositiveButton("Ver planes") { _, _ ->
                startActivity(Intent(requireContext(), PlanSuscripcionActivity::class.java))
            }
            .show()
    }

    /**
     * Carga los productos del inventario del vendedor desde Firestore.
     *
     * Consulta la subcolección `Inventario` ordenada por fecha de actualización descendente,
     * mapea los documentos a objetos [Producto], incluye datos de oferta y disponibilidad,
     * limpia las ofertas expiradas y sincroniza el inventario público. Finalmente, actualiza
     * el contador de productos en la interfaz.
     *
     * @throws Exception Si la consulta a Firestore falla; se muestra un mensaje de error al usuario.
     */
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
            if (soloLista) {
                val textoLimiteProductos = view?.findViewById<TextView>(R.id.texto_limite_productos)
                if (textoLimiteProductos != null) {
                    actualizarContadorProductos(textoLimiteProductos)
                }
            }
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudieron cargar los productos")
        }
    }

    /**
     * Actualiza el texto del contador de productos en la interfaz.
     *
     * Muestra el formato «X / Y», donde Y puede ser un número finito o el símbolo
     * de infinito si el plan no tiene límite de productos.
     *
     * @param textoLimite Vista de texto donde se presenta el contador.
     */
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

    /**
     * Aplica el filtro de búsqueda sobre la lista maestra de productos y actualiza el adaptador.
     *
     * Si la consulta está vacía, muestra todos los productos. En caso contrario, filtra por
     * nombre normalizado o categoría. Actualiza la visibilidad del mensaje de lista vacía
     * según el resultado del filtrado.
     */
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

    /**
     * Limpia las ofertas expiradas de los productos y actualiza sus documentos en Firestore.
     *
     * Identifica los productos cuya fecha de fin de oferta es anterior al momento actual,
     * resetea sus campos de oferta en Firestore y devuelve la lista con las ofertas canceladas.
     *
     * @param uid Identificador único del usuario autenticado.
     * @param productos Lista de productos a evaluar.
     * @return Lista de productos con las ofertas expiradas reseteadas.
     * @throws Exception Si la escritura en Firestore falla; el error se registra para reintento posterior.
     */
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
                // Reintentaremos en la próxima carga
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

    /**
     * Limpia todos los campos del formulario de nuevo producto.
     *
     * Restablece el nombre, precio, descripción y los selectores de categoría y unidad de precio
     * a sus valores por defecto.
     */
    @Suppress("UNCHECKED_CAST")
    private fun limpiarFormulario() {
        campoNombre.setText("")
        campoPrecio.setText("")
        campoDescripcion.setText("")
        spinnerCategoria.setSelection(0)
        spinnerUnidadPrecio.setSelection(0)
    }

    /**
     * Muestra un diálogo para editar los datos de un producto existente.
     *
     * Infla el diseño de edición, precarga los valores actuales del producto y, al confirmar,
     * valida los nuevos datos y persiste los cambios mediante [actualizarProducto].
     *
     * @param producto Producto que se desea editar.
     */
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

    /**
     * Actualiza los datos de un producto en Firestore y sincroniza el inventario público.
     *
     * Escribe los campos modificados mediante una operación de combinación ([SetOptions.merge]),
     * actualiza la lista local y notifica al adaptador del cambio.
     *
     * @param producto Producto original con sus datos actuales.
     * @param nombre Nuevo nombre del producto.
     * @param categoria Nueva categoría del producto.
     * @param precio Nuevo precio del producto.
     * @param unidadPrecio Nueva unidad de precio («unidad» o «kilo»).
     * @param descripcion Nueva descripción del producto.
     * @throws Exception Si la escritura en Firestore falla; se muestra un mensaje al usuario.
     */
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

    /**
     * Muestra el diálogo de programación de oferta para un producto.
     *
     * Verifica que el producto tenga un precio válido antes de abrir el diálogo.
     * Al confirmar, aplica la oferta mediante [aplicarOferta]; al cancelar, la elimina
     * mediante [cancelarOferta].
     *
     * @param producto Producto al que se desea programar una oferta.
     */
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

    /**
     * Aplica una oferta promocional a un producto en Firestore.
     *
     * Actualiza los campos de oferta (precio, descuento, fecha de fin) en el documento
     * del producto y sincroniza el cambio con el inventario público. Refleja el estado
     * en las listas locales y notifica al adaptador.
     *
     * @param producto Producto al que se aplica la oferta.
     * @param precioOferta Nuevo precio con descuento.
     * @param descuentoPorcentaje Porcentaje de descuento aplicado.
     * @param fechaFinOferta Marca de tiempo en milisegundos cuando expira la oferta.
     * @throws Exception Si la escritura en Firestore falla; se muestra un mensaje al usuario.
     */
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

    /**
     * Cancela la oferta activa de un producto en Firestore.
     *
     * Resetea los campos de oferta a `null` y sincroniza el cambio con el inventario público.
     * Refleja el estado en las listas locales y notifica al adaptador.
     *
     * @param producto Producto al que se cancela la oferta.
     * @throws Exception Si la escritura en Firestore falla; se muestra un mensaje al usuario.
     */
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

    /**
     * Muestra un diálogo de confirmación para eliminar un producto.
     *
     * Al aceptar, ejecuta [eliminarProducto] para remover el producto de Firestore.
     *
     * @param producto Producto que se desea eliminar.
     */
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

    /**
     * Elimina un producto de Firestore y del inventario público.
     *
     * Remueve el documento de la subcolección `Inventario` y su contraparte pública,
     * luego recarga la lista de productos.
     *
     * @param producto Producto que se desea eliminar.
     * @throws Exception Si la eliminación en Firestore falla; se muestra un mensaje al usuario.
     */
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

    /**
     * Muestra un mensaje breve al usuario mediante un [Toast].
     *
     * @param mensaje Texto a mostrar.
     */
    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(requireContext(), mensaje, Toast.LENGTH_SHORT).show()
    }

    /**
     * Procesa un archivo seleccionado para importar productos.
     *
     * Parsea el archivo con [ArchivoParser], enriquece los productos con datos del catálogo local
     * y abre la actividad de previsualización para que el vendedor confirme la importación.
     *
     * @param uri URI del archivo seleccionado por el usuario.
     */
    private fun procesarArchivo(uri: Uri) {
        val contexto = requireContext()
        mostrarMensaje("Procesando archivo...")
        viewLifecycleOwner.lifecycleScope.launch {
            val resultadoParsing = ArchivoParser.parsear(contexto, uri)

            when (resultadoParsing) {
                is ArchivoParser.Resultado.Exito -> {
                    if (resultadoParsing.productos.isEmpty()) {
                        mostrarMensaje("No se encontraron productos en el archivo")
                        return@launch
                    }

                    val productosEnriquecidos = ArchivoParser.enriquecerProductos(
                        contexto,
                        resultadoParsing.productos,
                    )

                    val duplicados = productosEnriquecidos.count { it.existeEnCatalogo }
                    val nuevos = productosEnriquecidos.size - duplicados

                    if (productosEnriquecidos.isEmpty()) {
                        mostrarMensaje("No se pudieron procesar los productos")
                    } else {
                        val mensaje = if (duplicados > 0) {
                            "✓ $nuevos nuevo(s) • $duplicados ya en catálogo"
                        } else {
                            "✓ ${productosEnriquecidos.size} producto(s) importado(s)"
                        }
                        mostrarMensaje(mensaje)

                        val json = com.google.gson.Gson().toJson(productosEnriquecidos)
                        val intent = Intent(contexto, PrevisualizacionActivity::class.java).apply {
                            putExtra(PrevisualizacionActivity.EXTRA_PRODUCTOS_JSON, json)
                            putExtra(PrevisualizacionActivity.EXTRA_TEXTO_CRUDO, "Importado desde archivo .${resultadoParsing.formato}")
                        }
                        startActivity(intent)
                    }
                }
                is ArchivoParser.Resultado.Error -> {
                    mostrarMensaje(resultadoParsing.mensaje)
                }
            }
        }
    }

    /**
     * Actualiza la disponibilidad de un producto en Firestore y sincroniza el inventario público.
     *
     * @param producto Producto cuya disponibilidad se desea modificar.
     * @param disponible `true` si el producto está disponible; `false` si está agotado.
     * @throws Exception Si la escritura en Firestore falla; se muestra un mensaje al usuario.
     */
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

    /**
     * Sincroniza un producto individual con la colección de inventario público.
     *
     * Construye los datos públicos combinando la información del producto con los datos
     * del almacén (nombre, ubicación, horario) y los escribe en Firestore.
     *
     * @param uid Identificador único del usuario autenticado.
     * @param producto Producto a sincronizar.
     * @throws Exception Si la escritura en Firestore falla; se muestra un aviso una sola vez.
     */
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

    /**
     * Elimina un producto de la colección de inventario público.
     *
     * @param uid Identificador único del usuario autenticado.
     * @param productoId Identificador del producto a eliminar.
     * @throws Exception Si la eliminación en Firestore falla; se muestra un aviso una sola vez.
     */
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

    /**
     * Sincroniza en lotes todos los productos del inventario con la colección pública.
     *
     * Construye los datos públicos de cada producto y los escribe mediante una operación
     * por lotes de Firestore para optimizar el rendimiento.
     *
     * @param uid Identificador único del usuario autenticado.
     * @param productos Lista de productos a sincronizar.
     * @throws Exception Si la escritura por lotes en Firestore falla; se muestra un aviso una sola vez.
     */
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

    /**
     * Obtiene los datos públicos del almacén del vendedor desde Firestore.
     *
     * Consulta el documento del usuario y extrae el nombre del almacén, la ubicación
     * geográfica y el horario de atención.
     *
     * @param uid Identificador único del usuario autenticado.
     * @return Mapa con los datos públicos del almacén, o un mapa vacío si ocurre un error.
     */
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

    /**
     * Construye el mapa de datos públicos de un producto para la colección de inventario público.
     *
     * Combina los campos del producto con los datos del almacén (nombre, ubicación, horario)
     * para que los compradores dispongan de toda la información necesaria.
     *
     * @param uid Identificador único del usuario autenticado.
     * @param producto Producto del cual extraer los datos.
     * @param datosAlmacen Mapa con los datos públicos del almacén.
     * @return Mapa con todos los campos listos para escritura en Firestore.
     */
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

    /**
     * Genera el identificador compuesto para un documento del inventario público.
     *
     * @param uid Identificador único del usuario autenticado.
     * @param productoId Identificador del producto.
     * @return Cadena con formato «uid_productoId».
     */
    private fun obtenerIdPublico(uid: String, productoId: String): String {
        return "${uid}_$productoId"
    }

    /** Objeto compañero que define constantes y el factory method del fragmento. */
    companion object {
        /** Clave del argumento booleano que indica si el fragmento está en modo solo lista. */
        private const val ARG_SOLO_LISTA = "solo_lista"

        /**
         * Crea una nueva instancia del fragmento con el modo de operación indicado.
         *
         * @param soloLista `true` para mostrar solo la lista de productos sin formulario;
         *   `false` para mostrar el formulario de agregado.
         * @return Instancia configurada de [ProductosFragment].
         */
        fun nuevaInstancia(soloLista: Boolean): ProductosFragment {
            val fragmento = ProductosFragment()
            fragmento.arguments = Bundle().apply {
                putBoolean(ARG_SOLO_LISTA, soloLista)
            }
            return fragmento
        }
    }

    /**
     * Modelo de datos que representa un producto del inventario del vendedor.
     *
     * @property id Identificador único del documento en Firestore.
     * @property nombre Nombre comercial del producto.
     * @property nombreNormalizado Nombre del producto normalizado para búsquedas sin distinción de tildes o mayúsculas.
     * @property categoria Categoría de clasificación del producto.
     * @property precio Precio de venta del producto.
     * @property unidadPrecio Unidad de medida del precio («unidad» o «kilo»).
     * @property cantidad Cantidad disponible del producto.
     * @property descripcion Descripción detallada del producto.
     * @property disponible Indica si el producto está disponible para la venta.
     * @property fechaActualizacion Marca de tiempo de la última modificación en milisegundos.
     * @property precioOferta Precio con descuento aplicado, o `null` si no hay oferta activa.
     * @property descuentoPorcentaje Porcentaje de descuento de la oferta, o `null`.
     * @property fechaFinOferta Marca de tiempo de expiración de la oferta, o `null`.
     * @property enOferta Indica si el producto tiene una oferta activa.
     */
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
        val precioOferta: Double? = null,
        val descuentoPorcentaje: Int? = null,
        val fechaFinOferta: Long? = null,
        val enOferta: Boolean = false,
    )

    /**
     * Adaptador de [RecyclerView] que presenta la lista de productos en tarjetas individuales.
     *
     * Cada elemento muestra el nombre, categoría, precio, estado de oferta y disponibilidad
     * del producto. Incluye botones para editar, programar oferta, eliminar y un interruptor
     * de disponibilidad.
     *
     * @param productos Lista de productos visibles a presentar.
     * @param onEditar Acción ejecutada al pulsar el botón de editar un producto.
     * @param onEliminar Acción ejecutada al pulsar el botón de eliminar un producto.
     * @param onProgramarOferta Acción ejecutada al pulsar el botón de oferta de un producto.
     * @param onCambiarDisponibilidad Acción ejecutada al cambiar el interruptor de disponibilidad.
     */
    private class AdaptadorProductos(
        private val productos: List<Producto>,
        private val onEditar: (Producto) -> Unit,
        private val onEliminar: (Producto) -> Unit,
        private val onProgramarOferta: (Producto) -> Unit,
        private val onCambiarDisponibilidad: (Producto, Boolean) -> Unit,
    ) : RecyclerView.Adapter<AdaptadorProductos.VistaProducto>() {

        /**
         * Contenedor de vistas para cada elemento de producto en el [RecyclerView].
         *
         * @param itemView Vista raíz del elemento de diseño `item_producto`.
         */
        class VistaProducto(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tarjeta: com.google.android.material.card.MaterialCardView =
                itemView.findViewById(R.id.tarjeta_producto)
            val textoNombre: android.widget.TextView = itemView.findViewById(R.id.texto_nombre_producto)
            val chipCategoria: com.google.android.material.chip.Chip =
                itemView.findViewById(R.id.chip_categoria_producto)
            val textoPrecio: android.widget.TextView = itemView.findViewById(R.id.texto_precio_producto)
            val badgeOferta: android.widget.TextView = itemView.findViewById(R.id.badge_oferta_activa)
            val textoResumenOferta: android.widget.TextView = itemView.findViewById(R.id.texto_oferta_resumen)
            val botonEditar: MaterialButton = itemView.findViewById(R.id.boton_editar_producto)
            val botonOferta: MaterialButton = itemView.findViewById(R.id.boton_oferta_producto)
            val botonEliminar: MaterialButton = itemView.findViewById(R.id.boton_eliminar_producto)
            val switchDisponible: com.google.android.material.materialswitch.MaterialSwitch =
                itemView.findViewById(R.id.switch_disponible_producto)
        }

        /**
         * Crea una nueva vista de producto inflando el diseño `item_producto`.
         *
         * @param parent Vista padre donde se insertará el nuevo elemento.
         * @param viewType Tipo de vista (no utilizado en este adaptador).
         * @return Instancia de [VistaProducto] con las vistas vinculadas.
         */
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaProducto {
            val vista = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_producto, parent, false)
            return VistaProducto(vista)
        }

        /**
         * Vincula los datos de un producto con las vistas de su contenedor.
         *
         * Muestra el nombre, categoría, precio, estado de oferta y disponibilidad.
         * Configura los listeners de los botones de editar, oferta, eliminar y del
         * interruptor de disponibilidad.
         *
         * @param holder Contenedor de vistas a actualizar.
         * @param position Posición del elemento en la lista.
         */
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

        /**
         * Devuelve la cantidad total de elementos visibles en el adaptador.
         *
         * @return Número de productos actualmente mostrados.
         */
        override fun getItemCount(): Int = productos.size

        /**
         * Convierte el valor interno de unidad de precio a una etiqueta legible.
         *
         * @param unidad Valor interno de la unidad («unidad» o «kilo»).
         * @return Cadena formateada («unidad» o «kg»).
         */
        private fun etiquetaUnidadPrecio(unidad: String): String {
            return if (unidad == "kilo") "kg" else "unidad"
        }
    }

    /**
     * Convierte el texto seleccionado del spinner de unidad de precio a su valor interno.
     *
     * @param texto Texto mostrado en el spinner (por ejemplo, «Por unidad» o «Por kilo»).
     * @return Valor interno de la unidad («unidad» o «kilo»).
     */
    private fun obtenerUnidadPrecio(texto: String): String {
        return if (texto.contains("kilo", ignoreCase = true)) "kilo" else "unidad"
    }
}
