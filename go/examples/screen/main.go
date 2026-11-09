// Command screen mirrors this computer's screen (X11) on both eyes: the area around the mouse pointer, the
// whole screen, or a fixed region, with a bar of the glasses' battery levels and the time. Everything is
// adjustable while wearing the glasses: the hotkey (default Ctrl+Alt+G) starts adjust mode, in which the
// keyboard belongs to this program and the bar shows the values; Enter or Esc saves them
// (scouterkit/screen.json in the user's configuration directory, the eyes' alignment in align.json, which
// every demo then uses).
//
// Black is see-through on the glasses and lit pixels cost battery, so the picture is inverted by default:
// a light desktop becomes light text on a clear background.
//
//	screen [-hotkey ctrl+alt+g] [-mode follow|full|fixed] [-fps 10] [-zoom 1] [-reset] [-dx N -dy N]
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"image"
	"math"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"scouterkit"
	"scouterkit/examples/internal/demo"
)

// settings are what adjust mode changes; saved as JSON.
type settings struct {
	Mode       string  `json:"mode"` // follow (the pointer), full (the whole screen) or fixed (a region)
	X          int     `json:"x"`    // the picture on the glasses' display (with the bar); W, H 0: default
	Y          int     `json:"y"`
	W          int     `json:"w"`
	H          int     `json:"h"`
	Zoom       float64 `json:"zoom"`    // screen pixels per glasses pixel, inverted: 2 doubles the size
	FixedX     int     `json:"fixed_x"` // the fixed region's top left corner on the screen
	FixedY     int     `json:"fixed_y"`
	Invert     bool    `json:"invert"`
	Gamma      float64 `json:"gamma"`      // > 1 dims the midtones, < 1 lifts them
	Levels     int     `json:"levels"`     // 16 greys or 2 (sharper, lighter on the link)
	Brightness int     `json:"brightness"` // 0 ... 42
	Bar        bool    `json:"bar"`        // the battery bar
	Clock      bool    `json:"clock"`
	FPS        float64 `json:"fps"`
	// Power: the panel off while an arm lies in the case or the glasses are folded (they charge better and the
	// battery lasts); LookUp > 0 shows the picture only while the head looks up by at least that many degrees.
	HideIdle bool `json:"hide_idle"`
	LookUp   int  `json:"look_up"`
}

func defaults() settings {
	return settings{Mode: "follow", Zoom: 1, Invert: true, Gamma: 1, Levels: 16, Brightness: 21, Bar: true,
		Clock: true, FPS: 10, HideIdle: true}
}

func settingsPath() string {
	dir, err := os.UserConfigDir()
	if err != nil {
		return ""
	}
	return filepath.Join(dir, "scouterkit", "screen.json")
}

func load() settings {
	s := defaults()
	if b, err := os.ReadFile(settingsPath()); err == nil {
		_ = json.Unmarshal(b, &s)
	}
	return s
}

func (s settings) save() error {
	p := settingsPath()
	if p == "" {
		return os.ErrNotExist
	}
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		return err
	}
	b, _ := json.MarshalIndent(s, "", "  ")
	return os.WriteFile(p, append(b, '\n'), 0o644)
}

// state is shared by the key handler (main goroutine) and the pusher.
type state struct {
	mu        sync.Mutex
	s         settings
	align     demo.Align
	adjusting bool
	paused    bool
	realignAt time.Time // the alignment changed: reconnect at this time (keys may still come)
	changed   bool      // anything changed: redraw even if the screen did not
	// The pusher's report, for the control page.
	connected bool
	display   [2]int
	screen    [2]int
	battery   [2]scouterkit.Battery
	frames    int
	hidden    string // why the panel is off ("in the case", "folded", "looking ahead"), or ""
}

