package com.example.rutaalmacen

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rutaalmacen.pagos.PlanManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
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

/**
 * Fragmento que gestiona las alertas generadas por inteligencia artificial.
 *
 * Muestra al vendedor un listado de alertas de búsqueda de compradores cercanos,
 * con funcionalidades de filtrado por rango de distancia y por antigüedad (solo nuevas
 * en las últimas 24 horas). Permite borrar alertas individuales o en lote, y reportar
 * alertas inapropiadas al administrador.
 *
 * Las alertas se cargan desde Firestore, se filtran por distancia calculada con la
 * fórmula de Haversine y se presentan mediante un [RecyclerView] con [AdaptadorAlertas].
 * El panel de filtros es colapsable y el rango se ajusta con un [Slider] no lineal.
 */
class AlertasIAFragment : Fragment(R.layout.fragment_alertas_ia) {

    /** Instancia de Firebase Authentication obtenida de forma perezosa. */
    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    /** Instancia de Firestore obtenida de forma perezosa para lecturas y escrituras remotas. */
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    /** Administrador del plan de suscripción del vendedor. */
    private val planManager = PlanManager()
    /** Estado actual de la suscripción, cargado de forma asíncrona. */
    private var estadoSuscripcion: com.example.rutaalmacen.pagos.EstadoSuscripcion? = null
    /** Lista mutable de alertas visibles tras aplicar filtros. */
    private val alertas: MutableList<AlertaIA> = mutableListOf()
    /** Lista maestra de todas las alertas cargadas, sin filtrar. */
    private val alertasTodas: MutableList<AlertaIA> = mutableListOf()
    private lateinit var adaptador: AdaptadorAlertas
    private lateinit var textoSinAlertas: android.widget.TextView
    private lateinit var textoLimiteAlertas: TextView
    private lateinit var botonSoloNuevas: MaterialButton
    private lateinit var botonBorrar: MaterialButton
    private lateinit var sliderRango: Slider
    private lateinit var textoValorRango: android.widget.TextView
    private lateinit var contenidoFiltros: View
    private lateinit var iconoToggleFiltros: android.widget.ImageView
    private lateinit var textoResumenFiltros: android.widget.TextView
    /** Indica si el panel de filtros está expandido. */
    private var filtrosExpandidos: Boolean = false
    /** Indica si se muestran únicamente las alertas de las últimas 24 horas. */
    private var mostrarSoloNuevas = false
    /** Radio de distancia seleccionado por el usuario, en kilómetros. */
    private var radioSeleccionadoKm: Double = RadioAlertasPreferencias.RADIO_POR_DEFECTO

    /**
     * Convierte un valor en kilómetros a la posición normalizada del deslizador.
     *
     * Aplica una normalización lineal entre [RADIO_MIN_KM] y [RADIO_MAX_KM].
     *
     * @param km Distancia en kilómetros a convertir.
     * @return Valor del deslizador entre 0.0 y 1.0.
     */
    private fun metrosASlider(km: Double): Float {
        val kmNorm = km.coerceIn(RADIO_MIN_KM, RADIO_MAX_KM)
        val fraccion = (kmNorm - RADIO_MIN_KM) / (RADIO_MAX_KM - RADIO_MIN_KM)
        return fraccion.toFloat().coerceIn(0f, 1f)
    }

    /**
     * Convierte la posición normalizada del deslizador a un valor en kilómetros.
     *
     * Aplica la operación inversa de [metrosASlider].
     *
     * @param valorSlider Valor del deslizador entre 0.0 y 1.0.
     * @return Distancia en kilómetros correspondiente.
     */
    private fun sliderAKm(valorSlider: Float): Double {
        val fraccion = valorSlider.toDouble().coerceIn(0.0, 1.0)
        return RADIO_MIN_KM + fraccion * (RADIO_MAX_KM - RADIO_MIN_KM)
    }

    /**
     * Genera una etiqueta legible para el valor de rango seleccionado.
     *
     * Muestra «Todo» si el rango cubre el máximo, metros si es menor a 1 km,
     * y kilómetros con un decimal o entero según la magnitud.
     *
     * @param km Distancia en kilómetros a formatear.
     * @return Cadena con la distancia formateada para presentación.
     */
    private fun formatearEtiquetaRango(km: Double): String {
        if (km >= RADIO_MAX_KM) return "Todo"
        if (km < 1.0) return "${(km * 1000).toInt()} m"
        return if (km < 10) String.format(java.util.Locale.forLanguageTag("es-CL"), "%.1f km", km)
        else String.format(java.util.Locale.forLanguageTag("es-CL"), "%.0f km", km)
    }

