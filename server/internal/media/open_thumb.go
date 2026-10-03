// Copyright (c) 2026-present Glagolitsa contributors. All Rights Reserved.
// See LICENSE for license information.

package media

import (
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"image/jpeg"
	_ "image/png"
	"io"
	"math"

	xdraw "golang.org/x/image/draw"
	_ "golang.org/x/image/webp"
)

// Gallery thumb edge (Telegram-ish small grid thumb).
const OpenThumbMaxEdge = 320

// buildOpenJPEGThumb decodes jpeg/png/webp (and common variants) and returns a JPEG thumb.
func buildOpenJPEGThumb(r io.Reader) ([]byte, error) {
	// Buffer fully so we can retry decode strategies.
	raw, err := io.ReadAll(r)
	if err != nil {
		return nil, err
	}
	if len(raw) == 0 {
		return nil, fmt.Errorf("empty image")
	}
	src, err := decodeOpenImage(raw)
	if err != nil {
		return nil, err
	}
	b := src.Bounds()
	w, h := b.Dx(), b.Dy()
	if w <= 0 || h <= 0 {
		return nil, fmt.Errorf("invalid image bounds")
	}
	scale := math.Min(float64(OpenThumbMaxEdge)/float64(w), float64(OpenThumbMaxEdge)/float64(h))
	if scale > 1 {
		scale = 1
	}
	nw := int(math.Max(1, math.Round(float64(w)*scale)))
	nh := int(math.Max(1, math.Round(float64(h)*scale)))

	// Draw onto opaque white so JPEG never sees alpha weirdness (PNG with alpha).
	dst := image.NewRGBA(image.Rect(0, 0, nw, nh))
	draw.Draw(dst, dst.Bounds(), &image.Uniform{C: color.White}, image.Point{}, draw.Src)
	xdraw.CatmullRom.Scale(dst, dst.Bounds(), src, b, xdraw.Over, nil)

	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, dst, &jpeg.Options{Quality: 78}); err != nil {
		return nil, err
	}
	if buf.Len() == 0 {
		return nil, fmt.Errorf("empty thumb")
	}
	return buf.Bytes(), nil
}

func decodeOpenImage(raw []byte) (image.Image, error) {
	img, _, err := image.Decode(bytes.NewReader(raw))
	if err == nil {
		return img, nil
	}
	// Fallback: try JPEG config then re-decode (some clients send JFIF quirks).
	if _, jerr := jpeg.DecodeConfig(bytes.NewReader(raw)); jerr == nil {
		if j, jerr2 := jpeg.Decode(bytes.NewReader(raw)); jerr2 == nil {
			return j, nil
		}
	}
	return nil, err
}
