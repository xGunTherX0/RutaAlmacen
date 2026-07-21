package com.example.rutaalmacen.admin

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rutaalmacen.Constantes
import com.example.rutaalmacen.FiltroContenido
import com.example.rutaalmacen.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Actividad que permite al superadministrador promover usuarios al rol de administrador
 * visualizador o quitarles dicho privilegio.
 *
 * <p>Carga la lista de usuarios desde Firestore, permite filtrarlos por nombre o correo,
 * y ofrece acciones de promoción y revocación. Al promover, el usuario obtiene el rol de
 * administrador con tipo «visualizador» (solo lectura). Al quitar el privilegio, el usuario
 * vuelve a su rol original.</p>
 */
class PromoverAdminActivity : AppCompatActivity() {

    /** Instancia de Firestore utilizada para las operaciones de lectura y escritura. */
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /** Lista mutable con todos los usuarios disponibles para promoción. */
    private val usuariosDisponibles: MutableList<UsuarioPromover> = mutableListOf()

    /** Lista mutable con los usuarios filtrados según el criterio de búsqueda actual. */
    private val usuariosFiltrados: MutableList<UsuarioPromover> = mutableListOf()

    /** Adaptador del [RecyclerView] que muestra los usuarios filtrados en pantalla. */
    private lateinit var adaptador: AdaptadorUsuariosPromover

    /** Texto informativo que se muestra cuando no hay usuarios disponibles. */
    private lateinit var textoSinUsuarios: TextView

