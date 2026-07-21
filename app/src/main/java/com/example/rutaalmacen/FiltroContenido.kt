package com.example.rutaalmacen

import java.text.Normalizer
import java.util.Locale

/**
 * Motor de validación y filtrado de contenido de texto para la aplicación.
 *
 * Proporciona métodos de sanitización para nombres de productos,
 * descripciones y palabras bloqueadas definidas por el administrador.
 * Implementa normalización Unicode (NFD) para eliminar diacríticos,
 * detección de patrones repetitivos y verificación contra un diccionario
 * de palabras prohibidas combinado con la lista dinámica de Firestore.
 *
 * Este componente actúa como primera línea de defensa contra contenido
 * inapropiado o malintencionado en los datos ingresados por los usuarios.
 */
object FiltroContenido {

    /**
     * Conjunto base de palabras prohibidas compilado en la aplicación.
     * Se complementa dinámicamente con las palabras cargadas desde
     * [PalabrasBloqueadasStore] vía Firestore.
     */
    private val palabrasBase = setOf(
        "caca",
        "puta",
        "mierda",
        "culiao",
        "culo",
        "weon",
        "huevon",
        "cabron",
        "pendejo",
        "idiota",
        "imbecil",
        "perra",
        "bastardo",
    )

    /**
     * Valida el nombre de un producto ingresado por el usuario.
     *
     * Aplica una cadena de verificaciones: no vacío, caracteres permitidos,
     * longitud mínima, detección de patrones repetitivos, filtrado de
     * palabras prohibidas y restricción de nombres compuestos únicamente
     * por dígitos numéricos.
     *
     * @param nombre Texto propuesto como nombre del producto.
     * @return [ResultadoValidacion] con el estado de validez y un mensaje
     *         descriptivo en caso de rechazo.
     */
    fun validarNombreProducto(nombre: String): ResultadoValidacion {
        val texto = nombre.trim()
        if (texto.isBlank()) {
            return ResultadoValidacion(false, "Ingresa el nombre del producto")
        }
        if (!tieneCaracteresPermitidos(texto, permitirPuntuacionLigera = true)) {
            return ResultadoValidacion(false, "El nombre tiene caracteres no permitidos")
        }
        val normalizado = normalizar(texto)
        val textoFiltro = normalizarParaFiltro(texto)
        if (normalizado.length < 3) {
            return ResultadoValidacion(false, "El nombre es demasiado corto")
        }
        if (esRepetitivo(normalizado)) {
            return ResultadoValidacion(false, "El nombre no parece válido")
        }
        if (contieneBloqueos(textoFiltro)) {
            return ResultadoValidacion(false, "El nombre contiene palabras no permitidas")
        }
        if (soloNumeros(normalizado)) {
            return ResultadoValidacion(false, "El nombre no puede ser solo números")
        }
        return ResultadoValidacion(true, "")
    }

    /**
     * Valida la descripción de un producto ingresada por el usuario.
     *
     * A diferencia de [validarNombreProducto], la descripción es opcional:
     * un campo vacío se considera válido. Se aplican las mismas reglas
     * de caracteres, longitud, repetitividad y filtrado de contenido.
     *
     * @param descripcion Texto propuesto como descripción del producto.
     * @return [ResultadoValidacion] con el estado de validez y un mensaje
     *         descriptivo en caso de rechazo.
     */
    fun validarDescripcion(descripcion: String): ResultadoValidacion {
        val texto = descripcion.trim()
        if (texto.isBlank()) {
            return ResultadoValidacion(true, "")
        }
        if (!tieneCaracteresPermitidos(texto, permitirPuntuacionLigera = false)) {
            return ResultadoValidacion(false, "La descripción tiene caracteres no permitidos")
        }
        val normalizado = normalizar(texto)
        val textoFiltro = normalizarParaFiltro(texto)
        if (normalizado.length < 3) {
            return ResultadoValidacion(false, "La descripción es demasiado corta")
        }
        if (esRepetitivo(normalizado)) {
            return ResultadoValidacion(false, "La descripción no parece válida")
        }
        if (contieneBloqueos(textoFiltro)) {
            return ResultadoValidacion(false, "La descripción contiene palabras no permitidas")
        }
        return ResultadoValidacion(true, "")
    }

