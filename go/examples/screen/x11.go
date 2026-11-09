package main

// The X11 side: the screen's pixels (XGetImage of the root window), the pointer, and keys. One display
// connection captures (the main loop), another waits for keys (its own goroutine): Xlib needs no locking
// then. Needs libX11 (Debian: libx11-dev) and an X11 session (on Wayland only XWayland's windows show).

/*
#cgo LDFLAGS: -lX11
#include <stdlib.h>
#include <string.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/keysym.h>

static int grab_failed;

static int on_error(Display *d, XErrorEvent *e) {
	if (e->request_code == 33) grab_failed = 1; // X_GrabKey: another program has the key
	return 0;
}

static void sk_x_init(void) {
	XInitThreads();
	XSetErrorHandler(on_error);
}

static void sk_x_size(Display *d, int *w, int *h) {
	Screen *s = DefaultScreenOfDisplay(d);
	*w = WidthOfScreen(s);
	*h = HeightOfScreen(s);
}

// The luma of the root window's rectangle (x, y, w, h) into out (w * h bytes); 0 on failure.
static int sk_x_capture(Display *d, int x, int y, int w, int h, unsigned char *out) {
	XImage *im = XGetImage(d, DefaultRootWindow(d), x, y, w, h, AllPlanes, ZPixmap);
	if (!im) return 0;
	int ok = im->bits_per_pixel == 32;
	if (ok) {
		int rs = __builtin_ctzl(im->red_mask), gs = __builtin_ctzl(im->green_mask), bs = __builtin_ctzl(im->blue_mask);
		for (int j = 0; j < h; j++) {
			const unsigned int *row = (const unsigned int *)(im->data + j * im->bytes_per_line);
			for (int i = 0; i < w; i++) {
				unsigned int p = row[i];
				unsigned int r = (p >> rs) & 255, g = (p >> gs) & 255, b = (p >> bs) & 255;
				out[j * w + i] = (unsigned char)((r * 77 + g * 150 + b * 29) >> 8);
			}
		}
	}
	XDestroyImage(im);
	return ok;
}

static void sk_x_pointer(Display *d, int *x, int *y) {
	Window root, child;
	int wx, wy;
	unsigned int mask;
	*x = *y = 0;
	XQueryPointer(d, DefaultRootWindow(d), &root, &child, x, y, &wx, &wy, &mask);
}

// The hotkey: keysym with modifiers, also with Num Lock and Caps Lock on. 1 if grabbed.
static int sk_x_grab_hotkey(Display *d, KeySym sym, unsigned int mods) {
	KeyCode code = XKeysymToKeycode(d, sym);
	if (!code) return 0;
	unsigned int extra[] = {0, Mod2Mask, LockMask, Mod2Mask | LockMask};
	grab_failed = 0;
	for (int i = 0; i < 4; i++)
		XGrabKey(d, code, mods | extra[i], DefaultRootWindow(d), True, GrabModeAsync, GrabModeAsync);
	XSync(d, False);
	return !grab_failed;
}

// While adjusting: every key comes here (and to no other program) until sk_x_release_keyboard.
static int sk_x_grab_keyboard(Display *d) {
	int r = XGrabKeyboard(d, DefaultRootWindow(d), True, GrabModeAsync, GrabModeAsync, CurrentTime);
	XFlush(d);
	return r == GrabSuccess;
}

static void sk_x_release_keyboard(Display *d) {
	XUngrabKeyboard(d, CurrentTime);
	XFlush(d);
}

// Blocks until a key is pressed: its keysym (unshifted), *state the modifiers.
static unsigned long sk_x_next_key(Display *d, unsigned int *state) {
	for (;;) {
		XEvent e;
		XNextEvent(d, &e);
		if (e.type != KeyPress) continue;
		*state = e.xkey.state;
		return XLookupKeysym(&e.xkey, 0);
	}
}
*/
import "C"

import (
	"errors"
	"fmt"
	"strings"
	"unsafe"
)

type xscreen struct {
	cap, keys *C.Display
	W, H      int
}

func openX() (*xscreen, error) {
	C.sk_x_init()
	a := C.XOpenDisplay(nil)
	b := C.XOpenDisplay(nil)
	if a == nil || b == nil {
		return nil, errors.New("no X11 display (is DISPLAY set? this needs an X11 session)")
	}
	var w, h C.int
	C.sk_x_size(a, &w, &h)
	return &xscreen{cap: a, keys: b, W: int(w), H: int(h)}, nil
}

