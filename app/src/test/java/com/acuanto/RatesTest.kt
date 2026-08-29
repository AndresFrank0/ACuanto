package com.acuanto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cubre el manejo del monto: es la única lógica de la app donde un error se traduce en plata mal calculada. */
class RatesTest {

    @Test
    fun `sanitize deja solo digitos y un separador, normalizado a coma`() {
        assertEquals("125", sanitizeInput("125"))
        assertEquals("12,5", sanitizeInput("12,5"))
        assertEquals("12,5", sanitizeInput("12.5"))
        assertEquals("12,5", sanitizeInput("1a2b,5"))
        // El segundo separador se descarta: nunca puede confundirse con miles.
        assertEquals("12,55", sanitizeInput("12,5.5"))
        assertEquals("", sanitizeInput("abc"))
    }

    @Test
    fun `parse acepta coma y punto`() {
        assertEquals(12.5, parseAmount("12,5")!!, 1e-9)
        assertEquals(12.5, parseAmount("12.5")!!, 1e-9)
        assertEquals(0.5, parseAmount(",5")!!, 1e-9)
        assertNull(parseAmount(""))
        assertNull(parseAmount(","))
        assertNull(parseAmount("-3"))
    }

    /** Si esto falla, escribir en un campo y tocar el otro cambiaría el monto solo. */
    @Test
    fun `formatField hace round-trip sin separador de miles`() {
        val texto = formatField(1234.5678)
        assertEquals("1234,57", texto)
        assertEquals(1234.57, parseAmount(sanitizeInput(texto))!!, 1e-9)
    }

    @Test
    fun `formato venezolano usa coma decimal y punto de miles`() {
        assertEquals("785,07", formatBs(785.0693))
        assertEquals("1.234,57", formatBs(1234.5678))
        assertEquals("785,0693", formatRate(785.0693))
    }

    @Test
    fun `conversion en ambos sentidos`() {
        val tasa = 785.0693
        assertEquals("785,07", formatField(1.0 * tasa))
        assertEquals("1", formatField(785.0693 / tasa))
    }

    @Test
    fun `fecha ISO a formato local`() {
        assertEquals("25/08/2026", formatFecha("2026-08-25T00:00:00-04:00"))
        assertNull(formatFecha(null))
        assertNull(formatFecha("basura"))
    }

    /**
     * Recorte del HTML real de bcv.org.ve, con su sangría y el <strong> con
     * atributos. Si el BCV cambia el markup, este test es lo que avisa.
     */
    private val htmlBcv = """
        <div id="euro" class="col-sm-12 col-xs-12 ">
          <div class="field-content"><div class="row recuadrotsmc">
            <div class="col-sm-6 col-xs-6">
              <img src="/sites/default/files/euro-04_2.png" class="icono_bss_blanco1">
              <span> EUR </span>
            </div>
            <div class="col-sm-6 col-xs-6 centrado textp"><strong class="a"> 916,02670993 </strong></div>
          </div></div>
        </div>
        <div id="yuan" class="col-sm-12 col-xs-12">
          <div class="field-content"><div class="row recuadrotsmc">
            <div class="col-sm-6 col-xs-6"><span> CNY </span></div>
            <div class="col-sm-6 col-xs-6 centrado textp"><strong class="a"> 116,79971732 </strong></div>
          </div></div>
        </div>
        <div id="dolar" class="col-sm-12 col-xs-12 ">
          <div class="field-content"><div class="row recuadrotsmc">
            <div class="col-sm-6 col-xs-6"><span> USD</span></div>
            <div class="col-sm-6 col-xs-6 centrado textp"><strong class="a"> 785,06930000 </strong></div>
          </div></div>
        </div>
        <div class="pull-right dolar"><span class="date-display-label">Fecha Valor:</span>
          <span class="date-display-single" property="dc:date" datatype="xsd:dateTime"
                content="2026-08-25T00:00:00-04:00">Martes, 25 Agosto  2026</span>
        </div>
    """.trimIndent()

    @Test
    fun `parsea las tasas del BCV en el orden de la pagina`() {
        val panel = parseBcvPanel(htmlBcv)
        assertEquals(
            listOf("EUR" to "916,02670993", "CNY" to "116,79971732", "USD" to "785,06930000"),
            panel.tasas,
        )
        assertEquals("25/08/2026", panel.fechaValor)
    }

    @Test
    fun `mediana con cantidad impar y par de precios`() {
        assertEquals(942.0, mediana(listOf(944.0, 940.0, 942.0)), 1e-9)
        assertEquals(941.0, mediana(listOf(944.0, 940.0, 942.0, 938.0)), 1e-9)
        assertEquals(944.0, mediana(listOf(944.0)), 1e-9)
    }

    /**
     * Los precios llegan desordenados y Binance pone de primero avisos extremos
     * (vi uno de 955 con la venta real en 944). Un promedio se los tragaría.
     */
    @Test
    fun `mediana ignora los precios extremos de Binance`() {
        val conRuido = listOf(955.0, 950.1, 944.2, 944.1, 944.0, 943.5, 943.5, 943.5, 943.5, 943.5)
        assertEquals(943.75, mediana(conRuido), 1e-9)
    }

    /** Si el BCV cambia su HTML no debe reventar: devuelve vacío y la app lo oculta. */
    @Test
    fun `html inesperado no lanza excepcion`() {
        val panel = parseBcvPanel("<html><body>rediseñamos todo</body></html>")
        assertEquals(emptyList<Pair<String, String>>(), panel.tasas)
        assertNull(panel.fechaValor)
    }
}
