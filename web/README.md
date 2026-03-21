# Web viewer (Firebase Hosting)

1. Create a Firebase project and enable Firestore.
2. Set `web/firestore.rules`: replace `YOUR_WRITE_KEY` with a long random secret. Use the **same** value as `firebase.writeKey` in Android `local.properties` (see root `local.properties.example`).
3. Set `web/public/index.html`: replace `YOUR_FIREBASE_PROJECT_ID` and `YOUR_FIREBASE_WEB_API_KEY` (Firebase console → Project settings → Web API key).
4. Deploy:

```bash
firebase deploy --only hosting,firestore:rules
```

The Android app publishes documents that include `writeKey`; the public site only reads.
