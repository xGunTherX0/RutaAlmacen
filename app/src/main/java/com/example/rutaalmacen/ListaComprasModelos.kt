package com.example.rutaalmacen

import java.io.Serializable

data class AlmacenConStock(
    val vendedorId: String,
    val nombreAlmacen: String,
    val horarioAtencion: String,
    val abiertoAhora: Boolean,
    val latitud: Double?,
    val longitud: Double?,
    val distanciaMetros: Double?,
    val productosEncontrados: List<String>,
    val productosFaltantes: List<String>,
) : Serializable

data class ResultadoListaCompras(
    val almacen: AlmacenConStock,
    val porcentajeCoincidencia: Double,
    val totalProductosLista: Int,
) : Serializable {
    val textoCoincidencia: String
        get() = "${almacen.productosEncontrados.size} de $totalProductosLista productos disponibles"
}
