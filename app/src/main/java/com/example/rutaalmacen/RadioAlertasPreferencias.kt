package com.example.rutaalmacen

import android.content.Context
import com.example.rutaalmacen.seguridad.PreferenciasCifradas

object RadioAlertasPreferencias {

    private const val ARCHIVO = "preferencias_alertas"
    private const val CLAVE_RADIO_KM = "radio_alertas_km"
    private const val CLAVE_RADIO_METROS_VIEJA = "radio_alertas_metros"

    const val RADIO_POR_DEFECTO = 0.1
    const val RADIO_MIN_KM = 0.1
    const val RADIO_MAX_KM = 5.0
    const val SIN_LIMITE_METROS = 5_000.0

    fun obtener(context: Context): Double {
        val prefs = PreferenciasCifradas.crear(ARCHIVO)
        val guardadoNuevo = prefs.getFloat(CLAVE_RADIO_KM, -1f)
        if (guardadoNuevo > 0f) return guardadoNuevo.toDouble().coerceIn(RADIO_MIN_KM, RADIO_MAX_KM)
        val guardadoViejo = prefs.getFloat(CLAVE_RADIO_METROS_VIEJA, -1f)
        if (guardadoViejo > 0f) {
            prefs.edit().remove(CLAVE_RADIO_METROS_VIEJA).apply()
        }
        return RADIO_POR_DEFECTO
    }

    fun guardar(context: Context, radioKm: Double) {
        val prefs = PreferenciasCifradas.crear(ARCHIVO)
        prefs.edit()
            .putFloat(CLAVE_RADIO_KM, radioKm.coerceIn(RADIO_MIN_KM, RADIO_MAX_KM).toFloat())
            .apply()
    }
}
