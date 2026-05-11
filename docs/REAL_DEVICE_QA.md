# Real Device QA Checklist

## Preconditions
- Install debug or release APK on a physical Android device.
- Ensure internet access for WebView and model download tests.
- Start with a clean install for first-run tests.

## 1. First launch with no model
1. Clear app data or fresh install.
2. Launch app.
3. Verify terminal shows model setup/missing state.
4. Verify Execute is disabled until model is ready.

Expected:
- No crash.
- UI shows first-run model setup guidance.

## 2. Model download
1. Enter a valid model URL (`.litertlm`, `.task`, or `.bin`).
2. Tap `Download`.
3. Observe progress and final ready state.

Expected:
- Progress logs appear.
- Model validates and initializes.
- Terminal shows `Model ready` and `Agent initialized`.

## 3. Invalid model
1. Enter URL to wrong/too-small/corrupt file.
2. Download.

Expected:
- Error state appears.
- No crash.
- Terminal shows `Model invalid` / validation failure.

## 4. Valid model load on relaunch
1. Close app.
2. Reopen app.

Expected:
- Persisted model path is validated.
- Agent initializes automatically if model is valid.

## 5. DuckDuckGo E2E
1. Tap `E2E DDG`.

Expected:
- Loads `https://html.duckduckgo.com/html/`.
- Inputs `MediaPipe LLM`.
- Submits form.
- Finishes without crash.

## 6. Execute user command
1. Enter a normal browser task.
2. Tap `Execute`.

Expected:
- Prompt build, LLM, parse, JS execution logs appear.
- Action executes or clean failure feedback appears.

## 7. Self-healing retry
1. Trigger a task likely to fail (invalid ID path/state mismatch).

Expected:
- Retry logs appear.
- Feedback injected.
- Stops after bounded retries.

## 8. `extract_data` memory save
1. Run flow where agent extracts text from a valid DOM ID.

Expected:
- Terminal shows fact saved.
- No schema/security violations.

## 9. Room memory recall (RAG)
1. After saving facts, run related task.

Expected:
- Prompt metrics show memory facts injected.
- Agent can reuse stored facts.

## 10. `ask_human` pause
1. Trigger ambiguous/CAPTCHA-like case.

Expected:
- Agent enters paused state.
- Warning banner appears.
- Auto execution pauses.

## 11. Resume after human intervention
1. Manually interact with WebView.
2. Tap `Resume`.

Expected:
- DOM resumes and re-extracts.
- Agent continues original task with feedback.

## 12. Clear logs
1. Tap `Clear Logs`.

Expected:
- Terminal buffer resets and continues logging afterward.

## 13. Clear context
1. Tap `Clear Ctx`.

Expected:
- Sliding-window context is cleared.
- Context usage metric updates.

## 14. Clear Room memory
1. Tap `Clear Room`.

Expected:
- Stored extracted facts are deleted.
- Later runs inject fewer/no memory facts.

## 15. Delete model
1. Tap `Delete Model`.

Expected:
- Model file removed.
- Agent becomes not-ready.
- First-run setup state returns.

## 16. WebView navigation
1. Use `Go` with URL and search-like text.

Expected:
- URL normalization works.
- WebView navigates and DOM extraction updates.

## 17. Rotation / lifecycle interruption
1. Rotate device during idle and during running flow.

Expected:
- No crash.
- State remains coherent.

## 18. Background / foreground return
1. Start agent task.
2. Send app to background and return.

Expected:
- No crash.
- Busy/paused state remains coherent.

## 19. Low-memory behavior
1. Run large-page navigation and repeated actions.
2. Optionally trigger OS memory pressure.

Expected:
- No hard crash from agent control flow.
- Bounded retries and safe failure logs.
