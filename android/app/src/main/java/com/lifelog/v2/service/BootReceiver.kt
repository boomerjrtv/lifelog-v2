package com.lifelog.v2.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, AssistantService::class.java).apply {
                action = AssistantService.ACTION_START
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
