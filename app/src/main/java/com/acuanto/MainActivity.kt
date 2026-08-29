package com.acuanto

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                ACuantoScreen()
            }
        }
    }
}

private enum class Moneda(
    val label: String,
    val code: String,
    val simbolo: String,
    val accent: Color,
) {
    // Etiquetas cortas: con tres tarjetas en fila cada una queda a un tercio del
    // ancho. Que la tasa es del BCV lo dice el subtítulo de la tarjeta.
    DOLAR("Dólar", "USD", "$", Color(0xFF43A047)),
    EURO("Euro", "EUR", "€", Color(0xFF1E88E5)),
    BINANCE("Binance", "USDT", "$", Color(0xFFF0B90B)),
}

/** El lado de venta de Binance se pinta de rojo claro; el de compra usa su amarillo. */
private val ROJO_VENTA = Color(0xFFE57373)

private fun acentoDe(moneda: Moneda, binanceCompra: Boolean): Color =
    if (moneda == Moneda.BINANCE && !binanceCompra) ROJO_VENTA else moneda.accent

/**
 * Un tick corto al alternar compra/venta.
 *
 * No usa performHapticFeedback a propósito: ese respeta "vibración táctil" del
 * sistema, que en este teléfono (y de fábrica en Samsung) viene apagada, así que
 * no hacía absolutamente nada. El Vibrator es una vibración explícita de la app
 * y sí suena; sigue respetando No molestar y la intensidad global.
 */
