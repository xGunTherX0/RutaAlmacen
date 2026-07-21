package com.example.rutaalmacen.entrada.voz

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.rutaalmacen.databinding.ActivityVozAsistenteBinding
import com.example.rutaalmacen.productos.ProductoRepository
import kotlinx.coroutines.launch

/**
 * Actividad principal del asistente de entrada por voz.
 *
 * Permite al usuario dictar productos mediante reconocimiento de voz en tiempo real,
 * visualizar los productos detectados en un listado editable y guardarlos en el
 * repositorio local. Implementa [SpeechRecognizerHelper.Listener] para recibir
 * los eventos del motor de reconocimiento de voz.
 */
class VozAsistenteActivity : AppCompatActivity(), SpeechRecognizerHelper.Listener {

    private lateinit var binding: ActivityVozAsistenteBinding
    private val viewModel: VozAsistenteViewModel by viewModels()
    private val repositorio = ProductoRepository()
    private lateinit var adaptador: ProductoVozAdapter
    private var speechHelper: SpeechRecognizerHelper? = null
    private var guardando: Boolean = false
    private var ultimoTextoValido: String = ""
    private var detenerPorUsuario: Boolean = false

    private val permisoMicrofono = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido ->
        if (concedido) {
            Toast.makeText(this, "Toca el micrófono para dictar", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Se necesita permiso de micrófono", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * {@inheritDoc}
     *
     * Inicializa el enlace de vistas, configura el listado de productos, los botones
     * de la interfaz y los observadores del modelo de vista. Solicita el permiso
     * de micrófono si no ha sido concedido previamente.
     *
     * @param savedInstanceState Estado guardado de la instancia anterior, o `null` si es nueva.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVozAsistenteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbarVoz.setNavigationOnClickListener { finish() }

        configurarRecycler()
        configurarBotones()
        configurarObservadores()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permisoMicrofono.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            Toast.makeText(this, "Toca el 🎤 para empezar a dictar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun configurarRecycler() {
        adaptador = ProductoVozAdapter(
            onEliminar = { producto ->
                viewModel.eliminarProducto(producto.id)
                actualizarVisibilidadPanel()
            },
        )
        binding.recyclerProductosVoz.layoutManager = LinearLayoutManager(this)
        binding.recyclerProductosVoz.adapter = adaptador
    }

    private fun configurarBotones() {
        binding.botonMicrofono.setOnClickListener { onMicrofonoClick() }
        binding.botonGuardarTodos.setOnClickListener { guardarTodos() }
        binding.botonCancelar.setOnClickListener { finish() }
        binding.botonAgregarTexto.setOnClickListener { agregarTextoManual() }
        binding.campoTextoManual.setOnEditorActionListener { _, _, _ ->
            agregarTextoManual()
            true
        }
    }

    private fun configurarObservadores() {
        viewModel.grabando.observe(this) { grabando ->
            if (grabando) {
                binding.botonMicrofono.text = "⏹ Detener"
                binding.botonMicrofono.isEnabled = true
                binding.progresoVoz.visibility = View.VISIBLE
            } else {
                binding.botonMicrofono.text = "🎤 Dictar"
                binding.botonMicrofono.isEnabled = true
                binding.progresoVoz.visibility = View.GONE
            }
        }

        viewModel.texto.observe(this) { texto ->
            if (texto.isNotBlank()) ultimoTextoValido = texto
            binding.textoEscuchado.text = if (texto.isBlank()) "Toca el 🎤 para empezar" else texto
        }

        viewModel.productos.observe(this) { productos ->
            adaptador.actualizar(productos)
            actualizarVisibilidadPanel()
        }
    }

    private fun actualizarVisibilidadPanel() {
        val productos = viewModel.obtenerProductos()
        if (productos.isEmpty()) {
            binding.panelResultado.visibility = View.GONE
        } else {
            binding.panelResultado.visibility = View.VISIBLE
            binding.textoContadorProductos.text = "${productos.size} producto(s) detectado(s)"
        }
    }

    private fun onMicrofonoClick() {
        if (guardando) return

        val grabando = viewModel.grabando.value == true
        if (grabando) {
            detenerPorUsuario = true
            detenerEscucha()
            procesarSiHayTexto()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                permisoMicrofono.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            iniciarEscucha()
        }
    }

    private fun iniciarEscucha() {
        // Inicialización perezosa: solo crea el reconocedor la primera vez que se necesita
        if (speechHelper == null) {
            speechHelper = SpeechRecognizerHelper(this, this)
        }
        if (!speechHelper!!.isDisponible) {
            Toast.makeText(this, "Reconocimiento de voz no disponible en este dispositivo", Toast.LENGTH_LONG).show()
            return
        }
        detenerPorUsuario = false
        ultimoTextoValido = ""
        viewModel.actualizarTexto("")
        viewModel.setGrabando(true)
        binding.textoEscuchado.text = "Escuchando... habla ahora"
        try {
            speechHelper?.iniciar()
        } catch (e: Exception) {
            viewModel.setGrabando(false)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun detenerEscucha() {
        speechHelper?.detener()
    }

    private fun procesarSiHayTexto() {
        val texto = ultimoTextoValido.trim()
        if (texto.isBlank()) {
            viewModel.actualizarTexto("No se reconoció voz. Intenta de nuevo.")
            return
        }
        viewModel.actualizarTexto(texto)
        viewModel.procesar(texto)
        ultimoTextoValido = ""
    }

    /**
     * {@inheritDoc}
     *
     * Actualiza la interfaz para indicar que el reconocimiento de voz ha comenzado.
     */
    override fun onInicio() {
        runOnUiThread {
            binding.textoEscuchado.text = "Escuchando... habla ahora"
        }
    }

    /**
     * {@inheritDoc}
     *
     * Actualiza el texto parcial mostrado en pantalla y lo almacena como último texto válido.
     *
     * @param texto Fragmento de texto reconocido parcialmente por el motor de voz.
     */
    override fun onParcial(texto: String) {
        runOnUiThread {
            if (texto.isNotBlank()) {
                ultimoTextoValido = texto
                viewModel.actualizarTexto(texto)
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * Detiene el estado de grabación y procesa el texto final dictado por el usuario.
     *
     * @param texto Texto completo reconocido al finalizar el dictado.
     */
    override fun onFinal(texto: String) {
        runOnUiThread {
            if (texto.isNotBlank()) ultimoTextoValido = texto
            viewModel.setGrabando(false)
            procesarSiHayTexto()
        }
    }

    /**
     * {@inheritDoc}
     *
     * Maneja los errores del reconocimiento de voz. Si el usuario no detuvo
     * manualmente y hay texto válido, lo procesa; de lo contrario, muestra el error.
     *
     * @param mensaje Descripción del error ocurrido durante el reconocimiento.
     */
    override fun onError(mensaje: String) {
        runOnUiThread {
            viewModel.setGrabando(false)
            // Si el usuario ya detuvo manualmente, no mostrar error
            if (!detenerPorUsuario && ultimoTextoValido.isNotBlank()) {
                procesarSiHayTexto()
            } else if (!detenerPorUsuario) {
                viewModel.actualizarTexto("Error: $mensaje")
                Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun agregarTextoManual() {
        val texto = binding.campoTextoManual.text?.toString()?.trim().orEmpty()
        if (texto.isBlank()) {
            Toast.makeText(this, "Escribe primero el producto", Toast.LENGTH_SHORT).show()
            return
        }
        binding.campoTextoManual.setText("")
        viewModel.procesar(texto)
    }

    private fun guardarTodos() {
        if (guardando) return
        val productos = adaptador.obtenerProductos()
        if (productos.isEmpty()) {
            Toast.makeText(this, "No hay productos para guardar. Toca 🎤 y dicta.", Toast.LENGTH_SHORT).show()
            return
        }

        // Validación en tres pasos: lista vacía, precios faltantes y nombres faltantes
        val sinPrecio = productos.filter { it.precio <= 0 }
        if (sinPrecio.isNotEmpty()) {
            val nombres = sinPrecio.joinToString(", ") { it.nombre }
            Toast.makeText(
                this,
                "Edita el precio de: $nombres (toca ▶ para expandir)",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val sinNombre = productos.filter { it.nombre.isBlank() }
        if (sinNombre.isNotEmpty()) {
            Toast.makeText(this, "Todos los productos deben tener nombre", Toast.LENGTH_SHORT).show()
            return
        }

        guardando = true
        binding.botonGuardarTodos.isEnabled = false
        binding.progresoVoz.visibility = View.VISIBLE
        binding.textoContadorProductos.text = "Guardando ${productos.size} producto(s)..."

        lifecycleScope.launch {
            var exitosos = 0
            var fallidos = 0
            // Guarda secuencialmente para evitar saturar el repositorio concurrentemente
            for (producto in productos) {
                val resultado = repositorio.guardar(
                    nombre = producto.nombre,
                    categoria = producto.categoria,
                    precio = producto.precio,
                    unidadPrecio = producto.tipoPrecio,
                )
                if (resultado.exitoso) exitosos++ else fallidos++
            }
            guardando = false
            binding.botonGuardarTodos.isEnabled = true
            binding.progresoVoz.visibility = View.GONE

            val mensaje = when {
                fallidos == 0 -> "✓ $exitosos producto(s) guardado(s)"
                exitosos == 0 -> "No se pudo guardar ninguno"
                else -> "$exitosos guardados, $fallidos fallaron"
            }
            Toast.makeText(this@VozAsistenteActivity, mensaje, Toast.LENGTH_LONG).show()

            if (fallidos == 0) {
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * Libera los recursos del auxiliar de reconocimiento de voz al destruir la actividad.
     */
    override fun onDestroy() {
        super.onDestroy()
        speechHelper?.destruir()
        speechHelper = null
    }
}