func main() {
	var f demo.Flags
	f.Register()
	hotkey := flag.String("hotkey", "ctrl+alt+g", "starts adjust mode (Enter or Esc saves and ends it)")
	mode := flag.String("mode", "", "follow (around the pointer), full (whole screen) or fixed (a region); default: saved")
	fps := flag.Float64("fps", 0, "frames per second (default: saved, else 10)")
	zoom := flag.Float64("zoom", 0, "magnification (default: saved, else 1)")
	reset := flag.Bool("reset", false, "start from the default settings")
	web := flag.String("http", "127.0.0.1:8737", "the control page's address (empty: none)")
	demo.Usage("screen [flags]")
	flag.Parse()

	s := load()
	if *reset {
		s = defaults()
	}
	if *mode != "" {
		s.Mode = *mode
	}
	if *fps > 0 {
		s.FPS = *fps
	}
	if *zoom > 0 {
		s.Zoom = *zoom
	}
	if err := run(f, *hotkey, *web, s); err != nil {
		fmt.Fprintln(os.Stderr, "screen:", err)
		os.Exit(1)
	}
}

func run(f demo.Flags, hotkey, web string, s settings) error {
	x, err := openX()
	if err != nil {
		return err
	}
	st := &state{s: s, align: demo.Align{DX: f.DX, DY: f.DY}, changed: true}
	if web != "" {
		if _, err := serve(web, st); err != nil {
			fmt.Fprintln(os.Stderr, "screen: no control page:", err)
		}
	}
	keys := make(chan key, 64)
	if err := x.listen(hotkey, keys); err != nil {
		fmt.Fprintln(os.Stderr, "screen: no adjust mode:", err)
	} else {
		fmt.Printf("Adjust while wearing the glasses: press %s.\n", hotkey)
	}
	stop := demo.Interrupted()
	done := make(chan error, 1)
	go func() { done <- push(f, x, st, stop) }()
	for {
		select {
		case k := <-keys:
			handle(st, k)
		case err := <-done:
			st.mu.Lock()
			saveAll(st)
			st.mu.Unlock()
			return err
		}
	}
}

func saveAll(st *state) {
	if err := st.s.save(); err != nil {
		fmt.Fprintln(os.Stderr, "screen: settings not saved:", err)
	}
	if err := demo.SaveAlign(st.align); err != nil {
		fmt.Fprintln(os.Stderr, "screen: alignment not saved:", err)
	}
}

const help = `Adjust mode (the keyboard is this program's until Enter or Esc):
  arrows        move the picture on the glasses (Shift: 1 px)
  A / D         narrower / wider        W / S   taller / shorter (Shift: 1 px)
  + / -         zoom in / out           , / .   darker / brighter midtones
  PgUp / PgDn   brightness              N       invert on / off
  J / L         eyes' alignment: horizontal (dx)
  I / K         eyes' alignment: vertical (dy)      (reconnects for a few seconds)
  M             mode: follow pointer / whole screen / fixed region
  Q             16 greys / 2 levels     F       frame rate 5 / 10 / 15 / 20 / 30
  B             battery bar on / off    T       clock on / off
  P             pause (glasses clear)   R       reset the picture (not the alignment)
  Enter / Esc   save and leave`

