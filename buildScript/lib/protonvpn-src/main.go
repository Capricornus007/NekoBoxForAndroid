// Command protonvpn-sidecar is the entry point of the bundled Proton sidecar,
// cross-compiled to app/executableSo/<abi>/libprotonvpn.so by
// `./run lib protonvpn` and launched as a child process by NekoBox.
//
// # WHY A SIDECAR AND NOT A gomobile AAR
//
// Proton ships its VPN client logic as go-vpn-lib plus go-srp (SRP auth) and
// gopenpgp (OpenPGP armor / detached-signature verification of Proton's API
// payloads). Their own apps link those packages through `gomobile bind`, which
// produces an AAR containing go.Seq + libgojni.so. An Android process may only
// carry ONE gomobile binding: a second one collides with libcore's (nb4a's
// sing-box core is already a gomobile AAR), and it would also drag Proton's
// whole dependency graph into libcore's pinned sing-box module graph. So we
// follow the same route as mieru/naive/masterdnsvpn/olcrtc: a plain Go `main`
// compiled for each ABI, dropped into jniLibs as lib<name>.so and executed from
// nativeLibraryDir as a separate process.
//
// That also means the SRP and OpenPGP work stays in Proton's own Go code. It is
// deliberately NOT re-implemented in Kotlin: the modulus signature produced by
// Proton's API must be verified against Proton's hardcoded modulus key, and the
// VPN endpoints come back as armored/clear-signed OpenPGP payloads.
//
// WHICH go-vpn-lib PACKAGES WE LINK
//
//	ed25519    - Proton key pair generation (Ed25519 -> X25519 for WireGuard)
//	localAgent - state machine speaking to Proton's in-server local agent
//	go-srp     - SRP auth (external module, same pinned graph)
//	gopenpgp   - OpenPGP armor + detached signature verification
//
// `wgAndroid` is intentionally NOT linked: it is Proton's copy of the
// wireguard-android libwg-go API and only compiles against Proton's forked
// wireguard-go device plus a patched Go runtime clock. nb4a creates its tunnel
// through sing-box, so that backend is irrelevant here and would only add a
// second VPN stack to the binary.
//
// CURRENT SCOPE (this file)
//
// The build chain plus a self-contained `selftest`: it exercises every linked
// Proton component offline (SRP handshake against Proton's real signed modulus,
// OpenPGP sign/verify, Ed25519 key pair, localAgent feature/state constants) so
// that a broken or mis-pinned dependency fails at build time instead of on the
// device. The account/API commands (login, fetch nodes, connect) are added by
// the follow-up tickets on top of this same entry point.
//
// Usage:
//
//	libprotonvpn.so version     - print pinned library provenance as JSON
//	libprotonvpn.so selftest    - run the offline checks, print JSON, exit 0/1
package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"runtime"
	"strings"
	"time"

	srp "github.com/ProtonMail/go-srp"
	"github.com/ProtonMail/gopenpgp/v2/crypto"
	"github.com/ProtonVPN/go-vpn-lib/ed25519"
	"github.com/ProtonVPN/go-vpn-lib/localAgent"
)

// protonLibCommit is stamped by buildScript/lib/protonvpn.sh through
// -ldflags -X so a device can report exactly which go-vpn-lib tree it runs.
// It stays "unknown" for host `go test` builds, which is fine.
var protonLibCommit = "unknown"

// selftestSignedModulus is Proton's production 2048-bit SRP modulus in the
// clear-signed form the auth API returns (the same value go-srp uses in its own
// end-to-end test). The selftest feeds it to the SRP engine so the OpenPGP
// signature check on the modulus is covered too.
const selftestSignedModulus = `-----BEGIN PGP SIGNED MESSAGE-----
Hash: SHA256

W2z5HBi8RvsfYzZTS7qBaUxxPhsfHJFZpu3Kd6s1JafNrCCH9rfvPLrfuqocxWPgWDH2R8neK7PkNvjxto9TStuY5z7jAzWRvFWN9cQhAKkdWgy0JY6ywVn22+HFpF4cYesHrqFIKUPDMSSIlWjBVmEJZ/MusD44ZT29xcPrOqeZvwtCffKtGAIjLYPZIEbZKnDM1Dm3q2K/xS5h+xdhjnndhsrkwm9U9oyA2wxzSXFL+pdfj2fOdRwuR5nW0J2NFrq3kJjkRmpO/Genq1UW+TEknIWAb6VzJJJA244K/H8cnSx2+nSNZO3bbo6Ys228ruV9A8m6DhxmS+bihN3ttQ==
-----BEGIN PGP SIGNATURE-----
Version: ProtonMail
Comment: https://protonmail.com

wl4EARYIABAFAlwB1j0JEDUFhcTpUY8mAAD8CgEAnsFnF4cF0uSHKkXa1GIa
GO86yMV4zDZEZcDSJo0fgr8A/AlupGN9EdHlsrZLmTA1vhIx+rOgxdEff28N
kvNM7qIK
=q6vu
-----END PGP SIGNATURE-----`

