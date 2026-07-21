package com.example.rutaalmacen.seguridad

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.File

object DetectorDepuracion {

    private const val TAG = "DetectorDepuracion"

    fun esDispositivoSeguro(context: Context): ResultadoSeguridad {
        val esDebuggable = (context.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val debuggerAttached = Debug.isDebuggerConnected()
        val esperandoDebugger = Debug.waitingForDebugger()
        val esEmulador = detectarEmulador()
        val esRoot = detectarRoot()

        val seguro = !esDebuggable && !debuggerAttached && !esperandoDebugger && !esRoot

        if (!seguro) {
            Log.w(TAG, "Dispositivo inseguro: debuggable=$esDebuggable, debugger=$debuggerAttached, " +
                    "esperandoDebugger=$esperandoDebugger, emulador=$esEmulador, root=$esRoot")
        }

        return ResultadoSeguridad(
            seguro = seguro,
            esDebuggable = esDebuggable,
            debuggerConectado = debuggerAttached,
            esperandoDebugger = esperandoDebugger,
            esEmulador = esEmulador,
            esRoot = esRoot
        )
    }

    private fun detectarEmulador(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator"))
    }

    private fun detectarRoot(): Boolean {
        val rutasBinarios = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/app/Superuser.apk",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su"
        )
        return rutasBinarios.any { File(it).exists() }
    }
}

data class ResultadoSeguridad(
    val seguro: Boolean,
    val esDebuggable: Boolean = false,
    val debuggerConectado: Boolean = false,
    val esperandoDebugger: Boolean = false,
    val esEmulador: Boolean = false,
    val esRoot: Boolean = false
)
