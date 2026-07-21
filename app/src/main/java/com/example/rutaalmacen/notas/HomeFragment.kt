package com.example.rutaalmacen.notas

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rutaalmacen.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Fragmento principal que presenta el listado de notas del usuario.
 *
 * Funcionalidad equivalente a [NotasActivity] pero embebida como fragmento.
 * Incluye búsqueda en tiempo real, creación y edición de notas mediante
 * [NotaDialogFragment], y eliminación con confirmación. Utiliza el ciclo de vida
 * del fragmento para las operaciones asíncronas.
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    /** Repositorio de notas con inicialización diferida. */
    private val repositorio: NotaRepository by lazy { NotaRepository() }
    /** Lista mutable que actúa como fuente de datos local para el adaptador. */
    private val notasActuales: MutableList<Nota> = mutableListOf()
    /** Adaptador del [RecyclerView] para renderizar las tarjetas de notas. */
    private lateinit var adaptador: AdaptadorNotas
    /** Vista de texto que se muestra cuando no hay notas que presentar. */
    private lateinit var textoVacio: TextView
    /** [RecyclerView] que contiene el listado visual de notas. */
    private lateinit var recycler: RecyclerView
    /** Texto de búsqueda actualmente ingresado por el usuario. */
    private var consultaActual: String = ""

    /**
     * Configura las vistas del fragmento tras la creación de la jerarquía de vistas.
     * Vincula el [RecyclerView], el campo de búsqueda, el botón flotante de creación
     * y registra los listeners correspondientes.
     *
     * @param view Vista raíz del fragmento.
     * @param savedInstanceState Estado guardado de la instancia anterior, o `null` si es nuevo.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recycler = view.findViewById(R.id.recycler_notas)
        textoVacio = view.findViewById(R.id.texto_vacio_notas)
        val campoBusqueda = view.findViewById<TextInputEditText>(R.id.campo_busqueda_notas)
        val fab = view.findViewById<FloatingActionButton>(R.id.fab_agregar_nota)

        adaptador = AdaptadorNotas(
            notas = notasActuales,
            onEditar = { nota -> mostrarDialogoNota(nota) },
            onEliminar = { nota -> confirmarEliminar(nota) },
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adaptador

        campoBusqueda.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                consultaActual = s?.toString().orEmpty()
                cargarNotas()
            }
        })

        fab.setOnClickListener { mostrarDialogoNota(null) }

        cargarNotas()
    }

    /**
     * Recarga las notas al reanudar el fragmento para reflejar cambios externos.
     */
    override fun onResume() {
        super.onResume()
        cargarNotas()
    }

    /**
     * Carga las notas desde el repositorio según la consulta de búsqueda actual.
     *
     * Si [consultaActual] está en blanco se obtienen todas las notas; de lo contrario
     * se ejecuta una búsqueda por texto. Actualiza el adaptador y la visibilidad del
     * mensaje de lista vacía.
     */
    private fun cargarNotas() {
        viewLifecycleOwner.lifecycleScope.launch {
            val resultado = if (consultaActual.isBlank()) {
                repositorio.obtenerTodas()
            } else {
                repositorio.buscarPorTexto(consultaActual)
            }
            notasActuales.clear()
            notasActuales.addAll(resultado)
            adaptador.notifyDataSetChanged()
            textoVacio.visibility = if (resultado.isEmpty()) View.VISIBLE else View.GONE
            recycler.visibility = if (resultado.isEmpty()) View.GONE else View.VISIBLE
            textoVacio.text = if (consultaActual.isBlank()) {
                "Aún no tienes notas. Toca + para crear la primera."
            } else {
                "Sin resultados para \"$consultaActual\""
            }
        }
    }

    /**
     * Muestra el diálogo de creación o edición de nota.
     *
     * @param nota Nota existente a editar, o `null` para crear una nota nueva.
     */
    private fun mostrarDialogoNota(nota: Nota?) {
        val dialogo = NotaDialogFragment.nuevaInstancia(nota)
        dialogo.setListener(object : NotaDialogFragment.Listener {
            override fun onNotaConfirmada(nota: Nota) {
                guardarNota(nota)
            }
        })
        dialogo.show(parentFragmentManager, NotaDialogFragment.TAG)
    }

    /**
     * Persiste la nota en el repositorio y recarga el listado si la operación es exitosa.
     *
     * @param nota Objeto [Nota] con los datos a guardar.
     */
    private fun guardarNota(nota: Nota) {
        viewLifecycleOwner.lifecycleScope.launch {
            val resultado = repositorio.guardar(nota)
            if (resultado.exitoso) {
                cargarNotas()
            } else {
                mostrarMensaje(resultado.mensaje ?: "No se pudo guardar la nota")
            }
        }
    }

    /**
     * Presenta un diálogo de confirmación antes de eliminar una nota.
     *
     * @param nota Objeto [Nota] que se desea eliminar.
     */
    private fun confirmarEliminar(nota: Nota) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Eliminar nota")
            .setMessage("¿Quieres eliminar esta nota?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val resultado = repositorio.eliminar(nota.id)
                    if (resultado.exitoso) {
                        cargarNotas()
                    } else {
                        mostrarMensaje(resultado.mensaje ?: "No se pudo eliminar")
                    }
                }
            }
            .show()
    }

    /**
     * Muestra un mensaje emergente (Toast) al usuario.
     *
     * @param mensaje Texto a presentar en el mensaje.
     */
    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(requireContext(), mensaje, Toast.LENGTH_SHORT).show()
    }

    /**
     * Adaptador del [RecyclerView] que renderiza la lista de notas como tarjetas visuales.
     *
     * Cada tarjeta muestra el título, contenido resumido, fecha formateada según
     * proximidad temporal y color de fondo configurable. Provee acciones de edición
     * y eliminación por cada elemento.
     *
     * @property notas Lista de notas que se presentan en el listado.
     * @property onEditar Acción invocada al pulsar una tarjeta para editar la nota.
     * @property onEliminar Acción invocada al pulsar el botón de eliminar de una tarjeta.
     */
    private class AdaptadorNotas(
        private val notas: List<Nota>,
        private val onEditar: (Nota) -> Unit,
        private val onEliminar: (Nota) -> Unit,
    ) : RecyclerView.Adapter<AdaptadorNotas.VistaNota>() {

        /** Formateador de fecha para notas del día actual (solo hora). */
        private val formatoHoy = SimpleDateFormat("HH:mm", Locale.forLanguageTag("es-CL"))
        /** Formateador de fecha para notas de la semana actual (día y hora). */
        private val formatoSemana = SimpleDateFormat("EEE HH:mm", Locale.forLanguageTag("es-CL"))
        /** Formateador de fecha para notas anteriores a la semana actual (fecha completa). */
        private val formatoCompleto = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.forLanguageTag("es-CL"))

        /**
         * Contenedor de vistas para un elemento individual de nota dentro del [RecyclerView].
         *
         * @property itemView Vista raíz del elemento de lista inflada.
         */
        class VistaNota(itemView: View) : RecyclerView.ViewHolder(itemView) {
            /** Tarjeta material que contiene visualmente la nota. */
            val tarjeta: com.google.android.material.card.MaterialCardView =
                itemView.findViewById(R.id.tarjeta_nota)
            /** Texto que muestra el título de la nota. */
            val texto_titulo: TextView = itemView.findViewById(R.id.texto_titulo_nota)
            /** Texto que muestra el contenido de la nota. */
            val texto_contenido: TextView = itemView.findViewById(R.id.texto_contenido_nota)
            /** Texto que muestra la fecha formateada de la nota. */
            val texto_fecha: TextView = itemView.findViewById(R.id.texto_fecha_nota)
            /** Botón para eliminar la nota. */
            val boton_eliminar: com.google.android.material.button.MaterialButton =
                itemView.findViewById(R.id.boton_eliminar_nota)
        }

        /**
         * Crea una nueva vista de elemento inflando el diseño `item_nota`.
         *
         * @param parent ViewGroup padre en el que se insertará la vista.
         * @param viewType Tipo de vista (no utilizado en este adaptador).
         * @return Instancia de [VistaNota] vinculada a la vista inflada.
         */
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaNota {
            val vista = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_nota, parent, false)
            return VistaNota(vista)
        }

        /**
         * Vincula los datos de la nota en la posición indicada con las vistas del contenedor.
         *
         * @param holder Contenedor de vistas donde se asignarán los datos.
         * @param position Posición del elemento dentro del adaptador.
         */
        override fun onBindViewHolder(holder: VistaNota, position: Int) {
            val nota = notas[position]
            holder.texto_titulo.text = nota.titulo.ifBlank { "(Sin título)" }
            holder.texto_contenido.visibility = if (nota.contenido.isBlank()) View.GONE else View.VISIBLE
            holder.texto_contenido.text = nota.contenido
            holder.texto_fecha.text = formatearFecha(nota.fechaLong)
            // Se intenta aplicar el color configurado; si falla se usa el amarillo por defecto
            try {
                holder.tarjeta.setCardBackgroundColor(Color.parseColor(nota.colorHex))
            } catch (_: Exception) {
                holder.tarjeta.setCardBackgroundColor(Color.parseColor(NotaColores.AMARILLO))
            }
            holder.itemView.setOnClickListener { onEditar(nota) }
            holder.boton_eliminar.setOnClickListener { onEliminar(nota) }
        }

        /**
         * @return Cantidad total de elementos en el adaptador.
         */
        override fun getItemCount(): Int = notas.size

        /**
         * Formatea una marca de tiempo según su proximidad con la fecha actual.
         *
         * - Si la nota es del mismo día: muestra solo la hora precedida de "Hoy,".
         * - Si la nota es de la misma semana: muestra el día de la semana y la hora.
         * - En otro caso: muestra la fecha completa con día, mes, año y hora.
         *
         * @param fechaLong Marca de tiempo en milisegundos desde epoch.
         * @return Cadena de texto con la fecha formateada, o cadena vacía si el valor es inválido.
         */
        private fun formatearFecha(fechaLong: Long): String {
            if (fechaLong <= 0L) return ""
            val ahora = Calendar.getInstance()
            val cal = Calendar.getInstance().apply { timeInMillis = fechaLong }
            // Se compara año y día del año para determinar si es hoy
            val mismoDia = ahora.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                ahora.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
            // Se compara año y semana del año para determinar si es de esta semana
            val mismaSemana = ahora.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                ahora.get(Calendar.WEEK_OF_YEAR) == cal.get(Calendar.WEEK_OF_YEAR)
            return when {
                mismoDia -> "Hoy, ${formatoHoy.format(Date(fechaLong))}"
                mismaSemana -> formatoSemana.format(Date(fechaLong))
                else -> formatoCompleto.format(Date(fechaLong))
            }
        }
    }
}