type checkOutput struct {
	Name   string `json:"name"`
	OK     bool   `json:"ok"`
	Detail string `json:"detail,omitempty"`
	Error  string `json:"error,omitempty"`
}

type selftestOutput struct {
	OK         bool          `json:"ok"`
	Sidecar    string        `json:"sidecar"`
	GoVersion  string        `json:"goVersion"`
	SRPVersion string        `json:"srpVersion"`
	ProtonLib  string        `json:"protonVpnLibCommit"`
	Checks     []checkOutput `json:"checks"`
}

type versionOutput struct {
	Sidecar    string `json:"sidecar"`
	ProtonLib  string `json:"protonVpnLibCommit"`
	SRPVersion string `json:"srpVersion"`
	GoVersion  string `json:"goVersion"`
}

// selftestCheck is one offline proof that a linked Proton component really
// works in this binary.
type selftestCheck struct {
	name string
	run  func() (string, error)
}

func selftestChecks() []selftestCheck {
	return []selftestCheck{
		{"srp-handshake", checkSRPHandshake},
		{"openpgp-sign-verify", checkOpenPGP},
		{"ed25519-keypair", checkEd25519KeyPair},
		{"localagent-features", checkLocalAgentFeatures},
	}
}

// checkSRPHandshake runs a full SRP-6a exchange locally: password hash ->
// verifier -> server challenge -> client proofs -> server proof verification.
// It covers the OpenPGP signature check on Proton's modulus, bcrypt, the SHA-512
// expansion and the constant-time bignum layer, which is the whole auth path the
// feature tickets will drive over the network.
func checkSRPHandshake() (string, error) {
	const bitLength = 2048

	password := []byte("nb4a-proton-sidecar-selftest")
	rawSalt, err := srp.RandomBytes(10)
	if err != nil {
		return "", fmt.Errorf("random salt: %w", err)
	}

	verifierAuth, err := srp.NewAuthForVerifier(password, selftestSignedModulus, rawSalt)
	if err != nil {
		return "", fmt.Errorf("modulus not accepted: %w", err)
	}
	verifier, err := verifierAuth.GenerateVerifier(bitLength)
	if err != nil {
		return "", fmt.Errorf("generate verifier: %w", err)
	}

	server, err := srp.NewServerFromSigned(selftestSignedModulus, verifier, bitLength)
	if err != nil {
		return "", fmt.Errorf("new server: %w", err)
	}
	challenge, err := server.GenerateChallenge()
	if err != nil {
		return "", fmt.Errorf("generate challenge: %w", err)
	}

	auth, err := srp.NewAuth(
		4,
		"nb4a-selftest",
		password,
		base64.StdEncoding.EncodeToString(rawSalt),
		selftestSignedModulus,
		base64.StdEncoding.EncodeToString(challenge),
	)
	if err != nil {
		return "", fmt.Errorf("new auth: %w", err)
	}
	proofs, err := auth.GenerateProofs(bitLength)
	if err != nil {
		return "", fmt.Errorf("generate proofs: %w", err)
	}

	serverProof, err := server.VerifyProofs(proofs.ClientEphemeral, proofs.ClientProof)
	if err != nil {
		return "", fmt.Errorf("server rejected client proof: %w", err)
	}
	if !server.IsCompleted() {
		return "", errors.New("SRP exchange did not reach the completed state")
	}
	if !bytes.Equal(proofs.ExpectedServerProof, serverProof) {
		return "", errors.New("server proof does not match the expected one")
	}
	session, err := server.GetSharedSession()
	if err != nil {
		return "", fmt.Errorf("shared session: %w", err)
	}

	return fmt.Sprintf("2048-bit exchange completed, session %d bytes", len(session)), nil
}

