package com.example.rutaalmacen

import android.app.Application
import com.example.rutaalmacen.seguridad.PreferenciasCifradas

class RutaAlmacenApp : Application() {

    override fun onCreate() {
        super.onCreate()
        PreferenciasCifradas.inicializar(this)
        PalabrasBloqueadasStore.iniciar()
    }
}
