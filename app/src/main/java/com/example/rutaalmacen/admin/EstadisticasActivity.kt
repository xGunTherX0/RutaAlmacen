package com.example.rutaalmacen.admin

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.rutaalmacen.R
import com.example.rutaalmacen.pagos.CodigoPlan
import com.example.rutaalmacen.pagos.Plan
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Actividad que muestra estadísticas resumidas sobre los usuarios y los planes de suscripción
 * de la aplicación.
 *
 * <p>Calcula y presenta la cantidad de usuarios por tipo de plan, el número de usuarios
 * que pagaron por su plan, los ingresos totales estimados, los usuarios con planes regalados
 * y el valor total de dichos planes. Los datos se obtienen desde la colección «Usuarios»
 * en Firestore.</p>
 */
class EstadisticasActivity : AppCompatActivity() {

    /** Instancia de Firestore utilizada para las operaciones de lectura. */
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /** Texto que muestra la cantidad de usuarios con plan gratuito. */
    private lateinit var textoUsuariosGratis: TextView
    /** Texto que muestra la cantidad de usuarios con plan vendedor. */
    private lateinit var textoUsuariosVendedor: TextView
    /** Texto que muestra la cantidad de usuarios con plan comercio. */
    private lateinit var textoUsuariosComercio: TextView
    /** Texto que muestra la cantidad de usuarios con plan empresarial. */
    private lateinit var textoUsuariosEmpresarial: TextView
    /** Texto que muestra la cantidad de usuarios que pagaron por su plan. */
    private lateinit var textoUsuariosPagaron: TextView
    /** Texto que muestra los ingresos totales estimados por planes pagados. */
    private lateinit var textoIngresosTotales: TextView
    /** Texto que muestra la cantidad de usuarios con planes regalados. */
    private lateinit var textoUsuariosRegalados: TextView
    /** Texto que muestra el valor total estimado de los planes regalados. */
    private lateinit var textoValorRegalado: TextView
    /** Botón para recargar las estadísticas desde Firestore. */
    private lateinit var botonActualizar: MaterialButton

