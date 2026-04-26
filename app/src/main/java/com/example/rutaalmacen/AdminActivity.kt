package com.example.rutaalmacen

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.Timestamp
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class AdminActivity : AppCompatActivity() {

    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val usuariosBase: MutableList<UsuarioAdmin> = mutableListOf()
    private val usuariosFiltrados: MutableList<UsuarioAdmin> = mutableListOf()
    private lateinit var adaptador: AdaptadorUsuariosAdmin
    private lateinit var textoSinUsuarios: android.widget.TextView
    private var filtroRol = FILTRO_TODOS
    private var textoBusqueda = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            onEliminar = { usuario -> confirmarEliminar(usuario) },
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

        lifecycleScope.launch { cargarUsuarios() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { cargarUsuarios() }
    }

    private suspend fun cargarUsuarios() {
        try {
            val resultado = baseDatos.collection(COLECCION_USUARIOS)
                .orderBy("rol", Query.Direction.ASCENDING)
                .get()
                .await()
            val ahora = System.currentTimeMillis()
            val nuevosUsuarios = resultado.documents.mapNotNull { documento ->
                val uid = documento.id
                val nombre = documento.getString("nombre").orEmpty()
                val correo = documento.getString("correo").orEmpty()
                val rol = documento.getString("rol").orEmpty()
                if (rol.lowercase() == ROL_ADMINISTRADOR) {
                    return@mapNotNull null
                }
                val nombreAlmacen = documento.getString("nombreAlmacen")
                val fechaCreacion = documento.getTimestamp("fechaCreacion")
                val ultimoLogin = documento.getTimestamp("ultimoLogin")
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
                )
            }

            usuariosBase.clear()
            usuariosBase.addAll(nuevosUsuarios)
            aplicarFiltros()
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudieron cargar los usuarios")
        }
    }

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

    private fun aplicarFiltros() {
        val texto = normalizarTexto(textoBusqueda)
        val filtrados = usuariosBase.filter { usuario ->
            val rolNormalizado = usuario.rol.lowercase()
            val cumpleRol = when (filtroRol) {
                FILTRO_VENDEDORES -> rolNormalizado == ROL_VENDEDOR
                FILTRO_COMPRADORES -> rolNormalizado == ROL_COMPRADOR
                else -> true
            }
            val nombre = normalizarTexto(usuario.nombre)
            val correo = normalizarTexto(usuario.correo)
            val cumpleTexto = texto.isBlank() || nombre.contains(texto) || correo.contains(texto)
            cumpleRol && cumpleTexto
        }

        usuariosFiltrados.clear()
        usuariosFiltrados.addAll(filtrados)
        adaptador.notifyDataSetChanged()
        textoSinUsuarios.visibility = if (usuariosFiltrados.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

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

    private fun calcularDiasInactivo(ultimoLogin: Timestamp, ahora: Long): Int {
        val diferencia = ahora - ultimoLogin.toDate().time
        return TimeUnit.MILLISECONDS.toDays(diferencia).toInt()
    }

    private fun determinarEstado(diasInactivo: Int?): String {
        if (diasInactivo == null) return "Sin datos"
        return if (diasInactivo <= DIAS_ACTIVO) "Activo" else "Inactivo"
    }

    private fun abrirStockVendedor(usuario: UsuarioAdmin) {
        if (usuario.rol.lowercase() != ROL_VENDEDOR) {
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

    private fun confirmarEliminar(usuario: UsuarioAdmin) {
        val actual = autenticacion.currentUser?.uid
        if (usuario.uid == actual) {
            mostrarMensaje("No puedes eliminar tu propio usuario")
            return
        }
        if (usuario.rol.lowercase() == ROL_ADMINISTRADOR) {
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


    private suspend fun eliminarUsuario(usuario: UsuarioAdmin) {
        try {
            eliminarInventario(usuario.uid)
            eliminarInventarioPublico(usuario.uid)
            baseDatos.collection(COLECCION_USUARIOS)
                .document(usuario.uid)
                .delete()
                .await()
            mostrarMensaje("Usuario eliminado")
            cargarUsuarios()
        } catch (excepcion: Exception) {
            mostrarMensaje("No se pudo eliminar el usuario")
        }
    }

    private suspend fun eliminarInventario(uid: String) {
        val inventario = baseDatos.collection(COLECCION_USUARIOS)
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

    private suspend fun eliminarInventarioPublico(uid: String) {
        val documentos = baseDatos.collection(COLECCION_INVENTARIO_PUBLICO)
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

    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }

    private fun normalizarTexto(texto: String): String {
        val limpio = texto.trim().lowercase(Locale.getDefault())
        val normalizado = java.text.Normalizer.normalize(limpio, java.text.Normalizer.Form.NFD)
        return normalizado.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    data class UsuarioAdmin(
        val uid: String,
        val nombre: String,
        val correo: String,
        val rol: String,
        val nombreAlmacen: String?,
        val diasInactivo: Int?,
        val estado: String,
        val fechaCreacion: Timestamp?,
    )

    companion object {
        private const val COLECCION_USUARIOS = "Usuarios"
        private const val COLECCION_INVENTARIO_PUBLICO = "InventarioPublico"
        private const val ROL_ADMINISTRADOR = "administrador"
        private const val ROL_VENDEDOR = "vendedor"
        private const val ROL_COMPRADOR = "comprador"
        private const val DIAS_ACTIVO = 30
        private const val FILTRO_TODOS = "Todos"
        private const val FILTRO_VENDEDORES = "Vendedores"
        private const val FILTRO_COMPRADORES = "Compradores"
    }
}

private class AdaptadorUsuariosAdmin(
    private val usuarios: List<AdminActivity.UsuarioAdmin>,
    private val onVerStock: (AdminActivity.UsuarioAdmin) -> Unit,
    private val onEliminar: (AdminActivity.UsuarioAdmin) -> Unit,
) : RecyclerView.Adapter<AdaptadorUsuariosAdmin.VistaUsuario>() {

    class VistaUsuario(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val textoNombre: android.widget.TextView = itemView.findViewById(R.id.texto_nombre_usuario)
        val textoCorreo: android.widget.TextView = itemView.findViewById(R.id.texto_correo_usuario)
        val textoRol: android.widget.TextView = itemView.findViewById(R.id.texto_rol_usuario)
        val textoEstado: android.widget.TextView = itemView.findViewById(R.id.texto_estado_usuario)
        val textoInactividad: android.widget.TextView = itemView.findViewById(R.id.texto_inactividad_usuario)
        val textoCreacion: android.widget.TextView = itemView.findViewById(R.id.texto_creacion_usuario)
        val botonVerStock: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.boton_ver_stock_admin)
        val botonEliminar: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.boton_eliminar_usuario)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaUsuario {
        val vista = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_usuario_admin, parent, false)
        return VistaUsuario(vista)
    }

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
        holder.botonVerStock.isEnabled = esVendedor
        holder.botonVerStock.alpha = if (esVendedor) 1f else 0.5f
        holder.botonVerStock.setOnClickListener { onVerStock(usuario) }
        holder.botonEliminar.setOnClickListener { onEliminar(usuario) }
    }

    override fun getItemCount(): Int = usuarios.size
}
