package com.example.rutaalmacen

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class CompradorActivity : AppCompatActivity() {

    private val categoriasHome = listOf(
        CategoriaHome("Caja Vecina", R.drawable.ic_caja_vecina, "caja_vecina"),
        CategoriaHome("Bebidas", R.drawable.ic_bebidas, "Bebidas y Jugos"),
        CategoriaHome("Panadería", R.drawable.ic_panaderia, "Pan y Pastelería"),
        CategoriaHome("Abarrotes", R.drawable.ic_abarrotes, "Despensa"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_comprador)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_comprador)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                barrasDelSistema.top,
                barrasDelSistema.right,
                0,
            )
            insets
        }

        MobileAds.initialize(this) {}

        val recyclerCategorias = findViewById<RecyclerView>(R.id.recycler_categorias)

        val adaptadorCategorias = AdaptadorCategorias(
            categorias = categoriasHome,
            onCategoriaClick = { categoria ->
                if (categoria.categoriaBusqueda == "caja_vecina") {
                    val intent = Intent(this, AlmacenesCercanosActivity::class.java)
                    intent.putExtra("filtro_caja_vecina", true)
                    startActivity(intent)
                } else {
                    val intent = Intent(this, ProductosActivity::class.java)
                    intent.putExtra("categoria", categoria.categoriaBusqueda)
                    startActivity(intent)
                }
            }
        )
        recyclerCategorias.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerCategorias.adapter = adaptadorCategorias

        configurarAccesosDirectos()
        configurarBotonAlertas()
        configurarAdView()
    }

    private fun configurarAccesosDirectos() {
        findViewById<MaterialCardView>(R.id.card_almacenes).setOnClickListener {
            startActivity(Intent(this, AlmacenesCercanosActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.card_productos).setOnClickListener {
            startActivity(Intent(this, ProductosActivity::class.java))
        }
    }

    private fun configurarBotonAlertas() {
        findViewById<MaterialButton>(R.id.boton_info_alertas).setOnClickListener {
            startActivity(Intent(this, InfoAlertasActivity::class.java))
        }
    }

    private fun configurarAdView() {
        val adView = findViewById<AdView>(R.id.ad_view)
        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)
    }
}
