package com.example.rutaalmacen

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Locale

class AlertasReportadasActivity : AppCompatActivity() {

    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val reportes: MutableList<AlertaReportada> = mutableListOf()
    private val reportesFiltrados: MutableList<AlertaReportada> = mutableListOf()

    private lateinit var adaptador: AdaptadorReportes
    private lateinit var textoSinReportes: TextView
    private var filtroActual = "todos"

    private val cacheNombres: MutableMap<String, String> = mutableMapOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_alertas_reportadas)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_alertas_reportadas)) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        textoSinReportes = findViewById(R.id.texto_sin_reportes)
        val recycler = findViewById<RecyclerView>(R.id.recycler_alertas_reportadas)

        adaptador = AdaptadorReportes(
            reportes = reportesFiltrados,
            onBloquear = { reporte -> confirmarBloquear(reporte) },
            onBloquearVendedor = { reporte -> confirmarBloquearVendedor(reporte) },
            onEliminarProducto = { reporte -> confirmarEliminarProducto(reporte) },
            onEliminar = { reporte -> confirmarEliminar(reporte) },
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adaptador

        findViewById<androidx.appcompat.widget.AppCompatImageButton>(R.id.boton_volver_reportadas).setOnClickListener {
            finish()
        }

        configurarFiltros()

        lifecycleScope.launch { cargarReportes() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { cargarReportes() }
    }

    private fun configurarFiltros() {
        val grupoFiltros = findViewById<MaterialButtonToggleGroup>(R.id.grupo_filtros_reportes)
        grupoFiltros.check(R.id.chip_todos_reportes)

        grupoFiltros.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            filtroActual = when (checkedId) {
                R.id.chip_productos_reportes -> "producto"
                R.id.chip_alertas_reportes -> "alerta"
                else -> "todos"
            }
            aplicarFiltro()
        }
    }

    private fun aplicarFiltro() {
        reportesFiltrados.clear()
        when (filtroActual) {
            "producto" -> reportesFiltrados.addAll(reportes.filter { it.tipoReporte == "producto" })
            "alerta" -> reportesFiltrados.addAll(reportes.filter { it.tipoReporte == "alerta" })
            else -> reportesFiltrados.addAll(reportes)
        }
        adaptador.notifyDataSetChanged()
        textoSinReportes.visibility = if (reportesFiltrados.isEmpty()) View.VISIBLE else View.GONE
    }

    private suspend fun cargarReportes() {
        try {
            val documentos = baseDatos.collection(Constantes.COLECCION_ALERTAS_REPORTADAS)
                .orderBy("fechaReporte", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .get()
                .await()
                .documents

            val nuevos = mutableListOf<AlertaReportada>()
            for (doc in documentos) {
                val id = doc.id
                val producto = doc.getString("producto").orEmpty()
                val productoId = doc.getString("productoId").orEmpty()
                val mensaje = doc.getString("mensaje").orEmpty()
                val vendedorId = doc.getString("vendedorId").orEmpty()
                val compradorId = doc.getString("compradorId").orEmpty()
                val fechaReporte = doc.getLong("fechaReporte") ?: 0L
                val estado = doc.getString("estado") ?: "pendiente"
                val tipoReporte = doc.getString("tipoReporte") ?: "alerta"

                val nombreComprador = obtenerNombreUsuario(compradorId)
                val nombreVendedor = if (vendedorId.isNotBlank()) obtenerNombreUsuario(vendedorId) else ""

                nuevos.add(AlertaReportada(id, producto, productoId, mensaje, vendedorId, compradorId, nombreComprador, nombreVendedor, fechaReporte, estado, tipoReporte))
            }

            reportes.clear()
            reportes.addAll(nuevos)
            aplicarFiltro()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudieron cargar los reportes", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun obtenerNombreUsuario(usuarioId: String): String {
        if (usuarioId.isBlank()) return "Desconocido"

        cacheNombres[usuarioId]?.let { return it }

        return try {
            val doc = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(usuarioId)
                .get()
                .await()
            val nombre = doc.getString("nombre").orEmpty().ifBlank { "Sin nombre" }
            cacheNombres[usuarioId] = nombre
            nombre
        } catch (e: Exception) {
            "Desconocido"
        }
    }

    private fun confirmarBloquear(reporte: AlertaReportada) {
        if (reporte.compradorId.isBlank()) {
            Toast.makeText(this, "No se puede identificar al usuario", Toast.LENGTH_SHORT).show()
            return
        }
        val estaBloqueado = reporte.estado == "bloqueado"
        MaterialAlertDialogBuilder(this)
            .setTitle(if (estaBloqueado) "Desbloquear cuenta" else "Bloquear cuenta")
            .setMessage(if (estaBloqueado) {
                "¿Deseas desbloquear la cuenta de ${reporte.nombreComprador}? Podrá volver a usar la aplicación."
            } else {
                "¿Deseas bloquear la cuenta de ${reporte.nombreComprador}? No podrá acceder a la aplicación."
            })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(if (estaBloqueado) "Desbloquear" else "Bloquear") { _, _ ->
                lifecycleScope.launch { toggleBloqueoComprador(reporte) }
            }
            .show()
    }

    private fun confirmarBloquearVendedor(reporte: AlertaReportada) {
        if (reporte.vendedorId.isBlank()) {
            Toast.makeText(this, "No se puede identificar al vendedor", Toast.LENGTH_SHORT).show()
            return
        }
        val estaBloqueado = reporte.estado == "vendedor_bloqueado"
        MaterialAlertDialogBuilder(this)
            .setTitle(if (estaBloqueado) "Desbloquear vendedor" else "Bloquear vendedor")
            .setMessage(if (estaBloqueado) {
                "¿Deseas desbloquear al vendedor ${reporte.nombreVendedor}? Podrá volver a publicar productos."
            } else {
                "¿Deseas bloquear al vendedor ${reporte.nombreVendedor}? No podrá publicar productos."
            })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(if (estaBloqueado) "Desbloquear" else "Bloquear") { _, _ ->
                lifecycleScope.launch { toggleBloqueoVendedor(reporte) }
            }
            .show()
    }

    private fun confirmarEliminarProducto(reporte: AlertaReportada) {
        if (reporte.vendedorId.isBlank() || reporte.productoId.isBlank()) {
            Toast.makeText(this, "No se puede identificar el producto", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminar producto")
            .setMessage("¿Deseas eliminar el producto \"${reporte.producto}\" del inventario del vendedor?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch { eliminarProductoReportado(reporte) }
            }
            .show()
    }

    private fun confirmarEliminar(reporte: AlertaReportada) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminar reporte")
            .setMessage("¿Deseas eliminar este reporte permanentemente?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch { eliminarReporte(reporte) }
            }
            .show()
    }

    private suspend fun toggleBloqueoComprador(reporte: AlertaReportada) {
        try {
            val estaBloqueado = reporte.estado == "bloqueado"
            val nuevoEstado = if (estaBloqueado) "pendiente" else "bloqueado"

            baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(reporte.compradorId)
                .set(mapOf("bloqueado" to !estaBloqueado), SetOptions.merge())
                .await()
            baseDatos.collection(Constantes.COLECCION_ALERTAS_REPORTADAS)
                .document(reporte.id)
                .set(mapOf("estado" to nuevoEstado), SetOptions.merge())
                .await()
            Toast.makeText(this, if (estaBloqueado) "Cuenta desbloqueada" else "Cuenta bloqueada", Toast.LENGTH_SHORT).show()
            cargarReportes()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo cambiar el estado de la cuenta", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun toggleBloqueoVendedor(reporte: AlertaReportada) {
        try {
            val estaBloqueado = reporte.estado == "vendedor_bloqueado"
            val nuevoEstado = if (estaBloqueado) "pendiente" else "vendedor_bloqueado"

            baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(reporte.vendedorId)
                .set(mapOf("bloqueado" to !estaBloqueado), SetOptions.merge())
                .await()
            baseDatos.collection(Constantes.COLECCION_ALERTAS_REPORTADAS)
                .document(reporte.id)
                .set(mapOf("estado" to nuevoEstado), SetOptions.merge())
                .await()
            Toast.makeText(this, if (estaBloqueado) "Vendedor desbloqueado" else "Vendedor bloqueado", Toast.LENGTH_SHORT).show()
            cargarReportes()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo cambiar el estado del vendedor", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun eliminarProductoReportado(reporte: AlertaReportada) {
        try {
            val docPublicoId = "${reporte.vendedorId}_${reporte.productoId}"
            baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
                .document(docPublicoId)
                .delete()
                .await()
            baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(reporte.vendedorId)
                .collection("Inventario")
                .document(reporte.productoId)
                .delete()
                .await()
            baseDatos.collection(Constantes.COLECCION_ALERTAS_REPORTADAS)
                .document(reporte.id)
                .set(mapOf("estado" to "producto_eliminado"), SetOptions.merge())
                .await()
            Toast.makeText(this, "Producto eliminado", Toast.LENGTH_SHORT).show()
            cargarReportes()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo eliminar el producto", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun eliminarReporte(reporte: AlertaReportada) {
        try {
            baseDatos.collection(Constantes.COLECCION_ALERTAS_REPORTADAS)
                .document(reporte.id)
                .delete()
                .await()
            Toast.makeText(this, "Reporte eliminado", Toast.LENGTH_SHORT).show()
            cargarReportes()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo eliminar el reporte", Toast.LENGTH_SHORT).show()
        }
    }

    data class AlertaReportada(
        val id: String,
        val producto: String,
        val productoId: String = "",
        val mensaje: String,
        val vendedorId: String,
        val compradorId: String,
        val nombreComprador: String,
        val nombreVendedor: String = "",
        val fechaReporte: Long,
        val estado: String,
        val tipoReporte: String = "alerta",
    )
}

private class AdaptadorReportes(
    private val reportes: List<AlertasReportadasActivity.AlertaReportada>,
    private val onBloquear: (AlertasReportadasActivity.AlertaReportada) -> Unit,
    private val onBloquearVendedor: (AlertasReportadasActivity.AlertaReportada) -> Unit,
    private val onEliminarProducto: (AlertasReportadasActivity.AlertaReportada) -> Unit,
    private val onEliminar: (AlertasReportadasActivity.AlertaReportada) -> Unit,
) : RecyclerView.Adapter<AdaptadorReportes.VistaReporte>() {

    class VistaReporte(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textoTipoReporte: TextView = itemView.findViewById(R.id.texto_tipo_reporte)
        val textoProducto: TextView = itemView.findViewById(R.id.texto_producto_reporte)
        val textoVendedor: TextView = itemView.findViewById(R.id.texto_vendedor_reporte)
        val textoMensaje: TextView = itemView.findViewById(R.id.texto_mensaje_reporte)
        val textoComprador: TextView = itemView.findViewById(R.id.texto_comprador_reporte)
        val textoFecha: TextView = itemView.findViewById(R.id.texto_fecha_reporte)
        val textoEstado: TextView = itemView.findViewById(R.id.texto_estado_reporte)
        val contenedorBotonesProducto: LinearLayout = itemView.findViewById(R.id.contenedor_botones_producto)
        val contenedorBotonesAlerta: LinearLayout = itemView.findViewById(R.id.contenedor_botones_alerta)
        val botonBloquear: MaterialButton = itemView.findViewById(R.id.boton_bloquear_comprador)
        val botonBloquearVendedor: MaterialButton = itemView.findViewById(R.id.boton_bloquear_vendedor)
        val botonEliminarProducto: MaterialButton = itemView.findViewById(R.id.boton_eliminar_producto)
        val botonEliminar: MaterialButton = itemView.findViewById(R.id.boton_eliminar_reporte)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VistaReporte {
        val vista = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alerta_reportada, parent, false)
        return VistaReporte(vista)
    }

    override fun onBindViewHolder(holder: VistaReporte, position: Int) {
        val reporte = reportes[position]
        val ctx = holder.itemView.context

        holder.textoTipoReporte.text = if (reporte.tipoReporte == "producto") "Reporte de producto" else "Reporte de alerta"
        holder.textoProducto.text = reporte.producto
        holder.textoMensaje.text = reporte.mensaje
        holder.textoComprador.text = "Reportado por: ${reporte.nombreComprador}"
        val formato = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        holder.textoFecha.text = formato.format(java.util.Date(reporte.fechaReporte))

        val colorEstado = androidx.core.content.ContextCompat.getColor(
            ctx,
            when (reporte.estado) {
                "pendiente" -> R.color.stock_verde
                "bloqueado", "vendedor_bloqueado", "producto_eliminado" -> R.color.stock_rojo
                else -> R.color.texto_secundario
            },
        )
        holder.textoEstado.text = reporte.estado
        holder.textoEstado.setTextColor(colorEstado)
        holder.textoEstado.setBackgroundResource(
            if (reporte.estado == "pendiente") R.drawable.bg_chip_estado_ok else R.drawable.bg_chip_estado_mal,
        )

        if (reporte.tipoReporte == "producto") {
            holder.textoVendedor.visibility = View.VISIBLE
            holder.textoVendedor.text = "Vendedor: ${reporte.nombreVendedor.ifBlank { "Desconocido" }}"
            holder.contenedorBotonesProducto.visibility = View.VISIBLE
            holder.contenedorBotonesAlerta.visibility = View.GONE

            val vendedorBloqueado = reporte.estado == "vendedor_bloqueado"
            holder.botonBloquearVendedor.text = if (vendedorBloqueado) "Desbloquear vendedor" else "Bloquear vendedor"
            holder.botonBloquearVendedor.setOnClickListener { onBloquearVendedor(reporte) }
            holder.botonEliminarProducto.setOnClickListener { onEliminarProducto(reporte) }
        } else {
            holder.textoVendedor.visibility = View.GONE
            holder.contenedorBotonesProducto.visibility = View.GONE
            holder.contenedorBotonesAlerta.visibility = View.VISIBLE

            val estaBloqueado = reporte.estado == "bloqueado"
            holder.botonBloquear.text = if (estaBloqueado) "Desbloquear cuenta" else "Bloquear cuenta"
            holder.botonBloquear.setOnClickListener { onBloquear(reporte) }
        }

        holder.botonEliminar.setOnClickListener { onEliminar(reporte) }
    }

    override fun getItemCount(): Int = reportes.size
}
