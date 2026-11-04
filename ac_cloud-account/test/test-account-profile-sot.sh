#!/usr/bin/env bash
# #1445 Cloud Account's half of the profile drift guard: the one guard lives with the SoT, in Cloud Me.
exec bash "$(cd "$(dirname "$0")/../.." && pwd)/ac_cloud-me/test/test-profile-sot.sh"
