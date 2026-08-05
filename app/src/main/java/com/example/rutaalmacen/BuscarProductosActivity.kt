package com.example.rutaalmacen

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

class BuscarProductosActivity : AppCompatActivity() {

    private var filtroAbiertoAhora = false
    private var filtroDistanciaActiva = false
    private var distanciaMaximaMetros: Double = 5000.0
    private var filtroOfertas = false

    private val solicitudPermisoAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) {
            iniciarReconocimientoVoz()
        } else {
            mostrarMensaje("Permiso de micrófono denegado")
        }
    }

    private val resultadoVoz = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        if (resultado.resultCode == RESULT_OK) {
            val datos = resultado.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!datos.isNullOrEmpty()) {
                val textoReconocido = datos[0]
                val campoBusqueda = findViewById<TextInputEditText>(R.id.campo_busqueda_producto)
                campoBusqueda.setText(textoReconocido)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_buscar_productos)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        val header = findViewById<View>(R.id.header_buscar)
        val paddingHeaderBase = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_buscar_productos)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                0,
                barrasDelSistema.right,
                barrasDelSistema.bottom,
            )
            header.setPadding(
                header.paddingLeft,
                paddingHeaderBase + barrasDelSistema.top,
                header.paddingRight,
                header.paddingBottom,
            )
            insets
        }

        val campoBusqueda = findViewById<TextInputEditText>(R.id.campo_busqueda_producto)
        val botonVolver = findViewById<MaterialButton>(R.id.boton_volver_home_buscar)
        val botonVoz = findViewById<MaterialButton>(R.id.boton_busqueda_voz)
        val botonBuscar = findViewById<MaterialButton>(R.id.boton_ejecutar_busqueda)

        botonVolver.setOnClickListener {
            val intent = Intent(this, CompradorActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
            finish()
        }

        botonVoz.setOnClickListener {
            verificarPermisoAudio()
        }

        botonBuscar.setOnClickListener {
            ejecutarBusqueda(campoBusqueda.text?.toString().orEmpty())
        }

        campoBusqueda.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                ejecutarBusqueda(campoBusqueda.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }

        configurarFiltros()

        val categoriaIntent = intent.getStringExtra("categoria")
        if (!categoriaIntent.isNullOrBlank()) {
            ejecutarBusquedaCategoria(categoriaIntent)
        }
    }

    private fun configurarFiltros() {
        val chipTodos = findViewById<MaterialButton>(R.id.chip_todos)
        val chipAbierto = findViewById<MaterialButton>(R.id.chip_abierto)
        val chipDistancia = findViewById<MaterialButton>(R.id.chip_distancia)
        val chipOfertas = findViewById<MaterialButton>(R.id.chip_ofertas)

        chipTodos.setOnClickListener {
            filtroAbiertoAhora = false
            filtroDistanciaActiva = false
            filtroOfertas = false
            actualizarEstadoChips(chipTodos, chipAbierto, chipDistancia, chipOfertas)
            ejecutarBusquedaTodos()
        }

        chipAbierto.setOnClickListener {
            filtroAbiertoAhora = !filtroAbiertoAhora
            if (filtroAbiertoAhora) {
                filtroDistanciaActiva = false
                filtroOfertas = false
            }
            actualizarEstadoChips(chipTodos, chipAbierto, chipDistancia, chipOfertas)
        }

        chipDistancia.setOnClickListener {
            mostrarDialogoDistancia()
        }

        chipOfertas.setOnClickListener {
            filtroOfertas = !filtroOfertas
            if (filtroOfertas) {
                filtroAbiertoAhora = false
                filtroDistanciaActiva = false
            }
            actualizarEstadoChips(chipTodos, chipAbierto, chipDistancia, chipOfertas)
        }
    }

    private fun actualizarEstadoChips(
        chipTodos: MaterialButton,
        chipAbierto: MaterialButton,
        chipDistancia: MaterialButton,
        chipOfertas: MaterialButton,
    ) {
        val colorPrimario = androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimary)

        chipTodos.setBackgroundColor(
            if (!filtroAbiertoAhora && !filtroDistanciaActiva && !filtroOfertas) colorPrimario else android.graphics.Color.TRANSPARENT
        )
        chipTodos.setTextColor(
            if (!filtroAbiertoAhora && !filtroDistanciaActiva && !filtroOfertas) android.graphics.Color.WHITE else colorPrimario
        )

        chipAbierto.setBackgroundColor(
            if (filtroAbiertoAhora) colorPrimario else android.graphics.Color.TRANSPARENT
        )
        chipAbierto.setTextColor(
            if (filtroAbiertoAhora) android.graphics.Color.WHITE else colorPrimario
        )

        val textoDistancia = if (filtroDistanciaActiva) "Hasta ${distanciaMaximaMetros.toInt()} m" else "Distancia"
        chipDistancia.text = textoDistancia
        chipDistancia.setBackgroundColor(
            if (filtroDistanciaActiva) colorPrimario else android.graphics.Color.TRANSPARENT
        )
        chipDistancia.setTextColor(
            if (filtroDistanciaActiva) android.graphics.Color.WHITE else colorPrimario
        )

        chipOfertas.setBackgroundColor(
            if (filtroOfertas) colorPrimario else android.graphics.Color.TRANSPARENT
        )
        chipOfertas.setTextColor(
            if (filtroOfertas) android.graphics.Color.WHITE else colorPrimario
        )
    }

    private fun mostrarDialogoDistancia() {
        val opciones = listOf("500 m", "1 km", "3 km", "5 km", "10 km", "Sin límite")
        val seleccionActual = when {
            distanciaMaximaMetros <= 500 -> 0
            distanciaMaximaMetros <= 1000 -> 1
            distanciaMaximaMetros <= 3000 -> 2
            distanciaMaximaMetros <= 5000 -> 3
            distanciaMaximaMetros <= 10000 -> 4
            else -> 5
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Seleccionar rango de distancia")
            .setSingleChoiceItems(opciones.toTypedArray(), seleccionActual) { dialogo, posicion ->
                distanciaMaximaMetros = when (posicion) {
                    0 -> 500.0
                    1 -> 1000.0
                    2 -> 3000.0
                    3 -> 5000.0
                    4 -> 10000.0
                    else -> 50000.0
                }
                filtroDistanciaActiva = true
                filtroAbiertoAhora = false
                filtroOfertas = false
                dialogo.dismiss()
                val chipTodos = findViewById<MaterialButton>(R.id.chip_todos)
                val chipAbierto = findViewById<MaterialButton>(R.id.chip_abierto)
                val chipDistancia = findViewById<MaterialButton>(R.id.chip_distancia)
                val chipOfertas = findViewById<MaterialButton>(R.id.chip_ofertas)
                actualizarEstadoChips(chipTodos, chipAbierto, chipDistancia, chipOfertas)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun ejecutarBusqueda(consulta: String) {
        val texto = consulta.trim()
        if (texto.isBlank()) {
            mostrarMensaje("Escribe un producto para buscar")
            return
        }

        val intent = Intent(this, ProductosActivity::class.java)
        intent.putExtra("consulta_inicial", texto)
        intent.putExtra("filtro_abierto", filtroAbiertoAhora)
        intent.putExtra("filtro_distancia", filtroDistanciaActiva)
        intent.putExtra("distancia_maxima", distanciaMaximaMetros)
        intent.putExtra("filtro_ofertas", filtroOfertas)
        startActivity(intent)
    }

    private fun ejecutarBusquedaCategoria(categoria: String) {
        val intent = Intent(this, ProductosActivity::class.java)
        intent.putExtra("categoria", categoria)
        intent.putExtra("filtro_abierto", filtroAbiertoAhora)
        intent.putExtra("filtro_distancia", filtroDistanciaActiva)
        intent.putExtra("distancia_maxima", distanciaMaximaMetros)
        intent.putExtra("filtro_ofertas", filtroOfertas)
        startActivity(intent)
    }

    private fun ejecutarBusquedaTodos() {
        val intent = Intent(this, ProductosActivity::class.java)
        intent.putExtra("filtro_abierto", filtroAbiertoAhora)
        intent.putExtra("filtro_distancia", filtroDistanciaActiva)
        intent.putExtra("distancia_maxima", distanciaMaximaMetros)
        intent.putExtra("filtro_ofertas", filtroOfertas)
        startActivity(intent)
    }

    private fun mostrarMensaje(mensaje: String) {
        android.widget.Toast.makeText(this, mensaje, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun verificarPermisoAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            iniciarReconocimientoVoz()
        } else {
            solicitudPermisoAudio.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun iniciarReconocimientoVoz() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Di el nombre del producto")
        }
        
        try {
            resultadoVoz.launch(intent)
        } catch (e: Exception) {
            mostrarMensaje("Reconocimiento de voz no disponible")
        }
    }
}
