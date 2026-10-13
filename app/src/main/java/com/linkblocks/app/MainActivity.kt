package com.linkblocks.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var bluetoothAddress: String? = null

    private lateinit var statusView: TextView
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var intervalInput: EditText
    private lateinit var commandInput: EditText
    private lateinit var monitorX: EditText
    private lateinit var monitorY: EditText
    private lateinit var monitorZ: EditText
    private lateinit var targetX: EditText
    private lateinit var targetY: EditText
    private lateinit var targetZ: EditText
    private lateinit var triggerSpinner: Spinner
    private lateinit var actionSpinner: Spinner
    private lateinit var modeSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)

        addText(root, "Minecraft LinkBlocks", 27f, true, 0xFF13233B.toInt())
        addText(
            root,
            "Controle regras do Bedrock por botão, temporizador e redstone.",
            14f,
            false,
            0xFF43536B.toInt()
        )
        statusView = TextView(this).apply {
            text = "Pronto. Conecte-se à ponte do servidor para começar."
            textSize = 13f
            setTextColor(0xFF203957.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFE3ECF8.toInt())
        }
        root.addView(statusView, matchWrap())

        addText(root, "CONEXÃO", 12f, true, 0xFF2363A4.toInt(), dp(16))
        modeSpinner = addSpinner(root, "Método", listOf("Wi-Fi / rede local", "Bluetooth (gateway Linux)"))
        hostInput = addField(root, "IP do computador que executa a ponte", "Ex.: 192.168.1.20", "192.168.1.20")
        portInput = addField(root, "Porta da ponte", "8765", "8765", InputType.TYPE_CLASS_NUMBER)
        tokenInput = addField(root, "Chave de acesso (igual à ponte e ao add-on)", "Troque a chave padrão", "change-this-token", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        val btButton = Button(this).apply {
            text = "Escolher dispositivo Bluetooth pareado"
            setOnClickListener { requestBluetoothDevice() }
        }
        root.addView(btButton, matchWrap())

        addText(root, "REGRA DE AUTOMAÇÃO", 12f, true, 0xFF2363A4.toInt(), dp(20))
        nameInput = addField(root, "Nome lógico do bloco/regra", "Ex.: portao_da_garagem", "portao")
        triggerSpinner = addSpinner(root, "Quando executar", listOf("Botão no app", "Temporizador", "Sinal de redstone"))
        intervalInput = addField(root, "Intervalo do temporizador (segundos)", "10", "10", InputType.TYPE_CLASS_NUMBER)
        actionSpinner = addSpinner(
            root,
            "Ação",
            listOf(
                "Abrir porta",
                "Fechar porta",
                "Alternar lâmpada de redstone",
                "Executar comando",
                "Enviar mensagem no chat",
                "Mudar para dia",
                "Mudar para noite",
                "Limpar clima"
            )
        )
        commandInput = addField(
            root,
            "Comando / mensagem (usado pela ação correspondente)",
            "Ex.: say Olá do LinkBlocks!",
            "say Olá do LinkBlocks!"
        )

        addText(root, "BLOCO MONITORADO (gatilho de redstone)", 12f, true, 0xFF2363A4.toInt(), dp(20))
        addText(root, "Posição do bloco cuja energia de redstone será observada.", 12f, false, 0xFF43536B.toInt())
        val monitorRow = row(root)
        monitorX = addField(monitorRow, "X", "0", "0", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED, compact = true)
        monitorY = addField(monitorRow, "Y", "64", "64", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED, compact = true)
        monitorZ = addField(monitorRow, "Z", "0", "0", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED, compact = true)

        addText(root, "ALVO DA AÇÃO", 12f, true, 0xFF2363A4.toInt(), dp(20))
        addText(root, "Para porta ou lâmpada, informe as coordenadas do bloco a alterar.", 12f, false, 0xFF43536B.toInt())
        val targetRow = row(root)
        targetX = addField(targetRow, "X", "0", "0", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED, compact = true)
        targetY = addField(targetRow, "Y", "64", "64", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED, compact = true)
        targetZ = addField(targetRow, "Z", "1", "1", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED, compact = true)

        val saveButton = Button(this).apply {
            text = "Salvar automação"
            setOnClickListener { saveRule() }
        }
        root.addView(saveButton, matchWrap())

        val actionButton = Button(this).apply {
            text = "Acionar agora"
            setOnClickListener { triggerNow() }
        }
        root.addView(actionButton, matchWrap())

        val testButton = Button(this).apply {
            text = "Testar conexão"
            setOnClickListener { sendEnvelope("ping", JSONObject(), "Teste da conexão") }
        }
        root.addView(testButton, matchWrap())

        addText(
            root,
            "Precisa de Bedrock Dedicated Server compatível com @minecraft/server-net. O app não controla diretamente um mundo solo ou Realms. Não publique a porta da ponte na internet.",
            12f,
            false,
            0xFF59677A.toInt(),
            dp(18)
        )
    }

    private fun saveRule() {
        val name = nameInput.text.toString().trim()
        if (name.isBlank()) {
            setStatus("Informe um nome para a regra.")
            return
        }
        val interval = intervalInput.text.toString().toIntOrNull() ?: 10
        if (triggerSpinner.selectedItemPosition == 1 && interval !in 1..86400) {
            setStatus("O intervalo precisa ser de 1 a 86400 segundos.")
            return
        }
        val payload = readRulePayload(name, interval)
        sendEnvelope("rules", payload, "Salvando regra")
    }

    private fun triggerNow() {
        val name = nameInput.text.toString().trim()
        if (name.isBlank()) {
            setStatus("Informe o nome da regra.")
            return
        }
        val payload = readRulePayload(name, intervalInput.text.toString().toIntOrNull() ?: 10)
        payload.put("name", name)
        sendEnvelope("command", payload, "Enviando comando")
    }

    private fun readRulePayload(name: String, interval: Int): JSONObject {
        val action = when (actionSpinner.selectedItemPosition) {
            0 -> "open_door"
            1 -> "close_door"
            2 -> "toggle_light"
            3 -> "command"
            4 -> "message"
            5 -> "day"
            6 -> "night"
            else -> "clear_weather"
        }
        val trigger = when (triggerSpinner.selectedItemPosition) {
            1 -> "timer"
            2 -> "redstone"
            else -> "button"
        }
        val target = JSONObject()
            .put("x", targetX.text.toString().toIntOrNull() ?: 0)
            .put("y", targetY.text.toString().toIntOrNull() ?: 64)
            .put("z", targetZ.text.toString().toIntOrNull() ?: 1)
            .put("dimension", "overworld")
        val monitor = JSONObject()
            .put("x", monitorX.text.toString().toIntOrNull() ?: 0)
            .put("y", monitorY.text.toString().toIntOrNull() ?: 64)
            .put("z", monitorZ.text.toString().toIntOrNull() ?: 0)
            .put("dimension", "overworld")

        return JSONObject()
            .put("name", name)
            .put("trigger", trigger)
            .put("action", action)
            .put("intervalSeconds", interval.coerceIn(1, 86400))
            .put("target", target)
            .put("monitor", monitor)
            .put("command", commandInput.text.toString().trim())
    }

    private fun sendEnvelope(endpoint: String, payload: JSONObject, label: String) {
        val token = tokenInput.text.toString().trim()
        if (token.isBlank()) {
            setStatus("Informe a chave de acesso.")
            return
        }
        val envelope = JSONObject()
            .put("token", token)
            .put("endpoint", endpoint)
            .put("payload", payload)
        val bluetooth = modeSpinner.selectedItemPosition == 1
        val host = hostInput.text.toString().trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
        val port = portInput.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: 8765
        val address = bluetoothAddress

        setStatus("$label…")
        worker.execute {
            try {
                val response = if (bluetooth) {
                    sendBluetooth(envelope, address)
                } else {
                    if (host.isBlank()) throw IOException("Informe o IP do servidor.")
                    sendHttp(host, port, envelope)
                }
                runOnUiThread { setStatus("$label: $response") }
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("Falha: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun sendHttp(host: String, port: Int, envelope: JSONObject): String {
        val connection = (URL("http://$host:$port/api/ingest").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 3500
            connection.readTimeout = 3500
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(envelope.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code: ${body.take(140)}")
            return body.ifBlank { "OK (HTTP $code)" }.take(180)
        } finally {
            connection.disconnect()
        }
    }

    @Suppress("MissingPermission")
    private fun sendBluetooth(envelope: JSONObject, address: String?): String {
        if (address.isNullOrBlank()) {
            throw IOException("Escolha primeiro o computador pareado no botão Bluetooth.")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            throw IOException("Permissão Bluetooth não concedida.")
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw IOException("Bluetooth indisponível.")
        val device = adapter.getRemoteDevice(address)
        val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            adapter.cancelDiscovery()
            socket.connect()
            socket.outputStream.use { output ->
                output.write((envelope.toString() + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }
            return BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8)).use {
                it.readLine() ?: "Gateway recebeu a mensagem, sem resposta."
            }.take(180)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun requestBluetoothDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BT)
            return
        }
        chooseBluetoothDevice()
    }

    @Suppress("MissingPermission")
    private fun chooseBluetoothDevice() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            setStatus("Este aparelho não oferece Bluetooth.")
            return
        }
        try {
            val devices = adapter.bondedDevices.sortedBy { it.name ?: it.address }
            if (devices.isEmpty()) {
                setStatus("Nenhum dispositivo pareado. Pareie primeiro com o computador gateway.")
                return
            }
            val labels = devices.map { "${it.name ?: "Bluetooth"} (${it.address})" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Escolher gateway Bluetooth")
                .setItems(labels) { _, index ->
                    bluetoothAddress = devices[index].address
                    setStatus("Dispositivo escolhido: ${devices[index].name ?: devices[index].address}")
                    modeSpinner.setSelection(1)
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } catch (e: SecurityException) {
            setStatus("Conceda a permissão Bluetooth nas configurações do Android.")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                chooseBluetoothDevice()
            } else {
                setStatus("Permissão Bluetooth negada.")
            }
        }
    }

    private fun addField(
        parent: LinearLayout,
        label: String,
        hint: String,
        value: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        compact: Boolean = false
    ): EditText {
        if (!compact) addText(parent, label, 13f, true, 0xFF263952.toInt(), dp(10))
        val input = EditText(this).apply {
            this.hint = hint
            setText(value)
            this.inputType = inputType
            textSize = if (compact) 14f else 15f
            setSingleLine(true)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            contentDescription = label
        }
        val params = LinearLayout.LayoutParams(
            if (compact) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50)
        )
        if (compact) {
            params.weight = 1f
            params.marginEnd = dp(5)
        } else {
            params.bottomMargin = dp(2)
        }
        parent.addView(input, params)
        if (compact) {
            val caption = TextView(this).apply {
                text = label
                textSize = 10f
                setTextColor(0xFF43536B.toInt())
            }
            // Keep coordinate captions above each compact field without changing the field order.
            parent.addView(caption, parent.indexOfChild(input), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return input
    }

    private fun addSpinner(parent: LinearLayout, label: String, items: List<String>): Spinner {
        addText(parent, label, 13f, true, 0xFF263952.toInt(), dp(10))
        val spinner = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        parent.addView(spinner, matchWrap())
        return spinner
    }

    private fun addText(
        parent: LinearLayout,
        value: String,
        size: Float,
        bold: Boolean,
        color: Int,
        topMargin: Int = dp(8)
    ) {
        val text = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, topMargin, 0, dp(5))
        }
        parent.addView(text, matchWrap())
    }

    private fun row(parent: LinearLayout): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        parent.addView(r, matchWrap())
        return r
    }

    private fun setStatus(message: String) {
        statusView.text = message
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(5)
        }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    companion object {
        private const val REQUEST_BT = 710
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
