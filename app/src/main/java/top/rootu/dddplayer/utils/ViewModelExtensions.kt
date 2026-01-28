package top.rootu.dddplayer.utils

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel

/**
 * Позволяет использовать getString(R.string.id) прямо внутри AndroidViewModel.
 * Поддерживает форматирование строк (vararg args).
 */
fun AndroidViewModel.getString(@StringRes id: Int, vararg args: Any): String {
    return getApplication<Application>().getString(id, *args)
}