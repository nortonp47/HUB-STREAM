package com.claybird.tvhub.voice

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * The Streamer's star button can only open a whole app, and opening Hub always
 * shows its home screen. This tiny app has no screen: assign the star button to
 * it and it jumps straight into Hub's voice search.
 */
class LaunchVoiceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent("com.claybird.tvhub.VOICE_SEARCH").apply {
            component = ComponentName(
                "com.claybird.tvhub",
                "com.claybird.tvhub.VoiceSearchActivity",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Install the Hub app first.", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
