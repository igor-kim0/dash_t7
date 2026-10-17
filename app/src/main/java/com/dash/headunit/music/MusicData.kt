package com.dash.headunit.music

import android.graphics.Bitmap

/** Снимок текущей media-сессии (BT-аудио, плееры). */
data class MusicData(
    val title: String?,
    val artist: String?,
    val album: String?,
    val art: Bitmap?,
    /** Источник: название пакета сессии (напр. bluetooth-стек или плеер). */
    val sourceLabel: String?,
    val playing: Boolean,
    /** Позиция/длительность для прогресса, мс (-1 если неизвестно). */
    val positionMs: Long = -1,
    val durationMs: Long = -1
)
