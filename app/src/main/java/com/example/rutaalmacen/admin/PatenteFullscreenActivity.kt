package com.example.rutaalmacen.admin

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.example.rutaalmacen.R
import com.google.android.material.button.MaterialButton

class PatenteFullscreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patente_fullscreen)

        val imagenPatente = findViewById<ImageView>(R.id.imagen_patente_fullscreen)
        val barraCarga = findViewById<ProgressBar>(R.id.barra_carga_fullscreen)
        val botonCerrar = findViewById<MaterialButton>(R.id.boton_cerrar_fullscreen)

        val imageUrl = intent.getStringExtra("imageUrl").orEmpty()

        if (imageUrl.isNotBlank()) {
            Glide.with(this)
                .load(imageUrl)
                .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                    override fun onLoadFailed(
                        e: com.bumptech.glide.load.engine.GlideException?,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        barraCarga.visibility = View.GONE
                        return false
                    }

                    override fun onResourceReady(
                        resource: android.graphics.drawable.Drawable,
                        model: Any,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                        dataSource: com.bumptech.glide.load.DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        barraCarga.visibility = View.GONE
                        return false
                    }
                })
                .into(imagenPatente)
        }

        botonCerrar.setOnClickListener { finish() }
    }
}
