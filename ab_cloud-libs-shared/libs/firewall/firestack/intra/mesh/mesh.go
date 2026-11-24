// Copyright (c) 2026 Diego Nunes Marcos and the firestack authors.
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// Package mesh is the libwg-go surface (#1881, one Go engine for wg-go and
// firestack) as a gomobile facade: the same six entry points that
// libs/net-wg/src/main/cpp/libwg-go/api-android.go exports over JNI, bound into
// the firestack aar so the mesh tunnel can run on the one Go runtime the
// firewall already ships. Only primitives and strings cross the boundary
// (gomobile restriction); the Java side is com.celzero.firestack.mesh.Mesh.
//
// It drives wireguard-go (the exact module version libwg-go pins) directly and
// takes the same wg-quick-derived UAPI settings text GoBackend already builds.
package mesh

import (
	"errors"
	"math"
	"net"
	"runtime/debug"
	"strings"
	"sync"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/ipc"
	"golang.zx2c4.com/wireguard/tun"

	"github.com/celzero/firestack/intra/log"
)

const tag = "WireGuard/GoBackend/"

type handle struct {
	device *device.Device
	uapi   net.Listener
}

var (
	mu      sync.Mutex
	handles = make(map[int32]handle)
)

var errNoHandle = errors.New("mesh: unable to find empty handle")

// nextHandle returns the lowest free handle in m (libwg-go allocation order).
func nextHandle(m map[int32]handle) (int32, error) {
	var i int32
	for i = 0; i < math.MaxInt32; i++ {
		if _, exists := m[i]; !exists {
			return i, nil
		}
	}
	return -1, errNoHandle
}

func logger(ifName string) *device.Logger {
	t := tag + ifName
	return &device.Logger{
		Verbosef: func(f string, a ...any) { log.D(t+": "+f, a...) },
		Errorf:   func(f string, a ...any) { log.E(t+": "+f, a...) },
	}
}

// TurnOn brings up a tunnel on tunFd with the UAPI settings text and returns
// its handle, or -1. It takes ownership of tunFd, as wgTurnOn does.
func TurnOn(ifName string, tunFd int32, settings string) int32 {
	lg := logger(ifName)

	t, name, err := tun.CreateUnmonitoredTUNFromFD(int(tunFd))
	if err != nil {
		_ = unix.Close(int(tunFd))
		lg.Errorf("CreateUnmonitoredTUNFromFD: %v", err)
		return -1
	}

	lg.Verbosef("Attaching to interface %v", name)
	dev := device.NewDevice(t, conn.NewStdNetBind(), lg)

	if err = dev.IpcSet(settings); err != nil {
		_ = unix.Close(int(tunFd))
		lg.Errorf("IpcSet: %v", err)
		return -1
	}
	dev.DisableSomeRoamingForBrokenMobileSemantics()

	var uapi net.Listener
	var uapiFile interface{ Close() error }
	if f, err := ipc.UAPIOpen(name); err != nil {
		lg.Errorf("UAPIOpen: %v", err)
	} else if l, err := ipc.UAPIListen(name, f); err != nil {
		_ = f.Close()
		lg.Errorf("UAPIListen: %v", err)
	} else {
		uapi, uapiFile = l, f
		go func() {
			for {
				c, err := l.Accept()
				if err != nil {
					return
				}
				go dev.IpcHandle(c)
			}
		}()
	}
	closeUapi := func() {
		if uapiFile != nil {
			_ = uapiFile.Close()
		}
	}

	if err = dev.Up(); err != nil {
		lg.Errorf("Unable to bring up device: %v", err)
		closeUapi()
		dev.Close()
		return -1
	}
	lg.Verbosef("Device started")

	mu.Lock()
	defer mu.Unlock()
	i, err := nextHandle(handles)
	if err != nil {
		lg.Errorf("%v", err)
		closeUapi()
		dev.Close()
		return -1
	}
	handles[i] = handle{device: dev, uapi: uapi}
	return i
}

// TurnOff tears the tunnel down; an unknown handle is a no-op.
func TurnOff(h int32) {
	mu.Lock()
	hd, ok := handles[h]
	delete(handles, h)
	mu.Unlock()
	if !ok {
		return
	}
	if hd.uapi != nil {
		_ = hd.uapi.Close()
	}
	hd.device.Close()
}

func get(h int32) (handle, bool) {
	mu.Lock()
	defer mu.Unlock()
	hd, ok := handles[h]
	return hd, ok
}

// GetSocketV4 returns the tunnel's IPv4 UDP socket fd (for VpnService.protect), or -1.
func GetSocketV4(h int32) int32 {
	hd, ok := get(h)
	if !ok {
		return -1
	}
	b, _ := hd.device.Bind().(conn.PeekLookAtSocketFd)
	if b == nil {
		return -1
	}
	fd, err := b.PeekLookAtSocketFd4()
	if err != nil {
		return -1
	}
	return int32(fd)
}

// GetSocketV6 returns the tunnel's IPv6 UDP socket fd, or -1.
func GetSocketV6(h int32) int32 {
	hd, ok := get(h)
	if !ok {
		return -1
	}
	b, _ := hd.device.Bind().(conn.PeekLookAtSocketFd)
	if b == nil {
		return -1
	}
	fd, err := b.PeekLookAtSocketFd6()
	if err != nil {
		return -1
	}
	return int32(fd)
}

// GetConfig returns the device's UAPI text (peer stats included), or "" for
// an unknown handle (libwg-go returns NULL there).
func GetConfig(h int32) string {
	hd, ok := get(h)
	if !ok {
		return ""
	}
	s, err := hd.device.IpcGet()
	if err != nil {
		return ""
	}
	return s
}

// Version is the pinned wireguard-go revision, as wgVersion reports it.
func Version() string {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return "unknown"
	}
	for _, dep := range info.Deps {
		if dep.Path == "golang.zx2c4.com/wireguard" {
			parts := strings.Split(dep.Version, "-")
			if len(parts) == 3 && len(parts[2]) == 12 {
				return parts[2][:7]
			}
			return dep.Version
		}
	}
	return "unknown"
}
