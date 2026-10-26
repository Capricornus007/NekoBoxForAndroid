package main

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// OpenVPN 那條路（#71）：Proton 的 OpenVPN 節點不用帳號密碼驗證，而是「客戶端憑證」——
// 本機生成 RSA 金鑰＋自簽 CSR，把公鑰送給 POST /vpn/v1/certificate，Proton 回簽一張
// 客戶端憑證，再把它們拼進官方 Windows 客戶端那份 config.ovpn 模板。
//
// 證據鏈（全部實查，2026-10-26）：
//   - 端點與欄位：ProtonVPN/python-proton-vpn-api-core
//     proton/vpn/session/fetcher.py:99 → POST /vpn/v1/certificate，
//     body {"ClientPublicKey": <PEM>, "Duration": "<n> min", "Features": ...}
//   - 回包結構：同倉 proton/vpn/session/dataclasses/certificate.py 的 VPNCertificate
//     （SerialNumber/ClientKey/ServerPublicKey/Certificate/ExpirationTime/RefreshTime/
//      Mode/DeviceName/ServerPublicKeyMode）
//   - 模板、CA、tls-crypt 靜態鑰：ProtonVPN/win-app
//     src/ProtonVPN.Service/Resources/config.ovpn（見 ovpn_assets.go）
//   - 官方 Android 端同一個端點：android-app api/ProtonVPNRetrofit.kt 的
//     @POST("vpn/v1/certificate")
//
// 這裡刻意不內嵌 <cert>/<key> 而寫成檔案路徑：nb4a 的 OpenVPN 匯入走「檔案＋金鑰檔」
// 形態，PEM 塞 inline 要赌解析器支援與換行處理，賭不起（規則：不能拿推論冒充資料）。

// openvpnKeyBits 4096 與官方客戶端一致：Proton 的 CA 只簽這個強度，別自己調低。
const openvpnKeyBits = 4096

// certAPIResponse 是 /vpn/v1/certificate 的回包（頂層 Code＋VPNCertificate 欄位）。
type certAPIResponse struct {
	Code  int    `json:"Code"`
	Error string `json:"Error"`
	// 庫把 VPNCertificate 的欄位直接攤平在頂層（實查 dataclass 註解：
	// "Same object structure coming from the API"），所以這裡不巢狀。
	SerialNumber     string `json:"SerialNumber"`
	ClientKey        string `json:"ClientKey"`
	Certificate      string `json:"Certificate"`
	ExpirationTime   int64  `json:"ExpirationTime"`
	RefreshTime      int64  `json:"RefreshTime"`
	Mode             string `json:"Mode"`
	DeviceName       string `json:"DeviceName"`
	ServerPublicKey  string `json:"ServerPublicKey"`
	ServerPubKeyMode string `json:"ServerPublicKeyMode"`
}

// generateOpenVPNIdentity 產出 RSA 金鑰與自簽 CSR 的公鑰 PEM。
//
// 自簽憑證的 CN 就是 Proton 要看的 device name；有效期取短（1 年）即可，因為 Proton
// 只從 CSR 裡取公鑰與 CN，回簽的那張才算數。
func generateOpenVPNIdentity(deviceName string) (privPEM string, pubPEM string, err error) {
	key, err := rsa.GenerateKey(rand.Reader, openvpnKeyBits)
	if err != nil {
		return "", "", fmt.Errorf("generate openvpn key: %w", err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(time.Now().UnixNano()),
		Subject:               pkix.Name{CommonName: deviceName},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().AddDate(1, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return "", "", fmt.Errorf("self-sign openvpn request: %w", err)
	}
	privPEM = string(pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)}))
	pubPEM = string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))
	return privPEM, pubPEM, nil
}

