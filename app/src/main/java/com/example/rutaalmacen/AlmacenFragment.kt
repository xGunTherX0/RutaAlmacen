package com.example.rutaalmacen

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment

/**
 * Fragmento de configuración del almacén.
 *
 * Muestra una vista resumen con un botón para abrir el panel lateral
 * de configuración (drawer). El drawer contiene todas las opciones de
 * personalización del almacén: nombre, horario, ubicación, categoría,
 * estado de apertura, métodos de pago, caja vecina, bloc de notas y
 * vista como comprador.
 */
class AlmacenFragment : Fragment(R.layout.fragment_almacen) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.boton_abrir_configuracion).setOnClickListener {
            (requireActivity() as? VendedorActivity)?.abrirDrawerAlmacen()
        }
    }
}
