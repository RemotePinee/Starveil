package com.starveil.app.cast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.starveil.app.StarveilApplication
import com.starveil.core.data.cast.CastRepository
import com.starveil.core.model.CastUiStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 通知栏上的两个动作：暂停/继续、断开。 */
class CastActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val repository = (context.applicationContext as? StarveilApplication)?.container?.castRepository ?: return
        val scope = CoroutineScope(Dispatchers.Default)
        when (intent.action) {
            ACTION_TOGGLE -> scope.launch {
                val connected = repository.status.value as? CastUiStatus.Connected ?: return@launch
                if (connected.session.state.name == "Playing") repository.pause() else repository.resume()
            }

            ACTION_STOP -> scope.launch { repository.disconnect() }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.starveil.app.cast.TOGGLE"
        const val ACTION_STOP = "com.starveil.app.cast.STOP"
    }
}
