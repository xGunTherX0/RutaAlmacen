package com.example.rutaalmacen

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rutaalmacen.admin.AdminViewModel
import com.example.rutaalmacen.admin.ConfirmarRolDialogFragment
import com.example.rutaalmacen.admin.GestionarPlanDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.Timestamp
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Panel de administración que permite gestionar los usuarios registrados en la aplicación.
 *
 * Ofrece funcionalidades de búsqueda, filtrado por rol, bloqueo temporal o indefinido,
 * eliminación de cuentas, visualización de stock de vendedores, gestión de planes,
 * promoción a administrador y acceso a utilidades adicionales como palabras bloqueadas,
 * alertas reportadas y estadísticas.
 *
 * Distingue entre super administrador y administrador visualizador; este último
 * tiene acceso restringido solo a la consulta de información.
 */
class AdminActivity : AppCompatActivity() {

    /** Instancia de [FirebaseFirestore] para acceder a la base de datos. */
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /** Instancia de [FirebaseAuth] para obtener el usuario autenticado actual. */
    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    /** ViewModel que centraliza la lógica administrativa y el estado de permisos. */
    private val viewModel: AdminViewModel by lazy { ViewModelProvider(this)[AdminViewModel::class.java] }

    /** Campo de entrada reutilizable para solicitar el número de días de bloqueo. */
    private val inputDiasBloqueo: EditText by lazy {
        EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Número de días (ej. 3)"
            setText("3")
        }
    }

    /** Lista completa de usuarios cargada desde Firestore, sin filtrar. */
    private val usuariosBase: MutableList<UsuarioAdmin> = mutableListOf()

    /** Lista de usuarios que cumple con los filtros actualmente aplicados. */
    private val usuariosFiltrados: MutableList<UsuarioAdmin> = mutableListOf()

    /** Adaptador del [RecyclerView] que muestra la lista de usuarios. */
    private lateinit var adaptador: AdaptadorUsuariosAdmin

    /** Etiqueta de texto mostrada cuando no hay usuarios que coincidan con los filtros. */
    private lateinit var textoSinUsuarios: android.widget.TextView

    /** Filtro de rol actualmente seleccionado (Todos, Vendedores o Compradores). */
    private var filtroRol = FILTRO_TODOS

    /** Texto actual del campo de búsqueda de usuarios. */
    private var textoBusqueda = ""

    /** Indica si el administrador actual tiene rol de solo visualización. */
    private var esAdminVisualizador = false

    /**
     * Inicializa el panel de administración: configura el [RecyclerView], los filtros,
     * la búsqueda, los botones de navegación a actividades auxiliares y carga los usuarios.
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
        setContentView(R.layout.activity_admin)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_admin)) { vista, insets ->
            val barrasDelSistema = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(
                barrasDelSistema.left,
                barrasDelSistema.top,
                barrasDelSistema.right,
                barrasDelSistema.bottom,
            )
            insets
        }

        lifecycleScope.launch { verificarTipoAdmin() }

        val recycler = findViewById<RecyclerView>(R.id.recycler_usuarios_admin)
        textoSinUsuarios = findViewById(R.id.texto_sin_usuarios)
        val campoBusqueda = findViewById<TextInputEditText>(R.id.campo_busqueda_usuario)
        val campoFiltroRol = findViewById<AutoCompleteTextView>(R.id.campo_filtro_rol)
        val encabezadoAcciones = findViewById<android.view.View>(R.id.encabezado_acciones_admin)
        val contenidoAcciones = findViewById<android.view.View>(R.id.contenido_acciones_admin)
        val iconoAcciones = findViewById<ImageView>(R.id.icono_acciones_admin)
        val botonPalabrasBloqueadas =
            findViewById<com.google.android.material.button.MaterialButton>(R.id.boton_palabras_bloqueadas)

        adaptador = AdaptadorUsuariosAdmin(
            usuarios = usuariosFiltrados,
            onVerStock = { usuario -> abrirStockVendedor(usuario) },
            onBloquear = { usuario -> confirmarBloquear(usuario) },
            onEliminar = { usuario -> confirmarEliminar(usuario) },
            onGestionarPlan = { usuario -> abrirGestionarPlan(usuario) },
            onHacerAdmin = { usuario -> abrirConfirmarRol(usuario) },
            isSuperAdmin = viewModel.esSuperAdmin(),
            esAdminVisualizador = esAdminVisualizador,
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adaptador

        configurarDesplegable(encabezadoAcciones, contenidoAcciones, iconoAcciones, expandidoInicial = false)
        configurarFiltroRol(campoFiltroRol)
        campoBusqueda.doOnTextChanged { texto, _, _, _ ->
            textoBusqueda = texto?.toString().orEmpty()
            aplicarFiltros()
        }

        botonPalabrasBloqueadas.setOnClickListener {
            startActivity(Intent(this, PalabrasBloqueadasActivity::class.java))
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.boton_alertas_reportadas).setOnClickListener {
            startActivity(Intent(this, AlertasReportadasActivity::class.java))
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.boton_estadisticas_planes).setOnClickListener {
            startActivity(Intent(this, com.example.rutaalmacen.admin.EstadisticasActivity::class.java))
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.boton_promover_admin).setOnClickListener {
            startActivity(Intent(this, com.example.rutaalmacen.admin.PromoverAdminActivity::class.java))
        }

        lifecycleScope.launch { cargarUsuarios() }
    }

    /**
     * Refresca el tipo de administrador y recarga la lista de usuarios
     * cada vez que la actividad vuelve a primer plano.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { 
            verificarTipoAdmin()
            cargarUsuarios() 
        }
    }

    /**
     * Consulta Firestore para determinar si el administrador actual es un
     * administrador visualizador (acceso restringido) y ajusta la visibilidad
     * de los botones de acciones sensibles en consecuencia.
     */
    private suspend fun verificarTipoAdmin() {
        try {
            val uidActual = autenticacion.currentUser?.uid ?: return
            val documento = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(uidActual)
                .get()
                .await()
            val tipoAdmin = documento.getString("tipoAdmin").orEmpty()
            esAdminVisualizador = tipoAdmin == "visualizador"
            
            val botonPalabras = findViewById<com.google.android.material.button.MaterialButton>(R.id.boton_palabras_bloqueadas)
            val botonAlertas = findViewById<com.google.android.material.button.MaterialButton>(R.id.boton_alertas_reportadas)
            val botonPromover = findViewById<com.google.android.material.button.MaterialButton>(R.id.boton_promover_admin)
            
            if (esAdminVisualizador) {
                botonPalabras.visibility = android.view.View.GONE
                botonAlertas.visibility = android.view.View.GONE
                botonPromover.visibility = android.view.View.GONE
            } else {
                botonPalabras.visibility = android.view.View.VISIBLE
                botonAlertas.visibility = android.view.View.VISIBLE
                botonPromover.visibility = android.view.View.VISIBLE
            }
        } catch (excepcion: Exception) {
            esAdminVisualizador = false
        }
    }

    /**
     * Carga todos los usuarios desde Firestore ordenados por rol, excluye a los
     * administradores y administradores visualizadores, calcula los días de inactividad
     * y actualiza las listas base y filtrada junto con el adaptador.
     */
    private suspend fun cargarUsuarios() {
        try {
            val resultado = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .orderBy("rol", Query.Direction.ASCENDING)
                .get()
                .await()
            val ahora = System.currentTimeMillis()
            val nuevosUsuarios = resultado.documents.mapNotNull { documento ->
                val uid = documento.id
                val nombre = documento.getString("nombre").orEmpty()
                val correo = documento.getString("correo").orEmpty()
                val rol = documento.getString("rol").orEmpty()
                if (rol.lowercase() == Constantes.ROL_ADMINISTRADOR) {
                    return@mapNotNull null
                }
                val tipoAdmin = documento.getString("tipoAdmin").orEmpty()
                val esAdminVisualizador = rol.lowercase() == AdminViewModel.ROL_ADMINISTRADOR && tipoAdmin == "visualizador"
                if (esAdminVisualizador) {
                    return@mapNotNull null
                }
                val nombreAlmacen = documento.getString("nombreAlmacen")
                val fechaCreacion = documento.getTimestamp("fechaCreacion")
                val ultimoLogin = documento.getTimestamp("ultimoLogin")
                val bloqueado = documento.getBoolean("bloqueado") ?: false
                val diasInactivo = ultimoLogin?.let { calcularDiasInactivo(it, ahora) }
                val estado = determinarEstado(diasInactivo)

                UsuarioAdmin(
                    uid = uid,
                    nombre = nombre,
                    correo = correo,
                    rol = rol,
                    nombreAlmacen = nombreAlmacen,
                    diasInactivo = diasInactivo,
                    estado = estado,
                    fechaCreacion = fechaCreacion,
                    bloqueado = bloqueado,
                )
            }

            usuariosBase.clear()
            usuariosBase.addAll(nuevosUsuarios)
            aplicarFiltros()
            
            adaptador = AdaptadorUsuariosAdmin(
                usuarios = usuariosFiltrados,
                onVerStock = { usuario -> abrirStockVendedor(usuario) },
                onBloquear = { usuario -> confirmarBloquear(usuario) },
                onEliminar = { usuario -> confirmarEliminar(usuario) },
                onGestionarPlan = { usuario -> abrirGestionarPlan(usuario) },
                onHacerAdmin = { usuario -> abrirConfirmarRol(usuario) },
                isSuperAdmin = viewModel.esSuperAdmin(),
                esAdminVisualizador = esAdminVisualizador,
            )
            findViewById<RecyclerView>(R.id.recycler_usuarios_admin).adapter = adaptador
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudieron cargar los usuarios")
        }
    }

    /**
     * Configura el desplegable de filtro por rol con las opciones disponibles
     * y vincula la selección con la aplicación de filtros sobre la lista de usuarios.
     *
     * @param campoFiltroRol Campo de texto con autocompletado donde se muestra el filtro.
     */
    private fun configurarFiltroRol(campoFiltroRol: AutoCompleteTextView) {
        val opciones = listOf(FILTRO_TODOS, FILTRO_VENDEDORES, FILTRO_COMPRADORES)
        val adaptador = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, opciones)
        campoFiltroRol.setAdapter(adaptador)
        campoFiltroRol.setText(FILTRO_TODOS, false)
        campoFiltroRol.setOnItemClickListener { _, _, position, _ ->
            filtroRol = opciones.getOrNull(position) ?: FILTRO_TODOS
            aplicarFiltros()
        }
    }

    /**
     * Aplica los filtros de rol y texto de búsqueda sobre [usuariosBase],
     * actualiza [usuariosFiltrados] y notifica al adaptador para refrescar la lista.
     *
     * Muestra u oculta el texto de «sin usuarios» según el resultado.
     */
    private fun aplicarFiltros() {
        val texto = FiltroContenido.normalizar(textoBusqueda)
        val filtrados = usuariosBase.filter { usuario ->
            val rolNormalizado = usuario.rol.lowercase()
            val cumpleRol = when (filtroRol) {
                FILTRO_VENDEDORES -> rolNormalizado == Constantes.ROL_VENDEDOR
                FILTRO_COMPRADORES -> rolNormalizado == Constantes.ROL_COMPRADOR
                else -> true
            }
            val nombre = FiltroContenido.normalizar(usuario.nombre)
            val correo = FiltroContenido.normalizar(usuario.correo)
            val cumpleTexto = texto.isBlank() || nombre.contains(texto) || correo.contains(texto)
            cumpleRol && cumpleTexto
        }

        usuariosFiltrados.clear()
        usuariosFiltrados.addAll(filtrados)
        adaptador.notifyDataSetChanged()
        textoSinUsuarios.visibility = if (usuariosFiltrados.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    /**
     * Configura un panel desplegable que alterna su visibilidad al pulsar el encabezado.
     *
     * Rota el icono indicador 180 grados cuando el panel está expandido.
     *
     * @param encabezado Vista clicable que actúa como título del desplegable.
     * @param contenido Vista cuyo visibility se alterna entre [android.view.View.VISIBLE] y [android.view.View.GONE].
     * @param icono Icono que rota para indicar el estado de expansión.
     * @param expandidoInicial Estado inicial del desplegable (`true` = expandido).
     */
    private fun configurarDesplegable(
        encabezado: android.view.View,
        contenido: android.view.View,
        icono: ImageView,
        expandidoInicial: Boolean,
    ) {
        var expandido = expandidoInicial
        fun aplicarEstado() {
            contenido.visibility = if (expandido) android.view.View.VISIBLE else android.view.View.GONE
            icono.rotation = if (expandido) 180f else 0f
        }
        aplicarEstado()
        encabezado.setOnClickListener {
            expandido = !expandido
            aplicarEstado()
        }
    }

    /**
     * Calcula la cantidad de días transcurridos desde el último inicio de sesión.
     *
     * @param ultimoLogin Marca de tiempo del último inicio de sesión del usuario.
     * @param ahora Marca de tiempo actual en milisegundos.
     * @return Número de días completos de inactividad.
     */
    private fun calcularDiasInactivo(ultimoLogin: Timestamp, ahora: Long): Int {
        val diferencia = ahora - ultimoLogin.toDate().time
        return TimeUnit.MILLISECONDS.toDays(diferencia).toInt()
    }

    /**
     * Determina el estado de actividad de un usuario a partir de sus días de inactividad.
     *
     * Un usuario se considera activo si su inactividad es menor o igual a [DIAS_ACTIVO].
     *
     * @param diasInactivo Días de inactividad, o `null` si no hay datos.
     * @return Cadena descriptiva: «Activo», «Inactivo» o «Sin datos».
     */
    private fun determinarEstado(diasInactivo: Int?): String {
        if (diasInactivo == null) return "Sin datos"
        return if (diasInactivo <= DIAS_ACTIVO) "Activo" else "Inactivo"
    }

    /**
     * Abre la actividad de visualización de stock de un vendedor específico.
     *
     * Solo permite acceder si el usuario seleccionado tiene rol de vendedor.
     *
     * @param usuario Usuario administrador del cual se desea consultar el stock.
     */
    private fun abrirStockVendedor(usuario: UsuarioAdmin) {
        if (usuario.rol.lowercase() != Constantes.ROL_VENDEDOR) {
            mostrarMensaje("Solo los vendedores tienen stock")
            return
        }
        val intent = Intent(this, StockVendedorAdminActivity::class.java).apply {
            putExtra(StockVendedorAdminActivity.EXTRA_USUARIO_ID, usuario.uid)
            putExtra(StockVendedorAdminActivity.EXTRA_NOMBRE, usuario.nombre)
            putExtra(StockVendedorAdminActivity.EXTRA_CORREO, usuario.correo)
            putExtra(StockVendedorAdminActivity.EXTRA_NOMBRE_ALMACEN, usuario.nombreAlmacen.orEmpty())
        }
        startActivity(intent)
    }

    /**
     * Abre el diálogo de gestión de plan para un usuario vendedor.
     *
     * @param usuario Usuario al que se le gestionará el plan.
     */
    private fun abrirGestionarPlan(usuario: UsuarioAdmin) {
        val dialogo = GestionarPlanDialogFragment.newInstance(
            usuarioId = usuario.uid,
            usuarioNombre = usuario.nombre,
            usuarioCorreo = usuario.correo,
        )
        dialogo.show(supportFragmentManager, GestionarPlanDialogFragment.TAG)
    }

    /**
     * Abre el diálogo de confirmación para promover un usuario al rol de administrador.
     *
     * Si el usuario ya es administrador, muestra un mensaje informativo y no abre el diálogo.
     *
     * @param usuario Usuario que se desea promover a administrador.
     */
    private fun abrirConfirmarRol(usuario: UsuarioAdmin) {
        if (usuario.rol.lowercase() == AdminViewModel.ROL_ADMINISTRADOR) {
            Toast.makeText(this, "Este usuario ya es administrador", Toast.LENGTH_SHORT).show()
            return
        }
        
        val dialogo = ConfirmarRolDialogFragment.newInstance(
            usuarioId = usuario.uid,
            usuarioNombre = usuario.nombre,
            usuarioCorreo = usuario.correo,
            usuarioRolActual = usuario.rol,
        )
        dialogo.show(supportFragmentManager, ConfirmarRolDialogFragment.TAG)
    }

    /**
     * Muestra un diálogo de confirmación para bloquear o desbloquear un usuario.
     *
     * Si el usuario ya está bloqueado, ofrece desbloquearlo directamente.
     * Si no está bloqueado, solicita el número de días de bloqueo; cero indica
     * bloqueo indefinido. Impide que el administrador se bloquee a sí mismo.
     *
     * @param usuario Usuario que se desea bloquear o desbloquear.
     */
    private fun confirmarBloquear(usuario: UsuarioAdmin) {
        val actual = autenticacion.currentUser?.uid
        if (usuario.uid == actual) {
            mostrarMensaje("No puedes bloquearte a ti mismo")
            return
        }
        
        if (usuario.bloqueado) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Desbloquear usuario")
                .setMessage("¿Deseas desbloquear la cuenta de ${usuario.nombre}? Podrá volver a acceder a la aplicación.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Desbloquear") { _, _ ->
                    lifecycleScope.launch { desbloquearUsuario(usuario) }
                }
                .show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("Bloquear usuario")
                .setMessage("¿Deseas bloquear la cuenta de ${usuario.nombre}? Ingresa el número de días que estará bloqueado. Cero días para bloqueo indefinido.")
                .setView(inputDiasBloqueo)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Bloquear") { _, _ ->
                    val diasStr = inputDiasBloqueo.text.toString()
                    val dias = diasStr.toIntOrNull()
                    if (dias == null || dias < 0) {
                        mostrarMensaje("Por favor, ingresa un número de días válido.")
                        return@setPositiveButton
                    }
                    lifecycleScope.launch {
                        val fechaDesbloqueo = if (dias > 0) {
                            val calendar = Calendar.getInstance()
                            calendar.add(Calendar.DAY_OF_YEAR, dias)
                            Timestamp(calendar.time)
                        } else {
                            null
                        }
                        bloquearUsuario(usuario, fechaDesbloqueo)
                    }
                }
                .show()
        }
    }

    /**
     * Desbloquea al usuario en Firestore eliminando el estado de bloqueo
     * y la fecha de desbloqueo, y recarga la lista de usuarios.
     *
     * @param usuario Usuario que será desbloqueado.
     */
    private suspend fun desbloquearUsuario(usuario: UsuarioAdmin) {
        try {
            baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(usuario.uid)
                .set(mapOf("bloqueado" to false, "fechaDesbloqueo" to null), com.google.firebase.firestore.SetOptions.merge())
                .await()
            mostrarMensaje("Usuario desbloqueado")
            cargarUsuarios()
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo desbloquear el usuario")
        }
    }

    /**
     * Bloquea al usuario en Firestore estableciendo el estado de bloqueo
     * y, opcionalmente, una fecha de desbloqueo automática.
     *
     * @param usuario Usuario que será bloqueado.
     * @param fechaDesbloqueo Fecha programada para el desbloqueo automático,
     *                        o `null` para bloqueo indefinido.
     */
    private suspend fun bloquearUsuario(usuario: UsuarioAdmin, fechaDesbloqueo: Timestamp? = null) {
        try {
            val datos = mutableMapOf<String, Any>("bloqueado" to true)
            if (fechaDesbloqueo != null) {
                datos["fechaDesbloqueo"] = fechaDesbloqueo
            }
            baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(usuario.uid)
                .set(datos, com.google.firebase.firestore.SetOptions.merge())
                .await()
            mostrarMensaje("Usuario bloqueado")
            cargarUsuarios()
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo bloquear el usuario")
        }
    }

    /**
     * Muestra un diálogo de confirmación para eliminar un usuario.
     *
     * Impide la eliminación del propio usuario autenticado y de otros administradores.
     *
     * @param usuario Usuario que se desea eliminar.
     */
    private fun confirmarEliminar(usuario: UsuarioAdmin) {
        val actual = autenticacion.currentUser?.uid
        if (usuario.uid == actual) {
            mostrarMensaje("No puedes eliminar tu propio usuario")
            return
        }
        if (usuario.rol.lowercase() == Constantes.ROL_ADMINISTRADOR) {
            mostrarMensaje("No puedes eliminar administradores")
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminar usuario")
            .setMessage("¿Deseas eliminar a ${usuario.nombre}?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch { eliminarUsuario(usuario) }
            }
            .show()
    }


    /**
     * Elimina al usuario de Firestore junto con su inventario privado y
     * las entradas correspondientes en el inventario público.
     *
     * @param usuario Usuario que será eliminado del sistema.
     */
    private suspend fun eliminarUsuario(usuario: UsuarioAdmin) {
        try {
            eliminarInventario(usuario.uid)
            eliminarInventarioPublico(usuario.uid)
            baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(usuario.uid)
                .delete()
                .await()
            mostrarMensaje("Usuario eliminado")
            cargarUsuarios()
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo eliminar el usuario")
        }
    }

    /**
     * Elimina todos los documentos de la subcolección «Inventario» de un usuario
     * utilizando una operación por lotes de Firestore.
     *
     * @param uid Identificador único del usuario cuyo inventario se eliminará.
     */
    private suspend fun eliminarInventario(uid: String) {
        val inventario = baseDatos.collection(Constantes.COLECCION_USUARIOS)
            .document(uid)
            .collection("Inventario")
            .get()
            .await()
        if (inventario.isEmpty) return
        val lote = baseDatos.batch()
        inventario.documents.forEach { documento ->
            lote.delete(documento.reference)
        }
        lote.commit().await()
    }

    /**
     * Elimina todos los documentos del inventario público asociados a un vendedor
     * utilizando una operación por lotes de Firestore.
     *
     * @param uid Identificador único del vendedor cuyo inventario público se eliminará.
     */
    private suspend fun eliminarInventarioPublico(uid: String) {
        val documentos = baseDatos.collection(Constantes.COLECCION_INVENTARIO_PUBLICO)
            .whereEqualTo("vendedorId", uid)
            .get()
            .await()
        if (documentos.isEmpty) return
        val lote = baseDatos.batch()
        documentos.documents.forEach { documento ->
            lote.delete(documento.reference)
        }
        lote.commit().await()
    }

    /**
     * Muestra un mensaje breve al usuario mediante un [android.widget.Toast].
     *
     * @param mensaje Texto que se desplegará en pantalla.
     */
    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }

    /**
     * Modelo de datos que representa a un usuario dentro del panel de administración.
     *
     * @property uid Identificador único del usuario en Firebase Authentication.
     * @property nombre Nombre mostrado del usuario.
     * @property correo Correo electrónico registrado.
     * @property rol Rol asignado al usuario (comprador, vendedor o administrador).
     * @property nombreAlmacen Nombre del almacén, aplicable solo a vendedores.
     * @property diasInactivo Días transcurridos desde el último inicio de sesión, o `null`.
     * @property estado Descripción legible del estado de actividad (Activo, Inactivo, Sin datos).
     * @property fechaCreacion Marca de tiempo de la creación de la cuenta en Firestore.
     * @property bloqueado Indica si la cuenta del usuario está bloqueada.
     */
    data class UsuarioAdmin(
        val uid: String,
        val nombre: String,
        val correo: String,
        val rol: String,
        val nombreAlmacen: String?,
        val diasInactivo: Int?,
        val estado: String,
        val fechaCreacion: Timestamp?,
        val bloqueado: Boolean = false,
    )

    /** Constantes utilizadas para los filtros y la determinación de estado de actividad. */
    companion object {
        /** Umbral máximo de días de inactividad para considerar a un usuario como activo. */
        private const val DIAS_ACTIVO = 30
        /** Opción de filtro que incluye todos los roles. */
        private const val FILTRO_TODOS = "Todos"
        /** Opción de filtro que incluye solo vendedores. */
        private const val FILTRO_VENDEDORES = "Vendedores"
        /** Opción de filtro que incluye solo compradores. */
        private const val FILTRO_COMPRADORES = "Compradores"
    }
}

