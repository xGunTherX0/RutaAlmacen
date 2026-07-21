package com.example.rutaalmacen

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Actividad que presenta el listado de alertas reportadas por los vendedores.
 *
 * <p>Permite al administrador visualizar cada reporte, bloquear o desbloquear la cuenta
 * del comprador asociado y eliminar reportes de forma permanente. Los datos se obtienen
 * desde la colección de alertas reportadas en Firestore y se muestran en un [RecyclerView].</p>
 */
class AlertasReportadasActivity : AppCompatActivity() {

    /** Instancia de Firestore utilizada para las operaciones de lectura y escritura. */
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /** Lista mutable con todas las alertas reportadas cargadas desde la base de datos. */
    private val reportes: MutableList<AlertaReportada> = mutableListOf()

    /** Adaptador del [RecyclerView] que muestra las alertas reportadas en pantalla. */
    private lateinit var adaptador: AdaptadorReportes

    /** Texto informativo que se muestra cuando no hay reportes disponibles. */
    private lateinit var textoSinReportes: TextView

    /** Caché local de nombres de compradores para evitar consultas repetidas a Firestore. */
    private val cacheNombres: MutableMap<String, String> = mutableMapOf()

    /**
     * Método del ciclo de vida llamado al crear la actividad.
     *
     * <p>Configura el diseño de borde a borde, inicializa el [RecyclerView] con su adaptador,
     * registra el listener del botón de retroceso y carga los reportes desde Firestore.</p>
     *
     * @param savedInstanceState Estado previamente guardado de la instancia, o {@code null}
     *                           si es la primera vez que se crea.
     */
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
            reportes = reportes,
            onBloquear = { reporte -> confirmarBloquear(reporte) },
            onEliminar = { reporte -> confirmarEliminar(reporte) },
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adaptador

        findViewById<androidx.appcompat.widget.AppCompatImageButton>(R.id.boton_volver_reportadas).setOnClickListener {
            finish()
        }

