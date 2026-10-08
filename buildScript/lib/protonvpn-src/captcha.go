package main

// CAPTCHA / 人類驗證的子命令。Kotlin 只跟這層說話，協定細節全部留在 Go：
//
//   captcha-begin  吃 login 回來的 challenge（Details.HumanVerificationToken 與方法
//                  清單），回兩個可載入 WebView 的網址。不碰網路。
//   captcha-solve  吃 WebView 交回的成果，正規化成重試要用的 token/類型對；只有在
//                  「使用者用外部瀏覽器解」時才需要 submit=true 去打兌換端點。
//
// login 本身則多兩個選填輸入欄位（captchaToken/captchaType），帶上它們就是「解完重試」。

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

func typeOrDefault(t string) string {
	if t == "" {
		return hvMethodCaptcha
	}
	return t
}

// normalizeMethods 丢掉 Proton 沒提供或我們根本畫不出來的選項，並在清單空了的時候
// 至少留 captcha：缺欄位時 UI 還是要有可解的東西。
func normalizeMethods(methods []string) []string {
	known := map[string]bool{
		hvMethodCaptcha: true, "email": true, "sms": true,
		"ownership-email": true, "ownership-sms": true,
	}
	var out []string
	for _, m := range methods {
		m = strings.ToLower(strings.TrimSpace(m))
		if m == "" || !known[m] {
			continue
		}
		dup := false
		for _, seen := range out {
			if seen == m {
				dup = true
				break
			}
		}
		if !dup {
			out = append(out, m)
		}
	}
	if len(out) == 0 {
		out = []string{hvMethodCaptcha}
	}
	return out
}

type captchaBeginInput struct {
	// Token 是 9001 回應裡 Details.HumanVerificationToken。
	Token string `json:"token"`
	// Methods 是 Details.HumanVerificationMethods；留空當作只有 captcha。
	Methods []string `json:"methods,omitempty"`
	// Error 是 Proton 的錯誤訊息原文，用來在 Details 缺 token 時撿回官方連結。
	Error  string `json:"error,omitempty"`
	APIURL string `json:"apiURL,omitempty"`
	Dark   bool   `json:"dark,omitempty"`
}

type captchaBeginOutput struct {
	OK      bool     `json:"ok"`
	Token   string   `json:"token,omitempty"`
	Methods []string `json:"methods,omitempty"`
	// SolveURL 是首選：Proton 官方的驗證網頁應用（支援 captcha 以外的方法）。
	SolveURL string `json:"solveURL,omitempty"`
	// CaptchaURL 是備選：API 自己的驗證頁（verify.proton.me 不通時才有用）。
	CaptchaURL string `json:"captchaURL,omitempty"`
	// MessageURL 是 Proton 在訊息裡附上的連結，讓 UI 有東西可以開到瀏覽器。
	MessageURL string `json:"messageURL,omitempty"`
	Error      string `json:"error,omitempty"`
	Reason     string `json:"reason,omitempty"`
}

type captchaSolveInput struct {
	// Token 是起點 token（Details.HumanVerificationToken），不是複合 token。
	Token string `json:"token"`
	// Response 是驗證頁面交回的東西：正常就該是 `<起點>:<結果>`，少了前綴會補。
	Response string `json:"response"`
	// Type 是 WebView 回報的方法，預設 captcha。
	Type string `json:"type,omitempty"`
	// Submit 只有在「於外部瀏覽器解、要先把成果兌換成可重試憑證」時才要 true：
	// POST <api>/core/v4/verification/captcha/<起點>，body {"CaptchaToken":複合}。
	Submit bool `json:"submit,omitempty"`
	// Expired 讓 WebView 能直接把「时限过了」回報成失敗，不用瞞著使用者。
	Expired bool `json:"expired,omitempty"`

	APIURL string `json:"apiURL,omitempty"`
}

type captchaSolveOutput struct {
	OK     bool   `json:"ok"`
	Token  string `json:"token,omitempty"`
	Type   string `json:"type,omitempty"`
	Error  string `json:"error,omitempty"`
	Reason string `json:"reason,omitempty"`
}

