import { redirect, type RouteObject } from 'react-router-dom';

import { Component as Vault } from './pages/workspace/vault';

// #1717: the vault is the one screen of Cloud Notes. The old workspace pages (home, all, boards, journals,
// collection/*, tag/*, trash, search, graph, detail/*) are gone; test/test-no-old-screens.sh fails if one returns.
//
// Every path an old build, a saved workbench location, a deep link or the native shell could still ask for lands in
// the vault instead of a blank /404: the index, each removed page, and a bare /<docId>. Only a path that never was a
// route of this app reaches the 404 fallback. test/test-no-old-screens.sh resolves each of them.
const toVault = () => redirect('/vault');

/** The paths that redirect to the vault (listed once; the guard test reads this list). */
export const LEGACY_START_PATHS = [
  '/',
  '/home',
  '/all',
  '/boards',
  '/journals',
  '/trash',
  '/search',
  '/graph',
  '/graph/local',
  '/collection',
  '/collection/:collectionId',
  '/tag',
  '/tag/:tagId',
  '/:pageId',
] as const;

export const workbenchRoutes = [
  {
    path: '/vault',
    Component: Vault,
  },
  ...LEGACY_START_PATHS.map(path => ({ path, loader: toVault })),
  {
    path: '*',
    lazy: () => import('./pages/404'),
  },
] satisfies [RouteObject, ...RouteObject[]];
