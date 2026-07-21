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

/**
 * Actividad que permite gestionar las palabras y frases bloqueadas por el sistema de filtrado
 * de contenido. Ofrece funcionalidad para agregar nuevas palabras, eliminar existentes y
 * buscar dentro del listado cargado desde Firestore.
 *
 * <p>La actividad mantiene dos listas internas: una con todas las palabras cargadas (base)
 * y otra filtrada según el texto de búsqueda ingresado por el usuario.</p>
 */
class PalabrasBloqueadasActivity : AppCompatActivity() {

    /** Instancia de Firestore utilizada para las operaciones de lectura y escritura. */
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /** Lista mutable con todas las palabras bloqueadas cargadas desde la base de datos. */
    private val palabrasBase: MutableList<PalabraBloqueada> = mutableListOf()

    /** Lista mutable con las palabras filtradas según el criterio de búsqueda actual. */
    private val palabrasFiltradas: MutableList<PalabraBloqueada> = mutableListOf()

    /** Adaptador del [RecyclerView] que muestra las palabras filtradas en pantalla. */
    private lateinit var adaptador: AdaptadorPalabras

    /** Texto informativo que se muestra cuando no hay palabras disponibles. */
    private lateinit var textoSinPalabras: android.widget.TextView

    /** Texto actual utilizado como filtro de búsqueda sobre las palabras cargadas. */
    private var textoBusqueda = ""

    /**
     * Método del ciclo de vida llamado al crear la actividad.
     *
     * <p>Configura el diseño de borde a borde, inicializa los componentes de la interfaz
     * (campo de entrada, campo de búsqueda, botón de agregar, [RecyclerView]), establece
     * los listeners correspondientes y carga las palabras bloqueadas desde Firestore.</p>
     *
     * @param savedInstanceState Estado previamente guardado de la instancia, o {@code null}
     *                           si es la primera vez que se crea.
     */
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

    /**
     * Método del ciclo de vida llamado cuando la actividad vuelve a primer plano.
     * Recarga las palabras bloqueadas desde Firestore para reflejar cambios recientes.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { cargarPalabras() }
    }

    /**
     * Carga todas las palabras bloqueadas desde la colección de Firestore,
     * ordenadas alfabéticamente por su forma normalizada.
     *
     * <p>Actualiza la lista base y reaplica los filtros de búsqueda vigentes.
     * En caso de error, muestra un mensaje informativo al usuario.</p>
     */
    private suspend fun cargarPalabras() {
        try {
            val documentos = baseDatos.collection(Constantes.COLECCION_PALABRAS_BLOQUEADAS)
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

    /**
     * Aplica el filtro de búsqueda actual sobre la lista base de palabras.
     *
     * <p>Normaliza el texto de búsqueda y filtra las palabras cuya forma normalizada
     * contenga dicho texto. Si el campo de búsqueda está vacío, se muestran todas las
     * palabras. Actualiza el adaptador y la visibilidad del texto informativo.</p>
     */
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

    /**
     * Agrega una nueva palabra o frase bloqueada a Firestore.
     *
     * <p>Valida el texto de entrada, verifica que no exista duplicado en la lista base
     * y, si todo es correcto, persiste el documento en la colección correspondiente
     * con sus campos normalizados.</p>
     *
     * @param texto Texto de la palabra o frase a bloquear.
     * @return {@code true} si la palabra se guardó correctamente, {@code false} en caso contrario.
     */
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
            baseDatos.collection(Constantes.COLECCION_PALABRAS_BLOQUEADAS)
                .document(normalizado)
                .set(datos)
                .await()
            true
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo guardar el bloqueo")
            false
        }
    }

    /**
     * Muestra un diálogo de confirmación antes de eliminar una palabra bloqueada.
     *
     * @param palabra Instancia de [PalabraBloqueada] que se desea eliminar.
     */
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

