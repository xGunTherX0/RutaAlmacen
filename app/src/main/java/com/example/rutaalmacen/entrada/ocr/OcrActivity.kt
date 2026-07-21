package com.example.rutaalmacen.entrada.ocr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import com.example.rutaalmacen.R
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Actividad principal para la captura de imágenes de boletas o listas de compras.
 *
 * Presenta una vista previa de la cámara trasera con controles para capturar
 * una fotografía o seleccionar una imagen desde la galería. La imagen capturada
 * se entrega al [OcrViewModel] para su análisis OCR mediante Gemini.
 *
 * Gestiona el permiso de cámara en tiempo de ejecución, el control del flash
 * (linterna) y la transición hacia [PrevisualizacionActivity] una vez que
 * se detectan productos.
 */
class OcrActivity : AppCompatActivity() {

    private val viewModel: OcrViewModel by viewModels()
    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private lateinit var previewView: PreviewView
    private lateinit var botonCapturar: MaterialButton
    private lateinit var textoEstado: TextView
    private lateinit var textoInstruccion: TextView
    private lateinit var progreso: ProgressBar
    private lateinit var botonCerrar: MaterialButton
    private lateinit var botonFlash: MaterialButton

    private var imageCapture: ImageCapture? = null
    private var camara: Camera? = null
    private var camaraIniciada = false
    private var flashEncendido = false

