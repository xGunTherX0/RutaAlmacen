package com.example.rutaalmacen

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.util.Locale

class ResultadosListaComprasActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_resultados_lista_compras)

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        val header = findViewById<View>(R.id.header_resultados_lista)
        val paddingHeaderBase = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenedor_resultados_lista)) { vista, insets ->
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

        @Suppress("UNCHECKED_CAST")
        val resultados = intent.getSerializableExtra(EXTRA_RESULTADOS) as? ArrayList<ResultadoListaCompras>
        val totalProductos = intent.getIntExtra(EXTRA_TOTAL_PRODUCTOS, 0)
        val listaProductos = intent.getStringArrayListExtra(EXTRA_LISTA_PRODUCTOS)

        val textoResumen = findViewById<TextView>(R.id.texto_resumen_busqueda)
        val textoSubtitulo = findViewById<TextView>(R.id.texto_subtitulo_resumen)
        val recyclerResultados = findViewById<RecyclerView>(R.id.recycler_resultados_lista)
        val contenedorSinResultados = findViewById<LinearLayout>(R.id.contenedor_sin_resultados)
        val botonVolverLista = findViewById<MaterialButton>(R.id.boton_volver_lista)
        val botonVolverHeader = findViewById<MaterialButton>(R.id.boton_volver_resultados)

        textoResumen.text = "${resultados?.size ?: 0} almacén${if ((resultados?.size ?: 0) != 1) "es" else ""} encontrado${if ((resultados?.size ?: 0) != 1) "s" else ""}"

        val textoProductos = listaProductos?.joinToString(", ") ?: ""
        textoSubtitulo.text = "Buscaste: $textoProductos"

        if (resultados.isNullOrEmpty()) {
            contenedorSinResultados.visibility = View.VISIBLE
            recyclerResultados.visibility = View.GONE
        } else {
            contenedorSinResultados.visibility = View.GONE
            recyclerResultados.visibility = View.VISIBLE

            val adaptador = AdaptadorResultadosLista(
                resultados = resultados,
                onLlegar = { resultado -> abrirNavegacion(resultado) },
                onVerStock = { resultado -> abrirStockAlmacen(resultado) },
            )
            recyclerResultados.layoutManager = LinearLayoutManager(this)
            recyclerResultados.adapter = adaptador
        }

        botonVolverHeader.setOnClickListener { finish() }
        botonVolverLista.setOnClickListener { finish() }
    }

    private fun abrirNavegacion(resultado: ResultadoListaCompras) {
        val lat = resultado.almacen.latitud
        val lon = resultado.almacen.longitud
        if (lat == null || lon == null) {
            Toast.makeText(this, "Ubicación del almacén no disponible", Toast.LENGTH_SHORT).show()
            return
        }
        val etiqueta = resultado.almacen.nombreAlmacen.ifBlank { "Almacén" }
        val uri = Uri.parse("google.navigation:q=$lat,$lon($etiqueta)")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")))
        }
    }

    private fun abrirStockAlmacen(resultado: ResultadoListaCompras) {
        if (resultado.almacen.vendedorId.isBlank()) {
            Toast.makeText(this, "No se pudo identificar el almacén", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, StockAlmacenActivity::class.java).apply {
            putExtra(StockAlmacenActivity.EXTRA_VENDEDOR_ID, resultado.almacen.vendedorId)
            putExtra(StockAlmacenActivity.EXTRA_NOMBRE_ALMACEN, resultado.almacen.nombreAlmacen)
            putExtra(StockAlmacenActivity.EXTRA_HORARIO_ATENCION, resultado.almacen.horarioAtencion)
            putExtra(StockAlmacenActivity.EXTRA_LATITUD_ALMACEN, resultado.almacen.latitud)
            putExtra(StockAlmacenActivity.EXTRA_LONGITUD_ALMACEN, resultado.almacen.longitud)
        }
        startActivity(intent)
    }

    private class AdaptadorResultadosLista(
        private val resultados: List<ResultadoListaCompras>,
        private val onLlegar: (ResultadoListaCompras) -> Unit,
        private val onVerStock: (ResultadoListaCompras) -> Unit,
    ) : RecyclerView.Adapter<AdaptadorResultadosLista.VistaAlmacen>() {

        class VistaAlmacen(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val textoNombreAlmacen: TextView = itemView.findViewById(R.id.texto_nombre_almacen_match)
            val textoBadgeCoincidencia: TextView = itemView.findViewById(R.id.texto_badge_coincidencia)
            val textoHorario: TextView = itemView.findViewById(R.id.texto_horario_almacen_match)
            val textoEstadoHorario: TextView = itemView.findViewById(R.id.texto_estado_horario_match)
            val textoDistancia: TextView = itemView.findViewById(R.id.texto_distancia_match)
            val textoProductosEncontrados: TextView = itemView.findViewById(R.id.texto_productos_encontrados)
            val chipGroupEncontrados: ChipGroup = itemView.findViewById(R.id.chipgroup_encontrados)
            val textoProductosFaltantes: TextView = itemView.findViewById(R.id.texto_productos_faltantes)
            val chipGroupFaltantes: ChipGroup = itemView.findViewById(R.id.chipgroup_faltantes)
            val botonVerStock: android.widget.TextView = itemView.findViewById(R.id.boton_ver_stock_almacen)
            val botonLlegar: android.widget.TextView = itemView.findViewById(R.id.boton_llegar_almacen_match)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VistaAlmacen {
            val vista = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_almacen_match, parent, false)
            return VistaAlmacen(vista)
        }

        override fun onBindViewHolder(holder: VistaAlmacen, position: Int) {
            val r = resultados[position]
            val ctx = holder.itemView.context
            val almacen = r.almacen

            holder.textoNombreAlmacen.text = almacen.nombreAlmacen

            val porcentaje = r.porcentajeCoincidencia.toInt()
            holder.textoBadgeCoincidencia.text = "$porcentaje% coincidencia"
            if (porcentaje >= 50) {
                holder.textoBadgeCoincidencia.setBackgroundResource(R.drawable.bg_chip_estado_ok)
                holder.textoBadgeCoincidencia.setTextColor(
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.stock_verde)
                )
            } else {
                holder.textoBadgeCoincidencia.setBackgroundResource(R.drawable.bg_chip_estado_mal)
                holder.textoBadgeCoincidencia.setTextColor(
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.stock_rojo)
                )
            }

            holder.textoHorario.text = almacen.horarioAtencion
            holder.textoEstadoHorario.text = if (almacen.abiertoAhora) "Abierto" else "Cerrado"
            holder.textoEstadoHorario.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    ctx, if (almacen.abiertoAhora) R.color.stock_verde else R.color.stock_rojo,
                )
            )
            holder.textoEstadoHorario.setBackgroundResource(
                if (almacen.abiertoAhora) R.drawable.bg_chip_estado_ok else R.drawable.bg_chip_estado_mal,
            )

            holder.textoDistancia.text = formatearDistancia(almacen.distanciaMetros)

            holder.textoProductosEncontrados.text = r.textoCoincidencia

            holder.chipGroupEncontrados.removeAllViews()
            for (producto in almacen.productosEncontrados) {
                val chip = Chip(ctx).apply {
                    text = "✓ $producto"
                    isCheckable = false
                    isClickable = false
                    setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.stock_verde))
                    setChipBackgroundColorResource(R.color.colorPrimaryLight)
                    textSize = 12f
                }
                holder.chipGroupEncontrados.addView(chip)
            }

            if (almacen.productosFaltantes.isNotEmpty()) {
                holder.textoProductosFaltantes.visibility = View.VISIBLE
                holder.textoProductosFaltantes.text = "Faltan ${almacen.productosFaltantes.size} producto${if (almacen.productosFaltantes.size > 1) "s" else ""}:"
                holder.chipGroupFaltantes.visibility = View.VISIBLE
                holder.chipGroupFaltantes.removeAllViews()
                for (producto in almacen.productosFaltantes) {
                    val chip = Chip(ctx).apply {
                        text = "✗ $producto"
                        isCheckable = false
                        isClickable = false
                        setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.stock_rojo))
                        setChipBackgroundColorResource(R.color.colorAccentLight)
                        textSize = 12f
                    }
                    holder.chipGroupFaltantes.addView(chip)
                }
            } else {
                holder.textoProductosFaltantes.visibility = View.GONE
                holder.chipGroupFaltantes.visibility = View.GONE
            }

            holder.botonVerStock.setOnClickListener { onVerStock(r) }
            holder.botonLlegar.setOnClickListener { onLlegar(r) }
            holder.botonLlegar.isEnabled = almacen.latitud != null && almacen.longitud != null
            holder.botonLlegar.alpha = if (holder.botonLlegar.isEnabled) 1f else 0.5f
        }

        override fun getItemCount(): Int = resultados.size

        private fun formatearDistancia(metros: Double?): String {
            if (metros == null) return "Sin ubicación"
            return if (metros >= 1000) "${String.format(Locale.forLanguageTag("es-CL"), "%.1f", metros / 1000.0)} km" else "${metros.toInt()} m"
        }
    }

    companion object {
        const val EXTRA_RESULTADOS = "resultados_lista"
        const val EXTRA_TOTAL_PRODUCTOS = "total_productos"
        const val EXTRA_LISTA_PRODUCTOS = "lista_productos"
    }
}
