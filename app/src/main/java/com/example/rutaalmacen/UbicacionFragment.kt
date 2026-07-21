package com.example.rutaalmacen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Fragmento que gestiona la actualización de la ubicación geográfica del almacén.
 *
 * Permite al vendedor registrar su posición actual mediante el proveedor de ubicación
 * fusionado de Google Play Services y almacenarla en Firestore. Además, sincroniza
 * la ubicación con los documentos del inventario público para que los compradores
 * puedan estimar la distancia al almacén.
 *
 * Requiere el permiso de ubicación precisa ([Manifest.permission.ACCESS_FINE_LOCATION])
 * y que el GPS o el proveedor de red estén activos.
 */
class UbicacionFragment : Fragment(R.layout.fragment_ubicacion) {

    /** Instancia de Firebase Authentication obtenida de forma perezosa. */
    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    /** Instancia de Firestore obtenida de forma perezosa para lecturas y escrituras remotas. */
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    /** Cliente de ubicación fusionada de Google Play Services para obtener la posición actual. */
    private val proveedorUbicacion: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(requireContext())
    }

    /**
     * Lanzador de solicitud de permiso de ubicación precisa.
     *
     * Si el permiso es concedido, inicia la actualización de ubicación del almacén.
     * En caso contrario, muestra un mensaje informativo al usuario.
     */
    private val solicitudPermisoUbicacion = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido ->
        if (concedido) {
            actualizarUbicacionDelAlmacen()
        } else {
            mostrarMensaje("Permiso de ubicación denegado")
        }
    }

    /**
     * Inicializa las vistas del fragmento y configura el botón de actualización de ubicación.
     *
     * @param view Vista raíz inflada del fragmento.
     * @param savedInstanceState Estado guardado previamente, o `null` si es un inicio nuevo.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val botonActualizarUbicacion = view.findViewById<MaterialButton>(R.id.boton_actualizar_ubicacion)
        botonActualizarUbicacion.setOnClickListener { iniciarActualizacionUbicacion() }
    }

    /**
     * Inicia el proceso de actualización de ubicación verificando el permiso correspondiente.
     *
     * Si el permiso de ubicación precisa ya fue concedido, obtiene la posición directamente.
     * De lo contrario, lanza la solicitud de permiso al usuario.
     */
    private fun iniciarActualizacionUbicacion() {
        val permisoConcedido = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (permisoConcedido) {
            actualizarUbicacionDelAlmacen()
        } else {
            solicitudPermisoUbicacion.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /**
     * Obtiene la ubicación actual del dispositivo con alta precisión y la almacena en Firestore.
     *
     * Verifica que el permiso haya sido concedido y que el GPS esté activo antes de solicitar
     * la ubicación. Tras obtener las coordenadas, actualiza los campos `latitud` y `longitud`
     * del documento del usuario en Firestore. Posteriormente, intenta sincronizar la ubicación
     * con los documentos del inventario público.
     *
     * @throws Exception Si la obtención de ubicación o la escritura en Firestore falla;
     *   se muestra un mensaje de error al usuario.
     */
    private fun actualizarUbicacionDelAlmacen() {
        val permisoConcedido = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (!permisoConcedido) {
            mostrarMensaje("Permiso de ubicación no concedido")
            return
        }

        if (!gpsActivo()) {
            mostrarMensaje("Activa el GPS para continuar")
            return
        }

        val usuario = autenticacion.currentUser
        if (usuario == null) {
            mostrarMensaje("No hay un usuario activo")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ubicacion = proveedorUbicacion
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .await()

                if (ubicacion == null) {
                    mostrarMensaje("No se pudo obtener la ubicación")
                    return@launch
                }

                val datos = mapOf(
                    "latitud" to ubicacion.latitude,
                    "longitud" to ubicacion.longitude,
                )

                baseDatos.collection(Constantes.COLECCION_USUARIOS)
                    .document(usuario.uid)
                    .set(datos, SetOptions.merge())
                    .await()

                mostrarMensaje("Ubicación del almacén actualizada")

                // Sincroniza la ubicación con el inventario público; si falla, no bloquea el flujo principal
                try {
                    actualizarInventarioPublico(usuario.uid, datos)
                } catch (excepcion: Exception) {
                    mostrarMensaje("Ubicación guardada, pero no se pudo actualizar la lista pública")
                }
            } catch (excepcion: Exception) {
                mostrarMensaje("No se pudo guardar la ubicación")
            }
        }
    }

    /**
     * Verifica si al menos un proveedor de ubicación (GPS o red) está habilitado en el dispositivo.
     *
     * @return `true` si el GPS o el proveedor de red están activos; `false` en caso contrario.
     */
    private fun gpsActivo(): Boolean {
        val manejador = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return manejador.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manejador.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
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
     * Sincroniza la ubicación del almacén con todos los documentos del inventario público del vendedor.
     *
     * Busca los documentos asociados al vendedor en la colección de inventario público y actualiza
     * los campos de ubicación mediante una operación de combinación ([SetOptions.merge]).
     *
     * @param uid Identificador único del usuario autenticado.
     * @param datos Mapa con las claves `latitud` y `longitud` a actualizar.
     * @throws Exception Si la consulta o la escritura por lotes en Firestore falla.
     */
    private suspend fun actualizarInventarioPublico(uid: String, datos: Map<String, Any>) {
        val resultado = baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
            .whereEqualTo("vendedorId", uid)
            .get()
            .await()
        if (resultado.isEmpty) {
            return
        }
        val lote = baseDatos.batch()
        resultado.documents.forEach { documento ->
            lote.set(documento.reference, datos, SetOptions.merge())
        }
        lote.commit().await()
    }
}
