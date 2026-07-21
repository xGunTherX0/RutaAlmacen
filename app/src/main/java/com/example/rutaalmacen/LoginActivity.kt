package com.example.rutaalmacen

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.rutaalmacen.seguridad.ConfiguracionAdmin
import com.example.rutaalmacen.seguridad.ConsentimientoPrivacidad
import com.example.rutaalmacen.seguridad.DetectorDepuracion
import com.example.rutaalmacen.seguridad.VerificadorIntegridad
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private var correoAdministrador = "carloscancino010@gmail.com"
    private val mensajeRegistrarPrimero = "Por favor, usa el botón de registrarse primero"
    private val autenticacion: FirebaseAuth by lazy { Firebase.auth }
    private val baseDatos by lazy { Firebase.firestore }
    private lateinit var clienteGoogle: GoogleSignInClient
    private lateinit var lanzadorInicioSesionGoogle: ActivityResultLauncher<Intent>
    private var flujoActual = FlujoInicioSesion.ACCESO_DIRECTO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_login)

        verificarSeguridadDispositivo()

        if (!ConsentimientoPrivacidad.fueAceptado(this)) {
            mostrarDialogoConsentimiento()
        } else {
            inicializarLogin()
        }
    }

    private fun inicializarLogin() {
        lifecycleScope.launch {
            correoAdministrador = ConfiguracionAdmin.obtenerCorreoAdmin()
            verificarIntegridadApp()
        }

        clienteGoogle = crearClienteGoogle()
        lanzadorInicioSesionGoogle =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
                procesarResultadoGoogle(resultado.resultCode, resultado.data)
            }

        findViewById<Button>(R.id.boton_google).setOnClickListener {
            iniciarSesionGoogle(FlujoInicioSesion.ACCESO_DIRECTO)
        }

        findViewById<Button>(R.id.boton_registrar).setOnClickListener {
            iniciarSesionGoogle(FlujoInicioSesion.REGISTRO)
        }
    }

    private fun mostrarDialogoConsentimiento() {
        val mensaje = """
            Al utilizar RutaAlmacén, aceptas nuestra Política de Privacidad y Términos y Condiciones.
            
            Recopilamos los siguientes datos:
            • Nombre, correo y foto (de tu cuenta Google)
            • Ubicación (solo cuando buscas almacenes cercanos)
            • Cámara y micrófono (solo para entrada de datos por voz/OCR)
            • Datos de inventario y productos
            
            Puedes ejercer tus derechos ARCO-PD (Acceso, Rectificación, Cancelación, Oposición) en cualquier momento.
            
            Más información en: docs/POLITICA_PRIVACIDAD.md
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("Privacidad y Términos")
            .setMessage(mensaje)
            .setCancelable(false)
            .setPositiveButton("Aceptar") { _, _ ->
                ConsentimientoPrivacidad.aceptar(this)
                inicializarLogin()
            }
            .setNegativeButton("Rechazar") { _, _ ->
                finish()
            }
            .show()
    }

    private fun verificarSeguridadDispositivo() {
        val resultado = DetectorDepuracion.esDispositivoSeguro(this)
        if (!resultado.seguro) {
            val motivos = buildString {
                if (resultado.esDebuggable) append("App en modo debug. ")
                if (resultado.debuggerConectado) append("Debugger conectado. ")
                if (resultado.esRoot) append("Dispositivo rooteado. ")
                if (resultado.esEmulador) append("Emulador detectado. ")
            }
            Log.w("LoginActivity", "Dispositivo inseguro: $motivos")
            
            MaterialAlertDialogBuilder(this)
                .setTitle("Dispositivo no seguro")
                .setMessage("Se detectaron condiciones de seguridad que impiden el acceso:\n$motivos\n\nPor favor, usa un dispositivo seguro para continuar.")
                .setCancelable(false)
                .setPositiveButton("Salir") { _, _ ->
                    finish()
                }
                .show()
        }
    }

    private suspend fun verificarIntegridadApp() {
        val resultado = VerificadorIntegridad.verificar(this)
        if (!resultado.integridadOk) {
            Log.w("LoginActivity", "Verificación de integridad falló: ${resultado.motivo}")
        }
    }

    /**
     * Crea y configura el cliente de Google Sign-In con el identificador
     * de cliente web de Firebase y la solicitud de correo electrónico.
     *
     * @return Instancia configurada de [GoogleSignInClient].
     */
    private fun crearClienteGoogle(): GoogleSignInClient {
        val configuracionGoogle = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(this, configuracionGoogle)
    }

    /**
     * Inicia el flujo de autenticación con Google tras verificar la disponibilidad
     * de Google Play Services y la configuración del cliente web.
     *
     * Cierra cualquier sesión previa antes de lanzar el intent de Google Sign-In
     * para garantizar un inicio limpio.
     *
     * @param flujoInicioSesion Tipo de flujo ([FlujoInicioSesion.ACCESO_DIRECTO] o [FlujoInicioSesion.REGISTRO]).
     */
    private fun iniciarSesionGoogle(flujoInicioSesion: FlujoInicioSesion) {
        if (!serviciosGoogleDisponibles()) {
            return
        }
        if (!clienteWebConfigurado()) {
            return
        }
        flujoActual = flujoInicioSesion
        autenticacion.signOut()
        clienteGoogle.signOut().addOnCompleteListener {
            lanzadorInicioSesionGoogle.launch(clienteGoogle.signInIntent)
        }
    }

    /**
     * Procesa el resultado devuelto por la actividad de Google Sign-In.
     *
     * Extrae la cuenta de Google del intent de resultado, valida el token de identidad
     * y delega la autenticación en [autenticarConFirebase]. En caso de error o cancelación,
     * muestra un mensaje al usuario y limpia la sesión temporal.
     *
     * @param codigoResultado Código de resultado de la actividad ([RESULT_OK] si fue exitosa).
     * @param datos Intent con los datos devueltos por Google Sign-In, o `null` si no se recibió información.
     */
    private fun procesarResultadoGoogle(codigoResultado: Int, datos: Intent?) {
        if (codigoResultado != RESULT_OK) {
            if (flujoActual == FlujoInicioSesion.ACCESO_DIRECTO) {
                mostrarMensaje("Inicio de sesión cancelado")
            }
            return
        }

        if (datos == null) {
            mostrarMensaje("No se recibió información de Google")
            lifecycleScope.launch {
                cerrarSesionTemporal()
            }
            return
        }

        val tareaCuenta = GoogleSignIn.getSignedInAccountFromIntent(datos)
        try {
            val cuentaGoogle = tareaCuenta.getResult(ApiException::class.java)
            if (cuentaGoogle.idToken.isNullOrBlank()) {
                mostrarMensaje("No se pudo obtener el token de Google")
                lifecycleScope.launch {
                    cerrarSesionTemporal()
                }
                return
            }
            autenticarConFirebase(cuentaGoogle)
        } catch (excepcion: ApiException) {
            mostrarMensaje(mensajeErrorGoogle(excepcion.statusCode))
            lifecycleScope.launch {
                cerrarSesionTemporal()
            }
        }
    }

    /**
     * Autentica al usuario en Firebase utilizando las credenciales de la cuenta de Google.
     *
     * Tras iniciar sesión con el proveedor de Google, verifica si el correo corresponde
     * al administrador, si el usuario ya existe en la base de datos (acceso directo)
     * o si es necesario registrar un nuevo rol. También comprueba si la cuenta está
     * bloqueada antes de permitir el acceso.
     *
     * @param cuentaGoogle Cuenta de Google autenticada con el token de identidad válido.
     */
    private fun autenticarConFirebase(cuentaGoogle: GoogleSignInAccount) {
        lifecycleScope.launch {
            try {
                val credencial = GoogleAuthProvider.getCredential(cuentaGoogle.idToken, null)
                autenticacion.signInWithCredential(credencial).await()

                val usuarioActual = autenticacion.currentUser
                if (usuarioActual == null) {
                    mostrarMensaje("No fue posible obtener el usuario autenticado")
                    cerrarSesionTemporal()
                    return@launch
                }

                val correoUsuario = usuarioActual.email.orEmpty().lowercase()
                val fotoUrl = cuentaGoogle.photoUrl?.toString().orEmpty()
                
                if (correoUsuario == correoAdministrador.lowercase()) {
                    guardarUsuario(usuarioActual, Constantes.ROL_ADMINISTRADOR, fotoUrl)
                    navegarSegunRol(Constantes.ROL_ADMINISTRADOR)
                    return@launch
                }

                when (flujoActual) {
                    FlujoInicioSesion.ACCESO_DIRECTO -> {
                        val datos = verificarUsuario(usuarioActual.uid)
                        if (datos == null) {
                            mostrarMensaje(mensajeRegistrarPrimero)
                            cerrarSesionTemporal()
                            return@launch
                        }
                        if (datos.bloqueado) {
                            mostrarMensaje("Tu cuenta ha sido bloqueada por actividad inapropiada.")
                            cerrarSesionTemporal()
                            return@launch
                        }
                        actualizarUltimoLogin(usuarioActual.uid)
                        navegarSegunRol(datos.rol)
                    }

                    FlujoInicioSesion.REGISTRO -> {
                        val datos = verificarUsuario(usuarioActual.uid)
                        if (datos != null) {
                            if (datos.bloqueado) {
                                mostrarMensaje("Tu cuenta ha sido bloqueada por actividad inapropiada.")
                                cerrarSesionTemporal()
                                return@launch
                            }
                            mostrarMensaje("Ya tienes una cuenta registrada como ${datos.rol}")
                            actualizarUltimoLogin(usuarioActual.uid)
                            navegarSegunRol(datos.rol)
                        } else {
                            mostrarSelectorDeRol(usuarioActual, fotoUrl)
                        }
                    }
                }
            } catch (excepcion: Exception) {
                mostrarMensaje("No se pudo completar el acceso")
                cerrarSesionTemporal()
            }
        }
    }

    /**
     * Muestra un diálogo para que el nuevo usuario seleccione su rol
     * dentro de la aplicación (comprador o vendedor).
     *
     * Una vez seleccionado el rol, guarda los datos del usuario en Firestore
     * y navega a la actividad correspondiente.
     *
     * @param usuarioActual Usuario autenticado en Firebase cuyo rol se va a asignar.
     * @param fotoUrl URL de la foto de perfil del usuario, puede estar vacía.
     */
    private fun mostrarSelectorDeRol(usuarioActual: FirebaseUser, fotoUrl: String) {
        val opciones = arrayOf("Soy Comprador", "Soy Vendedor")
        MaterialAlertDialogBuilder(this)
            .setTitle("Selecciona tu rol")
            .setItems(opciones) { _, posicion ->
                val rolSeleccionado = if (posicion == 0) Constantes.ROL_COMPRADOR else Constantes.ROL_VENDEDOR
                lifecycleScope.launch {
                    guardarUsuario(usuarioActual, rolSeleccionado, fotoUrl)
                    navegarSegunRol(rolSeleccionado)
                }
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Representa los datos esenciales de un usuario almacenados en Firestore.
     *
     * @property rol Rol asignado al usuario (comprador, vendedor o administrador).
     * @property bloqueado Indica si la cuenta del usuario ha sido bloqueada por un administrador.
     */
    private data class DatosUsuario(val rol: String, val bloqueado: Boolean)

    /**
     * Consulta Firestore para verificar si un usuario existe y obtener su rol
     * y estado de bloqueo.
     *
     * @param uid Identificador único del usuario en Firebase Authentication.
     * @return [DatosUsuario] con el rol y estado de bloqueo, o `null` si el usuario no existe.
     */
    private suspend fun verificarUsuario(uid: String): DatosUsuario? {
        val documentoUsuario = baseDatos.collection(Constantes.COLECCION_USUARIOS).document(uid).get().await()
        if (!documentoUsuario.exists()) {
            return null
        }
        val rol = documentoUsuario.getString("rol")?.lowercase() ?: return null
        val bloqueado = documentoUsuario.getBoolean("bloqueado") ?: false
        return DatosUsuario(rol, bloqueado)
    }

    /**
     * Guarda o actualiza los datos del usuario en la colección de usuarios de Firestore.
     *
     * Si el documento no existe previamente, añade el campo `fechaCreacion`.
     * Utiliza combinación (`merge`) para no sobrescribir datos existentes.
     *
     * @param usuarioActual Usuario autenticado en Firebase del cual se extraen nombre y correo.
     * @param rol Rol asignado al usuario (comprador, vendedor o administrador).
     * @param fotoUrl URL de la foto de perfil del usuario; se omite si está vacía.
     */
    private suspend fun guardarUsuario(
        usuarioActual: FirebaseUser,
        rol: String,
        fotoUrl: String = "",
    ) {
        val nombreUsuario = usuarioActual.displayName
            ?.takeIf { it.isNotBlank() }
            ?: usuarioActual.email?.substringBefore("@")
            ?: "Usuario"

        val documento = baseDatos.collection(Constantes.COLECCION_USUARIOS)
            .document(usuarioActual.uid)
            .get()
            .await()

        val datosUsuario = mutableMapOf<String, Any>(
            "nombre" to nombreUsuario,
            "correo" to usuarioActual.email.orEmpty(),
            "rol" to rol,
            "ultimoLogin" to FieldValue.serverTimestamp(),
        )
        
        if (fotoUrl.isNotBlank()) {
            datosUsuario["fotoUrl"] = fotoUrl
        }
        
        if (!documento.exists()) {
            datosUsuario["fechaCreacion"] = FieldValue.serverTimestamp()
        }

        baseDatos.collection(Constantes.COLECCION_USUARIOS)
            .document(usuarioActual.uid)
            .set(datosUsuario, SetOptions.merge())
            .await()
    }

    /**
     * Actualiza la marca de tiempo del último inicio de sesión del usuario en Firestore.
     *
     * @param uid Identificador único del usuario cuyo registro se actualizará.
     */
    private suspend fun actualizarUltimoLogin(uid: String) {
        val datos = mapOf("ultimoLogin" to FieldValue.serverTimestamp())
        baseDatos.collection(Constantes.COLECCION_USUARIOS)
            .document(uid)
            .set(datos, SetOptions.merge())
            .await()
    }

    /**
     * Navega a la actividad correspondiente según el rol del usuario autenticado.
     *
     * - Administrador: [AdminActivity]
     * - Vendedor: [VendedorActivity]
     * - Comprador: [AlmacenesCercanosActivity]
     *
     * Si el rol no es reconocido, muestra un mensaje y cierra la sesión temporal.
     *
     * @param rol Cadena que representa el rol del usuario.
     */
    private fun navegarSegunRol(rol: String) {
        val destino = when (rol.lowercase()) {
            Constantes.ROL_ADMINISTRADOR -> AdminActivity::class.java
            Constantes.ROL_VENDEDOR -> VendedorActivity::class.java
            Constantes.ROL_COMPRADOR -> AlmacenesCercanosActivity::class.java
            else -> null
        }

        if (destino == null) {
            mostrarMensaje("Rol no reconocido")
            lifecycleScope.launch {
                cerrarSesionTemporal()
            }
            return
        }

        startActivity(Intent(this, destino))
        finish()
    }

    /**
     * Muestra un mensaje breve al usuario mediante un [android.widget.Toast].
     *
     * @param mensaje Texto que se desplegará en pantalla.
     */
    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }

    /**
     * Verifica si los servicios de Google Play están disponibles en el dispositivo.
     *
     * Muestra un mensaje al usuario si los servicios no están disponibles.
     *
     * @return `true` si Google Play Services está disponible, `false` en caso contrario.
     */
    private fun serviciosGoogleDisponibles(): Boolean {
        val disponibilidad = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(this)
        if (disponibilidad == ConnectionResult.SUCCESS) {
            return true
        }
        mostrarMensaje("Google Play Services no está disponible en este dispositivo")
        return false
    }

    /**
     * Comprueba que el identificador de cliente web de Firebase esté configurado
     * en los recursos de la aplicación.
     *
     * @return `true` si el cliente web está configurado correctamente, `false` si está vacío.
     */
    private fun clienteWebConfigurado(): Boolean {
        val clienteWeb = getString(R.string.default_web_client_id)
        if (clienteWeb.isBlank()) {
            mostrarMensaje("Falta configurar el cliente web de Firebase")
            return false
        }
        return true
    }

    /**
     * Convierte un código de error de Google Sign-In en un mensaje legible para el usuario.
     *
     * @param codigo Código numérico de error devuelto por [ApiException].
     * @return Mensaje descriptivo en español asociado al código de error.
     */
    private fun mensajeErrorGoogle(codigo: Int): String {
        return when (codigo) {
            GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> "Inicio de sesión cancelado"
            GoogleSignInStatusCodes.SIGN_IN_REQUIRED ->
                "Necesitas iniciar sesión con una cuenta Google en el dispositivo"
            GoogleSignInStatusCodes.NETWORK_ERROR -> "No hay conexión a internet"
            GoogleSignInStatusCodes.DEVELOPER_ERROR ->
                "Configuración de Google incorrecta (revisa el SHA-1 en Firebase)"
            else -> "No se pudo iniciar sesión con Google"
        }
    }

    /**
     * Cierra la sesión tanto en Firebase Authentication como en el cliente de Google Sign-In.
     *
     * Se invoca tras fallos de autenticación para garantizar que no queden sesiones parciales.
     */
    private suspend fun cerrarSesionTemporal() {
        autenticacion.signOut()
        if (::clienteGoogle.isInitialized) {
            clienteGoogle.signOut()
        }
    }

    /**
     * Define los posibles flujos de inicio de sesión disponibles en la pantalla de acceso.
     */
    private enum class FlujoInicioSesion {
        /** El usuario ya tiene cuenta e intenta acceder directamente. */
        ACCESO_DIRECTO,
        /** El usuario es nuevo y desea registrarse en la aplicación. */
        REGISTRO,
    }
}
