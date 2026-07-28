package com.example.rutaalmacen

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class CategoriaHome(
    val nombre: String,
    val iconoResId: Int,
    val categoriaBusqueda: String
)

class AdaptadorCategorias(
    private val categorias: List<CategoriaHome>,
    private val onCategoriaClick: (CategoriaHome) -> Unit
) : RecyclerView.Adapter<AdaptadorCategorias.VistaCategoria>() {

    class VistaCategoria(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icono: ImageView = itemView.findViewById(R.id.icono_categoria)
        val texto: TextView = itemView.findViewById(R.id.texto_categoria)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VistaCategoria {
        val vista = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_categoria_home, parent, false)
        return VistaCategoria(vista)
    }

    override fun onBindViewHolder(holder: VistaCategoria, position: Int) {
        val categoria = categorias[position]
        holder.icono.setImageResource(categoria.iconoResId)
        holder.texto.text = categoria.nombre
        holder.itemView.setOnClickListener { onCategoriaClick(categoria) }
    }

    override fun getItemCount(): Int = categorias.size
}
