package com.example.rutaalmacen

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

class SplashActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var contenedorParticulas: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        // Instalar splash screen para evitar el ícono estático del sistema
        installSplashScreen()
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        auth = FirebaseAuth.getInstance()
        contenedorParticulas = findViewById(R.id.contenedor_particulas)

        // Iniciar la secuencia de animación profesional
        iniciarSecuenciaProfesional()

        // Navegar después de la animación
        lifecycleScope.launch {
            delay(3500)
            navegarAlLogin()
        }
    }

    private fun iniciarSecuenciaProfesional() {
        val circuloEsquinaSuperior = findViewById<View>(R.id.circulo_esquina_superior)
        val circuloEsquinaInferior = findViewById<View>(R.id.circulo_esquina_inferior)
        val lineaSuperior = findViewById<View>(R.id.linea_superior)
        val lineaInferior = findViewById<View>(R.id.linea_inferior)
        val circuloVerde = findViewById<View>(R.id.circulo_verde)
        val anillo1 = findViewById<View>(R.id.anillo_1)
        val anillo2 = findViewById<View>(R.id.anillo_2)
        val contenedorLogo = findViewById<View>(R.id.contenedor_logo)
        val sombraLogo = findViewById<View>(R.id.sombra_logo)
        val contenedorTexto = findViewById<View>(R.id.contenedor_texto)
        val lineaVerde = findViewById<View>(R.id.linea_verde)
        val progressCarga = findViewById<View>(R.id.progress_carga)

        // FASE 2: Círculos de esquinas aparecen (200-1000ms)
        lifecycleScope.launch {
            delay(200)
            animarCirculosEsquina(circuloEsquinaSuperior, circuloEsquinaInferior)
        }

        // FASE 3: Líneas decorativas se expanden (400-1200ms)
        lifecycleScope.launch {
            delay(400)
            animarLineasDecorativas(lineaSuperior, lineaInferior)
        }

        // FASE 4: Círculo verde central se expande (600-1400ms)
        lifecycleScope.launch {
            delay(600)
            animarCirculoVerde(circuloVerde)
        }

        // FASE 5: Partículas verdes flotantes (800-2000ms)
        lifecycleScope.launch {
            delay(800)
            crearParticulasVerdesFlotantes(0, 1200)
        }

        // FASE 6: Anillos concéntricos aparecen (1000-1800ms)
        lifecycleScope.launch {
            delay(1000)
            animarAnillosConcentricos(anillo1, anillo2)
        }

        // FASE 7: Logo aparece con sombra (1200-1800ms)
        lifecycleScope.launch {
            delay(1200)
            animarLogoConSombra(contenedorLogo, sombraLogo)
        }

        // FASE 8: Texto aparece elegantemente (1600-2200ms)
        lifecycleScope.launch {
            delay(1600)
            animarTextoProfesional(contenedorTexto, lineaVerde)
        }

        // FASE 9: Progress bar aparece (2200ms)
        lifecycleScope.launch {
            delay(2200)
            ObjectAnimator.ofFloat(progressCarga, "alpha", 0f, 1f).apply {
                duration = 400
                start()
            }
        }
    }

    private fun animarCirculosEsquina(circuloSuperior: View, circuloInferior: View) {
        val animSuperiorAlpha = ObjectAnimator.ofFloat(circuloSuperior, "alpha", 0f, 0.6f).apply {
            duration = 800
        }
        val animSuperiorScale = ObjectAnimator.ofFloat(circuloSuperior, "scaleX", 0f, 1f).apply {
            duration = 800
            interpolator = OvershootInterpolator(1.2f)
        }
        val animSuperiorScaleY = ObjectAnimator.ofFloat(circuloSuperior, "scaleY", 0f, 1f).apply {
            duration = 800
            interpolator = OvershootInterpolator(1.2f)
        }

        val animInferiorAlpha = ObjectAnimator.ofFloat(circuloInferior, "alpha", 0f, 0.4f).apply {
            duration = 800
            startDelay = 200
        }
        val animInferiorScale = ObjectAnimator.ofFloat(circuloInferior, "scaleX", 0f, 1f).apply {
            duration = 800
            startDelay = 200
            interpolator = OvershootInterpolator(1.2f)
        }
        val animInferiorScaleY = ObjectAnimator.ofFloat(circuloInferior, "scaleY", 0f, 1f).apply {
            duration = 800
            startDelay = 200
            interpolator = OvershootInterpolator(1.2f)
        }

        AnimatorSet().apply {
            playTogether(
                animSuperiorAlpha, animSuperiorScale, animSuperiorScaleY,
                animInferiorAlpha, animInferiorScale, animInferiorScaleY
            )
            start()
        }
    }

    private fun animarLineasDecorativas(lineaSuperior: View, lineaInferior: View) {
        val animSuperiorAlpha = ObjectAnimator.ofFloat(lineaSuperior, "alpha", 0f, 0.8f).apply {
            duration = 600
        }
        val animSuperiorScaleX = ObjectAnimator.ofFloat(lineaSuperior, "scaleX", 0f, 3f).apply {
            duration = 800
            interpolator = DecelerateInterpolator(2f)
        }

        val animInferiorAlpha = ObjectAnimator.ofFloat(lineaInferior, "alpha", 0f, 0.8f).apply {
            duration = 600
            startDelay = 200
        }
        val animInferiorScaleX = ObjectAnimator.ofFloat(lineaInferior, "scaleX", 0f, 3f).apply {
            duration = 800
            startDelay = 200
            interpolator = DecelerateInterpolator(2f)
        }

        AnimatorSet().apply {
            playTogether(
                animSuperiorAlpha, animSuperiorScaleX,
                animInferiorAlpha, animInferiorScaleX
            )
            start()
        }
    }

    private fun animarCirculoVerde(circulo: View) {
        val animScaleX = ObjectAnimator.ofFloat(circulo, "scaleX", 0f, 1f).apply {
            duration = 800
            interpolator = OvershootInterpolator(1.3f)
        }
        val animScaleY = ObjectAnimator.ofFloat(circulo, "scaleY", 0f, 1f).apply {
            duration = 800
            interpolator = OvershootInterpolator(1.3f)
        }
        val animAlpha = ObjectAnimator.ofFloat(circulo, "alpha", 0f, 1f).apply {
            duration = 600
        }

        AnimatorSet().apply {
            playTogether(animScaleX, animScaleY, animAlpha)
            start()
        }
    }

    private fun crearParticulasVerdesFlotantes(startDelay: Long, duration: Long) {
        val numParticulas = 35
        val colores = listOf(
            Color.parseColor("#10B981"),
            Color.parseColor("#34D399"),
            Color.parseColor("#6EE7B7"),
            Color.parseColor("#059669"),
            Color.parseColor("#A7F3D0")
        )

        for (i in 0 until numParticulas) {
            val tamano = Random.nextInt(4, 12)
            val particula = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(tamano, tamano).apply {
                    gravity = android.view.Gravity.CENTER
                }
                setBackgroundColor(colores[Random.nextInt(colores.size)])
                alpha = 0f
            }
            contenedorParticulas.addView(particula)

            // Posición inicial aleatoria
            val startX = Random.nextInt(-600, 600).toFloat()
            val startY = Random.nextInt(-900, 900).toFloat()
            particula.translationX = startX
            particula.translationY = startY

            // Animación flotante hacia el centro
            val animX = ObjectAnimator.ofFloat(particula, "translationX", startX, 0f).apply {
                this.startDelay = startDelay + Random.nextLong(0, 400)
                this.duration = duration
                interpolator = DecelerateInterpolator(1.5f)
            }
            val animY = ObjectAnimator.ofFloat(particula, "translationY", startY, 0f).apply {
                this.startDelay = startDelay + Random.nextLong(0, 400)
                this.duration = duration
                interpolator = DecelerateInterpolator(1.5f)
            }
            val animAlpha = ObjectAnimator.ofFloat(particula, "alpha", 0f, 0.7f).apply {
                this.startDelay = startDelay
                this.duration = 400
            }
            val animScaleX = ObjectAnimator.ofFloat(particula, "scaleX", 0.5f, 1f).apply {
                this.startDelay = startDelay
                this.duration = 400
            }
            val animScaleY = ObjectAnimator.ofFloat(particula, "scaleY", 0.5f, 1f).apply {
                this.startDelay = startDelay
                this.duration = 400
            }

            AnimatorSet().apply {
                playTogether(animX, animY, animAlpha, animScaleX, animScaleY)
                start()
            }

            // Desaparecer suavemente
            lifecycleScope.launch {
                delay(startDelay + duration)
                ObjectAnimator.ofFloat(particula, "alpha", 0.7f, 0f).apply {
                    this.duration = 600
                    start()
                }
            }
        }
    }

    private fun animarAnillosConcentricos(anillo1: View, anillo2: View) {
        // Anillo 1
        val animAnillo1ScaleX = ObjectAnimator.ofFloat(anillo1, "scaleX", 0f, 1f).apply {
            duration = 800
            interpolator = OvershootInterpolator(1.4f)
        }
        val animAnillo1ScaleY = ObjectAnimator.ofFloat(anillo1, "scaleY", 0f, 1f).apply {
            duration = 800
            interpolator = OvershootInterpolator(1.4f)
        }
        val animAnillo1Alpha = ObjectAnimator.ofFloat(anillo1, "alpha", 0f, 0.6f).apply {
            duration = 800
        }
        val animAnillo1Rotation = ObjectAnimator.ofFloat(anillo1, "rotation", 0f, 180f).apply {
            duration = 2000
            interpolator = AccelerateDecelerateInterpolator()
        }

        // Anillo 2 (con delay)
        val animAnillo2ScaleX = ObjectAnimator.ofFloat(anillo2, "scaleX", 0f, 1f).apply {
            duration = 800
            startDelay = 200
            interpolator = OvershootInterpolator(1.4f)
        }
        val animAnillo2ScaleY = ObjectAnimator.ofFloat(anillo2, "scaleY", 0f, 1f).apply {
            duration = 800
            startDelay = 200
            interpolator = OvershootInterpolator(1.4f)
        }
        val animAnillo2Alpha = ObjectAnimator.ofFloat(anillo2, "alpha", 0f, 0.3f).apply {
            duration = 800
            startDelay = 200
        }
        val animAnillo2Rotation = ObjectAnimator.ofFloat(anillo2, "rotation", 0f, -180f).apply {
            duration = 2500
            startDelay = 200
            interpolator = AccelerateDecelerateInterpolator()
        }

        AnimatorSet().apply {
            playTogether(
                animAnillo1ScaleX, animAnillo1ScaleY, animAnillo1Alpha, animAnillo1Rotation,
                animAnillo2ScaleX, animAnillo2ScaleY, animAnillo2Alpha, animAnillo2Rotation
            )
            start()
        }
    }

    private fun animarLogoConSombra(contenedorLogo: View, sombraLogo: View) {
        // Sombra aparece primero
        val animSombraAlpha = ObjectAnimator.ofFloat(sombraLogo, "alpha", 0f, 0.5f).apply {
            duration = 400
        }
        val animSombraScale = ObjectAnimator.ofFloat(sombraLogo, "scaleX", 0.8f, 1.3f).apply {
            duration = 1000
            repeatCount = 1
            repeatMode = ObjectAnimator.REVERSE
        }
        val animSombraScaleY = ObjectAnimator.ofFloat(sombraLogo, "scaleY", 0.8f, 1.3f).apply {
            duration = 1000
            repeatCount = 1
            repeatMode = ObjectAnimator.REVERSE
        }

        // Logo aparece con bounce
        val animLogoScaleX = ObjectAnimator.ofFloat(contenedorLogo, "scaleX", 0.5f, 1f).apply {
            duration = 600
            interpolator = OvershootInterpolator(1.4f)
        }
        val animLogoScaleY = ObjectAnimator.ofFloat(contenedorLogo, "scaleY", 0.5f, 1f).apply {
            duration = 600
            interpolator = OvershootInterpolator(1.4f)
        }
        val animLogoAlpha = ObjectAnimator.ofFloat(contenedorLogo, "alpha", 0f, 1f).apply {
            duration = 600
        }

        AnimatorSet().apply {
            playTogether(
                animSombraAlpha, animSombraScale, animSombraScaleY,
                animLogoScaleX, animLogoScaleY, animLogoAlpha
            )
            start()
        }
    }

    private fun animarTextoProfesional(contenedorTexto: View, lineaVerde: View) {
        // Contenedor aparece desde abajo
        val animContenedorAlpha = ObjectAnimator.ofFloat(contenedorTexto, "alpha", 0f, 1f).apply {
            duration = 600
        }
        val animContenedorTranslate = ObjectAnimator.ofFloat(contenedorTexto, "translationY", 30f, 0f).apply {
            duration = 600
            interpolator = DecelerateInterpolator(2f)
        }

        // Línea verde se expande
        val animLineaScaleX = ObjectAnimator.ofFloat(lineaVerde, "scaleX", 0f, 1f).apply {
            duration = 500
            startDelay = 200
            interpolator = DecelerateInterpolator(2f)
        }

        AnimatorSet().apply {
            playTogether(animContenedorAlpha, animContenedorTranslate, animLineaScaleX)
            start()
        }
    }

    private suspend fun navegarAlLogin() {
        delay(300)

        val intent = Intent(this, LoginActivity::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