// fetchOpenVPNCertificate 用存著的憑證把公鑰送給 Proton 簽。401/403 一律回報
// errSessionExpired，讓呼叫端能走既有的換 token 重試路徑（與 nodes/tier 同一套）。
func fetchOpenVPNCertificate(ctx context.Context, apiURL string, cred storedCredential, pubPEM, duration string) (certAPIResponse, error) {
	body := map[string]string{"ClientPublicKey": pubPEM}
	if duration != "" {
		body["Duration"] = duration
	}
	payload, err := json.Marshal(body)
	if err != nil {
		return certAPIResponse{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimRight(apiURL, "/")+"/vpn/v1/certificate", strings.NewReader(string(payload)))
	if err != nil {
		return certAPIResponse{}, err
	}
	req.Header.Set("Authorization", "Bearer "+cred.AccessToken)
	req.Header.Set("x-pm-uid", cred.UID)
	req.Header.Set("x-pm-appversion", protonAppVersion)
	req.Header.Set("x-pm-apiversion", protonAPIVersion)
	req.Header.Set("Accept", protonAccept)
	req.Header.Set("Content-Type", "application/json")

	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return certAPIResponse{}, err
	}
	defer res.Body.Close()

	switch {
	case res.StatusCode == http.StatusUnauthorized, res.StatusCode == http.StatusForbidden:
		return certAPIResponse{}, errSessionExpired
	case res.StatusCode != http.StatusOK:
		return certAPIResponse{}, fmt.Errorf("API returned HTTP %d", res.StatusCode)
	}

	var out certAPIResponse
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return certAPIResponse{}, fmt.Errorf("decode certificate response: %w", err)
	}
	if out.Code != 1000 {
		return out, fmt.Errorf("Proton refused the certificate request (code %d)", out.Code)
	}
	if out.Certificate == "" {
		return out, fmt.Errorf("certificate response carried no certificate")
	}
	return out, nil
}

// openvpnConfig 組出給 nb4a 匯入的 .ovpn 內容：材料全部 inline（<ca>/<cert>/<key>/
// <tls-crypt>），因為 nb4a 的解析器本來就吃這些區塊（fmt/openvpn/
// OpenVPNConfigFmt.kt 的 MATERIAL_BLOCKS），自包含一份就好帶、也好核；
// 同時仍把四份材料寫檔，讓用戶能把同一份憑證拿去別處用。
// remote 的 host/埠/協定由呼叫端從 /vpn/v2/logicals 那台的 EntryPerProtocol 挑。
// username 為空時不寫 auth-user-pass：官方 Windows 端 ConfigTemplate.cs:55-57 就是
// 「帳號與密碼都有值才加這條」，憑證式連線不假設一定要帳密。nb4a 的解析器也照這個
// 語意走（沒這條就不會要求補密碼）。
func openvpnConfig(host string, port int, proto, privPEM, certPEM, username string) string {
	var b strings.Builder
	b.WriteString("client\ndev tun\nremote " + host + " " + fmt.Sprint(port) + " " + proto + "\n")
	b.WriteString("proto " + proto + "\n")
	b.WriteString("resolv-retry infinite\ncipher AES-256-GCM\nverb 3\n")
	b.WriteString("tun-mtu 1500\ntun-mtu-extra 32\nmssfix 1450\n")
	b.WriteString("persist-key\npersist-tun\nping 15\nping-restart 0\nping-timer-rem\n")
	b.WriteString("reneg-sec 0\nhand-window 15\nconnect-timeout 10\nconnect-retry-max 1\n")
	b.WriteString("remote-cert-tls server\nkey-direction 1\ndisable-dco\n")
	if username != "" {
		b.WriteString("auth-user-pass\nsetenv USERNAME \"" + username + "\"\n")
	}
	b.WriteString("<tls-crypt>\n" + strings.TrimSpace(protonTLSCryptKey) + "\n</tls-crypt>\n")
	b.WriteString("<ca>\n" + strings.TrimSpace(protonOpenvpnCA) + "\n</ca>\n")
	b.WriteString("<cert>\n" + strings.TrimSpace(certPEM) + "\n</cert>\n")
	b.WriteString("<key>\n" + strings.TrimSpace(privPEM) + "\n</key>\n")
	return b.String()
}

type ovpnOutput struct {
	OK         bool   `json:"ok"`
	Config     string `json:"config,omitempty"`
	KeyPath    string `json:"keyPath,omitempty"`
	CertPath   string `json:"certPath,omitempty"`
	CAPath     string `json:"caPath,omitempty"`
	TLSKeyPath string `json:"tlsKeyPath,omitempty"`
	Host       string `json:"host,omitempty"`
	Port       int    `json:"port,omitempty"`
	Proto      string `json:"proto,omitempty"`
	ExpiresAt  int64  `json:"expiresAt,omitempty"`
	Error      string `json:"error,omitempty"`
	Code       string `json:"code,omitempty"`
}

func emitOvpn(stdout io.Writer, out ovpnOutput) int {
	if err := json.NewEncoder(stdout).Encode(out); err != nil {
		return 1
	}
	if !out.OK {
		return 1
	}
	return 0
}

// writePrivate 0600 落地（金鑰檔不能靠預設 umask）。
func writePrivate(path, content string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	return os.WriteFile(path, []byte(content), 0o600)
}

