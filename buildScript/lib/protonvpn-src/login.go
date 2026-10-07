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

// protonAppVersion 是 x-pm-appversion 的內容，格式被 Proton 的閘門寫死成
// 「<產品名>_<semver>」：他們用 strings.Split(v, "_") 要求剛好切成兩段、第二段要能
// 被 semver 解析，否則直接回 HTTP 400、Code 5003（實測到的那句 invalid app version）。
// 所以這裡絕對不能用「NB4A/1.0.0」這種斜線寫法——切不出兩段就等於沒報版本。
// 判式與回碼的參考實作就在依賴裡：go-proton-api@v0.4.0/server/router.go 的
// requireValidAppVersion + validateAppVersion。
const protonAppVersion = "NB4A_1.0.0"

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
