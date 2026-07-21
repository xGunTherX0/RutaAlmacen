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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Actividad que permite al vendedor seleccionar los métodos de pago aceptados en su almacén
 * (efectivo, débito, crédito, transferencia, otros). Los métodos seleccionados se guardan
 * en Firestore y se propagan al inventario público.
 */
class MetodosPagoActivity : AppCompatActivity() {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val metodosDisponibles = listOf(
        "Efectivo" to "chip_efectivo",
        "Débito" to "chip_debito",
        "Crédito" to "chip_credito",
        "Transferencia" to "chip_transferencia",
        "Otros" to "chip_otros",
    )

    /**
     * Ciclo de vida: inicializa la interfaz, configura el botón de guardar
     * y carga los métodos de pago actuales desde Firestore.
     *
     * @param savedInstanceState Estado guardado de la instancia anterior, o null si es nueva.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_metodos_pago)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_metodos_pago)) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        findViewById<MaterialToolbar>(R.id.toolbar_metodos_pago).setNavigationOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.boton_guardar_metodos).setOnClickListener { guardar() }

        lifecycleScope.launch { cargar() }
    }

    /**
     * Carga los métodos de pago guardados del vendedor desde Firestore
     * y marca los chips correspondientes en la interfaz.
     */
    private suspend fun cargar() {
        val usuario = autenticacion.currentUser ?: return
        try {
            val documento = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(usuario.uid)
                .get()
                .await()
            @Suppress("UNCHECKED_CAST")
            val guardados = documento.get("metodosPago") as? List<String> ?: emptyList()
            val grupo = findViewById<ChipGroup>(R.id.grupo_metodos_pago)
            for (indice in grupo.childCount - 1 downTo 0) {
                val chip = grupo.getChildAt(indice) as? Chip
                chip?.isChecked = (chip?.text?.toString() in guardados)
            }
        } catch (_: Exception) {
            mostrarMensaje("No se pudieron cargar los métodos de pago")
        }
    }

    /**
     * Guarda los métodos de pago seleccionados en Firestore y los propaga al inventario público.
     * Valida que al menos un método esté seleccionado antes de guardar.
     * Cierra la actividad al completar la operación exitosamente.
     */
    private fun guardar() {
        val usuario = autenticacion.currentUser
        if (usuario == null) {
            mostrarMensaje("No hay un usuario activo")
            return
        }
        val grupo = findViewById<ChipGroup>(R.id.grupo_metodos_pago)
        val seleccionados = (0 until grupo.childCount)
            .mapNotNull { i -> grupo.getChildAt(i) as? Chip }
            .filter { it.isChecked }
            .map { it.text.toString() }

        if (seleccionados.isEmpty()) {
            mostrarMensaje("Selecciona al menos un método de pago")
            return
        }

        lifecycleScope.launch {
            try {
                val datos = mapOf("metodosPago" to seleccionados)
                baseDatos.collection(Constantes.COLECCION_USUARIOS)
                    .document(usuario.uid)
                    .set(datos, SetOptions.merge())
                    .await()
                try {
                    actualizarInventarioPublico(usuario.uid, datos)
                } catch (_: Exception) {
                    // Continuar
                }
                mostrarMensaje("Métodos de pago guardados")
                finish()
            } catch (_: Exception) {
                mostrarMensaje("No se pudieron guardar los métodos de pago")
            }
        }
    }

    /**
     * Propaga los datos de métodos de pago a todos los documentos del inventario público
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