    /**
     * Método del ciclo de vida llamado al crear la actividad.
     *
     * <p>Inicializa los componentes de la interfaz, registra el listener del botón
     * de actualización y carga las estadísticas iniciales.</p>
     *
     * @param savedInstanceState Estado previamente guardado de la instancia, o {@code null}
     *                           si es la primera vez que se crea.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_estadisticas)

        textoUsuariosGratis = findViewById(R.id.texto_usuarios_gratis)
        textoUsuariosVendedor = findViewById(R.id.texto_usuarios_vendedor)
        textoUsuariosComercio = findViewById(R.id.texto_usuarios_comercio)
        textoUsuariosEmpresarial = findViewById(R.id.texto_usuarios_empresarial)
        textoUsuariosPagaron = findViewById(R.id.texto_usuarios_pagaron)
        textoIngresosTotales = findViewById(R.id.texto_ingresos_totales)
        textoUsuariosRegalados = findViewById(R.id.texto_usuarios_regalados)
        textoValorRegalado = findViewById(R.id.texto_valor_regalado)
        botonActualizar = findViewById(R.id.boton_actualizar_estadisticas)

        botonActualizar.setOnClickListener {
            cargarEstadisticas()
        }

        cargarEstadisticas()
    }

    /**
     * Carga las estadísticas desde Firestore calculando los conteos de usuarios por plan,
     * los ingresos totales y el valor de los planes regalados.
     *
     * <p>Itera sobre todos los documentos de la colección «Usuarios», filtra solo los
     * vendedores y clasifica sus planes. Para determinar si un plan fue pagado o regalado,
     * consulta la subcolección «HistorialPrivilegios» de cada usuario: si existe historial,
     * se considera regalado; de lo contrario, pagado. Deshabilita el botón de actualización
     * mientras se procesan los datos.</p>
     */
    private fun cargarEstadisticas() {
        botonActualizar.isEnabled = false
        botonActualizar.text = "Cargando..."

        lifecycleScope.launch {
            try {
                val usuarios = baseDatos.collection("Usuarios").get().await()
                
                var countGratis = 0
                var countVendedor = 0
                var countComercio = 0
                var countEmpresarial = 0
                
                var usuariosPagaron = 0
                var usuariosRegalados = 0
                var ingresosTotales = 0L
                var valorRegalado = 0L

                for (documento in usuarios.documents) {
                    val rol = documento.getString("rol").orEmpty()
                    if (rol.lowercase() != "vendedor") continue
                    
                    val plan = documento.getString("plan") ?: CodigoPlan.GRATIS.id
                    
                    when (plan) {
                        CodigoPlan.GRATIS.id -> countGratis++
                        CodigoPlan.VENDEDOR.id -> countVendedor++
                        CodigoPlan.COMERCIO.id -> countComercio++
                        CodigoPlan.EMPRESARIAL.id -> countEmpresarial++
                    }

                    val tieneHistorial = verificarHistorialPrivilegios(documento.id)
                    
                    if (plan != CodigoPlan.GRATIS.id) {
                        if (tieneHistorial) {
                            usuariosRegalados++
                            valorRegalado += obtenerPrecioPlan(plan)
                        } else {
                            usuariosPagaron++
                            ingresosTotales += obtenerPrecioPlan(plan)
                        }
                    }
                }

                textoUsuariosGratis.text = countGratis.toString()
                textoUsuariosVendedor.text = countVendedor.toString()
                textoUsuariosComercio.text = countComercio.toString()
                textoUsuariosEmpresarial.text = countEmpresarial.toString()
                
                textoUsuariosPagaron.text = usuariosPagaron.toString()
                textoIngresosTotales.text = "$${formatearMiles(ingresosTotales)}"
                
                textoUsuariosRegalados.text = usuariosRegalados.toString()
                textoValorRegalado.text = "$${formatearMiles(valorRegalado)}"

                Toast.makeText(this@EstadisticasActivity, "Estadísticas actualizadas", Toast.LENGTH_SHORT).show()
            } catch (excepcion: Exception) {
                Toast.makeText(this@EstadisticasActivity, "Error al cargar estadísticas", Toast.LENGTH_SHORT).show()
            } finally {
                botonActualizar.isEnabled = true
                botonActualizar.text = "Actualizar Estadísticas"
            }
        }
    }

    /**
     * Verifica si un usuario tiene registros en su subcolección «HistorialPrivilegios».
     *
     * <p>La presencia de al menos un documento en esta subcolección indica que el plan
     * del usuario fue otorgado por un administrador (regalado), en lugar de haber sido
     * adquirido mediante pago.</p>
     *
     * @param usuarioId Identificador único del usuario en Firestore.
     * @return {@code true} si el usuario tiene historial de privilegios, {@code false} en caso contrario.
     */
    private suspend fun verificarHistorialPrivilegios(usuarioId: String): Boolean {
        return try {
            val historial = baseDatos.collection("Usuarios")
                .document(usuarioId)
                .collection("HistorialPrivilegios")
                .get()
                .await()
            !historial.isEmpty
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Obtiene el precio estimado de un plan según su identificador.
     *
     * @param plan Identificador del código de plan (debe coincidir con los valores de [CodigoPlan]).
     * @return Precio del plan en la moneda local, o {@code 0} si el plan es gratuito o no reconocido.
     */
    private fun obtenerPrecioPlan(plan: String): Long {
        return when (plan) {
            CodigoPlan.VENDEDOR.id -> 3990
            CodigoPlan.COMERCIO.id -> 7990
            CodigoPlan.EMPRESARIAL.id -> 14990
            else -> 0
        }
    }

    /**
     * Formatea un valor numérico con separadores de miles utilizando punto como separador.
     *
     * @param valor Valor numérico a formatear.
     * @return Representación textual del valor con separadores de miles.
     */
    private fun formatearMiles(valor: Long): String {
        return String.format("%,d", valor).replace(",", ".")
    }
}
