package com.example.rutaalmacen.pagos

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rutaalmacen.R
import com.example.rutaalmacen.databinding.ActivityPlanSuscripcionBinding
import com.example.rutaalmacen.databinding.ItemPlanBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Actividad que presenta la pantalla de planes de suscripción al usuario.
 *
 * Muestra el estado actual de la suscripción del usuario, lista todos los planes disponibles
 * con sus precios y características, y gestiona el flujo de compra a través de [BillingManager].
 * Soporta la compra de nuevos planes, la cancelación mediante Google Play y la restauración
 * de compras existentes.
 *
 * Utiliza ViewBinding para el acceso a las vistas y un [RecyclerView] con un adaptador
 * interno ([PlanAdapter]) para renderizar la lista de planes.
 */
class PlanSuscripcionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlanSuscripcionBinding
    private lateinit var billingManager: BillingManager
    private val planManager = PlanManager()
    private var estadoActual: EstadoSuscripcion = EstadoSuscripcion(plan = Plan.GRATIS)
    private val adaptador by lazy { PlanAdapter() }

    /**
     * Inicializa la actividad, configura el ViewBinding, la barra de herramientas con borde a borde,
     * el gestor de facturación y el adaptador de la lista de planes.
     *
     * Establece los listeners de compra y restauración, inicia la conexión con Google Play,
     * restaura las compras existentes y carga el estado actual de la suscripción.
     *
     * @param savedInstanceState Estado guardado de la actividad anterior, o `null` si es una creación nueva.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPlanSuscripcionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        binding.toolbarPlanes.setNavigationOnClickListener { finish() }

        billingManager = BillingManager(this) { mensaje ->
            Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
        }
        billingManager.listenerCompra = { codigo, token, fecha ->
            lifecycleScope.launch {
                try {
                    planManager.aplicarCompra(codigo, token, fecha)
                    Toast.makeText(
                        this@PlanSuscripcionActivity,
                        "¡Suscripción ${codigo.name.lowercase().replaceFirstChar { it.uppercase() }} activada!",
                        Toast.LENGTH_LONG,
                    ).show()
                    cargarEstado()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@PlanSuscripcionActivity,
                        "No se pudo guardar la suscripción. Reintentaremos al abrir la app.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        billingManager.onComprasRestauradas = { _ -> cargarEstado() }
        billingManager.iniciarConexion()
        lifecycleScope.launch {
            try {
                billingManager.restaurarComprasExistentes()
            } catch (_: Exception) {
                // El BillingManager ya notifica errores vía onError
            }
        }

        binding.recyclerPlanes.layoutManager = LinearLayoutManager(this)
        binding.recyclerPlanes.adapter = adaptador

        adaptador.listener = { plan ->
            if (plan.esGratis) {
                abrirGestorSuscripcionGooglePlay()
            } else {
                confirmarCompra(plan)
            }
        }

        cargarEstado()
    }

    /**
     * Abre la página de gestión de suscripciones de Google Play en el navegador o en la app de Play Store.
     *
     * Si el usuario tiene un plan de pago activo, construye la URI con el identificador del producto
     * y el nombre del paquete para dirigir directamente a la suscripción correspondiente.
     */
    private fun abrirGestorSuscripcionGooglePlay() {
        val uriBase = "https://play.google.com/store/account/subscriptions"
        val planActivo = if (!estadoActual.plan.esGratis) {
            "?sku=${estadoActual.plan.codigo.id}&package=$packageName"
        } else {
            ""
        }
        val uri = android.net.Uri.parse("$uriBase$planActivo")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Abre la app de Google Play → Suscripciones para cancelar tu plan.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Carga el estado actual de la suscripción del usuario desde Firestore y actualiza la interfaz.
     *
     * Ejecuta la consulta de forma asíncrona en el alcance del ciclo de vida de la actividad
     * y, al completarse, refresca tanto el resumen del estado como la lista de planes disponibles.
     */
    private fun cargarEstado() {
        lifecycleScope.launch {
            estadoActual = planManager.cargarEstadoActual(this@PlanSuscripcionActivity)
            mostrarEstadoActual()
            mostrarPlanes()
        }
    }

    /**
     * Actualiza las vistas de resumen con la información del plan actual del usuario.
     *
     * Muestra el nombre y precio del plan contratado, la cantidad de productos registrados
     * frente al máximo permitido y la cantidad de alertas emitidas hoy frente al límite diario.
     */
    private fun mostrarEstadoActual() {
        val plan = estadoActual.plan
        binding.textoPlanActual.text = "Tu plan actual: ${plan.nombre} — ${plan.codigo.precioFormateado}"
        val productosTexto =
            "${estadoActual.productosActuales}/${plan.maxProductos} productos"
        val alertasTexto =
            if (plan.maxAlertasPorDia == Int.MAX_VALUE) "Alertas ilimitadas"
            else "${estadoActual.alertasHoy}/${plan.maxAlertasPorDia} alertas hoy"
        binding.textoUsoActual.text = "$productosTexto · $alertasTexto"
    }

    /**
     * Consulta los precios actualizados desde Google Play y los pasa al adaptador para renderizar la lista.
     *
     * Combina la lista estática de planes con los precios dinámicos obtenidos de la API de facturación.
     */
    private fun mostrarPlanes() {
        lifecycleScope.launch {
            val precios = billingManager.obtenerDetallesProductos()
            adaptador.actualizar(Plan.TODOS, estadoActual.plan, precios)
        }
    }

    /**
     * Presenta un diálogo de confirmación antes de iniciar el flujo de compra de un plan.
     *
     * Informa al usuario sobre el cobro mensual a través de Google Play y la posibilidad
     * de cancelar en cualquier momento. Si el usuario confirma, se invoca
     * [BillingManager.lanzarFlujoCompra].
     *
     * @param plan Plan que el usuario desea adquirir.
     */
    private fun confirmarCompra(plan: Plan) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Suscribirse a ${plan.nombre}")
            .setMessage(
                "Se cobrará ${plan.codigo.precioFormateado} al mes a través de Google Play.\n\n" +
                    "Podrás cancelar en cualquier momento desde tu cuenta de Google.",
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ ->
                billingManager.lanzarFlujoCompra(this, plan.codigo)
            }
            .show()
    }

    /**
     * Recarga el estado de la suscripción cada vez que la actividad vuelve a primer plano.
     */
    override fun onResume() {
        super.onResume()
        cargarEstado()
    }

    /**
     * Libera los recursos del gestor de facturación al destruir la actividad.
     */
    override fun onDestroy() {
        super.onDestroy()
        billingManager.terminarConexion()
    }

    /**
     * Adaptador del [RecyclerView] que renderiza la lista de planes de suscripción disponibles.
     *
     * Mantiene la lista de planes, el plan actualmente contratado por el usuario y los precios
     * dinámicos obtenidos desde Google Play. Notifica al listener externo cuando el usuario
     * selecciona un plan.
     */
    private inner class PlanAdapter : RecyclerView.Adapter<PlanAdapter.VistaPlan>() {

        private var planes: List<Plan> = Plan.TODOS
        private var planActual: Plan = Plan.GRATIS
        private var precios: Map<CodigoPlan, DetalleProductoSuscripcion> = emptyMap()

        /** Listener externo que recibe el plan seleccionado por el usuario. */
        var listener: ((Plan) -> Unit)? = null

        /**
         * Reemplaza los datos del adaptador con los planes, el plan actual y los precios actualizados,
         * y notifica al [RecyclerView] que debe redibujar todas las vistas.
         *
         * @param nuevosPlanes Lista completa de planes disponibles.
         * @param planActual Plan que el usuario tiene contratado actualmente.
         * @param precios Mapa de precios dinámicos obtenidos desde Google Play, indexados por [CodigoPlan].
         */
        fun actualizar(
            nuevosPlanes: List<Plan>,
            planActual: Plan,
            precios: Map<CodigoPlan, DetalleProductoSuscripcion>,
        ) {
            this.planes = nuevosPlanes
            this.planActual = planActual
            this.precios = precios
            notifyDataSetChanged()
        }

        /**
         * Crea una nueva vista de elemento de plan inflando el diseño [ItemPlanBinding].
         *
         * @param parent Vista contenedora donde se insertará el nuevo elemento.
         * @param viewType Tipo de vista del elemento (no utilizado en este adaptador).
         * @return Instancia de [VistaPlan] que contiene la referencia al diseño inflado.
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VistaPlan {
            val binding = ItemPlanBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return VistaPlan(binding)
        }

        /**
         * Vincula los datos del plan en la posición indicada con la vista del elemento.
         *
         * @param holder Vista del elemento que debe actualizarse con los datos.
         * @param position Posición del elemento dentro de la lista de planes.
         */
        override fun onBindViewHolder(holder: VistaPlan, position: Int) {
            holder.vincular(planes[position], planActual, precios[planes[position].codigo], listener)
        }

        /**
         * @return Cantidad total de planes en la lista.
         */
        override fun getItemCount(): Int = planes.size

        /**
         * Vista individual de un elemento de plan dentro del [RecyclerView].
         *
         * Se encarga de poblar todos los campos visuales de la tarjeta de plan, incluyendo
         * nombre, descripción, precio, periodo, límites de uso, funciones premium disponibles
         * y el estado del botón de selección.
         *
         * @property binding Referencia al ViewBinding del elemento de plan.
         */
        inner class VistaPlan(private val binding: ItemPlanBinding) :
            RecyclerView.ViewHolder(binding.root) {

            /**
             * Vincula los datos de un plan con los campos visuales de su tarjeta en la lista.
             *
             * Configura el nombre, la descripción, el precio, el periodo de facturación, los límites
             * de productos y alertas, las funciones premium disponibles, la gestión de almacenes y
             * el estado del botón de selección según el plan sea el actual, el gratuito o uno disponible.
             *
             * @param plan Plan que se va a representar en esta tarjeta.
             * @param planActual Plan que el usuario tiene contratado actualmente.
             * @param detalle Detalles del producto obtenidos desde Google Play, o `null` si no están disponibles.
             * @param listener Función de devolución de llamada invocada al pulsar el botón de selección.
             */
            fun vincular(
                plan: Plan,
                planActual: Plan,
                detalle: DetalleProductoSuscripcion?,
                listener: ((Plan) -> Unit)?,
            ) {
                binding.textoNombrePlan.text = plan.nombre
                binding.textoDescripcionPlan.text = plan.descripcionCorta
                val precioMostrar = detalle?.precioFormateado ?: plan.codigo.precioFormateado
                binding.textoPrecioPlan.text = precioMostrar
                binding.textoPeriodoPlan.text = if (plan.esGratis) "Sin costo" else "por mes"
                binding.chipPlanActual.visibility =
                    if (plan.codigo == planActual.codigo) View.VISIBLE else View.GONE

                binding.textoMaxProductos.text = "Hasta ${plan.maxProductos} productos"
                binding.textoMaxAlertas.text =
                    if (plan.maxAlertasPorDia == Int.MAX_VALUE) "Alertas ilimitadas por día"
                    else "Hasta ${plan.maxAlertasPorDia} alertas por día"

                // Se construye dinámicamente la lista de funciones premium habilitadas para este plan
                val funciones = mutableListOf<String>()
                if (plan.permiteOcr) funciones.add("Escanear boletas (OCR)")
                if (plan.permiteImportar) funciones.add("Importar desde Excel/archivo")
                if (plan.permiteVoz) funciones.add("Agregar productos por voz")
                binding.textoFunciones.text =
                    if (funciones.isEmpty()) "Sin funciones premium"
                    else funciones.joinToString(" + ")

                binding.textoAlmacenes.text =
                    if (plan.permiteAlmacenesMultiples) "Varios almacenes"
                    else "Un solo almacén"

                binding.botonSeleccionarPlan.text = when {
                    plan.codigo == planActual.codigo -> "Tu plan actual"
                    plan.esGratis -> "Cancelar en Google Play"
                    else -> "Elegir ${plan.nombre}"
                }
                binding.botonSeleccionarPlan.isEnabled = plan.codigo != planActual.codigo
                binding.botonSeleccionarPlan.setOnClickListener { listener?.invoke(plan) }
            }
        }
    }
}
