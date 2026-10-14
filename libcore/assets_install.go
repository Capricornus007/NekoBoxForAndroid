package libcore

import (
	"fmt"
	"github.com/oschwald/maxminddb-golang"
	geosites "github.com/sagernet/sing-box/common/geosite"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// Validate the complete staged database before atomically replacing the current one.
func InstallRouteAsset(staged, destination string) error {
	return withAssetLock(destination, func() error { return installRouteAsset(staged, destination) })
}

func InstallRouteAssetVersion(staged, destination, version string) error {
	if len(version) == 0 || len(version) > 128 {
		return fmt.Errorf("invalid asset version")
	}
	return withAssetLock(destination, func() error {
		if err := installRouteAsset(staged, destination); err != nil {
			return err
		}
		return writeAssetVersion(strings.TrimSuffix(destination, ".db")+".version.txt", version)
	})
}

func installRouteAsset(staged, destination string) error {
	info, err := os.Stat(staged)
	if err != nil {
		return err
	}
	if info.Size() > maxAssetBytes {
		return fmt.Errorf("route asset exceeds 512 MiB")
	}
	switch filepath.Base(destination) {
	case geoipDat:
		reader, err := maxminddb.Open(staged)
		if err != nil {
			return err
		}
		if reader.Metadata.DatabaseType != "sing-geoip" {
			reader.Close()
			return fmt.Errorf("expected sing-geoip database")
		}
		// SagerNet intentionally omits the optional description. Verify is
		// stricter than the MMDB specification; retain its structural checks
		// using a validation-only copy, without changing the database.
		checked := *reader
		if len(checked.Metadata.Description) == 0 {
			checked.Metadata.Description = map[string]string{"en": "sing-geoip"}
		}
		err = checked.Verify()
		if err == nil {
			networks := reader.Networks(maxminddb.SkipAliasedNetworks)
			for networks.Next() {
				var country string
				if _, err = networks.Network(&country); err != nil {
					break
				}
				if country == "" {
					err = fmt.Errorf("empty GeoIP country")
					break
				}
			}
			if err == nil {
				err = networks.Err()
			}
		}
		closeErr := reader.Close()
		if err != nil {
			return err
		}
		if closeErr != nil {
			return closeErr
		}
	case geositeDat:
		reader, codes, err := geosites.Open(staged)
		if err != nil {
			return err
		}
		closer := reader.Upstream().(io.Closer)
		for _, code := range codes {
			if _, err = reader.Read(code); err != nil {
				_ = closer.Close()
				return err
			}
		}
		if err = closer.Close(); err != nil {
			return err
		}
	default:
		return fmt.Errorf("unsupported route asset: %s", filepath.Base(destination))
	}
	return os.Rename(staged, destination)
}
