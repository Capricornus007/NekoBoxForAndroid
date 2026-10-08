package main

// Proton 的「人類驗證」（human verification，API 錯誤碼 9001）協定。2026-10-08 用
// 公開來源交叉驗證得來，以下每條都附出處，將來改程式時先重新核對再動：
//
//  1. 觸發：任何 Proton API 呼叫都可能回 HTTP 422 +
//     {"Code":9001,"Error":"...","Details":{"HumanVerificationToken":"...","HumanVerificationMethods":["captcha",...]}}
//     出處：ProtonMail/protoncore_android 的 human-verification/README.md（官方文件，
//     連範例 JSON 都有）、WebClients 的 packages/shared/tests/unauthApi/humanVerification.spec.ts
//     （同檔名 key 的假資料）。
//  2. 解法只有「把 Details.HumanVerificationToken 丟給驗證頁面」這一條路，頁面解完回
//     一個**複合 token**，格式是 `<HumanVerificationToken>:<驗證結果>`。證據是正式端點
//     回傳的 HTML 本身：GET <api>/core/v4/captcha?Token=<hv>&ForceWebMessaging=1 的
//     inline JS 寫 `function sendToken(responseRaw){ var response = captchaToken + ':' + responseRaw; ... }`
//     （2026-10-08 實測 api.protonmail.ch 與 verify-api 站台，拿到 88KB 的 HTML，含這段）。
//  3. 交回方式：重試原本那個請求時帶兩個標頭
//     x-pm-human-verification-token: <複合 token>
//     x-pm-human-verification-token-type: <使用的方法，ex. captcha>
//     出處：protoncore_android human-verification/README.md 的「API Headers」段、
//     WebClients packages/shared/lib/fetch/headers.ts 的 getVerificationHeaders()、
//     go-proton-api master 的 hv.go（hvPMTokenHeaderField / hvPMTokenType）。
//  4. **沒有可程式化验證的路**。三條獨立證據：
//     (a) go-proton-api（Proton 自己的 Go SDK，含最新 master）關於 captcha 只有一個
//         GetCaptcha()，它回的是 HTML 頁面（不是圖），而且 master 加的 HV 支援只有
//         「把解好的 token 附在請求上」（APIHVDetails / addHVToRequest /
//         NewClientWithLoginWithHVToken），沒有任何解題端點。
//     (b) Proton 自己的 Android 函式庫（protoncore_android 的 human-verification 模組）
//         在 Android 上就是用 WebView：HV3DialogFragment.kt `addJavascriptInterface(
//         VerificationJSInterface(), "AndroidInterface")`，WebView 載
//         `https://verify.proton.me/?embed=true&token=<hv>&methods=<csv>&theme=1|2`，
//         頁面則透過 WebClients applications/verify 的 broadcast.ts 判斷
//         `typeof window.AndroidInterface !== 'undefined'` 就呼叫
//         `AndroidInterface.dispatch(JSON)`，內容是
//         {"type":"HUMAN_VERIFICATION_SUCCESS","payload":{"token":"<複合>","type":"captcha"}}。
//     (c) 社群反解出来的 captcha/v1/api/init、/validate 這組非公開端點，對
//         api.protonmail.ch 實測回 404（{"Code":404,"Error":"Path not found"}），而且
//         公开的「解題機」是 OpenCV 認圖 + 假滑鼠軌跡（AzureFlow/proton-poc、
//         ahmedmani/proton-captcha-solver），本質是绕過反機器人、上游隨時改。產品裡不能靠它。
//     所以本檔實作的是「官方那條 WebView 路」：sidecar 只負責取出 challenge、組 URL、
//     正規化解好的 token、把它附在重試請求上；畫面交給 Kotlin 的 WebView。
//  5. 另有 POST <api>/core/v4/verification/captcha/<hvToken>，body {"CaptchaToken":"<複合>"}，
//     用在「使用者在外部瀏覽器解」的情境（WebClients applications/verify/src/app/Verify.tsx
//     的 submitExternalCaptcha，非 embed 時才呼叫，把成果兌換成可重試的憑證）。App 內
//     WebView 解完不需要這一步，所以只有 captcha-solve 的 submit 選填欄位會用到它。

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
)

