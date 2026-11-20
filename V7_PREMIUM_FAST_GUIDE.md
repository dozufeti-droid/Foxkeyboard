# D Key V7 — Premium Fast one-tap update

## Apply safely in Termux

Start from a clean `feature/sinhala-reliability-v6-2` checkout (V6.2):

```sh
cd ~/FoxkeyboardAI
# Ensure you are on V6.2 and working tree is clean before creating V7.
git status --short
git branch --show-current
# From V6.2 only:
git switch -c feature/premium-fast-v7
git apply --check ~/storage/downloads/FoxKeyboardAI_V7_REVIEWED_Final.patch
git apply ~/storage/downloads/FoxKeyboardAI_V7_REVIEWED_Final.patch
git diff --check
```

Commit and push after review (workflow will build a separate `.v7test` debug APK):

```sh
git add .github/workflows/build-fox-apk.yml app/build.gradle \
  app/src/main/res/layout/ai_translation_rows.xml \
  app/src/main/res/drawable/fox_v7_translate_chip.xml \
  app/src/main/java/unicode/sinhala/keyboard/FoxOneTapController.kt \
  app/src/main/java/unicode/sinhala/keyboard/InputMethodService.kt \
  app/src/main/java/unicode/sinhala/keyboard/ui/AiSettingsScreen.kt \
  app/src/main/java/unicode/sinhala/keyboard/ai/FoxQuickTranslator.kt \
  V7_PREMIUM_FAST_GUIDE.md
git diff --cached --check
git commit -m 'V7 premium compact translation bar and fast reliable one-tap'
git push -u origin feature/premium-fast-v7
```

## What changes
- A single, rounded, accessible 48dp-height row with English, Korean, Sinhala buttons (replaces three stacked 32dp rows). Layout IDs are unchanged.
- Sinhala result panel height 112dp instead of 180dp; tap Sinhala again to hide/show the last result in the same active editor without an extra API call.
- Short translation-status text, measured elapsed seconds, visible error toast for EN/KO. No automatic send.
- Guard against late Sinhala translation results being shown after switching apps.
- Allow sufficient generated output for longer Sinhala messages without increasing output generated for short messages.
- Reject foreign-script combining marks as well as foreign letters.
- Fast, targeted Sinhala prompt with one optional targeted repair attempt on contaminated output; no hidden cross-provider fallback.
- Network connect timeout 3.2s, response timeout 9.5s; a timeout is still possible. The app cannot promise a fixed translation time.
- Debug package suffix `.v7test` keeps V6.2 and stable APKs installed separately.
- Existing Singlish/voice/clipboard/settings/ML Kit and long-press WM tool are retained to avoid regressions; this is a focused safe cleanup of **screen clutter**, not an unsafe mass-deletion of dependencies.
- No new runtime library, font, tracking, external asset, API key, disk cache or permission.

## Validate on phone
1. In V7 AI Translation Settings choose Gemini or GroqCloud for both AI Provider and One-tap engine; confirm API key/model and One-tap ON.
2. Copy `그냥 넘어가려 했는데 자꾸 마음에 걸려요.`; tap 🇱🇰, check native Sinhala and elapsed seconds. Tap 🇱🇰 again to hide; tap once more to show instantly (same editor).
3. Copy `제가 연락을 안 드린 건 무례해서가 아니라 방해가 될까 봐 조심스러웠기 때문이에요.` and test 🇱🇰.
4. Type `මම හෙට අනිවාර්යයෙන්ම එනවා කියලා කිව්වේ නැහැ.` and tap 🇰🇷. Ensure only the same unchanged draft is replaced and never auto-sent.
5. Test Gemini API key error, timeout, clipboard changed mid-translation, long-message scroll, themes, Sinhala/Singlish typing, voice, emoji and clipboard.
6. Time at least 10 *distinct uncached* short translations on same Wi-Fi connection and compare median vs V6.2; re-opening the same Sinhala result is intentionally instant.

## Important audit status
- This is a code-reviewed patch, NOT a completed Android/phone acceptance test.
- No on-phone latency benchmark or network-based accuracy benchmark has been run on V7.
- Existing VM-style long-press translator, old opt-out mode, unrelated libraries and legacy code are **not** removed because they have live references and deleting them without full Android regression tests risks breaking the working keyboard.
- The layout change affects ONLY the compact translation row, not a full WM-style theme editor or complete app redesign.
- The 9.5 second read timeout is an error deadline, NOT a predicted speed or hard upper bound: connecting, writing, a repair attempt and UI scheduling add time.
- With Sinhala script rejection an extra AI request may be made. This improves failure handling, not guaranteed meaning accuracy.

## Known limits
- Sinhala still needs an online translation service; network/API latency is not under the app's control.
- Some Sinhala results may contain unnatural phrasing or be rejected as mixed-script. One repair attempt is made only when needed; validation does not prove semantic correctness.
- `wmTranslatePanel` and other historical components remain reachable; remove code/dependencies only after a complete reference audit and a device regression test.
