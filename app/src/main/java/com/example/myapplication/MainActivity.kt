package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.CellSignalStrengthLte
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.telephony.SignalStrength
import android.util.Log
import androidx.compose.foundation.layout.Column
import com.google.gson.Gson
import java.io.File

class MyBackgroundService : Service() {

    private lateinit var telephonyManager: TelephonyManager
    private var networkOperator: String? = null
    private var rsrp: Int? = null
    private var jsonFilePath: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MyBackgroundService", "Service started")
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        getTelephonyInfo()
        registerSignalStrengthListener()

        // Запись данных в JSON файл каждые 60 секунд
        writeToJsonEveryMinute()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        telephonyManager.listen(null, PhoneStateListener.LISTEN_NONE)
        Log.d("MyBackgroundService", "Service destroyed")
    }

    private fun getTelephonyInfo() {
        networkOperator = telephonyManager.networkOperatorName // Получение оператора сети
        Log.d("MyBackgroundService", "Network Operator: $networkOperator")
    }

    private fun registerSignalStrengthListener() {
        val phoneStateListener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                super.onSignalStrengthsChanged(signalStrength)
                rsrp = getRsrpFromSignalStrength(signalStrength)
                Log.d("MyBackgroundService", "RSRP: $rsrp")
            }
        }
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
    }

    private fun getRsrpFromSignalStrength(signalStrength: SignalStrength): Int? {
        // Пытаемся получить RSRP из объекта SignalStrength
        return try {
            // Android API 29+ предоставляет доступ к RSRP напрямую через API SignalStrength
            val cellInfo = signalStrength.cellSignalStrengths
            cellInfo.filterIsInstance<CellSignalStrengthLte>().firstOrNull()?.rsrp
        } catch (e: Exception) {
            Log.e("MyBackgroundService", "Ошибка получения RSRP: ${e.message}")
            null
        }
    }

    private fun writeToJsonEveryMinute() {
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                writeTelephonyAndLocationToJson()
                handler.postDelayed(this, 60000) // Повторять каждые 60 секунд
            }
        }, 60000)
    }

    private fun writeTelephonyAndLocationToJson() {
        // Получаем текущие координаты и данные TelephonyManager
        val data = mapOf(
            "networkOperator" to networkOperator,
            "rsrp" to rsrp,
            "latitude" to MainActivity.lastKnownLatitude,
            "longitude" to MainActivity.lastKnownLongitude
        )

        // Записываем в JSON файл
        val jsonData = Gson().toJson(data)
        jsonFilePath = filesDir.absolutePath + "/data.json"
        File(jsonFilePath!!).writeText(jsonData)

        Log.d("MyBackgroundService", "Data written to JSON: $jsonData")
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var networkOperator: String? = null
    private var rsrp: Int? = null

    companion object {
        var lastKnownLatitude: Double? = null
        var lastKnownLongitude: Double? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация клиента для получения местоположения
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Запуск фонового сервиса
        val serviceIntent = Intent(this, MyBackgroundService::class.java)
        startService(serviceIntent)

        // Запрос разрешений и получение данных
        requestPermissionsAndFetchData()

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    var latLong by remember { mutableStateOf("Загрузка координат...") }
                    var telephonyInfo by remember { mutableStateOf("Загрузка информации о сети...") }
                    var rsrpInfo by remember { mutableStateOf("Загрузка сигнала сети...") }

                    // Получение местоположения и обновление состояния
                    LaunchedEffect(Unit) {
                        getLastKnownLocation { lat, long ->
                            latLong = "Latitude: $lat, Longitude: $long"
                            lastKnownLatitude = lat
                            lastKnownLongitude = long
                        }

                        getTelephonyInfo()
                        telephonyInfo = "Network Operator: $networkOperator"
                        rsrpInfo = "RSRP: $rsrp"
                    }

                    Column(modifier = Modifier.padding(innerPadding)) {
                        Text(text = latLong)
                        Text(text = telephonyInfo)
                        Text(text = rsrpInfo)
                    }
                }
            }
        }
    }

    private fun requestPermissionsAndFetchData() {
        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                // Получаем координаты и данные Telephony
                getLastKnownLocation { lat, long ->
                    lastKnownLatitude = lat
                    lastKnownLongitude = long
                }
                getTelephonyInfo()
            } else {
                Toast.makeText(this, "Разрешение не предоставлено", Toast.LENGTH_SHORT).show()
            }
        }

        // Проверка и запрос разрешений
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            getLastKnownLocation { lat, long ->
                lastKnownLatitude = lat
                lastKnownLongitude = long
            }
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            getTelephonyInfo()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    // Получение информации о телефоне
    private fun getTelephonyInfo() {
        val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        networkOperator = telephonyManager.networkOperatorName
        registerSignalStrengthListener(telephonyManager)
    }

    private fun registerSignalStrengthListener(telephonyManager: TelephonyManager) {
        val phoneStateListener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                super.onSignalStrengthsChanged(signalStrength)
                rsrp = getRsrpFromSignalStrength(signalStrength)
                Log.d("MainActivity", "RSRP: $rsrp")
            }
        }
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
    }

    private fun getRsrpFromSignalStrength(signalStrength: SignalStrength): Int? {
        return try {
            val cellInfo = signalStrength.cellSignalStrengths
            cellInfo.filterIsInstance<CellSignalStrengthLte>().firstOrNull()?.rsrp
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка получения RSRP: ${e.message}")
            null
        }
    }

    // Функция для получения местоположения
    private fun getLastKnownLocation(onLocationReceived: (Double, Double) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    onLocationReceived(location.latitude, location.longitude)
                } else {
                    Toast.makeText(this, "Не удалось получить местоположение", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}


@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = name,
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme {
        Greeting("Android")
    }
}
