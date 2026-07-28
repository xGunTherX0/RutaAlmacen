package com.example.rutaalmacen.seguridad

import android.content.Context
import android.util.Log

object VerificadorIntegridad {

    private const val TAG = "VerificadorIntegridad"

    suspend fun verificar(context: Context): ResultadoIntegridad {
        Log.d(TAG, "Verificación de integridad desactivada para desarrollo")
        return ResultadoIntegridad(
            integridadOk = true,
            token = null
        )
    }
}

data class ResultadoIntegridad(
    val integridadOk: Boolean,
    val token: String? = null,
    val motivo: String? = null
)
