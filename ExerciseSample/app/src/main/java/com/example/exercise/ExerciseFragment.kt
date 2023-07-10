/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.exercise

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.AsyncTask
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseUpdate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.wear.ambient.AmbientModeSupport
import com.example.exercise.databinding.FragmentExerciseBinding
import com.google.common.base.CharMatcher.`is`
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.xml.sax.InputSource
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.xml.parsers.SAXParserFactory
import kotlin.math.roundToInt


/**
 * Fragment showing the exercise controls and current exercise metrics.
 */
@AndroidEntryPoint
class ExerciseFragment : Fragment() {

    @Inject
    lateinit var healthServicesManager: HealthServicesManager

    private val viewModel: MainViewModel by activityViewModels()

    private var _binding: FragmentExerciseBinding? = null
    private val binding get() = _binding!!

    private var serviceConnection = ExerciseServiceConnection()
    private var avgPulse: Int=0;
    private var minPulse: Int=0;
    private var maxPulse: Int=0;
    private var avgSpeed: Long=0L;
    private var sumSteps: Int=0;



    private var pulseData: MutableList<DataPoint> = mutableListOf();
    private var speedData: MutableList<DataPoint>  = mutableListOf();
    private var timeSinceBootInMillis: Long = 0L;

    private var accessTokenJWT:String="";

    private var cachedExerciseState = ExerciseState.ENDED
    private var activeDurationCheckpoint =
        ExerciseUpdate.ActiveDurationCheckpoint(Instant.now(), Duration.ZERO)
    private var chronoTickJob: Job? = null
    private var uiBindingJob: Job? = null
    private lateinit var latestMetrics: DataPointContainer