    /**
     * Registro de actividad para solicitar el permiso de cámara en tiempo de ejecución.
     * Si el permiso es concedido, inicia la cámara; en caso contrario, muestra un mensaje
     * y redirige a la galería como alternativa.
     */
    private val permisoCamara = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido ->
        if (concedido) {
            iniciarCamara()
        } else {
            mostrarMensaje("Se necesita permiso de cámara para escanear")
            abrirGaleria()
        }
    }

    /**
     * Registro de actividad para seleccionar una imagen desde la galería del dispositivo.
     * Si el usuario selecciona una imagen, la envía al ViewModel para su procesamiento;
     * si cancela, finaliza la actividad.
     */
    private val selectorGaleria = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            viewModel.procesarImagen(uri)
        } else {
            mostrarMensaje("No se eligió imagen")
            finish()
        }
    }

    /**
     * Ciclo de vida: inicializa la interfaz, vincula los observadores del ViewModel,
     * solicita el permiso de cámara y configura los listeners de los botones.
     *
     * @param savedInstanceState Estado guardado de la instancia anterior, o `null` si es nueva.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ocr)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_ocr)) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        previewView = findViewById(R.id.preview_camera)
        botonCapturar = findViewById(R.id.boton_capturar_ocr)
        textoEstado = findViewById(R.id.texto_estado_ocr)
        textoInstruccion = findViewById(R.id.texto_instruccion)
        progreso = findViewById(R.id.progreso_ocr)
        botonCerrar = findViewById(R.id.boton_cerrar_ocr)
        botonFlash = findViewById(R.id.boton_flash_ocr)

        botonCerrar.setOnClickListener { finish() }
        botonCapturar.setOnClickListener { capturar() }
        botonFlash.setOnClickListener { alternarFlash() }

        viewModel.estado.observe(this) { estado ->
            when (estado) {
                is OcrViewModel.EstadoOcr.Inactivo -> {
                    progreso.visibility = View.GONE
                    botonCapturar.isEnabled = true
                    textoEstado.text = "Toca el botón para capturar"
                }
                is OcrViewModel.EstadoOcr.Procesando -> {
                    progreso.visibility = View.VISIBLE
                    botonCapturar.isEnabled = false
                    textoEstado.text = estado.mensaje
                }
                is OcrViewModel.EstadoOcr.Listo -> {
                    progreso.visibility = View.GONE
                    botonCapturar.isEnabled = true
                    textoEstado.text = estado.mensaje
                    val productosActuales = viewModel.productosDetectados.value.orEmpty()
                    if (productosActuales.isNotEmpty()) {
                        val intent = Intent(this, PrevisualizacionActivity::class.java)
                        intent.putExtra(PrevisualizacionActivity.EXTRA_PRODUCTOS_JSON, com.google.gson.Gson().toJson(productosActuales))
                        intent.putExtra(PrevisualizacionActivity.EXTRA_TEXTO_CRUDO, viewModel.textoCrudoOcr.value.orEmpty())
                        startActivity(intent)
                        finish()
                    }
                }
                is OcrViewModel.EstadoOcr.Error -> {
                    progreso.visibility = View.GONE
                    botonCapturar.isEnabled = true
                    textoEstado.text = estado.mensaje
                    mostrarMensaje(estado.mensaje)
                }
            }
        }

        viewModel.productosDetectados.observe(this) { productos ->
            if (productos.isNotEmpty()) {
                textoInstruccion.text = "Toca el botón para capturar"
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            iniciarCamara()
        } else {
            permisoCamara.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Inicializa la cámara trasera con CameraX, configurando la vista previa
     * y el modo de captura de imagen con latencia minimizada.
     *
     * Si la cámara ya fue iniciada previamente, no realiza ninguna acción.
     * En caso de error, muestra un mensaje y redirige a la galería.
     */
    private fun iniciarCamara() {
        if (camaraIniciada) return
        val proveedorFuturo = ProcessCameraProvider.getInstance(this)
        proveedorFuturo.addListener({
            try {
                val proveedor = proveedorFuturo.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    .build()
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                proveedor.unbindAll()
                camara = proveedor.bindToLifecycle(this, selector, preview, imageCapture)
                camaraIniciada = true
                verificarDisponibilidadFlash()
            } catch (excepcion: Exception) {
                mostrarMensaje("No se pudo iniciar la cámara")
                abrirGaleria()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Verifica si el dispositivo posee unidad de flash y, en ese caso,
     * hace visible el botón de flash; de lo contrario, lo oculta.
     */
    private fun verificarDisponibilidadFlash() {
        val tieneFlash = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        botonFlash.visibility = if (tieneFlash && camara?.cameraInfo?.hasFlashUnit() == true) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    /**
     * Alterna el estado del flash (linterna) entre encendido y apagado.
     *
     * Activa tanto la linterna continua de la cámara como el modo de flash
     * para la captura de imagen. Si el dispositivo no posee unidad de flash,
     * muestra un mensaje informativo.
     */
    private fun alternarFlash() {
        val camaraActual = camara ?: return
        val info = camaraActual.cameraInfo
        if (!info.hasFlashUnit()) {
            mostrarMensaje("Este celular no tiene linterna")
            return
        }
        flashEncendido = !flashEncendido
        try {
            camaraActual.cameraControl.enableTorch(flashEncendido)
            imageCapture?.flashMode = if (flashEncendido) {
                ImageCapture.FLASH_MODE_ON
            } else {
                ImageCapture.FLASH_MODE_OFF
            }
            botonFlash.alpha = if (flashEncendido) 1.0f else 0.6f
            botonFlash.setIconResource(
                if (flashEncendido) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_menu_view
            )
        } catch (e: Exception) {
            mostrarMensaje("No se pudo controlar la linterna")
        }
    }

    /**
     * Captura una fotografía desde la cámara y la envía al ViewModel para su análisis.
     *
     * Crea un archivo temporal con marca de tiempo, configura la captura de imagen
     * y, al guardar exitosamente, entrega la URI resultante al [OcrViewModel].
     * Si la cámara no está disponible, redirige a la galería.
     */
    private fun capturar() {
        val captura = imageCapture
        if (captura == null) {
            mostrarMensaje("Cámara no lista. Probá con galería.")
            abrirGaleria()
            return
        }
        val archivo = crearArchivoDestino()
        val opciones = ImageCapture.OutputFileOptions.Builder(archivo).build()
        captura.takePicture(
            opciones,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(resultado: ImageCapture.OutputFileResults) {
                    val uri = Uri.fromFile(archivo)
                    viewModel.procesarImagen(uri)
                }
                override fun onError(excepcion: ImageCaptureException) {
                    mostrarMensaje("No se pudo capturar: ${excepcion.message ?: "?"}")
                }
            },
        )
    }

    /**
     * Crea un archivo JPEG temporal dentro del directorio privado «capturas»
     * para almacenar la imagen capturada por la cámara.
     *
     * @return Archivo de destino con nombre basado en la fecha y hora actuales.
     */
    private fun crearArchivoDestino(): File {
        val nombre = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val directorio = File(filesDir, "capturas").apply { if (!exists()) mkdirs() }
        return File(directorio, "boleta_$nombre.jpg")
    }

    /**
     * Abre el selector de galería del sistema para que el usuario elija una imagen.
     *
     * Si no se puede abrir la galería, muestra un mensaje y finaliza la actividad.
     */
    private fun abrirGaleria() {
        try {
            selectorGaleria.launch("image/*")
        } catch (_: Exception) {
            mostrarMensaje("No se pudo abrir la galería")
            finish()
        }
    }

    /**
     * Ciclo de vida: apaga el flash si estaba encendido para evitar que
     * la linterna quede activa al destruir la actividad.
     */
    override fun onDestroy() {
        super.onDestroy()
        if (flashEncendido) {
            try { camara?.cameraControl?.enableTorch(false) } catch (_: Exception) {}
        }
    }

    /**
     * Muestra un mensaje breve en pantalla mediante un [Toast].
     *
     * @param mensaje Texto a mostrar al usuario.
     */
    private fun mostrarMensaje(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }
}
