package com.example.rutaalmacen

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private val correoAdministrador = "carloscancino010@gmail.com"
    private val coleccionUsuarios = "Usuarios"
    private val rolAdministrador = "administrador"
    private val rolVendedor = "vendedor"
    private val rolComprador = "comprador"

    private val mensajeRegistrarPrimero = "Por favor, usa el botón de registrarse primero"

    private val autenticacion: FirebaseAuth by lazy { Firebase.auth }
    private val baseDatos by lazy { Firebase.firestore }

    private lateinit var clienteGoogle: GoogleSignInClient
    private lateinit var lanzadorInicioSesionGoogle: ActivityResultLauncher<Intent>

    private var flujoActual = FlujoInicioSesion.ACCESO_DIRECTO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

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

    private fun crearClienteGoogle(): GoogleSignInClient {
        val configuracionGoogle = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(this, configuracionGoogle)
    }

    private fun iniciarSesionGoogle(flujoInicioSesion: FlujoInicioSesion) {
        flujoActual = flujoInicioSesion
        autenticacion.signOut()
        clienteGoogle.signOut().addOnCompleteListener {
            lanzadorInicioSesionGoogle.launch(clienteGoogle.signInIntent)
        }
    }

    private fun procesarResultadoGoogle(codigoResultado: Int, datos: Intent?) {
        if (codigoResultado != RESULT_OK) {
            if (flujoActual == FlujoInicioSesion.ACCESO_DIRECTO) {
                mostrarMensaje("Inicio de sesión cancelado")
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
            mostrarMensaje("No se pudo iniciar sesión con Google")
        }
    }

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
                if (correoUsuario == correoAdministrador.lowercase()) {
                    guardarUsuario(usuarioActual, rolAdministrador)
                    navegarSegunRol(rolAdministrador)
                    return@launch
                }

                when (flujoActual) {
                    FlujoInicioSesion.ACCESO_DIRECTO -> {
                        val rol = verificarUsuario(usuarioActual.uid)
                        if (rol.isNullOrBlank()) {
                            mostrarMensaje(mensajeRegistrarPrimero)
                            cerrarSesionTemporal()
                            return@launch
                        }
                        navegarSegunRol(rol)
                    }

                    FlujoInicioSesion.REGISTRO -> {
                        val rolExistente = verificarUsuario(usuarioActual.uid)
                        if (!rolExistente.isNullOrBlank()) {
                            mostrarMensaje("Ya tienes una cuenta registrada como $rolExistente")
                            navegarSegunRol(rolExistente)
                        } else {
                            mostrarSelectorDeRol(usuarioActual)
                        }
                    }
                }
            } catch (excepcion: Exception) {
                mostrarMensaje("No se pudo completar el acceso")
                cerrarSesionTemporal()
            }
        }
    }

    private fun mostrarSelectorDeRol(usuarioActual: FirebaseUser) {
        val opciones = arrayOf("Soy Comprador", "Soy Vendedor")
        MaterialAlertDialogBuilder(this)
            .setTitle("Selecciona tu rol")
            .setItems(opciones) { _, posicion ->
                val rolSeleccionado = if (posicion == 0) rolComprador else rolVendedor
                lifecycleScope.launch {
                    guardarUsuario(usuarioActual, rolSeleccionado)
                    navegarSegunRol(rolSeleccionado)
                }
            }
            .setCancelable(false)
            .show()
    }

    private suspend fun verificarUsuario(uid: String): String? {
        val documentoUsuario = baseDatos.collection(coleccionUsuarios).document(uid).get().await()
        if (!documentoUsuario.exists()) {
            return null
        }
        return documentoUsuario.getString("rol")?.lowercase()
    }

    private suspend fun guardarUsuario(
        usuarioActual: FirebaseUser,
        rol: String,
    ) {
        val nombreUsuario = usuarioActual.displayName
            ?.takeIf { it.isNotBlank() }
            ?: usuarioActual.email?.substringBefore("@")
            ?: "Usuario"

        val datosUsuario = mapOf(
            "nombre" to nombreUsuario,
            "correo" to usuarioActual.email.orEmpty(),
            "rol" to rol,
        )

        baseDatos.collection(coleccionUsuarios)
            .document(usuarioActual.uid)
            .set(datosUsuario)
            .await()
    }

    private fun navegarSegunRol(rol: String) {
        val destino = when (rol.lowercase()) {
            rolAdministrador -> AdminActivity::class.java
            rolVendedor -> VendedorActivity::class.java
            rolComprador -> CompradorActivity::class.java
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

    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }

    private suspend fun cerrarSesionTemporal() {
        autenticacion.signOut()
        if (::clienteGoogle.isInitialized) {
            clienteGoogle.signOut()
        }
    }

    private enum class FlujoInicioSesion {
        ACCESO_DIRECTO,
        REGISTRO,
    }
}
