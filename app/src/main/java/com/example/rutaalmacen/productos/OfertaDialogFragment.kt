package com.example.rutaalmacen.productos

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.rutaalmacen.ProductosFragment
import com.example.rutaalmacen.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fragmento de diálogo para la creación, edición y cancelación de ofertas sobre productos.
 *
 * Permite al vendedor definir un precio de oferta mediante dos modalidades:
 * precio final directo o porcentaje de descuento. Incluye cálculo en tiempo real
 * del descuento equivalente, estimación de fecha de vencimiento configurable por
 * horas, días o semanas, y opción de cancelar la oferta vigente.
 *
 * Los datos del producto se reciben como argumentos del fragmento y la comunicación
 * con el contenedor se realiza mediante la interfaz [Listener].
 */
class OfertaDialogFragment : DialogFragment() {

    /**
     * Interfaz de comunicación para notificar las acciones del diálogo de oferta.
     */
    interface Listener {
        /**
         * Invocado cuando el usuario confirma una nueva oferta o modifica una existente.
         *
         * @param producto Producto al que se aplica la oferta.
         * @param precioOferta Precio final con descuento aplicado.
         * @param descuentoPorcentaje Porcentaje de descuento calculado (1-99).
         * @param fechaFinOferta Marca de tiempo en milisegundos que indica el vencimiento de la oferta.
         */
        fun onOfertaConfirmada(
            producto: ProductosFragment.Producto,
            precioOferta: Double,
            descuentoPorcentaje: Int,
            fechaFinOferta: Long,
        )

        /**
         * Invocado cuando el usuario solicita cancelar la oferta vigente del producto.
         *
         * @param producto Producto cuya oferta se desea cancelar.
         */
        fun onOfertaCancelada(producto: ProductosFragment.Producto)
    }

    /** Referencia al listener que recibirá las acciones del diálogo. */
    private var listener: Listener? = null
    /** Producto sobre el cual se configura la oferta. */
    private lateinit var producto: ProductosFragment.Producto

    /** Grupo de botones de radio para seleccionar el tipo de descuento. */
    private lateinit var grupoTipo: RadioGroup
    /** Contenedor del campo de valor de la oferta. */
    private lateinit var contenedorValor: TextInputLayout
    /** Campo de entrada para el valor del descuento o precio final. */
    private lateinit var campoValor: TextInputEditText
    /** Texto que muestra el cálculo en tiempo real del descuento o precio equivalente. */
    private lateinit var textoCalculo: TextView
    /** Campo de entrada para la cantidad de tiempo de vigencia. */
    private lateinit var campoCantidad: TextInputEditText
    /** Selector desplegable para la unidad de tiempo de vigencia. */
    private lateinit var spinnerUnidad: Spinner
    /** Texto que muestra la fecha estimada de vencimiento de la oferta. */
    private lateinit var textoVencimiento: TextView
    /** Botón para cancelar la oferta actualmente vigente. */
    private lateinit var botonCancelarOferta: MaterialButton
    /** Texto que muestra el precio actual del producto. */
    private lateinit var textoPrecioActual: TextView