func runOvpn(args []string, stdout, stderr io.Writer) int {
	command := flag.NewFlagSet("ovpn", flag.ContinueOnError)
	command.SetOutput(stderr)
	statePath := command.String("state", "", "stored session to read (required)")
	outDir := command.String("out-dir", "", "directory to write key/cert into (required)")
	host := command.String("host", "", "server hostname to dial (required)")
	port := command.Int("port", 0, "port to dial (required)")
	proto := command.String("proto", "udp", "udp or tcp")
	username := command.String("username", "", "account name for auth-user-pass; empty falls back to the login name in the session file")
	duration := command.String("duration", "", `certificate duration, e.g. "6000000 min" (empty = API default)`)
	apiURL := command.String("api-url", defaultProtonAPIURL, "API base URL")
	timeoutSec := command.Int("timeout", 60, "overall deadline in seconds")
	if err := command.Parse(args); err != nil {
		return 2
	}
	if *statePath == "" || *outDir == "" || *host == "" || *port <= 0 {
		fmt.Fprintln(stderr, "protonvpn: ovpn: --state, --out-dir, --host and --port are required")
		return 2
	}
	switch *proto {
	case "udp", "tcp":
	default:
		fmt.Fprintf(stderr, "protonvpn: ovpn: --proto must be udp or tcp, got %q\n", *proto)
		return 2
	}
	cred, err := readCredentialFile(*statePath)
	if err != nil {
		return emitOvpn(stdout, ovpnOutput{Error: err.Error()})
	}

	// 「登一次就夠」：沒明確指定就用狀態檔裡的登入名，用戶不需要再打任何東西。
	name := *username
	if name == "" {
		name = cred.Username
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(*timeoutSec)*time.Second)
	defer cancel()

	privPEM, pubPEM, err := generateOpenVPNIdentity(hostnameForDevice(*outDir))
	if err != nil {
		return emitOvpn(stdout, ovpnOutput{Error: err.Error()})
	}
	cert, err := fetchOpenVPNCertificate(ctx, *apiURL, cred, pubPEM, *duration)
	if err != nil && errors.Is(err, errSessionExpired) {
		refreshed, rerr := refreshSession(ctx, *apiURL, *statePath, cred)
		if rerr != nil {
			return emitOvpn(stdout, ovpnOutput{Error: errSessionExpired.Error(), Code: codeSessionExpired})
		}
		cert, err = fetchOpenVPNCertificate(ctx, *apiURL, refreshed, pubPEM, *duration)
	}
	if err != nil {
		code := ""
		if errors.Is(err, errSessionExpired) {
			code = codeSessionExpired
		}
		return emitOvpn(stdout, ovpnOutput{Error: err.Error(), Code: code})
	}

	keyPath := filepath.Join(*outDir, "client.key")
	certPath := filepath.Join(*outDir, "client.crt")
	caPath := filepath.Join(*outDir, "ca.crt")
	tlsKeyPath := filepath.Join(*outDir, "tls-crypt.key")
	for _, p := range []struct{ path, content string }{
		{keyPath, privPEM}, {certPath, cert.Certificate}, {caPath, protonOpenvpnCA + "\n"}, {tlsKeyPath, protonTLSCryptKey + "\n"},
	} {
		if err := writePrivate(p.path, p.content); err != nil {
			return emitOvpn(stdout, ovpnOutput{Error: fmt.Sprintf("write %s: %v", filepath.Base(p.path), err)})
		}
	}

	return emitOvpn(stdout, ovpnOutput{
		OK: true, Config: openvpnConfig(*host, *port, *proto, privPEM, cert.Certificate, name),
		KeyPath: keyPath, CertPath: certPath, CAPath: caPath, TLSKeyPath: tlsKeyPath,
		Host: *host, Port: *port, Proto: *proto, ExpiresAt: cert.ExpirationTime,
	})
}

// hostnameForDevice 給憑證一個可辨識但不外洩路徑的 CN。Proton 在網頁端會把它列成
// 「裝置名稱」，所以要有意義、又不能是隨機亂碼。
func hostnameForDevice(dir string) string {
	host, err := os.Hostname()
	if err != nil || host == "" {
		host = "nb4a"
	}
	host = strings.NewReplacer(" ", "-", "/", "-", "\\", "-", ":", "-").Replace(host)
	if len(host) > 32 {
		host = host[:32]
	}
	return host + "-openvpn"
}
