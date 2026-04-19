package com.example.rutaalmacen

import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

abstract class PantallaRolActivity : AppCompatActivity() {

    abstract val tituloPantalla: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_pantalla_rol)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_pantalla_rol)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                barrasDelSistema.top,
                barrasDelSistema.right,
                barrasDelSistema.bottom,
            )
            insets
        }

        findViewById<TextView>(R.id.texto_pantalla_rol).text = tituloPantalla
    }
}
