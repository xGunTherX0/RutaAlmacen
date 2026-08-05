package com.example.rutaalmacen

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.rutaalmacen.notas.NotasActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Actividad principal del módulo de vendedor.
 *
 * Gestiona la navegación por fragmentos dentro de la interfaz del vendedor,
 * incluyendo el inicio, la gestión de productos, la lista de productos y las
 * alertas de inteligencia artificial.
 * Utiliza un [BottomNavigationView] para alternar entre los distintos fragmentos
 * y maneja tanto la creación inicial como la restauración de estado.
 * Incluye un [DrawerLayout] lateral para la configuración del almacén,
 * accesible desde cualquier pantalla mediante el botón hamburguesa del header.
 */
class VendedorActivity : AppCompatActivity() {

    /** Fragmento de inicio (panel principal del vendedor). */
    private lateinit var fragmentInicio: InicioFragment

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

    /** TextView del título en el header verde. */
    private lateinit var textoTituloHeader: TextView

    /** Botón hamburguesa para abrir el drawer de configuración. Siempre visible. */
    private lateinit var botonMenuDrawer: ImageButton

    /** Drawer lateral de configuración del almacén. */
    private lateinit var drawerLayout: DrawerLayout

    /** Instancia de Firebase Authentication. */
    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    /** Instancia de Firestore. */
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_vendedor)

        val header = findViewById<View>(R.id.header_vendedor)
        val paddingHeaderBase = header.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_vendedor)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                0,
                barrasDelSistema.right,
                barrasDelSistema.bottom,
            )
            header.setPadding(
                header.paddingLeft,
                paddingHeaderBase + barrasDelSistema.top,
                header.paddingRight,
                header.paddingBottom,
            )
            insets
        }

        drawerLayout = findViewById(R.id.drawer_layout)
        navegacion = findViewById(R.id.nav_vendedor)
        textoTituloHeader = findViewById(R.id.texto_titulo_header_vendedor)
        botonMenuDrawer = findViewById(R.id.boton_menu_drawer)
        val gestorFragmentos = supportFragmentManager

        fragmentInicio = gestorFragmentos.findFragmentByTag(TAG_INICIO) as? InicioFragment ?: InicioFragment()
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
                .add(R.id.contenedor_fragmentos, fragmentInicio, TAG_INICIO)
                .commit()
            fragmentActivo = fragmentInicio
            navegacion.selectedItemId = R.id.nav_inicio
            actualizarTituloHeader(fragmentInicio)
        } else {
            fragmentActivo = listOf(
                fragmentInicio,
                fragmentProductos,
                fragmentLista,
                fragmentAlertas,
            )
                .firstOrNull { it.isVisible } ?: fragmentInicio
            navegacion.selectedItemId = when (fragmentActivo) {
                fragmentProductos -> R.id.nav_productos
                fragmentLista -> R.id.nav_lista
                fragmentAlertas -> R.id.nav_alertas
                else -> R.id.nav_inicio
            }
            actualizarTituloHeader(fragmentActivo!!)
        }

        botonMenuDrawer.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        configurarDrawer()

        navegacion.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_inicio -> mostrarFragmento(fragmentInicio)
                R.id.nav_productos -> mostrarFragmento(fragmentProductos)
                R.id.nav_lista -> mostrarFragmento(fragmentLista)
                R.id.nav_alertas -> mostrarFragmento(fragmentAlertas)
            }
            true
        }
    }

    /**
     * Configura los listeners de navegación y switches dentro del drawer.
     */
    private fun configurarDrawer() {
        val drawerView = findViewById<View>(R.id.drawer_almacen)

        drawerView.findViewById<View>(R.id.drawer_nombre_almacen).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, NombreAlmacenActivity::class.java))
        }
        drawerView.findViewById<View>(R.id.drawer_horario_almacen).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, HorarioAlmacenActivity::class.java))
        }
        drawerView.findViewById<View>(R.id.drawer_ubicacion_almacen).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, UbicacionActivity::class.java))
        }
        drawerView.findViewById<View>(R.id.drawer_categoria_almacen).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, CategoriaAlmacenActivity::class.java))
        }
        drawerView.findViewById<View>(R.id.drawer_metodos_pago).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, MetodosPagoActivity::class.java))
        }
        drawerView.findViewById<View>(R.id.drawer_block_notas).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, NotasActivity::class.java))
        }
        drawerView.findViewById<View>(R.id.drawer_ver_como_comprador).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            val intent = Intent(this, CompradorActivity::class.java)
            intent.putExtra("volver_a_vendedor", true)
            startActivity(intent)
        }

        val switchEstado = drawerView.findViewById<MaterialSwitch>(R.id.drawer_switch_estado_almacen)
        drawerView.findViewById<View>(R.id.drawer_estado_almacen).setOnClickListener {
            switchEstado.isChecked = !switchEstado.isChecked
        }
        switchEstado.setOnCheckedChangeListener { _, isChecked ->
            actualizarEstadoAlmacen(isChecked)
        }

        val switchCaja = drawerView.findViewById<MaterialSwitch>(R.id.drawer_switch_caja_vecina)
        drawerView.findViewById<View>(R.id.drawer_caja_vecina).setOnClickListener {
            switchCaja.isChecked = !switchCaja.isChecked
        }
        switchCaja.setOnCheckedChangeListener { _, isChecked ->
            actualizarCajaVecina(isChecked)
        }

        drawerView.findViewById<View>(R.id.drawer_saldo_caja_vecina).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, CajaVecinaActivity::class.java))
        }

        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                cargarEstadosDrawer()
            }
        })
    }

    /**
     * Carga los estados actuales desde Firestore y los refleja en los switches del drawer.
     */
    private fun cargarEstadosDrawer() {
        val usuario = autenticacion.currentUser ?: return
        lifecycleScope.launch {
            try {
                val documento = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                    .document(usuario.uid)
                    .get()
                    .await()
                val cerradoManual = documento.getBoolean("cerradoManual") ?: false
                val metodosPago = (documento.get("metodosPago") as? List<String>).orEmpty()
                val tieneCaja = documento.getBoolean("tieneCajaVecina") ?: false
                val saldoCaja = documento.getDouble("saldoCajaVecina") ?: 0.0

                val drawerView = findViewById<View>(R.id.drawer_almacen)
                drawerView.findViewById<MaterialSwitch>(R.id.drawer_switch_estado_almacen).isChecked = cerradoManual
                drawerView.findViewById<MaterialSwitch>(R.id.drawer_switch_caja_vecina).isChecked = tieneCaja

                val saldoTexto = if (saldoCaja > 0) {
                    "$${saldoCaja.toInt()}"
                } else {
                    "$0"
                }
                drawerView.findViewById<TextView>(R.id.drawer_texto_saldo_caja_vecina).text = saldoTexto

                val textoPagos = if (metodosPago.isEmpty()) {
                    "Pagos: Efectivo, Débito"
                } else {
                    "Pagos: ${metodosPago.joinToString(", ")}"
                }
                drawerView.findViewById<TextView>(R.id.drawer_texto_metodos_pago).text = textoPagos
            } catch (_: Exception) {
                // Silenciar
            }
        }
    }

    /**
     * Actualiza el estado de apertura/cierre del almacén en Firestore.
     */
    private fun actualizarEstadoAlmacen(cerrado: Boolean) {
        val usuario = autenticacion.currentUser ?: return
        lifecycleScope.launch {
            try {
                baseDatos.collection(Constantes.COLECCION_USUARIOS)
                    .document(usuario.uid)
                    .update("cerradoManual", cerrado)
                    .await()
            } catch (_: Exception) {
                // Silenciar
            }
        }
    }

    /**
     * Actualiza el estado de Caja Vecina en Firestore.
     */
    private fun actualizarCajaVecina(activo: Boolean) {
        val usuario = autenticacion.currentUser ?: return
        lifecycleScope.launch {
            try {
                baseDatos.collection(Constantes.COLECCION_USUARIOS)
                    .document(usuario.uid)
                    .update("tieneCajaVecina", activo)
                    .await()
            } catch (_: Exception) {
                // Silenciar
            }
        }
    }

    /**
     * Programa la selección de una pestaña de navegación de forma externa.
     *
     * @param itemId Identificador del elemento de menú a seleccionar.
     */
    fun seleccionarTab(itemId: Int) {
        if (::navegacion.isInitialized) {
            navegacion.selectedItemId = itemId
        }
    }

    /**
     * Abre el drawer de configuración del almacén.
     * Accesible desde cualquier fragmento.
     */
    fun abrirDrawerAlmacen() {
        if (::drawerLayout.isInitialized) {
            drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    /**
     * Muestra el fragmento indicado y oculta el fragmento actualmente activo.
     *
     * @param fragmento Fragmento que debe quedar visible tras la transacción.
     */
    private fun mostrarFragmento(fragmento: Fragment) {
        val actual = fragmentActivo
        if (actual == null || actual == fragmento) {
            fragmentActivo = fragmento
            actualizarTituloHeader(fragmento)
            return
        }
        supportFragmentManager.beginTransaction()
            .hide(actual)
            .show(fragmento)
            .commit()
        fragmentActivo = fragmento
        actualizarTituloHeader(fragmento)
    }

    /**
     * Actualiza el título del header verde según el fragmento activo.
     *
     * @param fragmento Fragmento actualmente visible.
     */
    private fun actualizarTituloHeader(fragmento: Fragment) {
        val titulo = when (fragmento) {
            is InicioFragment -> "RUTA ALMACÉN"
            is AgregarProductosFragment -> "Agregar producto"
            is ListaProductosFragment -> "Lista de productos"
            is AlertasIAFragment -> "Alertas de IA"
            else -> "RUTA ALMACÉN"
        }
        if (::textoTituloHeader.isInitialized) {
            textoTituloHeader.text = titulo
        }
    }

    /** Constantes utilizadas como etiquetas para la gestión de fragmentos. */
    private companion object {
        /** Etiqueta del fragmento de inicio. */
        private const val TAG_INICIO = "fragment_inicio"
        /** Etiqueta del fragmento de registro de productos. */
        private const val TAG_PRODUCTOS = "fragment_productos"
        /** Etiqueta del fragmento de lista de productos. */
        private const val TAG_LISTA = "fragment_lista"
        /** Etiqueta del fragmento de alertas de inteligencia artificial. */
        private const val TAG_ALERTAS = "fragment_alertas"
    }
}
