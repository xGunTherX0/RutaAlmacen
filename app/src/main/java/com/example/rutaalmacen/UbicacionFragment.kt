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

class UbicacionFragment : Fragment(R.layout.fragment_ubicacion) {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val proveedorUbicacion: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(requireContext())
    }
    private val coleccionInventarioPublico = "InventarioPublico"

    private val solicitudPermisoUbicacion = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido ->
        if (concedido) {
            actualizarUbicacionDelAlmacen()
        } else {
            mostrarMensaje("Permiso de ubicación denegado")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val botonActualizarUbicacion = view.findViewById<MaterialButton>(R.id.boton_actualizar_ubicacion)
        botonActualizarUbicacion.setOnClickListener { iniciarActualizacionUbicacion() }
    }

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

                baseDatos.collection("Usuarios")
                    .document(usuario.uid)
                    .set(datos, SetOptions.merge())
                    .await()

                mostrarMensaje("Ubicación del almacén actualizada")

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

    private fun gpsActivo(): Boolean {
        val manejador = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return manejador.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manejador.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(requireContext(), mensaje, Toast.LENGTH_SHORT).show()
    }

    private suspend fun actualizarInventarioPublico(uid: String, datos: Map<String, Any>) {
        val resultado = baseDatos.collection(coleccionInventarioPublico)
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
