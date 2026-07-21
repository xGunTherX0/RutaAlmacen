package com.example.rutaalmacen.notas

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.rutaalmacen.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

/**
 * Fragmento de diálogo para la creación y edición de notas.
 *
 * Permite al usuario ingresar o modificar el título, contenido y color de una nota.
 * Si se recibe una [Nota] como argumento, el diálogo se abre en modo edición
 * precargando los valores existentes. Los datos se comunican al fragmento o actividad
 * contenedora mediante la interfaz [Listener].
 */
class NotaDialogFragment : DialogFragment() {

    /**
     * Interfaz de comunicación para notificar la confirmación de una nota.
     */
    interface Listener {
        /**
         * Invocado cuando el usuario confirma los datos de la nota.
         *
         * @param nota Objeto [Nota] con los datos ingresados o modificados.
         */
        fun onNotaConfirmada(nota: Nota)
    }

    /** Referencia al listener que recibirá la nota confirmada. */
    private var listener: Listener? = null
    /** Color actualmente seleccionado en el selector visual de colores. */
    private var colorSeleccionado: String = NotaColores.AMARILLO

    /**
     * Establece el listener que recibirá la nota al confirmar el diálogo.
     *
     * @param listener Implementación de [Listener] que procesará la nota confirmada.
     */
    fun setListener(listener: Listener) {
        this.listener = listener
    }

    /**
     * Construye y retorna el diálogo de nota.
     *
     * Inicializa las vistas del diseño `dialog_nota`, precarga los datos si se trata
     * de una edición, configura el selector de colores y establece los botones de
     * acción. El botón "Guardar" valida que al menos el título o el contenido no estén
     * en blanco antes de invocar al listener.
     *
     * @param savedInstanceState Estado guardado de la instancia anterior, o `null`.
     * @return Instancia de [Dialog] configurada para la creación o edición de nota.
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val notaExistente = obtenerNotaArgumento()
        val vista = layoutInflater.inflate(R.layout.dialog_nota, null)
        val campoTitulo = vista.findViewById<TextInputEditText>(R.id.campo_titulo_nota)
        val campoContenido = vista.findViewById<TextInputEditText>(R.id.campo_contenido_nota)
        val selectorColor = vista.findViewById<LinearLayout>(R.id.selector_color_nota)

        notaExistente?.let {
            campoTitulo.setText(it.titulo)
            campoContenido.setText(it.contenido)
            colorSeleccionado = it.colorHex
        }

        construirSelectorColor(selectorColor)

        val titulo = if (notaExistente == null) "Nueva nota" else "Editar nota"

        val dialogo = MaterialAlertDialogBuilder(requireContext())
            .setTitle(titulo)
            .setView(vista)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar", null)
            .create()

        dialogo.setOnShowListener {
            val botonGuardar = dialogo.getButton(AlertDialog.BUTTON_POSITIVE)
            botonGuardar.setOnClickListener {
                val tituloTexto = campoTitulo.text?.toString().orEmpty().trim()
                val contenidoTexto = campoContenido.text?.toString().orEmpty().trim()
                if (tituloTexto.isBlank() && contenidoTexto.isBlank()) {
                    campoTitulo.error = "Ingresa un título o contenido"
                    return@setOnClickListener
                }
                val notaResultado = (notaExistente ?: Nota()).copy(
                    titulo = tituloTexto,
                    contenido = contenidoTexto,
                    colorHex = colorSeleccionado,
                )
                listener?.onNotaConfirmada(notaResultado)
                dialogo.dismiss()
            }
        }
        return dialogo
    }

    /**
     * Construye dinámicamente el selector visual de colores dentro del contenedor indicado.
     *
     * Cada color de [NotaColores.opciones] se representa como una vista circular.
     * El color actualmente seleccionado se distingue con un borde más grueso y oscuro.
     *
     * @param contenedor [LinearLayout] donde se agregarán las vistas circulares de color.
     */
    private fun construirSelectorColor(contenedor: LinearLayout) {
        contenedor.removeAllViews()
        val tamanio = (resources.displayMetrics.density * 40).toInt()
        val margen = (resources.displayMetrics.density * 6).toInt()
        NotaColores.opciones.forEach { hex ->
            val celda = View(requireContext())
            val parametros = LinearLayout.LayoutParams(tamanio, tamanio)
            parametros.marginEnd = margen
            celda.layoutParams = parametros
            celda.background = construirFondoColor(hex, hex == colorSeleccionado)
            celda.setOnClickListener {
                colorSeleccionado = hex
                construirSelectorColor(contenedor)
            }
            contenedor.addView(celda)
        }
    }

