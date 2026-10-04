package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"testing"
)

// TestSelftestChecksRunGreenOnHost executes the same offline checks the shipped
// binary runs, so buildScript/lib/protonvpn.sh fails before cross-compiling when
// a pinned Proton dependency stops working.
func TestSelftestChecksRunGreenOnHost(t *testing.T) {
	for _, check := range selftestChecks() {
		check := check
		t.Run(check.name, func(t *testing.T) {
			detail, err := check.run()
			if err != nil {
				t.Fatalf("%s failed: %v", check.name, err)
			}
			if detail == "" {
				t.Fatalf("%s reported success without a detail line", check.name)
			}
			t.Logf("%s: %s", check.name, detail)
		})
	}
}

func TestRunSelftestReportsEveryCheck(t *testing.T) {
	var out bytes.Buffer

	ok, err := runSelftest(&out)
	if err != nil {
		t.Fatalf("runSelftest returned an error: %v", err)
	}
	if !ok {
		t.Fatalf("selftest is not green on the build host: %s", out.String())
	}

	var report selftestOutput
	if err := json.Unmarshal(out.Bytes(), &report); err != nil {
		t.Fatalf("selftest output is not valid JSON (%q): %v", out.String(), err)
	}
	if len(report.Checks) != len(selftestChecks()) {
		t.Fatalf("selftest reported %d checks, want %d", len(report.Checks), len(selftestChecks()))
	}
	if report.OK != ok {
		t.Fatalf("report ok=%v but runSelftest returned %v", report.OK, ok)
	}
	if report.Sidecar != "libprotonvpn.so" {
		t.Fatalf("unexpected sidecar name %q", report.Sidecar)
	}
	for _, check := range report.Checks {
		if !check.OK {
			t.Errorf("check %s failed inside the report: %s", check.Name, check.Error)
		}
	}
}

func TestRunSelftestChecksMarksReportFailed(t *testing.T) {
	checks := []selftestCheck{
		{"good", func() (string, error) { return "fine", nil }},
		{"broken", func() (string, error) { return "", errors.New("injected failure") }},
	}

	var out bytes.Buffer
	ok, err := runSelftestChecks(&out, checks)
	if err != nil {
		t.Fatalf("runSelftestChecks returned an error: %v", err)
	}
	if ok {
		t.Fatal("a failing check did not flip the report to not-ok")
	}

	var report selftestOutput
	if err := json.Unmarshal(out.Bytes(), &report); err != nil {
		t.Fatalf("report is not valid JSON (%q): %v", out.String(), err)
	}
	if report.OK {
		t.Fatal("report.ok stayed true with a failing check")
	}
	if len(report.Checks) != 2 || report.Checks[1].OK || report.Checks[1].Error == "" {
		t.Fatalf("failing check was not reported: %+v", report.Checks)
	}
}

func TestSelftestCommandExitCodeFollowsReport(t *testing.T) {
	var stdout, stderr bytes.Buffer

	if code := run([]string{"selftest"}, nil, &stdout, &stderr); code != 0 {
		t.Fatalf("selftest exited with %d, stderr: %q", code, stderr.String())
	}
	if stderr.Len() != 0 {
		t.Fatalf("selftest wrote to stderr: %q", stderr.String())
	}
}

func TestVersionCommandPrintsJSON(t *testing.T) {
	var stdout, stderr bytes.Buffer

	if code := run([]string{"version"}, nil, &stdout, &stderr); code != 0 {
		t.Fatalf("version exited with %d, stderr: %q", code, stderr.String())
	}

	var report versionOutput
	if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
		t.Fatalf("version output is not valid JSON (%q): %v", stdout.String(), err)
	}
	if report.Sidecar != "libprotonvpn.so" {
		t.Fatalf("unexpected sidecar name %q", report.Sidecar)
	}
	if report.SRPVersion == "" {
		t.Fatal("srp version is empty, the SRP module is not linked in")
	}
}

func TestRunRejectsUnknownCommand(t *testing.T) {
	var stdout, stderr bytes.Buffer

	if code := run([]string{"definitely-not-a-command"}, nil, &stdout, &stderr); code != 2 {
		t.Fatalf("unknown command exited with %d, want 2 (stderr %q)", code, stderr.String())
	}
	if !bytes.Contains(stderr.Bytes(), []byte("unknown command")) {
		t.Fatalf("unknown command did not explain itself, stderr: %q", stderr.String())
	}
}

func TestRunWithoutArgumentsShowsUsage(t *testing.T) {
	var stdout, stderr bytes.Buffer

	if code := run(nil, nil, &stdout, &stderr); code != 2 {
		t.Fatalf("empty argv exited with %d, want 2", code)
	}
	if !bytes.Contains(stderr.Bytes(), []byte("usage:")) {
		t.Fatalf("empty argv did not print usage, stderr: %q", stderr.String())
	}
}
