package com.example.rutaalmacen

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Actividad que permite al vendedor marcar manualmente su almacén como cerrado,
 * independientemente del horario de atención configurado. El estado se sincroniza
 * con Firestore y se propaga al inventario público.
 */
class EstadoAlmacenActivity : AppCompatActivity() {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private lateinit var switchCerradoManual: MaterialSwitch
    private lateinit var textoDetalleEstado: android.widget.TextView
    private lateinit var textoResumenEstado: android.widget.TextView

    /**
     * Ciclo de vida: inicializa la interfaz, configura el switch de cierre manual
     * y carga el estado actual del almacén desde Firestore.
     *
     * @param savedInstanceState Estado guardado de la instancia anterior, o null si es nueva.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_estado_almacen)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_estado_almacen)) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        findViewById<MaterialToolbar>(R.id.toolbar_estado_almacen).setNavigationOnClickListener { finish() }
        switchCerradoManual = findViewById(R.id.switch_cerrado_manual)
        textoDetalleEstado = findViewById(R.id.texto_detalle_estado)
        textoResumenEstado = findViewById(R.id.texto_resumen_estado)

        findViewById<MaterialButton>(R.id.boton_guardar_estado).setOnClickListener { guardar() }

        actualizarResumen(false)
        lifecycleScope.launch { cargar() }
    }

    /**
     * Carga el estado de cierre manual y el horario de atención del vendedor
     * desde Firestore y actualiza la interfaz.
     */
    private suspend fun cargar() {
        val usuario = autenticacion.currentUser ?: return
        try {
            val documento = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(usuario.uid)
                .get()
                .await()
            val cerradoManual = documento.getBoolean("cerradoManual") ?: false
            val horarioTexto = documento.getString("horarioAtencion").orEmpty()
            switchCerradoManual.isChecked = cerradoManual
            actualizarResumen(cerradoManual, horarioTexto)
        } catch (_: Exception) {
            mostrarMensaje("No se pudo cargar el estado")
        }
    }

    /**
     * Guarda el estado de cierre manual en Firestore y lo propaga al inventario público.
     * Cierra la actividad al completar la operación exitosamente.
     */
    private fun guardar() {
        val usuario = autenticacion.currentUser
        if (usuario == null) {
            mostrarMensaje("No hay un usuario activo")
            return
        }
        val cerradoManual = switchCerradoManual.isChecked
        lifecycleScope.launch {
            try {
                val datos = mapOf("cerradoManual" to cerradoManual)
                baseDatos.collection(Constantes.COLECCION_USUARIOS)
                    .document(usuario.uid)
                    .set(datos, SetOptions.merge())
                    .await()
                try {
                    actualizarInventarioPublico(usuario.uid, datos)
                } catch (_: Exception) {
                    // Continuar
                }
                mostrarMensaje("Estado guardado")
                actualizarResumen(cerradoManual)
                finish()
            } catch (_: Exception) {
                mostrarMensaje("No se pudo guardar el estado")
            }
        }
    }

    /**
     * Actualiza el texto de resumen y detalle del estado del almacén en la interfaz.
     *
     * @param cerradoManual Indica si el almacén está marcado como cerrado manualmente.
     * @param horarioTexto Texto del horario de atención actual, vacío si no está definido.
     */
    private fun actualizarResumen(cerradoManual: Boolean, horarioTexto: String = "") {
        val estado = if (cerradoManual) {
            "Marcado como CERRADO manualmente"
        } else {
            "Sigue el horario del almacén"
        }
        textoDetalleEstado.text = if (cerradoManual) {
            "El almacén aparecerá cerrado aunque el horario diga abierto. Desactívalo para volver al horario automático."
        } else {
            "Si lo activas, el almacén aparecerá cerrado aunque el horario diga abierto."
        }
        val horarioInfo = if (horarioTexto.isNotBlank()) "Horario actual: $horarioTexto" else ""
        textoResumenEstado.text = listOf(estado, horarioInfo).filter { it.isNotBlank() }.joinToString("\n")
    }

    /**
     * Propaga los datos de estado a todos los documentos del inventario público
     * del vendedor, utilizando una escritura por lotes de Firestore.
     *
     * @param uid Identificador único del vendedor.
     * @param datos Mapa de datos a actualizar en cada documento.
     */
    private suspend fun actualizarInventarioPublico(uid: String, datos: Map<String, Any>) {
        val resultado = baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
            .whereEqualTo("vendedorId", uid)
            .get()
            .await()
        if (resultado.isEmpty) return
        val lote = baseDatos.batch()
        resultado.documents.forEach { documento ->
            lote.set(documento.reference, datos, SetOptions.merge())
        }
        lote.commit().await()
    }

    /**
     * Muestra un mensaje breve en pantalla mediante un Toast.
     *
     * @param mensaje Texto a mostrar al usuario.
     */
    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }
}
