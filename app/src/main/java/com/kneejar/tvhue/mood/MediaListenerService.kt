package com.kneejar.tvhue.mood

import android.service.notification.NotificationListenerService

/**
 * Does nothing itself. Android only lets an app see what's playing (media sessions)
 * if it has notification access, and that access is granted to this service.
 */
class MediaListenerService : NotificationListenerService()