func handle(st *state, k key) {
	st.mu.Lock()
	defer st.mu.Unlock()
	s := &st.s
	st.changed = true
	if k.start {
		st.adjusting = true
		fmt.Println(help)
		return
	}
	if k.done {
		st.adjusting = false
		saveAll(st)
		fmt.Println("saved")
		return
	}
	step := 8
	if k.shift {
		step = 1
	}
	realign := func() { st.realignAt = time.Now().Add(800 * time.Millisecond) }
	switch k.sym {
	case kLeft:
		s.X -= step
	case kRight:
		s.X += step
	case kUp:
		s.Y -= step
	case kDown:
		s.Y += step
	case letter('a'):
		s.W -= step
	case letter('d'):
		s.W += step
	case letter('w'):
		s.H += step
	case letter('s'):
		s.H -= step
	case kPlus, kKpPlus:
		s.Zoom = math.Min(s.Zoom*1.1, 6)
	case kMinus, kKpMinus:
		s.Zoom = math.Max(s.Zoom/1.1, 0.2)
	case kComma:
		s.Gamma = math.Min(s.Gamma+0.1, 3)
	case kPeriod:
		s.Gamma = math.Max(s.Gamma-0.1, 0.3)
	case kPageUp:
		s.Brightness = min(s.Brightness+2, 42)
	case kPageDown:
		s.Brightness = max(s.Brightness-2, 0)
	case letter('n'):
		s.Invert = !s.Invert
	case letter('j'):
		st.align.DX -= max(step/4, 1)
		realign()
	case letter('l'):
		st.align.DX += max(step/4, 1)
		realign()
	case letter('i'):
		st.align.DY -= max(step/4, 1)
		realign()
	case letter('k'):
		st.align.DY += max(step/4, 1)
		realign()
	case letter('m'):
		s.Mode = map[string]string{"follow": "full", "full": "fixed", "fixed": "follow"}[s.Mode]
		if s.Mode == "" {
			s.Mode = "follow"
		}
		if s.Mode == "fixed" {
			s.FixedX, s.FixedY = -1, -1 // the pusher pins the region where the pointer is now
		}
	case letter('q'):
		s.Levels = map[int]int{16: 2, 2: 16}[s.Levels]
	case letter('f'):
		rates := []float64{5, 10, 15, 20, 30}
		next := rates[0]
		for _, r := range rates {
			if r > s.FPS {
				next = r
				break
			}
		}
		s.FPS = next
	case letter('b'):
		s.Bar = !s.Bar
	case letter('t'):
		s.Clock = !s.Clock
	case letter('p'):
		st.paused = !st.paused
	case letter('r'):
		d := defaults()
		d.Brightness = s.Brightness
		*s = d
	default:
		st.changed = false
	}
}

// The glasses' side: one connection, its display size, the picture and bar bitmaps and the bar's font.
type glasses struct {
	cfg         scouterkit.Config
	arms        []scouterkit.Arm
	dw, dh      int
	font        *scouterkit.Font
	pic, bar    *scouterkit.Bitmap
	pw, ph, bw  int
	brightness  int
	battery     [2]scouterkit.Battery
	batteryAt   time.Time
	barText     string
	frame, prev []byte
	captureBuf  []byte
	sent        int // frames shown on the glasses
	started     time.Time
	hiddenWhy   string
	propsAt     time.Time
	props       [2][3]uint32 // per arm: IN_CASE, WORN, PITCH
}

func connect(f demo.Flags, a demo.Align) (*glasses, error) {
	f.DX, f.DY = a.DX, a.DY
	cfg, arms, err := demo.Connect(f)
	if err != nil {
		return nil, err
	}
	g := &glasses{cfg: cfg, arms: arms, brightness: -1, started: time.Now(), hiddenWhy: "unset"}
	g.dw, g.dh = scouterkit.Size()
	ascii := ""
	for c := ' '; c <= '~'; c++ {
		ascii += string(c)
	}
	if g.font, err = scouterkit.LoadFont(scouterkit.DepartureMono(), ascii, 1); err != nil {
		scouterkit.Deinit()
		return nil, err
	}
	fmt.Printf("glasses: display %dx%d (dx %d, dy %d), %d arms\n", g.dw, g.dh, cfg.DX, cfg.DY, len(arms))
	return g, nil
}

func (g *glasses) close() {
	fmt.Printf("%d frames sent in %.0f s\n", g.sent, time.Since(g.started).Seconds())
	scouterkit.Clear()
	scouterkit.Flush()
	scouterkit.Deinit()
}

// rect is the picture's place on the display: the default is the columns both eyes see, all rows.
func (g *glasses) rect(s *settings) image.Rectangle {
	both := 640 - abs(g.cfg.DX)
	if s.W <= 0 || s.H <= 0 {
		s.X, s.Y, s.W, s.H = abs(g.cfg.DX), 0, both, g.dh
	}
	s.W = clamp(s.W, 64, g.dw)
	s.H = clamp(s.H, 32, g.dh)
	s.X = clamp(s.X, 0, g.dw-s.W)
	s.Y = clamp(s.Y, 0, g.dh-s.H)
	return image.Rect(s.X, s.Y, s.X+s.W, s.Y+s.H)
}

