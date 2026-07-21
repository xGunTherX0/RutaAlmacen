package com.example.rutaalmacen

import java.util.Calendar

/**
 * Utilidad para la gestión de horarios de operación de almacenes.
 *
 * Proporciona métodos para determinar si un almacén se encuentra abierto
 * en el momento actual según sus franjas horarias de mañana y tarde,
 * así como para convertir representaciones textuales de hora a minutos
 * transcurridos desde medianoche.
 *
 * Las constantes definen los valores por defecto utilizados cuando
 * un almacén no ha configurado su horario personalizado.
 */
object HorarioUtil {

    /** Texto de hora de inicio del turno matutino por defecto. */
    const val HORARIO_MANANA_INICIO_TEXTO = "09:00"

    /** Texto de hora de fin del turno matutino por defecto. */
    const val HORARIO_MANANA_FIN_TEXTO = "13:00"

    /** Texto de hora de inicio del turno vespertino por defecto. */
    const val HORARIO_TARDE_INICIO_TEXTO = "16:00"

    /** Texto de hora de fin del turno vespertino por defecto. */
    const val HORARIO_TARDE_FIN_TEXTO = "22:00"

    /** Minutos transcurridos desde medianoche para el inicio matutino (540). */
    const val HORARIO_MANANA_INICIO = 9 * 60

    /** Minutos transcurridos desde medianoche para el fin matutino (780). */
    const val HORARIO_MANANA_FIN = 13 * 60

    /** Minutos transcurridos desde medianoche para el inicio vespertino (960). */
    const val HORARIO_TARDE_INICIO = 16 * 60

    /** Minutos transcurridos desde medianoche para el fin vespertino (1320). */
    const val HORARIO_TARDE_FIN = 22 * 60

    /** Duración de validez de la caché de ubicación en milisegundos (60 s). */
    const val TIEMPO_CACHE_UBICACION_MS = 60_000L

    /** Duración de validez de la caché de almacenes en milisegundos (60 s). */
    const val TIEMPO_CACHE_ALMACENES_MS = 60_000L

    /**
     * Determina si un almacén está abierto en el momento actual.
     *
     * Evalúa si la hora del sistema cae dentro de la franja matutina
     * o vespertina configurada para el almacén. Los intervalos son
     * semiabiertos: incluyen el inicio pero excluyen el fin.
     *
     * @param mananaInicioTexto Hora de inicio matutino en formato "HH:mm".
     * @param mananaFinTexto Hora de fin matutino en formato "HH:mm".
     * @param tardeInicioTexto Hora de inicio vespertino en formato "HH:mm".
     * @param tardeFinTexto Hora de fin vespertino en formato "HH:mm".
     * @return `true` si la hora actual se encuentra dentro de alguna franja operativa.
     */
    fun estaAlmacenAbiertoAhora(
        mananaInicioTexto: String,
        mananaFinTexto: String,
        tardeInicioTexto: String,
        tardeFinTexto: String,
    ): Boolean {
        val calendario = Calendar.getInstance()
        val minutosActuales = calendario.get(Calendar.HOUR_OF_DAY) * 60 + calendario.get(Calendar.MINUTE)
        val mananaInicio = convertirHoraAMinutos(mananaInicioTexto) ?: HORARIO_MANANA_INICIO
        val mananaFin = convertirHoraAMinutos(mananaFinTexto) ?: HORARIO_MANANA_FIN
        val tardeInicio = convertirHoraAMinutos(tardeInicioTexto) ?: HORARIO_TARDE_INICIO
        val tardeFin = convertirHoraAMinutos(tardeFinTexto) ?: HORARIO_TARDE_FIN
        val enTurnoManana = minutosActuales in mananaInicio until mananaFin
        val enTurnoTarde = minutosActuales in tardeInicio until tardeFin
        return enTurnoManana || enTurnoTarde
    }

    /**
     * Convierte una representación textual de hora a minutos transcurridos
     * desde medianoche.
     *
     * @param hora Cadena en formato "HH:mm".
     * @return Minutos transcurridos (0..1439), o `null` si el formato es
     *         inválido o los valores están fuera de rango.
     */
    fun convertirHoraAMinutos(hora: String): Int? {
        val partes = hora.split(":")
        if (partes.size != 2) {
            return null
        }
        val horas = partes[0].toIntOrNull() ?: return null
        val minutos = partes[1].toIntOrNull() ?: return null
        if (horas !in 0..23 || minutos !in 0..59) {
            return null
        }
        return horas * 60 + minutos
    }
}
