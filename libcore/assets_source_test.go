package libcore

import (
	"go/parser"
	"go/token"
	"os"
	"strconv"
	"strings"
	"testing"
)

// This checks source alignment on non-Android hosts, not JNI initialization.
func TestAndroidAssetMatchesBindingRuntime(t *testing.T) {
	const mobileModule = "github.com/sagernet/gomobile"
	file, err := parser.ParseFile(token.NewFileSet(), "assets_android.go", nil, parser.ImportsOnly)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, spec := range file.Imports {
		path, err := strconv.Unquote(spec.Path.Value)
		if err != nil {
			t.Fatal(err)
		}
		if strings.HasSuffix(path, "/asset") {
			if path != mobileModule+"/asset" {
				t.Fatalf("Android asset import %q does not share the binding runtime's JVM context", path)
			}
			found = true
		}
	}
	if !found {
		t.Fatal("Android asset import is missing")
	}
	initScript, err := os.ReadFile("init.sh")
	if err != nil {
		t.Fatal(err)
	}
	for _, tool := range []string{"gomobile", "gobind"} {
		if !strings.Contains(string(initScript), mobileModule+"/cmd/"+tool+"@$GOMOBILE_COMMIT") {
			t.Fatalf("%s must use the same mobile runtime as Android assets", tool)
		}
	}
	module, err := os.ReadFile("go.mod")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(module), "tool "+mobileModule+"/cmd/gobind") {
		t.Fatal("module binding tool must use the same mobile runtime as Android assets")
	}
}
