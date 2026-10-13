package main

import (
	"context"
	"errors"
	"net/http"
	"time"

	proton "github.com/ProtonMail/go-proton-api"
)

// errNoRefreshToken means the stored session cannot be renewed at all, so a sign-in
// really is the only way out. It is deliberately distinct from errSessionExpired,
// which now means "renewal was attempted and Proton rejected that too".
var errNoRefreshToken = errors.New("stored session carries no refresh token")

// errEmptyRefreshedToken is the "200 OK but no token in it" case: an auth rejection
// in disguise, not a network fault.
var errEmptyRefreshedToken = errors.New("Proton's token renewal carried no access token")

// refreshSession renews the access token with POST /auth/v4/refresh using only the
// stored UID and RefreshToken, then persists the rotated pair back to statePath.
//
// ⚠️ 這推翻本倉先前寫在 nodes.go 的判斷（「換 token 等於重跑 SRP，所以只有 login 該做」）。
// SRP 只屬於「有密碼」那一步；/auth/v4/refresh 要的是 UID＋RefreshToken，而這兩個
// 從登入那天起就存在狀態檔裡（login.go 的 storedCredential.RefreshToken）。
// 庫裡本來就有現成入口：go-proton-api v0.4.0 manager_auth.go:20
// NewClientWithRefresh(ctx, uid, ref)。
// 不換的後果是 2026-10-13 用戶實測到的循環：access token 一過期，節點清單每次都 401，
// App 就把他踢回登入頁（他什麼都沒做錯），自動登入開著時看起來像「不停換頁」。
func refreshSession(ctx context.Context, apiURL, statePath string, cred storedCredential) (storedCredential, error) {
	if cred.UID == "" || cred.RefreshToken == "" {
		return cred, errNoRefreshToken
	}

	m := proton.New(
		proton.WithHostURL(apiURL),
		proton.WithAppVersion(protonAppVersion),
	)
	defer m.Close()

	_, auth, err := m.NewClientWithRefresh(ctx, cred.UID, cred.RefreshToken)
	if err != nil {
		return cred, err
	}
	if auth.AccessToken == "" {
		// 換新「回 200 但沒給 token」必須當失敗，不能當成功：庫把回應解析成
		// struct{ Auth }（Go 的 json 對匿名欄位做提升，讀的是**頂層**欄位），
		// 一旦 Proton 改回包一層 envelope、或這裡的解析假設破了，回包就會全空。
		// 若此時仍寫檔並重試，用戶看到的是「換新成功但清單照樣 401」，
		// 比直接報失敗更難查（測試 TestRefreshSessionTreatsAnEmptyTokenAsFailure 釘住這條）。
		return cred, errEmptyRefreshedToken
	}

	// 只覆蓋回包真的帶回來的欄位：refresh 的回應不保證含 UserID／Scope，
	// 無條件覆蓋會把狀態檔裡原有的正確值抹成空字串，下次連登入都省了直接壞。
	next := cred
	if auth.UID != "" {
		next.UID = auth.UID
	}
	if auth.UserID != "" {
		next.UserID = auth.UserID
	}
	if auth.AccessToken != "" {
		next.AccessToken = auth.AccessToken
	}
	if auth.RefreshToken != "" {
		next.RefreshToken = auth.RefreshToken
	}
	if auth.ServerProof != "" {
		next.ServerProof = auth.ServerProof
	}
	if auth.Scope != "" {
		next.Scope = auth.Scope
	}
	next.SavedAt = time.Now().UTC().Format(time.RFC3339Nano)

	if err := writeCredentialFile(statePath, next); err != nil {
		return cred, err
	}
	return next, nil
}

// isAuthRejection reports whether err is Proton saying "this credential is not
// accepted" (as opposed to the network or a 5xx failing). Only the former justifies
// telling the user to sign in again; treating a timeout as an expired session would
// bounce him to the login page for no reason.
//
// 兩種形態都要認：庫是把 `&APIError{}` 交給 resty 當 error 物件，所以鏈上實際是
// *proton.APIError（指標），而它的 Error() 是 value receiver、兩種都實作 error。
// 只寫其中一種的話，「換新也被拒」會被誤判成網路錯誤（實測過一次）。
func isAuthRejection(err error) bool {
	if errors.Is(err, errNoRefreshToken) || errors.Is(err, errSessionExpired) ||
		errors.Is(err, errEmptyRefreshedToken) {
		return true
	}
	var ptrErr *proton.APIError
	if errors.As(err, &ptrErr) {
		return isRejectionStatus(ptrErr.Status)
	}
	var valErr proton.APIError
	if errors.As(err, &valErr) {
		return isRejectionStatus(valErr.Status)
	}
	return false
}

func isRejectionStatus(status int) bool {
	return status == http.StatusUnauthorized || status == http.StatusForbidden
}
