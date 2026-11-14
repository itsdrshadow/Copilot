package com.antigravity.drivingreport.feature.splash

sealed interface SplashState {
    data object Initial : SplashState
    data class Running(val progress: Float) : SplashState
    data object Completed : SplashState
    data object Skipped : SplashState
}
