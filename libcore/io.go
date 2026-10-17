package libcore

import (
	"archive/zip"
	"errors"
	"github.com/ulikunitz/xz"
	"io"
	"os"
	"path/filepath"
)

const maxAssetBytes int64 = 512 * 1024 * 1024

func Unxz(archive, path string) error {
	input, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer input.Close()
	reader, err := xz.NewReader(input)
	if err != nil {
		return err
	}
	output, err := os.CreateTemp(filepath.Dir(path), ".asset-*")
	if err != nil {
		return err
	}
	defer os.Remove(output.Name())
	defer output.Close()
	n, err := io.Copy(output, io.LimitReader(reader, maxAssetBytes+1))
	if err != nil {
		return err
	}
	if n > maxAssetBytes {
		return errors.New("decompressed asset exceeds 512 MiB")
	}
	if err = output.Sync(); err != nil {
		return err
	}
	if err = output.Close(); err != nil {
		return err
	}
	return os.Rename(output.Name(), path)
}

func Unzip(archive, path string) error {
	reader, err := zip.OpenReader(archive)
	if err != nil {
		return err
	}
	defer reader.Close()
	if err = os.MkdirAll(path, 0700); err != nil {
		return err
	}
	var total int64
	for _, file := range reader.File {
		if !filepath.IsLocal(file.Name) || file.Mode()&os.ModeSymlink != 0 {
			return errors.New("unsafe ZIP entry")
		}
		destination := filepath.Join(path, file.Name)
		if file.FileInfo().IsDir() {
			if err = os.MkdirAll(destination, 0700); err != nil {
				return err
			}
			continue
		}
		if err = os.MkdirAll(filepath.Dir(destination), 0700); err != nil {
			return err
		}
		err = func() error {
			input, err := file.Open()
			if err != nil {
				return err
			}
			defer input.Close()
			output, err := os.Create(destination)
			if err != nil {
				return err
			}
			defer output.Close()
			n, err := io.Copy(output, io.LimitReader(input, maxAssetBytes-total+1))
			total += n
			if err != nil {
				return err
			}
			if total > maxAssetBytes {
				return errors.New("ZIP exceeds 512 MiB")
			}
			return output.Close()
		}()
		if err != nil {
			return err
		}
	}
	return nil
}
