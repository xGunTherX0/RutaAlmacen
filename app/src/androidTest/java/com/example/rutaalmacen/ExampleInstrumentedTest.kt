package com.example.rutaalmacen

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Prueba instrumentada de ejemplo que se ejecuta en un dispositivo Android real.
 *
 * <p>Esta clase verifica que el contexto de la aplicación bajo prueba sea accesible
 * y que el nombre del paquete coincida con el esperado. Se utiliza el framework
 * de pruebas instrumentadas de Android con JUnit 4.</p>
 *
 * @see [Documentación de pruebas de Android](http://d.android.com/tools/testing)
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    /**
     * Verifica que el contexto de la aplicación bajo prueba sea correcto.
     *
     * <p>Obtiene el contexto objetivo a través del registro de instrumentación
     * y valida que el nombre del paquete de la aplicación sea
     * {@code "com.example.rutaalmacen"}.</p>
     *
     * @throws AssertionError si el nombre del paquete obtenido no coincide
     *         con el valor esperado.
     */
    @Test
    fun useAppContext() {
        // Se obtiene el contexto de la aplicación bajo prueba desde el registro de instrumentación.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.rutaalmacen", appContext.packageName)
    }
}