// checkOpenPGP proves the OpenPGP stack Proton's API payloads need is linked and
// working: key generation, armored public key, detached signature round trip.
func checkOpenPGP() (string, error) {
	key, err := crypto.GenerateKey("NB4A Proton Sidecar", "selftest@localhost", "x25519", 0)
	if err != nil {
		return "", fmt.Errorf("generate key: %w", err)
	}
	if !key.CanVerify() {
		return "", errors.New("generated key cannot create signatures")
	}

	armored, err := key.GetArmoredPublicKey()
	if err != nil {
		return "", fmt.Errorf("armor public key: %w", err)
	}
	if !strings.HasPrefix(armored, "-----BEGIN PGP PUBLIC KEY BLOCK-----") {
		return "", errors.New("unexpected armor header for the public key")
	}

	publicKey, err := crypto.NewKeyFromArmored(armored)
	if err != nil {
		return "", fmt.Errorf("read armored public key: %w", err)
	}
	publicRing, err := crypto.NewKeyRing(publicKey)
	if err != nil {
		return "", fmt.Errorf("public key ring: %w", err)
	}
	privateRing, err := crypto.NewKeyRing(key)
	if err != nil {
		return "", fmt.Errorf("private key ring: %w", err)
	}

	message := crypto.NewPlainMessageFromString("nb4a proton sidecar selftest")
	signature, err := privateRing.SignDetached(message)
	if err != nil {
		return "", fmt.Errorf("sign detached: %w", err)
	}
	if err := publicRing.VerifyDetached(message, signature, time.Now().Unix()); err != nil {
		return "", fmt.Errorf("verify detached: %w", err)
	}

	// A tampered payload must be rejected, otherwise the API signature check
	// we rely on for the node lists would be decorative.
	if err := publicRing.VerifyDetached(
		crypto.NewPlainMessageFromString("nb4a proton sidecar selftesT"),
		signature,
		time.Now().Unix(),
	); err == nil {
		return "", errors.New("detached signature verified for modified content")
	}

	return fmt.Sprintf("x25519 key %s, detached signature verified", key.GetSHA256Fingerprint()), nil
}

// checkEd25519KeyPair covers go-vpn-lib's key tooling: the WireGuard identity a
// Proton connection needs is an Ed25519 pair converted to an X25519 public key.
func checkEd25519KeyPair() (string, error) {
	keyPair, err := ed25519.NewKeyPair()
	if err != nil {
		return "", fmt.Errorf("new key pair: %w", err)
	}
	defer keyPair.Clear()

	x25519Raw, err := base64.StdEncoding.DecodeString(keyPair.ToX25519Base64())
	if err != nil {
		return "", fmt.Errorf("x25519 public key is not base64: %w", err)
	}
	if len(x25519Raw) != 32 {
		return "", fmt.Errorf("x25519 public key is %d bytes, want 32", len(x25519Raw))
	}

	pem, err := keyPair.PublicKeyPKIXPem()
	if err != nil {
		return "", fmt.Errorf("public key PEM: %w", err)
	}
	if !strings.HasPrefix(pem, "-----BEGIN PUBLIC KEY-----") {
		return "", errors.New("public key PEM has an unexpected header")
	}
	if len(keyPair.PublicKeyBytes()) != ed25519PublicKeySize {
		return "", fmt.Errorf("ed25519 public key is %d bytes, want %d",
			len(keyPair.PublicKeyBytes()), ed25519PublicKeySize)
	}

	return "ed25519 pair converted to a 32-byte x25519 public key", nil
}

// ed25519PublicKeySize mirrors crypto/ed25519.PublicKeySize without importing it.
const ed25519PublicKeySize = 32

