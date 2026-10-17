//go:build android

package libcore

import (
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/mobile/asset"
)

func extractAssets() {
	for _, name := range []string{geoipDat, geositeDat, yacdDstFolder} {
		if err := extractAssetName(name, intfNB4A.UseOfficialAssets()); err != nil {
			log.Println("Extract", name, "failed:", err)
		}
	}
}

func extractAssetName(name string, official bool) error {
	versionName, prefix, dir := yacdVersion, "", internalAssetsPath
	switch name {
	case geoipDat:
		versionName, prefix, dir = geoipVersion, apkAssetPrefixSingBox, externalAssetsPath
	case geositeDat:
		versionName, prefix, dir = geositeVersion, apkAssetPrefixSingBox, externalAssetsPath
	case yacdDstFolder:
	default:
		return fmt.Errorf("unsupported bundled asset: %s", name)
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	versionFile, err := asset.Open(prefix + versionName)
	if err != nil {
		return fmt.Errorf("open bundled version: %w", err)
	}
	contents, err := io.ReadAll(io.LimitReader(versionFile, 128))
	versionFile.Close()
	if err != nil {
		return err
	}
	version := strings.TrimSpace(string(contents))
	if version == "" {
		return fmt.Errorf("empty bundled version")
	}
	destination, versionPath := filepath.Join(dir, name), filepath.Join(dir, versionName)
	if name != yacdDstFolder {
		return installBundledRouteAsset(destination, versionPath, version, official, func() (io.ReadCloser, error) {
			return asset.Open(prefix + name + ".xz")
		})
	}
	return withAssetLock(destination, func() error {
		local, _ := os.ReadFile(versionPath)
		if _, err := os.Stat(destination); err == nil && !bundledAssetIsNewer(version, strings.TrimSpace(string(local))) {
			return nil
		}
		stage, err := os.MkdirTemp(dir, ".yacd-*")
		if err != nil {
			return err
		}
		defer os.RemoveAll(stage)
		input, err := asset.Open("yacd.zip")
		if err != nil {
			return err
		}
		archive := filepath.Join(stage, "yacd.zip")
		if err = extractAsset(input, archive); err != nil {
			return err
		}
		if err = Unzip(archive, stage); err != nil {
			return err
		}
		matches, err := filepath.Glob(filepath.Join(stage, "Yacd-*"))
		if err != nil {
			return err
		}
		if len(matches) != 1 {
			return fmt.Errorf("expected one Yacd directory, got %d", len(matches))
		}
		backup := filepath.Join(stage, "previous")
		hadOld := false
		if _, err = os.Stat(destination); err == nil {
			if err = os.Rename(destination, backup); err != nil {
				return err
			}
			hadOld = true
		}
		if err = os.Rename(matches[0], destination); err != nil {
			if hadOld {
				_ = os.Rename(backup, destination)
			}
			return err
		}
		return writeAssetVersion(versionPath, version)
	})
}

func extractAsset(input asset.File, path string) error {
	defer input.Close()
	output, err := os.Create(path)
	if err != nil {
		return err
	}
	defer output.Close()
	n, err := io.Copy(output, io.LimitReader(input, maxAssetBytes+1))
	if err != nil {
		return err
	}
	if n > maxAssetBytes {
		return fmt.Errorf("compressed asset exceeds 512 MiB")
	}
	return output.Close()
}
