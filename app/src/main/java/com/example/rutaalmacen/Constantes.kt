package com.example.rutaalmacen

/**
 * Repositorio centralizado de constantes de la aplicación RutaAlmacen.
 *
 * Define los identificadores de colecciones de Firebase Firestore utilizadas
 * como repositorio de datos en la nube, así como los roles de usuario
 * disponibles en el sistema de autenticación.
 *
 * Al centralizar estos valores se evita la dispersión de cadenas literales
 * en el código fuente y se facilita el mantenimiento evolutivo.
 */
object Constantes {

    /** Colección principal de perfiles de usuario en Firestore. */
    const val COLECCION_USUARIOS = "Usuarios"

    /** Colección pública de inventario accesible para búsquedas de compradores. */
    const val COLECCION_INVENTARIO_PUBLICO = "InventarioPublico"

    /** Colección de notificaciones generadas por el módulo de inteligencia artificial. */
    const val COLECCION_NOTIFICACIONES_IA = "Notificaciones_IA"

    /** Colección que almacena el historial de búsquedas realizadas por los compradores. */
    const val COLECCION_BUSQUEDAS_HISTORICAS = "Busquedas_Historicas"

    /** Colección de palabras y frases bloqueadas por el administrador del sistema. */
    const val COLECCION_PALABRAS_BLOQUEADAS = "Palabras_Bloqueadas"

    /** Colección de alertas reportadas por los usuarios para moderación. */
    const val COLECCION_ALERTAS_REPORTADAS = "Alertas_Reportadas"

    /** Subcolección de notas privadas asociada al perfil de cada vendedor. */
    const val SUBCOLECCION_NOTAS = "Notas"

    /** Identificador de rol para usuarios con permisos administrativos. */
    const val ROL_ADMINISTRADOR = "administrador"

    /** Identificador de rol para usuarios que gestionan un almacén. */
    const val ROL_VENDEDOR = "vendedor"

    /** Identificador de rol para usuarios que buscan productos en almacenes cercanos. */
    const val ROL_COMPRADOR = "comprador"
}