    /**
     * Método del ciclo de vida llamado al crear la actividad.
     *
     * <p>Configura el diseño de borde a borde, inicializa el [RecyclerView] con su adaptador,
     * registra los listeners del campo de búsqueda y del botón de retroceso, y carga los
     * usuarios desde Firestore.</p>
     *
     * @param savedInstanceState Estado previamente guardado de la instancia, o {@code null}
     *                           si es la primera vez que se crea.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_promover_admin)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_promover_admin)) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        val recycler = findViewById<RecyclerView>(R.id.recycler_usuarios_promover)
        textoSinUsuarios = findViewById(R.id.texto_sin_usuarios_promover)
        val campoBusqueda = findViewById<TextInputEditText>(R.id.campo_busqueda_promover)

        adaptador = AdaptadorUsuariosPromover(
            usuarios = usuariosFiltrados,
            onPromover = { usuario -> confirmarPromocion(usuario) },
            onQuitarAdmin = { usuario -> confirmarQuitarAdmin(usuario) },
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adaptador

        findViewById<androidx.appcompat.widget.AppCompatImageButton>(R.id.boton_volver_promover).setOnClickListener {
            finish()
        }

        campoBusqueda.doOnTextChanged { texto, _, _, _ ->
            val busqueda = texto?.toString().orEmpty()
            aplicarFiltro(busqueda)
        }

        lifecycleScope.launch { cargarUsuarios() }
    }

    /**
     * Método del ciclo de vida llamado cuando la actividad vuelve a primer plano.
     * Recarga los usuarios desde Firestore para reflejar cambios recientes.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { cargarUsuarios() }
    }

    /**
     * Carga todos los usuarios desde Firestore, excluyendo a los administradores
     * que no sean de tipo «visualizador».
     *
     * <p>Ordena los resultados alfabéticamente por nombre y determina si cada usuario
     * es un administrador visualizador para mostrar las acciones apropiadas en la interfaz.
     * En caso de error, muestra un mensaje informativo al usuario.</p>
     */
    private suspend fun cargarUsuarios() {
        try {
            val resultado = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .orderBy("nombre", Query.Direction.ASCENDING)
                .get()
                .await()

            val usuarios = resultado.documents.mapNotNull { documento ->
                val rol = documento.getString("rol").orEmpty()
                val tipoAdmin = documento.getString("tipoAdmin").orEmpty()
                
                if (rol.lowercase() == Constantes.ROL_ADMINISTRADOR && tipoAdmin != "visualizador") {
                    return@mapNotNull null
                }
                
                val uid = documento.id
                val nombre = documento.getString("nombre").orEmpty()
                val correo = documento.getString("correo").orEmpty()
                val estado = documento.getString("estado") ?: "Activo"
                val rolOriginal = documento.getString("rolOriginal").orEmpty()
                val esAdminVisualizador = rol.lowercase() == AdminViewModel.ROL_ADMINISTRADOR && tipoAdmin == "visualizador"
                
                UsuarioPromover(uid, nombre, correo, rol, estado, esAdminVisualizador, rolOriginal)
            }

            usuariosDisponibles.clear()
            usuariosDisponibles.addAll(usuarios)
            aplicarFiltro("")
        } catch (excepcion: Exception) {
            Toast.makeText(this, "No se pudieron cargar los usuarios", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Aplica el filtro de búsqueda sobre la lista de usuarios disponibles.
     *
     * <p>Normaliza el texto de búsqueda y filtra los usuarios cuyo nombre o correo
     * normalizado contenga dicho texto. Si el campo de búsqueda está vacío, se muestran
     * todos los usuarios. Actualiza el adaptador y la visibilidad del texto informativo.</p>
     *
     * @param busqueda Texto ingresado por el usuario para filtrar la lista.
     */
    private fun aplicarFiltro(busqueda: String) {
        val texto = FiltroContenido.normalizar(busqueda)
        val filtrados = if (texto.isBlank()) {
            usuariosDisponibles.toList()
        } else {
            usuariosDisponibles.filter { usuario ->
                val nombre = FiltroContenido.normalizar(usuario.nombre)
                val correo = FiltroContenido.normalizar(usuario.correo)
                nombre.contains(texto) || correo.contains(texto)
            }
        }

        usuariosFiltrados.clear()
        usuariosFiltrados.addAll(filtrados)
        adaptador.notifyDataSetChanged()
        textoSinUsuarios.visibility = if (usuariosFiltrados.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Muestra un diálogo de confirmación antes de promover un usuario a administrador visualizador.
     *
     * <p>Informa al administrador que el usuario obtendrá permisos de solo visualización
     * (no podrá editar, bloquear ni eliminar usuarios).</p>
     *
     * @param usuario Instancia de [UsuarioPromover] que se desea promover.
     */
    private fun confirmarPromocion(usuario: UsuarioPromover) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Promover a Administrador")
            .setMessage("¿Estás seguro de promover a ${usuario.nombre} como Administrador?\n\nEste usuario tendrá acceso al panel de administración con permisos de solo visualización (no podrá editar, bloquear ni eliminar usuarios).")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Promover") { _, _ ->
                lifecycleScope.launch { promoverUsuario(usuario) }
            }
            .show()
    }

    /**
     * Promueve un usuario al rol de administrador visualizador en Firestore.
     *
     * <p>Actualiza el documento del usuario con el nuevo rol, tipo de administrador
     * y rol original. Crea además un registro en la subcolección «HistorialRoles» con
     * los detalles de la promoción. Recarga la lista de usuarios al finalizar.</p>
     *
     * @param usuario Instancia de [UsuarioPromover] que se desea promover.
     */
    private suspend fun promoverUsuario(usuario: UsuarioPromover) {
        try {
            val adminId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
            val ahora = System.currentTimeMillis()

            val lote = baseDatos.batch()

            val documentoUsuario = baseDatos.collection(Constantes.COLECCION_USUARIOS).document(usuario.uid)
            lote.set(
                documentoUsuario,
                mapOf(
                    "rol" to AdminViewModel.ROL_ADMINISTRADOR,
                    "tipoAdmin" to "visualizador",
                    "rolOriginal" to usuario.rol,
                    "fechaActualizacion" to ahora,
                ),
                SetOptions.merge(),
            )

            val documentoHistorial = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(usuario.uid)
                .collection("HistorialRoles")
                .document()

            lote.set(
                documentoHistorial,
                mapOf(
                    "fecha" to ahora,
                    "autorizadoPor" to adminId,
                    "nuevoRol" to AdminViewModel.ROL_ADMINISTRADOR,
                    "tipoAdmin" to "visualizador",
                    "estado" to "exitoso",
                    "rolAnterior" to usuario.rol,
                ),
            )

            lote.commit().await()

            Toast.makeText(this, "✓ ${usuario.nombre} promovido a Administrador", Toast.LENGTH_LONG).show()
            cargarUsuarios()
        } catch (excepcion: Exception) {
            Toast.makeText(this, "Error al promover usuario: ${excepcion.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Muestra un diálogo de confirmación antes de quitar el privilegio de administrador
     * a un usuario.
     *
     * <p>Informa al administrador que el usuario volverá a su rol original. Si el usuario
     * no tiene un rol original registrado, se usará «comprador» como valor por defecto.</p>
     *
     * @param usuario Instancia de [UsuarioPromover] a la que se desea quitar el privilegio.
     */
    private fun confirmarQuitarAdmin(usuario: UsuarioPromover) {
        val rolDestino = if (usuario.rolOriginal.isNotBlank()) usuario.rolOriginal else "comprador"
        MaterialAlertDialogBuilder(this)
            .setTitle("Quitar privilegio de Administrador")
            .setMessage("¿Estás seguro de quitar el privilegio de administrador a ${usuario.nombre}?\n\nEl usuario volverá a su rol original: $rolDestino")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Quitar privilegio") { _, _ ->
                lifecycleScope.launch { quitarPrivilegioAdmin(usuario) }
            }
            .show()
    }

    /**
     * Quita el privilegio de administrador visualizador a un usuario en Firestore.
     *
     * <p>Restablece el rol del usuario a su rol original (o «comprador» si no tiene uno),
     * elimina los campos de tipo de administrador y rol original. Crea además un registro
     * en la subcolección «HistorialRoles» con los detalles de la revocación.
     * Recarga la lista de usuarios al finalizar.</p>
     *
     * @param usuario Instancia de [UsuarioPromover] a la que se desea quitar el privilegio.
     */
    private suspend fun quitarPrivilegioAdmin(usuario: UsuarioPromover) {
        try {
            val adminId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
            val ahora = System.currentTimeMillis()
            val rolDestino = if (usuario.rolOriginal.isNotBlank()) usuario.rolOriginal else "comprador"

            val lote = baseDatos.batch()

            val documentoUsuario = baseDatos.collection(Constantes.COLECCION_USUARIOS).document(usuario.uid)
            lote.set(
                documentoUsuario,
                mapOf(
                    "rol" to rolDestino,
                    "tipoAdmin" to null,
                    "rolOriginal" to null,
                    "fechaActualizacion" to ahora,
                ),
                SetOptions.merge(),
            )

            val documentoHistorial = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                .document(usuario.uid)
                .collection("HistorialRoles")
                .document()

            lote.set(
                documentoHistorial,
                mapOf(
                    "fecha" to ahora,
                    "autorizadoPor" to adminId,
                    "nuevoRol" to rolDestino,
                    "tipoAdmin" to null,
                    "estado" to "privilegio_quitado",
                    "rolAnterior" to AdminViewModel.ROL_ADMINISTRADOR,
                ),
            )

            lote.commit().await()

            Toast.makeText(this, "✓ Privilegio de administrador quitado a ${usuario.nombre}", Toast.LENGTH_LONG).show()
            cargarUsuarios()
        } catch (excepcion: Exception) {
            Toast.makeText(this, "Error al quitar privilegio: ${excepcion.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Modelo de datos que representa un usuario candidato a ser promovido o al que
     * se le puede quitar el privilegio de administrador.
     *
     * @property uid Identificador único del usuario en Firestore.
     * @property nombre Nombre visible del usuario.
     * @property correo Correo electrónico del usuario.
     * @property rol Rol actual del usuario en el sistema.
     * @property estado Estado actual de la cuenta del usuario.
     * @property esAdminVisualizador Indica si el usuario ya es un administrador de tipo visualizador.
     * @property rolOriginal Rol que tenía el usuario antes de ser promovido a administrador.
     */
    data class UsuarioPromover(
        val uid: String,
        val nombre: String,
        val correo: String,
        val rol: String,
        val estado: String,
        val esAdminVisualizador: Boolean = false,
        val rolOriginal: String = "",
    )
}

/**
 * Adaptador del [RecyclerView] que muestra la lista de usuarios candidatos a promoción.
 *
 * <p>Cada elemento presenta el nombre, correo, rol y estado del usuario, junto con
 * botones para promoverlo a administrador o quitarle el privilegio según corresponda.
 * Las acciones se delegan a los callbacks proporcionados en el constructor.</p>
 *
 * @param usuarios Lista de usuarios candidatos a mostrar.
 * @param onPromover Callback invocado cuando el usuario solicita promover a un candidato.
 * @param onQuitarAdmin Callback invocado cuando el usuario solicita quitar el privilegio
 *                      de administrador a un candidato.
 */
private class AdaptadorUsuariosPromover(
    private val usuarios: List<PromoverAdminActivity.UsuarioPromover>,
    private val onPromover: (PromoverAdminActivity.UsuarioPromover) -> Unit,
    private val onQuitarAdmin: (PromoverAdminActivity.UsuarioPromover) -> Unit,
) : RecyclerView.Adapter<AdaptadorUsuariosPromover.VistaUsuario>() {

    /**
     * ViewHolder que contiene las referencias a las vistas de cada elemento de usuario.
     *
     * @param itemView Vista raíz del elemento de lista inflada desde el diseño XML.
     */
    class VistaUsuario(itemView: View) : RecyclerView.ViewHolder(itemView) {
        /** Texto que muestra el nombre del usuario. */
        val textoNombre: TextView = itemView.findViewById(R.id.texto_nombre_usuario_promover)
        /** Texto que muestra el correo del usuario. */
        val textoCorreo: TextView = itemView.findViewById(R.id.texto_correo_usuario_promover)
        /** Texto que muestra el rol actual del usuario. */
        val textoRol: TextView = itemView.findViewById(R.id.texto_rol_usuario_promover)
        /** Texto que muestra el estado de la cuenta del usuario. */
        val textoEstado: TextView = itemView.findViewById(R.id.texto_estado_usuario_promover)
        /** Botón para promover al usuario a administrador visualizador. */
        val botonPromover: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.boton_promover_usuario)
        /** Botón para quitar el privilegio de administrador al usuario. */
        val botonQuitarAdmin: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.boton_quitar_admin)
    }

    /**
     * Crea un nuevo [VistaUsuario] inflando el diseño del elemento de usuario.
     *
     * @param parent Grupo de vistas padre al que se adjuntará la nueva vista.
     * @param viewType Tipo de vista (no utilizado en este adaptador).
     * @return Nueva instancia de [VistaUsuario].
     */
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaUsuario {
        val vista = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_usuario_promover_admin, parent, false)
        return VistaUsuario(vista)
    }

    /**
     * Vincula los datos del usuario en la posición indicada con las vistas del ViewHolder.
     *
     * <p>Configura la visibilidad de los botones según si el usuario ya es administrador
     * visualizador o no, y ajusta los textos de rol y estado.</p>
     *
     * @param holder ViewHolder que contiene las vistas del elemento.
     * @param position Posición del elemento dentro de la lista.
     */
    override fun onBindViewHolder(holder: VistaUsuario, position: Int) {
        val usuario = usuarios[position]
        holder.textoNombre.text = usuario.nombre.ifBlank { "Sin nombre" }
        holder.textoCorreo.text = usuario.correo.ifBlank { "Sin correo" }
        
        if (usuario.esAdminVisualizador) {
            holder.textoRol.text = "Rol: administrador (visualizador)"
            holder.textoEstado.text = "Estado: ${usuario.estado}"
            holder.botonPromover.visibility = View.GONE
            holder.botonQuitarAdmin.visibility = View.VISIBLE
        } else {
            holder.textoRol.text = "Rol: ${usuario.rol}"
            holder.textoEstado.text = "Estado: ${usuario.estado}"
            holder.botonPromover.visibility = View.VISIBLE
            holder.botonQuitarAdmin.visibility = View.GONE
        }
        
        holder.botonPromover.setOnClickListener { onPromover(usuario) }
        holder.botonQuitarAdmin.setOnClickListener { onQuitarAdmin(usuario) }
    }

    /**
     * Retorna la cantidad total de elementos en la lista de usuarios filtrados.
     *
     * @return Número de elementos que el adaptador debe mostrar.
     */
    override fun getItemCount(): Int = usuarios.size
}
