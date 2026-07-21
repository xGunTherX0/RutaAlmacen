package com.example.rutaalmacen

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.example.rutaalmacen.notas.HomeFragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Actividad principal del módulo de vendedor.
 *
 * Gestiona la navegación por fragmentos dentro de la interfaz del vendedor,
 * incluyendo el inicio, la configuración del almacén, la gestión de productos,
 * la lista de productos y las alertas de inteligencia artificial.
 * Utiliza un [BottomNavigationView] para alternar entre los distintos fragmentos
 * y maneja tanto la creación inicial como la restauración de estado.
 */
class VendedorActivity : AppCompatActivity() {

    /** Fragmento de inicio (panel principal del vendedor). */
    private lateinit var fragmentInicio: InicioFragment

    /** Fragmento de configuración del almacén. */
    private lateinit var fragmentAlmacen: AlmacenFragment

    /** Fragmento para registrar productos nuevos. */
    private lateinit var fragmentProductos: AgregarProductosFragment

    /** Fragmento que muestra la lista completa de productos del vendedor. */
    private lateinit var fragmentLista: ListaProductosFragment

    /** Fragmento que muestra las alertas generadas por inteligencia artificial. */
    private lateinit var fragmentAlertas: AlertasIAFragment

    /** Referencia al fragmento actualmente visible en el contenedor. */
    private var fragmentActivo: Fragment? = null

    /** Barra de navegación inferior utilizada para cambiar entre fragmentos. */
    private lateinit var navegacion: BottomNavigationView

    /**
     * Crea la actividad del vendedor, inicializa los fragmentos y configura
     * la navegación inferior.
     *
     * En la primera creación ([savedInstanceState] es `null`) se añaden todos los
     * fragmentos al contenedor y se ocultan excepto el de inicio. Si se restaura
     * el estado, se detecta cuál fragmento está visible y se sincroniza la navegación.
     *
     * @param savedInstanceState Estado guardado previamente, o `null` si es la primera creación.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
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

        navegacion = findViewById(R.id.nav_vendedor)
        val gestorFragmentos = supportFragmentManager

        fragmentInicio = gestorFragmentos.findFragmentByTag(TAG_INICIO) as? InicioFragment ?: InicioFragment()
        fragmentAlmacen = gestorFragmentos.findFragmentByTag(TAG_ALMACEN) as? AlmacenFragment ?: AlmacenFragment()
        fragmentProductos = gestorFragmentos.findFragmentByTag(TAG_PRODUCTOS) as? AgregarProductosFragment
            ?: AgregarProductosFragment()
        fragmentLista = gestorFragmentos.findFragmentByTag(TAG_LISTA) as? ListaProductosFragment
            ?: ListaProductosFragment()
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
                .add(R.id.contenedor_fragmentos, fragmentAlmacen, TAG_ALMACEN)
                .hide(fragmentAlmacen)
                .add(R.id.contenedor_fragmentos, fragmentInicio, TAG_INICIO)
                .commit()
            fragmentActivo = fragmentInicio
            navegacion.selectedItemId = R.id.nav_inicio
        } else {
            fragmentActivo = listOf(
                fragmentInicio,
                fragmentAlmacen,
                fragmentProductos,
                fragmentLista,
                fragmentAlertas,
            )
                .firstOrNull { it.isVisible } ?: fragmentInicio
            navegacion.selectedItemId = when (fragmentActivo) {
                fragmentAlmacen -> R.id.nav_almacen
                fragmentProductos -> R.id.nav_productos
                fragmentLista -> R.id.nav_lista
                fragmentAlertas -> R.id.nav_alertas
                else -> R.id.nav_inicio
            }
        }

        navegacion.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_inicio -> mostrarFragmento(fragmentInicio)
                R.id.nav_almacen -> mostrarFragmento(fragmentAlmacen)
                R.id.nav_productos -> mostrarFragmento(fragmentProductos)
                R.id.nav_lista -> mostrarFragmento(fragmentLista)
                R.id.nav_alertas -> mostrarFragmento(fragmentAlertas)
            }
            true
        }
    }

    /**
     * Programa la selección de una pestaña de navegación de forma externa.
     *
     * Permite que otros componentes (como los fragmentos hijos) soliciten
     * la navegación hacia una sección específica del vendedor.
     *
     * @param itemId Identificador del elemento de menú a seleccionar
     *               (por ejemplo, [R.id.nav_almacen]).
     */
    fun seleccionarTab(itemId: Int) {
        if (::navegacion.isInitialized) {
            navegacion.selectedItemId = itemId
        }
    }

    /**
     * Muestra el fragmento indicado y oculta el fragmento actualmente activo.
     *
     * Utiliza transacciones del [androidx.fragment.app.FragmentManager] para
     * alternar la visibilidad sin destruir los fragmentos, conservando su estado interno.
     *
     * @param fragmento Fragmento que debe quedar visible tras la transacción.
     */
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

    /** Constantes utilizadas como etiquetas para la gestión de fragmentos. */
    private companion object {
        /** Etiqueta del fragmento de inicio. */
        private const val TAG_INICIO = "fragment_inicio"
        /** Etiqueta del fragmento de configuración del almacén. */
        private const val TAG_ALMACEN = "fragment_almacen"
        /** Etiqueta del fragmento de registro de productos. */
        private const val TAG_PRODUCTOS = "fragment_productos"
        /** Etiqueta del fragmento de lista de productos. */
        private const val TAG_LISTA = "fragment_lista"
        /** Etiqueta del fragmento de alertas de inteligencia artificial. */
        private const val TAG_ALERTAS = "fragment_alertas"
    }
}