func push(f demo.Flags, x *xscreen, st *state, stop <-chan struct{}) error {
	st.mu.Lock()
	align := st.align
	st.mu.Unlock()
	g, err := connect(f, align)
	if err != nil {
		return err
	}
	defer func() {
		if g != nil {
			g.close()
		}
	}()
	next := time.Now()
	for {
		select {
		case <-stop:
			return nil
		default:
		}
		st.mu.Lock()
		if !st.realignAt.IsZero() && time.Now().After(st.realignAt) {
			st.realignAt = time.Time{}
			align = st.align
			st.mu.Unlock()
			fmt.Printf("realigning: dx %d, dy %d\n", align.DX, align.DY)
			g.close()
			g = nil
			if g, err = connect(f, align); err != nil {
				return err
			}
			st.mu.Lock()
			st.changed = true
		}
		r := g.rect(&st.s)
		s := st.s
		adjusting, paused, changed := st.adjusting, st.paused, st.changed
		pending := !st.realignAt.IsZero()
		st.changed = false
		al := st.align
		st.mu.Unlock()

		if s.Brightness != g.brightness {
			for _, a := range g.arms {
				_ = a.SetBrightness(s.Brightness)
			}
			g.brightness = s.Brightness
		}
		hidden := g.hidden(s)
		if hidden != g.hiddenWhy {
			g.show(hidden == "")
			g.hiddenWhy = hidden
			changed = true
		}
		g.pollBattery()
		if hidden != "" && !adjusting {
			// nothing to draw: the panel is off
		} else if err := g.frameOnce(x, &s, r, adjusting, paused, changed, pending, al); err != nil {
			return err
		}
		st.mu.Lock()
		if st.s.Mode == "fixed" && st.s.FixedX < 0 {
			st.s.FixedX, st.s.FixedY = s.FixedX, s.FixedY
		}
		st.mu.Unlock()
		st.report(g, x)
		st.mu.Lock()
		st.hidden = g.hiddenWhy
		st.mu.Unlock()
		next = next.Add(time.Duration(float64(time.Second) / s.FPS))
		if d := time.Until(next); d > 0 {
			time.Sleep(d)
		} else {
			next = time.Now()
		}
	}
}

// frameOnce shows one frame; it may set s.FixedX and FixedY (a fixed region pinned where the pointer is).
func (g *glasses) frameOnce(x *xscreen, s *settings, r image.Rectangle, adjusting, paused, changed, pending bool,
	al demo.Align) error {
	if paused && !adjusting {
		if changed {
			scouterkit.Clear()
			scouterkit.Flush()
		}
		return nil
	}
	barH := 0
	if s.Bar || adjusting {
		barH = g.font.LineSpacing + 2
	}
	pw, ph := r.Dx(), r.Dy()-barH
	if pw != g.pw || ph != g.ph || g.pic == nil {
		if g.pic != nil {
			g.pic.Unload()
		}
		pic, err := scouterkit.CreateBitmap(pw, ph)
		if err != nil {
			return fmt.Errorf("a %dx%d picture: %w", pw, ph, err)
		}
		g.pic, g.pw, g.ph = pic, pw, ph
		g.frame = make([]byte, pw*ph)
		g.prev = nil
	}
	if barH > 0 && (g.bar == nil || g.bw != r.Dx()) {
		if g.bar != nil {
			g.bar.Unload()
		}
		bar, err := scouterkit.CreateBitmap(r.Dx(), barH)
		if err != nil {
			return fmt.Errorf("the bar: %w", err)
		}
		g.bar, g.bw, g.barText = bar, r.Dx(), ""
	}

	// The screen: which region, captured and fitted into the picture.
	cx, cy, cw, ch := g.region(x, s)
	if len(g.captureBuf) < cw*ch {
		g.captureBuf = make([]byte, cw*ch)
	}
	if !x.capture(cx, cy, cw, ch, g.captureBuf) {
		return fmt.Errorf("could not capture the screen")
	}
	var bg byte
	if s.Invert {
		bg = 255
	}
	fit(g.frame, pw, ph, g.captureBuf[:cw*ch], cw, ch, s.Mode == "full", bg)
	tone(g.frame, *s)
	same := g.prev != nil && bytes.Equal(g.prev, g.frame)

	text := g.barLine(*s, adjusting, pending, al)
	barChanged := barH > 0 && text != g.barText
	if same && !barChanged && !changed {
		return nil
	}
	if !same {
		g.pic.Write(&image.Gray{Pix: g.frame, Stride: pw, Rect: image.Rect(0, 0, pw, ph)}, 0, 0)
		g.prev = append(g.prev[:0], g.frame...)
	}
	if barChanged {
		g.drawBar(text, adjusting)
	}
	scouterkit.Clear()
	if barH > 0 {
		scouterkit.Draw(g.bar, r.Min.X, r.Min.Y)
	}
	scouterkit.Draw(g.pic, r.Min.X, r.Min.Y+barH)
	scouterkit.Flush()
	g.sent++
	return nil
}

