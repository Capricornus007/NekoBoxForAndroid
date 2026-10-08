package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	proton "github.com/ProtonMail/go-proton-api"
)

// protonAppVersion 是 x-pm-appversion 的內容。Proton 的正式閘門有兩種合法寫法：
// 「<平台>-<產品>@<版本>」（官方客戶端）與「other_<版本>」（放行第三方客戶端），
// 用錯分隔符會被逐段糾正（2026-10-08 用公開端點 /auth/v4/info 逐個字串量測得來，
// 該端點在驗 token 之前就會先過版本閘門，所以不需要帳密）：
//
//	"NB4A_1.0.0"                  -> 400/5002 Invalid app version
//	"NB4A@1.0.0"                  -> 400/2064 Application platform and product must be separated by a dash
//	"android-nb4a@1.0.0"          -> 400/2064 Product `nb4a` is not valid
//	"NB4A-android@1.0.0"          -> 400/2064 Application name must be in lowercase
//	"Otherx_1.0.0"                -> 400/5002 Invalid app version（底線寫法只認 other 這個字）
//	"android-vpn@1.0.0"           -> 422/5003 This version of the app is no longer supported
//	"android-vpn@5.20.57.0"       -> 401 Invalid access token
//	"android-vpn@99.0.0"          -> 401 Invalid access token
//	"windows-vpn@5.20.57.0"       -> 200
//	"Other_1.0.0" / "other_1.0.0" -> 200（other 這條不比較版本號）
//
// 那兩記 401 是這版的死因：`android-vpn` 這組合被 Proton 在路由層整個拒掉（官方 VPN App
// 已改走免密碼的 session 流程），跟我們的 token 無關——連不存在的用戶名都吃 401，而且換成
// 99.0.0 也一樣；同一支產品換成 windows-vpn 就 200，所以擋的不是 product=vpn 整個類別。
// 因此改用專門留給第三方客戶端的 other_<版本>：不冒充官方身分，也不會跟著官方的版本淘汰
// 跑步機過期（上面那條 5003 就是會過期的常數會遇到的事）。
//
// 格式與值取自 Proton 公開源碼與 go-proton-api 的第三方用法；官方 Android 客戶端的身分是
//
//	app/src/main/java/com/protonvpn/android/utils/Constants.kt:73  MOBILE_CLIENT_ID = "android-vpn"
//	app/src/main/java/com/protonvpn/android/api/VpnApiClient.kt:49 "${clientId}@" + versionName()
const protonAppVersion = "Other_1.0.0"

const defaultProtonAPIURL = "https://api.protonmail.ch"

// loginInput comes from stdin, not argv: a password on the command line stays
// readable in /proc/<pid>/cmdline for the process lifetime.
type loginInput struct {
	Username      string `json:"username"`
	Password      string `json:"password"`
	TwoFactorCode string `json:"twoFactorCode,omitempty"`
	APIURL        string `json:"apiURL,omitempty"`
}

type storedCredential struct {
	UID          string `json:"uid"`
	UserID       string `json:"userId"`
	AccessToken  string `json:"accessToken"`
	RefreshToken string `json:"refreshToken"`
	ServerProof  string `json:"serverProof"`
	Scope        string `json:"scope"`
	TwoFactor    bool   `json:"twoFactor"`
	SavedAt      string `json:"savedAt"`
}

type loginOutput struct {
	OK                bool   `json:"ok"`
	UID               string `json:"uid,omitempty"`
	UserID            string `json:"userId,omitempty"`
	TwoFactorRequired bool   `json:"twoFactorRequired,omitempty"`
	CredentialFile    string `json:"credentialFile,omitempty"`
	Error             string `json:"error,omitempty"`
	Code              int    `json:"code,omitempty"`
	// Reason is a machine-readable tag so the UI can pick a string resource
	// instead of matching on Proton's English message text.
	Reason string `json:"reason,omitempty"`
}

