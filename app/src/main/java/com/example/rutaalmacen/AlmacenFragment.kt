package com.example.rutaalmacen

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Fragmento de configuración del almacén.
 *
 * Muestra un panel con las opciones de personalización del almacén: nombre, horario,
 * ubicación, categoría, estado de apertura, métodos de pago, caja vecina y bloc de notas.
 * Cada opción abre su actividad correspondiente. Además, carga de forma asíncrona los
 * estados actuales (cerrado manual, métodos de pago, caja vecina) desde Firestore para
 * reflejarlos en la interfaz.
 */
class AlmacenFragment : Fragment(R.layout.fragment_almacen) {

    /** Instancia de Firebase Authentication obtenida de forma perezosa. */
    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    /** Instancia de Firestore obtenida de forma perezosa para lecturas remotas. */
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /**
     * Configura los listeners de navegación para cada opción del almacén y carga
     * los estados actuales desde Firestore.
     *
     * @param view Vista raíz inflada del fragmento.
     * @param savedInstanceState Estado guardado previamente, o `null` si es un inicio nuevo.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.opcion_nombre_almacen).setOnClickListener {
            startActivity(Intent(requireContext(), NombreAlmacenActivity::class.java))
        }
        view.findViewById<View>(R.id.opcion_horario_almacen).setOnClickListener {
            startActivity(Intent(requireContext(), HorarioAlmacenActivity::class.java))
        }
        view.findViewById<View>(R.id.opcion_ubicacion_almacen).setOnClickListener {
            startActivity(Intent(requireContext(), UbicacionActivity::class.java))
        }
        view.findViewById<View>(R.id.opcion_categoria_almacen).setOnClickListener {
            startActivity(Intent(requireContext(), CategoriaAlmacenActivity::class.java))
        }
        view.findViewById<View>(R.id.opcion_estado_almacen).setOnClickListener {
            startActivity(Intent(requireContext(), EstadoAlmacenActivity::class.java))
        }
        view.findViewById<View>(R.id.opcion_metodos_pago).setOnClickListener {
            startActivity(Intent(requireContext(), MetodosPagoActivity::class.java))
        }
        view.findViewById<View>(R.id.opcion_caja_vecina).setOnClickListener {
            startActivity(Intent(requireContext(), CajaVecinaActivity::class.java))
        }
        view.findViewById<View>(R.id.opcion_block_notas).setOnClickListener {
            startActivity(Intent(requireContext(), com.example.rutaalmacen.notas.NotasActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch { cargarEstadosEnFila(view) }
    }

    /**
     * Recarga los estados del almacén desde Firestore cada vez que el fragmento
     * vuelve a primer plano.
     */
    override fun onResume() {
        super.onResume()
        view?.let { cargarEstadosEnFila(it) }
    }

    /**
     * Lee de forma asíncrona los estados actuales del almacén desde Firestore
     * y los refleja en la interfaz.
     *
     * Consulta los campos `cerradoManual`, `metodosPago` y `tieneCajaVecina` del documento
     * del usuario autenticado, y actualiza los interruptores y textos correspondientes.
     *
     * @param view Vista raíz del fragmento donde se encuentran los componentes a actualizar.
     * @throws Exception Si la consulta a Firestore falla; el error se silencia intencionalmente.
     */
    private fun cargarEstadosEnFila(view: View) {
        val usuario = autenticacion.currentUser ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val documento = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                    .document(usuario.uid)
                    .get()
                    .await()
                val cerradoManual = documento.getBoolean("cerradoManual") ?: false
                val metodosPago = (documento.get("metodosPago") as? List<String>).orEmpty()
                val tieneCaja = documento.getBoolean("tieneCajaVecina") ?: false

                view.findViewById<MaterialSwitch>(R.id.switch_estado_almacen).isChecked = cerradoManual
                view.findViewById<MaterialSwitch>(R.id.switch_caja_vecina).isChecked = tieneCaja
                view.findViewById<android.widget.TextView>(R.id.texto_metodos_pago).text =
                    if (metodosPago.isEmpty()) {
                        "Métodos de pago"
                    } else {
                        "Pagos: ${metodosPago.joinToString(", ")}"
                    }
            } catch (_: Exception) {
                // Silenciar
            }
        }
    }
}
