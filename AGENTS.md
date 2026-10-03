# FileSwitch development rules

- This is a native Android project. Keep the app interface and conversion logic in Android source and resources.
- Keep conversions local and genuine. Detect file signatures and enforce file and image size limits before decoding.
- Use Android's system document picker and `ContentResolver`; do not request broad storage permissions.
- Grant access to temporary output files only to the app selected for sharing or opening.
- Keep history to file names and output formats; do not persist file bytes.
- Keep the interface touch friendly, accessible and written in plain language.
- Prefer Android platform APIs over additional dependencies. Do not add telemetry or paid services.