// region is the screen rectangle to show.
func (g *glasses) region(x *xscreen, s *settings) (int, int, int, int) {
	if s.Mode == "full" {
		return 0, 0, x.W, x.H
	}
	cw := clamp(int(math.Round(float64(g.pw)/s.Zoom)), 8, x.W)
	ch := clamp(int(math.Round(float64(g.ph)/s.Zoom)), 8, x.H)
	var cx, cy int
	if s.Mode == "fixed" && s.FixedX >= 0 && s.FixedY >= 0 {
		cx, cy = s.FixedX, s.FixedY
	} else {
		px, py := x.pointer()
		cx, cy = px-cw/2, py-ch/2
	}
	cx, cy = clamp(cx, 0, x.W-cw), clamp(cy, 0, x.H-ch)
	if s.Mode == "fixed" && (s.FixedX < 0 || s.FixedY < 0) {
		s.FixedX, s.FixedY = cx, cy
	}
	return cx, cy, cw, ch
}

func (g *glasses) barLine(s settings, adjusting, pending bool, al demo.Align) string {
	if adjusting {
		re := ""
		if pending {
			re = "*"
		}
		return fmt.Sprintf("ADJUST %s %dx%d@%d,%d zoom %.2f gamma %.1f bright %d dx %d dy %d%s %dfps",
			s.Mode, s.W, s.H, s.X, s.Y, s.Zoom, s.Gamma, s.Brightness, al.DX, al.DY, re, int(s.FPS))
	}
	pct := func(b scouterkit.Battery) string {
		if b.Percent < 0 {
			return "?"
		}
		c := ""
		if b.Charging {
			c = "+"
		}
		return fmt.Sprintf("%d%%%s", b.Percent, c)
	}
	line := "L " + pct(g.battery[0]) + "|R " + pct(g.battery[1])
	if s.Clock {
		line += "|" + time.Now().Format("15:04")
	}
	return line
}

// drawBar: the battery levels at the bar's ends and the clock in its middle, or the adjust line.
func (g *glasses) drawBar(text string, adjusting bool) {
	g.barText = text
	g.bar.Fill(g.bar.Bounds(), 0)
	base := g.font.Ascent + 1
	if adjusting {
		g.font.DrawLine(g.bar, text, 0, base, scouterkit.AlignLeft, 255)
		return
	}
	parts := strings.Split(text, "|")
	g.font.DrawLine(g.bar, parts[0], 0, base, scouterkit.AlignLeft, 170)
	g.font.DrawLine(g.bar, parts[1], g.bw, base, scouterkit.AlignRight, 170)
	if len(parts) > 2 {
		g.font.DrawLine(g.bar, parts[2], g.bw/2, base, scouterkit.AlignCenter, 170)
	}
}

