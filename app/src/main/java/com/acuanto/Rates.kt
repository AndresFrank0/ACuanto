package com.acuanto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

const val URL_DOLAR = "https://ve.dolarapi.com/v1/dolares/oficial"
const val URL_EURO = "https://ve.dolarapi.com/v1/euros/oficial"
const val URL_BCV = "https://www.bcv.org.ve/"
const val URL_BINANCE = "https://p2p.binance.com/bapi/c2c/v2/friendly/c2c/adv/search"

/** Una tasa del BCV: bolívares por unidad de moneda, y la fecha que reporta la API. */
data class Rate(val promedio: Double, val fecha: String?)

/** Si [body] no es null la petición sale como POST con ese JSON. */
private suspend fun http(url: String, accept: String, body: String? = null): String =
    withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "ACuanto/1.0")
            if (body != null) {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            body?.let { json -> conn.outputStream.use { it.write(json.toByteArray()) } }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                error("HTTP ${conn.responseCode}")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

suspend fun fetchRate(url: String): Rate {
    val json = JSONObject(http(url, "application/json"))
    val promedio = json.getDouble("promedio")
    require(promedio.isFinite() && promedio > 0) { "Tasa inválida: $promedio" }
    return Rate(promedio, json.optString("fechaActualizacion").ifEmpty { null })
}

/**
 * El recuadro "Tipo de cambio de referencia" del BCV: los códigos y sus valores
 * tal cual los publica (sin redondear), en el mismo orden de la página.
 */
data class BcvPanel(val tasas: List<Pair<String, String>>, val fechaValor: String?)

private val ESPACIOS = Regex("\\s+")

// El BCV no expone API: hay que leer los cinco <div> del recuadro. Se normalizan
// los espacios primero para que el markup indentado no rompa los patrones.
private val BCV_TASA = Regex(
    "id=\"(?:dolar|euro|yuan|lira|rublo)\".*?<span>\\s*([A-Z]{3})\\s*</span>" +
        ".*?<strong[^>]*>\\s*([\\d.,]+)\\s*</strong>"
)
private val BCV_FECHA = Regex("Fecha Valor.*?content=\"(\\d{4}-\\d{2}-\\d{2}[^\"]*)\"")

fun parseBcvPanel(html: String): BcvPanel {
    val plano = html.replace(ESPACIOS, " ")
    return BcvPanel(
        tasas = BCV_TASA.findAll(plano)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList(),
        fechaValor = formatFecha(BCV_FECHA.find(plano)?.groupValues?.get(1)),
    )
}

suspend fun fetchBcvPanel(): BcvPanel {
    val panel = parseBcvPanel(http(URL_BCV, "text/html"))
    require(panel.tasas.isNotEmpty()) { "El BCV cambió su HTML: no se halló ninguna tasa" }
    return panel
}

/**
 * Tasa Binance P2P de USDT/VES. [compra] es lo que cuesta comprar un USDT
 * (más caro) y [venta] lo que pagan por venderlo; [hora] es cuándo se consultó,
 * porque a diferencia del BCV esto se mueve durante todo el día.
 */
data class BinanceRate(val compra: Double, val venta: Double, val hora: String)

/**
 * "BUY" son los avisos donde el usuario compra USDT; "SELL", donde lo vende.
 *
 * transAmount descarta las ofertas de polvo (vi una de 0,46 USDT). Sin ese filtro
 * la mediana de venta sale POR ENCIMA de la de compra, que es imposible en un
 * mercado real: Binance ordena por mejor precio y arriba quedan avisos minúsculos
 * o con límites inalcanzables.
 */
private fun cuerpoBinance(tradeType: String) =
    """{"asset":"USDT","fiat":"VES","tradeType":"$tradeType",""" +
        """"page":1,"rows":10,"transAmount":"5000"}"""

/** Mediana y no promedio: un solo aviso extremo no debe mover la tasa. */
internal fun mediana(valores: List<Double>): Double {
    val orden = valores.sorted()
    val medio = orden.size / 2
    return if (orden.size % 2 == 1) orden[medio] else (orden[medio - 1] + orden[medio]) / 2
}

private suspend fun precios(tradeType: String): List<Double> {
    val avisos = JSONObject(http(URL_BINANCE, "application/json", cuerpoBinance(tradeType)))
        .getJSONArray("data")
    return (0 until avisos.length())
        .map { avisos.getJSONObject(it).getJSONObject("adv").getDouble("price") }
        .filter { it.isFinite() && it > 0 }
}

suspend fun fetchBinance(): BinanceRate {
    val compra = precios("BUY")
    val venta = precios("SELL")
    require(compra.isNotEmpty() && venta.isNotEmpty()) { "Binance no devolvió ofertas" }
    return BinanceRate(
        compra = mediana(compra),
        venta = mediana(venta),
        hora = DateTimeFormatter.ofPattern("HH:mm").format(LocalTime.now()),
    )
}

private val VE: Locale = Locale.forLanguageTag("es-VE")

private fun fmt(value: Double, grouping: Boolean, maxDecimals: Int): String =
    NumberFormat.getNumberInstance(VE).apply {
        isGroupingUsed = grouping
        minimumFractionDigits = 0
        maximumFractionDigits = maxDecimals
    }.format(value)

/** Para mostrar en pantalla: con separador de miles y 2 decimales fijos. */
fun formatBs(value: Double): String =
    NumberFormat.getNumberInstance(VE).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(value)

/** Igual que [formatBs] pero con hasta 4 decimales, para las tasas del BCV. */
fun formatRate(value: Double): String = fmt(value, grouping = true, maxDecimals = 4)

/**
 * Para escribir dentro de un campo editable: redondeado a 2 decimales y SIN
 * separador de miles, porque [sanitizeInput] trataría ese punto como el
 * separador decimal y el valor cambiaría solo con que el usuario toque el campo.
 */
fun formatField(value: Double): String = fmt(value, grouping = false, maxDecimals = 2)

/**
 * Deja pasar solo dígitos y un único separador decimal, normalizado a coma.
 * Con un solo separador posible no hay ambigüedad entre decimales y miles.
 */
fun sanitizeInput(input: String): String {
    val out = StringBuilder()
    var separatorUsed = false
    for (c in input) {
        when {
            c.isDigit() -> out.append(c)
            (c == ',' || c == '.') && !separatorUsed -> {
                separatorUsed = true
                out.append(',')
            }
        }
    }
    return out.toString()
}

/** Convierte lo que salió de [sanitizeInput] a número; null si no hay monto usable. */
fun parseAmount(input: String): Double? =
    input.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

/** "2026-08-25T00:00:00-04:00" -> "25/08/2026". */
fun formatFecha(iso: String?): String? =
    iso?.take(10)?.split("-")?.takeIf { it.size == 3 && it.all { p -> p.isNotEmpty() } }
        ?.let { "${it[2]}/${it[1]}/${it[0]}" }
