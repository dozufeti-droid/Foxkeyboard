# FoxKeyboardAI V6.2: Sinhala translation reliability fix

## What is fixed

- Separates the Korean/English -> Sinhala instruction from the English/Korean translation instruction so unrelated Singlish examples cannot leak into Sinhala output.
- Adds explicit examples for natural Sinhala nuances, including uncertainty and non-invented listeners.
- Continues to reject mixed-script output; on failure retries **once** with a stronger Sinhala-only instruction. No extra network request for a valid first translation.
- Makes the UI distinguish a twice-rejected mixed-script translation from API key, model, HTTP limit and timeout errors.
- Does not send a message, automatically change AI providers, or alter API keys.

## Limits

- Unicode checks cannot prove translation meaning is correct. Natural-language quality requires phone tests with the chosen Gemini model.
- Rejected outputs will still happen occasionally: no model can guarantee 100% correct Sinhala. When the second answer is invalid, the user gets a clear message instead of corrupted text.
- A second API request, only after invalid output, may increase delay. Normal successful translations use a single request.

## Installation from V6.1

1. Keep the V6.1 branch intact. On a clean `feature/sinhala-quality-v6-1` checkout, create `feature/sinhala-reliability-v6-2`.
2. Run `git apply --check FoxKeyboardAI_Sinhala_Reliability_v6_2.patch` and `git apply ...` for the patch.
3. Commit, push, and run GitHub Actions for the new branch (workflow trigger included if necessary).
4. Install V6.2 as a separate test app (the original release, V5, V6 and V6.1 stay installed). Configure the Gemini key and model again in this new test app.
5. Test the previously failing Korean sentences and compare naturalness, script purity, and latency.

No API key should be shared in screenshots, command logs, or chats.
