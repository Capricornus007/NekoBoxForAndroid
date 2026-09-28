package libcore

import (
	"archive/zip"
	"io"
	"os"
	"path/filepath"

	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/ulikunitz/xz"
)

const defaultUnxzFileLimit = 256 * 1024 * 1024

func Unxz(archive string, path string) (err error) {
	i, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer i.Close()

	r, err := xz.NewReader(i)
	if err != nil {
		return err
	}

	dir, base := filepath.Split(path)
	if dir == "" {
		dir = "."
	}
	o, err := os.CreateTemp(dir, base+".*.tmp")
	if err != nil {
		return err
	}
	tmpPath := o.Name()
	defer func() {
		if err != nil {
			_ = os.Remove(tmpPath)
		}
	}()
	defer func() {
		if closeErr := o.Close(); err == nil {
			err = closeErr
		}
		if err == nil {
			err = os.Rename(tmpPath, path)
		}
	}()

	_, err = copyLimited(o, r, defaultUnxzFileLimit)
	return err
}

// unzipReplaceDir unpacks an archive whose only top-level entry is a directory
// and moves that directory to dst. dst is left untouched if unpacking fails,
// and restored if the final swap fails.
func unzipReplaceDir(archive, dst string) error {
	tmpDir, err := os.MkdirTemp(filepath.Dir(dst), filepath.Base(dst)+".*.tmp")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmpDir)
	unpacked := filepath.Join(tmpDir, "new")
	if err := unzip(archive, unpacked); err != nil {
		return err
	}
	entries, err := os.ReadDir(unpacked)
	if err != nil {
		return err
	}
	if len(entries) != 1 || !entries[0].IsDir() {
		return E.New("unzip ", archive, ": expected one top-level directory, found ", len(entries), " entries")
	}
	// Park the current dst inside tmpDir: the deferred cleanup removes it on
	// success, and it can be moved back if the swap fails.
	previous := filepath.Join(tmpDir, "old")
	if err := os.Rename(dst, previous); err != nil && !os.IsNotExist(err) {
		return err
	}
	if err := os.Rename(filepath.Join(unpacked, entries[0].Name()), dst); err != nil {
		_ = os.Rename(previous, dst)
		return err
	}
	return nil
}

func unzip(archive string, path string) error {
	r, err := zip.OpenReader(archive)
	if err != nil {
		return err
	}
	defer r.Close()

	err = os.MkdirAll(path, os.ModePerm)
	if err != nil {
		return err
	}

	for _, file := range r.File {
		filePath := filepath.Join(path, file.Name)

		if file.FileInfo().IsDir() {
			err = os.MkdirAll(filePath, os.ModePerm)
			if err != nil {
				return err
			}
			continue
		}

		newFile, err := os.Create(filePath)
		if err != nil {
			return err
		}

		zipFile, err := file.Open()
		if err != nil {
			newFile.Close()
			return err
		}

		var errs error
		_, err = io.Copy(newFile, zipFile)
		errs = E.Errors(errs, err)
		errs = E.Errors(errs, common.Close(zipFile, newFile))
		if errs != nil {
			return errs
		}
	}

	return nil
}
