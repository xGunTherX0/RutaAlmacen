package com.example.rutaalmacen.entrada.ocr

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rutaalmacen.R
import com.example.rutaalmacen.productos.ProductoRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

/**
 * Actividad de previsualización y edición de productos detectados por OCR.
 *
 * Presenta una lista editable de los productos identificados, permitiendo al usuario
 * modificar el nombre, la categoría y el precio de cada uno antes de confirmar
 * su guardado en el catálogo local y remoto (Firestore).
 *
 * Recibe los productos detectados y el texto OCR crudo mediante extras del Intent
 * (claves [EXTRA_PRODUCTOS_JSON] y [EXTRA_TEXTO_CRUDO]).
 */
class PrevisualizacionActivity : AppCompatActivity() {

    private val viewModel: OcrViewModel by viewModels()
    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val repositorio = ProductoRepository()

    /**
     * Objeto compañero que define las claves de los extras utilizados
     * para la comunicación entre actividades.
     */
    companion object {
        /** Clave del extra que transporta la lista de productos en formato JSON. */
        const val EXTRA_PRODUCTOS_JSON = "extra_productos_json"

        /** Clave del extra que transporta el texto OCR crudo para depuración. */
        const val EXTRA_TEXTO_CRUDO = "extra_texto_crudo"
    }

    private lateinit var adaptador: AdaptadorProductosEscaneados
    private lateinit var textoResumen: TextView
    private lateinit var textoSinProductos: TextView
    private lateinit var botonConfirmar: MaterialButton
    private lateinit var botonReintentar: MaterialButton
    private lateinit var recycler: RecyclerView

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

    /**
     * Ciclo de vida: deserializa los productos del Intent, configura la lista
     * adaptadora, el resumen y los botones de confirmar y reintentar.
     *
     * @param savedInstanceState Estado guardado de la instancia anterior, o `null` si es nueva.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_previsualizacion)

        if (savedInstanceState == null) {
            val productosJson = intent.getStringExtra(EXTRA_PRODUCTOS_JSON)
            val textoCrudo = intent.getStringExtra(EXTRA_TEXTO_CRUDO).orEmpty()
            if (!productosJson.isNullOrBlank()) {
                val tipo = object : com.google.gson.reflect.TypeToken<List<ProductoEscaneado>>() {}.type
                val productos = runCatching { com.google.gson.Gson().fromJson<List<ProductoEscaneado>>(productosJson, tipo) }
                    .getOrNull().orEmpty()
                viewModel.productosDetectados.value = productos
                viewModel.textoCrudoOcr.value = textoCrudo
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_previsualizacion)) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        findViewById<MaterialToolbar>(R.id.toolbar_previsualizacion).setNavigationOnClickListener { finish() }
        textoResumen = findViewById(R.id.texto_resumen_productos)
        textoSinProductos = findViewById(R.id.texto_sin_productos_ocr)
        botonConfirmar = findViewById(R.id.boton_confirmar_ocr)
        botonReintentar = findViewById(R.id.boton_reintentar_camara)
        recycler = findViewById(R.id.recycler_productos_escaneados)

        adaptador = AdaptadorProductosEscaneados(
            productosIniciales = emptyList(),
            categorias = categorias,
            onCambio = { producto -> viewModel.actualizarProducto(producto) },
            onEliminar = { producto -> viewModel.eliminarProducto(producto.id) },
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adaptador

        botonConfirmar.setOnClickListener { confirmarYGuardar() }
        botonReintentar.setOnClickListener {
            viewModel.limpiar()
            val intent = Intent(this, OcrActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            finish()
        }

        viewModel.productosDetectados.observe(this) { productos ->
            if (adaptador.itemCount == 0 && productos.isNotEmpty()) {
                adaptador.reemplazar(productos)
            }
            actualizarResumen(productos)
        }

        viewModel.estado.observe(this) { estado ->
            if (estado is OcrViewModel.EstadoOcr.Error) {
                Toast.makeText(this, estado.mensaje, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Actualiza el texto de resumen con la cantidad total de productos detectados
     * y, si aplica, la cantidad de aquellos que ya existen en el catálogo.
     *
     * También controla la visibilidad del mensaje «sin productos», la lista
     * y el estado habilitado del botón de confirmación.
     *
     * @param productos Lista actual de productos detectados.
     */
    private fun actualizarResumen(productos: List<ProductoEscaneado>) {
        val total = productos.size
        val duplicados = productos.count { it.existeEnCatalogo }
        val resumen = if (duplicados > 0) {
            "Detectados: $total (⚠ $duplicados ya en catálogo)"
        } else {
            "Productos detectados: $total"
        }
        textoResumen.text = resumen
        textoSinProductos.visibility = if (productos.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (productos.isEmpty()) View.GONE else View.VISIBLE
        botonConfirmar.isEnabled = productos.isNotEmpty()
    }

    /**
     * Valida los productos editados y los guarda en la caché local y en Firestore.
     *
     * Verifica que exista un usuario activo, que no haya productos sin nombre ni
     * sin precio, y procede a guardar cada producto mediante el [ProductoRepository].
     * Muestra un mensaje con el resultado final y finaliza la actividad al completar.
     */
    private fun confirmarYGuardar() {
        val usuario = autenticacion.currentUser
        if (usuario == null) {
            Toast.makeText(this, "No hay un usuario activo", Toast.LENGTH_SHORT).show()
            return
        }
        val lista = adaptador.obtener()
        if (lista.isEmpty()) {
            Toast.makeText(this, "No hay productos para guardar", Toast.LENGTH_SHORT).show()
            return
        }
        val sinNombre = lista.filter { it.nombre.isBlank() }
        if (sinNombre.isNotEmpty()) {
            Toast.makeText(this, "Hay ${sinNombre.size} producto(s) sin nombre. Toca 🗑 para eliminarlos.", Toast.LENGTH_LONG).show()
            return
        }
        val sinPrecio = lista.filter { it.precio <= 0.0 }
        if (sinPrecio.isNotEmpty()) {
            Toast.makeText(this, "Escribe el precio de ${sinPrecio.size} producto(s).", Toast.LENGTH_LONG).show()
            return
        }

        botonConfirmar.isEnabled = false
        lifecycleScope.launch {
            try {
                val localGuardados = viewModel.guardarEnCacheLocal(usuario.uid, lista)
                var remotosExitosos = 0
                for (producto in lista) {
                    if (producto.nombre.isBlank() || producto.precio <= 0.0) continue
                    val resultado = repositorio.guardar(
                        nombre = producto.nombre,
                        categoria = producto.categoria,
                        precio = producto.precio,
                        unidadPrecio = "unidad",
                    )
                    if (resultado.exitoso) remotosExitosos++
                }
                val mensaje = if (remotosExitosos == lista.size) {
                    "✓ $remotosExitosos producto(s) guardado(s)"
                } else {
                    "$remotosExitosos guardados, ${lista.size - remotosExitosos} fallaron"
                }
                Toast.makeText(this@PrevisualizacionActivity, mensaje, Toast.LENGTH_LONG).show()
                viewModel.limpiar()
                finish()
            } catch (excepcion: Exception) {
                botonConfirmar.isEnabled = true
                Toast.makeText(this@PrevisualizacionActivity, "No se pudo guardar: ${excepcion.message ?: "?"}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
