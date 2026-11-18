import { describe, expect, test } from 'vitest';

import {
  docIdFromPath,
  drawerDocs,
  NATIVE_NAV_FIRST_PAGE,
  NATIVE_NAV_TARGETS,
  resolveNativeNav,
} from './native-nav-routes';

describe('native nav routes', () => {
  test('a section tap resolves to its first page', () => {
    expect(resolveNativeNav({ section: 'home' })).toEqual({
      kind: 'route',
      to: '/vault',
    });
    expect(resolveNativeNav({ section: 'search', page: null })).toEqual({
      kind: 'route',
      to: '/vault',
    });
  });

  test('every section has a first page that has a target', () => {
    for (const [section, page] of Object.entries(NATIVE_NAV_FIRST_PAGE)) {
      expect(NATIVE_NAV_TARGETS[`${section}/${page}`]).toBeDefined();
    }
  });

  test('A4.4: Graph global/local are routes, Ask is a native page, nothing toasts', () => {
    expect(NATIVE_NAV_TARGETS['graph/global']).toEqual({
      kind: 'route',
      to: '/vault',
    });
    expect(NATIVE_NAV_TARGETS['graph/local']).toEqual({
      kind: 'route',
      to: '/vault',
    });
    expect(NATIVE_NAV_TARGETS['search/ask'].kind).toBe('native');
    const kinds = new Set(Object.values(NATIVE_NAV_TARGETS).map(t => t.kind));
    expect(Array.from(kinds).sort()).toEqual(['action', 'native', 'route']);
    for (const t of Object.values(NATIVE_NAV_TARGETS))
      if (t.kind === 'native') expect(t).not.toHaveProperty('to');
  });

  test('a doc id is read from /<id> only', () => {
    expect(docIdFromPath('/abc123')).toBe('abc123');
    expect(docIdFromPath('/abc123/')).toBe('abc123');
    expect(docIdFromPath('/graph')).toBeNull();
    expect(docIdFromPath('/graph/local')).toBeNull();
    expect(docIdFromPath('/tag/xyz')).toBeNull();
    expect(docIdFromPath('/all')).toBeNull();
    expect(docIdFromPath('/boards')).toBeNull();
    expect(docIdFromPath('/vault')).toBeNull();
    expect(docIdFromPath('/')).toBeNull();
  });

  test('unknown ids resolve to null', () => {
    expect(resolveNativeNav({ section: 'nope' })).toBeNull();
    expect(resolveNativeNav({ section: 'home', page: 'nope' })).toBeNull();
  });

  test('the drawer lists live pages, newest edit first, titled', () => {
    expect(
      drawerDocs([
        { id: 'old', title: 'Old', updatedDate: 1 },
        { id: 'gone', title: 'Trashed', trash: true, updatedDate: 9 },
        { id: 'new', title: '  New  ', updatedDate: 5 },
        { id: 'blank', title: ' ', updatedDate: 3 },
        { id: 'untitled', updatedDate: 2 },
      ])
    ).toEqual([
      { id: 'new', title: 'New' },
      { id: 'blank', title: 'Untitled' },
      { id: 'untitled', title: 'Untitled' },
      { id: 'old', title: 'Old' },
    ]);
  });

  test('the drawer list is capped', () => {
    const many = Array.from({ length: 100 }, (_, i) => ({
      id: `d${i}`,
      updatedDate: i,
    }));
    expect(drawerDocs(many, 40)).toHaveLength(40);
    expect(drawerDocs(many)[0].id).toBe('d99');
  });
});
