package com.antigravity.copilot.feature.splash

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Splash lifecycle. State changes only at stage boundaries (start → exit → done), never per
 * frame, so observers don't recompose while the animation runs.
 */
class SplashViewModel(
    val config: SplashConfig = SplashConfig()
) : ViewModel() {
    private val _splashState = MutableStateFlow<SplashState>(SplashState.Initial)
    val splashState: StateFlow<SplashState> = _splashState.asStateFlow()

    private val _isSplashCompleted = MutableStateFlow(false)
    val isSplashCompleted: StateFlow<Boolean> = _isSplashCompleted.asStateFlow()

    /** True once the splash starts fading out (or is done): the dashboard may draw behind it. */
    private val _isExiting = MutableStateFlow(false)
    val isExiting: StateFlow<Boolean> = _isExiting.asStateFlow()

    fun onExitStarted() {
        _isExiting.value = true
    }

    fun onAnimationFinished() {
        if (_isSplashCompleted.value) return
        _isExiting.value = true
        _splashState.value = SplashState.Completed
        _isSplashCompleted.value = true
    }

    fun skipSplash(): Boolean {
        if (!config.allowTapToSkip || _isSplashCompleted.value) {
            return false
        }
        _isExiting.value = true
        _splashState.value = SplashState.Skipped
        _isSplashCompleted.value = true
        return true
    }
}
