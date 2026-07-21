package com.example.rutaalmacen

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Repositorio en memoria de palabras y frases bloqueadas sincronizado
 * con Firebase Firestore.
 *
 * Mantiene un listener activo sobre la colección [Constantes.COLECCION_PALABRAS_BLOQUEADAS]
 * que actualiza en tiempo real los conjuntos de palabras y frases prohibidas.
 * Estos conjuntos son consumidos por [FiltroContenido] para validar el
 * contenido ingresado por los usuarios.
 *
 * La clase es thread-safe mediante el uso de propiedades marcadas con
 * [@Volatile][Volatile] para garantizar visibilidad entre hilos.
 */
object PalabrasBloqueadasStore {

    /** Instancia singleton de Firebase Firestore para consultas en la nube. */
    private val baseDatos: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /** Conjunto actual de palabras individuales bloqueadas. Volátil para visibilidad entre hilos. */
    @Volatile
    private var palabrasActuales: Set<String> = emptySet()

    /** Conjunto actual de frases completas bloqueadas. Volátil para visibilidad entre hilos. */
    @Volatile
    private var frasesActuales: Set<String> = emptySet()

    /** Registro del listener de Firestore para permitir su cancelación si fuera necesario. */
    private var listener: ListenerRegistration? = null

    /**
     * Inicia la sincronización en tiempo real con la colección de palabras bloqueadas.
     *
     * Registra un snapshot listener que clasifica cada documento como palabra
     * individual o frase según su campo "tipo" o la presencia de espacios
     * en el texto normalizado. Es idempotente: si el listener ya está
     * registrado, no se crea uno duplicado.
     */
    fun iniciar() {
        if (listener != null) return
        listener = baseDatos.collection(Constantes.COLECCION_PALABRAS_BLOQUEADAS)
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

    /**
     * Retorna el conjunto actual de palabras individuales bloqueadas.
     *
     * @return Conjunto inmutable de palabras prohibidas sincronizado desde Firestore.
     */
    fun obtenerPalabras(): Set<String> = palabrasActuales

    /**
     * Retorna el conjunto actual de frases completas bloqueadas.
     *
     * @return Conjunto inmutable de frases prohibidas sincronizado desde Firestore.
     */
    fun obtenerFrases(): Set<String> = frasesActuales
}
