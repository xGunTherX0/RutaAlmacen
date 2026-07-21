package com.example.rutaalmacen.pagos

import com.example.rutaalmacen.Constantes
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.Calendar

/**
 * Representa el estado completo de la suscripción de un usuario en un momento dado.
 *
 * Incluye el plan contratado, los conteos de uso actuales (productos registrados y alertas
 * emitidas en el día), la fecha de vencimiento y el estado de verificación de la compra
 * en el servidor.
 *
 * @property plan Plan de suscripción actualmente activo para el usuario.
 * @property productosActuales Cantidad de productos que el usuario tiene registrados en su inventario.
 * @property alertasHoy Cantidad de alertas de IA emitidas durante el día en curso.
 * @property fechaVencimientoMillis Marca de tiempo en milisegundos de la fecha de vencimiento de la suscripción,
 *                                  o `null` si el usuario se encuentra en el plan gratuito.
 * @property compraVerificadaServidor Indica si la compra fue verificada exitosamente por la Cloud Function del servidor.
 */
data class EstadoSuscripcion(
    val plan: Plan,
    val productosActuales: Int = 0,
    val alertasHoy: Int = 0,
    val fechaVencimientoMillis: Long? = null,
    val compraVerificadaServidor: Boolean = false,
) {
    /**
     * Determina si la suscripción de pago se encuentra activa en este momento.
     *
     * Una suscripción se considera activa cuando el plan no es gratuito y la fecha de
     * vencimiento es posterior al instante actual.
     */
    val suscripcionActiva: Boolean
        get() = !plan.esGratis && fechaVencimientoMillis?.let { it > System.currentTimeMillis() } == true

    /**
     * Calcula la cantidad de espacios disponibles para registrar nuevos productos.
     *
     * El valor mínimo retornado es cero para evitar números negativos cuando el usuario
     * ha alcanzado o superado el límite de su plan.
     */
    val espaciosDisponiblesProductos: Int
        get() = (plan.maxProductos - productosActuales).coerceAtLeast(0)
}

/**
 * Gestor principal de la lógica de planes y suscripciones de la aplicación.
 *
 * Se encarga de consultar el estado de la suscripción del usuario desde Firestore,
 * validar las acciones del usuario contra las restricciones de su plan y aplicar
 * los cambios derivados de una compra o cancelación.
 *
 * @property baseDatos Instancia de [FirebaseFirestore] utilizada para acceder a los datos del usuario.
 * @property autenticacion Instancia de [FirebaseAuth] utilizada para obtener el usuario autenticado.
 */
