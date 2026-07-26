package com.MeshLink.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.RemoteInput
import com.MeshLink.android.ui.NotificationManager
import com.MeshLink.android.mesh.BluetoothMeshService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationReplyReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NotificationReply"
        const val KEY_TEXT_REPLY = "KEY_TEXT_REPLY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val peerId = intent.getStringExtra(NotificationManager.EXTRA_PEER_ID) ?: return
        val replyText = getMessageText(intent)
        
        if (!replyText.isNullOrBlank()) {
            Log.d(TAG, "Received inline reply for $peerId: $replyText")
            // TODO: Inject MessageRouter or BluetoothMeshService properly to send the message.
            // For now, this just captures the text.
        }
    }

    private fun getMessageText(intent: Intent): CharSequence? {
        val remoteInput: Bundle? = RemoteInput.getResultsFromIntent(intent)
        return remoteInput?.getCharSequence(KEY_TEXT_REPLY)
    }
}
