package libcore

import (
	"bytes"
	"io"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"github.com/oschwald/maxminddb-golang"
	geosites "github.com/sagernet/sing-box/common/geosite"
	"github.com/ulikunitz/xz"
)

// Minimal MMDB: a single IPv4 node maps both halves to "ru". Like the
// official SagerNet database it has an empty, optional description.
func geoipFixture() []byte {
	b := append([]byte{0, 0, 17, 0, 0, 17}, make([]byte, 16)...)
	b = append(b, 0x42, 'r', 'u')
	b = append(b, []byte("\xab\xcd\xefMaxMind.com")...)
	b = append(b, 0xe7)
	text := func(s string) { b = append(b, byte(0x40|len(s))); b = append(b, s...) }
	text("database_type")
	text("sing-geoip")
	text("description")
	b = append(b, 0xe0)
	for _, field := range []struct {
		name  string
		value byte
	}{
		{"binary_format_major_version", 2}, {"binary_format_minor_version", 0},
		{"ip_version", 4}, {"node_count", 1}, {"record_size", 24},
	} {
		text(field.name)
		b = append(b, 0xc1, field.value)
	}
	return b
}

func compressAsset(t *testing.T, value []byte) []byte {
	t.Helper()
	var packed bytes.Buffer
	writer, err := xz.NewWriter(&packed)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = writer.Write(value); err != nil {
		t.Fatal(err)
	}
	if err = writer.Close(); err != nil {
		t.Fatal(err)
	}
	return packed.Bytes()
}

func TestGeoIPWithoutDescriptionIsReadableAndInstallable(t *testing.T) {
	dir := t.TempDir()
	stage := filepath.Join(dir, "stage")
	if err := os.WriteFile(stage, geoipFixture(), 0600); err != nil {
		t.Fatal(err)
	}
	if err := InstallRouteAsset(stage, filepath.Join(dir, geoipDat)); err != nil {
		t.Fatal(err)
	}
	reader, err := maxminddb.Open(filepath.Join(dir, geoipDat))
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	if len(reader.Metadata.Description) != 0 {
		t.Fatal("changed stored metadata")
	}
	networks := reader.Networks()
	if !networks.Next() {
		t.Fatal("missing network")
	}
	var country string
	if _, err = networks.Network(&country); err != nil || country != "ru" {
		t.Fatalf("country %q: %v", country, err)
	}
}

func TestBundledAssetsFirstInstallAndConcurrentProcesses(t *testing.T) {
	var site bytes.Buffer
	if err := geosites.Write(&site, map[string][]geosites.Item{"ru": {{Type: geosites.RuleTypeDomainSuffix, Value: ".ru"}}}); err != nil {
		t.Fatal(err)
	}
	for name, value := range map[string][]byte{geoipDat: geoipFixture(), geositeDat: site.Bytes()} {
		t.Run(name, func(t *testing.T) {
			dir := t.TempDir()
			dst, version := filepath.Join(dir, name), filepath.Join(dir, "version")
			packed := compressAsset(t, value)
			opener := func() (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(packed)), nil }
			var workers sync.WaitGroup
			for range 4 {
				workers.Add(1)
				go func() {
					defer workers.Done()
					if err := installBundledRouteAsset(dst, version, "20261012", true, opener); err != nil {
						t.Error(err)
					}
				}()
			}
			workers.Wait()
			got, _ := os.ReadFile(dst)
			v, _ := os.ReadFile(version)
			if !bytes.Equal(got, value) || string(v) != "20261012" {
				t.Fatal("incomplete first installation")
			}
			for _, local := range []string{"Custom", "20271012"} {
				_ = os.WriteFile(version, []byte(local), 0600)
				if err := installBundledRouteAsset(dst, version, "20261012", true, func() (io.ReadCloser, error) { t.Error("opened preserved database"); return nil, os.ErrNotExist }); err != nil {
					t.Fatal(err)
				}
			}
			_ = os.WriteFile(version, []byte("20251012"), 0600)
			truncated := packed[:len(packed)-4]
			if installBundledRouteAsset(dst, version, "20261012", true, func() (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(truncated)), nil }) == nil {
				t.Fatal("accepted truncated bundle")
			}
			got, _ = os.ReadFile(dst)
			v, _ = os.ReadFile(version)
			if !bytes.Equal(got, value) || string(v) != "20251012" {
				t.Fatal("failed extraction replaced database or version")
			}
			if err := installBundledRouteAsset(dst, version, "20261012", true, opener); err != nil {
				t.Fatal(err)
			}
			v, _ = os.ReadFile(version)
			if string(v) != "20261012" {
				t.Fatal("valid update did not commit version")
			}
		})
	}
}

func TestPinnedRouteDatabases(t *testing.T) {
	dir := os.Getenv("NEKO_ROUTE_ASSET_TEST_DIR")
	if dir == "" {
		t.Skip("set NEKO_ROUTE_ASSET_TEST_DIR to validate downloaded release databases")
	}
	for _, name := range []string{geoipDat, geositeDat} {
		t.Run(name, func(t *testing.T) {
			value, err := os.ReadFile(filepath.Join(dir, name))
			if os.IsNotExist(err) {
				packed, openErr := os.Open(filepath.Join(dir, name+".xz"))
				if openErr != nil {
					t.Fatal(openErr)
				}
				defer packed.Close()
				reader, readErr := xz.NewReader(packed)
				if readErr != nil {
					t.Fatal(readErr)
				}
				value, err = io.ReadAll(reader)
			}
			if err != nil {
				t.Fatal(err)
			}
			stage := filepath.Join(t.TempDir(), "stage")
			if err = os.WriteFile(stage, value, 0600); err != nil {
				t.Fatal(err)
			}
			if err = InstallRouteAsset(stage, filepath.Join(filepath.Dir(stage), name)); err != nil {
				t.Fatal(err)
			}
		})
	}
}
