package com.example.rutaalmacen

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

object PalabrasBloqueadasStore {

    private const val COLECCION_PALABRAS = "Palabras_Bloqueadas"
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    @Volatile
    private var palabrasActuales: Set<String> = emptySet()
    @Volatile
    private var frasesActuales: Set<String> = emptySet()
    private var listener: ListenerRegistration? = null

    fun iniciar() {
        if (listener != null) return
        listener = baseDatos.collection(COLECCION_PALABRAS)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val nuevasPalabras = mutableSetOf<String>()
                val nuevasFrases = mutableSetOf<String>()
                snapshot.documents.forEach { documento ->
                    val textoNormalizado = documento.getString("textoNormalizado")
                        ?: documento.getString("palabraNormalizada")
                        ?: documento.id.takeIf { it.isNotBlank() }
                        ?: return@forEach
                    val tipo = documento.getString("tipo").orEmpty()
                    val esFrase = tipo == "frase" || textoNormalizado.contains(" ")
                    if (esFrase) {
                        nuevasFrases.add(textoNormalizado)
                    } else {
                        nuevasPalabras.add(textoNormalizado)
                    }
                }
                palabrasActuales = nuevasPalabras
                frasesActuales = nuevasFrases
            }
    }

    fun obtenerPalabras(): Set<String> = palabrasActuales

    fun obtenerFrases(): Set<String> = frasesActuales
}