class PlanManager(
    private val baseDatos: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val autenticacion: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    /**
     * Carga el estado completo de la suscripción del usuario autenticado desde Firestore.
     *
     * Consulta el documento del usuario para obtener el plan contratado, la fecha de vencimiento
     * y el estado de verificación de compra. Además, consulta los conteos de productos registrados
     * y alertas emitidas en el día.
     *
     * Si no hay un usuario autenticado o se produce un error en la consulta, se devuelve
     * un estado correspondiente al plan gratuito como valor de respaldo.
     *
     * @return Estado de suscripción del usuario con todos los datos de uso y plan actual.
     */
    suspend fun cargarEstadoActual(): EstadoSuscripcion {
        val usuario = autenticacion.currentUser
            ?: return EstadoSuscripcion(plan = Plan.GRATIS)

        return try {
            val documentoUsuario = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(usuario.uid)
                .get()
                .await()

            val codigoPlan = CodigoPlan.desdeId(documentoUsuario.getString("plan"))
            val plan = Plan.desdeCodigo(codigoPlan)
            val fechaVencimiento = documentoUsuario.getLong("planVencimiento")?.takeIf { it > 0 }
            val compraVerificada = documentoUsuario.getBoolean("compraVerificada") ?: false

            val (productos, alertas) = cargarConteos(usuario.uid, fechaVencimiento)

            EstadoSuscripcion(
                plan = plan,
                productosActuales = productos,
                alertasHoy = alertas,
                fechaVencimientoMillis = fechaVencimiento,
                compraVerificadaServidor = compraVerificada,
            )
        } catch (e: Exception) {
            EstadoSuscripcion(plan = Plan.GRATIS)
        }
    }

    /**
     * Carga los conteos de uso del usuario: productos registrados y alertas emitidas hoy.
     *
     * @param uid Identificador único del usuario en Firebase.
     * @param fechaVencimiento Marca de tiempo de vencimiento del plan, utilizada como contexto adicional.
     * @return Par con la cantidad de productos y la cantidad de alertas del día.
     */
    private suspend fun cargarConteos(uid: String, fechaVencimiento: Long?): Pair<Int, Int> {
        val productos = contarProductos(uid)
        val alertas = contarAlertasHoy(uid)
        return productos to alertas
    }

    /**
     * Cuenta la cantidad de productos registrados en el inventario del usuario.
     *
     * Consulta la subcolección «Inventario» del documento del usuario en Firestore.
     * En caso de error, devuelve cero para no bloquear el flujo de la aplicación.
     *
     * @param uid Identificador único del usuario en Firebase.
     * @return Cantidad total de productos en el inventario del usuario.
     */
    private suspend fun contarProductos(uid: String): Int {
        return try {
            baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(uid)
                .collection("Inventario")
                .get()
                .await()
                .size()
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Cuenta las alertas de IA emitidas para el usuario durante el día en curso.
     *
     * Intenta primero una consulta compuesta con filtro por fecha; si falla (por ejemplo,
     * por falta de un índice adecuado), realiza una consulta sin filtro de fecha y filtra
     * los resultados en memoria comparando la marca de tiempo con el inicio del día.
     *
     * @param uid Identificador único del usuario en Firebase.
     * @return Cantidad de alertas de IA creadas desde las 00:00 del día actual.
     */
    private suspend fun contarAlertasHoy(uid: String): Int {
        // Se calcula el inicio del día actual en milisegundos para usar como umbral de filtrado
        val inicioDelDia = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        return try {
            baseDatos.collection(Constantes.COLECCION_NOTIFICACIONES_IA)
                .whereEqualTo("vendedorId", uid)
                .whereGreaterThanOrEqualTo("fechaCreacion", inicioDelDia)
                .get()
                .await()
                .size()
        } catch (_: Exception) {
            // Consulta alternativa sin filtro compuesto, filtrando en memoria por fecha de creación
            try {
                baseDatos.collection(Constantes.COLECCION_NOTIFICACIONES_IA)
                    .whereEqualTo("vendedorId", uid)
                    .get()
                    .await()
                    .documents
                    .count { documento ->
                        (documento.getLong("fechaCreacion") ?: 0L) >= inicioDelDia
                    }
            } catch (_: Exception) {
                0
            }
        }
    }

    /**
     * Valida si el usuario puede guardar un nuevo producto según su plan actual.
     *
     * Si la cantidad de productos registrados es inferior al límite del plan, la operación
     * se permite. En caso contrario, se devuelve un resultado bloqueado indicando el siguiente
     * plan disponible que permitiría continuar.
     *
     * @param estado Estado actual de la suscripción del usuario.
     * @return [ResultadoValidacion.Permitido] si hay espacio disponible, o [ResultadoValidacion.Bloqueado]
     *         con el mensaje explicativo y el plan requerido para desbloquear la acción.
     */
    fun validarGuardarProducto(estado: EstadoSuscripcion): ResultadoValidacion {
        if (estado.productosActuales < estado.plan.maxProductos) {
            return ResultadoValidacion.Permitido
        }
        val siguiente = Plan.TODOS.firstOrNull { it.maxProductos > estado.plan.maxProductos }
            ?: Plan.EMPRESARIAL
        return ResultadoValidacion.Bloqueado(
            mensaje = "Tu plan ${estado.plan.nombre} permite hasta ${estado.plan.maxProductos} productos. " +
                "Has alcanzado el límite.",
            planRequerido = siguiente,
        )
    }

    /**
     * Valida si el usuario puede emitir una nueva alerta de IA según su plan actual.
     *
     * Si el plan no impone límite de alertas o la cantidad emitida hoy es inferior al máximo
     * permitido, la operación se permite. En caso contrario, se devuelve un resultado bloqueado
     * con el siguiente plan que ofrece un límite superior.
     *
     * @param estado Estado actual de la suscripción del usuario.
     * @return [ResultadoValidacion.Permitido] si aún hay cupo de alertas, o [ResultadoValidacion.Bloqueado]
     *         con el mensaje explicativo y el plan requerido.
     */
    fun validarEmitirAlerta(estado: EstadoSuscripcion): ResultadoValidacion {
        if (estado.plan.maxAlertasPorDia == Int.MAX_VALUE) {
            return ResultadoValidacion.Permitido
        }
        if (estado.alertasHoy < estado.plan.maxAlertasPorDia) {
            return ResultadoValidacion.Permitido
        }
        val siguiente = Plan.TODOS.firstOrNull { it.maxAlertasPorDia > estado.plan.maxAlertasPorDia }
            ?: Plan.EMPRESARIAL
        return ResultadoValidacion.Bloqueado(
            mensaje = "Tu plan ${estado.plan.nombre} permite hasta ${estado.plan.maxAlertasPorDia} alertas por día. " +
                "Has alcanzado el límite.",
            planRequerido = siguiente,
        )
    }

    /**
     * Valida si una característica específica del plan está habilitada para el estado actual del usuario.
     *
     * Evalúa si el plan del usuario incluye la funcionalidad solicitada. Si no está permitida,
     * determina cuál es el plan mínimo que la incluye y genera un mensaje descriptivo para el usuario.
     *
     * @param estado Estado actual de la suscripción del usuario.
     * @param caracteristica Característica del plan que se desea validar.
     * @return [ResultadoValidacion.Permitido] si la característica está habilitada, o
     *         [ResultadoValidacion.Bloqueado] con el plan requerido y un mensaje explicativo.
     */
    fun validarCaracteristica(
        estado: EstadoSuscripcion,
        caracteristica: CaracteristicaPlan,
    ): ResultadoValidacion {
        val permitido = when (caracteristica) {
            CaracteristicaPlan.OCR -> estado.plan.permiteOcr
            CaracteristicaPlan.IMPORTAR -> estado.plan.permiteImportar
            CaracteristicaPlan.VOZ -> estado.plan.permiteVoz
            CaracteristicaPlan.ESTADISTICAS -> estado.plan.permiteEstadisticas
            CaracteristicaPlan.ALMACENES_MULTIPLES -> estado.plan.permiteAlmacenesMultiples
        }
        if (permitido) return ResultadoValidacion.Permitido

        // Se determina el plan mínimo que habilita la característica solicitada
        val siguiente = when (caracteristica) {
            CaracteristicaPlan.OCR, CaracteristicaPlan.IMPORTAR, CaracteristicaPlan.VOZ ->
                Plan.VENDEDOR
            CaracteristicaPlan.ESTADISTICAS ->
                Plan.VENDEDOR
            CaracteristicaPlan.ALMACENES_MULTIPLES ->
                Plan.EMPRESARIAL
        }
        val mensaje = when (caracteristica) {
            CaracteristicaPlan.OCR -> "El escaneo de boletas requiere el plan ${siguiente.nombre}."
            CaracteristicaPlan.IMPORTAR -> "La importación desde archivo requiere el plan ${siguiente.nombre}."
            CaracteristicaPlan.VOZ -> "El asistente por voz requiere el plan ${siguiente.nombre}."
            CaracteristicaPlan.ESTADISTICAS -> "Las estadísticas avanzadas requieren el plan ${siguiente.nombre}."
            CaracteristicaPlan.ALMACENES_MULTIPLES -> "Por ahora cada cuenta administra un solo almacén."
        }
        return ResultadoValidacion.Bloqueado(mensaje = mensaje, planRequerido = siguiente)
    }

    /**
     * Registra la activación de una compra de suscripción en el sistema.
     *
     * La activación oficial la realiza la Cloud Function `verificarCompraSuscripcion`
     * con permisos de Admin SDK, evitando que el cliente pueda escribir directamente.
     * Este método se mantiene como punto único de extensión para registrar eventos locales
     * en el futuro.
     *
     * @param codigoPlan Código del plan que fue adquirido por el usuario.
     * @param purchaseToken Token de compra proporcionado por Google Play.
     * @param fechaVencimientoMillis Fecha de vencimiento de la suscripción en milisegundos.
     */
    suspend fun aplicarCompra(
        codigoPlan: CodigoPlan,
        purchaseToken: String,
        fechaVencimientoMillis: Long,
    ) {
        // La activación oficial la realiza la Cloud Function "verificarCompraSuscripcion"
        // con permisos de Admin SDK, evitando que el cliente pueda escribir directamente.
        // Aquí dejamos un punto único por si en el futuro se quiere registrar un evento local.
    }

    /**
     * Degrada la suscripción del usuario al plan gratuito.
     *
     * Las suscripciones pagadas se cancelan únicamente desde Google Play. Cuando Google envía
     * el evento de cancelación, la Cloud Function actualiza el plan en Firestore.
     * Este método se mantiene por compatibilidad con llamadas existentes.
     *
     * @param motivo Descripción del motivo por el cual se degrada la suscripción.
     */
    suspend fun degradarAPlanGratis(motivo: String) {
        // Las suscripciones pagadas se cancelan únicamente desde Google Play.
        // Cuando Google envía el evento de cancelación, la Cloud Function actualiza el plan.
        // Este método se mantiene por compatibilidad con llamadas existentes.
    }
}

/**
 * Enumera las características premium disponibles que pueden estar restringidas según el plan de suscripción.
 *
 * Cada valor representa una funcionalidad cuya disponibilidad se valida contra el plan
 * activo del usuario antes de permitir su uso.
 */
enum class CaracteristicaPlan {
    /** Escaneo de boletas mediante reconocimiento óptico de caracteres. */
    OCR,
    /** Importación de inventario desde archivos externos (Excel, CSV, etc.). */
    IMPORTAR,
    /** Asistente de entrada de datos por comando de voz. */
    VOZ,
    /** Acceso a estadísticas avanzadas de ventas e inventario. */
    ESTADISTICAS,
    /** Gestión y consulta de múltiples almacenes desde una sola cuenta. */
    ALMACENES_MULTIPLES,
}
