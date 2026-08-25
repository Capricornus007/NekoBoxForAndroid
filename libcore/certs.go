package libcore

import (
	"crypto/x509"
	"log"
	_ "unsafe" // for go:linkname
)

//go:linkname systemRoots crypto/x509.systemRoots
var systemRoots *x509.CertPool

func updateRootCACerts(pem []byte) {
	x509.SystemCertPool()
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(pem) {
		log.Println("failed to append certificates from pem")
		return
	}
	systemRoots = roots
	log.Println("external ca.pem was loaded")
}

//go:linkname initSystemRoots crypto/x509.initSystemRoots
func initSystemRoots()

// AndroidCAStore is implemented by the Android app to supply the system
// trust anchors (AndroidCAStore KeyStore) through a Java callback, so
// user-added CAs are honored as well. It replaces the handmade Go CA store.
type AndroidCAStore interface {
	Certificates() []byte
}

var androidCAStore AndroidCAStore

func RegisterAndroidCAStore(store AndroidCAStore) {
	androidCAStore = store
}