    /**
     * Valida una palabra o frase propuesta para la lista de bloqueos
     * administrativos.
     *
     * Garantiza que el texto cumpla con los criterios de formato antes
     * de ser persistido en la colección [Constantes.COLECCION_PALABRAS_BLOQUEADAS].
     *
     * @param palabra Texto propuesto como palabra o frase bloqueada.
     * @return [ResultadoValidacion] con el estado de validez y un mensaje
     *         descriptivo en caso de rechazo.
     */
    fun validarPalabraBloqueada(palabra: String): ResultadoValidacion {
        val texto = palabra.trim()
        if (texto.isBlank()) {
            return ResultadoValidacion(false, "Ingresa una palabra o frase válida")
        }
        if (!tieneCaracteresPermitidos(texto, permitirPuntuacionLigera = false)) {
            return ResultadoValidacion(false, "La palabra tiene caracteres no permitidos")
        }
        val normalizado = normalizarParaFiltro(texto)
        if (normalizado.length < 3) {
            return ResultadoValidacion(false, "La palabra o frase es demasiado corta")
        }
        if (esRepetitivo(normalizado)) {
            return ResultadoValidacion(false, "La palabra o frase no parece válida")
        }
        if (soloNumeros(normalizado)) {
            return ResultadoValidacion(false, "La palabra no puede ser solo números")
        }
        return ResultadoValidacion(true, "")
    }

