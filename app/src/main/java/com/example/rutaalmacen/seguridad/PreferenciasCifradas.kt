package com.example.rutaalmacen.seguridad

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object PreferenciasCifradas {

    private val masterKey by lazy {
        MasterKey.Builder(contexto)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private lateinit var contexto: Context

    fun inicializar(context: Context) {
        contexto = context.applicationContext
    }

    fun crear(nombre: String): SharedPreferences {
        return EncryptedSharedPreferences.create(
            contexto,
            nombre,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