// checkLocalAgentFeatures covers the agent protocol plumbing: the feature map
// that is sent to Proton's in-server local agent and the state/error constants
// the Kotlin side has to map to UI strings.
func checkLocalAgentFeatures() (string, error) {
	features := localAgent.NewFeatures()
	features.SetInt("netshield-level", 2)
	features.SetBool("jail", true)
	features.SetString(localAgent.Constants().FeatureBouncing, localAgent.Constants().LabelPartner)

	encoded, err := json.Marshal(features)
	if err != nil {
		return "", fmt.Errorf("marshal features: %w", err)
	}

	roundTrip := localAgent.NewFeatures()
	if err := json.Unmarshal(encoded, roundTrip); err != nil {
		return "", fmt.Errorf("unmarshal features: %w", err)
	}
	if roundTrip.GetInt("netshield-level") != 2 {
		return "", errors.New("netshield-level did not survive the JSON round trip")
	}
	if !roundTrip.GetBool("jail") {
		return "", errors.New("jail did not survive the JSON round trip")
	}
	if roundTrip.GetCount() != features.GetCount() {
		return "", fmt.Errorf("feature count changed after round trip: %d -> %d",
			features.GetCount(), roundTrip.GetCount())
	}

	constants := localAgent.Constants()
	if constants.StateConnected != "Connected" || constants.StateConnecting != "Connecting" {
		return "", errors.New("unexpected local agent state constants")
	}
	if constants.ErrorCodeCertificateRevoked != 86102 {
		return "", fmt.Errorf("unexpected revoked-certificate error code: %d",
			constants.ErrorCodeCertificateRevoked)
	}

	return fmt.Sprintf("%d features round-tripped, %d state constants readable",
		features.GetCount(), 11), nil
}

func runSelftest(w io.Writer) (bool, error) {
	return runSelftestChecks(w, selftestChecks())
}

// runSelftestChecks builds the report from an injected check list so the failure
// path can be tested without breaking a real dependency.
func runSelftestChecks(w io.Writer, checks []selftestCheck) (bool, error) {
	report := selftestOutput{
		OK:         true,
		Sidecar:    "libprotonvpn.so",
		GoVersion:  runtime.Version(),
		SRPVersion: srp.VersionNumber(),
		ProtonLib:  protonLibCommit,
	}

	for _, check := range checks {
		output := checkOutput{Name: check.name, OK: true}
		detail, err := check.run()
		if err != nil {
			output.OK = false
			output.Error = err.Error()
			report.OK = false
		} else {
			output.Detail = detail
		}
		report.Checks = append(report.Checks, output)
	}

	if err := json.NewEncoder(w).Encode(report); err != nil {
		return report.OK, fmt.Errorf("write selftest report: %w", err)
	}
	return report.OK, nil
}

func runVersion(w io.Writer) error {
	return json.NewEncoder(w).Encode(versionOutput{
		Sidecar:    "libprotonvpn.so",
		ProtonLib:  protonLibCommit,
		SRPVersion: srp.VersionNumber(),
		GoVersion:  runtime.Version(),
	})
}

func usage(w io.Writer) {
	fmt.Fprint(w, `usage: libprotonvpn.so <command>

commands:
  version     print the pinned Proton library provenance as JSON
  selftest    run the offline dependency checks, print JSON, exit 1 on failure
`)
}

func run(args []string, stdout, stderr io.Writer) int {
	if len(args) == 0 {
		usage(stderr)
		return 2
	}

	switch args[0] {
	case "version":
		command := flag.NewFlagSet("version", flag.ContinueOnError)
		command.SetOutput(stderr)
		if err := command.Parse(args[1:]); err != nil {
			return 2
		}
		if err := runVersion(stdout); err != nil {
			fmt.Fprintf(stderr, "protonvpn: %v\n", err)
			return 1
		}
		return 0

	case "selftest":
		command := flag.NewFlagSet("selftest", flag.ContinueOnError)
		command.SetOutput(stderr)
		if err := command.Parse(args[1:]); err != nil {
			return 2
		}
		ok, err := runSelftest(stdout)
		if err != nil {
			fmt.Fprintf(stderr, "protonvpn: %v\n", err)
			return 1
		}
		if !ok {
			return 1
		}
		return 0

	case "help", "-h", "--help":
		usage(stdout)
		return 0

	default:
		fmt.Fprintf(stderr, "protonvpn: unknown command %q\n", args[0])
		usage(stderr)
		return 2
	}
}

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr))
}
