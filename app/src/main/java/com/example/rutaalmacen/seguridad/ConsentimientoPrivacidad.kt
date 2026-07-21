package com.example.rutaalmacen.seguridad

import android.content.Context
import android.content.SharedPreferences

object ConsentimientoPrivacidad {

    private const val ARCHIVO = "consentimiento_privacidad"
    private const val CLAVE_ACEPTADO = "consentimiento_aceptado"
    private const val CLAVE_VERSION = "consentimiento_version"
    private const val CLAVE_TIMESTAMP = "consentimiento_timestamp"

    private const val VERSION_ACTUAL = 1

    private fun preferencias(context: Context): SharedPreferences {
        return context.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)
    }

    fun fueAceptado(context: Context): Boolean {
        val prefs = preferencias(context)
        val versionGuardada = prefs.getInt(CLAVE_VERSION, 0)
        return prefs.getBoolean(CLAVE_ACEPTADO, false) && versionGuardada >= VERSION_ACTUAL
    }

    fun aceptar(context: Context) {
        preferencias(context).edit().apply {
            putBoolean(CLAVE_ACEPTADO, true)
            putInt(CLAVE_VERSION, VERSION_ACTUAL)
            putLong(CLAVE_TIMESTAMP, System.currentTimeMillis())
            apply()
        }
    }

    fun revocar(context: Context) {
        preferencias(context).edit().apply {
            putBoolean(CLAVE_ACEPTADO, false)
            apply()
        }
    }
}