    /**
     * Elimina una palabra bloqueada de la colección de Firestore.
     *
     * <p>Utiliza la forma normalizada de la palabra como identificador del documento.
     * En caso de error, muestra un mensaje informativo al usuario.</p>
     *
     * @param palabra Instancia de [PalabraBloqueada] que se desea eliminar.
     */
    private suspend fun eliminarPalabra(palabra: PalabraBloqueada) {
        try {
            baseDatos.collection(Constantes.COLECCION_PALABRAS_BLOQUEADAS)
                .document(palabra.palabraNormalizada)
                .delete()
                .await()
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo eliminar el bloqueo")
        }
    }

    /**
     * Muestra un mensaje breve al usuario mediante un [Toast].
     *
     * @param mensaje Texto que se mostrará en la notificación emergente.
     */
    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }

    /**
     * Modelo de datos que representa una palabra o frase bloqueada en el sistema.
     *
     * @property id Identificador único del documento en Firestore.
     * @property palabra Texto original de la palabra o frase tal como fue ingresada.
     * @property palabraNormalizada Versión normalizada de la palabra, utilizada para
     *           comparaciones sin distinción de mayúsculas, acentos ni caracteres especiales.
     */
    data class PalabraBloqueada(
        val id: String,
        val palabra: String,
        val palabraNormalizada: String,
    )

    /** Objeto compañero reservado para futuras constantes o métodos de fábrica. */
    companion object {
    }
}

/**
 * Adaptador del [RecyclerView] que muestra la lista de palabras bloqueadas filtradas.
 *
 * <p>Cada elemento de la lista muestra el texto de la palabra y un botón para eliminarla.
 * Las acciones de eliminación se delegan al callback proporcionado en el constructor.</p>
 *
 * @param palabras Lista de palabras bloqueadas a mostrar.
 * @param onEliminar Callback invocado cuando el usuario solicita eliminar una palabra.
 */
private class AdaptadorPalabras(
    private val palabras: List<PalabrasBloqueadasActivity.PalabraBloqueada>,
    private val onEliminar: (PalabrasBloqueadasActivity.PalabraBloqueada) -> Unit,
) : RecyclerView.Adapter<AdaptadorPalabras.VistaPalabra>() {

    /**
     * ViewHolder que contiene las referencias a las vistas de cada elemento de palabra bloqueada.
     *
     * @param itemView Vista raíz del elemento de lista inflada desde el diseño XML.
     */
    class VistaPalabra(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        /** Texto que muestra la palabra bloqueada. */
        val textoPalabra: android.widget.TextView = itemView.findViewById(R.id.texto_palabra_bloqueada)
        /** Botón que dispara la acción de eliminar la palabra. */
        val botonEliminar: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.boton_eliminar_palabra)
    }

    /**
     * Crea un nuevo [VistaPalabra] inflando el diseño del elemento de palabra bloqueada.
     *
     * @param parent Grupo de vistas padre al que se adjuntará la nueva vista.
     * @param viewType Tipo de vista (no utilizado en este adaptador).
     * @return Nueva instancia de [VistaPalabra].
     */
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaPalabra {
        val vista = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_palabra_bloqueada, parent, false)
        return VistaPalabra(vista)
    }

    /**
     * Vincula los datos de la palabra en la posición indicada con las vistas del ViewHolder.
     *
     * @param holder ViewHolder que contiene las vistas del elemento.
     * @param position Posición del elemento dentro de la lista.
     */
    override fun onBindViewHolder(holder: VistaPalabra, position: Int) {
        val palabra = palabras[position]
        holder.textoPalabra.text = palabra.palabra
        holder.botonEliminar.setOnClickListener { onEliminar(palabra) }
    }

    /**
     * Retorna la cantidad total de elementos en la lista de palabras filtradas.
     *
     * @return Número de elementos que el adaptador debe mostrar.
     */
    override fun getItemCount(): Int = palabras.size
}
