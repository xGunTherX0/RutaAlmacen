package com.example.rutaalmacen.admin

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rutaalmacen.Constantes
import com.example.rutaalmacen.R
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Locale

class AdminVerificacionActivity : AppCompatActivity() {

    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private lateinit var recyclerVerificaciones: RecyclerView
    private lateinit var textoContador: TextView
    private lateinit var textoSinSolicitudes: TextView

    private val solicitudes: MutableList<SolicitudVerificacion> = mutableListOf()
    private lateinit var adaptador: AdaptadorVerificacion

    private var listenerFirestore: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_verificacion)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_verificacion)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                barrasDelSistema.top,
                barrasDelSistema.right,
                barrasDelSistema.bottom,
            )
            insets
        }

        recyclerVerificaciones = findViewById(R.id.recycler_verificaciones)
        textoContador = findViewById(R.id.texto_contador_pendientes)
        textoSinSolicitudes = findViewById(R.id.texto_sin_solicitudes)

        adaptador = AdaptadorVerificacion(solicitudes) { solicitud ->
            val fechaTexto = solicitud.fechaRegistro?.toDate()?.let { fecha ->
                val formato = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.forLanguageTag("es-CL"))
                formato.format(fecha)
            }.orEmpty()

            val intent = Intent(this, DetalleVerificacionActivity::class.java).apply {
                putExtra("uid", solicitud.uid)
                putExtra("storeName", solicitud.storeName)
                putExtra("displayName", solicitud.displayName)
                putExtra("rut", solicitud.rut)
                putExtra("address", solicitud.address)
                putExtra("patentImageUrl", solicitud.patentImageUrl)
                putExtra("fechaRegistro", fechaTexto)
            }
            startActivity(intent)
        }

        recyclerVerificaciones.layoutManager = LinearLayoutManager(this)
        recyclerVerificaciones.adapter = adaptador
    }

    override fun onResume() {
        super.onResume()
        escucharSolicitudesPendientes()
    }

    override fun onPause() {
        super.onPause()
        listenerFirestore?.remove()
        listenerFirestore = null
    }

    private fun escucharSolicitudesPendientes() {
        listenerFirestore?.remove()

        listenerFirestore = baseDatos.collection(Constantes.COLECCION_USUARIOS)
            .whereEqualTo("rol", Constantes.ROL_VENDEDOR)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    return@addSnapshotListener
                }

                val todasLasSolicitudes = mutableListOf<SolicitudVerificacion>()

                snapshots?.documents?.forEach { documento ->
                    val sellerProfile = documento.get("sellerProfile") as? Map<*, *> ?: return@forEach
                    val status = sellerProfile["verificationStatus"] as? String

                    if (status == Constantes.EstadoVerificacionPendiente) {
                        val solicitud = SolicitudVerificacion(
                            uid = documento.id,
                            storeName = sellerProfile["storeName"] as? String ?: "",
                            displayName = documento.getString("displayName") ?: "",
                            rut = sellerProfile["rut"] as? String ?: "",
                            address = sellerProfile["address"] as? String ?: "",
                            patentImageUrl = sellerProfile["patentImageUrl"] as? String ?: "",
                            fechaRegistro = sellerProfile["createdAt"] as? Timestamp
                        )
                        todasLasSolicitudes.add(solicitud)
                    }
                }

                solicitudes.clear()
                solicitudes.addAll(todasLasSolicitudes.sortedByDescending { it.fechaRegistro })

                runOnUiThread {
                    adaptador.notifyDataSetChanged()
                    textoContador.text = "${solicitudes.size} pendiente(s)"
                    textoSinSolicitudes.visibility = if (solicitudes.isEmpty()) View.VISIBLE else View.GONE
                }
            }
    }

    data class SolicitudVerificacion(
        val uid: String,
        val storeName: String,
        val displayName: String,
        val rut: String,
        val address: String,
        val patentImageUrl: String,
        val fechaRegistro: Timestamp?
    )

    class AdaptadorVerificacion(
        private val solicitudes: List<SolicitudVerificacion>,
        private val onSolicitudClick: (SolicitudVerificacion) -> Unit
    ) : RecyclerView.Adapter<AdaptadorVerificacion.VistaSolicitud>() {

        class VistaSolicitud(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tarjeta: View = itemView.findViewById(R.id.tarjeta_solicitud)
            val textoNombreAlmacen: TextView = itemView.findViewById(R.id.texto_nombre_almacen)
            val textoSolicitante: TextView = itemView.findViewById(R.id.texto_nombre_solicitante)
            val textoRut: TextView = itemView.findViewById(R.id.texto_rut)
            val textoDireccion: TextView = itemView.findViewById(R.id.texto_direccion)
            val textoFechaRegistro: TextView = itemView.findViewById(R.id.texto_fecha_registro)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VistaSolicitud {
            val vista = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_lista_verificacion, parent, false)
            return VistaSolicitud(vista)
        }

        override fun onBindViewHolder(holder: VistaSolicitud, position: Int) {
            val solicitud = solicitudes[position]

            holder.textoNombreAlmacen.text = solicitud.storeName.ifBlank { "Sin nombre" }
            holder.textoSolicitante.text = "Solicitante: ${solicitud.displayName.ifBlank { "Sin nombre" }}"
            holder.textoRut.text = "RUT: ${solicitud.rut.ifBlank { "No registrado" }}"
            holder.textoDireccion.text = "Dirección: ${solicitud.address.ifBlank { "No registrada" }}"

            val fecha = solicitud.fechaRegistro?.toDate()
            holder.textoFechaRegistro.text = if (fecha != null) {
                val formato = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("es-CL"))
                "Registrado: ${formato.format(fecha)}"
            } else {
                "Registrado: sin fecha"
            }

            holder.tarjeta.setOnClickListener { 
                onSolicitudClick(solicitud) 
            }
        }

        override fun getItemCount(): Int = solicitudes.size
    }
}
