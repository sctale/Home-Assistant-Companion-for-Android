package io.homeassistant.companion.android.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.homeassistant.companion.android.BuildConfig
import io.homeassistant.companion.android.database.settings.SensorUpdateFrequencySetting
import io.homeassistant.companion.android.database.settings.Setting
import io.homeassistant.companion.android.database.settings.SettingsDao
import io.homeassistant.companion.android.database.settings.WebsocketSetting
import io.homeassistant.companion.android.websocket.WebsocketManager
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

@HiltViewModel
class SettingViewModel @Inject constructor(private val settingsDao: SettingsDao, application: Application) :
    AndroidViewModel(application) {

    companion object {
        val DEFAULT_UPDATE_FREQUENCY = SensorUpdateFrequencySetting.NORMAL
        val DEFAULT_WEBSOCKET_SETTING =
            if (BuildConfig.FLAVOR == "full") WebsocketSetting.NEVER else WebsocketSetting.ALWAYS
    }

    suspend fun getSetting(id: Int): Setting {
        var setting = settingsDao.get(id)
        if (setting == null) {
            setting = Setting(id, DEFAULT_WEBSOCKET_SETTING, DEFAULT_UPDATE_FREQUENCY)
            settingsDao.insert(setting)
        }
        return setting
    }

    fun getSettingFlow(id: Int): Flow<Setting> = settingsDao.getFlow(id).onStart {
        emit(getSetting(id))
    }.distinctUntilChanged()

    fun updateWebsocketSetting(id: Int, setting: WebsocketSetting) {
        viewModelScope.launch {
            settingsDao.get(id)?.let {
                it.websocketSetting = setting
                settingsDao.update(it)
            }
            // 用户刚刚改了 WebSocket 模式：这是唯一需要"立刻起跑"的调用点。例行报到（亮灭屏、网络状态
            // 变化、进 App）都走 start() 的 KEEP 路径，不再重置周期排期。
            WebsocketManager.startImmediately(getApplication())
        }
    }

    fun updateSensorSetting(id: Int, setting: SensorUpdateFrequencySetting) {
        viewModelScope.launch {
            settingsDao.get(id)?.let {
                it.sensorUpdateFrequency = setting
                settingsDao.update(it)
            }
        }
    }
}