const (
	hvAPIErrorCode = 9001

	// 重試時附的兩個標頭，名字取自 protoncore_android README 與 WebClients headers.ts。
	hvTokenHeader = "x-pm-human-verification-token"
	hvTypeHeader  = "x-pm-human-verification-token-type"

	hvCaptchaPath = "/core/v4/captcha"
	hvSubmitPath  = "/core/v4/verification/captcha/"

	// hvVerifyApp 是 Proton 的驗證網頁應用（官方 Android 端預設值）。出處：
	// protoncore_android configuration/data/.../EnvironmentConfiguration.kt:38-39
	// `hv3Host = "verify.$host"` / `hv3Url = "https://$hv3Host"`，host 預設 proton.me，
	// 同檔的 androidTest 也斷言 https://verify.proton.me。
	hvVerifyApp = "https://verify.proton.me"

	hvMethodCaptcha = "captcha"

	// UI 用的結構性 reason，Kotlin 靠它選字串與流程，不比對 Proton 的英文訊息。
	reasonCaptchaRequired = "captcha-required"
	reasonCaptchaRejected = "captcha-rejected"
)

// hvChallenge 是 422/9001 的 Details 内容：一組可用方法加上一個起點 token。
type hvChallenge struct {
	Token   string
	Methods []string
	Title   string
}

// hvSolution 是解完之後要附在重試請求上的東西。
type hvSolution struct {
	Token string
	Type  string
}

// hvErrorBody 是 Proton 錯誤回應的骨架。注意訊息欄位叫 Error（不是 Message），
// go-proton-api v0.4.0 的 APIError 也只讀這一個，而且**整個丢掉 Details**，
// 所以我們才要在 hvTransport 裡自己截一份原始回應。
type hvErrorBody struct {
	Code    int    `json:"Code"`
	Message string `json:"Error"`
	Details struct {
		Token   string   `json:"HumanVerificationToken"`
		Methods []string `json:"HumanVerificationMethods"`
		Title   string   `json:"Title"`
	} `json:"Details"`
}

// parseHVChallenge 只在「真的是 9001 而且 Details 给了起點 token」時回非 nil。
// 比對 Code 而不是訊息字樣：同一句話可能挂在別的 Code 下（見 login.go 的兜底），
// 但反過來拿別的 Code 當驗證請求會讓我們附錯標頭。
func parseHVChallenge(body []byte) *hvChallenge {
	if len(body) == 0 {
		return nil
	}
	var parsed hvErrorBody
	if err := json.Unmarshal(body, &parsed); err != nil {
		return nil
	}
	if parsed.Code != hvAPIErrorCode || parsed.Details.Token == "" {
		return nil
	}
	return &hvChallenge{
		Token:   parsed.Details.Token,
		Methods: parsed.Details.Methods,
		Title:   parsed.Details.Title,
	}
}

// hvURLFromMessage 是備用路：Proton 有时把驗證連結直接寫進 Error 訊息（社群常見的那種
// 「去這個連結解一次」）。訊息裡沒有 verify.proton.me 的 token 參數時回空字串。
func hvURLFromMessage(message string) string {
	for _, token := range strings.FieldsFunc(message, func(r rune) bool {
		return r == ' ' || r == '\n' || r == '\t' || r == '"' || r == '\''
	}) {
		if !strings.HasPrefix(token, "http") {
			continue
		}
		parsed, err := url.Parse(strings.TrimRight(token, ".,;"))
		if err != nil || parsed.Host == "" {
			continue
		}
		if !strings.HasSuffix(parsed.Hostname(), "proton.me") && !strings.HasSuffix(parsed.Hostname(), "protonmail.com") {
			continue
		}
		if parsed.Query().Get("token") == "" && parsed.Query().Get("Token") == "" {
			continue
		}
		return parsed.String()
	}
	return ""
}

// verifyAppURL 組出 Proton 官方 Android 客戶端在 WebView 裡載的那個網址。參數順序與
// 值取自 protoncore_android HV3DialogFragment.kt buildUrl()：embed=true、token、methods
// （逗號串）、theme（1 深、2 淺）。
func verifyAppURL(challenge *hvChallenge, dark bool) string {
	if challenge == nil || challenge.Token == "" {
		return ""
	}
	methods := challenge.Methods
	if len(methods) == 0 {
		methods = []string{hvMethodCaptcha}
	}
	theme := "2"
	if dark {
		theme = "1"
	}
	query := url.Values{}
	query.Set("embed", "true")
	query.Set("token", challenge.Token)
	query.Set("methods", strings.Join(methods, ","))
	query.Set("theme", theme)
	return hvVerifyApp + "/?" + query.Encode()
}

