//go:build android

package libcore

import (
	"os"
	_ "unsafe"
)

// Go probes pidfd support the first time it starts a process. Android < 12
// blocks the pidfd syscalls with seccomp and raises SIGSYS; a Go runtime
// loaded as a shared library installs no handler for that signal, so the
// probe kills the process. Tailscale's netstack can spawn `ping`, so make the
// probe report failure instead. Needs -ldflags=-checklinkname=0.
// https://github.com/golang/go/issues/70508
//
//go:linkname checkPidfdOnce os.checkPidfdOnce
var checkPidfdOnce func() error

func init() {
	checkPidfdOnce = func() error {
		return os.ErrInvalid
	}
}
