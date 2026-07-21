package com.example.rutaalmacen

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.rutaalmacen.notas.NotasActivity
import com.example.rutaalmacen.pagos.CodigoPlan
import com.example.rutaalmacen.pagos.EstadoSuscripcion
import com.example.rutaalmacen.pagos.PlanManager
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Fragmento principal que muestra la pantalla de inicio del vendedor.
 *
 * Presenta un resumen del estado del almacén, incluyendo el saludo personalizado,
 * la foto de perfil, el plan de suscripción actual con su barra de progreso de productos,
 * accesos rápidos a funciones frecuentes y un banner publicitario de AdMob.
 *
 * Los datos del usuario y del plan se cargan de forma asíncrona desde Firebase
 * (Authentication, Firestore) y se reflejan en la interfaz mediante el ciclo de vida
 * del fragmento.
 */
class InicioFragment : Fragment(R.layout.fragment_inicio) {

    /** Instancia de Firebase Authentication obtenida de forma perezosa. */
    private val autenticacion: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    /** Instancia de Firestore obtenida de forma perezosa para lecturas remotas. */
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    /** Administrador del plan de suscripción del vendedor. */
    private val planManager = PlanManager()

    private lateinit var imagenPerfil: ShapeableImageView
    private lateinit var textoSaludo: TextView
    private lateinit var textoNombreUsuario: TextView
    private lateinit var iconoNotificaciones: ImageView
    private lateinit var tarjetaEstadoAlmacen: MaterialCardView
    private lateinit var iconoPlan: ImageView
    private lateinit var textoTituloPlan: TextView
    private lateinit var textoNombrePlan: TextView
    private lateinit var textoProductosActivos: TextView
    private lateinit var textoPorcentaje: TextView
    private lateinit var barraProgresoProductos: LinearProgressIndicator
    private lateinit var tarjetaAnuncio: MaterialCardView
    private lateinit var adView: AdView
    private lateinit var tarjetaNuevaVenta: MaterialCardView
    private lateinit var tarjetaBlocNotas: MaterialCardView

    /** Objeto compañero que define la etiqueta de registro del fragmento. */
    companion object {
        private const val TAG = "InicioFragment"
    }

    /**
     * Inicializa las vistas del fragmento y dispara la carga de datos.
     *
     * Vincula cada referencia de vista con su identificador de diseño, establece valores
     * por defecto visibles inmediatamente y lanza las rutinas de carga de foto de perfil,
     * datos del usuario, configuración de accesos rápidos e inicialización de AdMob.
     *
     * @param view Vista raíz inflada del fragmento.
     * @param savedInstanceState Estado guardado previamente, o `null` si es un inicio nuevo.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        imagenPerfil = view.findViewById(R.id.imagen_perfil)
        textoSaludo = view.findViewById(R.id.texto_saludo)
        textoNombreUsuario = view.findViewById(R.id.texto_nombre_usuario)
        iconoNotificaciones = view.findViewById(R.id.icono_notificaciones)
        tarjetaEstadoAlmacen = view.findViewById(R.id.tarjeta_estado_almacen)
        iconoPlan = view.findViewById(R.id.icono_plan)
        textoTituloPlan = view.findViewById(R.id.texto_titulo_plan)
        textoNombrePlan = view.findViewById(R.id.texto_nombre_plan)
        textoProductosActivos = view.findViewById(R.id.texto_productos_activos)
        textoPorcentaje = view.findViewById(R.id.texto_porcentaje)
        barraProgresoProductos = view.findViewById(R.id.barra_progreso_productos)
        tarjetaAnuncio = view.findViewById(R.id.tarjeta_anuncio)
        adView = view.findViewById(R.id.ad_view_inicio)
        tarjetaNuevaVenta = view.findViewById(R.id.tarjeta_nueva_venta)
        tarjetaBlocNotas = view.findViewById(R.id.tarjeta_bloc_notas)

        Log.d(TAG, "onViewCreated: vistas inicializadas")

        // Valores por defecto visibles inmediatamente
        textoSaludo.text = "¡Hola,"
        textoNombreUsuario.text = "Cargando..."
        imagenPerfil.setImageResource(android.R.drawable.sym_def_app_icon)

        configurarAccesosRapidos()
        cargarDatosUsuario()
        cargarFotoPerfil()
        inicializarAdMob()
    }

    /**
     * Reanuda la reproducción del anuncio publicitario al volver al fragmento.
     */
    override fun onResume() {
        super.onResume()
        adView.resume()
    }

    /**
     * Pausa la reproducción del anuncio publicitario al salir del fragmento.
     */
    override fun onPause() {
        adView.pause()
        super.onPause()
    }

    /**
     * Destruye la vista del anuncio publicitario para liberar recursos.
     */
    override fun onDestroyView() {
        adView.destroy()
        super.onDestroyView()
    }

