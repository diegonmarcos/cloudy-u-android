import type { RouteObject } from 'react-router-dom';

import { Component as Vault } from './pages/workspace/vault';

// #1717: the vault is the one screen of Cloud Notes. The old workspace pages (home, all, boards, journals,
// collection/*, tag/*, trash, search, graph, detail/*) are gone; test/test-no-old-screens.sh fails if one returns.
export const workbenchRoutes = [
  {
    path: '/vault',
    Component: Vault,
  },
  {
    path: '*',
    lazy: () => import('./pages/404'),
  },
] satisfies [RouteObject, ...RouteObject[]];
