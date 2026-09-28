package libcore

import (
	"archive/zip"
	"os"
	"path/filepath"
	"testing"
)

// writeZip creates an archive with the given entries in order; names ending in
// "/" become directory entries, others become files containing their own name.
func writeZip(t *testing.T, path string, entries ...string) {
	t.Helper()
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	w := zip.NewWriter(f)
	for _, name := range entries {
		e, err := w.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if name[len(name)-1] == '/' {
			continue
		}
		if _, err := e.Write([]byte(name)); err != nil {
			t.Fatal(err)
		}
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestUnzipReplaceDir(t *testing.T) {
	root := t.TempDir()
	dst := filepath.Join(root, "yacd")
	if err := os.MkdirAll(dst, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dst, "old.txt"), []byte("old"), 0o644); err != nil {
		t.Fatal(err)
	}

	good := filepath.Join(root, "good.zip")
	writeZip(t, good, "Yacd-meta-abc/", "Yacd-meta-abc/index.html")
	if err := unzipReplaceDir(good, dst); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(filepath.Join(dst, "index.html")); string(b) != "Yacd-meta-abc/index.html" {
		t.Fatalf("index.html = %q, want new content", b)
	}
	if _, err := os.Stat(filepath.Join(dst, "old.txt")); !os.IsNotExist(err) {
		t.Fatal("old contents survived the swap")
	}

	bad := filepath.Join(root, "bad.zip")
	writeZip(t, bad, "a/", "a/x", "b/", "b/y")
	if err := unzipReplaceDir(bad, dst); err == nil {
		t.Fatal("expected error for archive with two top-level dirs")
	}
	if _, err := os.Stat(filepath.Join(dst, "index.html")); err != nil {
		t.Fatal("dst was replaced by a failed extraction:", err)
	}
	if leftovers, _ := filepath.Glob(filepath.Join(root, "yacd.*.tmp")); len(leftovers) != 0 {
		t.Fatalf("temp dirs left behind: %v", leftovers)
	}

	fresh := filepath.Join(root, "fresh")
	if err := unzipReplaceDir(good, fresh); err != nil {
		t.Fatal("first install without an existing dst:", err)
	}
	if _, err := os.Stat(filepath.Join(fresh, "index.html")); err != nil {
		t.Fatal(err)
	}
}
