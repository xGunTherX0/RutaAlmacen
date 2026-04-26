package com.example.rutaalmacen

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class NombreAlmacenActivity : AppCompatActivity() {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val coleccionInventarioPublico = "InventarioPublico"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_nombre_almacen)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_nombre_almacen)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                barrasDelSistema.top,
                barrasDelSistema.right,
                barrasDelSistema.bottom,
            )
            insets
        }

        val campoNombre = findViewById<TextInputEditText>(R.id.campo_nombre_almacen)
        val botonGuardar = findViewById<MaterialButton>(R.id.boton_guardar_nombre_almacen)

        botonGuardar.setOnClickListener {
            guardarNombre(campoNombre.text?.toString().orEmpty())
        }

        lifecycleScope.launch { cargarNombreAlmacen(campoNombre) }
    }

    private fun guardarNombre(nombre: String) {
        val nombreAlmacen = nombre.trim()
        if (nombreAlmacen.isBlank()) {
            mostrarMensaje("Ingresa el nombre del almacén")
            return
        }

        val usuario = autenticacion.currentUser
        if (usuario == null) {
            mostrarMensaje("No hay un usuario activo")
            return
        }

        lifecycleScope.launch {
            try {
                val datos = mapOf("nombreAlmacen" to nombreAlmacen)
                baseDatos.collection("Usuarios")
                    .document(usuario.uid)
                    .set(datos, SetOptions.merge())
                    .await()

                mostrarMensaje("Nombre del almacén guardado")

                try {
                    actualizarInventarioPublico(usuario.uid, datos)
                } catch (excepcion: Exception) {
                    mostrarMensaje("Nombre guardado, pero no se pudo actualizar la lista pública")
                }
            } catch (excepcion: Exception) {
                mostrarMensaje("No se pudo guardar el nombre del almacén")
            }
        }
    }

    private suspend fun cargarNombreAlmacen(campoNombre: TextInputEditText) {
        val usuario = autenticacion.currentUser ?: return
        try {
            val documento = baseDatos.collection("Usuarios")
                .document(usuario.uid)
                .get()
                .await()
            val nombreAlmacen = documento.getString("nombreAlmacen")
            if (!nombreAlmacen.isNullOrBlank()) {
                campoNombre.setText(nombreAlmacen)
            }
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo cargar el nombre del almacén")
        }
    }

    private suspend fun actualizarInventarioPublico(uid: String, datos: Map<String, Any>) {
        val resultado = baseDatos.collection(coleccionInventarioPublico)
            .whereEqualTo("vendedorId", uid)
            .get()
            .await()
        if (resultado.isEmpty) {
            return
        }
        val lote = baseDatos.batch()
        resultado.documents.forEach { documento ->
            lote.set(documento.reference, datos, SetOptions.merge())
        }
        lote.commit().await()
    }

    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }
}
