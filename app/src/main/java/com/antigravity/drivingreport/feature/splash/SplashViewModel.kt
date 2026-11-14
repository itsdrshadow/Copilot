package com.antigravity.drivingreport.feature.splash

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SplashViewModel(
    val config: SplashConfig = SplashConfig()
) : ViewModel() {
    private val _splashState = MutableStateFlow<SplashState>(SplashState.Initial)
    val splashState: StateFlow<SplashState> = _splashState.asStateFlow()

    private val _isSplashCompleted = MutableStateFlow(false)
    val isSplashCompleted: StateFlow<Boolean> = _isSplashCompleted.asStateFlow()

    private val _exitTransitionProgress = MutableStateFlow(0f)
    val exitTransitionProgress: StateFlow<Float> = _exitTransitionProgress.asStateFlow()

    fun updateProgress(progress: Float) {
        if (_isSplashCompleted.value) return
        val clamped = progress.coerceIn(0f, 1f)
        _splashState.value = SplashState.Running(clamped)
        if (clamped >= 1f) {
            onAnimationFinished()
        }
    }

    fun updateExitProgress(progress: Float) {
        if (_isSplashCompleted.value) return
        _exitTransitionProgress.value = progress.coerceIn(0f, 1f)
    }

    fun onAnimationFinished() {
        if (_isSplashCompleted.value) return
        _exitTransitionProgress.value = 1f
        _splashState.value = SplashState.Completed
        _isSplashCompleted.value = true
    }

    fun skipSplash(): Boolean {
        if (!config.allowTapToSkip || _isSplashCompleted.value) {
            return false
        }
        _exitTransitionProgress.value = 1f
        _splashState.value = SplashState.Skipped
        _isSplashCompleted.value = true
        return true
    }
}
