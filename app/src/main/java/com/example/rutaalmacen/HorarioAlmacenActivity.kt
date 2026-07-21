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

/**
 * Actividad que permite al vendedor configurar el horario de atención de su almacén,
 * dividiéndolo en turno mañana y turno tarde. Valida que los horarios sean coherentes
 * y sincroniza los cambios con Firestore y el inventario público.
 */
class HorarioAlmacenActivity : AppCompatActivity() {

    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private lateinit var textoHorario: android.widget.TextView
    private lateinit var botonMananaInicio: MaterialButton
    private lateinit var botonMananaFin: MaterialButton
    private lateinit var botonTardeInicio: MaterialButton
    private lateinit var botonTardeFin: MaterialButton
    private var horarioMananaInicio = HorarioUtil.HORARIO_MANANA_INICIO_TEXTO
    private var horarioMananaFin = HorarioUtil.HORARIO_MANANA_FIN_TEXTO
    private var horarioTardeInicio = HorarioUtil.HORARIO_TARDE_INICIO_TEXTO
    private var horarioTardeFin = HorarioUtil.HORARIO_TARDE_FIN_TEXTO

    /**
     * Ciclo de vida: inicializa la interfaz, configura los selectores de hora
     * para cada turno y carga el horario actual desde Firestore.
     *
     * @param savedInstanceState Estado guardado de la instancia anterior, o null si es nueva.
     */
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

    /**
     * Valida y guarda el horario de atención en Firestore. Verifica que los turnos
     * no se solapen y que el fin de cada turno sea posterior a su inicio.
     * Propaga los cambios al inventario público.
     */
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
                baseDatos.collection(Constantes.COLECCION_USUARIOS)
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

    /**
     * Carga el horario de atención actual del vendedor desde Firestore
     * y actualiza los botones y el resumen en la interfaz.
     */
    private suspend fun cargarHorario() {
        val usuario = autenticacion.currentUser ?: return
        try {
            val documento = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(usuario.uid)
                .get()
                .await()

            horarioMananaInicio = documento.getString("horarioMananaInicio") ?: HorarioUtil.HORARIO_MANANA_INICIO_TEXTO
            horarioMananaFin = documento.getString("horarioMananaFin") ?: HorarioUtil.HORARIO_MANANA_FIN_TEXTO
            horarioTardeInicio = documento.getString("horarioTardeInicio") ?: HorarioUtil.HORARIO_TARDE_INICIO_TEXTO
            horarioTardeFin = documento.getString("horarioTardeFin") ?: HorarioUtil.HORARIO_TARDE_FIN_TEXTO
            botonMananaInicio.text = horarioMananaInicio
            botonMananaFin.text = horarioMananaFin
            botonTardeInicio.text = horarioTardeInicio
            botonTardeFin.text = horarioTardeFin
            actualizarResumenHorario()
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo cargar el horario")
        }
    }

    /**
     * Actualiza el texto de resumen del horario de atención en la interfaz.
     */
    private fun actualizarResumenHorario() {
        textoHorario.text = "Horario de atención: ${construirHorarioAtencion()}"
    }

    /**
     * Construye la cadena de texto del horario de atención en formato
     * «HH:MM - HH:MM / HH:MM - HH:MM» (mañana / tarde).
     *
     * @return Cadena con el horario de atención formateado.
     */
    private fun construirHorarioAtencion(): String {
        return "$horarioMananaInicio - $horarioMananaFin / $horarioTardeInicio - $horarioTardeFin"
    }

    /**
     * Muestra un selector de hora (TimePickerDialog) preconfigurado con la hora actual.
     * Al confirmar, invoca el callback con la hora seleccionada en formato «HH:MM».
     *
     * @param horaActual Hora actual en formato «HH:MM» para preseleccionar en el diálogo.
     * @param onHoraSeleccionada Callback que recibe la hora seleccionada en formato «HH:MM».
     */
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

    /**
     * Convierte una hora en formato «HH:MM» a su equivalente en minutos desde medianoche.
     *
     * @param hora Cadena con la hora en formato «HH:MM».
     * @return Total de minutos desde medianoche, o null si el formato es inválido.
     */
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

    /**
     * Propaga los datos de horario a todos los documentos del inventario público
     * del vendedor, utilizando una escritura por lotes de Firestore.
     *
     * @param uid Identificador único del vendedor.
     * @param datos Mapa de datos a actualizar en cada documento.
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

    /**
     * Muestra un mensaje breve en pantalla mediante un Toast.
     *
     * @param mensaje Texto a mostrar al usuario.
     */
    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }

    /**
     * Objeto compañero para constantes de la actividad. Actualmente vacío,
     * reservado para futuras extensiones.
     */
    companion object {
    }
}
