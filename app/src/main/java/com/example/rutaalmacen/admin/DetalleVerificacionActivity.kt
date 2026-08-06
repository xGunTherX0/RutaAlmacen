package com.example.rutaalmacen.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.rutaalmacen.Constantes
import com.example.rutaalmacen.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class DetalleVerificacionActivity : AppCompatActivity() {

    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private lateinit var textoNombreAlmacen: TextView
    private lateinit var textoSolicitante: TextView
    private lateinit var textoRut: TextView
    private lateinit var textoDireccion: TextView
    private lateinit var textoFecha: TextView
    private lateinit var imagenPatente: ImageView
    private lateinit var barraCarga: ProgressBar
    private lateinit var botonVerCompleta: MaterialButton
    private lateinit var botonCopiarRut: MaterialButton
    private lateinit var botonAprobar: MaterialButton
    private lateinit var botonRechazar: MaterialButton
    private lateinit var botonVolver: MaterialButton

    private var uidUsuario: String = ""
    private var patentImageUrl: String = ""
    private var rutVendedor: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detalle_verificacion)

        inicializarVistas()
        cargarDatosIntent()
        configurarListeners()
    }

    private fun inicializarVistas() {
        textoNombreAlmacen = findViewById(R.id.texto_detalle_nombre_almacen)
        textoSolicitante = findViewById(R.id.texto_detalle_solicitante)
        textoRut = findViewById(R.id.texto_detalle_rut)
        textoDireccion = findViewById(R.id.texto_detalle_direccion)
        textoFecha = findViewById(R.id.texto_detalle_fecha)
        imagenPatente = findViewById(R.id.imagen_patente_detalle)
        barraCarga = findViewById(R.id.barra_carga_patente)
        botonVerCompleta = findViewById(R.id.boton_ver_patente_completa)
        botonCopiarRut = findViewById(R.id.boton_copiar_rut_sii)
        botonAprobar = findViewById(R.id.boton_aprobar_vendedor)
        botonRechazar = findViewById(R.id.boton_rechazar_vendedor)
        botonVolver = findViewById(R.id.boton_volver_lista)
    }

    private fun cargarDatosIntent() {
        uidUsuario = intent.getStringExtra("uid").orEmpty()
        val storeName = intent.getStringExtra("storeName").orEmpty()
        val displayName = intent.getStringExtra("displayName").orEmpty()
        rutVendedor = intent.getStringExtra("rut").orEmpty()
        val address = intent.getStringExtra("address").orEmpty()
        patentImageUrl = intent.getStringExtra("patentImageUrl").orEmpty()
        val fechaRegistro = intent.getStringExtra("fechaRegistro").orEmpty()

        textoNombreAlmacen.text = storeName.ifBlank { "Sin nombre" }
        textoSolicitante.text = "Solicitante: ${displayName.ifBlank { "Sin nombre" }}"
        textoRut.text = "RUT: ${rutVendedor.ifBlank { "No registrado" }}"
        textoDireccion.text = "Dirección: ${address.ifBlank { "No registrada" }}"
        textoFecha.text = "Fecha: ${fechaRegistro.ifBlank { "Sin fecha" }}"

        if (patentImageUrl.isNotBlank()) {
            barraCarga.visibility = View.VISIBLE
            imagenPatente.visibility = View.GONE
            Glide.with(this)
                .load(patentImageUrl)
                .centerInside()
                .placeholder(R.drawable.ic_camera)
                .error(R.drawable.ic_alerta)
                .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                    override fun onResourceReady(
                        resource: android.graphics.drawable.Drawable,
                        transition: com.bumptech.glide.request.transition.Transition<in android.graphics.drawable.Drawable>?
                    ) {
                        imagenPatente.setImageDrawable(resource)
                        barraCarga.visibility = View.GONE
                        imagenPatente.visibility = View.VISIBLE
                    }

                    override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                        barraCarga.visibility = View.GONE
                        imagenPatente.visibility = View.VISIBLE
                    }

                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                })
        } else {
            barraCarga.visibility = View.GONE
        }
    }

    private fun configurarListeners() {
        botonVerCompleta.setOnClickListener {
            if (patentImageUrl.isNotBlank()) {
                val intent = Intent(this, PatenteFullscreenActivity::class.java).apply {
                    putExtra("imageUrl", patentImageUrl)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "No hay patente para mostrar", Toast.LENGTH_SHORT).show()
            }
        }

        botonCopiarRut.setOnClickListener {
            if (rutVendedor.isBlank()) {
                Toast.makeText(this, "No hay RUT para copiar", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("RUT Vendedor", rutVendedor)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "RUT copiado: $rutVendedor. Pegalo en el SII.", Toast.LENGTH_SHORT).show()

            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www2.sii.cl/stc/noauthz"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo abrir el navegador", Toast.LENGTH_SHORT).show()
            }
        }

        botonAprobar.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Aprobar vendedor")
                .setMessage("¿Deseas aprobar a ${textoNombreAlmacen.text}? El vendedor podrá publicar productos.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Aprobar") { _, _ ->
                    cambiarEstadoVerificacion(Constantes.EstadoVerificacionAprobada)
                }
                .show()
        }

        botonRechazar.setOnClickListener {
            val input = EditText(this).apply {
                hint = "Motivo del rechazo"
                setPadding(48, 32, 48, 32)
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("Rechazar vendedor")
                .setMessage("¿Deseas rechazar a ${textoNombreAlmacen.text}? Especifica el motivo:")
                .setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Rechazar") { _, _ ->
                    val motivo = input.text.toString().trim()
                    if (motivo.isBlank()) {
                        Toast.makeText(this, "Ingresa un motivo", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    cambiarEstadoVerificacion(Constantes.EstadoVerificacionRechazada, motivo)
                }
                .show()
        }

        botonVolver.setOnClickListener { finish() }
    }

    private fun cambiarEstadoVerificacion(nuevoEstado: String, motivo: String? = null) {
        lifecycleScope.launch {
            try {
                val datos = mutableMapOf<String, Any>(
                    "sellerProfile.verificationStatus" to nuevoEstado
                )
                if (motivo != null) {
                    datos["sellerProfile.rejectionReason"] = motivo
                }

                baseDatos.collection(Constantes.COLECCION_USUARIOS)
                    .document(uidUsuario)
                    .set(datos, SetOptions.merge())
                    .await()

                val mensaje = if (nuevoEstado == Constantes.EstadoVerificacionAprobada) {
                    "Vendedor aprobado correctamente"
                } else {
                    "Vendedor rechazado"
                }
                Toast.makeText(this@DetalleVerificacionActivity, mensaje, Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@DetalleVerificacionActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