// captchaPageURL 是第二條路：不繞 verify.proton.me，直接用 API 自己的驗證頁面。
// ForceWebMessaging=0 才會走原生橋（實測：帶 1 時 inline JS 是
// `var forceWebCommunication = true`，只認 window.parent.postMessage；帶 0 或省略時是
// false，此时页面在偵測到 AndroidInterface 後改呼叫 AndroidInterface.receiveResponse）。
// 它只需要 API 網域可达，verify.proton.me 被擋時還有這條。
func captchaPageURL(apiURL string, challenge *hvChallenge, dark bool) string {
	if challenge == nil || challenge.Token == "" {
		return ""
	}
	if apiURL == "" {
		apiURL = defaultProtonAPIURL
	}
	target := strings.TrimRight(apiURL, "/") + hvCaptchaPath
	query := url.Values{}
	query.Set("Token", challenge.Token)
	query.Set("ForceWebMessaging", "0")
	if dark {
		query.Set("Dark", "true")
	}
	return target + "?" + query.Encode()
}

// composeCaptchaToken 把驗證頁面交回的東西正規化成「複合 token」。
// 頁面自己就交 `<起點>:<結果>`（見上方 sendToken），這裡只是修補拿不到前綴的情况。
// 只在 captcha 方法上做：email/sms 的前綴是聯絡方式本身（WebClients helper.ts 的
// getFormattedCode 用 verificationModel.value），不該被我們動到。
func composeCaptchaToken(kind, start, response string) string {
	start = strings.TrimSpace(start)
	response = strings.TrimSpace(response)
	if typeOrDefault(kind) != hvMethodCaptcha {
		return response
	}
	switch {
	case response == "":
		return ""
	case start == "":
		return response
	case strings.HasPrefix(response, start+":"):
		return response
	case strings.Contains(response, ":"):
		// 已經带某個前綴，猜不動就不改。
		return response
	default:
		return start + ":" + response
	}
}

// hvTransport 是一個 http.RoundTripper 包裝，做兩件事：
//  1. 出站：把解好的兩個標頭附在 /auth/ 請求上。
//  2. 入站：替 4xx 回應攔截一份原始 body，因為 go-proton-api v0.4.0 把 Details 丢了，
//     而它的 AddPostRequestHook 又排不到（manager_builder.go 把 catchAPIError 註冊为
//     第一個 OnAfterResponse，resty v2.7 的迴圈在中間件回報錯誤時就 break，我們的鉦子
//     永遠看不到 422）。在傳輸層動作就不受中間件順序影響。
type hvTransport struct {
	base http.RoundTripper

	attach *hvSolution

	// challenge 指向呼叫方的變數，最後一次看到的 9001 challenge 寫在那裡。
	// resty 會重試，所以是「最後一次」而不是「第一次」。
	challenge **hvChallenge
}

func (t *hvTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	base := t.base
	if base == nil {
		base = http.DefaultTransport
	}
	if t.attach != nil && t.attach.Token != "" && strings.HasPrefix(req.URL.Path, "/auth/") {
		clone := req.Clone(req.Context())
		clone.Header.Set(hvTokenHeader, t.attach.Token)
		clone.Header.Set(hvTypeHeader, typeOrDefault(t.attach.Type))
		req = clone
	}

	res, err := base.RoundTrip(req)
	if err != nil || res == nil {
		return res, err
	}
	if t.challenge == nil || res.StatusCode < 400 || res.StatusCode >= 500 || res.Body == nil {
		return res, nil
	}

	body, readErr := io.ReadAll(res.Body)
	closeErr := res.Body.Close()
	if readErr != nil {
		return nil, readErr
	}
	if closeErr != nil {
		return nil, closeErr
	}
	if found := parseHVChallenge(body); found != nil {
		*t.challenge = found
	}
	// 原樣交還給 resty：它要能再讀一次同一份 body。
	res.Body = io.NopCloser(bytes.NewReader(body))
	res.ContentLength = int64(len(body))
	return res, nil
}
