package com.example.rutaalmacen

import android.app.Application
import com.google.firebase.firestore.FirebaseFirestore

class RutaAlmacenApp : Application() {

    override fun onCreate() {
        super.onCreate()
        PalabrasBloqueadasStore.iniciar()
        GestorAnalisisIA.iniciar()
    }
}

object GestorAnalisisIA {
    private var motor: MotorAnalisisIA? = null

    fun iniciar() {
        if (motor != null) return
        val firestore = FirebaseFirestore.getInstance()
        val nuevoMotor = MotorAnalisisIA(firestore)
        nuevoMotor.registrarObservador(ObservadorNotificacionesIA(firestore))
        nuevoMotor.iniciar()
        motor = nuevoMotor
    }
}
