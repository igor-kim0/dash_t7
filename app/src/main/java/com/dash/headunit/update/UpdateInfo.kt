package com.dash.headunit.update

/**
 * Информация о свежем релизе из GitHub Releases.
 *
 * @param versionName версия вида X.Y.Z (с тега releases/latest, без префикса v).
 * @param versionCode версия-код, вычисленная из versionName (X·10000 + Y·100 + Z).
 * @param apkUrl прямая ссылка на APK-ассет релиза.
 * @param changelog текст релиза (release notes) или null.
 * @param releaseNotesUrl ссылка на страницу релиза на GitHub.
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val changelog: String?,
    val releaseNotesUrl: String?
) {
    /** Актуально ли это обновление для текущей версии-кода приложения. */
    fun isNewerThan(currentVersionCode: Int): Boolean = versionCode > currentVersionCode
}
