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
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Actividad que permite al vendedor indicar si su almacén acepta Caja Vecina
 * y establecer el saldo disponible para los clientes.
 * El estado se guarda en Firestore y se propaga al inventario público.
 */
class CajaVecinaActivity : AppCompatActivity() {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private lateinit var switchTieneCaja: MaterialSwitch
    private lateinit var campoSaldo: TextInputEditText

    /**
     * Ciclo de vida: inicializa la interfaz, configura el switch de Caja Vecina
     * y carga el estado actual desde Firestore.
     *
     * @param savedInstanceState Estado guardado de la instancia anterior, o null si es nueva.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_caja_vecina)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_caja_vecina)) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        findViewById<MaterialToolbar>(R.id.toolbar_caja_vecina).setNavigationOnClickListener { finish() }
        switchTieneCaja = findViewById(R.id.switch_tiene_caja_vecina)
        campoSaldo = findViewById(R.id.campo_saldo_caja_vecina)
        findViewById<MaterialButton>(R.id.boton_guardar_caja_vecina).setOnClickListener { guardar() }

        lifecycleScope.launch { cargar() }
    }

    /**
     * Carga el estado actual de Caja Vecina del vendedor desde Firestore
     * y actualiza el switch y el campo de saldo en la interfaz.
     */
    private suspend fun cargar() {
        val usuario = autenticacion.currentUser ?: return
        try {
            val documento = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(usuario.uid)
                .get()
                .await()
            switchTieneCaja.isChecked = documento.getBoolean("tieneCajaVecina") ?: false
            val saldo = documento.getDouble("saldoCajaVecina") ?: 0.0
            if (saldo > 0) {
                campoSaldo.setText(saldo.toInt().toString())
            }
        } catch (_: Exception) {
            mostrarMensaje("No se pudo cargar el estado de Caja Vecina")
        }
    }

    /**
     * Guarda el estado de Caja Vecina y el saldo en Firestore y los propaga al inventario público.
     * Cierra la actividad al completar la operación exitosamente.
     */
    private fun guardar() {
        val usuario = autenticacion.currentUser
        if (usuario == null) {
            mostrarMensaje("No hay un usuario activo")
            return
        }
        val tieneCaja = switchTieneCaja.isChecked
        val saldoTexto = campoSaldo.text?.toString()?.trim().orEmpty()
        val saldo = saldoTexto.toDoubleOrNull() ?: 0.0

        lifecycleScope.launch {
            try {
                val datos = mapOf(
                    "tieneCajaVecina" to tieneCaja,
                    "saldoCajaVecina" to saldo,
                )
                baseDatos.collection(Constantes.COLECCION_USUARIOS)
                    .document(usuario.uid)
                    .set(datos, SetOptions.merge())
                    .await()
                try {
                    actualizarInventarioPublico(usuario.uid, datos)
                } catch (_: Exception) {
                    // Continuar
                }
                mostrarMensaje("Caja Vecina guardada")
                finish()
            } catch (_: Exception) {
                mostrarMensaje("No se pudo guardar Caja Vecina")
            }
        }
    }

    /**
     * Propaga los datos de Caja Vecina a todos los documentos del inventario público
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