    /** Lista de unidades de tiempo disponibles para la vigencia de la oferta. */
    private val unidades = OfertaUtil.UnidadTiempo.values().toList()
    /** Formateador de fecha para presentar la fecha de vencimiento estimada. */
    private val formatoFecha = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.forLanguageTag("es-CL"))

    /**
     * Establece el listener que recibirá las acciones del diálogo.
     *
     * @param listener Implementación de [Listener] que procesará las confirmaciones y cancelaciones.
     */
    fun setListener(listener: Listener) {
        this.listener = listener
    }

    /**
     * Construye y retorna el diálogo de oferta.
     *
     * Inicializa las vistas del diseño `dialog_oferta`, configura los listeners de
     * actualización en tiempo real, precarga los valores si el producto ya tiene una
     * oferta activa, y establece los botones de guardar y cancelar.
     *
     * @param savedInstanceState Estado guardado de la instancia anterior, o `null`.
     * @return Instancia de [Dialog] configurada para la gestión de ofertas.
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        producto = obtenerProductoArgumento()
            ?: return MaterialAlertDialogBuilder(requireContext())
                .setMessage("No se pudo cargar el producto")
                .setPositiveButton("Cerrar", null)
                .create()

        val vista = layoutInflater.inflate(R.layout.dialog_oferta, null)
        inicializarVistas(vista)
        configurarListeners()
        precargarValores()
        actualizarCalculoYVencimiento()

        val dialogo = MaterialAlertDialogBuilder(requireContext())
            .setView(vista)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar oferta", null)
            .create()

        dialogo.setOnShowListener {
            val botonGuardar = dialogo.getButton(AlertDialog.BUTTON_POSITIVE)
            botonGuardar.setOnClickListener { intentarGuardar(dialogo) }
            botonCancelarOferta.setOnClickListener {
                listener?.onOfertaCancelada(producto)
                dialogo.dismiss()
            }
        }
        return dialogo
    }

    /**
     * Inicializa las vistas del diálogo vinculándolas con sus identificadores de diseño.
     *
     * Configura el texto de precio actual, el adaptador del selector de unidades de tiempo
     * y la visibilidad del botón de cancelar oferta según el estado actual del producto.
     *
     * @param vista Vista raíz del diseño `dialog_oferta` inflada.
     */
    private fun inicializarVistas(vista: View) {
        textoPrecioActual = vista.findViewById(R.id.texto_precio_actual)
        grupoTipo = vista.findViewById(R.id.grupo_tipo_descuento)
        contenedorValor = vista.findViewById(R.id.contenedor_valor_oferta)
        campoValor = vista.findViewById(R.id.campo_valor_oferta)
        textoCalculo = vista.findViewById(R.id.texto_descuento_calculado)
        campoCantidad = vista.findViewById(R.id.campo_cantidad_tiempo)
        spinnerUnidad = vista.findViewById(R.id.spinner_unidad_tiempo)
        textoVencimiento = vista.findViewById(R.id.texto_vencimiento_estimado)
        botonCancelarOferta = vista.findViewById(R.id.boton_cancelar_oferta)

        val precioTexto = String.format(Locale.forLanguageTag("es-CL"), "%.0f", producto.precio)
        val unidadEtiqueta = if (producto.unidadPrecio == "kilo") "kg" else "unidad"
        textoPrecioActual.text = "Precio actual: $$precioTexto / $unidadEtiqueta"

        val adaptadorUnidades = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            unidades.map { it.etiqueta },
        )
        adaptadorUnidades.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerUnidad.adapter = adaptadorUnidades

        botonCancelarOferta.visibility = if (producto.enOferta) View.VISIBLE else View.GONE
    }

    /**
     * Registra los listeners de los campos de entrada para actualizar el cálculo
     * de la oferta y la fecha de vencimiento en tiempo real.
     */
    private fun configurarListeners() {
        grupoTipo.setOnCheckedChangeListener { _, _ ->
            actualizarHintValor()
            actualizarCalculoYVencimiento()
        }

        val observador = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                actualizarCalculoYVencimiento()
            }
        }
        campoValor.addTextChangedListener(observador)
        campoCantidad.addTextChangedListener(observador)

        spinnerUnidad.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                actualizarCalculoYVencimiento()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    /**
     * Precarga los valores de la oferta existente si el producto ya tiene una oferta activa.
     *
     * Selecciona la opción de precio final y completa el campo de valor con el precio
     * de oferta actual. Si no hay oferta, selecciona precio final como opción por defecto.
     */
    private fun precargarValores() {
        if (producto.enOferta && producto.precioOferta != null) {
            grupoTipo.check(R.id.opcion_precio_final)
            campoValor.setText(
                String.format(Locale.forLanguageTag("es-CL"), "%.0f", producto.precioOferta),
            )
        } else {
            grupoTipo.check(R.id.opcion_precio_final)
        }
        actualizarHintValor()
    }

    /**
     * Actualiza la pista (hint) y los sufijos del campo de valor según el tipo de descuento seleccionado.
     *
     * - Si es porcentaje: muestra "Porcentaje de descuento (1-99)" con sufijo "%".
     * - Si es precio final: muestra "Precio final con descuento" con prefijo "$".
     */
    private fun actualizarHintValor() {
        val esPorcentaje = grupoTipo.checkedRadioButtonId == R.id.opcion_porcentaje
        if (esPorcentaje) {
            contenedorValor.hint = "Porcentaje de descuento (1-99)"
            contenedorValor.prefixText = null
            contenedorValor.suffixText = "%"
        } else {
            contenedorValor.hint = "Precio final con descuento"
            contenedorValor.prefixText = "$"
            contenedorValor.suffixText = null
        }
    }

    /**
     * Determina el tipo de descuento seleccionado en el grupo de botones de radio.
     *
     * @return [OfertaUtil.TipoDescuento.PORCENTAJE] o [OfertaUtil.TipoDescuento.PRECIO_FINAL]
     *         según la opción marcada.
     */
    private fun obtenerTipoSeleccionado(): OfertaUtil.TipoDescuento {
        return if (grupoTipo.checkedRadioButtonId == R.id.opcion_porcentaje) {
            OfertaUtil.TipoDescuento.PORCENTAJE
        } else {
            OfertaUtil.TipoDescuento.PRECIO_FINAL
        }
    }

    /**
     * Calcula el resultado de la oferta según el tipo de descuento y el valor ingresado.
     *
     * @return [OfertaUtil.CalculoOferta] con el precio final y porcentaje de descuento,
     *         o `null` si el valor ingresado es inválido o no se puede calcular.
     */
    private fun calcularOferta(): OfertaUtil.CalculoOferta? {
        val valor = campoValor.text?.toString()?.toDoubleOrNull() ?: return null
        return when (obtenerTipoSeleccionado()) {
            OfertaUtil.TipoDescuento.PRECIO_FINAL ->
                OfertaUtil.calcularPorPrecioFinal(producto.precio, valor)
            OfertaUtil.TipoDescuento.PORCENTAJE ->
                OfertaUtil.calcularPorPorcentaje(producto.precio, valor.toInt())
        }
    }

    /**
     * Calcula la fecha de vencimiento de la oferta según la unidad y cantidad seleccionadas.
     *
     * @return Marca de tiempo en milisegundos que representa el vencimiento de la oferta.
     */
    private fun obtenerFechaFin(): Long {
        val unidad = unidades.getOrNull(spinnerUnidad.selectedItemPosition) ?: OfertaUtil.UnidadTiempo.HORAS
        val cantidad = campoCantidad.text?.toString()?.toIntOrNull() ?: 1
        return OfertaUtil.calcularFechaFin(unidad, cantidad)
    }

    /**
     * Actualiza los textos de cálculo de descuento y fecha de vencimiento en la interfaz.
     *
     * Muestra el precio final o el porcentaje equivalente según el tipo de descuento
     * seleccionado. Si el cálculo no es válido, oculta el texto de cálculo.
     */
    private fun actualizarCalculoYVencimiento() {
        val calculo = calcularOferta()
        if (calculo != null) {
            val precioFmt = String.format(Locale.forLanguageTag("es-CL"), "%.0f", calculo.precioOferta)
            textoCalculo.visibility = View.VISIBLE
            textoCalculo.text = when (obtenerTipoSeleccionado()) {
                OfertaUtil.TipoDescuento.PRECIO_FINAL ->
                    "Equivale a ${calculo.descuentoPorcentaje}% de descuento"
                OfertaUtil.TipoDescuento.PORCENTAJE ->
                    "Precio final: $$precioFmt"
            }
        } else {
            textoCalculo.visibility = View.GONE
        }
        textoVencimiento.text = "Vence el ${formatoFecha.format(Date(obtenerFechaFin()))}"
    }

    /**
     * Intenta guardar la oferta tras validar los datos ingresados.
     *
     * Si el cálculo de la oferta es válido, notifica al listener con los datos calculados
     * y cierra el diálogo. En caso contrario, muestra un mensaje de error en el campo de valor.
     *
     * @param dialogo Referencia al diálogo para poder cerrarlo tras la confirmación.
     */
    private fun intentarGuardar(dialogo: AlertDialog) {
        val calculo = calcularOferta()
        if (calculo == null) {
            contenedorValor.error = when (obtenerTipoSeleccionado()) {
                OfertaUtil.TipoDescuento.PRECIO_FINAL ->
                    "Ingresa un precio menor a $${producto.precio.toInt()}"
                OfertaUtil.TipoDescuento.PORCENTAJE ->
                    "Ingresa un porcentaje entre 1 y 99"
            }
            return
        }
        contenedorValor.error = null
        listener?.onOfertaConfirmada(
            producto = producto,
            precioOferta = calculo.precioOferta,
            descuentoPorcentaje = calculo.descuentoPorcentaje,
            fechaFinOferta = obtenerFechaFin(),
        )
        dialogo.dismiss()
    }

    /**
     * Extrae los datos del producto desde los argumentos del fragmento.
     *
     * Reconstruye un objeto [ProductosFragment.Producto] a partir de las claves
     * de argumento definidas en el objeto complementario.
     *
     * @return Objeto [ProductosFragment.Producto] reconstruido, o `null` si no existen argumentos.
     */
    @Suppress("DEPRECATION")
    private fun obtenerProductoArgumento(): ProductosFragment.Producto? {
        val args = arguments ?: return null
        return ProductosFragment.Producto(
            id = args.getString(ARG_ID).orEmpty(),
            nombre = args.getString(ARG_NOMBRE).orEmpty(),
            nombreNormalizado = args.getString(ARG_NOMBRE_NORM).orEmpty(),
            categoria = args.getString(ARG_CATEGORIA).orEmpty(),
            precio = args.getDouble(ARG_PRECIO, 0.0),
            unidadPrecio = args.getString(ARG_UNIDAD_PRECIO, "unidad"),
            cantidad = args.getInt(ARG_CANTIDAD, 1),
            descripcion = args.getString(ARG_DESCRIPCION).orEmpty(),
            disponible = args.getBoolean(ARG_DISPONIBLE, true),
            fechaActualizacion = args.getLong(ARG_FECHA_ACT, 0L),
            precioOferta = if (args.containsKey(ARG_PRECIO_OFERTA)) args.getDouble(ARG_PRECIO_OFERTA) else null,
            descuentoPorcentaje = if (args.containsKey(ARG_DESCUENTO)) args.getInt(ARG_DESCUENTO) else null,
            fechaFinOferta = if (args.containsKey(ARG_FECHA_FIN)) args.getLong(ARG_FECHA_FIN) else null,
            enOferta = args.getBoolean(ARG_EN_OFERTA, false),
        )
    }

    /**
     * Objeto complementario que define las constantes de argumentos y el método
     * de fábrica para crear instancias del diálogo de oferta.
     */
    companion object {
        /** Etiqueta utilizada para identificar el fragmento en el [FragmentManager]. */
        const val TAG = "OfertaDialogFragment"

        /** Clave de argumento para el identificador del producto. */
        private const val ARG_ID = "id"
        /** Clave de argumento para el nombre del producto. */
        private const val ARG_NOMBRE = "nombre"
        /** Clave de argumento para el nombre normalizado del producto. */
        private const val ARG_NOMBRE_NORM = "nombreNorm"
        /** Clave de argumento para la categoría del producto. */
        private const val ARG_CATEGORIA = "categoria"
        /** Clave de argumento para el precio del producto. */
        private const val ARG_PRECIO = "precio"
        /** Clave de argumento para la unidad de precio del producto. */
        private const val ARG_UNIDAD_PRECIO = "unidadPrecio"
        /** Clave de argumento para la cantidad del producto. */
        private const val ARG_CANTIDAD = "cantidad"
        /** Clave de argumento para la descripción del producto. */
        private const val ARG_DESCRIPCION = "descripcion"
        /** Clave de argumento para la disponibilidad del producto. */
        private const val ARG_DISPONIBLE = "disponible"
        /** Clave de argumento para la fecha de última actualización del producto. */
        private const val ARG_FECHA_ACT = "fechaActualizacion"
        /** Clave de argumento para el precio de oferta del producto. */
        private const val ARG_PRECIO_OFERTA = "precioOferta"
        /** Clave de argumento para el porcentaje de descuento del producto. */
        private const val ARG_DESCUENTO = "descuentoPorcentaje"
        /** Clave de argumento para la fecha de fin de oferta del producto. */
        private const val ARG_FECHA_FIN = "fechaFinOferta"
        /** Clave de argumento para el indicador de oferta activa del producto. */
        private const val ARG_EN_OFERTA = "enOferta"

        /**
         * Crea una nueva instancia del diálogo precargada con los datos del producto.
         *
         * @param producto Producto sobre el cual se gestionará la oferta.
         * @return Instancia de [OfertaDialogFragment] lista para ser mostrada.
         */
        fun nuevaInstancia(producto: ProductosFragment.Producto): OfertaDialogFragment {
            val fragment = OfertaDialogFragment()
            val args = Bundle().apply {
                putString(ARG_ID, producto.id)
                putString(ARG_NOMBRE, producto.nombre)
                putString(ARG_NOMBRE_NORM, producto.nombreNormalizado)
                putString(ARG_CATEGORIA, producto.categoria)
                putDouble(ARG_PRECIO, producto.precio)
                putString(ARG_UNIDAD_PRECIO, producto.unidadPrecio)
                putInt(ARG_CANTIDAD, producto.cantidad)
                putString(ARG_DESCRIPCION, producto.descripcion)
                putBoolean(ARG_DISPONIBLE, producto.disponible)
                putLong(ARG_FECHA_ACT, producto.fechaActualizacion)
                producto.precioOferta?.let { putDouble(ARG_PRECIO_OFERTA, it) }
                producto.descuentoPorcentaje?.let { putInt(ARG_DESCUENTO, it) }
                producto.fechaFinOferta?.let { putLong(ARG_FECHA_FIN, it) }
                putBoolean(ARG_EN_OFERTA, producto.enOferta)
            }
            fragment.arguments = args
            return fragment
        }
    }
}
