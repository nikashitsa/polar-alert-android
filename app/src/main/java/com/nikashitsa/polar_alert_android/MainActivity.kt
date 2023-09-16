package com.nikashitsa.polar_alert_android

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import java.util.Timer
import java.util.UUID
import kotlin.concurrent.schedule


class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1
    }

    private lateinit var deviceId: String

    private val api: PolarBleApi by lazy {
        PolarBleApiDefaultImpl.defaultImplementation(
            applicationContext,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO
            )
        )
    }

    private var scanDisposable: Disposable? = null
    private var hrDisposable: Disposable? = null
    private var deviceConnected = false

    private lateinit var homeStep: LinearLayout
    private lateinit var connectButton: Button
    private lateinit var loadingIndicator: CircularProgressIndicator

    private lateinit var setupStep: LinearLayout
    private lateinit var textViewSetupMinBPM: TextView
    private lateinit var textViewSetupMaxBPM: TextView
    private lateinit var textViewSetupVolume: TextView
    private lateinit var hrRangeSlider: RangeSlider
    private lateinit var volumeSlider: Slider
    private lateinit var vibrateSwitch: MaterialSwitch
    private lateinit var startButton: Button

    private lateinit var runStep: LinearLayout
    private lateinit var stopButton: Button
    private lateinit var textViewRunBPM: TextView
    private lateinit var textViewRunTip: TextView

    private lateinit var deviceList: ArrayAdapter<Device>
    private lateinit var dialog: AlertDialog
    private lateinit var searchingDialog: AlertDialog
    private lateinit var settings: SharedPreferences

    private var isPlayingBeat = false
    private var isVibrating = false
    private var hrMin = 110
    private var hrMax = 140
    private var volume = 90
    private var vibrate = false

    private var lowBeepId = 0
    private var highBeepId = 0

    private var animatorSet: AnimatorSet? = null

    private lateinit var soundPool: SoundPool

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "version: " + PolarBleApiDefaultImpl.versionInfo())

        // Home step
        homeStep = findViewById(R.id.homeStep)
        connectButton = findViewById(R.id.connectButton)
        loadingIndicator = findViewById(R.id.loadingIndicator)

        connectButton.setOnClickListener {
            showSearchingDialog()
            scanDisposable = api.searchForDevice()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { polarDeviceInfo: PolarDeviceInfo ->
                        searchingDialog.hide()
                        showSelectDeviceDialog()
                        deviceList.add(Device(polarDeviceInfo))
                    },
                    { error: Throwable ->
                        Log.e(TAG, "Device scan failed. Reason $error")
                    },
                    {
                        Log.d(TAG, "complete")
                    }
                )
        }

        // Setup step
        setupStep = findViewById(R.id.setupStep)
        settings = getSharedPreferences("settings", MODE_PRIVATE)

        hrMin = settings.getInt("hrMin", hrMin)
        hrMax = settings.getInt("hrMax", hrMax)
        textViewSetupMinBPM = findViewById(R.id.textViewSetupMinBPM)
        textViewSetupMaxBPM = findViewById(R.id.textViewSetupMaxBPM)
        textViewSetupMinBPM.text = hrMin.toString()
        textViewSetupMaxBPM.text = getString(R.string.bpm_max, hrMax)
        hrRangeSlider = findViewById(R.id.hrRangeSlider)
        hrRangeSlider.setValues(hrMin.toFloat(), hrMax.toFloat())
        hrRangeSlider.addOnChangeListener { hrRangeSlider, _, _ ->
            hrMin = hrRangeSlider.values[0].toInt()
            hrMax = hrRangeSlider.values[1].toInt()
            textViewSetupMinBPM.text = hrMin.toString()
            textViewSetupMaxBPM.text = getString(R.string.bpm_max, hrMax)
            with (settings.edit()) {
                putInt("hrMin", hrMin)
                putInt("hrMax", hrMax)
                apply()
            }
        }

        volume = settings.getInt("volume", volume)
        textViewSetupVolume = findViewById(R.id.textViewSetupVolume)
        textViewSetupVolume.text = getString(R.string.volume, volume)
        volumeSlider = findViewById(R.id.volumeSlider)
        volumeSlider.value = volume.toFloat()
        volumeSlider.addOnChangeListener { _, value, _ ->
            volume = value.toInt()
            textViewSetupVolume.text = getString(R.string.volume, volume)
            with (settings.edit()) {
                putInt("volume", volume)
                apply()
            }
            playLow()
        }

        vibrate = settings.getBoolean("vibrate", vibrate)
        vibrateSwitch = findViewById(R.id.vibrateSwitch)
        vibrateSwitch.text = getString(R.string.vibrate, if (vibrate) "ON" else "OFF")
        vibrateSwitch.isChecked = vibrate

        vibrateSwitch.setOnCheckedChangeListener { _, isChecked ->
            vibrate = isChecked
            if (isChecked) {
                vibrateSwitch.text = getString(R.string.vibrate, "ON")
                vibrate("low")
            } else {
                vibrateSwitch.text = getString(R.string.vibrate, "OFF")
            }
            with (settings.edit()) {
                putBoolean("vibrate", vibrate)
                apply()
            }
        }

        startButton = findViewById(R.id.startButton)

        textViewSetupMinBPM.setOnClickListener { _: View? ->
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Choose min BPM")
            val items = (40.. hrMax).toList()
            val adapter = ArrayAdapter(this, R.layout.list_item, items)
            builder.setSingleChoiceItems(adapter, hrMin - 40) { _, which ->
                if (adapter.isEmpty) return@setSingleChoiceItems
                val value = adapter.getItem(which)
                if (value != null) {
                    hrMin = value
                    hrRangeSlider.setValues(hrMin.toFloat(), hrMax.toFloat())
                    dialog.dismiss()
                }
            }
            dialog = builder.create()
            dialog.show()
        }

        textViewSetupMaxBPM.setOnClickListener { _: View? ->
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Choose max BPM")
            val items = (hrMin.. 220).toList()
            val adapter = ArrayAdapter(this, R.layout.list_item, items)
            builder.setSingleChoiceItems(adapter, hrMax - hrMin) { _, which ->
                if (adapter.isEmpty) return@setSingleChoiceItems
                val value = adapter.getItem(which)
                if (value != null) {
                    hrMax = value
                    hrRangeSlider.setValues(hrMin.toFloat(), hrMax.toFloat())
                    dialog.dismiss()
                }
            }
            dialog = builder.create()
            dialog.show()
        }

        startButton.setOnClickListener {
            setupStep.visibility = View.GONE
            runStep.visibility = View.VISIBLE
            textViewRunBPM.text = getString(R.string.bpm_run, 0)
            textViewRunBPM.setTextColor(getColor(R.color.white))
            textViewRunTip.text = ""
            hrDisposable = api.startHrStreaming(deviceId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { hrData: PolarHrData ->
                        for (sample in hrData.samples) {
                            Log.d(TAG, "HR bpm: ${sample.hr}")
                            pulse(textViewRunBPM)
                            textViewRunBPM.text = getString(R.string.bpm_run, sample.hr)
                            if (sample.hr > hrMax) {
                                playBeat("high")
                                vibrate("high")
                                textViewRunBPM.setTextColor(getColor(R.color.red))
                                textViewRunTip.text = getString(R.string.too_high)
                            } else if (sample.hr < hrMin) {
                                playBeat("low")
                                vibrate("low")
                                textViewRunBPM.setTextColor(getColor(R.color.red))
                                textViewRunTip.text = getString(R.string.too_low)
                            } else {
                                textViewRunBPM.setTextColor(getColor(R.color.white))
                                textViewRunTip.text = getString(R.string.good)
                            }
                        }
                    },
                    { error: Throwable ->
                        Log.e(TAG, "HR stream failed. Reason $error")
                    },
                    { Log.d(TAG, "HR stream complete") }
                )
        }

        // Run step
        runStep = findViewById(R.id.runStep)
        stopButton = findViewById(R.id.stopButton)
        textViewRunBPM = findViewById(R.id.textViewRunBPM)
        textViewRunTip = findViewById(R.id.textViewRunTip)

        soundPool = SoundPool.Builder()
            .setMaxStreams(10)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        lowBeepId = soundPool.load(this, R.raw.low_beep, 1)
        highBeepId = soundPool.load(this, R.raw.high_beep, 1)

        stopButton.setOnClickListener {
            hrDisposable?.dispose()
            runStep.visibility = View.GONE
            setupStep.visibility = View.VISIBLE
        }

        // Common
        // TODO: not sure about this
        api.setPolarFilter(false)
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                Log.d(TAG, "BLE power: $powered")
                if (powered) {
                    showToast("Bluetooth on")
                } else {
                    showToast("Bluetooth off")
                }
            }
            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "CONNECTED: ${polarDeviceInfo.deviceId}")
                deviceId = polarDeviceInfo.deviceId
                deviceConnected = true
                homeStep.visibility = View.GONE
                setupStep.visibility = View.VISIBLE
            }
            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "CONNECTING: ${polarDeviceInfo.deviceId}")
            }
            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "DISCONNECTED: ${polarDeviceInfo.deviceId}")
                deviceConnected = false
            }
            override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
                Log.d(TAG, "DIS INFO uuid: $uuid value: $value")
            }
            override fun batteryLevelReceived(identifier: String, level: Int) {
                Log.d(TAG, "BATTERY LEVEL: $level")
            }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), PERMISSION_REQUEST_CODE)
            } else {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQUEST_CODE)
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (index in 0..grantResults.lastIndex) {
                if (grantResults[index] == PackageManager.PERMISSION_DENIED) {
                    Log.w(TAG, "No sufficient permissions")
                    showToast("No sufficient permissions")
                    return
                }
            }
            Log.d(TAG, "Needed permissions are granted")
        }
    }

    public override fun onPause() {
        super.onPause()
    }

    public override fun onResume() {
        super.onResume()
        api.foregroundEntered()
    }

    public override fun onDestroy() {
        super.onDestroy()
        api.shutDown()
        soundPool.release()
    }

    private fun showToast(message: String) {
        val toast = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG)
        toast.show()
    }

    private fun playBeat(type: String) {
        if (isPlayingBeat) return
        isPlayingBeat = true
        if (type == "low") {
            playLow()
        } else if (type == "high") {
            playHigh()
        }
        Timer().schedule(300){
            isPlayingBeat = false
        }
    }

    private fun playLow() {
        val volumeFloat = volume / 100f
        soundPool.play(lowBeepId, volumeFloat, volumeFloat, 0, 0, 1f)
    }

    private fun playHigh() {
        val volumeFloat = volume / 100f
        soundPool.play(highBeepId, volumeFloat, volumeFloat, 0, 0, 1f)
    }

    private fun vibrate(type: String) {
        if (!vibrate) return
        if (isVibrating) return
        isVibrating = true
        if (type == "low") {
            vibrateLow()
        } else if (type == "high") {
            vibrateHigh()
        }
        Timer().schedule(300){
            isVibrating = false
        }
    }

    private fun vibrateLow() {
        val vibrator = this.getSystemService(VIBRATOR_SERVICE) as Vibrator
        val pattern = longArrayOf(0, 75)
        vibrator.vibrate(pattern, -1)
    }

    private fun vibrateHigh() {
        val vibrator = this.getSystemService(VIBRATOR_SERVICE) as Vibrator
        val pattern = longArrayOf(0, 75, 150, 75)
        vibrator.vibrate(pattern, -1)
    }

    private fun showSearchingDialog() {
        val builder = AlertDialog.Builder(this)
        val timer = Timer()
        builder.setTitle("Searching Polar device")
        builder.setView(R.layout.searching_dialog)
        builder.setOnCancelListener {
            scanDisposable?.dispose()
            timer.cancel()
        }
        searchingDialog = builder.create()
        searchingDialog.show()
        timer.schedule(10000){
            val isEmptyDeviceList = !::deviceList.isInitialized || deviceList.isEmpty
            if (searchingDialog.isShowing && isEmptyDeviceList) {
                searchingDialog.cancel()
                Looper.prepare()
                showNotFoundDialog()
                Looper.loop()
            }
        }
    }

    private fun showSelectDeviceDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Choose device")
        deviceList = ArrayAdapter(this, R.layout.list_item)
        builder.setAdapter(deviceList) { _, which ->
            if (deviceList.isEmpty) return@setAdapter
            val device: Device? = deviceList.getItem(which)
            if (device != null) {
                connectToDevice(device.id)
                scanDisposable?.dispose()
                deviceList.clear()
            }
        }
        builder.setOnCancelListener {
            scanDisposable?.dispose()
            deviceList.clear()
        }
        dialog = builder.create()
        dialog.show()
    }

    private fun showNotFoundDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Polar device not found")
        builder.setMessage("Make sure that you put it on and that the battery level is good.")
        val dialog = builder.create()
        dialog.show()
    }

    private fun connectToDevice(deviceId: String) {
        try {
            api.connectToDevice(deviceId)
            connectButton.visibility = View.GONE
            loadingIndicator.visibility = View.VISIBLE
        } catch (polarInvalidArgument: PolarInvalidArgument) {
            Log.e(TAG, "Failed to connect. Reason $polarInvalidArgument ")
        }
    }

    private fun pulse(target: View) {
        if (animatorSet?.isRunning == true) {
            return
        }
        val scaleXFrom = ObjectAnimator.ofFloat(target, "scaleX", 1f)
        val scaleYFrom = ObjectAnimator.ofFloat(target, "scaleY", 1f)
        val scaleXTo = ObjectAnimator.ofFloat(target, "scaleX", 0.9f)
        val scaleYTo = ObjectAnimator.ofFloat(target, "scaleY", 0.9f)
        animatorSet = AnimatorSet().apply {
            duration = 150
            play(scaleXTo).with(scaleYTo)
            play(scaleXFrom).with(scaleYFrom).after(scaleYTo)
            start()
        }
    }
}