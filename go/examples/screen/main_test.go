package main

import (
	"net/http"
	"os"
	"strings"
	"testing"
	"time"

	"scouterkit"
	"scouterkit/examples/internal/demo"
)

func TestFitAverages(t *testing.T) {
	src := []byte{0, 255, 0, 255, 0, 255, 0, 255} // 4 x 2
	dst := make([]byte, 2)                        // 2 x 1
	fit(dst, 2, 1, src, 4, 2, false, 0)
	if dst[0] != 127 || dst[1] != 127 {
		t.Fatalf("got %v, want the averages 127", dst)
	}
}

func TestFitKeepsAspectWithClearBorders(t *testing.T) {
	src := make([]byte, 16*9)
	for i := range src {
		src[i] = 100
	}
	dst := make([]byte, 16*16)
	fit(dst, 16, 16, src, 16, 9, true, 255)
	if dst[0] != 255 || dst[15*16] != 255 {
		t.Fatalf("borders %d, %d: want the background 255", dst[0], dst[15*16])
	}
	if dst[8*16+8] != 100 {
		t.Fatalf("centre %d, want the picture 100", dst[8*16+8])
	}
}

func TestToneInvertsToClearAndQuantizes(t *testing.T) {
	s := defaults()
	p := []byte{255, 0, 128}
	tone(p, s)
	if p[0] != 0 || p[1] != 255 {
		t.Fatalf("white %d, black %d: inverted they should be 0 (clear) and 255", p[0], p[1])
	}
	for _, v := range p {
		if v%17 != 0 {
			t.Fatalf("%d is not one of the 16 levels (a multiple of 17)", v)
		}
	}
	s.Levels = 2
	p = []byte{200, 40}
	tone(p, s)
	if p[0] != 0 || p[1] != 255 {
		t.Fatalf("2 levels: got %v", p)
	}
}

func TestAdjustKeys(t *testing.T) {
	st := &state{s: defaults()}
	st.s.X, st.s.Y, st.s.W, st.s.H = 100, 0, 540, 471
	handle(st, key{sym: kRight})
	handle(st, key{sym: kDown, shift: true})
	handle(st, key{sym: letter('a')})
	handle(st, key{sym: kPlus})
	handle(st, key{sym: letter('n')})
	handle(st, key{sym: letter('m')})
	if st.s.X != 108 || st.s.Y != 1 || st.s.W != 532 || st.s.Zoom <= 1 || st.s.Invert || st.s.Mode != "full" {
		t.Fatalf("got %+v", st.s)
	}
	handle(st, key{sym: letter('l')})
	if st.align.DX != 2 || st.realignAt.IsZero() {
		t.Fatalf("alignment %+v, realign at %v", st.align, st.realignAt)
	}
	handle(st, key{sym: letter('r')})
	if st.s.W != 0 || st.s.Mode != "follow" {
		t.Fatalf("reset: %+v", st.s)
	}
}

func TestRegionClampsToTheScreen(t *testing.T) {
	g := &glasses{pw: 540, ph: 455}
	x := &xscreen{W: 1600, H: 900}
	s := defaults()
	s.Mode, s.FixedX, s.FixedY = "fixed", 5000, 5000
	cx, cy, cw, ch := g.region(x, &s)
	if cx+cw > 1600 || cy+ch > 900 || cw != 540 || ch != 455 {
		t.Fatalf("region %d,%d %dx%d", cx, cy, cw, ch)
	}
}

// The real X11 screen, when there is one.
func TestCapture(t *testing.T) {
	if os.Getenv("DISPLAY") == "" {
		t.Skip("no X11 display")
	}
	x, err := openX()
	if err != nil {
		t.Skip(err)
	}
	buf := make([]byte, 64*48)
	if !x.capture(0, 0, 64, 48, buf) {
		t.Fatal("capture failed")
	}
	px, py := x.pointer()
	t.Logf("screen %dx%d, pointer at %d,%d", x.W, x.H, px, py)
}

func TestPatch(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st := &state{s: defaults(), align: demo.Align{DX: 100, DY: 9}}
	if err := st.patch([]byte(`{"zoom": 99, "brightness": 30, "mode": "full", "invert": false, "nonsense": 1}`)); err != nil {
		t.Fatal(err)
	}
	if st.s.Zoom != 6 || st.s.Brightness != 30 || st.s.Mode != "full" || st.s.Invert || !st.realignAt.IsZero() {
		t.Fatalf("got %+v", st.s)
	}
	if err := st.patch([]byte(`{"dx": 90, "mode": "bogus", "paused": true}`)); err != nil {
		t.Fatal(err)
	}
	if st.align.DX != 90 || st.realignAt.IsZero() || st.s.Mode != "full" || !st.paused {
		t.Fatalf("align %+v mode %s paused %v", st.align, st.s.Mode, st.paused)
	}
	if err := st.patch([]byte(`{"reset": true}`)); err != nil {
		t.Fatal(err)
	}
	if st.s.Mode != "follow" || st.s.Brightness != 30 {
		t.Fatalf("reset kept brightness? %+v", st.s)
	}
}

func TestSetNeedsThePagesHeader(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st := &state{s: defaults()}
	addr, err := serve("127.0.0.1:0", st)
	if err != nil {
		t.Fatal(err)
	}
	post := func(header bool) int {
		req, _ := http.NewRequest("POST", "http://"+addr+"/api/set", strings.NewReader(`{"brightness": 5}`))
		if header {
			req.Header.Set("X-Scouter", "1")
		}
		r, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		r.Body.Close()
		return r.StatusCode
	}
	if code := post(false); code != http.StatusForbidden || st.s.Brightness == 5 {
		t.Fatalf("without the header: status %d, brightness %d", code, st.s.Brightness)
	}
	if code := post(true); code != http.StatusOK || st.s.Brightness != 5 {
		t.Fatalf("with the header: status %d, brightness %d", code, st.s.Brightness)
	}
	r, err := http.Get("http://" + addr + "/")
	if err != nil || r.StatusCode != http.StatusOK {
		t.Fatalf("page: %v %v", err, r)
	}
	r.Body.Close()
}

func TestHidden(t *testing.T) {
	u := uint32(scouterkit.Unknown)
	g := &glasses{propsAt: time.Now().Add(time.Hour)} // no reads: the props below stand
	s := defaults()
	cases := []struct {
		props  [2][3]uint32
		lookUp int
		want   string
	}{
		{[2][3]uint32{{0, 1, 0}, {0, 1, 0}}, 0, ""},
		{[2][3]uint32{{1, 0, 0}, {0, 1, 0}}, 0, "in the case"},
		{[2][3]uint32{{0, 0, 0}, {0, 0, 0}}, 0, "folded"},
		{[2][3]uint32{{0, 0, 0}, {0, 1, 0}}, 0, ""},  // one arm worn: not folded
		{[2][3]uint32{{u, u, u}, {u, u, u}}, 15, ""}, // unknown never hides
		{[2][3]uint32{{0, 1, 5}, {0, 1, 3}}, 15, "looking ahead"},
		{[2][3]uint32{{0, 1, 20}, {0, 1, 3}}, 15, ""},
		{[2][3]uint32{{0, 1, uint32(0xFFFFFFF6)}, {0, 1, u}}, 15, "looking ahead"}, // -10 degrees
	}
	for i, c := range cases {
		g.props, s.LookUp = c.props, c.lookUp
		if got := g.hidden(s); got != c.want {
			t.Errorf("case %d: %q, want %q", i, got, c.want)
		}
	}
}