    private lateinit var ambientController: AmbientModeSupport.AmbientController
    private lateinit var ambientModeHandler: AmbientModeHandler


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExerciseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.startEndButton.setOnClickListener {
            // App could take a perceptible amount of time to transition between states; put button into
            // an intermediary "disabled" state to provide UI feedback.
            it.isEnabled = false
            startEndExercise()
        }
        binding.pauseResumeButton.setOnClickListener {
            // App could take a perceptible amount of time to transition between states; put button into
            // an intermediary "disabled" state to provide UI feedback.
            it.isEnabled = false
            pauseResumeExercise()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val capabilities =
                    healthServicesManager.getExerciseCapabilities() ?: return@repeatOnLifecycle
                val supportedTypes = capabilities.supportedDataTypes

                // Set enabled state for relevant text elements.
                binding.heartRateText.isEnabled = DataType.HEART_RATE_BPM in supportedTypes
                binding.caloriesText.isEnabled = DataType.CALORIES_TOTAL in supportedTypes
                binding.distanceText.isEnabled = DataType.DISTANCE in supportedTypes
                binding.lapsText.isEnabled = true
            }
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.keyPressFlow.collect {
                    healthServicesManager.markLap()
                }
            }
        }

        // Ambient Mode
        ambientModeHandler = AmbientModeHandler()
        ambientController = AmbientModeSupport.attach(requireActivity())
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ambientEventFlow.collect {
                    ambientModeHandler.onAmbientEvent(it)
                }
            }
        }

        // Bind to our service. Views will only update once we are connected to it.
        ExerciseService.bindService(requireContext().applicationContext, serviceConnection)
        bindViewsToService()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Unbind from the service.
        ExerciseService.unbindService(requireContext().applicationContext, serviceConnection)
        _binding = null
    }

    private fun startEndExercise() {
        if (cachedExerciseState.isEnded) {
            tryStartExercise()
        } else {

            derivePayload();
            checkNotNull(serviceConnection.exerciseService) {
                "Failed to achieve ExerciseService instance"
            }.endExercise()

        }
    }

    private fun derivePayload(){
        var durationInSeconds: Long = this.pulseData.last().time.toLong();
        val currentDate = LocalDate.now()
        val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val formattedDate = currentDate.format(formatter)


        val data = Data(formattedDate, durationInSeconds,avgPulse, maxPulse, minPulse, avgSpeed, sumSteps, pulseData, speedData)
        val gson = Gson()
        val jsonPayload = gson.toJson(data)
        Log.d(TAG, "6")

        GlobalScope.launch {
            try {
                login()
                sendData( jsonPayload)
                println("HTTP POST Request erfolgreich gesendet")
            } catch (e: Exception) {
                println("Fehler beim Senden des HTTP POST Requests: ${e.message}")
            }
        }

    }
    data class DataPoint(val time: Double, val value: Double)

    data class Data(val date: String, val durationInSeconds: Long, val avgPulse: Int, val maxPulse: Int, val minPulse: Int, val avgSpeed: Long, val sumSteps: Int, val pulseData: List<DataPoint>, val speedData: List<DataPoint>)
    data class User(val username: String, val password: String)

    data class TokenContainer(val token:String)
    private fun login(){
        var username="admin@gmail.com";
        var password="hashed";
        val gson = Gson()
        val jsonPayload = gson.toJson(User(username, password));
        val url = URL("http://janikmichael.ddns.net:3001/auth/login")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.doOutput = true

        try {
            val outputStream: OutputStream = BufferedOutputStream(connection.outputStream)
            outputStream.write(jsonPayload.toByteArray(Charsets.UTF_8))
            outputStream.flush()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() } // bitte hier token handeln

                val jsonResponse = gson.fromJson(response, TokenContainer::class.java)
                val token = jsonResponse.token
                this.accessTokenJWT = token;

                println("Antwort: $response")
            } else {
                // Fehler bei der Anfrage
                val responseMessage = connection.responseMessage
                println("Fehler bei der Anfrage: $responseMessage")
            }
        } catch (e: Exception) {
            // Fehler bei der Verbindung oder der Datenübertragung
            println("Fehler bei der Verbindung oder Datenübertragung: ${e.message}")
        } finally {
            connection.disconnect()
        }

    }
    private fun sendData(jsonPayload: String) {
        val url = URL("http://janikmichael.ddns.net:3001/app/trainings")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.setRequestProperty("Authorization", "Bearer "+this.accessTokenJWT)
        connection.doOutput = true

        try {
            val outputStream: OutputStream = BufferedOutputStream(connection.outputStream)
            outputStream.write(jsonPayload.toByteArray(Charsets.UTF_8))
            outputStream.flush()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                println("Antwort: $response")
            } else {
                // Fehler bei der Anfrage
                val responseMessage = connection.responseMessage
                println("Fehler bei der Anfrage: $responseMessage")
            }
        } catch (e: Exception) {
            // Fehler bei der Verbindung oder der Datenübertragung
            println("Fehler bei der Verbindung oder Datenübertragung: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun tryStartExercise() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (healthServicesManager.isTrackingExerciseInAnotherApp()) {
                // Show the user a confirmation screen.
                findNavController().navigate(R.id.to_newExerciseConfirmation)
            } else if (!healthServicesManager.isExerciseInProgress()) {
                checkNotNull(serviceConnection.exerciseService) {
                    "Failed to achieve ExerciseService instance"
                }.startExercise()
            }
        }
    }

    private fun pauseResumeExercise() {
        val service = checkNotNull(serviceConnection.exerciseService) {
            "Failed to achieve ExerciseService instance"
        }
        if (cachedExerciseState.isPaused) {
            service.resumeExercise()
        } else {
            service.pauseExercise()
        }
    }

    private fun bindViewsToService() {
        if (uiBindingJob != null) return

        uiBindingJob = viewLifecycleOwner.lifecycleScope.launch {
            serviceConnection.repeatWhenConnected { service ->
                // Use separate launch blocks because each .collect executes indefinitely.
                launch {
                    service.exerciseState.collect {
                        updateExerciseStatus(it)
                    }
                }
                launch {
                    service.latestMetrics.collect {
                        it?.let { updateMetrics(it) }
                    }
                }
                launch {
                    service.exerciseLaps.collect {
                        updateLaps(it)
                    }
                }
                launch {
                    service.activeDurationCheckpoint.collect {
                        // We don't update the chronometer here since these updates come at irregular
                        // intervals. Instead we store the duration and update the chronometer with
                        // our own regularly-timed intervals.
                        activeDurationCheckpoint = it
                    }
                }
            }
        }
    }

    private fun unbindViewsFromService() {
        uiBindingJob?.cancel()
        uiBindingJob = null
    }

    private fun updateExerciseStatus(state: ExerciseState) {
        val previousStatus = cachedExerciseState
        if (previousStatus.isEnded && !state.isEnded) {
            // We're starting a new exercise. Clear metrics from any prior exercise.
            resetDisplayedFields()
        }

        if (state == ExerciseState.ACTIVE && !ambientController.isAmbient) {
            startChronometer()
        } else {
            stopChronometer()
        }

        updateButtons(state)
        cachedExerciseState = state
    }

    private fun updateButtons(state: ExerciseState) {
        binding.startEndButton.setText(if (state.isEnded) R.string.start else R.string.end)
        binding.startEndButton.isEnabled = true
        binding.pauseResumeButton.setText(if (state.isPaused) R.string.resume else R.string.pause)
        binding.pauseResumeButton.isEnabled = !state.isEnded
    }

    private fun updateMetrics(latestMetrics: DataPointContainer) {
        this.latestMetrics = latestMetrics;
        latestMetrics.getData(DataType.HEART_RATE_BPM).let {
            if (it.isNotEmpty()) {
                binding.heartRateText.text = it.last().value.roundToInt().toString()
                if(this.timeSinceBootInMillis == 0L){
                    this.timeSinceBootInMillis = it.last().timeDurationFromBoot.toMillis();
                }
                var time = (it.last().timeDurationFromBoot.toMillis()-timeSinceBootInMillis)/1000.0;
                if(this.pulseData.isEmpty() || time-this.pulseData.last().time>0.5)
                    this.pulseData.add(DataPoint(time, it.last().value));
                else
                    this.pulseData.add(DataPoint(time+1, it.last().value));
            }
        }
        latestMetrics.getData(DataType.STEPS_PER_MINUTE).let {
            if (it.isNotEmpty()) {
                if(this.timeSinceBootInMillis == 0L){
                    this.timeSinceBootInMillis = it.last().timeDurationFromBoot.toMillis();
                }
                var time = (it.last().timeDurationFromBoot.toMillis()-timeSinceBootInMillis)/1000.0;
                if(this.speedData.isEmpty() || time-this.speedData.last().time>0.5)
                    this.speedData.add(DataPoint(time, it.last().value.toDouble()));
                else
                    this.speedData.add(DataPoint(time+1, it.last().value.toDouble()));
            }
        }
        latestMetrics.getData(DataType.STEPS_PER_MINUTE_STATS).let {
            if (it!=null) {
                avgSpeed = it.average
            }
        }
        latestMetrics.getData(DataType.HEART_RATE_BPM_STATS).let {
            if (it!=null) {
                avgPulse= it.average.toInt();
                maxPulse = it.max.toInt();
                minPulse = it.min.toInt();
            }
        }
        latestMetrics.getData(DataType.STEPS_TOTAL).let {
            if (it!=null) {
                sumSteps = it.total.toInt();
            }
        }
        latestMetrics.getData(DataType.DISTANCE_TOTAL)?.let {
            binding.distanceText.text = formatDistanceKm(it.total)
        }
        latestMetrics.getData(DataType.CALORIES_TOTAL)?.let {
            binding.caloriesText.text = formatCalories(it.total)
        }

    }

    private fun updateLaps(laps: Int) {
        binding.lapsText.text = laps.toString()
    }

    private fun startChronometer() {
        if (chronoTickJob == null) {
            chronoTickJob = viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        delay(CHRONO_TICK_MS)
                        updateChronometer()
                    }
                }
            }
        }
    }

    private fun stopChronometer() {
        chronoTickJob?.cancel()
        chronoTickJob = null
    }

    private fun updateChronometer() {
        // We update the chronometer on our own regular intervals independent of the exercise
        // duration value received. If the exercise is still active, add the difference between
        // the last duration update and now.
        val duration = activeDurationCheckpoint.displayDuration(Instant.now(), cachedExerciseState)
        binding.elapsedTime.text = formatElapsedTime(duration, !ambientController.isAmbient)
    }

    private fun resetDisplayedFields() {
        getString(R.string.empty_metric).let {
            binding.heartRateText.text = it
            binding.caloriesText.text = it
            binding.distanceText.text = it
            binding.lapsText.text = it
        }
        binding.elapsedTime.text = formatElapsedTime(Duration.ZERO, true)
    }

    // -- Ambient Mode support

    private fun setAmbientUiState(isAmbient: Boolean) {
        // Change icons to white while in ambient mode.
        val iconTint = if (isAmbient) {
            Color.WHITE
        } else {
            resources.getColor(R.color.primary_orange, null)
        }
        ColorStateList.valueOf(iconTint).let {
            binding.clockIcon.imageTintList = it
            binding.heartRateIcon.imageTintList = it
            binding.caloriesIcon.imageTintList = it
            binding.distanceIcon.imageTintList = it
            binding.lapsIcon.imageTintList = it
        }

        // Hide the buttons in ambient mode.
        val buttonVisibility = if (isAmbient) View.INVISIBLE else View.VISIBLE
        buttonVisibility.let {
            binding.startEndButton.visibility = it
            binding.pauseResumeButton.visibility = it
        }
    }

    private fun performOneTimeUiUpdate() {
        val service = checkNotNull(serviceConnection.exerciseService) {
            "Failed to achieve ExerciseService instance"
        }
        updateExerciseStatus(service.exerciseState.value)
        updateLaps(service.exerciseLaps.value)

        service.latestMetrics.value?.let { updateMetrics(it) }

        activeDurationCheckpoint = service.activeDurationCheckpoint.value
        updateChronometer()
    }

    inner class AmbientModeHandler {
        internal fun onAmbientEvent(event: AmbientEvent) {
            when (event) {
                is AmbientEvent.Enter -> onEnterAmbient()
                is AmbientEvent.Exit -> onExitAmbient()
                is AmbientEvent.Update -> onUpdateAmbient()
            }
        }

        private fun onEnterAmbient() {
            // Note: Apps should also handle low-bit ambient and burn-in protection.
            unbindViewsFromService()
            setAmbientUiState(true)
            performOneTimeUiUpdate()
        }

        private fun onExitAmbient() {
            performOneTimeUiUpdate()
            setAmbientUiState(false)
            bindViewsToService()
        }

        private fun onUpdateAmbient() {
            performOneTimeUiUpdate()
        }
    }

    private companion object {
        const val CHRONO_TICK_MS = 200L
    }
}
