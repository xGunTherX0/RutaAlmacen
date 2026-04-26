package com.example.rutaalmacen

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class MotorAnalisisIA(
    private val baseDatos: FirebaseFirestore,
) {

    interface ObservadorDemandaIA {
        suspend fun onDemandaDetectada(demanda: DemandaInsatisfecha)
    }

    data class DemandaInsatisfecha(
        val id: String,
        val producto: String,
        val productoNormalizado: String,
        val latitudCentro: Double,
        val longitudCentro: Double,
        val radioMetros: Double,
        val totalBusquedas: Int,
        val fechaCreacion: Long,
    )

    private val observadores = mutableSetOf<ObservadorDemandaIA>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listener: ListenerRegistration? = null
    private val demandasEmitidas = mutableSetOf<String>()

    fun registrarObservador(observador: ObservadorDemandaIA) {
        observadores.add(observador)
    }

    fun removerObservador(observador: ObservadorDemandaIA) {
        observadores.remove(observador)
    }

    fun iniciar() {
        if (listener != null) return
        listener = baseDatos.collection(COLECCION_BUSQUEDAS)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    return@addSnapshotListener
                }
                scope.launch {
                    analizarDemandas(snapshot.documents)
                }
            }
    }

    fun detener() {
        listener?.remove()
        listener = null
    }

    private suspend fun analizarDemandas(documentos: List<DocumentSnapshot>) {
        val fallidas = documentos.mapNotNull { documento ->
            val resultadoExitoso = documento.getBoolean("resultadoExitoso") ?: true
            if (resultadoExitoso) return@mapNotNull null
            val nombreProducto = documento.getString("nombreProducto").orEmpty().trim()
            val latitud = documento.getDouble("latitud")
            val longitud = documento.getDouble("longitud")
            if (nombreProducto.isBlank() || latitud == null || longitud == null) {
                return@mapNotNull null
            }
            val validacion = FiltroContenido.validarNombreProducto(nombreProducto)
            if (!validacion.esValido) return@mapNotNull null
            RegistroBusqueda(
                producto = nombreProducto,
                productoNormalizado = FiltroContenido.normalizar(nombreProducto),
                latitud = latitud,
                longitud = longitud,
            )
        }

        val grupos = fallidas.groupBy { it.productoNormalizado }
        grupos.forEach { (_, registros) ->
            if (registros.size < MINIMO_BUSQUEDAS) return@forEach
            val latitudCentro = registros.map { it.latitud }.average()
            val longitudCentro = registros.map { it.longitud }.average()
            val radioMetros = registros.maxOf { registro ->
                distanciaMetros(latitudCentro, longitudCentro, registro.latitud, registro.longitud)
            }

            val producto = registros.first().producto
            val productoNormalizado = registros.first().productoNormalizado
            val idDemanda = construirIdDemanda(
                productoNormalizado,
                latitudCentro,
                longitudCentro,
                radioMetros,
            )

            if (!demandasEmitidas.add(idDemanda)) return@forEach

            val demanda = DemandaInsatisfecha(
                id = idDemanda,
                producto = producto,
                productoNormalizado = productoNormalizado,
                latitudCentro = latitudCentro,
                longitudCentro = longitudCentro,
                radioMetros = radioMetros,
                totalBusquedas = registros.size,
                fechaCreacion = System.currentTimeMillis(),
            )

            notificarObservadores(demanda)
        }
    }

    private suspend fun notificarObservadores(demanda: DemandaInsatisfecha) {
        observadores.forEach { observador ->
            runCatching { observador.onDemandaDetectada(demanda) }
        }
    }

    private fun construirIdDemanda(
        productoNormalizado: String,
        latitudCentro: Double,
        longitudCentro: Double,
        radioMetros: Double,
    ): String {
        val latitud = (latitudCentro * 1000).roundToInt()
        val longitud = (longitudCentro * 1000).roundToInt()
        val radio = radioMetros.roundToInt()
        return "${productoNormalizado}_${latitud}_${longitud}_$radio"
    }

    private fun distanciaMetros(
        latitud1: Double,
        longitud1: Double,
        latitud2: Double,
        longitud2: Double,
    ): Double {
        val radioTierra = 6371000.0
        val dLat = Math.toRadians(latitud2 - latitud1)
        val dLon = Math.toRadians(longitud2 - longitud1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(latitud1)) * cos(Math.toRadians(latitud2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return radioTierra * c
    }

    private data class RegistroBusqueda(
        val producto: String,
        val productoNormalizado: String,
        val latitud: Double,
        val longitud: Double,
    )

    companion object {
        private const val COLECCION_BUSQUEDAS = "Busquedas_Historicas"
        private const val MINIMO_BUSQUEDAS = 2
    }
}

class ObservadorNotificacionesIA(
    private val baseDatos: FirebaseFirestore,
) : MotorAnalisisIA.ObservadorDemandaIA {

    override suspend fun onDemandaDetectada(demanda: MotorAnalisisIA.DemandaInsatisfecha) {
        val mensaje = "¡Oportunidad! Hay alta demanda de ${demanda.producto} en tu zona " +
            "(aprox. ${formatearMetros(demanda.radioMetros)})."
        val datos = mapOf(
            "producto" to demanda.producto,
            "productoNormalizado" to demanda.productoNormalizado,
            "latitudCentro" to demanda.latitudCentro,
            "longitudCentro" to demanda.longitudCentro,
            "radioMetros" to demanda.radioMetros,
            "totalBusquedas" to demanda.totalBusquedas,
            "fechaCreacion" to demanda.fechaCreacion,
            "mensaje" to mensaje,
        )
        baseDatos.collection(COLECCION_NOTIFICACIONES)
            .document(demanda.id)
            .set(datos)
            .await()
    }

    private fun formatearMetros(metros: Double): String {
        return if (metros >= 1000) {
            val km = metros / 1000.0
            String.format(Locale.forLanguageTag("es-CL"), "%.1f km", km)
        } else {
            "${metros.roundToInt()} m"
        }
    }

    companion object {
        private const val COLECCION_NOTIFICACIONES = "Notificaciones_IA"
    }
}
