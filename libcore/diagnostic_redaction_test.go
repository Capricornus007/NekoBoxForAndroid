package libcore

import (
	"bytes"
	"strings"
	"testing"
)

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
