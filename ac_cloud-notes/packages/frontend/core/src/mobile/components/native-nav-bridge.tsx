import { usePageHelper } from '@cloud-notes/core/blocksuite/block-suite-page-list/utils';
import { DocsService } from '@cloud-notes/core/modules/doc';
import { WorkbenchService } from '@cloud-notes/core/modules/workbench';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { LiveData, useLiveData, useService } from '@toeverything/infra';
import { useEffect, useMemo } from 'react';

import {
  type CloudNotesHost,
  docIdFromPath,
  drawerDocs,
  NATIVE_NAV_EVENT,
  NATIVE_OPEN_DOC_EVENT,
  type NativeNavDetail,
  resolveNativeNav,
  setLastDocId,
} from './native-nav-routes';

/**
 * Receives the Compose shell's island / page-strip taps (a `cloud-notes:nav`
 * window event, see MainActivity.dispatchNav) and drives the mobile workbench.
 * Rendered inside the workspace scope, so service lookups are legal here
 * (test-root-scope-services); the native side never sees a URL.
 */
export const NativeNavBridge = () => {
  const workbench = useService(WorkbenchService).workbench;
  const workspace = useService(WorkspaceService).workspace;
  const pageHelper = usePageHelper(workspace.docCollection);
  const docList = useService(DocsService).list;

  // The local graph follows the note that was open last.
  const location = useLiveData(workbench.location$);
  useEffect(() => {
    setLastDocId(docIdFromPath(location.pathname));
  }, [location.pathname]);

  // The top bar's Settings > Configs and a page picked in its drawer.
  useEffect(() => {
    // Settings > Configs is the vault's own Settings screen (VaultShell listens for the same event); the AFFiNE dialog is gone
    const openDoc = (e: Event) => {
      const id = (e as CustomEvent<{ id?: string }>).detail?.id;
      if (id) workbench.openDoc(id);
    };
    window.addEventListener(NATIVE_OPEN_DOC_EVENT, openDoc);
    return () => {
      window.removeEventListener(NATIVE_OPEN_DOC_EVENT, openDoc);
    };
  }, [workbench]);

  // The drawer's page list: the native side cannot see the workspace, so the
  // web half pushes it whenever a title or a trash flag changes.
  const metas = useLiveData(
    useMemo(
      () =>
        LiveData.computed(get =>
          get(docList.docs$).map(d => ({ id: d.id, ...get(d.meta$) }))
        ),
      [docList]
    )
  );
  useEffect(() => {
    const host = (window as unknown as { CloudNotesHost?: CloudNotesHost })
      .CloudNotesHost;
    host?.setDocs(JSON.stringify(drawerDocs(metas)));
  }, [metas]);

  useEffect(() => {
    const listener = (e: Event) => {
      const detail = (e as CustomEvent<NativeNavDetail>).detail;
      const target = detail ? resolveNativeNav(detail) : null;
      if (!target) return;
      switch (target.kind) {
        case 'route':
          workbench.open(target.to);
          break;
        case 'action': {
          const doc =
            target.action === 'new-board'
              ? pageHelper.createEdgeless({ show: false })
              : pageHelper.createPage(undefined, { show: false });
          workbench.openDoc(doc.id);
          break;
        }
        case 'native':
          // drawn by the Compose shell (Search > Ask); nothing for the web half to do
          break;
      }
    };
    window.addEventListener(NATIVE_NAV_EVENT, listener);
    return () => window.removeEventListener(NATIVE_NAV_EVENT, listener);
  }, [pageHelper, workbench]);

  return null;
};