    /**
     * Genera el fondo circular ([GradientDrawable]) para un color del selector.
     *
     * @param hex Código de color en formato hexadecimal (AARRGGBB).
     * @param seleccionado Indica si el color debe mostrarse con borde de selección.
     * @return [GradientDrawable] circular con el color y estado de selección aplicados.
     */
    private fun construirFondoColor(hex: String, seleccionado: Boolean): GradientDrawable {
        val forma = GradientDrawable()
        forma.shape = GradientDrawable.OVAL
        forma.setColor(Color.parseColor(hex))
        if (seleccionado) {
            val grosor = (resources.displayMetrics.density * 3).toInt()
            forma.setStroke(grosor, Color.parseColor("#FF0F172A"))
        }
        return forma
    }

    /**
     * Extrae los datos de la nota desde los argumentos del fragmento.
     *
     * @return Objeto [Nota] reconstruido a partir de los argumentos, o `null`
     *         si no existen argumentos o falta la clave de identificación.
     */
    @Suppress("DEPRECATION")
    private fun obtenerNotaArgumento(): Nota? {
        val args = arguments ?: return null
        if (!args.containsKey(ARG_ID)) return null
        return Nota(
            id = args.getString(ARG_ID).orEmpty(),
            titulo = args.getString(ARG_TITULO).orEmpty(),
            contenido = args.getString(ARG_CONTENIDO).orEmpty(),
            fechaLong = args.getLong(ARG_FECHA, 0L),
            colorHex = args.getString(ARG_COLOR, NotaColores.AMARILLO),
        )
    }

    /**
     * Objeto complementario que define las constantes de argumentos y el método
     * de fábrica para crear instancias del diálogo.
     */
    companion object {
        /** Etiqueta utilizada para identificar el fragmento en el [FragmentManager]. */
        const val TAG = "NotaDialogFragment"

        /** Clave de argumento para el identificador de la nota. */
        private const val ARG_ID = "id"
        /** Clave de argumento para el título de la nota. */
        private const val ARG_TITULO = "titulo"
        /** Clave de argumento para el contenido de la nota. */
        private const val ARG_CONTENIDO = "contenido"
        /** Clave de argumento para la marca de tiempo de la nota. */
        private const val ARG_FECHA = "fechaLong"
        /** Clave de argumento para el color hexadecimal de la nota. */
        private const val ARG_COLOR = "colorHex"

        /**
         * Crea una nueva instancia del diálogo, opcionalmente precargada con los datos
         * de una nota existente para modo edición.
         *
         * @param nota Nota existente a editar, o `null` para crear una nota nueva.
         * @return Instancia de [NotaDialogFragment] lista para ser mostrada.
         */
        fun nuevaInstancia(nota: Nota? = null): NotaDialogFragment {
            val fragment = NotaDialogFragment()
            if (nota != null) {
                fragment.arguments = Bundle().apply {
                    putString(ARG_ID, nota.id)
                    putString(ARG_TITULO, nota.titulo)
                    putString(ARG_CONTENIDO, nota.contenido)
                    putLong(ARG_FECHA, nota.fechaLong)
                    putString(ARG_COLOR, nota.colorHex)
                }
            }
            return fragment
        }
    }
}
