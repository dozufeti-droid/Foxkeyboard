# FoxKeyboardAI focused EN/KO hybrid translate V5

Base: `feature/google-web-translate-v2` at commit `1421d88`. V5 includes the
V4 capabilities. **Do not apply V3 or V4 first.** Long-press an existing
English/Korean/Sinhala translation row to open the optional compact tool.
Short taps, layout, voice, suggestions and saved API-key/model data are not
replaced. A separate Git branch and install-side backup are recommended.

## Translation modes

- **Sinhala/Singlish → English or Korean**: Online; currently selected and
  configured Gemini/GroqCloud first. Google Web is used when no AI provider is
  configured/selected; its unofficial endpoint may return HTTP 429.
- **English ↔ Korean**: Tap `↓ Offline` in the translate panel once while online
  to download the two language models. Select `On device` to translate without
  internet, or `Automatic` to prefer already-downloaded local models.
- **Automatic**: Never downloads silently. For Sinhala it takes the online path
  exactly once, and for EN/KO it tries installed offline models first, then
  online. This fixes the double online attempt possible in V4.
- **On device**: English/Korean only. No offline Sinhala/Singlish translation;
  those inputs require a separate supported local translation engine. No cloud
  model downloaded onto the device automatically acquires Sinhala support.
- **Google 429**: Respect the provider's Retry-After seconds when available,
  and pause further requests. This is not a rate-limit bypass. Models on device
  do not call the unofficial Google Web endpoint for translations.
- **Quality AI**: explicit 4th engine choice, uses a saved Gemini/GroqCloud
  key and the dedicated English/Korean chat translator. For romanized Sinhala
  choose the Sinhala source explicitly. Accuracy depends on the AI model.
- No repeated automatic requests per keystroke. Translate is user initiated.

## Safety, compatibility, and testing

- Input only sent to an external service upon Translate tap (or existing legacy
  buttons). Downloading an ML Kit model sends a model download request, not
  user text. The optional panel never sends a chat message automatically.
- Insert refuses to replace a selected text range. Replace requires the same
  editor and unchanged original text. Edits invalidate previous results.
- Panel and normal keyboard rows use the existing view: no change to the base
  keyboard XML, colors, or key arrangement; long-press UI is compact.
  Debug APK uses applicationId suffix `.v5test` and installs alongside the
  original FoxKeyboardAI. Its saved AI keys are separate; test apps require
  configuring their own provider. Release package ID is unchanged.
- ML Kit dependencies require Android API 23+. The app's original minimum API
  21 is raised to API 23. Existing devices below Android 6 are excluded.
- This is **NOT** WM Keyboard's entire Bergamot/NLLB/offline or multi-provider
  implementation. WM Keyboard MIT licensed code was studied; the panel is a
  separate compatible implementation, not a byte-identical copy.
- No Android SDK is installed in the patch authoring container; Kotlin/Android
  compilation and phone runtime are **not yet validated**. Build via the
  existing GitHub Actions workflow on a separate branch before installation.
- The existing app uses Google and third-party AI APIs; each service's limits,
  availability and privacy terms apply. Neither "zero rate limits" nor perfect
  translation accuracy can be guaranteed.

Suggested smoke tests: type Sinhala and Singlish, normal English/Korean key
layout, voice input, all saved API keys, long-press Translate tool, paste,
model download, airplane-mode EN↔KO, selected-text Insert/Replace and 429
fallback. For Sinhala/Singlish test idioms, pronouns, Korean honorifics, and
short/incomplete text. The device's speed and model availability matter.