func runLogin(args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	command := flag.NewFlagSet("login", flag.ContinueOnError)
	command.SetOutput(stderr)
	statePath := command.String("state", "", "file to write the session to (required)")
	timeoutSec := command.Int("timeout", 30, "overall deadline in seconds")
	if err := command.Parse(args); err != nil {
		return 2
	}
	if *statePath == "" {
		fmt.Fprintln(stderr, "protonvpn: login: --state is required")
		return 2
	}

	var in loginInput
	if err := json.NewDecoder(stdin).Decode(&in); err != nil {
		return emitLogin(stdout, loginOutput{Error: "read stdin: " + err.Error()})
	}
	if in.Username == "" || in.Password == "" {
		return emitLogin(stdout, loginOutput{Error: "username and password are required"})
	}

	apiURL := in.APIURL
	if apiURL == "" {
		apiURL = defaultProtonAPIURL
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(*timeoutSec)*time.Second)
	defer cancel()

	m := proton.New(proton.WithHostURL(apiURL), proton.WithAppVersion(protonAppVersion))
	defer m.Close()

	c, auth, err := m.NewClientWithLogin(ctx, in.Username, []byte(in.Password))
	if err != nil {
		return emitLogin(stdout, classifyLoginError(err))
	}
	defer c.Close()

	if auth.TwoFA.Enabled&proton.HasTOTP != 0 {
		if in.TwoFactorCode == "" {
			return emitLogin(stdout, loginOutput{
				TwoFactorRequired: true,
				Reason:            "two-factor-required",
				Error:             "this account has two-factor authentication enabled",
			})
		}
		if err := c.Auth2FA(ctx, proton.Auth2FAReq{TwoFactorCode: in.TwoFactorCode}); err != nil {
			out := classifyLoginError(err)
			out.TwoFactorRequired = true
			out.Reason = "two-factor-rejected"
			return emitLogin(stdout, out)
		}
		auth.TwoFA.Enabled = proton.HasTOTP
	}

	cred := storedCredential{
		UID:          auth.UID,
		UserID:       auth.UserID,
		AccessToken:  auth.AccessToken,
		RefreshToken: auth.RefreshToken,
		ServerProof:  auth.ServerProof,
		Scope:        auth.Scope,
		TwoFactor:    auth.TwoFA.Enabled&proton.HasTOTP != 0,
		SavedAt:      time.Now().UTC().Format(time.RFC3339),
	}
	if err := writeCredentialFile(*statePath, cred); err != nil {
		return emitLogin(stdout, loginOutput{Error: "save session: " + err.Error()})
	}

	return emitLogin(stdout, loginOutput{
		OK:             true,
		UID:            auth.UID,
		UserID:         auth.UserID,
		CredentialFile: *statePath,
	})
}

func classifyLoginError(err error) loginOutput {
	out := loginOutput{Error: err.Error()}
	var apiErr *proton.APIError
	if !errors.As(err, &apiErr) {
		if errors.Is(err, proton.ErrInvalidProof) {
			out.Reason = "server-proof-mismatch"
			out.Error = "Proton's SRP server proof did not match: refusing to use this session"
		}
		return out
	}
	out.Code = int(apiErr.Code)
	switch apiErr.Code {
	case proton.PasswordWrong:
		out.Reason = "wrong-password"
	case proton.InvalidValue:
		out.Reason = "bad-request"
	case proton.HumanVerificationRequired:
		out.Reason = "human-verification-required"
	case proton.AppVersionBadCode, proton.AppVersionMissingCode:
		out.Reason = "app-version-rejected"
	case proton.PaidPlanRequired:
		out.Reason = "paid-plan-required"
	default:
		out.Reason = "api-error"
	}
	// 實戰兜底：Proton 的 captcha 要求正式編號是 9001（go-proton-api 的
	// HumanVerificationRequired），但同一句話也可能帶著別的 Code 回來（例如攔在
	// /auth/v4/info 那一步、或以后改代碼）。UI 要靠這個 reason 決定「開瀏覽器解
	// CAPTCHA」那條路，所以訊息裡有 captcha 字樣就一律歸到同一個 reason。
	if !strings.Contains(strings.ToLower(out.Error), "captcha") {
		return out
	}
	out.Reason = "human-verification-required"
	return out
}

// writeCredentialFile creates the parent as 0700 and the file as 0600 through a
// temp+rename, so a crash cannot leave a half-written session that the next
// launch would try to refresh.
func writeCredentialFile(path string, cred storedCredential) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	payload, err := json.Marshal(cred)
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err := f.Write(payload); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, path)
}

func emitLogin(w io.Writer, out loginOutput) int {
	if err := json.NewEncoder(w).Encode(out); err != nil {
		return 1
	}
	if !out.OK {
		return 1
	}
	return 0
}
