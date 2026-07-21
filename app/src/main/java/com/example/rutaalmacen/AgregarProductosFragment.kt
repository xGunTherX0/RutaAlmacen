package com.example.rutaalmacen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.rutaalmacen.entrada.archivo.ArchivoParser
import com.example.rutaalmacen.entrada.ocr.OcrActivity
import com.example.rutaalmacen.entrada.ocr.PrevisualizacionActivity
import com.example.rutaalmacen.entrada.voz.VozAsistenteActivity
import com.example.rutaalmacen.pagos.EstadoSuscripcion
import com.example.rutaalmacen.pagos.PlanManager
import com.example.rutaalmacen.pagos.PlanSuscripcionActivity
import com.example.rutaalmacen.pagos.ResultadoValidacion
import com.example.rutaalmacen.productos.ProductoRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class AgregarProductosFragment : Fragment(R.layout.fragment_agregar_productos) {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val planManager = PlanManager()
    private var estadoSuscripcion: EstadoSuscripcion? = null

    private lateinit var campoNombre: TextInputEditText
    private lateinit var spinnerCategoria: Spinner
    private lateinit var campoPrecio: TextInputEditText
    private lateinit var campoDescripcion: TextInputEditText
    private lateinit var spinnerUnidadPrecio: Spinner

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

    private val lanzadorVoz = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { resultado ->
        if (resultado.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), "Productos guardados por voz", Toast.LENGTH_SHORT).show()
        }
    }

    private val lanzadorArchivoMultiproposito = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            procesarArchivo(uri)
        }
    }

    private val tiposMimeArchivos = arrayOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "text/csv",
        "text/comma-separated-values",
        "application/vnd.oasis.opendocument.spreadsheet",
        "text/plain",
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        campoNombre = view.findViewById(R.id.campo_nombre_producto)
        spinnerCategoria = view.findViewById(R.id.spinner_categoria)
        campoPrecio = view.findViewById(R.id.campo_precio_producto)
        campoDescripcion = view.findViewById(R.id.campo_descripcion_producto)
        spinnerUnidadPrecio = view.findViewById(R.id.spinner_unidad_precio)
        val botonGuardar = view.findViewById<MaterialButton>(R.id.boton_guardar_producto)
        val botonMenuAgregar = view.findViewById<MaterialButton>(R.id.boton_menu_agregar)
        val textoLimiteProductos = view.findViewById<TextView>(R.id.texto_limite_productos)

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
                        Toast.makeText(
                            requireContext(),
                            "Abriendo escáner de boleta...",
                            Toast.LENGTH_SHORT,
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
            estadoSuscripcion = planManager.cargarEstadoActual(requireContext())
            actualizarContadorProductos(textoLimiteProductos)
        }
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            estadoSuscripcion = planManager.cargarEstadoActual(requireContext())
            val textoLimiteProductos = view?.findViewById<TextView>(R.id.texto_limite_productos)
            if (textoLimiteProductos != null) {
                actualizarContadorProductos(textoLimiteProductos)
            }
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

        viewLifecycleOwner.lifecycleScope.launch {
            val estado = estadoSuscripcion ?: planManager.cargarEstadoActual(requireContext()).also { estadoSuscripcion = it }
            when (val resultado = planManager.validarGuardarProducto(estado)) {
                is ResultadoValidacion.Bloqueado -> {
                    mostrarDialogoLimite(resultado)
                    return@launch
                }
                is ResultadoValidacion.Permitido -> Unit
            }

            val repositorio = ProductoRepository()
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
            } else {
                mostrarMensaje(resultado.mensaje ?: "No se pudo guardar el producto")
            }
        }
    }

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

    private fun limpiarFormulario() {
        campoNombre.setText("")
        campoPrecio.setText("")
        campoDescripcion.setText("")
        spinnerCategoria.setSelection(0)
        spinnerUnidadPrecio.setSelection(0)
    }

    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(requireContext(), mensaje, Toast.LENGTH_SHORT).show()
    }

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

    private fun obtenerUnidadPrecio(texto: String): String {
        return if (texto.contains("kilo", ignoreCase = true)) "kilo" else "unidad"
    }
}
