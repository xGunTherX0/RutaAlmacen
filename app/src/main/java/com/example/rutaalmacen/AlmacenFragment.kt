package com.example.rutaalmacen

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment

class AlmacenFragment : Fragment(R.layout.fragment_almacen) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.opcion_nombre_almacen).setOnClickListener {
            startActivity(Intent(requireContext(), NombreAlmacenActivity::class.java))
        }
        view.findViewById<View>(R.id.opcion_horario_almacen).setOnClickListener {
            startActivity(Intent(requireContext(), HorarioAlmacenActivity::class.java))
        }
        view.findViewById<View>(R.id.opcion_categoria_almacen).setOnClickListener {
            startActivity(Intent(requireContext(), CategoriaAlmacenActivity::class.java))
        }
    }
}