/**
 * Adaptador del [RecyclerView] que renderiza la lista de usuarios en el panel de administración.
 *
 * Muestra información de cada usuario (nombre, correo, rol, estado, inactividad, fecha de creación)
 * y proporciona botones de acción para ver stock, bloquear, eliminar, gestionar plan y promover
 * a administrador. La visibilidad de los botones depende de si el administrador actual
 * es super administrador o visualizador.
 *
 * @param usuarios Lista de usuarios filtrados que se mostrarán en el adaptador.
 * @param onVerStock Acción ejecutada al pulsar «Ver stock» de un vendedor.
 * @param onBloquear Acción ejecutada al pulsar «Bloquear» o «Desbloquear».
 * @param onEliminar Acción ejecutada al pulsar «Eliminar».
 * @param onGestionarPlan Acción ejecutada al pulsar «Gestionar plan».
 * @param onHacerAdmin Acción ejecutada al pulsar «Hacer admin».
 * @param isSuperAdmin Indica si el administrador actual tiene permisos de super administrador.
 * @param esAdminVisualizador Indica si el administrador actual es de solo visualización.
 */
private class AdaptadorUsuariosAdmin(
    private val usuarios: List<AdminActivity.UsuarioAdmin>,
    private val onVerStock: (AdminActivity.UsuarioAdmin) -> Unit,
    private val onBloquear: (AdminActivity.UsuarioAdmin) -> Unit,
    private val onEliminar: (AdminActivity.UsuarioAdmin) -> Unit,
    private val onGestionarPlan: (AdminActivity.UsuarioAdmin) -> Unit,
    private val onHacerAdmin: (AdminActivity.UsuarioAdmin) -> Unit,
    private val isSuperAdmin: Boolean,
    private val esAdminVisualizador: Boolean,
) : RecyclerView.Adapter<AdaptadorUsuariosAdmin.VistaUsuario>() {

    /**
     * Contenedor de vistas para cada elemento de la lista de usuarios.
     *
     * Mantiene referencias a todos los elementos visuales de [R.layout.item_usuario_admin]
     * para evitar búsquedas repetidas durante el desplazamiento.
     *
     * @param itemView Vista raíz del elemento de la lista.
     */
    class VistaUsuario(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        /** Etiqueta con el nombre del usuario. */
        val textoNombre: android.widget.TextView = itemView.findViewById(R.id.texto_nombre_usuario)
        /** Etiqueta con el correo del usuario. */
        val textoCorreo: android.widget.TextView = itemView.findViewById(R.id.texto_correo_usuario)
        /** Etiqueta con el rol asignado al usuario. */
        val textoRol: android.widget.TextView = itemView.findViewById(R.id.texto_rol_usuario)
        /** Etiqueta con el estado de actividad del usuario. */
        val textoEstado: android.widget.TextView = itemView.findViewById(R.id.texto_estado_usuario)
        /** Etiqueta con los días de inactividad del usuario. */
        val textoInactividad: android.widget.TextView = itemView.findViewById(R.id.texto_inactividad_usuario)
        /** Etiqueta con la fecha de creación de la cuenta. */
        val textoCreacion: android.widget.TextView = itemView.findViewById(R.id.texto_creacion_usuario)
        /** Botón para gestionar el plan del usuario vendedor. */
        val botonGestionarPlan: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.boton_gestionar_plan)
        /** Botón para ver el stock del vendedor. */
        val botonVerStock: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.boton_ver_stock_admin)
        /** Botón para bloquear o desbloquear al usuario. */
        val botonBloquear: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.boton_bloquear_usuario)
        /** Botón para eliminar al usuario. */
        val botonEliminar: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.boton_eliminar_usuario)
        /** Botón para promover al usuario a administrador (solo super admin). */
        val botonHacerAdmin: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.boton_hacer_admin)
    }

    /**
     * Crea una nueva vista de elemento inflando el diseño [R.layout.item_usuario_admin].
     *
     * @param parent Grupo de vistas padre donde se insertará la nueva vista.
     * @param viewType Tipo de vista (no utilizado en este adaptador).
     * @return Nueva instancia de [VistaUsuario] enlazada al diseño inflado.
     */
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaUsuario {
        val vista = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_usuario_admin, parent, false)
        return VistaUsuario(vista)
    }

    /**
     * Vincula los datos de un usuario con los elementos visuales de la vista.
     *
     * Configura los textos de nombre, correo, rol, estado, inactividad y fecha de creación.
     * Ajusta la visibilidad de los botones de acción según el rol del usuario y los permisos
     * del administrador actual. Asigna los listeners de clic a cada botón.
     *
     * @param holder Vista del elemento que se va a vincular.
     * @param position Posición del elemento dentro de la lista.
     */
    override fun onBindViewHolder(holder: VistaUsuario, position: Int) {
        val usuario = usuarios[position]
        holder.textoNombre.text = usuario.nombre.ifBlank { "Sin nombre" }
        holder.textoCorreo.text = usuario.correo.ifBlank { "Sin correo" }
        holder.textoRol.text = "Rol: ${usuario.rol}"
        holder.textoEstado.text = "Estado: ${usuario.estado}"

        val dias = usuario.diasInactivo
        holder.textoInactividad.text = if (dias != null) {
            "Inactivo: ${dias} días"
        } else {
            "Inactivo: sin datos"
        }

        val fecha = usuario.fechaCreacion?.toDate()
        holder.textoCreacion.text = if (fecha != null) {
            val formato = SimpleDateFormat("dd/MM/yyyy", Locale.forLanguageTag("es-CL"))
            "Creado: ${formato.format(fecha)}"
        } else {
            "Creado: sin datos"
        }

        val esVendedor = usuario.rol.lowercase() == "vendedor"
        val esAdministrador = usuario.rol.lowercase() == "administrador"
        
        holder.botonVerStock.visibility = if (esVendedor && !esAdminVisualizador) android.view.View.VISIBLE else android.view.View.GONE
        holder.botonGestionarPlan.visibility = if (esVendedor && !esAdminVisualizador) android.view.View.VISIBLE else android.view.View.GONE
        holder.botonBloquear.visibility = if (!esAdminVisualizador) android.view.View.VISIBLE else android.view.View.GONE
        holder.botonEliminar.visibility = if (!esAdminVisualizador) android.view.View.VISIBLE else android.view.View.GONE
        
        holder.botonHacerAdmin.visibility = if (isSuperAdmin && !esAdministrador && !esAdminVisualizador) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        
        holder.botonBloquear.text = if (usuario.bloqueado) "Desbloquear" else "Bloquear"
        
        if (!esAdminVisualizador) {
            holder.botonVerStock.setOnClickListener { onVerStock(usuario) }
            holder.botonBloquear.setOnClickListener { onBloquear(usuario) }
            holder.botonEliminar.setOnClickListener { onEliminar(usuario) }
            holder.botonGestionarPlan.setOnClickListener { onGestionarPlan(usuario) }
            holder.botonHacerAdmin.setOnClickListener { onHacerAdmin(usuario) }
        }
    }

    /**
     * @return Cantidad total de elementos que muestra el adaptador.
     */
    override fun getItemCount(): Int = usuarios.size
}
