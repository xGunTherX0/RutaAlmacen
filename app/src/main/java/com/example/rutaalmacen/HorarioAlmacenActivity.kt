package com.example.rutaalmacen

import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

class HorarioAlmacenActivity : AppCompatActivity() {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val coleccionInventarioPublico = "InventarioPublico"

    private lateinit var textoHorario: android.widget.TextView
    private lateinit var botonMananaInicio: MaterialButton
    private lateinit var botonMananaFin: MaterialButton
    private lateinit var botonTardeInicio: MaterialButton
    private lateinit var botonTardeFin: MaterialButton
    private var horarioMananaInicio = HORARIO_MANANA_INICIO_TEXTO
    private var horarioMananaFin = HORARIO_MANANA_FIN_TEXTO
    private var horarioTardeInicio = HORARIO_TARDE_INICIO_TEXTO
    private var horarioTardeFin = HORARIO_TARDE_FIN_TEXTO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_horario_almacen)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_horario_almacen)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                barrasDelSistema.top,
                barrasDelSistema.right,
                barrasDelSistema.bottom,
            )
            insets
        }

        textoHorario = findViewById(R.id.texto_horario_almacen)
        botonMananaInicio = findViewById(R.id.boton_horario_manana_inicio)
        botonMananaFin = findViewById(R.id.boton_horario_manana_fin)
        botonTardeInicio = findViewById(R.id.boton_horario_tarde_inicio)
        botonTardeFin = findViewById(R.id.boton_horario_tarde_fin)
        val botonGuardarHorario = findViewById<MaterialButton>(R.id.boton_guardar_horario)

        botonMananaInicio.setOnClickListener {
            mostrarSelectorHora(horarioMananaInicio) { hora ->
                horarioMananaInicio = hora
                botonMananaInicio.text = hora
                actualizarResumenHorario()
            }
        }
        botonMananaFin.setOnClickListener {
            mostrarSelectorHora(horarioMananaFin) { hora ->
                horarioMananaFin = hora
                botonMananaFin.text = hora
                actualizarResumenHorario()
            }
        }
        botonTardeInicio.setOnClickListener {
            mostrarSelectorHora(horarioTardeInicio) { hora ->
                horarioTardeInicio = hora
                botonTardeInicio.text = hora
                actualizarResumenHorario()
            }
        }
        botonTardeFin.setOnClickListener {
            mostrarSelectorHora(horarioTardeFin) { hora ->
                horarioTardeFin = hora
                botonTardeFin.text = hora
                actualizarResumenHorario()
            }
        }

        botonGuardarHorario.setOnClickListener { guardarHorario() }

        actualizarResumenHorario()
        lifecycleScope.launch { cargarHorario() }
    }

    private fun guardarHorario() {
        val minutosMananaInicio = convertirHoraAMinutos(horarioMananaInicio)
        val minutosMananaFin = convertirHoraAMinutos(horarioMananaFin)
        val minutosTardeInicio = convertirHoraAMinutos(horarioTardeInicio)
        val minutosTardeFin = convertirHoraAMinutos(horarioTardeFin)

        if (minutosMananaInicio == null || minutosMananaFin == null ||
            minutosTardeInicio == null || minutosTardeFin == null
        ) {
            mostrarMensaje("Selecciona horarios válidos")
            return
        }
        if (minutosMananaInicio >= minutosMananaFin) {
            mostrarMensaje("El turno mañana debe terminar después de iniciar")
            return
        }
        if (minutosTardeInicio >= minutosTardeFin) {
            mostrarMensaje("El turno tarde debe terminar después de iniciar")
            return
        }
        if (minutosMananaFin > minutosTardeInicio) {
            mostrarMensaje("El turno tarde debe iniciar después del turno mañana")
            return
        }

        val usuario = autenticacion.currentUser
        if (usuario == null) {
            mostrarMensaje("No hay un usuario activo")
            return
        }

        lifecycleScope.launch {
            try {
                val datos = mapOf(
                    "horarioAtencion" to construirHorarioAtencion(),
                    "horarioMananaInicio" to horarioMananaInicio,
                    "horarioMananaFin" to horarioMananaFin,
                    "horarioTardeInicio" to horarioTardeInicio,
                    "horarioTardeFin" to horarioTardeFin,
                )
                baseDatos.collection("Usuarios")
                    .document(usuario.uid)
                    .set(datos, SetOptions.merge())
                    .await()

                try {
                    actualizarInventarioPublico(usuario.uid, datos)
                } catch (excepcion: Exception) {
                    mostrarMensaje("Horario guardado, pero no se pudo actualizar la lista pública")
                }

                mostrarMensaje("Horario guardado")
                actualizarResumenHorario()
            } catch (excepcion: Exception) {
                mostrarMensaje("No se pudo guardar el horario")
            }
        }
    }

    private suspend fun cargarHorario() {
        val usuario = autenticacion.currentUser ?: return
        try {
            val documento = baseDatos.collection("Usuarios")
                .document(usuario.uid)
                .get()
                .await()

            horarioMananaInicio = documento.getString("horarioMananaInicio") ?: HORARIO_MANANA_INICIO_TEXTO
            horarioMananaFin = documento.getString("horarioMananaFin") ?: HORARIO_MANANA_FIN_TEXTO
            horarioTardeInicio = documento.getString("horarioTardeInicio") ?: HORARIO_TARDE_INICIO_TEXTO
            horarioTardeFin = documento.getString("horarioTardeFin") ?: HORARIO_TARDE_FIN_TEXTO
            botonMananaInicio.text = horarioMananaInicio
            botonMananaFin.text = horarioMananaFin
            botonTardeInicio.text = horarioTardeInicio
            botonTardeFin.text = horarioTardeFin
            actualizarResumenHorario()
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo cargar el horario")
        }
    }

    private fun actualizarResumenHorario() {
        textoHorario.text = "Horario de atención: ${construirHorarioAtencion()}"
    }

    private fun construirHorarioAtencion(): String {
        return "$horarioMananaInicio - $horarioMananaFin / $horarioTardeInicio - $horarioTardeFin"
    }

    private fun mostrarSelectorHora(
        horaActual: String,
        onHoraSeleccionada: (String) -> Unit,
    ) {
        val minutos = convertirHoraAMinutos(horaActual) ?: 0
        val hora = minutos / 60
        val minuto = minutos % 60
        TimePickerDialog(
            this,
            { _, horaSeleccionada, minutoSeleccionado ->
                val texto = String.format(
                    Locale.forLanguageTag("es-CL"),
                    "%02d:%02d",
                    horaSeleccionada,
                    minutoSeleccionado,
                )
                onHoraSeleccionada(texto)
            },
            hora,
            minuto,
            true,
        ).show()
    }

    private fun convertirHoraAMinutos(hora: String): Int? {
        val partes = hora.split(":")
        if (partes.size != 2) {
            return null
        }
        val horas = partes[0].toIntOrNull() ?: return null
        val minutos = partes[1].toIntOrNull() ?: return null
        if (horas !in 0..23 || minutos !in 0..59) {
            return null
        }
        return horas * 60 + minutos
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

    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val HORARIO_MANANA_INICIO_TEXTO = "09:00"
        private const val HORARIO_MANANA_FIN_TEXTO = "13:00"
        private const val HORARIO_TARDE_INICIO_TEXTO = "16:00"
        private const val HORARIO_TARDE_FIN_TEXTO = "22:00"
    }
}
