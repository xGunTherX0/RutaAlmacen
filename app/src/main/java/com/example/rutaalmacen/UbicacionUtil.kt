package com.example.rutaalmacen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume

/**
 * Utilidad centralizada para la obtención y cálculo de ubicaciones geográficas.
 *
 * Abstrae la complejidad de trabajar con múltiples proveedores de ubicación
 * (Fused Location Provider de Google Play Services y LocationManager del sistema)
 * ofreciendo una API suspendible basada en corrutinas. Implementa estrategias
 * de fallback en cascada para maximizar la probabilidad de obtener una
 * coordenada válida incluso en condiciones de conectividad degradada.
 *
 * Todas las operaciones de localización verifican previamente los permisos
 * de acceso a la ubicación del contexto de la aplicación.
 */
object UbicacionUtil {

    /**
     * Verifica si la aplicación posee permisos de ubicación concedidos.
     *
     * @param context Contexto de la aplicación o actividad.
     * @return `true` si al menos uno de los permisos de ubicación
     *         (fino o aproximado) está concedido.
     */
    fun tienePermisoUbicacion(context: Context): Boolean {
        val permisoFino = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val permisoAproximado = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return permisoFino || permisoAproximado
    }

    /**
     * Calcula la distancia en metros entre dos coordenadas geográficas.
     *
     * Utiliza el algoritmo de distancia geodésica provisto por la API
     * de Android para obtener resultados precisos sobre la superficie
     * elipsoidal de la Tierra.
     *
     * @param latitudOrigen Latitud del punto de origen en grados decimales.
     * @param longitudOrigen Longitud del punto de origen en grados decimales.
     * @param latitudDestino Latitud del punto de destino en grados decimales.
     * @param longitudDestino Longitud del punto de destino en grados decimales.
     * @return Distancia entre ambos puntos expresada en metros.
     */
    fun calcularDistancia(
        latitudOrigen: Double,
        longitudOrigen: Double,
        latitudDestino: Double,
        longitudDestino: Double,
    ): Double {
        val resultados = FloatArray(1)
        Location.distanceBetween(
            latitudOrigen,
            longitudOrigen,
            latitudDestino,
            longitudDestino,
            resultados,
        )
        return resultados.first().toDouble()
    }

