package com.example.rutaalmacen

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class VendedorActivity : AppCompatActivity() {

    private lateinit var fragmentAlmacen: AlmacenFragment
    private lateinit var fragmentUbicacion: UbicacionFragment
    private lateinit var fragmentProductos: ProductosFragment
    private lateinit var fragmentLista: ProductosFragment
    private lateinit var fragmentAlertas: AlertasIAFragment
    private var fragmentActivo: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_vendedor)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_vendedor)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                barrasDelSistema.top,
                barrasDelSistema.right,
                barrasDelSistema.bottom,
            )
            insets
        }

        val navegacion = findViewById<BottomNavigationView>(R.id.nav_vendedor)
        val gestorFragmentos = supportFragmentManager

        fragmentAlmacen = gestorFragmentos.findFragmentByTag(TAG_ALMACEN) as? AlmacenFragment ?: AlmacenFragment()
        fragmentUbicacion = gestorFragmentos.findFragmentByTag(TAG_UBICACION) as? UbicacionFragment ?: UbicacionFragment()
        fragmentProductos = gestorFragmentos.findFragmentByTag(TAG_PRODUCTOS) as? ProductosFragment
            ?: ProductosFragment.nuevaInstancia(false)
        fragmentLista = gestorFragmentos.findFragmentByTag(TAG_LISTA) as? ProductosFragment
            ?: ProductosFragment.nuevaInstancia(true)
        fragmentAlertas = gestorFragmentos.findFragmentByTag(TAG_ALERTAS) as? AlertasIAFragment
            ?: AlertasIAFragment()

        if (savedInstanceState == null) {
            gestorFragmentos.beginTransaction()
                .add(R.id.contenedor_fragmentos, fragmentAlertas, TAG_ALERTAS)
                .hide(fragmentAlertas)
                .add(R.id.contenedor_fragmentos, fragmentLista, TAG_LISTA)
                .hide(fragmentLista)
                .add(R.id.contenedor_fragmentos, fragmentProductos, TAG_PRODUCTOS)
                .hide(fragmentProductos)
                .add(R.id.contenedor_fragmentos, fragmentUbicacion, TAG_UBICACION)
                .hide(fragmentUbicacion)
                .add(R.id.contenedor_fragmentos, fragmentAlmacen, TAG_ALMACEN)
                .commit()
            fragmentActivo = fragmentAlmacen
            navegacion.selectedItemId = R.id.nav_almacen
        } else {
            fragmentActivo = listOf(
                fragmentAlmacen,
                fragmentUbicacion,
                fragmentProductos,
                fragmentLista,
                fragmentAlertas,
            )
                .firstOrNull { it.isVisible } ?: fragmentAlmacen
            navegacion.selectedItemId = when (fragmentActivo) {
                fragmentUbicacion -> R.id.nav_ubicacion
                fragmentProductos -> R.id.nav_productos
                fragmentLista -> R.id.nav_lista
                fragmentAlertas -> R.id.nav_alertas
                else -> R.id.nav_almacen
            }
        }

        navegacion.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_almacen -> mostrarFragmento(fragmentAlmacen)
                R.id.nav_ubicacion -> mostrarFragmento(fragmentUbicacion)
                R.id.nav_productos -> mostrarFragmento(fragmentProductos)
                R.id.nav_lista -> mostrarFragmento(fragmentLista)
                R.id.nav_alertas -> mostrarFragmento(fragmentAlertas)
            }
            true
        }
    }

    private fun mostrarFragmento(fragmento: Fragment) {
        val actual = fragmentActivo
        if (actual == null || actual == fragmento) {
            fragmentActivo = fragmento
            return
        }
        supportFragmentManager.beginTransaction()
            .hide(actual)
            .show(fragmento)
            .commit()
        fragmentActivo = fragmento
    }

    private companion object {
        private const val TAG_ALMACEN = "fragment_almacen"
        private const val TAG_UBICACION = "fragment_ubicacion"
        private const val TAG_PRODUCTOS = "fragment_productos"
        private const val TAG_LISTA = "fragment_lista"
        private const val TAG_ALERTAS = "fragment_alertas"
    }
}
