package com.example.rutaalmacen.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Toast
import com.example.rutaalmacen.R
import com.example.rutaalmacen.pagos.CodigoPlan
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Calendar

/**
 * Diálogo de hoja inferior que permite al administrador gestionar el plan de suscripción
 * de un usuario específico.
 *
 * <p>Muestra los datos del usuario (nombre y correo), un selector de plan, opciones de
 * duración (1 mes, 6 meses, 1 año o indefinido) y un campo para ingresar la justificación
 * del cambio. Al confirmar, actualiza el plan del usuario y registra la operación en el
 * historial de privilegios dentro de Firestore.</p>
 */
class GestionarPlanDialogFragment : BottomSheetDialogFragment() {

    /** Texto que muestra el nombre del usuario cuyo plan se gestionará. */
    private lateinit var textoNombre: android.widget.TextView
    /** Texto que muestra el correo del usuario cuyo plan se gestionará. */
    private lateinit var textoCorreo: android.widget.TextView
    /** Selector desplegable para elegir el nuevo plan del usuario. */
    private lateinit var spinnerPlan: Spinner
    /** Grupo de botones de radio para seleccionar la duración del plan. */
    private lateinit var radioGrupo: RadioGroup
    /** Campo de texto para ingresar la justificación del cambio de plan. */
    private lateinit var campoRazon: TextInputEditText
    /** Botón para confirmar y guardar el cambio de plan. */
    private lateinit var botonConfirmar: MaterialButton

    /** Identificador único del usuario cuyo plan se modificará. */
    private var usuarioId: String = ""
    /** Nombre visible del usuario cuyo plan se modificará. */
    private var usuarioNombre: String = ""
    /** Correo electrónico del usuario cuyo plan se modificará. */
    private var usuarioCorreo: String = ""

    /** Instancia de Firestore utilizada para las operaciones de lectura y escritura. */
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    /** Instancia de autenticación de Firebase para obtener el UID del administrador actual. */
    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    /** Lista de nombres legibles de los planes disponibles para mostrar en el selector. */
    private val planes = listOf("Gratis", "Vendedor", "Comercio", "Empresarial")
    /** Lista de identificadores de código de cada plan, en el mismo orden que [planes]. */
    private val codigosPlanes = listOf(
        CodigoPlan.GRATIS.id,
        CodigoPlan.VENDEDOR.id,
        CodigoPlan.COMERCIO.id,
        CodigoPlan.EMPRESARIAL.id,
    )

    /**
     * Método del ciclo de vida llamado al crear el fragmento.
     * Extrae los argumentos del [Bundle] para obtener los datos del usuario objetivo.
     *
     * @param savedInstanceState Estado previamente guardado de la instancia, o {@code null}.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usuarioId = arguments?.getString(ARG_USUARIO_ID).orEmpty()
        usuarioNombre = arguments?.getString(ARG_USUARIO_NOMBRE).orEmpty()
        usuarioCorreo = arguments?.getString(ARG_USUARIO_CORREO).orEmpty()
    }

    /**
     * Infla el diseño del diálogo de gestión de plan.
     *
     * @param inflater Inflador de diseños utilizado para crear la vista.
     * @param container Grupo de vistas padre, o {@code null}.
     * @param savedInstanceState Estado previamente guardado de la instancia, o {@code null}.
     * @return Vista raíz del diálogo inflada desde el diseño XML.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.layout_dialog_gestionar_plan, container, false)
    }

    /**
     * Método llamado después de crear la vista del diálogo.
     *
     * <p>Inicializa todos los componentes de la interfaz, muestra los datos del usuario,
     * configura el adaptador del selector de planes y registra el listener del botón
     * de confirmación.</p>
     *
     * @param view Vista raíz del diálogo.
     * @param savedInstanceState Estado previamente guardado de la instancia, o {@code null}.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        textoNombre = view.findViewById(R.id.texto_nombre_usuario_plan)
        textoCorreo = view.findViewById(R.id.texto_correo_usuario_plan)
        spinnerPlan = view.findViewById(R.id.spinner_plan_usuario)
        radioGrupo = view.findViewById(R.id.radio_grupo_duracion)
        campoRazon = view.findViewById(R.id.campo_razon_cambio)
        botonConfirmar = view.findViewById(R.id.boton_confirmar_plan)

        textoNombre.text = usuarioNombre.ifBlank { "Sin nombre" }
        textoCorreo.text = usuarioCorreo.ifBlank { "Sin correo" }

        val adaptador = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, planes)
        adaptador.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPlan.adapter = adaptador

        botonConfirmar.setOnClickListener { validarYGardar() }
    }

    /**
     * Valida los datos ingresados por el usuario y guarda el cambio de plan.
     *
     * <p>Verifica que la justificación no esté vacía, determina el código de plan
     * seleccionado y la duración en milisegundos según la opción de radio elegida,
     * y delega el guardado a [guardarPlan].</p>
     */
    private fun validarYGardar() {
        val razon = campoRazon.text?.toString()?.trim().orEmpty()
        if (razon.isBlank()) {
            campoRazon.error = "La justificación es obligatoria"
            campoRazon.requestFocus()
            return
        }

        val planSeleccionado = spinnerPlan.selectedItemPosition
        val codigoPlan = codigosPlanes.getOrNull(planSeleccionado) ?: CodigoPlan.GRATIS.id

        val duracionMillis = when (radioGrupo.checkedRadioButtonId) {
            R.id.radio_un_mes -> calcularDuracion(1)
            R.id.radio_seis_meses -> calcularDuracion(6)
            R.id.radio_un_anio -> calcularDuracion(12)
            R.id.radio_indefinido -> ANO_2050_MILLIS
            else -> calcularDuracion(1)
        }

        guardarPlan(codigoPlan, duracionMillis, razon)
    }

