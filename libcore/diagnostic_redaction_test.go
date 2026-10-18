package libcore

import (
	"bytes"
	"strings"
	"testing"
)

func TestNativeLogStripsANSIColorsWithoutLosingContext(t *testing.T) {
	input := "\x1b[31mERROR\x1b[0m[0010] [\x1b[38;5;84m42\x1b[0m 1ms] router: failed password=fixture-secret\n"
	var sink bytes.Buffer
	n, err := (redactingLogWriter{&sink}).Write([]byte(input))
	if err != nil || n != len(input) {
		t.Fatalf("write=%d err=%v", n, err)
	}
	output := sink.String()
	if strings.ContainsRune(output, '\x1b') || strings.Contains(output, "fixture-secret") || !strings.Contains(output, "ERROR[0010] [42 1ms] router: failed") {
		t.Fatalf("unexpected log: %q", output)
	}
}

func TestNativeLogRedactionBeforeWriting(t *testing.T) {
	input := "dns timeout: https://resolver.test/account-secret\n" +
		`{"private_key":"fixture-private","customHeaders":"Example-Api-Key: a\"quoted-secret","password":"secret phrase"}` +
		"\n-----BEGIN PRIVATE KEY-----\nprivate-pem\n-----END PRIVATE KEY-----\n"
	var sink bytes.Buffer
	n, err := (redactingLogWriter{&sink}).Write([]byte(input))
	if err != nil || n != len(input) {
		t.Fatalf("write=%d err=%v", n, err)
	}
	output := sink.String()
	for _, secret := range []string{"account-secret", "fixture-private", "quoted-secret", "secret phrase", "private-pem"} {
		if strings.Contains(output, secret) {
			t.Fatalf("leaked %s", secret)
		}
	}
	if !strings.Contains(output, "dns timeout") {
		t.Fatal("lost error context")
	}
}

func TestDiagnosticRedactionMasksAWGHeaderProtectionKey(t *testing.T) {
	const secret = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
	for _, name := range []string{"HeaderProtectionKey", "header_protection_key"} {
		if strings.Contains(redactDiagnostic(name+"="+secret), secret) {
			t.Fatal("AWG header key exposed")
		}
	}
}