    /**
     * Normaliza un texto eliminando diacríticos y convirtiendo a minúsculas.
     *
     * Utiliza la forma de normalización Unicode NFD para descomponer
     * caracteres acentuados en su letra base más el signo diacrítico,
     * y luego elimina los marcas de combinación. Esto permite comparaciones
     * insensibles a acentos en el filtrado de contenido.
     *
     * @param texto Texto de entrada a normalizar.
     * @return Texto en minúsculas sin diacríticos ni espacios extremos.
     */
    fun normalizar(texto: String): String {
        val limpio = texto.trim().lowercase(Locale.getDefault())
        val normalizado = Normalizer.normalize(limpio, Normalizer.Form.NFD)
        return normalizado.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    /**
     * Normaliza un texto para su uso en el filtro de contenido.
     *
     * Aplica [normalizar] y adicionalmente elimina toda puntuación,
     * colapsando secuencias de espacios en un único separador.
     * El resultado es una cadena limpia apta para búsqueda de
     * palabras prohibidas por coincidencia exacta.
     *
     * @param texto Texto de entrada a normalizar para filtrado.
     * @return Texto normalizado sin puntuación y con espacios colapsados.
     */
    fun normalizarParaFiltro(texto: String): String {
        val base = normalizar(texto)
        val sinPuntuacion = base.replace(Regex("[^a-z0-9]+"), " ")
        return sinPuntuacion.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Verifica si un texto normalizado contiene bloqueos de contenido.
     *
     * Combina la detección de palabras prohibidas individuales con
     * la búsqueda de frases bloqueadas completas.
     *
     * @param textoNormalizado Texto ya normalizado mediante [normalizarParaFiltro].
     * @return `true` si se detecta algún bloqueo, `false` en caso contrario.
     */
    private fun contieneBloqueos(textoNormalizado: String): Boolean {
        if (contienePalabrasProhibidas(textoNormalizado)) {
            return true
        }
        return contieneFrasesBloqueadas(textoNormalizado)
    }

    /**
     * Detecta palabras prohibidas dentro de un texto normalizado.
     *
     * Compara cada token del texto contra la unión del diccionario
     * base compilado y las palabras dinámicas cargadas desde Firestore.
     *
     * @param textoNormalizado Texto tokenizado y normalizado.
     * @return `true` si alguna palabra del texto está en la lista de bloqueos.
     */
    private fun contienePalabrasProhibidas(textoNormalizado: String): Boolean {
        val palabras = textoNormalizado.split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
        val bloqueadas = palabrasBase + PalabrasBloqueadasStore.obtenerPalabras()
        return palabras.any { palabra -> palabra in bloqueadas }
    }

    /**
     * Detecta frases bloqueadas completas dentro de un texto normalizado.
     *
     * A diferencia de la búsqueda por palabras, esta verificación requiere
     * coincidencia exacta de la frase completa delimitada por espacios,
     * evitando falsos positivos por substrings parciales.
     *
     * @param textoNormalizado Texto normalizado donde buscar frases.
     * @return `true` si alguna frase bloqueada está contenida en el texto.
     */
    private fun contieneFrasesBloqueadas(textoNormalizado: String): Boolean {
        val frases = PalabrasBloqueadasStore.obtenerFrases()
        if (frases.isEmpty()) return false
        val texto = " ${textoNormalizado.trim()} "
        return frases.any { frase -> texto.contains(" $frase ") }
    }

    /**
     * Determina si un texto normalizado está compuesto exclusivamente por dígitos.
     *
     * @param textoNormalizado Texto a evaluar.
     * @return `true` si el texto contiene solo caracteres numéricos.
     */
    private fun soloNumeros(textoNormalizado: String): Boolean {
        val sinEspacios = textoNormalizado.replace(" ", "")
        return sinEspacios.isNotBlank() && sinEspacios.all { it.isDigit() }
    }

    /**
     * Detecta patrones de caracteres excesivamente repetitivos.
     *
     * Identifica textos donde todos los caracteres son iguales o donde
     * un mismo carácter se repite cuatro o más veces consecutivas,
     * patrón típico de entradas malintencionadas o erróneas.
     *
     * @param textoNormalizado Texto a evaluar.
     * @return `true` si el texto presenta un patrón repetitivo anómalo.
     */
    private fun esRepetitivo(textoNormalizado: String): Boolean {
        val sinEspacios = textoNormalizado.replace(" ", "")
        if (sinEspacios.length < 3) return false
        if (sinEspacios.toSet().size <= 1) return true
        return Regex("(.)\\1{3,}").containsMatchIn(sinEspacios)
    }

    /**
     * Verifica que un texto contenga únicamente caracteres permitidos.
     *
     * @param texto Texto a validar.
     * @param permitirPuntuacionLigera Si es `true`, restringe la puntuación
     *        a un subconjunto mínimo (espacio, guión, punto, barra, coma, paréntesis).
     *        Si es `false`, permite un conjunto más amplio que incluye punto y coma,
     *        dos puntos, etc.
     * @return `true` si todos los caracteres del texto son letras, dígitos
     *         o puntuación permitida.
     */
    private fun tieneCaracteresPermitidos(
        texto: String,
        permitirPuntuacionLigera: Boolean,
    ): Boolean {
        val permitidos = if (permitirPuntuacionLigera) {
            setOf(' ', '-', '.', '/', ',','(', ')')
        } else {
            setOf(' ', '-', '.', '/', ',', ';', ':', '(', ')')
        }
        return texto.all { caracter ->
            caracter.isLetterOrDigit() || caracter in permitidos
        }
    }

    /**
     * Resultado inmutable de una operación de validación de contenido.
     *
     * @property esValido Indica si el texto evaluado cumple todas las reglas.
     * @property mensaje Texto descriptivo del motivo de rechazo cuando
     *                   [esValido] es `false`. Vacío cuando la validación es exitosa.
     */
    data class ResultadoValidacion(
        val esValido: Boolean,
        val mensaje: String,
    )
}
