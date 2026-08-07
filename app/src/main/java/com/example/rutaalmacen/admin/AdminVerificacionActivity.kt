package com.example.rutaalmacen.admin

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rutaalmacen.Constantes
import com.example.rutaalmacen.R
import com.google.android.material.tabs.TabLayout
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Locale

class AdminVerificacionActivity : AppCompatActivity() {

    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private lateinit var recyclerVerificaciones: RecyclerView
    private lateinit var textoContador: TextView
    private lateinit var textoSinSolicitudes: TextView
    private lateinit var tabLayout: TabLayout

    private val todasLasSolicitudes: MutableList<SolicitudVerificacion> = mutableListOf()
    private val solicitudesFiltradas: MutableList<SolicitudVerificacion> = mutableListOf()
    private lateinit var adaptador: AdaptadorVerificacion

    private var listenerFirestore: ListenerRegistration? = null
    private var filtroActual = Constantes.EstadoVerificacionPendiente

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
        tabLayout = findViewById(R.id.tab_layout_estados)

        findViewById<android.view.View>(R.id.boton_recargar).setOnClickListener {
            cargarDatos()
        }

        configurarTabs()

        adaptador = AdaptadorVerificacion(solicitudesFiltradas) { solicitud ->
            val fechaTexto = solicitud.fechaRegistro?.toDate()?.let { fecha ->
                val formato = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("es-CL"))
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
                putExtra("status", solicitud.status)
            }
            startActivity(intent)
        }

        recyclerVerificaciones.layoutManager = LinearLayoutManager(this)
        recyclerVerificaciones.adapter = adaptador
    }

    override fun onResume() {
        super.onResume()
        cargarDatos()
    }

    override fun onPause() {
        super.onPause()
        listenerFirestore?.remove()
        listenerFirestore = null
    }

    private fun configurarTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("Pendientes"))
        tabLayout.addTab(tabLayout.newTab().setText("Aprobados"))
        tabLayout.addTab(tabLayout.newTab().setText("Rechazados"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                filtroActual = when (tab?.position) {
                    0 -> Constantes.EstadoVerificacionPendiente
                    1 -> Constantes.EstadoVerificacionAprobada
                    2 -> Constantes.EstadoVerificacionRechazada
                    else -> Constantes.EstadoVerificacionPendiente
                }
                aplicarFiltro()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun cargarDatos() {
        lifecycleScope.launch {
            try {
                val snapshots = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                    .whereEqualTo("rol", Constantes.ROL_VENDEDOR)
                    .get()
                    .await()

                todasLasSolicitudes.clear()

                snapshots.documents.forEach { documento ->
                    val sellerProfile = documento.get("sellerProfile") as? Map<*, *> ?: return@forEach
                    val status = sellerProfile["verificationStatus"] as? String ?: return@forEach

                    val solicitud = SolicitudVerificacion(
                        uid = documento.id,
                        storeName = sellerProfile["storeName"] as? String ?: "",
                        displayName = documento.getString("displayName") ?: "",
                        rut = sellerProfile["rut"] as? String ?: "",
                        address = sellerProfile["address"] as? String ?: "",
                        patentImageUrl = sellerProfile["patentImageUrl"] as? String ?: "",
                        fechaRegistro = sellerProfile["createdAt"] as? Timestamp,
                        status = status,
                        rejectionReason = sellerProfile["rejectionReason"] as? String
                    )
                    todasLasSolicitudes.add(solicitud)
                }

                aplicarFiltro()
                escucharCambios()
            } catch (e: Exception) {
                // Silenciar
            }
        }
    }

    private fun escucharCambios() {
        listenerFirestore?.remove()
        listenerFirestore = baseDatos.collection(Constantes.COLECCION_USUARIOS)
            .whereEqualTo("rol", Constantes.ROL_VENDEDOR)
            .addSnapshotListener { _, _ ->
                cargarDatos()
            }
    }

    private fun aplicarFiltro() {
        val filtradas = todasLasSolicitudes
            .filter { it.status == filtroActual }
            .sortedByDescending { it.fechaRegistro }

        solicitudesFiltradas.clear()
        solicitudesFiltradas.addAll(filtradas)
        adaptador.notifyDataSetChanged()

        val totalPendientes = todasLasSolicitudes.count { it.status == Constantes.EstadoVerificacionPendiente }
        textoContador.text = "$totalPendientes pendiente(s)"
        textoSinSolicitudes.visibility = if (solicitudesFiltradas.isEmpty()) View.VISIBLE else View.GONE
    }

    data class SolicitudVerificacion(
        val uid: String,
        val storeName: String,
        val displayName: String,
        val rut: String,
        val address: String,
        val patentImageUrl: String,
        val fechaRegistro: Timestamp?,
        val status: String,
        val rejectionReason: String? = null
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
            val textoEstadoBadge: TextView = itemView.findViewById(R.id.texto_estado_badge)
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

            when (solicitud.status) {
                Constantes.EstadoVerificacionAprobada -> {
                    holder.textoEstadoBadge.text = "APROBADO"
                    holder.textoEstadoBadge.setBackgroundResource(R.drawable.bg_estado_verificado)
                    holder.textoEstadoBadge.setTextColor(holder.itemView.context.getColor(R.color.texto_inverso))
                }
                Constantes.EstadoVerificacionRechazada -> {
                    holder.textoEstadoBadge.text = "RECHAZADO"
                    holder.textoEstadoBadge.setBackgroundResource(R.drawable.bg_estado_rechazado)
                    holder.textoEstadoBadge.setTextColor(holder.itemView.context.getColor(R.color.texto_inverso))
                }
                else -> {
                    holder.textoEstadoBadge.text = "PENDIENTE"
                    holder.textoEstadoBadge.setBackgroundResource(R.drawable.bg_estado_pendiente)
                    holder.textoEstadoBadge.setTextColor(holder.itemView.context.getColor(R.color.colorSecondaryDark))
                }
            }

            holder.tarjeta.setOnClickListener { onSolicitudClick(solicitud) }
        }

        override fun getItemCount(): Int = solicitudes.size
    }
}
