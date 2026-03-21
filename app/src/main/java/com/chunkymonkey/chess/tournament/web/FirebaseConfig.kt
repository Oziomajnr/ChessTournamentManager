package com.chunkymonkey.chess.tournament.web

import com.chunkymonkey.chess.tournament.BuildConfig

/**
 * Firebase project configuration for web publishing.
 *
 * Credentials are read from BuildConfig, which is populated from local.properties
 * at build time. local.properties is gitignored and never checked into VCS.
 *
 * To enable publishing, add these lines to your local.properties:
 *   firebase.projectId=your-project-id
 *   firebase.apiKey=your-web-api-key
 */
object FirebaseConfig {
    val projectId: String get() = BuildConfig.FIREBASE_PROJECT_ID
    val apiKey: String get() = BuildConfig.FIREBASE_API_KEY
    val writeKey: String get() = BuildConfig.FIREBASE_WRITE_KEY

    val isConfigured: Boolean
        get() = projectId.isNotBlank() && apiKey.isNotBlank()

    fun firestoreUrl(documentPath: String): String =
        "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents/$documentPath?key=$apiKey"

    fun webViewerUrl(shareCode: String): String =
        "https://$projectId.web.app/t/$shareCode"
}
