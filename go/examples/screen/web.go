package main

// The control page: every setting on sliders and switches, applied live, saved at each change. It listens on
// this computer only (127.0.0.1). Changes need the X-Scouter header, which a page of another site cannot send
// here (no CORS), so only this page can change the glasses.

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"time"

	"scouterkit"
	"scouterkit/examples/internal/demo"
)

//go:embed index.html
var indexHTML []byte

type webState struct {
	Settings   settings   `json:"settings"`
	Align      demo.Align `json:"align"`
	Paused     bool       `json:"paused"`
	Realigning bool       `json:"realigning"`
	Connected  bool       `json:"connected"`
	Display    [2]int     `json:"display"` // the glasses' display, w and h
	Screen     [2]int     `json:"screen"`  // this computer's screen
	Battery    [2]battery `json:"battery"` // left, right
	Frames     int        `json:"frames"`
	Hidden     string     `json:"hidden"` // why the panel is off now, or ""
}

type battery struct {
	Percent  int  `json:"percent"` // -1: unknown
	Charging bool `json:"charging"`
}

func (st *state) snapshot() webState {
	st.mu.Lock()
	defer st.mu.Unlock()
	w := webState{Settings: st.s, Align: st.align, Paused: st.paused, Realigning: !st.realignAt.IsZero(),
		Connected: st.connected, Display: st.display, Screen: st.screen, Frames: st.frames, Hidden: st.hidden}
	for i, b := range st.battery {
		w.Battery[i] = battery{Percent: b.Percent, Charging: b.Charging}
	}
	return w
}

// patch applies the fields present in body: settings by their JSON names, and dx, dy, paused, reset.
func (st *state) patch(body []byte) error {
	var p map[string]json.RawMessage
	if err := json.Unmarshal(body, &p); err != nil {
		return err
	}
	st.mu.Lock()
	defer st.mu.Unlock()
	if r, ok := p["reset"]; ok && string(r) == "true" {
		d := defaults()
		d.Brightness = st.s.Brightness
		st.s = d
	}
	// The settings: the current ones with the given fields over them.
	cur, _ := json.Marshal(st.s)
	var merged map[string]json.RawMessage
	_ = json.Unmarshal(cur, &merged)
	for k, v := range p {
		if _, ok := merged[k]; ok {
			merged[k] = v
		}
	}
	b, _ := json.Marshal(merged)
	next := st.s
	if err := json.Unmarshal(b, &next); err != nil {
		return err
	}
	if next.Mode != "follow" && next.Mode != "full" && next.Mode != "fixed" {
		next.Mode = st.s.Mode
	}
	if next.Mode == "fixed" && st.s.Mode != "fixed" {
		next.FixedX, next.FixedY = -1, -1 // pinned where the pointer is now
	}
	next.Zoom = clampF(next.Zoom, 0.2, 6)
	next.Gamma = clampF(next.Gamma, 0.3, 3)
	next.FPS = clampF(next.FPS, 1, 30)
	next.Brightness = clamp(next.Brightness, 0, 42)
	next.LookUp = clamp(next.LookUp, 0, 60)
	if next.Levels != 2 {
		next.Levels = 16
	}
	st.s = next
	align := st.align
	for k, dst := range map[string]*int{"dx": &align.DX, "dy": &align.DY} {
		if v, ok := p[k]; ok {
			_ = json.Unmarshal(v, dst)
		}
	}
	align.DX, align.DY = clamp(align.DX, -320, 320), clamp(align.DY, -64, 64)
	if align != st.align {
		st.align = align
		st.realignAt = time.Now().Add(800 * time.Millisecond)
	}
	if v, ok := p["paused"]; ok {
		_ = json.Unmarshal(v, &st.paused)
	}
	st.changed = true
	saveAll(st)
	return nil
}

func serve(addr string, st *state) (string, error) {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return "", err
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write(indexHTML)
	})
	mux.HandleFunc("/api/state", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(st.snapshot())
	})
	mux.HandleFunc("/api/set", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.Header.Get("X-Scouter") != "1" {
			http.Error(w, "POST with X-Scouter: 1", http.StatusForbidden)
			return
		}
		body, err := io.ReadAll(io.LimitReader(r.Body, 8192))
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		if err := st.patch(body); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(st.snapshot())
	})
	fmt.Printf("Control page: http://%s/\n", ln.Addr())
	go func() { _ = http.Serve(ln, mux) }()
	return ln.Addr().String(), nil
}

func clampF(v, lo, hi float64) float64 {
	if v != v || v < lo { // NaN too
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

// report: the pusher's view of the glasses, for the page.
func (st *state) report(g *glasses, x *xscreen) {
	st.mu.Lock()
	defer st.mu.Unlock()
	st.connected = g != nil
	if g != nil {
		st.display = [2]int{g.dw, g.dh}
		st.battery = [2]scouterkit.Battery{g.battery[0], g.battery[1]}
		st.frames = g.sent
	}
	st.screen = [2]int{x.W, x.H}
}
