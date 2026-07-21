package com.example.rutaalmacen.pagos

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.example.rutaalmacen.seguridad.PreferenciasCifradas
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Gestor de facturación a través de Google Play Billing Library.
 *
 * Encapsula toda la lógica de conexión con el servicio de facturación de Google Play,
 * incluyendo la consulta de detalles de productos de suscripción, el lanzamiento del flujo
 * de compra, la verificación de compras con la Cloud Function del servidor y la confirmación
 * (acknowledge) de compras en Google Play.
 *
 * Implementa [PurchasesUpdatedListener] para recibir notificaciones asíncronas cuando
 * el estado de las compras del usuario cambia.
 *
 * @property contexto Contexto de la aplicación utilizado para crear el cliente de facturación.
 * @property onError Función de devolución de llamada invocada cuando se produce un error comunicable al usuario.
 */
class BillingManager(
    private val contexto: Context,
    private val onError: (String) -> Unit = {},
) : PurchasesUpdatedListener {

    private var cliente: BillingClient? = null
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance()
    private val autenticacion: FirebaseAuth = FirebaseAuth.getInstance()
    private val tokensProcesados: MutableSet<String> = mutableSetOf()
    private val alcanceBilling = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Función de devolución de llamada invocada cuando una compra se completa y verifica exitosamente.
     *
     * Recibe el código del plan adquirido, el token de compra y la fecha de vencimiento en milisegundos.
     */
    var listenerCompra: ((CodigoPlan, String, Long) -> Unit)? = null

    /**
     * Función de devolución de llamada invocada cuando se completan las compras existentes restauradas.
     *
     * Recibe la lista de códigos de plan asociados a las compras activas encontradas.
     */
    var onComprasRestauradas: ((List<CodigoPlan>) -> Unit)? = null

    /**
     * Mapa que asocia los identificadores de producto de Google Play con su [CodigoPlan] correspondiente.
     *
     * Solo incluye los planes de pago, excluyendo el plan gratuito.
     */
    private val codigosSuscripcion = mapOf(
        CodigoPlan.VENDEDOR.id to CodigoPlan.VENDEDOR,
        CodigoPlan.COMERCIO.id to CodigoPlan.COMERCIO,
        CodigoPlan.EMPRESARIAL.id to CodigoPlan.EMPRESARIAL,
    )

    /**
     * Preferencias compartidas utilizadas para persistir tokens de compra pendientes
     * que requieren reintento en la siguiente conexión exitosa.
     */
    private val preferencias: SharedPreferences by lazy {
        PreferenciasCifradas.crear(PREFS)
    }

    /**
     * Listener interno que gestiona los eventos de conexión y desconexión del servicio de facturación.
     *
     * Cuando la conexión se establece exitosamente, dispara el reintento de tokens pendientes.
     * Cuando el servicio se desconecta, limpia la referencia del cliente e intenta reconectar.
     */
    private val conexionListener = object : BillingClientStateListener {
        override fun onBillingSetupFinished(result: BillingResult) {
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(TAG, "❌ onBillingSetupFinished FALLO: ${nombreCodigo(result.responseCode)} - ${result.debugMessage}")
                onError("Error al conectar con Play Billing: ${result.debugMessage}")
            } else {
                Log.i(TAG, "✅ onBillingSetupFinished OK - conexión lista")
                reintentarTokensPendientes()
            }
        }

        override fun onBillingServiceDisconnected() {
            Log.w(TAG, "⚠️ Servicio de facturación desconectado. Reconectando...")
            cliente = null
            iniciarConexion()
        }
    }

    /**
     * Inicia la conexión con el servicio de facturación de Google Play.
     *
     * Si el cliente ya se encuentra listo, no realiza ninguna acción para evitar reconexiones
     * innecesarias. Si el cliente no ha sido creado, lo instancia con la configuración de
     * compras pendientes habilitada.
     */
    fun iniciarConexion() {
        if (cliente?.isReady == true) {
            Log.d(TAG, "iniciarConexion: cliente ya listo, no se reconecta")
            return
        }
        if (cliente == null) {
            Log.i(TAG, "iniciarConexion: creando nuevo BillingClient")
            cliente = BillingClient.newBuilder(contexto)
                .setListener(this)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build(),
                )
                .build()
        }
        Log.i(TAG, "iniciarConexion: startConnection()...")
        cliente?.startConnection(conexionListener)
    }

    /**
     * Finaliza la conexión con el servicio de facturación y libera los recursos asociados.
     *
     * Debe invocarse cuando la actividad que utiliza este gestor se destruye.
     */
    fun terminarConexion() {
        cliente?.endConnection()
        cliente = null
    }

    /**
     * Consulta los detalles de los productos de suscripción disponibles en Google Play.
     *
     * Obtiene el precio formateado y el periodo de facturación de cada plan de pago
     * configurado en Google Play Console. Si un producto no se encuentra o no tiene
     * ofertas configuradas, se utiliza el precio predeterminado definido en [CodigoPlan].
     *
     * @return Mapa que asocia cada [CodigoPlan] con su [DetalleProductoSuscripcion] correspondiente.
     *         Retorna un mapa vacío si la conexión no puede establecerse o la consulta falla.
     */
    suspend fun obtenerDetallesProductos(): Map<CodigoPlan, DetalleProductoSuscripcion> {
        val clienteActivo = asegurarConexion() ?: run {
            Log.e(TAG, "❌ obtenerDetallesProductos: no se pudo asegurar conexión")
            return emptyMap()
        }

        Log.i(TAG, "obtenerDetallesProductos: consultando ${codigosSuscripcion.keys}")
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                codigosSuscripcion.keys.map { idProducto ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(idProducto)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                },
            )
            .build()

        return suspendCancellableCoroutine { cont ->
            clienteActivo.queryProductDetailsAsync(params) { resultado, lista ->
                if (resultado.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.e(TAG, "❌ queryProductDetailsAsync FALLO: ${nombreCodigo(resultado.responseCode)} - ${resultado.debugMessage}")
                    onError("No se pudieron cargar los precios: ${resultado.debugMessage}")
                    cont.resume(emptyMap())
                    return@queryProductDetailsAsync
                }
                Log.i(TAG, "✅ queryProductDetailsAsync OK: ${lista.size} productos devueltos por Google Play")
                if (lista.isEmpty()) {
                    Log.w(TAG, "⚠️ Google Play NO devolvió productos. Posibles causas:")
                    Log.w(TAG, "   1. Los productos no fueron creados en Play Console.")
                    Log.w(TAG, "   2. Los Product IDs no coinciden (esperados: ${codigosSuscripcion.keys})")
                    Log.w(TAG, "   3. La app no fue subida a Internal/Closed/Open testing.")
                    Log.w(TAG, "   4. La cuenta Gmail del dispositivo NO está en License Testers.")
                    Log.w(TAG, "   5. La app fue instalada por Android Studio en vez de Play Store.")
                }
                val mapa = mutableMapOf<CodigoPlan, DetalleProductoSuscripcion>()
                lista.forEach { detalle ->
                    Log.d(TAG, "   - Producto encontrado: ${detalle.productId} title=${detalle.title}")
                    val codigo = codigosSuscripcion[detalle.productId] ?: return@forEach
                    // Se prioriza la oferta con la etiqueta «plan-activo»; si no existe, se toma la primera disponible
                    val oferta = detalle.subscriptionOfferDetails
                        ?.firstOrNull { it.offerTags.contains("plan-activo") }
                        ?: detalle.subscriptionOfferDetails?.firstOrNull()
                    if (oferta == null) {
                        Log.w(TAG, "   ⚠️ Producto ${detalle.productId} sin ofertas configuradas")
                    }
                    val precio = oferta?.pricingPhases?.pricingPhaseList
                        ?.firstOrNull()
                        ?.formattedPrice
                        ?: detalle.oneTimePurchaseOfferDetails?.formattedPrice
                        ?: codigo.precioFormateado
                    val periodo = oferta?.pricingPhases?.pricingPhaseList
                        ?.firstOrNull()
                        ?.billingPeriod
                    mapa[codigo] = DetalleProductoSuscripcion(
                        codigo = codigo,
                        precioFormateado = precio,
                        periodo = periodo,
                    )
                }
                cont.resume(mapa)
            }
        }
    }

    /**
     * Inicia el flujo de compra de un plan de suscripción en Google Play.
     *
     * Consulta los detalles del producto solicitado, selecciona la oferta de suscripción
     * apropiada y lanza la interfaz de compra de Google Play. Si el cliente de facturación
     * no está listo o no hay un usuario autenticado, se notifica el error al usuario.
     *
     * @param activity Actividad desde la cual se presenta la interfaz de compra de Google Play.
     * @param codigoPlan Código del plan de suscripción que el usuario desea adquirir.
     */
    fun lanzarFlujoCompra(activity: Activity, codigoPlan: CodigoPlan) {
        Log.i(TAG, "🛒 lanzarFlujoCompra: plan=${codigoPlan.id}")
        val clienteActivo = cliente
        if (clienteActivo == null || !clienteActivo.isReady) {
            Log.e(TAG, "❌ Cliente Billing no listo (cliente=$clienteActivo, isReady=${clienteActivo?.isReady})")
            onError("La conexión con Google Play aún no está lista. Intenta nuevamente.")
            iniciarConexion()
            return
        }
        val uid = autenticacion.currentUser?.uid
        if (uid.isNullOrBlank()) {
            Log.e(TAG, "❌ No hay usuario autenticado")
            onError("Debes iniciar sesión antes de comprar un plan")
            return
        }
        Log.d(TAG, "lanzarFlujoCompra: uid=$uid")
        val idProducto = codigoPlan.id

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(idProducto)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ),
            )
            .build()

        clienteActivo.queryProductDetailsAsync(params) { resultado, lista ->
            if (resultado.responseCode != BillingClient.BillingResponseCode.OK || lista.isEmpty()) {
                Log.e(TAG, "❌ queryProductDetailsAsync para $idProducto: ${nombreCodigo(resultado.responseCode)}, lista vacía=${lista.isEmpty()}")
                onError(mensajePorCodigo(resultado.responseCode, resultado.debugMessage))
                return@queryProductDetailsAsync
            }
            val detalle = lista.first()
            // Se prioriza la oferta con la etiqueta «plan-activo»; si no existe, se toma la primera disponible
            val oferta = detalle.subscriptionOfferDetails
                ?.firstOrNull { it.offerTags.contains("plan-activo") }
                ?: detalle.subscriptionOfferDetails?.firstOrNull()
            if (oferta == null) {
                Log.e(TAG, "❌ Producto $idProducto sin offer (¿le falta el plan base o el tag 'plan-activo'?)")
                onError("El plan no tiene una oferta activa configurada en Google Play.")
                return@queryProductDetailsAsync
            }
            Log.i(TAG, "lanzarFlujoCompra: offer encontrada, offerToken=${oferta.offerToken.take(20)}...")

            val paramsCompra = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(detalle)
                            .setOfferToken(oferta.offerToken)
                            .build(),
                    ),
                )
                .setObfuscatedAccountId(uid)
                .build()

            val resultadoLanzamiento = clienteActivo.launchBillingFlow(activity, paramsCompra)
            if (resultadoLanzamiento.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(TAG, "❌ launchBillingFlow falló: ${nombreCodigo(resultadoLanzamiento.responseCode)}")
                onError(mensajePorCodigo(resultadoLanzamiento.responseCode, resultadoLanzamiento.debugMessage))
            } else {
                Log.i(TAG, "✅ launchBillingFlow OK - Google Play debe mostrar la pantalla de compra")
            }
        }
    }

    /**
     * Recibe las notificaciones de Google Play cuando el estado de las compras del usuario cambia.
     *
     * Procesa cada código de respuesta de forma diferenciada: las compras exitosas se manejan
     * internamente, las cancelaciones se registran, los productos ya poseídos disparan una
     * restauración automática y los errores de red intentan reconectar.
     *
     * @param result Resultado de la operación de facturación que incluye el código de respuesta y mensaje de depuración.
     * @param purchases Lista de compras actualizadas, o `null` si no hubo compras involucradas.
     */
    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        Log.i(TAG, "📩 onPurchasesUpdated: code=${nombreCodigo(result.responseCode)}, compras=${purchases?.size ?: 0}")
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { compra -> manejarCompra(compra) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "Compra cancelada por el usuario")
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.w(TAG, "ITEM_ALREADY_OWNED: usuario ya tiene una sub. Restaurando...")
                onError("Ya tienes una suscripción activa. Restaurando...")
                alcanceBilling.launch {
                    val codigos = restaurarComprasExistentes()
                    onComprasRestauradas?.invoke(codigos)
                }
            }
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.NETWORK_ERROR -> {
                Log.e(TAG, "❌ Error de red/servicio: ${result.debugMessage}")
                onError("Sin conexión con Google Play. Verifica tu red e intenta nuevamente.")
                iniciarConexion()
            }
            else -> {
                Log.e(TAG, "❌ Error desconocido: ${result.debugMessage}")
                onError(mensajePorCodigo(result.responseCode, result.debugMessage))
            }
        }
    }

    /**
     * Procesa una compra individual según su estado actual.
     *
     * Si la compra está en estado [Purchase.PurchaseState.PURCHASED], se envía a verificación
     * con el servidor. Si está en estado [Purchase.PurchaseState.PENDING] (por ejemplo, pago
     * en efectivo o tarjeta de regalo), se almacena el token para reintento posterior.
     *
     * @param compra Objeto [Purchase] recibido desde Google Play.
     */
    private fun manejarCompra(compra: Purchase) {
        Log.i(TAG, "manejarCompra: state=${compra.purchaseState} ack=${compra.isAcknowledged} products=${compra.products}")
        when (compra.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> procesarCompraConfirmada(compra)
            Purchase.PurchaseState.PENDING -> {
                Log.w(TAG, "Compra en estado PENDING (efectivo/gift card). Esperando confirmación.")
                onError("Pago pendiente. Activaremos tu plan cuando Google confirme el pago.")
                guardarTokenPendiente(compra.purchaseToken)
            }
            else -> Log.d(TAG, "Compra en estado ${compra.purchaseState}, se ignora")
        }
    }

    /**
     * Procesa una compra confirmada enviándola a verificación con la Cloud Function del servidor.
     *
     * Verifica que la compra tenga productos asociados, que el producto corresponda a un plan
     * conocido y que el token no haya sido procesado previamente en la sesión actual para
     * evitar duplicaciones.
     *
     * @param compra Objeto [Purchase] en estado confirmado.
     */
    private fun procesarCompraConfirmada(compra: Purchase) {
        if (compra.products.isEmpty()) {
            Log.w(TAG, "Compra sin productos asociados")
            return
        }
        val idProducto = compra.products.first()
        val codigoPlan = codigosSuscripcion[idProducto] ?: run {
            Log.w(TAG, "Producto no reconocido: $idProducto")
            return
        }
        if (tokensProcesados.contains(compra.purchaseToken)) {
            Log.d(TAG, "Token ya procesado en esta sesión, se ignora")
            return
        }
        Log.i(TAG, "procesarCompraConfirmada: enviando a Cloud Function para verificar plan=$codigoPlan")
        verificarCompraEnServidor(compra, codigoPlan)
    }

    /**
     * Envía el token de compra a la Cloud Function `verificarCompraSuscripcion` para validación
     * del lado del servidor.
     *
     * Si la verificación es exitosa, se confirma la compra en Google Play, se elimina el token
     * de la cola de pendientes y se notifica al listener de compra. Si la verificación falla
     * o la llamada al servidor produce un error, el token se almacena para reintento posterior.
     *
     * @param compra Objeto [Purchase] con los datos de la transacción.
     * @param codigoPlan Código del plan asociado a la compra.
     */
    private fun verificarCompraEnServidor(compra: Purchase, codigoPlan: CodigoPlan) {
        val uid = autenticacion.currentUser?.uid
        if (uid.isNullOrBlank()) {
            Log.e(TAG, "❌ Sin sesión al verificar compra")
            onError("Sesión expirada. Vuelve a iniciar sesión para activar tu plan.")
            guardarTokenPendiente(compra.purchaseToken)
            return
        }
        Log.i(TAG, "📡 Llamando Cloud Function verificarCompraSuscripcion...")
        val data = hashMapOf(
            "purchaseToken" to compra.purchaseToken,
            "productId" to compra.products.firstOrNull().orEmpty(),
            "platform" to "android",
        )

        functions
            .getHttpsCallable("verificarCompraSuscripcion")
            .call(data)
            .addOnSuccessListener { resultado ->
                @Suppress("UNCHECKED_CAST")
                val datos = resultado.getData() as? Map<String, Any?>
                Log.i(TAG, "📩 Respuesta Cloud Function: $datos")
                val verificado = datos?.get("valido") as? Boolean ?: false
                if (verificado) {
                    val fechaVencimiento = (datos["fechaVencimiento"] as? Number)?.toLong()
                    if (fechaVencimiento == null || fechaVencimiento <= System.currentTimeMillis()) {
                        Log.e(TAG, "❌ Fecha vencimiento inválida: $fechaVencimiento")
                        onError("La suscripción ya está vencida según Google Play.")
                        return@addOnSuccessListener
                    }
                    Log.i(TAG, "✅ Compra verificada. Vence: ${java.util.Date(fechaVencimiento)}")
                    tokensProcesados.add(compra.purchaseToken)
                    eliminarTokenPendiente(compra.purchaseToken)
                    confirmarCompraEnGooglePlay(compra)
                    listenerCompra?.invoke(codigoPlan, compra.purchaseToken, fechaVencimiento)
                } else {
                    val motivo = (datos?.get("motivo") as? String) ?: "verificación rechazada"
                    Log.e(TAG, "❌ Cloud Function rechazó la compra: $motivo")
                    onError("La compra no pudo ser verificada por el servidor: $motivo")
                    guardarTokenPendiente(compra.purchaseToken)
                }
            }
            .addOnFailureListener { excepcion ->
                Log.e(TAG, "❌ Falló llamada a Cloud Function (¿función desplegada?)", excepcion)
                onError("No se pudo verificar la compra con el servidor. Reintentaremos al volver a abrir la app.")
                guardarTokenPendiente(compra.purchaseToken)
            }
    }

    /**
     * Confirma (acknowledge) la compra en Google Play para evitar que el reembolso automático la revoque.
     *
     * Si la compra ya se encuentra confirmada, no realiza ninguna acción adicional.
     *
     * @param compra Objeto [Purchase] que debe ser confirmado ante Google Play.
     */
    private fun confirmarCompraEnGooglePlay(compra: Purchase) {
        if (compra.isAcknowledged) {
            Log.d(TAG, "Compra ya estaba acknowledged")
            return
        }
        Log.i(TAG, "✋ acknowledgePurchase: enviando ACK a Google Play")
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(compra.purchaseToken)
            .build()
        cliente?.acknowledgePurchase(params) { resultado ->
            if (resultado.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "⚠️ acknowledgePurchase falló: ${resultado.debugMessage}")
                guardarTokenPendiente(compra.purchaseToken)
            } else {
                Log.i(TAG, "✅ acknowledgePurchase OK")
            }
        }
    }

    /**
     * Consulta y restaura las compras activas existentes del usuario en Google Play.
     *
     * Utiliza [BillingClient.queryPurchasesAsync] para obtener todas las suscripciones activas
     * y procesa cada compra confirmada a través del flujo de verificación con el servidor.
     *
     * @return Lista de [CodigoPlan] asociados a las compras activas encontradas, sin duplicados.
     */
    suspend fun restaurarComprasExistentes(): List<CodigoPlan> {
        val clienteActivo = asegurarConexion() ?: run {
            Log.e(TAG, "❌ restaurarComprasExistentes: sin conexión")
            return emptyList()
        }
        Log.i(TAG, "🔄 restaurarComprasExistentes: consultando compras activas...")

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        val compras = suspendCancellableCoroutine<List<Purchase>> { cont ->
            clienteActivo.queryPurchasesAsync(params) { resultado, lista ->
                if (resultado.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.e(TAG, "❌ queryPurchasesAsync FALLO: ${nombreCodigo(resultado.responseCode)}")
                    cont.resume(emptyList())
                } else {
                    Log.i(TAG, "✅ queryPurchasesAsync OK: ${lista.size} compras encontradas")
                    cont.resume(lista)
                }
            }
        }

        val activas = compras.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        Log.i(TAG, "restaurarComprasExistentes: ${activas.size} activas, procesando...")
        activas.forEach { compra -> procesarCompraConfirmada(compra) }

        return activas
            .flatMap { it.products }
            .mapNotNull { codigosSuscripcion[it] }
            .distinct()
    }

    /**
     * Reintenta el procesamiento de tokens de compra que quedaron pendientes en sesiones anteriores.
     *
     * Lee los tokens almacenados en las preferencias compartidas y, si existen, dispara
     * una restauración completa de compras para que el servidor los reprocese.
     */
    private fun reintentarTokensPendientes() {
        val pendientes = preferencias.getStringSet(KEY_TOKENS_PENDIENTES, emptySet()).orEmpty()
        if (pendientes.isEmpty()) return
        alcanceBilling.launch {
            restaurarComprasExistentes()
        }
    }

    /**
     * Persiste un token de compra pendiente en las preferencias compartidas para reintento posterior.
     *
     * @param token Token de compra que no pudo ser procesado exitosamente.
     */
    private fun guardarTokenPendiente(token: String) {
        val actuales = preferencias.getStringSet(KEY_TOKENS_PENDIENTES, emptySet()).orEmpty().toMutableSet()
        actuales.add(token)
        preferencias.edit().putStringSet(KEY_TOKENS_PENDIENTES, actuales).apply()
    }

    /**
     * Elimina un token de compra de la cola de pendientes una vez que fue procesado exitosamente.
     *
     * @param token Token de compra que ya fue verificado y confirmado.
     */
    private fun eliminarTokenPendiente(token: String) {
        val actuales = preferencias.getStringSet(KEY_TOKENS_PENDIENTES, emptySet()).orEmpty().toMutableSet()
        if (actuales.remove(token)) {
            preferencias.edit().putStringSet(KEY_TOKENS_PENDIENTES, actuales).apply()
        }
    }

    /**
     * Garantiza que el cliente de facturación se encuentre conectado antes de realizar operaciones.
     *
     * Intenta establecer la conexión hasta tres veces con un intervalo de espera entre cada intento.
     *
     * @return La instancia de [BillingClient] si la conexión fue exitosa, o `null` si no se pudo establecer.
     */
    private suspend fun asegurarConexion(): BillingClient? {
        var intentos = 0
        while (cliente?.isReady != true && intentos < 3) {
            iniciarConexion()
            kotlinx.coroutines.delay(500)
            intentos++
        }
        return cliente
    }

    /**
     * Genera un mensaje de error legible para el usuario a partir del código de respuesta de Google Play.
     *
     * @param codigo Código numérico de respuesta de [BillingClient.BillingResponseCode].
     * @param debug Mensaje de depuración opcional proporcionado por Google Play.
     * @return Texto descriptivo del error apropiado para mostrar en la interfaz de usuario.
     */
    private fun mensajePorCodigo(codigo: Int, debug: String?): String {
        return when (codigo) {
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
                "Google Play Billing no está disponible en este dispositivo."
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
                "Este plan aún no está publicado en Google Play."
            BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
                "Error de configuración. Contacta al soporte."
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ->
                "Tu versión de Google Play no soporta suscripciones."
            BillingClient.BillingResponseCode.NETWORK_ERROR,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ->
                "Sin conexión con Google Play. Reintenta más tarde."
            else -> "Error de Google Play (código $codigo): ${debug.orEmpty()}"
        }
    }

    /**
     * Convierte un código numérico de respuesta de Google Play en su nombre legible para registro.
     *
     * @param codigo Código numérico de respuesta de [BillingClient.BillingResponseCode].
     * @return Nombre legible del código de respuesta, o `DESCONOCIDO(codigo)` si no está mapeado.
     */
    private fun nombreCodigo(codigo: Int): String = when (codigo) {
        BillingClient.BillingResponseCode.OK -> "OK"
        BillingClient.BillingResponseCode.USER_CANCELED -> "USER_CANCELED"
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE"
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "ITEM_UNAVAILABLE"
        BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "DEVELOPER_ERROR"
        BillingClient.BillingResponseCode.ERROR -> "ERROR"
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED"
        BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> "ITEM_NOT_OWNED"
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
        BillingClient.BillingResponseCode.NETWORK_ERROR -> "NETWORK_ERROR"
        else -> "DESCONOCIDO($codigo)"
    }

    companion object {
        /** Etiqueta utilizada para los registros de depuración de esta clase. */
        private const val TAG = "BillingManager"
        /** Nombre del archivo de preferencias compartidas para almacenamiento local de tokens. */
        private const val PREFS = "billing_manager_prefs"
        /** Clave utilizada para almacenar el conjunto de tokens de compra pendientes en preferencias. */
        private const val KEY_TOKENS_PENDIENTES = "tokens_pendientes"
    }
}

/**
 * Modelo de datos que representa los detalles de un producto de suscripción obtenidos desde Google Play.
 *
 * @property codigo Código de plan asociado a este producto de suscripción.
 * @property precioFormateado Precio del producto formateado con símbolo de moneda, tal como lo proporciona Google Play.
 * @property periodo Periodo de facturación de la suscripción (por ejemplo, «P1M» para mensual), o `null` si no está disponible.
 */
data class DetalleProductoSuscripcion(
    val codigo: CodigoPlan,
    val precioFormateado: String,
    val periodo: String?,
)