// fit scales src (sw x sh) into dst (w x h), averaging the source pixels under each output pixel; keep (the
// whole screen) keeps the aspect ratio, centred, with bg around it (the value that tone makes clear).
func fit(dst []byte, w, h int, src []byte, sw, sh int, keep bool, bg byte) {
	ow, oh, ox, oy := w, h, 0, 0
	if keep {
		k := math.Min(float64(w)/float64(sw), float64(h)/float64(sh))
		ow, oh = max(int(float64(sw)*k), 1), max(int(float64(sh)*k), 1)
		ox, oy = (w-ow)/2, (h-oh)/2
		for i := range dst {
			dst[i] = bg
		}
	}
	for y := 0; y < oh; y++ {
		y0 := y * sh / oh
		y1 := max((y+1)*sh/oh, y0+1)
		for x := 0; x < ow; x++ {
			x0 := x * sw / ow
			x1 := max((x+1)*sw/ow, x0+1)
			sum, n := 0, 0
			for yy := y0; yy < y1; yy++ {
				row := src[yy*sw:]
				for xx := x0; xx < x1; xx++ {
					sum += int(row[xx])
					n++
				}
			}
			dst[(y+oy)*w+x+ox] = byte(sum / n)
		}
	}
}

// tone inverts, applies the gamma and quantizes to the glasses' levels (multiples of 17, so Write keeps them
// exactly: no dithering noise to send).
func tone(p []byte, s settings) {
	var lut [256]byte
	for v := 0; v < 256; v++ {
		f := float64(v) / 255
		if s.Invert {
			f = 1 - f
		}
		f = math.Pow(f, s.Gamma)
		if s.Levels == 2 {
			if f >= 0.5 {
				f = 1
			} else {
				f = 0
			}
		}
		lut[v] = byte(math.Round(f*15) * 17)
	}
	for i, v := range p {
		p[i] = lut[v]
	}
}

func clamp(v, lo, hi int) int {
	if hi < lo {
		return lo
	}
	return min(max(v, lo), hi)
}

func abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

func (g *glasses) pollBattery() {
	if time.Since(g.batteryAt) < 20*time.Second {
		return
	}
	g.batteryAt = time.Now()
	for i, a := range []scouterkit.Arm{scouterkit.Left, scouterkit.Right} {
		if b, err := a.Battery(); err == nil {
			g.battery[i] = b
		}
	}
}

// hidden is why the panel should be off now, or "": an arm in the case, the glasses folded (both arms not
// worn), or the head not looking up far enough. Unknown readings never hide. Read once a second.
func (g *glasses) hidden(s settings) string {
	if !s.HideIdle && s.LookUp <= 0 {
		return ""
	}
	if time.Since(g.propsAt) >= time.Second {
		g.propsAt = time.Now()
		for i, a := range []scouterkit.Arm{scouterkit.Left, scouterkit.Right} {
			g.props[i] = [3]uint32{scouterkit.Unknown, scouterkit.Unknown, scouterkit.Unknown}
			if !a.State().Present {
				continue
			}
			if v, err := a.Props(scouterkit.PropInCase, scouterkit.PropWorn, scouterkit.PropPitch); err == nil {
				copy(g.props[i][:], v)
			}
		}
	}
	if s.HideIdle {
		folded, known := true, false
		for _, p := range g.props {
			if p[0] == 1 {
				return "in the case"
			}
			if p[1] != scouterkit.Unknown {
				known = true
				folded = folded && p[1] == 0
			}
		}
		if known && folded {
			return "folded"
		}
	}
	if s.LookUp > 0 {
		up, known := false, false
		for _, p := range g.props {
			if p[2] != scouterkit.Unknown {
				known = true
				up = up || int32(p[2]) >= int32(s.LookUp)
			}
		}
		if known && !up {
			return "looking ahead"
		}
	}
	return ""
}

// show switches both panels on (the next frame redraws) or clears and switches them off.
func (g *glasses) show(on bool) {
	if !on {
		scouterkit.Clear()
		scouterkit.Flush()
	}
	for _, a := range g.arms {
		a.SetPanel(on)
	}
	g.prev = nil
	g.barText = ""
}
