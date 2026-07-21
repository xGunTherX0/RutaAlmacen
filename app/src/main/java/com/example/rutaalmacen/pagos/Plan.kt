package com.example.rutaalmacen.pagos

/**
 * Representa los identificadores únicos de cada plan de suscripción disponibles en la aplicación.
 *
 * Cada constante se asocia con un identificador de producto de Google Play, un precio formateado
 * para presentación en interfaz y el precio en centavos para procesamiento interno.
 *
 * @property id Identificador del producto en Google Play Console.
 * @property precioFormateado Texto legible del precio para mostrar en la interfaz de usuario.
 * @property precioCentavos Valor del plan expresado en centavos de moneda local.
 */
enum class CodigoPlan(val id: String, val precioFormateado: String, val precioCentavos: Long) {
    GRATIS("gratis", "Gratis", 0L),
    VENDEDOR("vendedor_mensual", "$3.990", 399000L),
    COMERCIO("comercio_mensual", "$7.990", 799000L),
    EMPRESARIAL("empresarial_mensual", "$14.990", 1499000L),
    ;

    companion object {
        /**
         * Convierte un identificador de producto en su [CodigoPlan] correspondiente.
         *
         * Si el identificador no coincide con ningún plan conocido, se devuelve [GRATIS]
         * como valor predeterminado para garantizar un comportamiento seguro.
         *
         * @param id Identificador de producto proveniente de Firestore o Google Play.
         * @return El [CodigoPlan] asociado al identificador, o [GRATIS] si no se reconoce.
         */
        fun desdeId(id: String?): CodigoPlan =
            values().firstOrNull { it.id == id } ?: GRATIS
    }
}

/**
 * Modelo de datos que describe las capacidades y límites de un plan de suscripción.
 *
 * Contiene toda la información necesaria para validar el acceso del usuario a las
 * distintas funcionalidades de la aplicación, incluyendo límites de productos, alertas
 * y características premium disponibles.
 *
 * @property codigo Código de plan asociado a este conjunto de capacidades.
 * @property nombre Nombre legible del plan para presentación en interfaz.
 * @property descripcionCorta Descripción breve del público objetivo del plan.
 * @property maxProductos Cantidad máxima de productos que el usuario puede registrar.
 * @property maxAlertasPorDia Cantidad máxima de alertas de IA que se pueden emitir por día.
 * @property permiteOcr Indica si el plan habilita el escaneo de boletas mediante OCR.
 * @property permiteImportar Indica si el plan habilita la importación de inventario desde archivos.
 * @property permiteVoz Indica si el plan habilita el asistente por voz.
 * @property permiteEstadisticas Indica si el plan habilita las estadísticas avanzadas.
 * @property permiteAlmacenesMultiples Indica si el plan habilita la gestión de múltiples almacenes.
 */
data class Plan(
    val codigo: CodigoPlan,
    val nombre: String,
    val descripcionCorta: String,
    val maxProductos: Int,
    val maxAlertasPorDia: Int,
    val permiteOcr: Boolean,
    val permiteImportar: Boolean,
    val permiteVoz: Boolean,
    val permiteEstadisticas: Boolean,
    val permiteAlmacenesMultiples: Boolean,
) {
    /** Indica si este plan corresponde al nivel gratuito. */
    val esGratis: Boolean get() = codigo == CodigoPlan.GRATIS

    companion object {
        /** Plan gratuito con capacidades básicas para probar la aplicación. */
        val GRATIS = Plan(
            codigo = CodigoPlan.GRATIS,
            nombre = "Gratis",
            descripcionCorta = "Para probar la app",
            maxProductos = 20,
            maxAlertasPorDia = 15,
            permiteOcr = false,
            permiteImportar = false,
            permiteVoz = false,
            permiteEstadisticas = false,
            permiteAlmacenesMultiples = false,
        )

        /** Plan intermedio orientado a almacenes de barrio. */
        val VENDEDOR = Plan(
            codigo = CodigoPlan.VENDEDOR,
            nombre = "Vendedor",
            descripcionCorta = "Para almacenes de barrio",
            maxProductos = 80,
            maxAlertasPorDia = 30,
            permiteOcr = true,
            permiteImportar = true,
            permiteVoz = true,
            permiteEstadisticas = true,
            permiteAlmacenesMultiples = false,
        )

        /** Plan avanzado para tiendas con catálogo amplio. */
        val COMERCIO = Plan(
            codigo = CodigoPlan.COMERCIO,
            nombre = "Comercio",
            descripcionCorta = "Para tiendas con catálogo amplio",
            maxProductos = 250,
            maxAlertasPorDia = 100,
            permiteOcr = true,
            permiteImportar = true,
            permiteVoz = true,
            permiteEstadisticas = true,
            permiteAlmacenesMultiples = false,
        )

        /** Plan de máximo nivel para almacenes grandes y mayoristas. */
        val EMPRESARIAL = Plan(
            codigo = CodigoPlan.EMPRESARIAL,
            nombre = "Empresarial",
            descripcionCorta = "Para almacenes grandes y mayoristas",
            maxProductos = 1000,
            maxAlertasPorDia = Int.MAX_VALUE,
            permiteOcr = true,
            permiteImportar = true,
            permiteVoz = true,
            permiteEstadisticas = true,
            permiteAlmacenesMultiples = false,
        )

        /**
         * Obtiene la instancia de [Plan] asociada a un código de plan.
         *
         * @param codigo Código de plan cuya instancia se desea obtener.
         * @return Instancia de [Plan] correspondiente al código indicado.
         */
        fun desdeCodigo(codigo: CodigoPlan): Plan = when (codigo) {
            CodigoPlan.GRATIS -> GRATIS
            CodigoPlan.VENDEDOR -> VENDEDOR
            CodigoPlan.COMERCIO -> COMERCIO
            CodigoPlan.EMPRESARIAL -> EMPRESARIAL
        }

        /**
         * Obtiene la instancia de [Plan] a partir de un identificador de producto.
         *
         * @param id Identificador de producto que puede provenir de Firestore o Google Play.
         * @return Instancia de [Plan] correspondiente, o el plan gratuito si no se reconoce.
         */
        fun desdeId(id: String?): Plan = desdeCodigo(CodigoPlan.desdeId(id))

        /** Lista ordenada con todos los planes disponibles, desde el gratuito hasta el empresarial. */
        val TODOS: List<Plan> = listOf(GRATIS, VENDEDOR, COMERCIO, EMPRESARIAL)
    }
}

/**
 * Resultado de la validación de una acción del usuario contra las restricciones de su plan actual.
 *
 * Permite determinar si la operación puede ejecutarse o si debe bloquearse, en cuyo caso
 * se proporciona un mensaje explicativo y el plan mínimo requerido para habilitarla.
 */
sealed class ResultadoValidacion {
    /** La operación está permitida bajo el plan actual del usuario. */
    data object Permitido : ResultadoValidacion()

    /**
     * La operación está bloqueada porque el plan actual no la incluye.
     *
     * @property mensaje Texto descriptivo que explica al usuario por qué se bloqueó la acción.
     * @property planRequerido Plan mínimo al que el usuario debe suscribirse para desbloquear la funcionalidad.
     */
    data class Bloqueado(val mensaje: String, val planRequerido: Plan) : ResultadoValidacion()
}
