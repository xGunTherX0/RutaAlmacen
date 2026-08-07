package com.example.rutaalmacen

import android.os.Bundle
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class TerminosCondicionesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminos_condiciones)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_terminos)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.title = "Terminos y Condiciones"

        val textoTerminos = findViewById<TextView>(R.id.texto_terminos)
        textoTerminos.text = obtenerTextoTerminos()

        val checkboxAceptar = findViewById<CheckBox>(R.id.checkbox_aceptar_terminos)
        val botonAceptar = findViewById<MaterialButton>(R.id.boton_aceptar_terminos)

        botonAceptar.isEnabled = false

        checkboxAceptar.setOnCheckedChangeListener { _, isChecked ->
            botonAceptar.isEnabled = isChecked
        }

        botonAceptar.setOnClickListener {
            if (checkboxAceptar.isChecked) {
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    @Deprecated("Use onBackPressedDispatcher instead")
    override fun onBackPressed() {
        super.onBackPressed()
        finishAffinity()
    }

    private fun obtenerTextoTerminos(): String {
        return """
            TERMINOS Y CONDICIONES DE USO - RutaAlmacen
            
            Ultima actualizacion: 20 de julio de 2026
            
            1. ACEPTACION DE LOS TERMINOS
            
            Al descargar, instalar o utilizar la aplicacion RutaAlmacen ("la Aplicacion"), usted ("el Usuario") acepta integramente los presentes Terminos y Condiciones de Uso ("los Terminos"). Si no esta de acuerdo con estos Terminos, no debe utilizar la Aplicacion.
            
            
            2. DESCRIPCION DEL SERVICIO
            
            RutaAlmacen es una plataforma movil que permite:
            
            - Vendedores: Gestionar inventario de productos, configurar su almacen, recibir alertas de demanda y comunicarse con compradores cercanos
            - Compradores: Buscar almacenes cercanos, consultar disponibilidad de productos y recibir notificaciones de ofertas
            - Administradores: Gestionar usuarios, moderar contenido y supervisar la plataforma
            
            
            3. ELEGIBILIDAD
            
            Para utilizar la Aplicacion, el Usuario debe:
            
            1. Ser mayor de 18 anos (o 14 anos con consentimiento de sus padres/tutores)
            2. Disponer de una cuenta de Google valida
            3. Contar con un dispositivo Android compatible (API 24 o superior)
            4. Aceptar la Politica de Privacidad
            5. No estar inhabilitado legalmente para usar servicios digitales
            
            
            4. REGISTRO Y CUENTA
            
            4.1 Creacion de cuenta
            
            El registro se realiza mediante Google Sign-In. El Usuario es responsable de:
            
            - Mantener la seguridad de su cuenta de Google
            - No compartir sus credenciales
            - Notificar cualquier acceso no autorizado
            - Proporcionar informacion veraz y actualizada
            
            4.2 Roles de usuario
            
            Al registrarse, el Usuario selecciona un rol:
            
            - Comprador: Puede buscar almacenes y consultar productos
            - Vendedor: Puede gestionar su almacen y publicar productos
            
            El cambio de rol debe ser solicitado al administrador.
            
            4.3 Suspension y eliminacion
            
            La cuenta puede ser suspendida o eliminada por:
            
            - Violacion de estos Terminos
            - Uso fraudulento o malintencionado
            - Inactividad prolongada (mas de 12 meses)
            - Solicitud del propio Usuario
            
            
            5. USO ACEPTABLE
            
            5.1 Conducta permitida
            
            El Usuario puede:
            
            - Publicar productos veridicos y legales
            - Buscar informacion de almacenes
            - Comunicarse con otros usuarios de forma respetuosa
            - Utilizar las funcionalidades segun su rol
            
            5.2 Conducta prohibida
            
            El Usuario NO puede:
            
            - Publicar productos ilegales, falsificados o peligrosos
            - Proporcionar informacion falsa o enganosa
            - Utilizar lenguaje ofensivo, discriminatorio o acosador
            - Intentar acceder a cuentas de otros usuarios
            - Realizar ingenieria inversa de la Aplicacion
            - Utilizar bots, scripts o herramientas automatizadas no autorizadas
            - Interferir con el funcionamiento de la plataforma
            - Vender o transferir su cuenta
            - Utilizar la Aplicacion para fines comerciales no autorizados
            
            
            6. PROPIEDAD INTELLECTUAL
            
            6.1 Derechos de la Aplicacion
            
            Todo el contenido, disenio, codigo fuente, logotipos, marcas y funcionalidades de RutaAlmacen son propiedad del desarrollador y estan protegidos por las leyes de propiedad intelectual de Chile y tratados internacionales.
            
            6.2 Contenido del Usuario
            
            El Usuario conserva los derechos sobre:
            
            - Informacion de sus productos
            - Notas personales
            - Datos de su almacen
            
            Al publicar contenido, el Usuario otorga a RutaAlmacen una licencia no exclusiva, gratuita y mundial para:
            
            - Mostrar el contenido dentro de la plataforma
            - Indexarlo para busquedas
            - Eliminarlo si viola estos Terminos
            
            6.3 Marcas registradas
            
            "RutaAlmacen" y el logotipo son marcas del desarrollador. No pueden utilizarse sin autorizacion expresa.
            
            
            7. SUSCRIPCIONES Y PAGOS
            
            7.1 Planes disponibles
            
            RutaAlmacen ofrece planes gratuitos y de pago:
            
            - Plan Gratuito: Funcionalidades basicas
            - Plan Vendedor: Funcionalidades avanzadas para vendedores
            - Plan Comercio: Funcionalidades para comercios medianos
            - Plan Empresarial: Funcionalidades para grandes empresas
            
            7.2 Procesamiento de pagos
            
            Los pagos se procesan a traves de Google Play Billing. El Usuario acepta:
            
            - Los terminos de servicio de Google Play
            - La politica de reembolsos de Google
            - Que los precios pueden cambiar con notificacion previa
            
            7.3 Renovacion automatica
            
            Las suscripciones se renuevan automaticamente segun el periodo contratado. El Usuario puede cancelar en cualquier momento desde Google Play Store.
            
            7.4 Reembolsos
            
            Los reembolsos se rigen por la politica de Google Play Store (generalmente 48 horas desde la compra).
            
            
            8. LIMITACION DE RESPONSABILIDAD
            
            8.1 Servicio "tal cual"
            
            La Aplicacion se proporciona "tal cual" y "segun disponibilidad". El desarrollador no garantiza:
            
            - Funcionamiento ininterrumpido o libre de errores
            - Satisfaccion total del Usuario
            - Compatibilidad con todos los dispositivos
            
            8.2 Exclusiones de responsabilidad
            
            el desarrollador NO es responsable por:
            
            - Perdida de datos por fallo del dispositivo del Usuario
            - Interrupciones del servicio por causas de fuerza mayor
            - Danos causados por terceros o por uso indebido de la Aplicacion
            - Transacciones comerciales entre Usuarios
            - Informacion inexacta proporcionada por los Usuarios
            - Danos indirectos, incidentales o consecuentes
            - Perdida de beneficios o oportunidades de negocio
            
            8.3 Limitacion de danos
            
            En ningun caso la responsabilidad total del desarrollador excedera el monto pagado por el Usuario en los ultimos 12 meses.
            
            
            9. PRIVACIDAD Y PROTECCION DE DATOS
            
            El tratamiento de datos personales se rige por la Politica de Privacidad de la Aplicacion, que forma parte integral de estos Terminos.
            
            Al aceptar estos Terminos, el Usuario consiente el tratamiento de sus datos conforme a:
            
            - Ley N 19.628 (Chile), modificada por Ley N 21.727
            - Reglamento General de Proteccion de Datos (GDPR) si aplica
            
            
            10. SEGURIDAD
            
            10.1 Obligaciones del Usuario
            
            El Usuario debe:
            
            - Mantener la seguridad de su dispositivo
            - No compartir sus credenciales
            - Cerrar sesion en dispositivos compartidos
            - Reportar actividades sospechosas
            
            10.2 Medidas del desarrollador
            
            El desarrollador implementa:
            
            - Cifrado de datos
            - Autenticacion segura
            - Monitoreo de seguridad
            - Actualizaciones periodicas
            
            Sin embargo, ningun sistema es 100% seguro. El Usuario acepta los riesgos inherentes al uso de servicios digitales.
            
            
            11. MODIFICACIONES DEL SERVICIO
            
            El desarrollador se reserva el derecho de:
            
            - Modificar, suspender o discontinuar la Aplicacion (total o parcialmente)
            - Cambiar las funcionalidades disponibles
            - Actualizar estos Terminos
            - Modificar los precios de las suscripciones
            
            Los cambios seran notificados con al menos 30 dias de anticipacion cuando sea posible.
            
            
            12. MODIFICACIONES DE LOS TERMINOS
            
            Estos Terminos pueden ser modificados. Los cambios seran notificados mediante:
            
            - Aviso en la Aplicacion
            - Correo electronico
            - Actualizacion de la fecha de "Ultima actualizacion"
            
            El uso continuado de la Aplicacion despues de los cambios implica aceptacion.
            
            
            13. RESOLUCION DE DISPUTAS
            
            13.1 Negociacion directa
            
            Las partes intentaran resolver las disputas mediante negociacion de buena fe.
            
            13.2 Mediacion
            
            Si la negociacion falla, las partes pueden acudir a mediacion voluntaria.
            
            13.3 Jurisdiccion
            
            Las disputas se someteran a los tribunales competentes de Santiago, Chile, salvo que la ley aplicable disponga otra cosa.
            
            
            14. LEY APLICABLE
            
            Estos Terminos se rigen por las leyes de la Republica de Chile, en especial:
            
            - Ley N 19.628 (Proteccion de la Vida Privada)
            - Ley N 21.727 (Proteccion de Datos Personales)
            - Ley N 19.496 (Proteccion al Consumidor)
            - Codigo Civil y Comercio
            
            Para usuarios de la Union Europea, se aplica adicionalmente el GDPR.
            
            
            15. NULIDAD PARCIAL
            
            Si alguna clausula de estos Terminos se declara nula o inaplicable, las demas clausulas mantendran su vigencia y efecto.
            
            
            16. FUERZA MAYOR
            
            El desarrollador no sera responsable por incumplimientos debidos a:
            
            - Desastres naturales
            - Pandemias
            - Guerra o disturbios civiles
            - Fallos de infraestructura de terceros (Google, ISPs)
            - Ciberataques masivos
            - Otras causas fuera de su control razonable
            
            
            17. CONTACTO
            
            Para consultas sobre estos Terminos:
            
            - Correo: carloscancino010@gmail.com
            - Pais: Chile
            
            
            18. ACEPTACION FINAL
            
            Al utilizar RutaAlmacen, usted declara:
            
            1. Haber leido y comprendido estos Terminos y Condiciones
            2. Ser mayor de edad o contar con autorizacion de sus padres/tutores
            3. Aceptar integramente las disposiciones aqui contenidas
            4. Entender que estos Terminos constituyen un acuerdo vinculante
        """.trimIndent()
    }
}
