package byedpi

import (
	"reflect"
	"strings"
	"testing"
)

func TestSanitizeArgsDropsEmbeddedControlAndDebugArgs(t *testing.T) {
	args := []string{
		"--ip", "0.0.0.0",
		"--port", "1080",
		"--protect-path", "/tmp/protect",
		"-D",
		"-x", "2",
		"--debug", "1",
		"--split", "1",
		"--disorder", "3",
		"--tlsrec", "1+s",
	}
	want := []string{
		"--split", "1",
		"--disorder", "3",
		"--tlsrec", "1+s",
	}

	if got := sanitizeArgs(args); !reflect.DeepEqual(got, want) {
		t.Fatalf("sanitizeArgs() = %#v, want %#v", got, want)
	}
}

func TestSplitArgs(t *testing.T) {
	for _, test := range []struct {
		name  string
		line  string
		want  []string
		wantE bool
	}{
		{
			name: "plain",
			line: "--split 1 --disorder 3",
			want: []string{"--split", "1", "--disorder", "3"},
		},
		{
			name: "empty and repeated whitespace",
			line: "  -Ku\t-a1 \n -An ",
			want: []string{"-Ku", "-a1", "-An"},
		},
		{
			name: "double quotes keep spaces",
			line: `--fake --fake-str "evil evil" -d1`,
			want: []string{"--fake", "--fake-str", "evil evil", "-d1"},
		},
		{
			name: "single quotes are literal",
			line: `--fake 'evil " evil'`,
			want: []string{"--fake", `evil " evil`},
		},
		{
			name: "backslash escapes outside quotes",
			line: `--filter-tcp=443 --oob\ data`,
			want: []string{"--filter-tcp=443", "--oob data"},
		},
		{
			name: "escaped quote inside double quotes",
			line: `--comment "say \"hi\""`,
			want: []string{"--comment", `say "hi"`},
		},
		{
			name:  "unterminated quote",
			line:  `--fake-str "evil`,
			wantE: true,
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			got, err := splitArgs(test.line)
			if test.wantE {
				if err == nil {
					t.Fatalf("splitArgs(%q) = %#v, want error", test.line, got)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(got, test.want) {
				t.Fatalf("splitArgs(%q) = %#v, want %#v", test.line, got, test.want)
			}
		})
	}
}

func TestBuildArgsReservesBridgeControl(t *testing.T) {
	args, err := buildArgs(`--ip 0.0.0.0 --port 1080 --split 1`)
	if err != nil {
		t.Fatal(err)
	}
	joined := strings.Join(args, " ")
	if strings.Contains(joined, "0.0.0.0") || strings.Contains(joined, "1080") {
		t.Fatalf("user override of bridge control survived: %s", joined)
	}
	for _, want := range []string{"--ip", "127.0.0.1", "--port", "0", "--protect-path", "protect_path", "--split", "1"} {
		if !strings.Contains(joined, want) {
			t.Fatalf("buildArgs() = %s, missing %q", joined, want)
		}
	}
	if args[len(args)-2] != "--split" || args[len(args)-1] != "1" {
		t.Fatalf("user args must be appended last, got %#v", args)
	}
}
