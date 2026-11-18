// Light web specs run by test/run-phase0-storage.sh (cloud-notes-hybrid). Deliberately
// NOT the monorepo vitest.config.ts: these specs mock every @cloud-notes/* and @capacitor/*
// import (or import none), so they need only vitest itself -- no yarn install of the
// whole Cloud Notes workspace, which keeps this check in seconds instead of minutes.
//   nbstore/index.spec.ts          Phase 0 storage adapter round trip
//   native-nav-routes.spec.ts      the nav routes + the top bar's drawer list
//   vault-import.spec.ts           Phase 1: the folder walk behind "Import vault"
//   graph/__tests__/graph.spec.ts  Phase 4: graph builder, local BFS, force layout
import { fileURLToPath } from 'node:url';

import { defineConfig } from 'vitest/config';

export default defineConfig({
  root: fileURLToPath(new URL('../../../..', import.meta.url)), // ac_cloud-notes
  test: {
    environment: 'node',
    include: [
      'packages/frontend/apps/android/src/plugins/nbstore/index.spec.ts',
      'packages/frontend/core/src/mobile/components/native-nav-routes.spec.ts',
      'packages/frontend/apps/android/src/vault-import.spec.ts',
      'blocksuite/cloud-notes/graph/src/__tests__/graph.spec.ts',
    ],
  },
});
