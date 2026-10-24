package libcore

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/ulikunitz/xz"
	"golang.org/x/sys/unix"
)

// Android's UI and VPN processes share the same asset directory.
func withAssetLock(destination string, action func() error) error {
	lock, err := os.OpenFile(destination+".lock", os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return err
	}
	defer lock.Close()
	if err = unix.Flock(int(lock.Fd()), unix.LOCK_EX); err != nil {
		return err
	}
	defer unix.Flock(int(lock.Fd()), unix.LOCK_UN)
	return action()
}

func writeAssetVersion(path, version string) error {
	file, err := os.CreateTemp(filepath.Dir(path), ".version-*")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	defer file.Close()
	if _, err = io.WriteString(file, version); err != nil {
		return err
	}
	if err = file.Sync(); err != nil {
		return err
	}
	if err = file.Close(); err != nil {
		return err
	}
	return os.Rename(file.Name(), path)
}

func bundledAssetIsNewer(bundled, local string) bool {
	if local == "Custom" {
		return false
	}
	b, be := strconv.ParseUint(bundled, 10, 64)
	l, le := strconv.ParseUint(local, 10, 64)
	if be == nil && le == nil {
		return b > l
	}
	return bundled != local
}

func installBundledRouteAsset(destination, versionPath, version string, official bool, open func() (io.ReadCloser, error)) error {
	return withAssetLock(destination, func() error {
		if _, err := os.Stat(destination); err == nil {
			local, err := os.ReadFile(versionPath)
			// An unversioned user database is also retained.
			if !official || err != nil || !bundledAssetIsNewer(version, strings.TrimSpace(string(local))) {
				return nil
			}
		}
		input, err := open()
		if err != nil {
			return err
		}
		defer input.Close()
		reader, err := xz.NewReader(input)
		if err != nil {
			return err
		}
		staged, err := os.CreateTemp(filepath.Dir(destination), ".bundled-*")
		if err != nil {
			return err
		}
		defer os.Remove(staged.Name())
		defer staged.Close()
		n, err := io.Copy(staged, io.LimitReader(reader, maxAssetBytes+1))
		if err != nil {
			return err
		}
		if n > maxAssetBytes {
			return fmt.Errorf("bundled asset exceeds 512 MiB")
		}
		if err = staged.Sync(); err != nil {
			return err
		}
		if err = staged.Close(); err != nil {
			return err
		}
		if err = installRouteAsset(staged.Name(), destination); err != nil {
			return err
		}
		return writeAssetVersion(versionPath, version)
	})
}