    /**
     * Inicializa las vistas del fragmento, configura el adaptador del listado,
     * el deslizador de rango, los botones de filtro y carga las alertas.
     *
     * @param view Vista raíz inflada del fragmento.
     * @param savedInstanceState Estado guardado previamente, o `null` si es un inicio nuevo.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler_alertas)
        textoSinAlertas = view.findViewById(R.id.texto_sin_alertas)
        textoLimiteAlertas = view.findViewById(R.id.texto_limite_alertas)
        botonSoloNuevas = view.findViewById(R.id.boton_solo_nuevas_alertas)
        botonBorrar = view.findViewById(R.id.boton_borrar_alertas)
        sliderRango = view.findViewById(R.id.slider_rango_alertas)
        textoValorRango = view.findViewById(R.id.texto_valor_rango)
        contenidoFiltros = view.findViewById(R.id.contenido_filtros_alertas)
        iconoToggleFiltros = view.findViewById(R.id.icono_toggle_filtros_alertas)
        textoResumenFiltros = view.findViewById(R.id.texto_resumen_filtros_alertas)
        val cabeceraFiltros = view.findViewById<View>(R.id.cabecera_filtros_alertas)
        cabeceraFiltros.setOnClickListener { alternarFiltros() }
        actualizarEstadoFiltros()

        adaptador = AdaptadorAlertas(
            alertas = alertas,
            onBorrar = { alerta -> confirmarBorrarAlerta(alerta) },
            onReportar = { alerta -> confirmarReportarAlerta(alerta) },
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adaptador

        radioSeleccionadoKm = RADIO_MIN_KM
        val valorInicialSlider = metrosASlider(radioSeleccionadoKm)
        sliderRango.value = valorInicialSlider
        textoValorRango.text = formatearEtiquetaRango(radioSeleccionadoKm)

        sliderRango.addOnChangeListener { slider, valor, desdeUsuario ->
            radioSeleccionadoKm = sliderAKm(valor)
            textoValorRango.text = formatearEtiquetaRango(radioSeleccionadoKm)
            actualizarResumenFiltros()
            if (desdeUsuario) {
                RadioAlertasPreferencias.guardar(requireContext(), radioSeleccionadoKm)
            }
            aplicarFiltroAlertas()
        }

        botonSoloNuevas.setOnClickListener {
            mostrarSoloNuevas = !mostrarSoloNuevas
            actualizarResumenFiltros()
            aplicarFiltroAlertas()
        }
        botonBorrar.setOnClickListener { confirmarBorrarAlertas() }

        actualizarResumenFiltros()
        viewLifecycleOwner.lifecycleScope.launch {
            estadoSuscripcion = planManager.cargarEstadoActual()
            actualizarContadorAlertas()
            cargarAlertas()
        }
    }

    /**
     * Alterna la visibilidad del panel de filtros entre expandido y colapsado.
     */
    private fun alternarFiltros() {
        filtrosExpandidos = !filtrosExpandidos
        actualizarEstadoFiltros()
    }

    /**
     * Actualiza la visibilidad del contenido de filtros y la rotación del ícono toggle.
     */
    private fun actualizarEstadoFiltros() {
        contenidoFiltros.visibility = if (filtrosExpandidos) View.VISIBLE else View.GONE
        iconoToggleFiltros.animate().rotation(if (filtrosExpandidos) 90f else 0f).setDuration(180).start()
    }

    /**
     * Actualiza el texto de resumen de filtros con el rango actual y el estado
     * del filtro «Solo nuevas».
     */
    private fun actualizarResumenFiltros() {
        if (!::textoResumenFiltros.isInitialized) return
        val rango = formatearEtiquetaRango(radioSeleccionadoKm)
        textoResumenFiltros.text = if (mostrarSoloNuevas) {
            "Filtros · $rango · Solo nuevas"
        } else {
            "Filtros · $rango"
        }
    }

