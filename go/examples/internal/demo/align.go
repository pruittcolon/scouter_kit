package demo

import (
	"encoding/json"
	"os"
	"path/filepath"
)

// Align is the eyes' alignment (Config.DX, DY) that fits the wearer: examples/screen adjusts it on the glasses
// and saves it, and every demo's -dx and -dy default to it.
type Align struct {
	DX int `json:"dx"`
	DY int `json:"dy"`
}

// AlignPath is the saved alignment's file: scouterkit/align.json in the user's configuration directory.
func AlignPath() string {
	dir, err := os.UserConfigDir()
	if err != nil {
		return ""
	}
	return filepath.Join(dir, "scouterkit", "align.json")
}

// LoadAlign returns the saved alignment, or the library's default when none is saved.
func LoadAlign() Align {
	a := Align{DX: 100, DY: 9}
	if b, err := os.ReadFile(AlignPath()); err == nil {
		_ = json.Unmarshal(b, &a)
	}
	return a
}

// SaveAlign keeps a for the next demo.
func SaveAlign(a Align) error {
	p := AlignPath()
	if p == "" {
		return os.ErrNotExist
	}
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		return err
	}
	b, _ := json.MarshalIndent(a, "", "  ")
	return os.WriteFile(p, append(b, '\n'), 0o644)
}
