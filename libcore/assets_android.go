//go:build android

package libcore

import (
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strconv"

	"golang.org/x/mobile/asset"
)

func extractAssets() {
	useOfficialAssets := intfNB4A.UseOfficialAssets()
	for _, name := range []string{geoipDat, geositeDat, yacdDstFolder} {
		if err := extractAssetName(name, useOfficialAssets); err != nil {
			log.Println("Extract", name, "failed:", err)
		}
	}
}

// this extracts the ones inside the apk
func extractAssetName(name string, useOfficialAssets bool) error {
	// those that support unofficial sources are replaceable, placed in the Android directory
	// those that don't support unofficial sources go in the file directory
	replaceable := true

	var version string
	var apkPrefix string
	switch name {
	case geoipDat:
		version = geoipVersion
		apkPrefix = apkAssetPrefixSingBox
	case geositeDat:
		version = geositeVersion
		apkPrefix = apkAssetPrefixSingBox
	case yacdDstFolder:
		version = yacdVersion
		replaceable = false
	}

	var dir string
	if !replaceable {
		dir = internalAssetsPath
	} else {
		dir = externalAssetsPath
	}
	dstName := dir + name

	b, err := readAsset(apkPrefix + version)
	if err != nil {
		return fmt.Errorf("read version in assets: %v", err)
	}
	assetVersion := string(b)

	var doExtract bool

	if _, err := os.Stat(dstName); err != nil {
		// assetFileMissing
		doExtract = true
	} else if useOfficialAssets || !replaceable {
		// official source upgrade
		b, err := os.ReadFile(dir + version)
		if err != nil {
			// versionFileMissing
			doExtract = true
		} else {
			localVersion := string(b)
			if localVersion == "Custom" {
				doExtract = false
			} else {
				av, err := strconv.ParseUint(assetVersion, 10, 64)
				if err != nil {
					doExtract = assetVersion != localVersion
				} else {
					lv, err := strconv.ParseUint(localVersion, 10, 64)
					doExtract = err != nil || av > lv
				}
			}
		}
	} else {
		//unofficial sources are not upgraded
	}

	if !doExtract {
		return nil
	}

	switch name {
	case yacdDstFolder:
		err = extractArchive("yacd.zip", dstName, unzipReplaceDir)
	default:
		err = extractArchive(apkPrefix+name+".xz", dstName, Unxz)
	}
	if err != nil {
		return err
	}
	// Record the version only once the asset is in place, so a failed
	// extraction is retried on the next start instead of being masked.
	return os.WriteFile(dir+version, []byte(assetVersion), 0o644)
}

func readAsset(name string) ([]byte, error) {
	f, err := asset.Open(name)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return io.ReadAll(f)
}

// extractArchive copies an APK asset to a temp file next to dst and unpacks it
// with unpack, which is responsible for replacing dst atomically.
func extractArchive(assetName, dst string, unpack func(archive, dst string) error) error {
	tmp := dst + filepath.Ext(assetName)
	defer os.Remove(tmp)
	if err := extractAsset(assetName, tmp); err != nil {
		return err
	}
	return unpack(tmp, dst)
}

func extractAsset(name, path string) error {
	i, err := asset.Open(name)
	if err != nil {
		return err
	}
	defer i.Close()
	o, err := os.Create(path)
	if err != nil {
		return err
	}
	_, err = io.Copy(o, i)
	if closeErr := o.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	log.Println("Extract >>", path)
	return nil
}
