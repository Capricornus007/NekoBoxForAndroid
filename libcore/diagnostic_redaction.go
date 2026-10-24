package libcore

import (
	"io"
	"regexp"
	"strings"
)

var diagnosticPrivatePEM = regexp.MustCompile(`(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----`)
var diagnosticURI = regexp.MustCompile(`(?i)\b(?:https?|ssr?|vmess|vless|trojan(?:-go)?|socks[45a]?|naive\+https?|hysteria2?|tuic|juicity|anytls|vpn|sn)://[^\s"'<>]+`)
var diagnosticSecretField = regexp.MustCompile(`(?i)(["']?(?:password|passwd|private[_-]?key|preshared[_-]?key|pre_shared_key|header[_-]?protection[_-]?key|uuid|token|secret|authorization|cookie|(?:x-)?hwid|customHwidParams|customHeaders)["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\])*"|'[^'\r\n]*'|[^,;}\r\n]+)`)
var diagnosticANSIColor = regexp.MustCompile(`\x1b\[[0-9;:]*m`)

func redactDiagnostic(message string) string {
	if strings.IndexByte(message, '\x1b') >= 0 {
		message = diagnosticANSIColor.ReplaceAllString(message, "")
	}
	message = diagnosticPrivatePEM.ReplaceAllString(message, "[redacted private key]")
	message = diagnosticURI.ReplaceAllString(message, "[redacted URI]")
	return diagnosticSecretField.ReplaceAllString(message, "${1}[redacted]")
}

type redactingLogWriter struct{ target io.Writer }

func (w redactingLogWriter) Write(p []byte) (int, error) {
	_, err := w.target.Write([]byte(redactDiagnostic(string(p))))
	if err != nil {
		return 0, err
	}
	return len(p), nil
}
