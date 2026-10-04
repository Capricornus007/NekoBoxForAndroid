package main

import (
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"io"
)

// A WireGuard tunnel needs an X25519 pair, and Proton hands out its server key as
// a base64 X25519 public key, so both sides are produced in that same form.
//
// go-vpn-lib's ed25519 helper only yields the X25519 private scalar (it is
// sha512(seed) truncated by the caller's convention) and never the matching
// X25519 public point, so generating the pair with crypto/ecdh keeps the two
// halves guaranteed consistent.
type keyPairOutput struct {
	OK         bool   `json:"ok"`
	PrivateKey string `json:"privateKey,omitempty"`
	PublicKey  string `json:"publicKey,omitempty"`
	Error      string `json:"error,omitempty"`
}

func runKeyPair(stdout, stderr io.Writer) int {
	command := flag.NewFlagSet("keypair", flag.ContinueOnError)
	command.SetOutput(stderr)
	if err := command.Parse(nil); err != nil {
		return 2
	}

	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return emitKeyPair(stdout, keyPairOutput{Error: err.Error()})
	}

	out := keyPairOutput{
		OK:         true,
		PrivateKey: base64.StdEncoding.EncodeToString(key.Bytes()),
		PublicKey:  base64.StdEncoding.EncodeToString(key.PublicKey().Bytes()),
	}
	if len(key.Bytes()) != x25519KeySize || len(key.PublicKey().Bytes()) != x25519KeySize {
		return emitKeyPair(stdout, keyPairOutput{
			Error: fmt.Sprintf("unexpected X25519 key sizes %d/%d", len(key.Bytes()), len(key.PublicKey().Bytes())),
		})
	}
	return emitKeyPair(stdout, out)
}

const x25519KeySize = 32

func emitKeyPair(w io.Writer, out keyPairOutput) int {
	if err := json.NewEncoder(w).Encode(out); err != nil {
		return 1
	}
	if !out.OK {
		return 1
	}
	return 0
}
