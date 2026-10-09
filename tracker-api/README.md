# Tracker Hybrid API (optional)

The Android v0.4 beta works with an Android system speech recognizer even without this service. For higher-quality Taglish **expense interpretation**, deploy this lightweight API to Railway.

Required Railway environment variables:

- `OPENAI_API_KEY`: your own OpenAI API key (never put it inside an Android APK).
- `TRACKER_CLIENT_TOKEN`: randomly generated strong secret. Put the matching token into Tracker Settings; treat it as sensitive.
- `PORT`: assigned by Railway automatically.
- `OPENAI_MODEL` (optional): defaults to `gpt-4o-mini`.

Health check: `GET /healthz`. Secured endpoint: `POST /interpret` with `Content-Type: application/json`, `X-Tracker-Token`, and `{"text":"Bumili ako ng snacks, 125 pesos"}`.

The service does not store expense history or audio. It receives **recognized text**, sends it to the model API, validates an expense payload, and returns it. Spoken audio is processed by your Android device's speech recognition provider; that provider may send audio to its own servers. If the backend is unavailable the app falls back to its local expense parser. An API account and usage-based billing are separate from any ChatGPT subscription.

This is a beta architecture. For a commercial release, replace the shared token with per-device accounts/short-lived scoped tokens, and add stronger throttling and monitoring.
