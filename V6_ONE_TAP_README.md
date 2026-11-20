# FoxKeyboardAI V6 — Fast One-Tap Translation

Base branch: `feature/en-ko-hybrid-v5` at commit `2d82444`.

**What changes**
- Existing 🇬🇧/🇰🇷 row: one tap sends only the requested target to the selected engine, then safely replaces the selected text or current draft's last line. Does not auto-send messages.
- Existing 🇱🇰 button: one tap translates manually copied Korean/English text and shows Sinhala in the existing expandable panel. It does not overwrite an editor or auto-send.
- Keyboard visuals, key layout, voice typing, suggestions and saved API keys remain unchanged.
- Separate V6 debug app id `.v6test`, so V5 and your stable app remain installed.
- No automatic requests during typing, no automatic cross-provider forwarding, no uncontrolled retries.
- One-tap provider is independent of current AI Provider; `Follow AI Provider` preserves old selection. For natural Sinhala/Singlish, manually select `GroqCloud` or `Google Gemini` in AI Settings, and ensure its key/model already exists.
- `Offline English-Korean` uses **already downloaded** ML Kit EN/KO models only; select `Download EN ↔ KO Offline Models` once in AI Settings. It does not download models invisibly and never attempts Sinhala offline.
- Long-press WM-style panel still exists as an optional advanced tool. Toggle One-tap OFF in AI Settings for the original V5 click behaviour.

**Speed choices and limitations**
- Only one requested language per request (unlike the older dual EN + KO output).
- Lower reasoning effort for Groq GPT-OSS models, and no thinking for exact Gemini 2.5 Flash/Flash-Lite models, to reduce unnecessary latency.
- Each online API request has a 3.5s connect / 7.5s read timeout and no automatic retry. This is **not** a guaranteed total response time; network/device/service delays vary and failures are possible.
- Fast short-message prompt is tuned for Korean speech-level consistency, Sinhala idioms, speaker pronouns, questions, negation and Singlish. Output quality still requires real-world testing.
- Google Web's unofficial endpoint can return HTTP 429; V6 cannot remove its rate limit.
- Quick mode is intended for messages up to 2000 chars. For long incoming copied messages, disable One-tap and use the existing V5 Sinhala long-message flow.

**Safe install (Termux)**
1. On your current clean `feature/en-ko-hybrid-v5` branch, `git switch -c feature/fox-one-tap-v6`.
2. `git apply --check ~/storage/downloads/FoxKeyboardAI_Fast_OneTap_v6.patch`
3. `git apply ~/storage/downloads/FoxKeyboardAI_Fast_OneTap_v6.patch`
4. `git diff --check && git status --short`
5. The patch already registers the V6 branch in GitHub Actions. Commit and `git push -u origin feature/fox-one-tap-v6` so GitHub Actions can compile and upload a test APK.

**Not verified**: Android compilation and actual phone speed cannot be verified in this container; use GitHub Actions and test real chat sentences. Back up working branches before editing. GPL/MIT licence obligations for existing source remain applicable.
