package com.example.rutaalmacen.admin

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth

/**
 * Modelo de vista que gestiona la lógica de autenticación y roles de administrador.
 *
 * <p>Proporciona métodos para verificar si el usuario actual es el superadministrador,
 * validar el PIN maestro de seguridad y exponer constantes con los roles disponibles
 * en el sistema.</p>
 */
class AdminViewModel : ViewModel() {

    /** Objeto compañero que contiene las constantes de roles y credenciales maestras. */
    companion object {
        /** UID del superadministrador en Firebase Authentication. */
        const val SUPER_ADMIN_UID = "TU_SUPER_ADMIN_UID_AQUI"
        /** PIN maestro de seguridad utilizado para operaciones administrativas sensibles. */
        const val PIN_MAESTRO = "6666"
        /** Valor del rol de administrador en Firestore. */
        const val ROL_ADMINISTRADOR = "administrador"
        /** Valor del rol de vendedor en Firestore. */
        const val ROL_VENDEDOR = "vendedor"
        /** Valor del rol de comprador en Firestore. */
        const val ROL_COMPRADOR = "comprador"
    }

    /** LiveData interno que indica si el usuario actual es superadministrador. */
    private val _isSuperAdmin = MutableLiveData<Boolean>()
    /** LiveData público que expone si el usuario actual es superadministrador. */
    val isSuperAdmin: LiveData<Boolean> = _isSuperAdmin

    /**
     * Verifica si el UID del usuario autenticado coincide con el del superadministrador
     * y actualiza el valor de [isSuperAdmin] en consecuencia.
     */
    fun verificarSuperAdmin() {
        val uidActual = FirebaseAuth.getInstance().currentUser?.uid
        _isSuperAdmin.value = uidActual == SUPER_ADMIN_UID
    }

    /**
     * Consulta de forma síncrona si el usuario autenticado actual es el superadministrador.
     *
     * @return {@code true} si el UID del usuario actual coincide con [SUPER_ADMIN_UID],
     *         {@code false} en caso contrario.
     */
    fun esSuperAdmin(): Boolean {
        val uidActual = FirebaseAuth.getInstance().currentUser?.uid
        return uidActual == SUPER_ADMIN_UID
    }

    /**
     * Valida si el PIN ingresado coincide con el PIN maestro de seguridad.
     *
     * @param pinIngresado PIN proporcionado por el administrador.
     * @return {@code true} si el PIN es correcto, {@code false} en caso contrario.
     */
    fun validarPin(pinIngresado: String): Boolean {
        return pinIngresado == PIN_MAESTRO
    }
}
