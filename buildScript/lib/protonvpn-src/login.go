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
	"time"

	proton "github.com/ProtonMail/go-proton-api"
)

// protonAppVersion 是 x-pm-appversion 的內容。Proton 正式閘門的規則是
// 「<平台>-<產品>@<版本>」，三段都有要求，用錯分隔符會被逐段糾正（實測回碼）：
//
//	"NB4A_1.0.0"                  -> 400/5002 Invalid app version
//	"NB4A@1.0.0"                  -> 400/2064 Application platform and product must be separated by a dash
//	"android-nb4a@1.0.0"          -> 400/2064 Product `nb4a` is not valid
//	"NB4A-android@1.0.0"          -> 400/2064 Application name must be in lowercase
//	"android-vpn@1.0.0"           -> 422/5003 This version of the app is no longer supported
//	"android-vpn@5.20.57.0"       -> 401（已過版本閘門，進到驗 token）
//
// 平台與產品都要在他們登記過的清單裡，所以第三方客戶端只能沿用官方 Android 客戶端的
// 身分；格式與值直接取自公開源碼 ProtonVPN/android-app：
//
//	app/src/main/java/com/protonvpn/android/utils/Constants.kt:73  MOBILE_CLIENT_ID = "android-vpn"
//	app/src/main/java/com/protonvpn/android/api/VpnApiClient.kt:49 "${clientId}@" + versionName()
//
// 版本號取該倉最新 release（5.20.57.0，2026-09-30）。Proton 會淘汰舊版本（見上面的
// 422/5003），所以這行是會過期的常數：哪天登入吃到 5003，就到上面那個倉抓新的 release tag。
const protonAppVersion = "android-vpn@5.20.57.0"

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
