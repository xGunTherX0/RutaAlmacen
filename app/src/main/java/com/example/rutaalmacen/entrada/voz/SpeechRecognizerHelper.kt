package com.example.rutaalmacen.entrada.voz

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Clase auxiliar que encapsula el reconocimiento de voz del sistema Android.
 *
 * Envuelve [SpeechRecognizer] para simplificar su ciclo de vida y proporcionar
 * una interfaz de callbacks simplificada mediante [Listener]. Configura
 * automáticamente el reconocimiento en español con resultados parciales.
 *
 * @param context Contexto de la aplicación o actividad utilizado para crear el reconocedor.
 * @param listener Receptor de eventos del ciclo de reconocimiento de voz.
 */
class SpeechRecognizerHelper(
    private val context: Context,
    private val listener: Listener,
) {
    /**
     * Interfaz de callbacks para los eventos del ciclo de reconocimiento de voz.
     *
     * Las implementaciones reciben notificaciones sobre el inicio, resultados
     * parciales, resultado final y errores del reconocimiento.
     */
    interface Listener {
        /** Invocado cuando el reconocedor está listo para recibir audio del micrófono. */
        fun onInicio()
        /**
         * Invocado cuando se dispone de un resultado parcial del reconocimiento.
         *
         * @param texto Fragmento de texto reconocido de forma intermedia.
         */
        fun onParcial(texto: String)
        /**
         * Invocado cuando el reconocimiento finaliza con un resultado completo.
         *
         * @param texto Texto final reconocido por el motor de voz.
         */
        fun onFinal(texto: String)
        /**
         * Invocado cuando ocurre un error durante el reconocimiento de voz.
         *
         * @param mensaje Descripción legible del error ocurrido.
         */
        fun onError(mensaje: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private val tag = "SpeechHelper"
    private var created = false

    /** Indica si el reconocimiento de voz está disponible en el dispositivo actual. */
    val isDisponible: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Inicia una sesión de reconocimiento de voz.
     *
     * Crea un nuevo [SpeechRecognizer] si no existe uno activo, configura los
     * parámetros de reconocimiento en español con resultados parciales y comienza
     * la escucha del micrófono.
     *
     * @throws Exception Si no es posible crear o iniciar el reconocedor de voz.
     */
    fun iniciar() {
        Log.d(tag, "iniciar() llamado, isRecognitionAvailable=${isDisponible}")
        try {
            if (recognizer != null) {
                Log.d(tag, "destruyendo recognizer previo")
                destruir()
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(buildListener())
                created = true
            }
            Log.d(tag, "recognizer creado: ${recognizer?.javaClass?.name}")
            // Configuración del intent: modelo libre, español, resultados parciales y umbrales de silencio
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)
            }
            Log.d(tag, "intent construido, llamando startListening")
            recognizer?.startListening(intent)
            Log.d(tag, "startListening llamado OK")
        } catch (e: Exception) {
            Log.e(tag, "iniciar fallo", e)
            listener.onError("No se pudo iniciar: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Detiene la escucha activa del micrófono sin liberar los recursos del reconocedor.
     *
     * El reconocedor permanece creado para poder reiniciar la escucha rápidamente.
     */
    fun detener() {
        Log.d(tag, "detener() llamado")
        try {
            if (recognizer != null && created) {
                recognizer?.stopListening()
                Log.d(tag, "stopListening llamado")
            }
        } catch (e: Exception) {
            Log.e(tag, "detener fallo", e)
        }
    }

    /**
     * Libera completamente los recursos del reconocedor de voz.
     *
     * Cancela cualquier reconocimiento en curso y destruye la instancia subyacente
     * de [SpeechRecognizer]. Debe invocarse en el ciclo de vida `onDestroy`
     * de la actividad para evitar fugas de memoria.
     */
    fun destruir() {
        Log.d(tag, "destruir() llamado")
        try {
            if (recognizer != null && created) {
                recognizer?.cancel()
            }
        } catch (e: Exception) {
            Log.e(tag, "cancel fallo", e)
        }
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.e(tag, "destroy fallo", e)
        }
        recognizer = null
        created = false
    }

    private fun buildListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(tag, "onReadyForSpeech - listo para escuchar")
            listener.onInicio()
        }

        override fun onBeginningOfSpeech() {
            Log.d(tag, "onBeginningOfSpeech - usuario empezó a hablar")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(tag, "onEndOfSpeech - usuario dejó de hablar")
        }

        override fun onError(error: Int) {
            // Mapeo de códigos de error numéricos del sistema a mensajes descriptivos en español
            val nombreError = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                else -> "ERROR_$error"
            }
            Log.e(tag, "onError codigo=$error ($nombreError)")
            val mensaje = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Error de audio del micrófono"
                SpeechRecognizer.ERROR_CLIENT -> "Error del cliente de voz"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sin permiso de micrófono. Ve a Ajustes y concédelo."
                SpeechRecognizer.ERROR_NETWORK -> "ERROR 11: Sin internet o Google Speech no disponible. Escribe los productos abajo con el teclado."
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de red agotado"
                SpeechRecognizer.ERROR_NO_MATCH -> "No se reconoció voz. Habla más fuerte o más cerca del micrófono. Si persiste, usa el teclado abajo."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconocedor ocupado"
                SpeechRecognizer.ERROR_SERVER -> "Error del servidor de Google"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No se detectó voz. Intenta hablar apenas veas 'Escuchando...'"
                else -> "Error $nombreError del reconocimiento"
            }
            listener.onError(mensaje)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            Log.d(tag, "onResults: ${matches?.size ?: 0} resultados, primero='${matches?.firstOrNull()}'")
            val texto = matches?.firstOrNull()
            if (texto.isNullOrBlank()) {
                listener.onError("No se obtuvo texto. Toca el micrófono e intenta de nuevo.")
            } else {
                listener.onFinal(texto)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val texto = matches?.firstOrNull() ?: return
            Log.d(tag, "onPartialResults: '$texto'")
            listener.onParcial(texto)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
