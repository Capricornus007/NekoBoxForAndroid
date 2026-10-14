package libcore

import (
	"fmt"
	"github.com/oschwald/maxminddb-golang"
	geosites "github.com/sagernet/sing-box/common/geosite"
	"io"
	"os"
	"path/filepath"
)

// Validate the complete staged database before atomically replacing the current one.
func InstallRouteAsset(staged, destination string) error {
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
		err = reader.Verify()
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