    /**
     * Carga la foto de perfil del usuario desde Firestore y la muestra con Glide.
     *
     * Consulta el campo `fotoUrl` del documento del usuario autenticado. Si existe una URL
     * válida, la descarga y la presenta recortada en forma circular. Si no hay URL o ocurre
     * un error, se mantiene el ícono por defecto.
     *
     * @throws Exception Si la consulta a Firestore falla; el error se registra en el log.
     */
    private fun cargarFotoPerfil() {
        val usuario = autenticacion.currentUser ?: return
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val documento = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                    .document(usuario.uid)
                    .get()
                    .await()
                
                val fotoUrl = documento.getString("fotoUrl").orEmpty()
                
                if (fotoUrl.isNotBlank()) {
                    Glide.with(this@InicioFragment)
                        .load(fotoUrl)
                        .placeholder(android.R.drawable.sym_def_app_icon)
                        .error(android.R.drawable.sym_def_app_icon)
                        .circleCrop()
                        .into(imagenPerfil)
                    
                    Log.d(TAG, "✓ Foto de perfil cargada desde Firestore")
                } else {
                    Log.d(TAG, "No hay foto de perfil guardada")
                }
            } catch (excepcion: Exception) {
                Log.e(TAG, "Error al cargar foto de perfil", excepcion)
            }
        }
    }

    /**
     * Configura los listeners de los accesos rápidos del panel de inicio.
     *
     * La tarjeta «Nueva venta» navega a la pestaña de productos de [VendedorActivity],
     * y la tarjeta «Bloc de notas» abre [NotasActivity].
     */
    private fun configurarAccesosRapidos() {
        tarjetaNuevaVenta.setOnClickListener {
            (activity as? VendedorActivity)?.seleccionarTab(R.id.nav_productos)
        }
        tarjetaBlocNotas.setOnClickListener {
            startActivity(Intent(requireContext(), NotasActivity::class.java))
        }
    }

    /**
     * Carga los datos del usuario autenticado desde Firestore y actualiza la interfaz.
     *
     * Obtiene el nombre del usuario (o el prefijo del correo si no hay nombre) y lo muestra
     * en el saludo. Además, carga el estado actual de la suscripción y actualiza la
     * información del plan en pantalla.
     *
     * @throws Exception Si la consulta a Firestore falla; se muestran valores por defecto
     *   de error en la interfaz.
     */
    private fun cargarDatosUsuario() {
        viewLifecycleOwner.lifecycleScope.launch {
            val usuario = autenticacion.currentUser
            if (usuario == null) {
                textoNombreUsuario.text = "Usuario"
                return@launch
            }

            try {
                val documento = baseDatos.collection(Constantes.COLECCION_USUARIOS)
                    .document(usuario.uid)
                    .get()
                    .await()

                val nombre = documento.getString("nombre").orEmpty().ifBlank {
                    documento.getString("correo").orEmpty().substringBefore("@")
                }
                textoSaludo.text = "¡Hola,"
                textoNombreUsuario.text = "$nombre!"

                Log.d(TAG, "Nombre cargado: $nombre")

                val estadoSuscripcion = planManager.cargarEstadoActual(requireContext())
                actualizarInformacionPlan(estadoSuscripcion)
            } catch (excepcion: Exception) {
                Log.e(TAG, "Error al cargar datos", excepcion)
                textoNombrePlan.text = "Plan: Error"
                textoProductosActivos.text = "0 / 0 productos"
                textoPorcentaje.text = "0%"
                barraProgresoProductos.progress = 0
            }
        }
    }

    /**
     * Actualiza la interfaz con la información del plan de suscripción vigente.
     *
     * Muestra el nombre del plan, la cantidad de productos activos frente al máximo permitido,
     * el porcentaje de uso en la barra de progreso y, si el plan es gratuito, hace visible
     * la tarjeta de anuncio publicitario.
     *
     * @param estado Estado actual de la suscripción del vendedor.
     */
    private fun actualizarInformacionPlan(estado: EstadoSuscripcion) {
        val nombrePlan = estado.plan.nombre
        val productosActuales = estado.productosActuales
        val maxProductos = estado.plan.maxProductos

        textoNombrePlan.text = "Plan: $nombrePlan"
        textoProductosActivos.text = "$productosActuales / $maxProductos productos"

        val porcentaje = if (maxProductos > 0) {
            ((productosActuales.toFloat() / maxProductos.toFloat()) * 100).toInt()
        } else {
            0
        }
        textoPorcentaje.text = "$porcentaje%"
        barraProgresoProductos.progress = porcentaje.coerceIn(0, 100)

        Log.d(TAG, "Plan: $nombrePlan, Productos: $productosActuales/$maxProductos ($porcentaje%)")

        if (estado.plan.codigo == CodigoPlan.GRATIS) {
            tarjetaAnuncio.visibility = View.VISIBLE
        } else {
            tarjetaAnuncio.visibility = View.GONE
        }
    }

    /**
     * Inicializa el banner publicitario de AdMob con un listener de carga.
     *
     * Configura un [AdListener] para registrar eventos de carga exitosa o fallida,
     * y solicita un anuncio con una petición estándar.
     */
    private fun inicializarAdMob() {
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                Log.d(TAG, "Anuncio cargado")
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                Log.e(TAG, "Error anuncio: ${adError.message}")
            }
        }
        adView.loadAd(AdRequest.Builder().build())
    }
}
