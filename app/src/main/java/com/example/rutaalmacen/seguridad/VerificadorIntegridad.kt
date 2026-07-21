package com.example.rutaalmacen.seguridad

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

object VerificadorIntegridad {

    private const val TAG = "VerificadorIntegridad"

    private const val PROJECT_NUMBER = 984484023806L

    suspend fun verificar(context: Context): ResultadoIntegridad {
        return try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return ResultadoIntegridad(
                integridadOk = false,
                motivo = "Sin usuario autenticado"
            )

            val manager = IntegrityManagerFactory.create(context)

            val tokenRequest = IntegrityTokenRequest.builder()
                .setCloudProjectNumber(PROJECT_NUMBER)
                .setNonce(uid)
                .build()

            val tokenResponse = manager.requestIntegrityToken(tokenRequest).await()
            val token = tokenResponse.token()

            Log.d(TAG, "Token de integridad obtenido correctamente")

            ResultadoIntegridad(
                integridadOk = true,
                token = token
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando integridad: ${e.message}")
            ResultadoIntegridad(
                integridadOk = false,
                motivo = e.message ?: "Error desconocido"
            )
        }
    }
}

data class ResultadoIntegridad(
    val integridadOk: Boolean,
    val token: String? = null,
    val motivo: String? = null
)
