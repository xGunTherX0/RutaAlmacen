package com.example.rutaalmacen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SellerRegistrationActivity : AppCompatActivity() {

    private val autenticacion: FirebaseAuth by lazy { Firebase.auth }
    private val baseDatos by lazy { Firebase.firestore }
    private val almacenamiento by lazy { Firebase.storage }

    private lateinit var campoNombreAlmacen: TextInputLayout
    private lateinit var inputNombreAlmacen: TextInputEditText
    private lateinit var campoDireccion: TextInputLayout
    private lateinit var inputDireccion: TextInputEditText
    private lateinit var campoRut: TextInputLayout
    private lateinit var inputRut: TextInputEditText
    private lateinit var tarjetaFotoPatente: com.google.android.material.card.MaterialCardView
    private lateinit var contenedorPlaceholder: LinearLayout
    private lateinit var imagenPatenteVistaPrevia: ImageView
    private lateinit var botonEliminarFoto: ImageButton
    private lateinit var botonAdjuntarFoto: MaterialButton
    private lateinit var checkboxTerminos: MaterialSwitch
    private lateinit var botonEnviar: MaterialButton
    private lateinit var barraProgreso: ProgressBar

    private var uriPatente: Uri? = null
    private var uriFotoTemporal: Uri? = null
    private var uidUsuario: String = ""
    private var emailUsuario: String = ""
    private var nombreDisplay: String = ""
    private var fotoUrlUsuario: String = ""

    private lateinit var lanzadorGaleria: ActivityResultLauncher<Intent>
    private lateinit var lanzadorCamara: ActivityResultLauncher<Uri>
    private lateinit var lanzadorPermisos: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_seller_registration)

        uidUsuario = intent.getStringExtra("uid").orEmpty()
        emailUsuario = intent.getStringExtra("email").orEmpty()
        nombreDisplay = intent.getStringExtra("displayName").orEmpty()
        fotoUrlUsuario = intent.getStringExtra("fotoUrl").orEmpty()

        if (uidUsuario.isBlank()) {
            Toast.makeText(this, "Error: UID de usuario no disponible", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        inicializarVistas()
        inicializarLanzadores()
        configurarListeners()
    }

    private fun inicializarVistas() {
        campoNombreAlmacen = findViewById(R.id.campo_nombre_almacen)
        inputNombreAlmacen = findViewById(R.id.input_nombre_almacen)
        campoDireccion = findViewById(R.id.campo_direccion)
        inputDireccion = findViewById(R.id.input_direccion)
        campoRut = findViewById(R.id.campo_rut)
        inputRut = findViewById(R.id.input_rut)
        tarjetaFotoPatente = findViewById(R.id.tarjeta_foto_patente)
        contenedorPlaceholder = findViewById(R.id.contenedor_placeholder_patente)
        imagenPatenteVistaPrevia = findViewById(R.id.imagen_patente_vista_previa)
        botonEliminarFoto = findViewById(R.id.boton_eliminar_foto)
        botonAdjuntarFoto = findViewById(R.id.boton_adjuntar_foto)
        checkboxTerminos = findViewById(R.id.checkbox_terminos)
        botonEnviar = findViewById(R.id.boton_enviar_verificacion)
        barraProgreso = findViewById(R.id.barra_progreso)
    }

    private fun inicializarLanzadores() {
        lanzadorGaleria = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
            if (resultado.resultCode == RESULT_OK) {
                resultado.data?.data?.let { uri ->
                    uriPatente = uri
                    mostrarVistaPrevia(uri)
                }
            }
        }

        lanzadorCamara = registerForActivityResult(ActivityResultContracts.TakePicture()) { exito ->
            if (exito && uriFotoTemporal != null) {
                uriPatente = uriFotoTemporal
                mostrarVistaPrevia(uriFotoTemporal!!)
            }
        }

        lanzadorPermisos = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permisos ->
            val camaraConcedida = permisos[Manifest.permission.CAMERA] == true
            if (camaraConcedida) {
                abrirCamara()
            } else {
                Toast.makeText(this, "Permiso de cámara requerido", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun configurarListeners() {
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                botonEnviar.isEnabled = validarFormulario()
            }
        }

        inputNombreAlmacen.addTextChangedListener(textWatcher)
        inputDireccion.addTextChangedListener(textWatcher)
        inputRut.addTextChangedListener(textWatcher)

        checkboxTerminos.setOnCheckedChangeListener { _, _ ->
            botonEnviar.isEnabled = validarFormulario()
        }

        tarjetaFotoPatente.setOnClickListener {
            mostrarDialogoSeleccionarFoto()
        }

        botonAdjuntarFoto.setOnClickListener {
            mostrarDialogoSeleccionarFoto()
        }

        botonEliminarFoto.setOnClickListener {
            uriPatente = null
            contenedorPlaceholder.visibility = View.VISIBLE
            imagenPatenteVistaPrevia.visibility = View.GONE
            botonEliminarFoto.visibility = View.GONE
            botonEnviar.isEnabled = validarFormulario()
        }

        botonEnviar.setOnClickListener {
            enviarAVerificacion()
        }
    }

    private fun mostrarDialogoSeleccionarFoto() {
        val opciones = arrayOf("Tomar foto", "Elegir de galería")
        MaterialAlertDialogBuilder(this)
            .setTitle("Seleccionar patente")
            .setItems(opciones) { _, cual ->
                when (cual) {
                    0 -> verificarPermisoCamara()
                    1 -> abrirGaleria()
                }
            }
            .show()
    }

    private fun verificarPermisoCamara() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                abrirCamara()
            }
            else -> {
                lanzadorPermisos.launch(arrayOf(Manifest.permission.CAMERA))
            }
        }
    }

    private fun abrirCamara() {
        val archivoFoto = crearArchivoImagen()
        uriFotoTemporal = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            archivoFoto
        )
        lanzadorCamara.launch(uriFotoTemporal!!)
    }

    private fun crearArchivoImagen(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val directorio = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("PATENTE_${timeStamp}_", ".jpg", directorio)
    }

    private fun abrirGaleria() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        lanzadorGaleria.launch(intent)
    }

    private fun mostrarVistaPrevia(uri: Uri) {
        contenedorPlaceholder.visibility = View.GONE
        imagenPatenteVistaPrevia.visibility = View.VISIBLE
        botonEliminarFoto.visibility = View.VISIBLE
        imagenPatenteVistaPrevia.setImageURI(uri)
        botonEnviar.isEnabled = validarFormulario()
    }

    private fun validarFormulario(): Boolean {
        val nombreValido = inputNombreAlmacen.text?.isNotBlank() == true
        val direccionValida = inputDireccion.text?.isNotBlank() == true
        val rutValido = inputRut.text?.isNotBlank() == true
        val fotoValida = uriPatente != null
        val terminosAceptados = checkboxTerminos.isChecked

        campoNombreAlmacen.error = if (!nombreValido && inputNombreAlmacen.hasFocus().not()) "Requerido" else null
        campoDireccion.error = if (!direccionValida && inputDireccion.hasFocus().not()) "Requerido" else null
        campoRut.error = if (!rutValido && inputRut.hasFocus().not()) "Requerido" else null

        return nombreValido && direccionValida && rutValido && fotoValida && terminosAceptados
    }

    private fun enviarAVerificacion() {
        if (!validarFormulario()) {
            Toast.makeText(this, "Completa todos los campos y adjunta la patente", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = uriPatente ?: return

        mostrarCargando(true)

        lifecycleScope.launch {
            try {
                val urlPatente = subirPatenteStorage(uri)
                guardarPerfilVendedor(urlPatente)
                Toast.makeText(
                    this@SellerRegistrationActivity,
                    "Registro enviado. Tu patente está en proceso de verificación.",
                    Toast.LENGTH_LONG
                ).show()
                navegarADashboard()
            } catch (e: Exception) {
                Log.e("SellerRegistration", "Error al enviar: ${e.message}", e)
                Toast.makeText(
                    this@SellerRegistrationActivity,
                    "Error al enviar: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                mostrarCargando(false)
            }
        }
    }

    private suspend fun subirPatenteStorage(uri: Uri): String {
        val referencia = almacenamiento.reference.child("${Constantes.RutaPatentes}/${uidUsuario}_patente.jpg")
        referencia.putFile(uri).await()
        return referencia.downloadUrl.await().toString()
    }

    private suspend fun guardarPerfilVendedor(urlPatente: String) {
        val nombreUsuario = nombreDisplay.ifBlank {
            emailUsuario.substringBefore("@")
        }

        val sellerProfile = mapOf(
            "storeName" to inputNombreAlmacen.text.toString().trim(),
            "address" to inputDireccion.text.toString().trim(),
            "rut" to inputRut.text.toString().trim(),
            "patentImageUrl" to urlPatente,
            "verificationStatus" to Constantes.EstadoVerificacionPendiente,
            "createdAt" to FieldValue.serverTimestamp()
        )

        val datosUsuario = mutableMapOf<String, Any>(
            "uid" to uidUsuario,
            "email" to emailUsuario,
            "displayName" to nombreUsuario,
            "role" to Constantes.ROL_VENDEDOR,
            "sellerProfile" to sellerProfile,
            "fechaCreacion" to FieldValue.serverTimestamp(),
            "ultimoLogin" to FieldValue.serverTimestamp()
        )

        if (fotoUrlUsuario.isNotBlank()) {
            datosUsuario["fotoUrl"] = fotoUrlUsuario
        }

        baseDatos.collection(Constantes.COLECCION_USUARIOS)
            .document(uidUsuario)
            .set(datosUsuario, SetOptions.merge())
            .await()
    }

    private fun navegarADashboard() {
        startActivity(Intent(this, VendedorActivity::class.java))
        finishAffinity()
    }

    private fun mostrarCargando(cargando: Boolean) {
        barraProgreso.visibility = if (cargando) View.VISIBLE else View.GONE
        botonEnviar.isEnabled = !cargando && validarFormulario()
        botonAdjuntarFoto.isEnabled = !cargando
        inputNombreAlmacen.isEnabled = !cargando
        inputDireccion.isEnabled = !cargando
        inputRut.isEnabled = !cargando
        checkboxTerminos.isEnabled = !cargando
    }
}
