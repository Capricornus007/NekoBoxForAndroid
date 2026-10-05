//go:build android

package libcore

import (
	"fmt"
	"io"
	"log"
	"os"
	"strconv"

	"github.com/sagernet/gomobile/asset"
)

func extractAssets() {
	useOfficialAssets := intfNB4A.UseOfficialAssets()

	extract := func(name string) {
		err := extractAssetName(name, useOfficialAssets)
		if err != nil {
			log.Println("Extract", geoipDat, "failed:", err)
		}
	}

	extract(geoipDat)
	extract(geositeDat)
}

// this extracts the ones inside the apk
func extractAssetName(name string, useOfficialAssets bool) error {
	// APK 內建資產一律可被官方來源取代，所以落在 external 目錄。
	var version string
	var apkPrefix string
	switch name {
	case geoipDat:
		version = geoipVersion
		apkPrefix = apkAssetPrefixSingBox
	case geositeDat:
		version = geositeVersion
		apkPrefix = apkAssetPrefixSingBox
	}

	dir := externalAssetsPath
	dstName := dir + name

	var localVersion string
	var assetVersion string

	// loadAssetVersion from APK
	loadAssetVersion := func() error {
		av, err := asset.Open(apkPrefix + version)
		if err != nil {
			return fmt.Errorf("open version in assets: %v", err)
		}
		b, err := io.ReadAll(av)
		av.Close()
		if err != nil {
			return fmt.Errorf("read internal version: %v", err)
		}
		assetVersion = string(b)
		return nil
	}
	if err := loadAssetVersion(); err != nil {
		return err
	}

	var doExtract bool

	if _, err := os.Stat(dstName); err != nil {
		// assetFileMissing
		doExtract = true
	} else if useOfficialAssets {
		// official source upgrade
		b, err := os.ReadFile(dir + version)
		if err != nil {
			// versionFileMissing
			doExtract = true
			_ = os.RemoveAll(version)
		} else {
			localVersion = string(b)
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

	extractXz := func(f asset.File) error {
		tmpXzName := dstName + ".xz"
		err := extractAsset(f, tmpXzName)
		if err == nil {
			err = Unxz(tmpXzName, dstName)
			os.Remove(tmpXzName)
		}
		if err != nil {
			return fmt.Errorf("extract xz: %v", err)
		}
		return nil
	}

	if f, err := asset.Open(apkPrefix + name + ".xz"); err == nil {
		extractXz(f)
	} // TODO normal file

	o, err := os.Create(dir + version)
	if err != nil {
		return fmt.Errorf("create version: %v", err)
	}
	_, err = io.WriteString(o, assetVersion)
	o.Close()
	return err
}

func extractAsset(i asset.File, path string) error {
	defer i.Close()
	o, err := os.Create(path)
	if err != nil {
		return err
	}
	defer o.Close()
	_, err = io.Copy(o, i)
	if err == nil {
		log.Println("Extract >>", path)
	}
	return err
}