private fun Context.tickSutil() {
    val vibrador = getSystemService(Vibrator::class.java) ?: return
    if (!vibrador.hasVibrator()) return
    vibrador.vibrate(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // EFFECT_TICK es el más suave de los predefinidos.
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            // ponytail: 20 ms a amplitud baja es lo más parecido en API 26-28.
            VibrationEffect.createOneShot(20, 60)
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ACuantoScreen() {
    val scope = rememberCoroutineScope()

    // ponytail: las tasas se vuelven a consultar al rotar la pantalla.
    // Si molesta, moverlas a un ViewModel.
    var dolar by remember { mutableStateOf<Rate?>(null) }
    var euro by remember { mutableStateOf<Rate?>(null) }
    var binance by remember { mutableStateOf<BinanceRate?>(null) }
    var panel by remember { mutableStateOf<BcvPanel?>(null) }
    var cargando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Lo que el usuario escribió sí sobrevive a la rotación.
    var monedaName by rememberSaveable { mutableStateOf(Moneda.DOLAR.name) }
    var input by rememberSaveable { mutableStateOf("1") }
    var editandoBs by rememberSaveable { mutableStateOf(false) }
    // Qué lado de Binance usa la calculadora; se cambia con un toque largo.
    var binanceCompra by rememberSaveable { mutableStateOf(true) }
    val moneda = Moneda.valueOf(monedaName)

    fun actualizar() {
        if (cargando) return
        cargando = true
        scope.launch {
            // runCatching va DENTRO del async: así el fallo de una moneda
            // no cancela la consulta de la otra.
            val d = async { runCatching { fetchRate(URL_DOLAR) } }
            val e = async { runCatching { fetchRate(URL_EURO) } }
            val n = async { runCatching { fetchBinance() } }
            val b = async { runCatching { fetchBcvPanel() } }
            val rd = d.await()
            val re = e.await()
            val rn = n.await()

            // El panel del BCV es informativo y sale de raspar su HTML: si cambia
            // el markup se queda con lo último que trajo, sin tocar la calculadora
            // ni el mensaje de error, que dependen solo de dolarapi.
            b.await()
                .onSuccess { panel = it }
                .onFailure { Log.w("ACuanto", "No se pudo leer el panel del BCV", it) }

            // Un fallo nunca borra el último valor bueno que ya teníamos.
            rd.getOrNull()?.let { dolar = it }
            re.getOrNull()?.let { euro = it }
            rn.getOrNull()?.let { binance = it }

            error = when {
                rd.isSuccess && re.isSuccess && rn.isSuccess -> null
                dolar == null && euro == null && binance == null ->
                    "No se pudo conectar. Revisa tu internet y toca Actualizar."
                rd.isFailure && re.isFailure && rn.isFailure ->
                    "Sin conexión: se muestran los últimos valores obtenidos."
                rd.isFailure -> "No se pudo actualizar el dólar."
                re.isFailure -> "No se pudo actualizar el euro."
                else -> "No se pudo actualizar la tasa Binance."
            }
            cargando = false
        }
    }

    LaunchedEffect(Unit) { actualizar() }

    val ladoBinance = if (binanceCompra) "Compra" else "Venta"
    val tasaBinance = binance?.let { if (binanceCompra) it.compra else it.venta }
    val tasa = when (moneda) {
        Moneda.DOLAR -> dolar?.promedio
        Moneda.EURO -> euro?.promedio
        Moneda.BINANCE -> tasaBinance
    }
    val monto = parseAmount(input)
    val calculado = when {
        tasa == null || monto == null -> ""
        editandoBs -> formatField(monto / tasa)
        else -> formatField(monto * tasa)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ACuanto") },
                actions = {
                    if (cargando) {
                        CircularProgressIndicator(
                            Modifier.padding(end = 20.dp).size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        TextButton(onClick = { actualizar() }) { Text("Actualizar") }
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Moneda.entries.forEach { m ->
                    val bcv = when (m) {
                        Moneda.DOLAR -> dolar
                        Moneda.EURO -> euro
                        Moneda.BINANCE -> null
                    }
                    TarjetaTasa(
                        moneda = m,
                        valor = if (m == Moneda.BINANCE) tasaBinance else bcv?.promedio,
                        subtitulo = if (m == Moneda.BINANCE) {
                            if (binance != null) "$ladoBinance ⇅" else null
                        } else {
                            formatFecha(bcv?.fecha)?.let { "BCV $it" }
                        },
                        seleccionada = m == moneda,
                        modifier = Modifier.weight(1f),
                        accent = acentoDe(m, binanceCompra),
                        // Solo la de Binance tiene dos lados que alternar.
                        onLongClick = if (m == Moneda.BINANCE) {
                            { binanceCompra = !binanceCompra }
                        } else {
                            null
                        },
                        onClick = { monedaName = m.name },
                    )
                }
            }

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()

            Text(
                when {
                    tasa == null -> "Calculadora"
                    // Sin decir el lado, la tasa de Binance no significa nada.
                    moneda == Moneda.BINANCE ->
                        "1 USDT = Bs ${formatRate(tasa)} (${ladoBinance.lowercase()} P2P)"
                    else -> "1 ${moneda.code} = Bs ${formatRate(tasa)}"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = if (editandoBs) calculado else input,
                onValueChange = {
                    input = sanitizeInput(it)
                    editandoBs = false
                },
                label = { Text("Monto en ${moneda.code}") },
                prefix = { Text(moneda.simbolo) },
                singleLine = true,
                enabled = tasa != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "⇅",
                style = MaterialTheme.typography.titleLarge,
                color = acentoDe(moneda, binanceCompra),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            OutlinedTextField(
                value = if (editandoBs) input else calculado,
                onValueChange = {
                    input = sanitizeInput(it)
                    editandoBs = true
                },
                label = { Text("Monto en Bs") },
                prefix = { Text("Bs") },
                singleLine = true,
                enabled = tasa != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            panel?.let { PanelBcv(it) }

            Text(
                buildString {
                    append("Fuente: ve.dolarapi.com, bcv.org.ve y Binance P2P")
                    binance?.let { append(" (${it.hora})") }
                    append(".\n")
                    append(".\nBinance es la mediana de las 10 mejores ofertas de USDT/VES")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val SIMBOLOS = mapOf(
    "EUR" to "€", "CNY" to "¥", "TRY" to "₺", "RUB" to "₽", "USD" to "$",
)

/** Réplica del recuadro "Tipo de cambio de referencia" de bcv.org.ve. */
@Composable
private fun PanelBcv(panel: BcvPanel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "TIPO DE CAMBIO DE REFERENCIA BCV",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            panel.tasas.forEach { (codigo, valor) ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${SIMBOLOS[codigo].orEmpty()} $codigo".trim(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        valor,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            panel.fechaValor?.let {
                Text(
                    "Fecha Valor: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TarjetaTasa(
    moneda: Moneda,
    valor: Double?,
    subtitulo: String?,
    seleccionada: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = moneda.accent,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val contexto = LocalContext.current
    // Un corte seco entre el amarillo y el rojo se lee como un parpadeo; animado
    // se entiende que es la misma tarjeta cambiando de lado.
    val acento by animateColorAsState(accent, label = "acento")
    Card(
        modifier = modifier
            .semantics { selected = seleccionada }
            .combinedClickable(
                role = Role.RadioButton,
                onLongClickLabel = onLongClick?.let { "Cambiar entre compra y venta" },
                onLongClick = onLongClick?.let {
                    {
                        contexto.tickSutil()
                        it()
                    }
                },
                onClick = onClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (seleccionada) {
                acento.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = if (seleccionada) BorderStroke(2.dp, acento) else null,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                moneda.label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = acento,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            // titleMedium y no titleLarge: con tres tarjetas "Bs 944,75" ya no
            // entra a 22sp en pantallas de 360dp.
            Text(
                valor?.let { "Bs ${formatBs(it)}" } ?: "-",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitulo?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (onLongClick != null) {
                        acento
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TarjetasPreview() {
    MaterialTheme {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TarjetaTasa(Moneda.DOLAR, 785.0693, "BCV 25/08/2026", false, Modifier.weight(1f)) {}
            // Las dos caras de la misma tarjeta, para ver amarillo y rojo juntos.
            TarjetaTasa(
                Moneda.BINANCE,
                944.75,
                "Compra ⇅",
                true,
                Modifier.weight(1f),
                accent = acentoDe(Moneda.BINANCE, binanceCompra = true),
                onLongClick = {},
            ) {}
            TarjetaTasa(
                Moneda.BINANCE,
                939.62,
                "Venta ⇅",
                true,
                Modifier.weight(1f),
                accent = acentoDe(Moneda.BINANCE, binanceCompra = false),
                onLongClick = {},
            ) {}
        }
    }
}
