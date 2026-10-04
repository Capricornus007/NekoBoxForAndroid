package main

import (
	"bytes"
	"crypto/ecdh"
	"encoding/base64"
	"encoding/json"
	"testing"
)

func TestKeyPairProducesAMatchingX25519Pair(t *testing.T) {
	var stdout, stderr bytes.Buffer
	if code := runKeyPair(&stdout, &stderr); code != 0 {
		t.Fatalf("keypair exited %d: %s", code, stderr.String())
	}
	var out keyPairOutput
	if err := json.Unmarshal(bytes.TrimSpace(stdout.Bytes()), &out); err != nil {
		t.Fatalf("parse stdout %q: %v", stdout.String(), err)
	}
	if !out.OK {
		t.Fatalf("keypair reported failure: %+v", out)
	}

	privateRaw, err := base64.StdEncoding.DecodeString(out.PrivateKey)
	if err != nil {
		t.Fatalf("private key is not base64: %v", err)
	}
	publicRaw, err := base64.StdEncoding.DecodeString(out.PublicKey)
	if err != nil {
		t.Fatalf("public key is not base64: %v", err)
	}
	if len(privateRaw) != x25519KeySize || len(publicRaw) != x25519KeySize {
		t.Fatalf("key sizes = %d/%d, want 32/32", len(privateRaw), len(publicRaw))
	}

	// The reported public key must be the one derived from the reported private
	// key, otherwise a node would be configured with a pair that cannot handshake.
	key, err := ecdh.X25519().NewPrivateKey(privateRaw)
	if err != nil {
		t.Fatalf("private key rejected: %v", err)
	}
	if !bytes.Equal(key.PublicKey().Bytes(), publicRaw) {
		t.Error("public key does not match the private key")
	}
}

func TestKeyPairDoesNotReuseAKey(t *testing.T) {
	first := captureKeyPair(t)
	second := captureKeyPair(t)
	if first.PrivateKey == second.PrivateKey {
		t.Fatal("two keypair runs returned the same private key")
	}
	if first.PublicKey == second.PublicKey {
		t.Fatal("two keypair runs returned the same public key")
	}
}

func captureKeyPair(t *testing.T) keyPairOutput {
	t.Helper()
	var stdout, stderr bytes.Buffer
	if code := runKeyPair(&stdout, &stderr); code != 0 {
		t.Fatalf("keypair exited %d: %s", code, stderr.String())
	}
	var out keyPairOutput
	if err := json.Unmarshal(bytes.TrimSpace(stdout.Bytes()), &out); err != nil {
		t.Fatalf("parse stdout: %v", err)
	}
	return out
}