    /**
     * Recarga el estado de suscripción y las alertas al volver a primer plano.
     */
    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            estadoSuscripcion = planManager.cargarEstadoActual()
            actualizarContadorAlertas()
            cargarAlertas()
        }
    }

    /**
     * Actualiza el contador de alertas del día en la interfaz.
     *
     * Muestra el formato «X / Y hoy», donde Y puede ser un número finito o el símbolo
     * de infinito si el plan no tiene límite diario.
     */
    private fun actualizarContadorAlertas() {
        val estado = estadoSuscripcion
        if (estado == null) {
            textoLimiteAlertas.text = "0 / 15 hoy"
            return
        }
        val limiteTexto = if (estado.plan.maxAlertasPorDia == Int.MAX_VALUE) {
            "∞"
        } else {
            estado.plan.maxAlertasPorDia.toString()
        }
        textoLimiteAlertas.text = "${estado.alertasHoy} / $limiteTexto hoy"
    }

    /**
     * Carga las alertas del vendedor desde Firestore, calcula distancias y aplica filtros.
     *
     * Obtiene la ubicación del vendedor, consulta las notificaciones de IA asociadas,
     * descarta las alertas antiguas (más de una semana) y las que no pasan el filtro de
     * contenido. Calcula la distancia en metros con la fórmula de Haversine cuando hay
     * coordenadas disponibles. Finalmente, actualiza el contador de alertas del día.
     *
     * @throws Exception Si la consulta a Firestore falla; se muestra un mensaje de error
     *   al usuario.
     */
    private suspend fun cargarAlertas() {
        val usuario = autenticacion.currentUser ?: return
        try {
            val documentoUsuario = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(usuario.uid)
                .get()
                .await()
            val latitudUsuario = documentoUsuario.getDouble("latitud")
            val longitudUsuario = documentoUsuario.getDouble("longitud")

            val documentosAlertas = baseDatos.collection(Constantes.COLECCION_NOTIFICACIONES_IA)
                .whereEqualTo("vendedorId", usuario.uid)
                .get()
                .await()
                .documents

            val ahora = System.currentTimeMillis()
            val limite = ahora - MILISEGUNDOS_SEMANA
            limpiarAlertasAntiguas(documentosAlertas, limite)

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
                val compradorId = documento.getString("compradorId").orEmpty()
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
                    id = documento.id,
                    producto = producto,
                    mensaje = mensaje,
                    totalBusquedas = totalBusquedas,
                    radioMetros = radioMetros,
                    distanciaMetros = distancia,
                    fechaCreacion = fechaCreacion,
                    compradorId = compradorId,
                )
            }.sortedByDescending { it.fechaCreacion }

            alertasTodas.clear()
            alertasTodas.addAll(nuevasAlertas)
            aplicarFiltroAlertas()

            val inicioDelDia = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            val alertasHoy = alertasTodas.count { it.fechaCreacion >= inicioDelDia }
            estadoSuscripcion = estadoSuscripcion?.copy(alertasHoy = alertasHoy)
            actualizarContadorAlertas()
        } catch (excepcion: Exception) {
            android.util.Log.e("AlertasIA", "Error cargando alertas", excepcion)
            mostrarMensaje("No se pudieron cargar las alertas: ${excepcion.message ?: "?"}")
        }
    }

    /**
     * Muestra un mensaje breve al usuario mediante un [Toast].
     *
     * @param mensaje Texto a mostrar.
     */
    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(requireContext(), mensaje, Toast.LENGTH_SHORT).show()
    }

    /**
     * Calcula la distancia en metros entre dos coordenadas geográficas usando la fórmula de Haversine.
     *
     * @param latitud1 Latitud del primer punto en grados decimales.
     * @param longitud1 Longitud del primer punto en grados decimales.
     * @param latitud2 Latitud del segundo punto en grados decimales.
     * @param longitud2 Longitud del segundo punto en grados decimales.
     * @return Distancia aproximada en metros entre los dos puntos.
     */
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

    /**
     * Aplica los filtros activos (tiempo y rango de distancia) sobre la lista maestra
     * de alertas y actualiza el adaptador.
     *
     * Si [mostrarSoloNuevas] está activo, filtra las alertas de las últimas 24 horas.
     * Si el radio seleccionado es menor al máximo, descarta las alertas que superen
     * esa distancia. Actualiza el mensaje de estado vacío y el texto del botón de filtro.
     */
    private fun aplicarFiltroAlertas() {
        val ahora = System.currentTimeMillis()
        val limite = ahora - MILISEGUNDOS_DIA
        val conFiltroTiempo = if (mostrarSoloNuevas) {
            alertasTodas.filter { it.fechaCreacion >= limite }
        } else {
            alertasTodas
        }
        val radioEnMetros = radioSeleccionadoKm * 1_000.0
        val conFiltroRango = if (radioSeleccionadoKm >= RADIO_MAX_KM) {
            conFiltroTiempo
        } else {
            conFiltroTiempo.filter { alerta ->
                val distancia = alerta.distanciaMetros
                distancia == null || distancia <= radioEnMetros
            }
        }

        alertas.clear()
        alertas.addAll(conFiltroRango)
        adaptador.notifyDataSetChanged()
        val sinAlertasMsg = if (mostrarSoloNuevas) {
            "No hay alertas nuevas en tu rango."
        } else {
            "No hay alertas en tu rango."
        }
        textoSinAlertas.text = sinAlertasMsg
        textoSinAlertas.visibility = if (alertas.isEmpty()) View.VISIBLE else View.GONE
        botonSoloNuevas.text = if (mostrarSoloNuevas) {
            "Ver todas"
        } else {
            "Solo nuevas (24h)"
        }
    }

    /**
     * Muestra un diálogo de confirmación para borrar todas las alertas visibles.
     *
     * Si no hay alertas, muestra un mensaje informativo. En caso contrario, presenta
     * un diálogo de confirmación que ejecuta [borrarAlertasVisibles] al aceptar.
     */
    private fun confirmarBorrarAlertas() {
        if (alertas.isEmpty()) {
            mostrarMensaje("No hay alertas para limpiar")
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Limpiar alertas")
            .setMessage("¿Deseas borrar las alertas visibles?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { borrarAlertasVisibles() }
            }
            .show()
    }

    /**
     * Muestra un diálogo de confirmación para borrar una alerta individual.
     *
     * @param alerta Alerta que se desea eliminar.
     */
    private fun confirmarBorrarAlerta(alerta: AlertaIA) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Borrar alerta")
            .setMessage("¿Deseas borrar esta alerta?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { borrarAlerta(alerta) }
            }
            .show()
    }

    /**
     * Muestra un diálogo de confirmación para reportar una alerta como inapropiada.
     *
     * @param alerta Alerta que se desea reportar al administrador.
     */
    private fun confirmarReportarAlerta(alerta: AlertaIA) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Reportar alerta")
            .setMessage("¿Esta alerta proviene de una búsqueda inapropiada? Se notificará al administrador.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Reportar") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { reportarAlerta(alerta) }
            }
            .show()
    }

    /**
     * Envía un reporte de alerta inapropiada a la colección de alertas reportadas en Firestore.
     *
     * Crea un nuevo documento con los datos de la alerta, el vendedor, el comprador
     * y la fecha del reporte, en estado «pendiente».
     *
     * @param alerta Alerta que se reporta.
     * @throws Exception Si la escritura en Firestore falla; se muestra un mensaje al usuario.
     */
    private suspend fun reportarAlerta(alerta: AlertaIA) {
        val vendedor = autenticacion.currentUser ?: return
        try {
            val datos = mapOf(
                "alertaId" to alerta.id,
                "producto" to alerta.producto,
                "mensaje" to alerta.mensaje,
                "vendedorId" to vendedor.uid,
                "compradorId" to alerta.compradorId,
                "fechaReporte" to System.currentTimeMillis(),
                "estado" to "pendiente",
            )
            baseDatos.collection(Constantes.COLECCION_ALERTAS_REPORTADAS)
                .add(datos)
                .await()
            mostrarMensaje("Alerta reportada al administrador")
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo reportar la alerta")
        }
    }

    /**
     * Elimina una alerta individual de Firestore y actualiza la lista local.
     *
     * @param alerta Alerta que se desea eliminar.
     * @throws Exception Si la eliminación en Firestore falla; se muestra un mensaje al usuario.
     */
    private suspend fun borrarAlerta(alerta: AlertaIA) {
        try {
            baseDatos.collection(Constantes.COLECCION_NOTIFICACIONES_IA)
                .document(alerta.id)
                .delete()
                .await()
            alertasTodas.removeAll { it.id == alerta.id }
            aplicarFiltroAlertas()
            mostrarMensaje("Alerta eliminada")
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo borrar la alerta")
        }
    }

    /**
     * Elimina en lotes todas las alertas visibles actualmente de Firestore.
     *
     * Agrupa los identificadores en lotes de 450 para respetar el límite de operaciones
     * por escritura en lotes de Firestore. Tras la eliminación, recarga las alertas.
     *
     * @throws Exception Si la eliminación por lotes falla; se muestra un mensaje al usuario.
     */
    private suspend fun borrarAlertasVisibles() {
        val ids = alertas.map { it.id }.distinct()
        if (ids.isEmpty()) {
            mostrarMensaje("No hay alertas para borrar")
            return
        }
        try {
            ids.chunked(450).forEach { loteIds ->
                val lote = baseDatos.batch()
                loteIds.forEach { id ->
                    lote.delete(baseDatos.collection(Constantes.COLECCION_NOTIFICACIONES_IA).document(id))
                }
                lote.commit().await()
            }
            mostrarMensaje("Alertas eliminadas")
            cargarAlertas()
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudieron borrar las alertas")
        }
    }

    /**
     * Elimina de Firestore las alertas cuya fecha de creación sea anterior al límite indicado.
     *
     * Procesa los documentos en lotes de 450 para cumplir con las restricciones de Firestore.
     *
     * @param documentos Lista de documentos de alertas a evaluar.
     * @param limite Marca de tiempo en milisegundos; las alertas anteriores a esta fecha se eliminan.
     * @throws Exception Si alguna operación por lotes falla; el error se propaga al invocador.
     */
    private suspend fun limpiarAlertasAntiguas(
        documentos: List<DocumentSnapshot>,
        limite: Long,
    ) {
        val porEliminar = documentos.filter { documento ->
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

    /**
     * Modelo de datos que representa una alerta generada por inteligencia artificial.
     *
     * @property id Identificador único del documento en Firestore.
     * @property producto Nombre del producto buscado por el comprador.
     * @property mensaje Mensaje descriptivo de la alerta.
     * @property totalBusquedas Cantidad total de búsquedas asociadas a esta alerta.
     * @property radioMetros Radio de cobertura de la alerta en metros, o `null` si no aplica.
     * @property distanciaMetros Distancia estimada desde el vendedor hasta el centro de la alerta, o `null`.
     * @property fechaCreacion Marca de tiempo de creación en milisegundos.
     * @property compradorId Identificador del comprador que originó la búsqueda.
     */
    data class AlertaIA(
        val id: String,
        val producto: String,
        val mensaje: String,
        val totalBusquedas: Int,
        val radioMetros: Double?,
        val distanciaMetros: Double?,
        val fechaCreacion: Long,
        val compradorId: String = "",
    )

    /** Constantes de tiempo y rango para el filtrado de alertas. */
    private companion object {
        private const val MILISEGUNDOS_SEMANA = 7L * 24 * 60 * 60 * 1000
        private const val MILISEGUNDOS_DIA = 24L * 60 * 60 * 1000
        private const val RADIO_MIN_KM = 0.1
        private const val RADIO_MAX_KM = 5.0
    }
}

/**
 * Adaptador de [RecyclerView] que presenta la lista de alertas de IA en tarjetas individuales.
 *
 * Cada elemento muestra el producto, el mensaje, la distancia estimada, la cantidad de búsquedas
 * y la fecha de creación. Incluye botones para borrar y reportar cada alerta.
 *
 * @param alertas Lista de alertas visibles a presentar.
 * @param onBorrar Acción ejecutada al pulsar el botón de borrar una alerta.
 * @param onReportar Acción ejecutada al pulsar el botón de reportar una alerta.
 */
private class AdaptadorAlertas(
    private val alertas: List<AlertasIAFragment.AlertaIA>,
    private val onBorrar: (AlertasIAFragment.AlertaIA) -> Unit,
    private val onReportar: (AlertasIAFragment.AlertaIA) -> Unit,
) : RecyclerView.Adapter<AdaptadorAlertas.VistaAlerta>() {

    /**
     * Contenedor de vistas para cada elemento de alerta en el [RecyclerView].
     *
     * @param itemView Vista raíz del elemento de diseño `item_alerta_ia`.
     */
    class VistaAlerta(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textoProducto: android.widget.TextView = itemView.findViewById(R.id.texto_producto_alerta)
        val textoDetalle: android.widget.TextView = itemView.findViewById(R.id.texto_detalle_alerta)
        val textoMetrica: android.widget.TextView = itemView.findViewById(R.id.texto_metrica_alerta)
        val botonBorrar: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.boton_borrar_alerta)
        val botonReportar: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.boton_reportar_alerta)
    }

    /**
     * Crea una nueva vista de alerta inflando el diseño `item_alerta_ia`.
     *
     * @param parent Vista padre donde se insertará el nuevo elemento.
     * @param viewType Tipo de vista (no utilizado en este adaptador).
     * @return Instancia de [VistaAlerta] con las vistas vinculadas.
     */
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaAlerta {
        val vista = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alerta_ia, parent, false)
        return VistaAlerta(vista)
    }

    /**
     * Vincula los datos de una alerta con las vistas de su contenedor.
     *
     * Muestra el nombre del producto, el mensaje, la distancia formateada, la cantidad
     * de búsquedas y la fecha. Configura los listeners de los botones de borrar y reportar.
     *
     * @param holder Contenedor de vistas a actualizar.
     * @param position Posición del elemento en la lista.
     */
    override fun onBindViewHolder(holder: VistaAlerta, position: Int) {
        val alerta = alertas[position]
        val textoSinRadio = "Sin radio"
        val textoSinUbicacion = "Sin ubicación"
        holder.textoProducto.text = alerta.producto
        holder.textoDetalle.text = alerta.mensaje
        val distanciaTexto = formatearMetrosNullable(alerta.distanciaMetros, textoSinUbicacion)
        val distanciaInfo = if (alerta.distanciaMetros != null) {
            "A ~$distanciaTexto de ti"
        } else {
            distanciaTexto
        }
        holder.textoMetrica.text = "Búsquedas: ${alerta.totalBusquedas} • " +
            "$distanciaInfo • " +
            "Fecha: ${formatearFecha(alerta.fechaCreacion)}"
        holder.botonBorrar.setOnClickListener { onBorrar(alerta) }
        holder.botonReportar.setOnClickListener { onReportar(alerta) }
    }

    /**
     * Devuelve la cantidad total de elementos visibles en el adaptador.
     *
     * @return Número de alertas actualmente mostradas.
     */
    override fun getItemCount(): Int = alertas.size

    /**
     * Formatea una distancia en metros a una cadena legible.
     *
     * Si la distancia es mayor o igual a 1000 metros, la convierte a kilómetros con un decimal.
     *
     * @param metros Distancia en metros.
     * @return Cadena formateada (por ejemplo, «1.5 km» o «500 m»).
     */
    private fun formatearMetros(metros: Double): String {
        return if (metros >= 1000) {
            val km = metros / 1000.0
            String.format(java.util.Locale.forLanguageTag("es-CL"), "%.1f km", km)
        } else {
            "${metros.toInt()} m"
        }
    }

    /**
     * Formatea una distancia en metros a una cadena legible, o devuelve un texto alternativo si es nula.
     *
     * @param metros Distancia en metros, o `null`.
     * @param textoNulo Texto a devolver cuando la distancia es `null`.
     * @return Cadena formateada o el texto alternativo.
     */
    private fun formatearMetrosNullable(metros: Double?, textoNulo: String): String {
        if (metros == null) {
            return textoNulo
        }
        return formatearMetros(metros)
    }

    /**
     * Formatea el radio de la alerta en metros a una cadena legible.
     *
     * @param radioMetros Radio en metros, o `null` si no aplica.
     * @return Cadena formateada o «Sin radio» si es `null`.
     */
    private fun formatearRadio(radioMetros: Double?): String {
        if (radioMetros == null) return "Sin radio"
        return formatearMetros(radioMetros)
    }

    /**
     * Formatea una marca de tiempo en milisegundos a una cadena con formato de fecha y hora.
     *
     * @param millis Marca de tiempo en milisegundos desde la época Unix.
     * @return Cadena con formato «dd/MM/yyyy HH:mm», o «Sin fecha» si el valor es inválido.
     */
    private fun formatearFecha(millis: Long): String {
        if (millis <= 0L) return "Sin fecha"
        val formato = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
        return formato.format(java.util.Date(millis))
    }
}

