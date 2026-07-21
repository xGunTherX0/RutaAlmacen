package com.example.rutaalmacen.seguridad

import android.util.Log
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await

object ConfiguracionAdmin {

    private const val TAG = "ConfiguracionAdmin"
    private const val COLECCION_CONFIG = "Configuracion"
    private const val DOC_ADMIN = "admin"
    private const val CORREO_FALLBACK = "carloscancino010@gmail.com"

    private var correoCache: String? = null

    suspend fun obtenerCorreoAdmin(): String {
        correoCache?.let { return it }

        return try {
            val doc = Firebase.firestore.collection(COLECCION_CONFIG)
                .document(DOC_ADMIN)
                .get()
                .await()

            val correo = doc.getString("correo")
            if (!correo.isNullOrBlank()) {
                correoCache = correo
                correo
            } else {
                Log.w(TAG, "No se encontró correo admin en Firestore, usando fallback")
                CORREO_FALLBACK
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo correo admin: ${e.message}")
            CORREO_FALLBACK
        }
    }
}