    /**
     * Calcula la marca de tiempo de vencimiento sumando los meses indicados
     * a la fecha actual.
     *
     * @param meses Cantidad de meses que durará el plan.
     * @return Marca de tiempo en milisegundos correspondiente al vencimiento.
     */
    private fun calcularDuracion(meses: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MONTH, meses)
        return calendar.timeInMillis
    }

    /**
     * Guarda el nuevo plan del usuario en Firestore mediante una operación por lotes.
     *
     * <p>Actualiza los campos de plan, vencimiento y fecha de actualización en el documento
     * del usuario, y crea un registro en la subcolección «HistorialPrivilegios» con los
     * detalles del cambio. La operación se ejecuta en un hilo de fondo; al finalizar,
     * muestra un mensaje de éxito o error en el hilo principal y cierra el diálogo.</p>
     *
     * @param codigoPlan Identificador del plan seleccionado.
     * @param duracionMillis Marca de tiempo en milisegundos del vencimiento del plan.
     * @param razon Justificación del cambio de plan ingresada por el administrador.
     */
    private fun guardarPlan(codigoPlan: String, duracionMillis: Long, razon: String) {
        botonConfirmar.isEnabled = false
        botonConfirmar.text = "Guardando..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val adminId = autenticacion.currentUser?.uid.orEmpty()
                val ahora = System.currentTimeMillis()

                val lote = baseDatos.batch()

                val documentoUsuario = baseDatos.collection("Usuarios").document(usuarioId)
                lote.set(
                    documentoUsuario,
                    mapOf(
                        "plan" to codigoPlan,
                        "planVencimiento" to duracionMillis,
                        "fechaActualizacion" to ahora,
                    ),
                    SetOptions.merge(),
                )

                val documentoHistorial = baseDatos.collection("Usuarios")
                    .document(usuarioId)
                    .collection("HistorialPrivilegios")
                    .document()

                lote.set(
                    documentoHistorial,
                    mapOf(
                        "fecha" to ahora,
                        "planOtorgado" to codigoPlan,
                        "razonCambio" to razon,
                        "administrador" to adminId,
                        "duracionMillis" to duracionMillis,
                    ),
                )

                lote.commit().await()

                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(requireContext(), "Plan actualizado correctamente", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            } catch (excepcion: Exception) {
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(requireContext(), "Error al actualizar el plan", Toast.LENGTH_SHORT).show()
                    botonConfirmar.isEnabled = true
                    botonConfirmar.text = "Confirmar Plan"
                }
            }
        }
    }

    /** Objeto compañero que contiene constantes y el método de fábrica del diálogo. */
    companion object {
        /** Etiqueta utilizada para identificar el fragmento en transacciones. */
        const val TAG = "GestionarPlanDialogFragment"
        /** Clave del argumento que contiene el identificador del usuario. */
        private const val ARG_USUARIO_ID = "usuario_id"
        /** Clave del argumento que contiene el nombre del usuario. */
        private const val ARG_USUARIO_NOMBRE = "usuario_nombre"
        /** Clave del argumento que contiene el correo del usuario. */
        private const val ARG_USUARIO_CORREO = "usuario_correo"

        /**
         * Marca de tiempo en milisegundos correspondiente al 31 de diciembre de 2050.
         * Se utiliza como valor de vencimiento para planes de duración indefinida.
         */
        private val ANO_2050_MILLIS: Long = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2050)
            set(Calendar.MONTH, Calendar.DECEMBER)
            set(Calendar.DAY_OF_MONTH, 31)
        }.timeInMillis

        /**
         * Crea una nueva instancia del diálogo con los datos del usuario objetivo.
         *
         * @param usuarioId Identificador único del usuario en Firestore.
         * @param usuarioNombre Nombre visible del usuario.
         * @param usuarioCorreo Correo electrónico del usuario.
         * @return Nueva instancia de [GestionarPlanDialogFragment] con los argumentos configurados.
         */
        fun newInstance(
            usuarioId: String,
            usuarioNombre: String,
            usuarioCorreo: String,
        ): GestionarPlanDialogFragment {
            return GestionarPlanDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_USUARIO_ID, usuarioId)
                    putString(ARG_USUARIO_NOMBRE, usuarioNombre)
                    putString(ARG_USUARIO_CORREO, usuarioCorreo)
                }
            }
        }
    }
}
