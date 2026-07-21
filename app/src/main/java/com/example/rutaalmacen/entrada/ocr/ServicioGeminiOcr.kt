package com.example.rutaalmacen.entrada.ocr

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente de comunicación con la API de Google Gemini para reconocimiento óptico
 * de caracteres (OCR) asistido por inteligencia artificial.
 *
 * Envía imágenes codificadas en Base64 al modelo «gemini-2.5-flash» junto con
 * un prompt especializado en la lectura de boletas chilenas y listas de compras
 * manuscritas. El modelo devuelve una lista estructurada de productos en formato JSON,
 * la cual se parsea a una lista de [ProductoEscaneado].
 *
 * Toda la operación de red se ejecuta en [Dispatchers.IO].
 *
 * @property contextoApp Contexto de la aplicación, necesario para leer el contenido
 *                       de la imagen a través del [android.content.ContentResolver].
 */
class ServicioGeminiOcr(private val contextoApp: Context) {

    /**
     * Analiza una imagen en busca de productos legibles, enviándola al modelo Gemini.
     *
     * Lee los bytes de la imagen apuntada por [uri], la codifica en Base64 y construye
     * una solicitud HTTP POST con un prompt especializado y un esquema JSON de respuesta
     * que restringe la salida a objetos con «nombre» y «categoria».
     *
     * @param uri URI de contenido que apunta a la imagen a analizar.
     * @return [ResultadoAnalisis.Exito] con la lista de productos detectados y el JSON crudo,
     *         o [ResultadoAnalisis.Error] si la lectura de imagen, la comunicación HTTP
     *         o el parseo de la respuesta fallan.
     */
    suspend fun analizarImagen(uri: Uri): ResultadoAnalisis = withContext(Dispatchers.IO) {
        val bytes = contextoApp.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext ResultadoAnalisis.Error("No se pudo leer la imagen")
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val prompt = """
            Eres un asistente que lee listas de compras manuscritas o boletas chilenas.
            Tu trabajo es identificar los PRODUCTOS que la persona quiere vender o registrar.
            
            Reglas críticas:
            1. Ignora líneas de una boleta que NO sean productos: RUT, IVA, NETO, TOTAL, SUBTOTAL, 
               CAMBIO, EFECTIVO, TARJETA, TRANSFERENCIA, DÉBITO, CRÉDITO, VUELTO, FECHA, HORA, 
               CAJA, LOCAL, TIENDA, SUCURSAL, DIRECCIÓN, TELÉFONO, FAX, RUTA, FOLIO, NÚMERO, 
               CLIENTE, VENDEDOR, BOLETA, DTE, SII, HORARIO, ABIERTO, CERRADO, etc.
            2. Si hay precios como $500, $1.200, ignóralos (no son productos).
            3. Si la palabra está mal escrita (ej: "Huevb" en vez de "Huevos", "Slalchicha" en vez de 
               "Salchicha"), usa tu mejor juicio para corregir a la palabra más probable en ESPAÑOL.
            4. Corrige la ortografía: si ves "tomte" es "Tomate", "lecne" es "Leche", etc.
            5. Devuelve SOLO productos válidos, no descripciones largas.
            6. Cada producto debe estar en singular o plural natural (ej: "Huevos", "Manzanas" está bien).
            7. Si NO hay productos visibles, devuelve un array vacío [].
            8. Prioriza la calidad sobre la cantidad: si no estás seguro de una palabra, NO la incluyas.
            9. Si la imagen tiene texto que no es de una lista de productos (ej: párrafo de un libro, 
               señalética), devuelve un array vacío [].
        """.trimIndent()

        val categorias = listOf(
            "Despensa", "Lácteos y Huevos", "Cecinas y Quesos", "Bebidas y Jugos",
            "Pan y Pastelería", "Frutas y Verduras", "Snacks y Dulces", "Congelados",
            "Aseo Hogar", "Higiene Personal",
        )

        val esquemaJson = JSONObject().apply {
            put("type", "ARRAY")
            put("description", "Lista de productos identificados en la lista manuscrita o boleta")
            put("items", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("nombre", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Nombre del producto, corregido y legible")
                    })
                    put("categoria", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Categoría: ${categorias.joinToString(", ")}")
                        put("enum", JSONArray(categorias))
                    })
                })
                put("required", JSONArray().put("nombre").put("categoria"))
            })
        }

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", prompt))
                    put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", base64)
                        })
                    })
                })
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("response_mime_type", "application/json")
                put("response_schema", esquemaJson)
            })
        }

        try {
            val url = URL(
                "https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini-2.5-flash:generateContent?key=$CLAVE_API"
            )
            val conexion = url.openConnection() as HttpURLConnection
            conexion.requestMethod = "POST"
            conexion.connectTimeout = 30000
            conexion.readTimeout = 60000
            conexion.doOutput = true
            conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            OutputStreamWriter(conexion.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }

            val codigo = conexion.responseCode
            if (codigo !in 200..299) {
                val error = conexion.errorStream?.let { s ->
                    BufferedReader(InputStreamReader(s)).use { it.readText() }
                } ?: "HTTP $codigo"
                Log.e("OcrDebug", "Gemini HTTP $codigo: $error")
                return@withContext ResultadoAnalisis.Error("Gemini HTTP $codigo: $error")
            }

            val texto = BufferedReader(InputStreamReader(conexion.inputStream)).use { it.readText() }
            Log.d("OcrDebug", "Gemini resp: ${texto.take(500)}")
            val productos = parsearProductos(texto)
            ResultadoAnalisis.Exito(productos, texto)
        } catch (e: Exception) {
            Log.e("OcrDebug", "Gemini excepción", e)
            ResultadoAnalisis.Error("Gemini error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Interpreta la respuesta JSON del modelo Gemini y extrae los productos identificados.
     *
     * Navega la estructura de «candidates → content → parts → text» y parsea el texto
     * resultante como un arreglo JSON de objetos con «nombre» y «categoria».
     * Soporta tanto arreglos directos como objetos envoltorios con la clave «productos».
     *
     * @param jsonRespuesta Cadena JSON completa devuelta por la API de Gemini.
     * @return Lista de [ProductoEscaneado] extraídos, con nombres capitalizados.
     *         Lista vacía si el parseo falla o no hay productos válidos.
     */
    private fun parsearProductos(jsonRespuesta: String): List<ProductoEscaneado> {
        return try {
            val outer = JSONObject(jsonRespuesta)
            val candidates = outer.optJSONArray("candidates") ?: return emptyList()
            if (candidates.length() == 0) return emptyList()
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return emptyList()
            val parts = content.optJSONArray("parts") ?: return emptyList()
            if (parts.length() == 0) return emptyList()
            val textoJson = parts.getJSONObject(0).optString("text", "[]")
            val limpio = textoJson.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val array = try {
                JSONArray(limpio)
            } catch (_: Exception) {
                try {
                    val obj = JSONObject(limpio)
                    obj.optJSONArray("productos") ?: JSONArray()
                } catch (_: Exception) {
                    return emptyList()
                }
            }
            val productos = mutableListOf<ProductoEscaneado>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val nombre = item.optString("nombre").trim()
                val categoria = item.optString("categoria", "Despensa").trim().ifEmpty { "Despensa" }
                if (nombre.isBlank() || nombre.length < 2) continue
                productos.add(
                    ProductoEscaneado(
                        nombre = nombre.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
                        categoria = categoria,
                    )
                )
            }
            productos
        } catch (e: Exception) {
            Log.e("OcrDebug", "parsearProductos excepción", e)
            emptyList()
        }
    }

    /**
     * Resultado del análisis OCR realizado por Gemini.
     *
     * Puede ser exitoso, conteniendo la lista de productos y el JSON crudo de respuesta,
     * o un error con un mensaje descriptivo de la falla.
     */
    sealed class ResultadoAnalisis {
        /**
         * Análisis completado con éxito.
         *
         * @property productos Lista de productos identificados en la imagen.
         * @property jsonCrudo Respuesta JSON completa devuelta por la API de Gemini.
         */
        data class Exito(val productos: List<ProductoEscaneado>, val jsonCrudo: String) : ResultadoAnalisis()

        /**
         * El análisis falló por un error de lectura, red o parseo.
         *
         * @property mensaje Descripción del error ocurrido.
         */
        data class Error(val mensaje: String) : ResultadoAnalisis()
    }

    /**
     * Objeto compañero que contiene las constantes de configuración del servicio.
     */
    companion object {
        /** Clave de API para autenticar las solicitudes al servicio de Gemini. */
        private const val CLAVE_API = "AIzaSyAIZA_AL6urgR2f3n2pe73ReNMAvwi9OEg"
    }
}