// capture puts the luma of r (on the screen) into out, len w*h.
func (x *xscreen) capture(rx, ry, w, h int, out []byte) bool {
	if w <= 0 || h <= 0 || len(out) < w*h {
		return false
	}
	return C.sk_x_capture(x.cap, C.int(rx), C.int(ry), C.int(w), C.int(h), (*C.uchar)(unsafe.Pointer(&out[0]))) != 0
}

func (x *xscreen) pointer() (int, int) {
	var px, py C.int
	C.sk_x_pointer(x.cap, &px, &py)
	return int(px), int(py)
}

// Modifier masks and the keys the adjust mode knows.
const (
	modShift = C.ShiftMask
	modCtrl  = C.ControlMask
	modAlt   = C.Mod1Mask
	modSuper = C.Mod4Mask
)

// A key in adjust mode; start and done mark the mode's beginning (the hotkey) and its end (Enter, Esc or the
// hotkey again).
type key struct {
	sym         uint64
	shift       bool
	start, done bool
}

// parseHotkey reads "ctrl+alt+g" into modifiers and a keysym.
func parseHotkey(s string) (C.uint, C.KeySym, error) {
	var mods C.uint
	parts := strings.Split(s, "+")
	for _, p := range parts[:len(parts)-1] {
		switch strings.ToLower(p) {
		case "ctrl", "control":
			mods |= modCtrl
		case "alt":
			mods |= modAlt
		case "shift":
			mods |= modShift
		case "super", "win":
			mods |= modSuper
		default:
			return 0, 0, fmt.Errorf("unknown modifier %q in %q", p, s)
		}
	}
	// Keysym names are case-sensitive ("g", "F12", "Home"): as given, then lower, then upper case.
	last := parts[len(parts)-1]
	for _, n := range []string{last, strings.ToLower(last), strings.ToUpper(last)} {
		name := C.CString(n)
		sym := C.XStringToKeysym(name)
		C.free(unsafe.Pointer(name))
		if sym != C.NoSymbol {
			return mods, sym, nil
		}
	}
	return 0, 0, fmt.Errorf("unknown key in %q", s)
}

// listen grabs the hotkey. The hotkey starts adjust mode: the whole keyboard is then this program's and each
// key goes to keys, until Enter, Esc or the hotkey again ends it. All calls on the key connection are made by
// this goroutine. It runs until the process ends.
func (x *xscreen) listen(hotkey string, keys chan<- key) error {
	mods, sym, err := parseHotkey(hotkey)
	if err != nil {
		return err
	}
	if C.sk_x_grab_hotkey(x.keys, sym, mods) == 0 {
		return fmt.Errorf("%s is taken by another program: choose another with -hotkey", hotkey)
	}
	go func() {
		adjusting := false
		for {
			var state C.uint
			s := uint64(C.sk_x_next_key(x.keys, &state))
			hot := s == uint64(sym) && C.uint(state)&(modCtrl|modAlt|modShift|modSuper) == mods
			switch {
			case !adjusting && hot:
				if C.sk_x_grab_keyboard(x.keys) != 0 {
					adjusting = true
					keys <- key{start: true}
				}
			case adjusting && (hot || s == kReturn || s == kEscape):
				C.sk_x_release_keyboard(x.keys)
				adjusting = false
				keys <- key{done: true}
			case adjusting:
				keys <- key{sym: s, shift: state&modShift != 0}
			}
		}
	}()
	return nil
}

// Keysyms used by the adjust mode.
var (
	kLeft     = uint64(C.XK_Left)
	kRight    = uint64(C.XK_Right)
	kUp       = uint64(C.XK_Up)
	kDown     = uint64(C.XK_Down)
	kPageUp   = uint64(C.XK_Prior)
	kPageDown = uint64(C.XK_Next)
	kReturn   = uint64(C.XK_Return)
	kEscape   = uint64(C.XK_Escape)
	kPlus     = uint64(C.XK_equal)
	kKpPlus   = uint64(C.XK_KP_Add)
	kMinus    = uint64(C.XK_minus)
	kKpMinus  = uint64(C.XK_KP_Subtract)
	kComma    = uint64(C.XK_comma)
	kPeriod   = uint64(C.XK_period)
)

// letter is the keysym of a lowercase letter.
func letter(c byte) uint64 { return uint64(C.XK_a) + uint64(c-'a') }