func runCaptchaBegin(args []string, stdin io.Reader, stdout io.Writer) int {
	command := flag.NewFlagSet("captcha-begin", flag.ContinueOnError)
	command.SetOutput(stdout)
	if err := command.Parse(args); err != nil {
		return 2
	}

	var in captchaBeginInput
	if err := json.NewDecoder(stdin).Decode(&in); err != nil {
		return emitCaptchaBegin(stdout, captchaBeginOutput{
			Error:  "read stdin: " + err.Error(),
			Reason: "bad-request",
		})
	}

	challenge := &hvChallenge{Token: strings.TrimSpace(in.Token), Methods: in.Methods}
	if challenge.Token == "" {
		// Details 沒給 token 的老 Proton 回覆：只能靠訊息裡的官方連結。
		link := hvURLFromMessage(in.Error)
		if link == "" {
			return emitCaptchaBegin(stdout, captchaBeginOutput{
				Error:  "Proton did not include the verification token",
				Reason: reasonCaptchaRequired,
			})
		}
		return emitCaptchaBegin(stdout, captchaBeginOutput{
			OK: true, Methods: []string{hvMethodCaptcha}, MessageURL: link,
		})
	}

	challenge.Methods = normalizeMethods(challenge.Methods)
	apiURL := in.APIURL
	if apiURL == "" {
		apiURL = defaultProtonAPIURL
	}

	return emitCaptchaBegin(stdout, captchaBeginOutput{
		OK:         true,
		Token:      challenge.Token,
		Methods:    challenge.Methods,
		SolveURL:   verifyAppURL(challenge, in.Dark),
		CaptchaURL: captchaPageURL(apiURL, challenge, in.Dark),
		MessageURL: hvURLFromMessage(in.Error),
	})
}

func runCaptchaSolve(args []string, stdin io.Reader, stdout io.Writer) int {
	command := flag.NewFlagSet("captcha-solve", flag.ContinueOnError)
	command.SetOutput(stdout)
	timeoutSec := command.Int("timeout", 30, "deadline for --submit in seconds")
	if err := command.Parse(args); err != nil {
		return 2
	}

	var in captchaSolveInput
	if err := json.NewDecoder(stdin).Decode(&in); err != nil {
		return emitCaptchaSolve(stdout, captchaSolveOutput{
			Error:  "read stdin: " + err.Error(),
			Reason: "bad-request",
		})
	}
	if in.Expired {
		return emitCaptchaSolve(stdout, captchaSolveOutput{
			Error:  "the verification challenge expired, ask Proton for a new one",
			Reason: "captcha-expired",
		})
	}

	kind := typeOrDefault(in.Type)
	solved := composeCaptchaToken(kind, in.Token, in.Response)
	if solved == "" {
		return emitCaptchaSolve(stdout, captchaSolveOutput{
			Error:  "no CAPTCHA answer to submit",
			Reason: "bad-request",
		})
	}
	out := captchaSolveOutput{OK: true, Token: solved, Type: kind}
	// 只有 captcha 的複合式以起點 token 開頭（驗證頁的 sendToken 就是這麼拼的）。
	// email/sms 那兩條的 token 是「聯絡方式:代碼」（WebClients helper.ts 的
	// getFormattedCode），本來就不含起點 token，不能用同一個判式卡死。
	if kind == hvMethodCaptcha && in.Token != "" && !strings.HasPrefix(solved, in.Token+":") {
		return emitCaptchaSolve(stdout, captchaSolveOutput{
			Error:  "the answer does not belong to this challenge",
			Reason: "captcha-mismatch",
		})
	}
	if !in.Submit {
		return emitCaptchaSolve(stdout, out)
	}

	apiURL := in.APIURL
	if apiURL == "" {
		apiURL = defaultProtonAPIURL
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(*timeoutSec)*time.Second)
	defer cancel()
	if err := submitCaptchaSolution(ctx, apiURL, in.Token, solved); err != nil {
		return emitCaptchaSolve(stdout, captchaSolveOutput{Error: err.Error(), Reason: "captcha-submit-failed"})
	}
	return emitCaptchaSolve(stdout, out)
}

// submitCaptchaSolution 是外部瀏覽器那條路的兌換端點（見檔頭註解 5）。App 內 WebView
// 解完不需要它，所以只有在明確要求時才打。
func submitCaptchaSolution(ctx context.Context, apiURL, start, composite string) error {
	if start == "" {
		return errors.New("submitting an external CAPTCHA answer needs the challenge token")
	}
	payload, err := json.Marshal(map[string]string{"CaptchaToken": composite})
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(
		ctx, http.MethodPost, strings.TrimRight(apiURL, "/")+hvSubmitPath+url.PathEscape(start), bytes.NewReader(payload),
	)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("x-pm-appversion", protonAppVersion)

	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(res.Body, 4096))
		var api struct {
			Code    int    `json:"Code"`
			Message string `json:"Error"`
		}
		_ = json.Unmarshal(body, &api)
		if api.Message != "" {
			return fmt.Errorf("Proton rejected the CAPTCHA answer (HTTP %d, code %d): %s",
				res.StatusCode, api.Code, api.Message)
		}
		return fmt.Errorf("Proton rejected the CAPTCHA answer (HTTP %d)", res.StatusCode)
	}
	return nil
}

func emitCaptchaBegin(w io.Writer, out captchaBeginOutput) int {
	if err := json.NewEncoder(w).Encode(out); err != nil {
		return 1
	}
	if !out.OK {
		return 1
	}
	return 0
}

func emitCaptchaSolve(w io.Writer, out captchaSolveOutput) int {
	if err := json.NewEncoder(w).Encode(out); err != nil {
		return 1
	}
	if !out.OK {
		return 1
	}
	return 0
}
