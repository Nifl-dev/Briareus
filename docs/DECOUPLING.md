# Decoupling: Chinese services, China front-ends, and bilingual prompt text

**Status:** in progress — Step 1 scoped, not yet applied
**Branch base:** `main` @ `0c57abc` (clean, level with `origin/main`)
**Author of decisions:** user. **Measured by:** Operit, 2026-11-24.

This document exists so the decoupling work is unambiguous. It records the policy, the measured
surface, the exact targets, and the hazards. Read §1 and §7 before changing anything.

---

## 1. Policy (user-stated, final)

| Decision | Detail |
|---|---|
| **Keep** Chinese model providers that are widely used outside China | DeepSeek, Qwen, Kimi (Moonshot), MiniMax, MiMo |
| **Drop** the Operit marketplace | Decoupling from `api.operit.app` / `static.operit.app` distribution |
| **Drop** Chinese services *not* primarily marketed outside China | CN-only endpoints, CN GitHub accelerators, ModelScope |
| **Goal** | No Chinese text in code, UI strings, or prompts; no runtime dependency on CN-only infrastructure |

Provider *integrations* are **kept**. What is under review is the **endpoint defaults and front-end
links** those integrations point at.

---

## 2. Finding: several providers ship CN default endpoints, but international hosts exist

All declared in `data/collects/ApiProviderConfigCollect.kt`. **Headline: no provider in the keep-list
has to be dropped.** Every one has a reachable non-CN host — the code either points at the CN host
by `defaultApiEndpoint`, or lists the international host only as a selectable option.

DNS + HTTP reachability was verified 2026-11-24 (a 401/404 on `/` still proves the host is live):

| Provider | `defaultApiEndpoint` shipped | International option in code? | Live check |
|---|---|---|---|
| DEEPSEEK | `api.deepseek.com` | n/a — already global | ✅ global |
| MOONSHOT (Kimi) | `api.moonshot.cn` ❌ | ✅ `api.moonshot.ai` labelled *International* | `.ai` → HTTP 200 |
| ZHIPU | `open.bigmodel.cn` ❌ | ✅ `api.z.ai` labelled *International* | `api.z.ai` → HTTP 301 |
| MINIMAX | `api.minimaxi.com` ❌ | ✅ `api.minimax.io` labelled *International* | `api.minimax.io` → HTTP 404 (alive) |
| ALIYUN (Qwen) | `dashscope.aliyuncs.com` ❌ | ❌ **not wired** | `dashscope-intl.aliyuncs.com` → HTTP 404 |
| DOUBAO | `ark.cn-beijing.volces.com` ❌ | ❌ **not wired** | `ark.ap-southeast.volces.com` → HTTP 401 |
| SILICONFLOW | `api.siliconflow.cn` ❌ | ❌ **not wired** | `api.siliconflow.com` → HTTP 404 |
| MIMO | `api.xiaomimimo.com` | ⚠️ ambiguous | resolves to `20.157.221.14` (Azure) |
| BAIDU | `aip.baidubce.com` ❌ | ❌ | — |
| XUNFEI | `spark-api-open.xf-yun.com` ❌ | ❌ | — |
| BAICHUAN | `api.baichuan-ai.com` ❌ | ❌ | — |
| IFLOW | `apis.iflow.cn` ❌ | ❌ | — |
| ALIPAY_BAILING | `api.tbox.cn` ❌ | ❌ | — |

**The fix is two different sizes of work, and they should not be one commit:**

- **Three providers just need a corrected default** (MOONSHOT, ZHIPU, MINIMAX). The international
  endpoint is already in `endpointOptions` with an explicit `"International…"` label — so the
  existing UI already tells users a global host exists. Swapping `defaultApiEndpoint` is a one-line
  edit per provider.
- **Three providers need a new option added** (ALIYUN, DOUBAO, SILICONFLOW). Their international
  hosts are confirmed live but absent from `endpointOptions`, so users have no way to pick them.

**Correction to an earlier claim of mine:** I previously described MiniMax as "`minimaxi.com` CN,
`minimax.io` global". Both hosts in that file are CN-registered; the *international* host is
`api.minimax.io`. Similarly **MiMo is not clearly CN-only** — `api.xiaomimimo.com` resolves to a
**Microsoft Azure** IP (`20.157.221.14`), so the endpoint is almost certainly globally reachable.
Hosting region for MiMo should be confirmed before treating it as a CN endpoint.

**Still needs a policy decision (not guessed here):** BAIDU, XUNFEI, BAICHUAN, IFLOW, ALIPAY_BAILING
have no international host at all. Under the stated policy these are drop candidates, but they were
not in the user's explicit keep-list either — so this is a decision, not an inference.

Note the asymmetry: **an integration can be legitimate while its default host is not.** Changing a
default endpoint is a config edit; removing a provider is an API-surface removal.

---

## 3. Finding: GitHub downloads route through 35 China accelerator proxies