        lifecycleScope.launch { cargarReportes() }
    }

    /**
     * Método del ciclo de vida llamado cuando la actividad vuelve a primer plano.
     * Recarga los reportes desde Firestore para reflejar cambios recientes.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { cargarReportes() }
    }

    /**
     * Carga todas las alertas reportadas desde Firestore, ordenadas por fecha descendente.
     *
     * <p>Para cada reporte, resuelve el nombre del comprador (usando la caché si está
     * disponible) y actualiza la lista local y el adaptador. En caso de error, muestra
     * un mensaje informativo al usuario.</p>
     */
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
                val mensaje = doc.getString("mensaje").orEmpty()
                val vendedorId = doc.getString("vendedorId").orEmpty()
                val compradorId = doc.getString("compradorId").orEmpty()
                val fechaReporte = doc.getLong("fechaReporte") ?: 0L
                val estado = doc.getString("estado") ?: "pendiente"
                
                val nombreComprador = obtenerNombreComprador(compradorId)
                
                nuevos.add(AlertaReportada(id, producto, mensaje, vendedorId, compradorId, nombreComprador, fechaReporte, estado))
            }
            
            reportes.clear()
            reportes.addAll(nuevos)
            adaptador.notifyDataSetChanged()
            textoSinReportes.visibility = if (reportes.isEmpty()) View.VISIBLE else View.GONE
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudieron cargar los reportes", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Obtiene el nombre del comprador desde Firestore, utilizando la caché local
     * para evitar consultas repetidas.
     *
     * @param compradorId Identificador único del comprador en Firestore.
     * @return Nombre del comprador, o un texto alternativo si no se puede resolver.
     */
    private suspend fun obtenerNombreComprador(compradorId: String): String {
        if (compradorId.isBlank()) return "Desconocido"
        
        cacheNombres[compradorId]?.let { return it }
        
        return try {
            val doc = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(compradorId)
                .get()
                .await()
            val nombre = doc.getString("nombre").orEmpty().ifBlank { "Sin nombre" }
            cacheNombres[compradorId] = nombre
            nombre
        } catch (e: Exception) {
            "Desconocido"
        }
    }

    /**
     * Muestra un diálogo de confirmación para bloquear o desbloquear la cuenta
     * del comprador asociado al reporte.
     *
     * <p>Si el comprador no puede identificarse, muestra un mensaje de error y
     * no presenta el diálogo.</p>
     *
     * @param reporte Instancia de [AlertaReportada] cuyo comprador se desea bloquear o desbloquear.
     */
    private fun confirmarBloquear(reporte: AlertaReportada) {
        if (reporte.compradorId.isBlank()) {
            Toast.makeText(this, "No se puede identificar al comprador", Toast.LENGTH_SHORT).show()
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

    /**
     * Muestra un diálogo de confirmación antes de eliminar un reporte de forma permanente.
     *
     * @param reporte Instancia de [AlertaReportada] que se desea eliminar.
     */
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

    /**
     * Alterna el estado de bloqueo del comprador asociado al reporte.
     *
     * <p>Si el comprador está bloqueado, lo desbloquea y restablece el estado del reporte
     * a «pendiente». Si no está bloqueado, lo bloquea y actualiza el estado a «bloqueado».
     * Actualiza ambos documentos en Firestore (usuario y reporte) mediante una operación
     * atómica. Recarga los reportes al finalizar.</p>
     *
     * @param reporte Instancia de [AlertaReportada] cuyo comprador se desea modificar.
     */
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

    /**
     * Elimina un reporte de alertas de Firestore de forma permanente.
     *
     * <p>Recarga los reportes al finalizar para reflejar el cambio en la interfaz.</p>
     *
     * @param reporte Instancia de [AlertaReportada] que se desea eliminar.
     */
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

    /**
     * Modelo de datos que representa una alerta reportada por un vendedor.
     *
     * @property id Identificador único del documento en Firestore.
     * @property producto Nombre o descripción del producto involucrado en la alerta.
     * @property mensaje Texto del reporte enviado por el vendedor.
     * @property vendedorId Identificador del vendedor que generó el reporte.
     * @property compradorId Identificador del comprador reportado.
     * @property nombreComprador Nombre resuelto del comprador reportado.
     * @property fechaReporte Marca de tiempo (en milisegundos) de la creación del reporte.
     * @property estado Estado actual del reporte (por ejemplo, «pendiente» o «bloqueado»).
     */
    data class AlertaReportada(
        val id: String,
        val producto: String,
        val mensaje: String,
        val vendedorId: String,
        val compradorId: String,
        val nombreComprador: String,
        val fechaReporte: Long,
        val estado: String,
    )
}

/**
 * Adaptador del [RecyclerView] que muestra la lista de alertas reportadas.
 *
 * <p>Cada elemento presenta la información del reporte (producto, mensaje, comprador,
 * fecha y estado) junto con botones para bloquear/desbloquear al comprador y eliminar
 * el reporte. Las acciones se delegan a los callbacks proporcionados en el constructor.</p>
 *
 * @param reportes Lista de alertas reportadas a mostrar.
 * @param onBloquear Callback invocado cuando el usuario solicita bloquear o desbloquear
 *                   la cuenta de un comprador.
 * @param onEliminar Callback invocado cuando el usuario solicita eliminar un reporte.
 */
private class AdaptadorReportes(
    private val reportes: List<AlertasReportadasActivity.AlertaReportada>,
    private val onBloquear: (AlertasReportadasActivity.AlertaReportada) -> Unit,
    private val onEliminar: (AlertasReportadasActivity.AlertaReportada) -> Unit,
) : RecyclerView.Adapter<AdaptadorReportes.VistaReporte>() {

    /**
     * ViewHolder que contiene las referencias a las vistas de cada elemento de alerta reportada.
     *
     * @param itemView Vista raíz del elemento de lista inflada desde el diseño XML.
     */
    class VistaReporte(itemView: View) : RecyclerView.ViewHolder(itemView) {
        /** Texto que muestra el producto involucrado en el reporte. */
        val textoProducto: TextView = itemView.findViewById(R.id.texto_producto_reporte)
        /** Texto que muestra el mensaje del reporte. */
        val textoMensaje: TextView = itemView.findViewById(R.id.texto_mensaje_reporte)
        /** Texto que muestra el nombre del comprador reportado. */
        val textoComprador: TextView = itemView.findViewById(R.id.texto_comprador_reporte)
        /** Texto que muestra la fecha del reporte. */
        val textoFecha: TextView = itemView.findViewById(R.id.texto_fecha_reporte)
        /** Texto que muestra el estado actual del reporte. */
        val textoEstado: TextView = itemView.findViewById(R.id.texto_estado_reporte)
        /** Botón para bloquear o desbloquear la cuenta del comprador. */
        val botonBloquear: MaterialButton = itemView.findViewById(R.id.boton_bloquear_comprador)
        /** Botón para eliminar el reporte de forma permanente. */
        val botonEliminar: MaterialButton = itemView.findViewById(R.id.boton_eliminar_reporte)
    }

    /**
     * Crea un nuevo [VistaReporte] inflando el diseño del elemento de alerta reportada.
     *
     * @param parent Grupo de vistas padre al que se adjuntará la nueva vista.
     * @param viewType Tipo de vista (no utilizado en este adaptador).
     * @return Nueva instancia de [VistaReporte].
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VistaReporte {
        val vista = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alerta_reportada, parent, false)
        return VistaReporte(vista)
    }

    /**
     * Vincula los datos del reporte en la posición indicada con las vistas del ViewHolder.
     *
     * <p>Formatea la fecha del reporte y ajusta el texto del botón de bloqueo según
     * el estado actual del comprador.</p>
     *
     * @param holder ViewHolder que contiene las vistas del elemento.
     * @param position Posición del elemento dentro de la lista.
     */
    override fun onBindViewHolder(holder: VistaReporte, position: Int) {
        val reporte = reportes[position]
        holder.textoProducto.text = "Producto: ${reporte.producto}"
        holder.textoMensaje.text = reporte.mensaje
        holder.textoComprador.text = "Comprador: ${reporte.nombreComprador}"
        val formato = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        holder.textoFecha.text = "Fecha: ${if (reporte.fechaReporte > 0) formato.format(java.util.Date(reporte.fechaReporte)) else "Sin fecha"}"
        holder.textoEstado.text = "Estado: ${reporte.estado}"

        val estaBloqueado = reporte.estado == "bloqueado"
        
        holder.botonBloquear.text = if (estaBloqueado) "Desbloquear cuenta" else "Bloquear cuenta"
        holder.botonBloquear.alpha = 1f
        
        holder.botonEliminar.alpha = 1f

        holder.botonBloquear.setOnClickListener { onBloquear(reporte) }
        holder.botonEliminar.setOnClickListener { onEliminar(reporte) }
    }

    /**
     * Retorna la cantidad total de elementos en la lista de reportes.
     *
     * @return Número de elementos que el adaptador debe mostrar.
     */
    override fun getItemCount(): Int = reportes.size
}
