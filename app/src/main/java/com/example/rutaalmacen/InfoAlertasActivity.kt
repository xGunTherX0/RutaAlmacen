package com.example.rutaalmacen

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Actividad informativa que muestra al usuario información detallada sobre el sistema
 * de alertas de la aplicación. Presenta una pantalla estática con un botón para
 * volver a la actividad anterior.
 */
class InfoAlertasActivity : AppCompatActivity() {

    /**
     * Método del ciclo de vida llamado al crear la actividad.
     *
     * <p>Configura el diseño de borde a borde, establece el contenido visual
     * y registra el listener del botón de retroceso para finalizar la actividad.</p>
     *
     * @param savedInstanceState Estado previamente guardado de la instancia, o {@code null}
     *                           si es la primera vez que se crea.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_info_alertas)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_info_alertas_pantalla)) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }
        findViewById<androidx.appcompat.widget.AppCompatImageButton>(R.id.boton_volver_info).setOnClickListener {
            finish()
        }
    }
}