`util/GithubReleaseUtil.kt` defines `GITHUB_MIRRORS` — 35 entries, the great majority CN-operated
GitHub accelerators (`ghproxy.*`, `ghfast.top`, `gitproxy.mrhjx.cn`, `down.npee.cn`,
`gh.xx9527.cn`, `hub.gitmirror.com`, …), plus one Cloudflare Worker.

The file *does* probe mirrors and prefers the official URL (`GITHUB_SOURCE_KEY` is tried first, with
`Range`-request and HTML-masquerade detection). So the official path exists — but the mirror list is
CN-centric and each mirror is a third party that can observe or modify downloaded APK artefacts.

**Recommendation:** keep the official-URL path, drop or heavily prune the CN mirror list. This is
both a decoupling and a supply-chain change, so it deserves its own review rather than riding along
with text translation.

---

## 4. Finding: MNN model downloads use ModelScope (`modelscope.cn`)

`data/mnn/MnnModelDownloadManager.kt` builds `https://modelscope.cn/api/v1/models/…` (lines ~296,
~704). No Hugging Face equivalent is wired in this file. On-device MNN model download therefore
depends on a CN host.

**Recommendation:** out of scope for Step 1, but must be tracked — local-model download will fail or
be slow for non-CN users.

---

## 5. How the bilingual prompt system actually works

Critical to understanding Step 1. There is **no build flavor** (`app/build.gradle.kts` has no
`productFlavors` / `flavorDimensions`). Localisation is a **runtime branch**:

- `LocaleUtils.usesChineseContent(context)` decides language.
- Call sites read `val useEnglish = !LocaleUtils.usesChineseContent(context)` — 8 sites today:
  `EnhancedAIService.kt:3016`, `ConversationService.kt:123,352,561,1164`,
  `ToolExecutionManager.kt:375`, `MemoryLibrary.kt:243,568`, `AIMessageManager.kt:1064`.
- Prompt code then picks a Chinese or English variant.

### Per-file inventory (measured)

| File | Lines | CJK lines | `if(useEnglish)` branches | Machinery |
|---|---|---|---|---|
| `FunctionalPrompts.kt` | 1,353 | 318 | 35 | 7 `_EN` consts, 8 `_CN` consts |
| `SystemPromptConfig.kt` | 713 | 33 | 10 | 3 `_EN` + 4 `_CN` consts, `…En`/`…Cn` function pairs |
| `SystemToolPrompts.kt` | 1,008 | 112 | 1 | 5 `…En` / 5 `…Cn` function pairs |
| `SystemToolPromptsInternal.kt` | 6,005 | 604 | **0** | **none — Chinese only** |

So three of four files already contain complete English versions. Step 1 is largely **deletion of
the Chinese branch plus its plumbing**, not translation.

`SystemToolPromptsInternal.kt` is the exception: 604 CJK lines of tool `description` /
`ToolParameterSchema` text with no English counterpart. That one needs real translation.

### ⚠️ Hazard A — `useEnglish` defaults to `false` (Chinese)

Declarations read `useEnglish: Boolean = false`. A caller that omits the argument gets **Chinese
prompts regardless of device locale**. When collapsing to English-only, do **not** flip a default
from `false` to `true` and call it done — remove the parameter and the branch, or every future call
site inherits an invisible language switch. Verify per call site before editing.

### ⚠️ Hazard B — template token substitution

`SystemPromptConfig.kt` builds prompts via `.replace()` on 6 tokens:
`ACTIVE_PACKAGES_SECTION`, `AVAILABLE_TOOLS_SECTION`, `BEGIN_SELF_INTRODUCTION_SECTION`,
`PACKAGE_SYSTEM_GUIDELINES_SECTION`, `TOOL_USAGE_GUIDELINES_SECTION`, `WORKSPACE_GUIDELINES_SECTION`.

If a Chinese template is deleted while a `.replace()` still targets it, the token silently survives
in the output and the model receives a literal placeholder. Every token must be traced before
removing its template.

### ⚠️ Hazard C — parallel function pairs must be deleted together

In `SystemToolPrompts.kt` and `SystemPromptConfig.kt` the English and Chinese versions are separate
functions (`generateToolsPromptEn` / `generateToolsPromptCn`, `getAllCategoriesEn` / `…Cn`,
`getAvailableToolsEn` / `…Cn`, `getMemoryToolsEn` / `…Cn`, `buildSafBookmarksSectionEn` / `…Cn`).
Each currently has ≥1 call site. Removing one without rewriting its dispatcher breaks the build.

---

## 6. Marketplace is a separate decision from text

Marketplace/package UI carries ~2,962 CJK chars across 27 files. **Do not translate it.** But note
the split:

- MCP pages are ~2,145 of those chars (`MCPConfigScreen.kt` 1,133, `MCPDeployViewModel.kt` 339,
  `MCPViewModel.kt` 253, `MCPServerDetailsHeader.kt` 120, `MCPCommandsEditDialog.kt` 98,
  `MCPEnvironmentVariablesDialog.kt` 92, `MCPServerDetailsDialog.kt` 75, `MCPDeployConfirmDialog.kt` 67,
  `MCPDeployProgressDialog.kt` 67, `MCPInstallProgressDialog.kt` 77, `MCPCategories.kt` 12,
  `MCPServerDetailsTabs.kt` 47, `MCPServerDetailsActions.kt` 8, `MCPServerDetailsContent.kt` 4).
  **MCP is a core interoperable standard, not Chinese-specific** — keep it.
- The market/publish plumbing (`MarketStatsApiService.kt` 93, `ArtifactMarketModels.kt` 35, …) is
  what decoupling targets.

`MarketStatsApiService.kt` is 1,418 lines with 25 `try`/`runCatching` guards, so losing the remote
does not crash — it degrades. Dead-looking UI is a UX problem, not a stability one.

`TagMarketBilingualData.kt` (114 CJK lines) is a **local bundled preset library** (`val
bilingualPresetTags`), *not* the remote marketplace — `TagMarketScreen.kt` contains 0 http refs.
Its Chinese halves are deletion candidates; its English halves stay. Never machine-translate it.

---

## 7. Step 1 — scoped plan

Ordered by risk, lowest first. Each is independently committable.

**1-pre (strongly recommended before any 1a–1c work): swap the three default endpoints.**

Cheapest, highest-value change in the whole decoupling effort, and it is *not* translation work:

| Provider | Change `defaultApiEndpoint` | One-line edit |
|---|---|---|
| MOONSHOT | `api.moonshot.cn` → `api.moonshot.ai` | yes |
| ZHIPU | `open.bigmodel.cn` → `api.z.ai` | yes |
| MINIMAX | `api.minimaxi.com` → `api.minimax.io` | yes |

Rationale: the international endpoint is *already* offered as a labelled option, so the fix does not
remove a capability — it stops the CN host being the silent default. Verify each new default actually
completes a request, not merely that DNS resolves; a 404/301 on `/` only proves the host is live.

**1a. `SystemPromptConfig.kt` (33 CJK lines, 10 branches) — do first.**
Smallest surface, highest branch density, exercises every hazard (tokens, function pairs, defaults).
Proves the pattern. Target: delete `_CN` consts, fold `…Cn` functions, strip `useEnglish`.

**1b. `SystemToolPrompts.kt` (112 CJK lines, 5 function pairs).**
Mechanical once 1a establishes the shape.

**1c. `FunctionalPrompts.kt` (318 CJK lines, 35 branches).**
Largest branch count, but has clean `_EN`/`_CN` const pairs for the summary prompt. Note
`SUMMARY_MARKER_CN`/`_EN` and the four `SUMMARY_SECTION_*` pairs are already split.

**1d. `SystemToolPromptsInternal.kt` (604 CJK lines) — translation, not deletion.**
Defer until 1a–1c land; it is a genuine translation task and the only one.

**Do not** attempt 1a–1d as one commit. Each needs its own verification.

---

## 8. Verification constraint (important)

**This machine cannot build the project.** Measured 2026-11-24:

- No Android SDK installed; `ANDROID_HOME` unset; no `local.properties`.
- RAM: 7 GB total, 0 available. `gradle.properties` sets `org.gradle.jvmargs=-Xmx8192m` — **larger
  than physical RAM**. A build would OOM or swap-thrash.
- Disk: 28 GB free (sufficient, but RAM is the binding constraint).

Consequences:

1. Every change in §7 must be verified by **static reasoning + reference counting**, not compilation,
   unless performed elsewhere.
2. `.github/workflows/android-build.yml` exists — CI is the practical verification vehicle.
3. Better still: the planned Contabo box (6 vCPU / 12 GB) exceeds the local RAM limit and is the
   intended build escape hatch.

**Because of this, code edits in §7 were deliberately not committed in this session.** Writing
uncompilable Kotlin into a repo on a machine that cannot typecheck it trades a real risk for an
apparent speed-up.

---

## 9. Open questions (need user decision)

1. **Qwen / Doubao / MiMo / SiliconFlow** have no non-CN host in code. Keep with CN host, gate, or drop?
2. **GitHub mirror list** — prune to official-only, or keep a small non-CN subset? Supply-chain call.
3. **Marketplace removal** — delete screens outright, or hide behind the existing install-state
   machinery first?
4. **ModelScope** — add a Hugging Face fallback, or accept CN-only for MNN models?
5. Once §7 lands: should `LocaleUtils.usesChineseContent` be removed entirely, or retained for
   *runtime output* language (model reply language) even when prompts are English-only? These are
   currently the same switch and almost certainly should become two.

---

## 10. Change log

- **2026-11-24** — Document created. Audit of endpoints, GitHub mirrors, ModelScope, bilingual
  machinery, and build feasibility completed. Step 1 scoped, not applied.
