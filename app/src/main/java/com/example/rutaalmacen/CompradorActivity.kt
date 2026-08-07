package com.example.rutaalmacen

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.location.Geocoder
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class CompradorActivity : AppCompatActivity() {

    private val categoriasHome = listOf(
        CategoriaHome("Caja Vecina", R.drawable.img_cat_caja_vecina, "caja_vecina"),
        CategoriaHome("Ofertas", R.drawable.img_cat_ofertas, "ofertas"),
        CategoriaHome("Despensa", R.drawable.img_cat_abarrotes, "Despensa"),
        CategoriaHome("Lácteos y Quesos", R.drawable.img_cat_lacteos, "Lácteos y Quesos"),
        CategoriaHome("Huevos", R.drawable.img_cat_huevos, "Huevos"),
        CategoriaHome("Cecinas y Embutidos", R.drawable.img_cat_cecinas, "Cecinas y Embutidos"),
        CategoriaHome("Bebidas y Jugos", R.drawable.img_cat_bebidas, "Bebidas y Jugos"),
        CategoriaHome("Alcohol", R.drawable.img_cat_alcohol, "Alcohol"),
        CategoriaHome("Pan y Pastelería", R.drawable.img_cat_panaderia, "Pan y Pastelería"),
        CategoriaHome("Frutas y Verduras", R.drawable.img_cat_frutas, "Frutas y Verduras"),
        CategoriaHome("Snacks y Dulces", R.drawable.img_cat_snacks, "Snacks y Dulces"),
        CategoriaHome("Congelados", R.drawable.img_cat_congelados, "Congelados"),
        CategoriaHome("Aseo Hogar", R.drawable.img_cat_aseo, "Aseo Hogar"),
        CategoriaHome("Higiene Personal", R.drawable.img_cat_higiene, "Higiene Personal"),
    )

    private val proveedorUbicacion: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private lateinit var textoUbicacion: TextView
    private lateinit var iconoUbicacion: ImageView
    private var animacionPulso: ObjectAnimator? = null
    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private val solicitudPermisoUbicacion = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { resultados ->
        val concedido = resultados[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            resultados[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (concedido) {
            detectarUbicacion()
        } else {
            detenerPulso()
            textoUbicacion.text = "Ubicación no disponible"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_comprador)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val yaVioAlertasIA = prefs.getBoolean("ya_vio_alertas_ia", false)

        if (!yaVioAlertasIA) {
            prefs.edit().putBoolean("ya_vio_alertas_ia", true).apply()
            startActivity(Intent(this, InfoAlertasActivity::class.java))
        }

        val header = findViewById<View>(R.id.header_home)
        val paddingHeaderBase = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_comprador)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                0,
                barrasDelSistema.right,
                0,
            )
            header.setPadding(
                header.paddingLeft,
                paddingHeaderBase + barrasDelSistema.top,
                header.paddingRight,
                header.paddingBottom,
            )
            insets
        }

        MobileAds.initialize(this) {}

        val esVendedorVisitante = intent.getBooleanExtra("volver_a_vendedor", false)
        val botonVolverVendedor = findViewById<MaterialButton>(R.id.boton_volver_vendedor)
        if (esVendedorVisitante) {
            botonVolverVendedor.visibility = View.VISIBLE
            botonVolverVendedor.setOnClickListener {
                startActivity(Intent(this, VendedorActivity::class.java))
                finish()
            }
        }

        configurarChipUbicacion()
        configurarCategorias()
        configurarAccesosDirectos()
        configurarBotonAlertas()
        configurarBotonMenu()
        configurarAdView()
        detectarUbicacion()
    }

    private fun configurarChipUbicacion() {
        textoUbicacion = findViewById(R.id.texto_ubicacion)
        iconoUbicacion = findViewById(R.id.icono_ubicacion)
    }

    private fun detectarUbicacion() {
        if (!UbicacionUtil.tienePermisoUbicacion(this)) {
            detenerPulso()
            textoUbicacion.text = "Ubicación no disponible"
            solicitudPermisoUbicacion.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            return
        }

        textoUbicacion.text = "Detectando tu zona..."
        iniciarPulso()

        lifecycleScope.launch {
            val ubicacion = UbicacionUtil.obtenerUbicacionRapida(proveedorUbicacion)
                ?: UbicacionUtil.obtenerUbicacionFused(proveedorUbicacion)
                ?: UbicacionUtil.obtenerUbicacionSistema(this@CompradorActivity)

            detenerPulso()

            if (ubicacion == null) {
                textoUbicacion.text = "Zona no detectada"
                return@launch
            }

            val zona = withContext(Dispatchers.IO) {
                obtenerNombreZona(ubicacion.latitude, ubicacion.longitude)
            }
            textoUbicacion.text = zona
        }
    }

    private fun obtenerNombreZona(latitud: Double, longitud: Double): String {
        return try {
            @Suppress("DEPRECATION")
            val direcciones = Geocoder(this, Locale("es", "CL"))
                .getFromLocation(latitud, longitud, 1)
            val dir = direcciones?.firstOrNull()
            when {
                dir == null -> "Cerca de ti"
                !dir.subLocality.isNullOrBlank() && !dir.locality.isNullOrBlank() ->
                    "${dir.subLocality}, ${dir.locality}"
                !dir.locality.isNullOrBlank() -> dir.locality
                !dir.subAdminArea.isNullOrBlank() -> dir.subAdminArea
                !dir.adminArea.isNullOrBlank() -> dir.adminArea
                !dir.thoroughfare.isNullOrBlank() -> dir.thoroughfare
                else -> "Cerca de ti"
            }
        } catch (_: Exception) {
            "Cerca de ti"
        }
    }

    private fun iniciarPulso() {
        detenerPulso()
        animacionPulso = ObjectAnimator.ofFloat(iconoUbicacion, View.ALPHA, 1f, 0.35f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun detenerPulso() {
        animacionPulso?.cancel()
        animacionPulso = null
        iconoUbicacion.alpha = 1f
    }

    private fun configurarCategorias() {
        val mitad = (categoriasHome.size + 1) / 2
        val fila1 = categoriasHome.take(mitad)
        val fila2 = categoriasHome.drop(mitad)

        val alClick: (CategoriaHome) -> Unit = { categoria ->
            if (categoria.categoriaBusqueda == "caja_vecina") {
                val intent = Intent(this, AlmacenesCercanosActivity::class.java)
                intent.putExtra("filtro_caja_vecina", true)
                startActivity(intent)
            } else if (categoria.categoriaBusqueda == "ofertas") {
                val intent = Intent(this, ProductosActivity::class.java)
                intent.putExtra("filtro_ofertas", true)
                startActivity(intent)
            } else {
                val intent = Intent(this, ProductosActivity::class.java)
                intent.putExtra("categoria", categoria.categoriaBusqueda)
                startActivity(intent)
            }
        }

        configurarFilaCategorias(
            R.id.recycler_categorias_fila1,
            R.id.flecha_cat_fila1_izq,
            R.id.flecha_cat_fila1_der,
            fila1,
            alClick,
        )
        configurarFilaCategorias(
            R.id.recycler_categorias_fila2,
            R.id.flecha_cat_fila2_izq,
            R.id.flecha_cat_fila2_der,
            fila2,
            alClick,
        )
    }

    private fun configurarFilaCategorias(
        recyclerId: Int,
        flechaIzqId: Int,
        flechaDerId: Int,
        categorias: List<CategoriaHome>,
        onClick: (CategoriaHome) -> Unit,
    ) {
        val recycler = findViewById<RecyclerView>(recyclerId)
        val flechaIzq = findViewById<ImageView>(flechaIzqId)
        val flechaDer = findViewById<ImageView>(flechaDerId)
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        recycler.layoutManager = layoutManager
        recycler.adapter = AdaptadorCategorias(categorias, onClick)

        flechaIzq.setOnClickListener {
            val pos = layoutManager.findFirstVisibleItemPosition()
            if (pos > 0) {
                recycler.smoothScrollToPosition(pos - 1)
            }
        }
        flechaDer.setOnClickListener {
            val pos = layoutManager.findLastVisibleItemPosition()
            val total = recycler.adapter?.itemCount ?: 0
            if (pos in 0 until total - 1) {
                recycler.smoothScrollToPosition(pos + 1)
            }
        }

        val actualizarFlechas = {
            val total = recycler.adapter?.itemCount ?: 0
            if (total <= 2) {
                flechaIzq.visibility = View.GONE
                flechaDer.visibility = View.GONE
            } else {
                val primero = layoutManager.findFirstCompletelyVisibleItemPosition()
                val ultimo = layoutManager.findLastCompletelyVisibleItemPosition()
                flechaIzq.visibility = if (primero > 0) View.VISIBLE else View.INVISIBLE
                flechaDer.visibility =
                    if (ultimo >= 0 && ultimo < total - 1) View.VISIBLE else View.INVISIBLE
            }
        }

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                actualizarFlechas()
            }
        })
        recycler.post { actualizarFlechas() }
    }

    private fun configurarAccesosDirectos() {
        findViewById<MaterialCardView>(R.id.card_almacenes).setOnClickListener {
            startActivity(Intent(this, AlmacenesCercanosActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.card_productos).setOnClickListener {
            startActivity(Intent(this, ListaComprasActivity::class.java))
        }
    }

    private fun configurarBotonAlertas() {
        findViewById<MaterialButton>(R.id.boton_info_alertas).setOnClickListener {
            startActivity(Intent(this, InfoAlertasActivity::class.java))
        }
    }

    private fun configurarBotonMenu() {
        val botonMenu = findViewById<ImageButton>(R.id.boton_menu_comprador)
        botonMenu.setOnClickListener { vista ->
            val popup = PopupMenu(this, vista)
            popup.menuInflater.inflate(R.menu.menu_comprador_opciones, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.opcion_cerrar_sesion -> {
                        cerrarSesion()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun cerrarSesion() {
        autenticacion.signOut()
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun configurarAdView() {
        val adView = findViewById<AdView>(R.id.ad_view)
        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)
    }

    override fun onDestroy() {
        detenerPulso()
        super.onDestroy()
    }
}