    /**
     * Obtiene la última ubicación conocida del Fused Location Provider.
     *
     * Es la estrategia más rápida y de menor consumo energético, pero
     * puede retornar `null` si no hay lecturas previas disponibles.
     *
     * @param proveedor Cliente de Fused Location Provider ya inicializado.
     * @return Última ubicación conocida, o `null` si no está disponible
     *         o se produce un error de permisos.
     */
    suspend fun obtenerUbicacionRapida(proveedor: FusedLocationProviderClient): Location? {
        return try {
            proveedor.lastLocation.await()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Obtiene una ubicación utilizando el Fused Location Provider con
     * estrategia de fallback en cascada.
     *
     * Intenta primero obtener una ubicación de alta precisión en tiempo
     * real. Si no está disponible, recurre a la última ubicación conocida.
     * Como último recurso, solicita una actualización única al proveedor.
     *
     * @param proveedor Cliente de Fused Location Provider ya inicializado.
     * @return Ubicación obtenida por cualquiera de las estrategias,
     *         o `null` si todas fallan.
     */
    suspend fun obtenerUbicacionFused(proveedor: FusedLocationProviderClient): Location? {
        return try {
            val ubicacionActual = proveedor
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .await()

            if (ubicacionActual != null) {
                return ubicacionActual
            }

            val ultimaUbicacion = proveedor.lastLocation.await()
            if (ultimaUbicacion != null) {
                return ultimaUbicacion
            }

            solicitarUbicacionUnica(proveedor)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Obtiene una ubicación utilizando el LocationManager nativo del sistema.
     *
     * Consulta los proveedores GPS, red y pasivo, seleccionando la lectura
     * más reciente entre los proveedores habilitados. Si no hay lecturas
     * cached, solicita una actualización única al primer proveedor activo.
     *
     * @param context Contexto para acceder al servicio de localización del sistema.
     * @return Ubicación obtenida del proveedor del sistema, o `null` si
     *         ningún proveedor está disponible o se produce un error.
     */
    suspend fun obtenerUbicacionSistema(context: Context): Location? {
        val manejador = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val proveedores = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        val ultimaUbicacion = proveedores
            .filter { proveedor -> manejador.isProviderEnabled(proveedor) }
            .mapNotNull { proveedor -> manejador.getLastKnownLocation(proveedor) }
            .maxByOrNull { ubicacion -> ubicacion.time }

        if (ultimaUbicacion != null) {
            return ultimaUbicacion
        }

        val proveedorActivo = proveedores.firstOrNull { proveedor -> manejador.isProviderEnabled(proveedor) }
            ?: return null

        return solicitarUbicacionUnicaSistema(context, manejador, proveedorActivo)
    }

    /**
     * Solicita una única actualización de ubicación al Fused Location Provider.
     *
     * Utiliza [suspendCancellableCoroutine] para envolver el callback asíncrono
     * en una corrutina suspendible. La solicitud se configura con alta precisión
     * y un máximo de una actualización para minimizar el consumo de batería.
     *
     * @param proveedor Cliente de Fused Location Provider ya inicializado.
     * @return Ubicación obtenida en la primera actualización, o `null` si
     *         la solicitud falla o la corrutina es cancelada.
     */
    suspend fun solicitarUbicacionUnica(proveedor: FusedLocationProviderClient): Location? {
        return suspendCancellableCoroutine { continuacion ->
            val solicitud = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .setMaxUpdates(1)
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(resultado: LocationResult) {
                    proveedor.removeLocationUpdates(this)
                    if (!continuacion.isCompleted) {
                        continuacion.resume(resultado.lastLocation)
                    }
                }
            }

            proveedor.requestLocationUpdates(
                solicitud,
                callback,
                Looper.getMainLooper(),
            ).addOnFailureListener {
                proveedor.removeLocationUpdates(callback)
                if (!continuacion.isCompleted) {
                    continuacion.resume(null)
                }
            }

            continuacion.invokeOnCancellation {
                proveedor.removeLocationUpdates(callback)
            }
        }
    }

    /**
     * Solicita una única actualización de ubicación al LocationManager del sistema.
     *
     * Envuelve el [LocationListener] en una corrutina suspendible mediante
     * [suspendCancellableCoroutine]. Maneja las excepciones de seguridad
     * y de argumento ilegal que pueden ocurrir si el proveedor se deshabilita
     * durante la solicitud.
     *
     * @param context Contexto de la aplicación.
     * @param manejador Instancia de [LocationManager] del sistema.
     * @param proveedor Nombre del proveedor de ubicación a utilizar.
     * @return Ubicación obtenida en la primera actualización, o `null` si
     *         la solicitud falla o la corrutina es cancelada.
     */
    suspend fun solicitarUbicacionUnicaSistema(
        context: Context,
        manejador: LocationManager,
        proveedor: String,
    ): Location? {
        return suspendCancellableCoroutine { continuacion ->
            val listener = object : LocationListener {
                override fun onLocationChanged(ubicacion: Location) {
                    manejador.removeUpdates(this)
                    if (!continuacion.isCompleted) {
                        continuacion.resume(ubicacion)
                    }
                }

                override fun onProviderDisabled(provider: String) {
                    manejador.removeUpdates(this)
                    if (!continuacion.isCompleted) {
                        continuacion.resume(null)
                    }
                }

                override fun onProviderEnabled(provider: String) = Unit

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }

            try {
                manejador.requestLocationUpdates(
                    proveedor,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
            } catch (_: SecurityException) {
                manejador.removeUpdates(listener)
                if (!continuacion.isCompleted) {
                    continuacion.resume(null)
                }
            } catch (_: IllegalArgumentException) {
                manejador.removeUpdates(listener)
                if (!continuacion.isCompleted) {
                    continuacion.resume(null)
                }
            }

            continuacion.invokeOnCancellation {
                manejador.removeUpdates(listener)
            }
        }
    }

    /**
     * Obtiene una ubicación desde caché si aún es válida temporalmente.
     *
     * @param cache Ubicación almacenada en caché previamente.
     * @param cacheTiempo Timestamp en milisegundos de cuando se almacenó la caché.
     * @param duracionCacheMs Duración máxima de validez de la caché en milisegundos.
     * @return La ubicación en caché si no ha expirado, o `null` si es necesario
     *         obtener una nueva lectura.
     */
    fun obtenerUbicacionConCache(
        cache: Location?,
        cacheTiempo: Long,
        duracionCacheMs: Long,
    ): Location? {
        val ahora = System.currentTimeMillis()
        return if (cache != null && ahora - cacheTiempo < duracionCacheMs) {
            cache
        } else {
            null
        }
    }
}
