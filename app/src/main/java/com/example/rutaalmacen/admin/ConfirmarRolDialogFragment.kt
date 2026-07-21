package com.example.rutaalmacen.admin

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.example.rutaalmacen.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Diálogo de confirmación que solicita al administrador ingresar un PIN de seguridad
 * antes de promover un usuario al rol de administrador.
 *
 * <p>Muestra un mensaje de advertencia, valida el PIN ingresado contra el PIN maestro
 * definido en [AdminViewModel] y, si es correcto, actualiza el rol del usuario en
 * Firestore y registra la operación en la subcolección «HistorialRoles».</p>
 */
class ConfirmarRolDialogFragment : DialogFragment() {

    /** Objeto compañero que contiene constantes y el método de fábrica del diálogo. */
    companion object {
        /** Etiqueta utilizada para identificar el fragmento en transacciones. */
        const val TAG = "ConfirmarRolDialogFragment"
        /** Clave del argumento que contiene el identificador del usuario. */
        private const val ARG_USUARIO_ID = "usuario_id"
        /** Clave del argumento que contiene el nombre del usuario. */
        private const val ARG_USUARIO_NOMBRE = "usuario_nombre"
        /** Clave del argumento que contiene el correo del usuario. */
        private const val ARG_USUARIO_CORREO = "usuario_correo"
        /** Clave del argumento que contiene el rol actual del usuario. */
        private const val ARG_USUARIO_ROL_ACTUAL = "usuario_rol_actual"

        /**
         * Crea una nueva instancia del diálogo con los datos del usuario a promover.
         *
         * @param usuarioId Identificador único del usuario en Firestore.
         * @param usuarioNombre Nombre visible del usuario.
         * @param usuarioCorreo Correo electrónico del usuario.
         * @param usuarioRolActual Rol actual del usuario antes de la promoción.
         * @return Nueva instancia de [ConfirmarRolDialogFragment] con los argumentos configurados.
         */
        fun newInstance(
            usuarioId: String,
            usuarioNombre: String,
            usuarioCorreo: String,
            usuarioRolActual: String,
        ): ConfirmarRolDialogFragment {
            return ConfirmarRolDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_USUARIO_ID, usuarioId)
                    putString(ARG_USUARIO_NOMBRE, usuarioNombre)
                    putString(ARG_USUARIO_CORREO, usuarioCorreo)
                    putString(ARG_USUARIO_ROL_ACTUAL, usuarioRolActual)
                }
            }
        }
    }

    /** Texto que muestra la advertencia sobre la promoción del usuario. */
    private lateinit var textoAdvertencia: TextView
    /** Contenedor del campo de PIN que muestra errores de validación. */
    private lateinit var contenedorPin: TextInputLayout
    /** Campo de texto para ingresar el PIN de seguridad del administrador. */
    private lateinit var campoPin: TextInputEditText
    /** Botón para cancelar y cerrar el diálogo sin realizar cambios. */
    private lateinit var botonCancelar: MaterialButton
    /** Botón para confirmar la promoción tras validar el PIN. */
    private lateinit var botonConfirmar: MaterialButton

    /** Identificador único del usuario que será promovido. */
    private var usuarioId: String = ""
    /** Nombre visible del usuario que será promovido. */
    private var usuarioNombre: String = ""
    /** Correo electrónico del usuario que será promovido. */
    private var usuarioCorreo: String = ""
    /** Rol actual del usuario antes de la promoción. */
    private var usuarioRolActual: String = ""

    /** Instancia de Firestore utilizada para las operaciones de lectura y escritura. */
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    /** Modelo de vista utilizado para validar el PIN maestro. */
    private val viewModel: AdminViewModel by lazy { AdminViewModel() }

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
        usuarioRolActual = arguments?.getString(ARG_USUARIO_ROL_ACTUAL).orEmpty()
    }

    /**
     * Infla el diseño del diálogo de confirmación de rol.
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
        return inflater.inflate(R.layout.layout_dialog_confirmar_rol, container, false)
    }

    /**
     * Método llamado después de crear la vista del diálogo.
     *
     * <p>Inicializa los componentes de la interfaz, configura el mensaje de advertencia
     * y registra los listeners de los botones y del campo de PIN.</p>
     *
     * @param view Vista raíz del diálogo.
     * @param savedInstanceState Estado previamente guardado de la instancia, o {@code null}.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        textoAdvertencia = view.findViewById(R.id.texto_advertencia)
        contenedorPin = view.findViewById(R.id.contenedor_pin)
        campoPin = view.findViewById(R.id.campo_pin)
        botonCancelar = view.findViewById(R.id.boton_cancelar)
        botonConfirmar = view.findViewById(R.id.boton_confirmar)

        textoAdvertencia.text = "¿Está seguro de promover a $usuarioNombre como Administrador? Esta acción otorga acceso total al panel de control."

        botonCancelar.setOnClickListener {
            dismiss()
        }

        botonConfirmar.setOnClickListener {
            validarYConfirmar()
        }

        campoPin.setOnEditorActionListener { _, _, _ ->
            validarYConfirmar()
            true
        }
    }

    /**
     * Valida el PIN ingresado y, si es correcto, promueve al usuario al rol de administrador.
     *
     * <p>Verifica que el PIN tenga entre 4 y 6 dígitos y que coincida con el PIN maestro
     * definido en [AdminViewModel]. Si la validación es exitosa, actualiza el rol del usuario
     * en Firestore y crea un registro en la subcolección «HistorialRoles» con los detalles
     * de la promoción. La operación se ejecuta en un hilo de fondo.</p>
     */
    private fun validarYConfirmar() {
        val pinIngresado = campoPin.text?.toString()?.trim().orEmpty()

        if (pinIngresado.length < 4 || pinIngresado.length > 6) {
            contenedorPin.error = "El PIN debe tener entre 4 y 6 dígitos"
            return
        }

        if (!viewModel.validarPin(pinIngresado)) {
            contenedorPin.error = "PIN incorrecto. Acceso denegado."
            campoPin.text?.clear()
            return
        }

        contenedorPin.error = null
        botonConfirmar.isEnabled = false
        botonConfirmar.text = "Procesando..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val adminId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                val ahora = System.currentTimeMillis()

                val lote = baseDatos.batch()

                val documentoUsuario = baseDatos.collection("Usuarios").document(usuarioId)
                lote.set(
                    documentoUsuario,
                    mapOf(
                        "rol" to AdminViewModel.ROL_ADMINISTRADOR,
                        "fechaActualizacion" to ahora,
                    ),
                    SetOptions.merge(),
                )

                val documentoHistorial = baseDatos.collection("Usuarios")
                    .document(usuarioId)
                    .collection("HistorialRoles")
                    .document()

                lote.set(
                    documentoHistorial,
                    mapOf(
                        "fecha" to ahora,
                        "autorizadoPor" to adminId,
                        "nuevoRol" to AdminViewModel.ROL_ADMINISTRADOR,
                        "estado" to "exitoso",
                        "rolAnterior" to usuarioRolActual,
                    ),
                )

                lote.commit().await()

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "✓ $usuarioNombre promovido a Administrador", Toast.LENGTH_LONG).show()
                    dismiss()
                }
            } catch (excepcion: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error al promover usuario: ${excepcion.message}", Toast.LENGTH_LONG).show()
                    botonConfirmar.isEnabled = true
                    botonConfirmar.text = "Confirmar Ascenso"
                }
            }
        }
    }

    /**
     * Método del ciclo de vida llamado cuando el diálogo se hace visible.
     * Ajusta el ancho del diálogo para que ocupe todo el ancho disponible.
     */
    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
}
