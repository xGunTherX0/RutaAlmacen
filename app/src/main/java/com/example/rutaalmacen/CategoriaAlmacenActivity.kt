package com.example.rutaalmacen

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CategoriaAlmacenActivity : AppCompatActivity() {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val coleccionInventarioPublico = "InventarioPublico"

    private val categorias = listOf(
        "Almacén",
        "Verdulería",
        "Panadería",
        "Botillería",
        "Carnicería",
        "Bazar",
        "Pescadería",
        "Ferretería",
        "Otro",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_categoria_almacen)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_categoria_almacen)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                barrasDelSistema.top,
                barrasDelSistema.right,
                barrasDelSistema.bottom,
            )
            insets
        }

        val campoCategoria = findViewById<AutoCompleteTextView>(R.id.campo_categoria_almacen)
        val botonGuardar = findViewById<MaterialButton>(R.id.boton_guardar_categoria)

        configurarCategorias(campoCategoria)
        botonGuardar.setOnClickListener {
            guardarCategoria(campoCategoria.text?.toString().orEmpty())
        }

        lifecycleScope.launch { cargarCategoria(campoCategoria) }
    }

    private fun configurarCategorias(campoCategoria: AutoCompleteTextView) {
        val adaptadorCategorias = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            categorias,
        )
        campoCategoria.setAdapter(adaptadorCategorias)
        campoCategoria.setOnItemClickListener { _, _, _, _ -> }
    }

    private fun guardarCategoria(categoriaTexto: String) {
        val categoria = categoriaTexto.trim()
        if (categoria.isBlank()) {
            mostrarMensaje("Selecciona una categoría")
            return
        }
        if (!categorias.contains(categoria)) {
            mostrarMensaje("Selecciona una categoría válida")
            return
        }

        val usuario = autenticacion.currentUser
        if (usuario == null) {
            mostrarMensaje("No hay un usuario activo")
            return
        }

        lifecycleScope.launch {
            try {
                val datos = mapOf("categoriaAlmacen" to categoria)
                baseDatos.collection("Usuarios")
                    .document(usuario.uid)
                    .set(datos, SetOptions.merge())
                    .await()

                mostrarMensaje("Categoría guardada")

                try {
                    actualizarInventarioPublico(usuario.uid, datos)
                } catch (excepcion: Exception) {
                    mostrarMensaje("Categoría guardada, pero no se pudo actualizar la lista pública")
                }
            } catch (excepcion: Exception) {
                mostrarMensaje("No se pudo guardar la categoría")
            }
        }
    }

    private suspend fun cargarCategoria(campoCategoria: AutoCompleteTextView) {
        val usuario = autenticacion.currentUser ?: return
        try {
            val documento = baseDatos.collection("Usuarios")
                .document(usuario.uid)
                .get()
                .await()
            val categoria = documento.getString("categoriaAlmacen")
            if (!categoria.isNullOrBlank() && categorias.contains(categoria)) {
                campoCategoria.setText(categoria, false)
            }
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo cargar la categoría")
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
