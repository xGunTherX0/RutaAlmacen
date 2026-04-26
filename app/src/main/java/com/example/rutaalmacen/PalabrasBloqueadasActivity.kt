package com.example.rutaalmacen

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class PalabrasBloqueadasActivity : AppCompatActivity() {

    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val palabrasBase: MutableList<PalabraBloqueada> = mutableListOf()
    private val palabrasFiltradas: MutableList<PalabraBloqueada> = mutableListOf()
    private lateinit var adaptador: AdaptadorPalabras
    private lateinit var textoSinPalabras: android.widget.TextView
    private var textoBusqueda = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_palabras_bloqueadas)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_palabras_bloqueadas)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                barrasDelSistema.top,
                barrasDelSistema.right,
                barrasDelSistema.bottom,
            )
            insets
        }

        val campoPalabra = findViewById<TextInputEditText>(R.id.campo_palabra_bloqueada)
        val campoBusqueda = findViewById<TextInputEditText>(R.id.campo_busqueda_palabras)
        val botonAgregar = findViewById<com.google.android.material.button.MaterialButton>(R.id.boton_agregar_palabra)
        val recycler = findViewById<RecyclerView>(R.id.recycler_palabras_bloqueadas)
        textoSinPalabras = findViewById(R.id.texto_sin_palabras)

        adaptador = AdaptadorPalabras(
            palabras = palabrasFiltradas,
            onEliminar = { palabra -> confirmarEliminar(palabra) },
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adaptador

        campoBusqueda.doOnTextChanged { texto, _, _, _ ->
            textoBusqueda = texto?.toString().orEmpty()
            aplicarFiltros()
        }

        botonAgregar.setOnClickListener {
            val texto = campoPalabra.text?.toString().orEmpty()
            lifecycleScope.launch {
                val resultado = agregarPalabra(texto)
                if (resultado) {
                    campoPalabra.setText("")
                }
            }
        }

        lifecycleScope.launch { cargarPalabras() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { cargarPalabras() }
    }

    private suspend fun cargarPalabras() {
        try {
            val documentos = baseDatos.collection(COLECCION_PALABRAS)
                .orderBy("palabraNormalizada", Query.Direction.ASCENDING)
                .get()
                .await()
                .documents
            val nuevas = documentos.mapNotNull { documento ->
                val palabra = documento.getString("palabra")
                    ?: documento.getString("texto")
                    ?: ""
                val palabraNormalizada = documento.getString("textoNormalizado")
                    ?: documento.getString("palabraNormalizada")
                    ?: FiltroContenido.normalizarParaFiltro(palabra)
                if (palabraNormalizada.isBlank()) return@mapNotNull null
                PalabraBloqueada(
                    id = documento.id,
                    palabra = palabra.ifBlank { palabraNormalizada },
                    palabraNormalizada = palabraNormalizada,
                )
            }
            palabrasBase.clear()
            palabrasBase.addAll(nuevas)
            aplicarFiltros()
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudieron cargar los bloqueos")
        }
    }

    private fun aplicarFiltros() {
        val texto = FiltroContenido.normalizarParaFiltro(textoBusqueda)
        val filtrados = palabrasBase.filter { palabra ->
            texto.isBlank() || palabra.palabraNormalizada.contains(texto)
        }
        palabrasFiltradas.clear()
        palabrasFiltradas.addAll(filtrados)
        adaptador.notifyDataSetChanged()
        textoSinPalabras.visibility = if (palabrasFiltradas.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private suspend fun agregarPalabra(texto: String): Boolean {
        val validacion = FiltroContenido.validarPalabraBloqueada(texto)
        if (!validacion.esValido) {
            mostrarMensaje(validacion.mensaje)
            return false
        }
        val normalizado = FiltroContenido.normalizarParaFiltro(texto)
        if (palabrasBase.any { it.palabraNormalizada == normalizado }) {
            mostrarMensaje("La palabra o frase ya está bloqueada")
            return false
        }
        return try {
            val esFrase = normalizado.contains(" ")
            val datos = mapOf(
                "palabra" to texto.trim(),
                "palabraNormalizada" to normalizado,
                "texto" to texto.trim(),
                "textoNormalizado" to normalizado,
                "tipo" to if (esFrase) "frase" else "palabra",
                "fechaCreacion" to FieldValue.serverTimestamp(),
            )
            baseDatos.collection(COLECCION_PALABRAS)
                .document(normalizado)
                .set(datos)
                .await()
            true
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo guardar el bloqueo")
            false
        }
    }

    private fun confirmarEliminar(palabra: PalabraBloqueada) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminar bloqueo")
            .setMessage("¿Deseas eliminar '${palabra.palabra}'?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch { eliminarPalabra(palabra) }
            }
            .show()
    }

    private suspend fun eliminarPalabra(palabra: PalabraBloqueada) {
        try {
            baseDatos.collection(COLECCION_PALABRAS)
                .document(palabra.palabraNormalizada)
                .delete()
                .await()
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo eliminar el bloqueo")
        }
    }

    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }

    data class PalabraBloqueada(
        val id: String,
        val palabra: String,
        val palabraNormalizada: String,
    )

    companion object {
        private const val COLECCION_PALABRAS = "Palabras_Bloqueadas"
    }
}

private class AdaptadorPalabras(
    private val palabras: List<PalabrasBloqueadasActivity.PalabraBloqueada>,
    private val onEliminar: (PalabrasBloqueadasActivity.PalabraBloqueada) -> Unit,
) : RecyclerView.Adapter<AdaptadorPalabras.VistaPalabra>() {

    class VistaPalabra(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val textoPalabra: android.widget.TextView = itemView.findViewById(R.id.texto_palabra_bloqueada)
        val botonEliminar: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.boton_eliminar_palabra)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaPalabra {
        val vista = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_palabra_bloqueada, parent, false)
        return VistaPalabra(vista)
    }

    override fun onBindViewHolder(holder: VistaPalabra, position: Int) {
        val palabra = palabras[position]
        holder.textoPalabra.text = palabra.palabra
        holder.botonEliminar.setOnClickListener { onEliminar(palabra) }
    }

    override fun getItemCount(): Int = palabras.size
}
