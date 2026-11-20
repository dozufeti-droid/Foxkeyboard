# FoxKeyboardAI V6.1 — Sinhala quality and script safety fix

## What the user reported

The V6 Gemini Sinhala output sometimes mixed Sinhala with Singlish/Latin,
Hangul, Devanagari, Hebrew/Arabic, and untranslated English tokens, including
`mama heta aniwa`, `연락`, and `their`.

## Changes

- Adds **SinhalaOutputGuard**: Unicode script validation is local and fast.
- Strengthens Sinhala-only translation instructions for both Gemini and GroqCloud.
- Retries **at most one** extra AI call when the first Sinhala result contains
  letters from other scripts. A valid first response still uses one call.
- If the retry also fails, shows an error instead of displaying broken text.
- Does not modify existing typing logic, keyboard UI, or saved API keys.
- New isolated debug APK `.v61test` for testing without replacing V6.

## Quality / latency limitations

- This catches mixed scripts, but CANNOT guarantee Sinhala grammar or meaning.
- A retry may consume additional API quota and increase latency.
- For higher natural-language quality, test available `gemini-3.8-flash` for quality or `gemini-3.5-flash-lite`
  for speed; availability, latency and costs can change.
- The unofficial Google Web Translate mode has script validation but NO AI
  repair retry. Select Gemini / GroqCloud for the repair behavior.
- Test first with the same Korean sentences that previously failed.

## Install using Termux (on V6 branch at e62f2c9 or descendant)

```sh
cd ~/FoxkeyboardAI && git switch -c feature/sinhala-quality-v6-1 && git apply --check ~/storage/downloads/FoxKeyboardAI_Sinhala_Quality_v6_1.patch
cd ~/FoxkeyboardAI && git apply ~/storage/downloads/FoxKeyboardAI_Sinhala_Quality_v6_1.patch && git diff --check
cd ~/FoxkeyboardAI && git add . && git commit -m "Validate and repair corrupted Sinhala translation output" && git push -u origin feature/sinhala-quality-v6-1
```

GitHub Actions will build a SEPARATE debug package. Do not uninstall V5 or V6.
