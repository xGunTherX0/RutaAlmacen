package com.example.rutaalmacen

import java.text.Normalizer
import java.util.Locale

object FiltroContenido {

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

    fun normalizar(texto: String): String {
        val limpio = texto.trim().lowercase(Locale.getDefault())
        val normalizado = Normalizer.normalize(limpio, Normalizer.Form.NFD)
        return normalizado.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    fun normalizarParaFiltro(texto: String): String {
        val base = normalizar(texto)
        val sinPuntuacion = base.replace(Regex("[^a-z0-9]+"), " ")
        return sinPuntuacion.trim().replace(Regex("\\s+"), " ")
    }

    private fun contieneBloqueos(textoNormalizado: String): Boolean {
        if (contienePalabrasProhibidas(textoNormalizado)) {
            return true
        }
        return contieneFrasesBloqueadas(textoNormalizado)
    }

    private fun contienePalabrasProhibidas(textoNormalizado: String): Boolean {
        val palabras = textoNormalizado.split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
        val bloqueadas = palabrasBase + PalabrasBloqueadasStore.obtenerPalabras()
        return palabras.any { palabra -> palabra in bloqueadas }
    }

    private fun contieneFrasesBloqueadas(textoNormalizado: String): Boolean {
        val frases = PalabrasBloqueadasStore.obtenerFrases()
        if (frases.isEmpty()) return false
        val texto = " ${textoNormalizado.trim()} "
        return frases.any { frase -> texto.contains(" $frase ") }
    }

    private fun soloNumeros(textoNormalizado: String): Boolean {
        val sinEspacios = textoNormalizado.replace(" ", "")
        return sinEspacios.isNotBlank() && sinEspacios.all { it.isDigit() }
    }

    private fun esRepetitivo(textoNormalizado: String): Boolean {
        val sinEspacios = textoNormalizado.replace(" ", "")
        if (sinEspacios.length < 3) return false
        if (sinEspacios.toSet().size <= 1) return true
        return Regex("(.)\\1{3,}").containsMatchIn(sinEspacios)
    }

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

    data class ResultadoValidacion(
        val esValido: Boolean,
        val mensaje: String,
    )
}
