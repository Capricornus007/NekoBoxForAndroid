package main

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

// Exercise the real generated module without compiling an APK: the binding must
// resolve the same sing version as libcore, rather than promote unused modules.
func TestNekoBindingModuleGraph(t *testing.T) {
	mainDir := os.Getenv("NEKO_BIND_MAIN_MODULE")
	if mainDir == "" {
		t.Fatal("NEKO_BIND_MAIN_MODULE is required")
	}
	t.Setenv("GOWORK", "off")
	t.Setenv("GOFLAGS", "-mod=mod")
	run := func(dir string, args ...string) string {
		t.Helper()
		cmd := exec.Command("go", args...)
		cmd.Dir = dir
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("go %v: %v\n%s", args, err, out)
		}
		return strings.TrimSpace(string(out))
	}
	mainPath := run(mainDir, "list", "-m", "-f", "{{.Path}}")
	version := run(mainDir, "list", "-m", "-f", "{{.Version}}", "github.com/sagernet/sing")
	f, err := getModuleVersions("android", "arm64", mainDir)
	if err != nil || f == nil {
		t.Fatalf("generate module: %v", err)
	}
	if len(f.Require) != 1 || f.Require[0].Mod.Path != mainPath {
		t.Fatalf("binding must require only the main module; got %v", f.Require)
	}
	mod, err := f.Format()
	if err != nil {
		t.Fatal(err)
	}
	bindDir := t.TempDir()
	for path, data := range map[string][]byte{
		"go.mod": mod,
		"binding.go": []byte("package binding\nimport _ " + strconv.Quote(mainPath) + "\n"),
	} {
		if err := os.WriteFile(filepath.Join(bindDir, path), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	run(bindDir, "mod", "tidy")
	got := run(bindDir, "list", "-m", "-f", "{{.Version}}", "github.com/sagernet/sing")
	if got != version {
		t.Fatalf("binding changed sing from %s to %s\n%s", version, got, mod)
	}
	original, err := os.ReadFile(filepath.Join(mainDir, "go.mod"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(original, []byte("github.com/sagernet/sing "+version)) {
		t.Fatalf("sing %s must remain pinned in the main module", version)
	}
}
