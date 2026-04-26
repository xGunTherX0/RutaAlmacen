package com.example.rutaalmacen

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AlertasIAFragment : Fragment(R.layout.fragment_alertas_ia) {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val alertas: MutableList<AlertaIA> = mutableListOf()
    private lateinit var adaptador: AdaptadorAlertas
    private lateinit var textoSinAlertas: android.widget.TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler_alertas)
        textoSinAlertas = view.findViewById(R.id.texto_sin_alertas)

        adaptador = AdaptadorAlertas(alertas)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adaptador

        viewLifecycleOwner.lifecycleScope.launch { cargarAlertas() }
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch { cargarAlertas() }
    }

    private suspend fun cargarAlertas() {
        val usuario = autenticacion.currentUser ?: return
        try {
            val documentoUsuario = baseDatos.collection("Usuarios")
                .document(usuario.uid)
                .get()
                .await()
            val latitud = documentoUsuario.getDouble("latitud")
            val longitud = documentoUsuario.getDouble("longitud")

            val latitudUsuario = latitud
            val longitudUsuario = longitud

            val documentosAlertas = baseDatos.collection("Notificaciones_IA")
                .orderBy("fechaCreacion", Query.Direction.DESCENDING)
                .get()
                .await()
                .documents

            val limite = System.currentTimeMillis() - MILISEGUNDOS_SEMANA
            limpiarAlertasAntiguas(usuario.uid, documentosAlertas, limite)

            val nuevasAlertas = documentosAlertas.mapNotNull { documento ->
                val vendedorId = documento.getString("vendedorId")
                if (!vendedorId.isNullOrBlank() && vendedorId != usuario.uid) {
                    return@mapNotNull null
                }
                val producto = documento.getString("producto").orEmpty()
                if (!FiltroContenido.validarNombreProducto(producto).esValido) {
                    return@mapNotNull null
                }
                val latitudCentro = documento.getDouble("latitudCentro")
                val longitudCentro = documento.getDouble("longitudCentro")
                val radioMetros = documento.getDouble("radioMetros")
                val totalBusquedas = documento.getLong("totalBusquedas")?.toInt() ?: 0
                val fechaCreacion = documento.getLong("fechaCreacion") ?: 0L
                if (fechaCreacion < limite) {
                    return@mapNotNull null
                }
                val mensaje = documento.getString("mensaje").orEmpty()
                val distancia = if (
                    latitudUsuario != null &&
                    longitudUsuario != null &&
                    latitudCentro != null &&
                    longitudCentro != null
                ) {
                    distanciaMetros(latitudUsuario, longitudUsuario, latitudCentro, longitudCentro)
                } else {
                    null
                }
                AlertaIA(
                    producto = producto,
                    mensaje = mensaje,
                    totalBusquedas = totalBusquedas,
                    radioMetros = radioMetros,
                    distanciaMetros = distancia,
                    fechaCreacion = fechaCreacion,
                )
            }.sortedWith(
                compareByDescending<AlertaIA> { it.totalBusquedas }
                    .thenBy { it.distanciaMetros ?: Double.MAX_VALUE },
            )

            alertas.clear()
            alertas.addAll(nuevasAlertas)
            adaptador.notifyDataSetChanged()
            textoSinAlertas.visibility = if (alertas.isEmpty()) View.VISIBLE else View.GONE
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudieron cargar las alertas")
        }
    }

    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(requireContext(), mensaje, Toast.LENGTH_SHORT).show()
    }

    private fun normalizarTexto(texto: String): String {
        val limpio = texto.trim().lowercase(java.util.Locale.getDefault())
        val normalizado = java.text.Normalizer.normalize(limpio, java.text.Normalizer.Form.NFD)
        return normalizado.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
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

    private suspend fun limpiarAlertasAntiguas(
        usuarioId: String,
        documentos: List<DocumentSnapshot>,
        limite: Long,
    ) {
        val porEliminar = documentos.filter { documento ->
            val vendedorId = documento.getString("vendedorId")
            if (!vendedorId.isNullOrBlank() && vendedorId != usuarioId) {
                return@filter false
            }
            val fecha = documento.getLong("fechaCreacion") ?: 0L
            fecha < limite
        }
        if (porEliminar.isEmpty()) {
            return
        }
        porEliminar.chunked(450).forEach { loteDocs ->
            val lote = baseDatos.batch()
            loteDocs.forEach { documento ->
                lote.delete(documento.reference)
            }
            lote.commit().await()
        }
    }

    data class AlertaIA(
        val producto: String,
        val mensaje: String,
        val totalBusquedas: Int,
        val radioMetros: Double?,
        val distanciaMetros: Double?,
        val fechaCreacion: Long,
    )

    private companion object {
        private const val MILISEGUNDOS_SEMANA = 7L * 24 * 60 * 60 * 1000
    }
}

private class AdaptadorAlertas(
    private val alertas: List<AlertasIAFragment.AlertaIA>,
) : RecyclerView.Adapter<AdaptadorAlertas.VistaAlerta>() {

    class VistaAlerta(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textoProducto: android.widget.TextView = itemView.findViewById(R.id.texto_producto_alerta)
        val textoDetalle: android.widget.TextView = itemView.findViewById(R.id.texto_detalle_alerta)
        val textoMetrica: android.widget.TextView = itemView.findViewById(R.id.texto_metrica_alerta)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaAlerta {
        val vista = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alerta_ia, parent, false)
        return VistaAlerta(vista)
    }

    override fun onBindViewHolder(holder: VistaAlerta, position: Int) {
        val alerta = alertas[position]
        val textoSinRadio = "Sin radio"
        val textoSinUbicacion = "Sin ubicación"
        holder.textoProducto.text = alerta.producto
        holder.textoDetalle.text = alerta.mensaje
        holder.textoMetrica.text = "Búsquedas: ${alerta.totalBusquedas} • " +
            "Radio aprox: ${formatearMetrosNullable(alerta.radioMetros, textoSinRadio)} • " +
            "Distancia: ${formatearMetrosNullable(alerta.distanciaMetros, textoSinUbicacion)} • " +
            "Fecha: ${formatearFecha(alerta.fechaCreacion)}"
    }

    override fun getItemCount(): Int = alertas.size

    private fun formatearMetros(metros: Double): String {
        return if (metros >= 1000) {
            val km = metros / 1000.0
            String.format(java.util.Locale.forLanguageTag("es-CL"), "%.1f km", km)
        } else {
            "${metros.toInt()} m"
        }
    }

    private fun formatearMetrosNullable(metros: Double?, textoNulo: String): String {
        if (metros == null) {
            return textoNulo
        }
        return formatearMetros(metros)
    }

    private fun formatearFecha(millis: Long): String {
        if (millis <= 0L) return "Sin fecha"
        val formato = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
        return formato.format(java.util.Date(millis))
    }
}
