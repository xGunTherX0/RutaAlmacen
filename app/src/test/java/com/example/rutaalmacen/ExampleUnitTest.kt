package com.example.rutaalmacen

import org.junit.Test

import org.junit.Assert.*

/**
 * Prueba unitaria local de ejemplo que se ejecuta en la máquina de desarrollo (host).
 *
 * <p>Esta clase contiene pruebas unitarias básicas que no requieren un dispositivo
 * Android ni emulador para su ejecución. Se utiliza JUnit 4 como framework de pruebas.</p>
 *
 * @see [Documentación de pruebas de Android](http://d.android.com/tools/testing)
 */
class ExampleUnitTest {

    /**
     * Verifica que la operación de suma básica funcione correctamente.
     *
     * <p>Comprueba que la suma de 2 + 2 sea igual a 4, sirviendo como prueba
     * de sanidad para confirmar que el entorno de pruebas unitarias está
     * configurado adecuadamente.</p>
     *
     * @throws AssertionError si el resultado de la suma no es igual a 4.
     */
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }
}