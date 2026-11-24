// Copyright (c) 2026 Diego Nunes Marcos and the firestack authors.
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package mesh

import "testing"

func TestNextHandleLowestFree(t *testing.T) {
	m := map[int32]handle{0: {}, 1: {}, 3: {}}
	if i, err := nextHandle(m); err != nil || i != 2 {
		t.Fatalf("nextHandle = %d, %v; want 2", i, err)
	}
	if i, err := nextHandle(map[int32]handle{}); err != nil || i != 0 {
		t.Fatalf("empty = %d, %v; want 0", i, err)
	}
}

func TestUnknownHandleIsInert(t *testing.T) {
	TurnOff(42)
	if GetConfig(42) != "" || GetSocketV4(42) != -1 || GetSocketV6(42) != -1 {
		t.Fatal("unknown handle must report empty config and -1 sockets")
	}
}

func TestTurnOnBadFdFails(t *testing.T) {
	if h := TurnOn("t0", -1, ""); h != -1 {
		t.Fatalf("TurnOn on a bad fd = %d, want -1", h)
	}
}

func TestVersionNonEmpty(t *testing.T) {
	if Version() == "" {
		t.Fatal("empty version")
	}
}
