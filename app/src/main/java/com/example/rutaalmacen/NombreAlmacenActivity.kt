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

/**
 * Actividad que permite al vendedor editar el nombre comercial de su almacén.
 * El nombre se guarda en Firestore y se propaga al inventario público.
 */
class NombreAlmacenActivity : AppCompatActivity() {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /**
     * Ciclo de vida: inicializa la interfaz, configura el botón de guardar
     * y carga el nombre actual del almacén desde Firestore.
     *
     * @param savedInstanceState Estado guardado de la instancia anterior, o null si es nueva.
     */
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

    /**
     * Valida y guarda el nombre del almacén en Firestore. Propaga los cambios
     * al inventario público. Muestra mensajes de error si la validación falla.
     *
     * @param nombre Nombre ingresado por el usuario en el campo de texto.
     */
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
                baseDatos.collection(Constantes.COLECCION_USUARIOS)
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

    /**
     * Carga el nombre actual del almacén desde Firestore y lo muestra en el campo de texto.
     *
     * @param campoNombre Campo de texto donde se mostrará el nombre cargado.
     */
    private suspend fun cargarNombreAlmacen(campoNombre: TextInputEditText) {
        val usuario = autenticacion.currentUser ?: return
        try {
            val documento = baseDatos.collection(Constantes.COLECCION_USUARIOS)
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

    /**
     * Propaga los datos del nombre a todos los documentos del inventario público
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
        if (resultado.isEmpty) {
            return
        }
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
