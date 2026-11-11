import { notify } from '@cloud-notes/component';
import { getStoreManager } from '@cloud-notes/core/blocksuite/manager/store';
import { CloudNotesContext } from '@cloud-notes/core/components/context';
import { AppFallback } from '@cloud-notes/core/mobile/components/app-fallback';
import { MobileModalConfigProvider } from '@cloud-notes/core/mobile/components/mobile-modal-config-provider';
import { configureMobileModules } from '@cloud-notes/core/mobile/modules';
import { MobileBackCoordinator } from '@cloud-notes/core/mobile/modules/back-coordinator';
import { VirtualKeyboardProvider } from '@cloud-notes/core/mobile/modules/virtual-keyboard';
import { router } from '@cloud-notes/core/mobile/router';
import { configureCommonModules } from '@cloud-notes/core/modules';
import { AIButtonProvider } from '@cloud-notes/core/modules/ai-button';
import {
  AuthProvider,
  AuthService,
  DefaultServerService,
  ServerScope,
  ServerService,
  ServersService,
  ValidatorProvider,
} from '@cloud-notes/core/modules/cloud';
import { registerNativePreviewHandlers } from '@cloud-notes/core/modules/code-block-preview-renderer';
import { DocsService } from '@cloud-notes/core/modules/doc';
import { ImportService } from '@cloud-notes/core/modules/import';
import { GlobalContextService } from '@cloud-notes/core/modules/global-context';
import { I18nProvider } from '@cloud-notes/core/modules/i18n';
import { LifecycleService } from '@cloud-notes/core/modules/lifecycle';
import {
  configureLocalStorageStateStorageImpls,
  NbstoreProvider,
} from '@cloud-notes/core/modules/storage';
import { PopupWindowProvider } from '@cloud-notes/core/modules/url';
import { ClientSchemeProvider } from '@cloud-notes/core/modules/url/providers/client-schema';
import { configureBrowserWorkbenchModule } from '@cloud-notes/core/modules/workbench';
import {
  getCloudNotesWorkspaceSchema,
  WorkspacesService,
} from '@cloud-notes/core/modules/workspace';
import { configureBrowserWorkspaceFlavours } from '@cloud-notes/core/modules/workspace-engine';
import { getWorkerUrl } from '@cloud-notes/env/worker';
import { I18n } from '@cloud-notes/i18n';
import { serveAuthRequests } from '@cloud-notes/mobile-shared/auth/channel';
import { StoreManagerClient } from '@cloud-notes/nbstore/worker/client';
import { setTelemetryTransport } from '@cloud-notes/track';
import { Container } from '@blocksuite/cloud-notes/global/di';
import {
  docLinkBaseURLMiddleware,
  MarkdownAdapter,
  titleMiddleware,
} from '@blocksuite/cloud-notes/shared/adapters';
import { HtmlTransformer } from '@blocksuite/cloud-notes/widgets/linked-doc';
import { App as CapacitorApp } from '@capacitor/app';
import { Keyboard } from '@capacitor/keyboard';
import { StatusBar, Style } from '@capacitor/status-bar';
import { InAppBrowser } from '@capgo/inappbrowser';
import {
  Framework,
  FrameworkRoot,
  getCurrentStore,
  useLiveData,
  useService,
} from '@toeverything/infra';
import { OpClient } from '@toeverything/infra/op';
import { AsyncCall } from 'async-call-rpc';
import { useTheme } from 'next-themes';
import { Suspense, useEffect, useState } from 'react';
import { RouterProvider } from 'react-router-dom';

import { CloudNotesTheme } from './plugins/cloud-notes-theme';
import { AIButton } from './plugins/ai-button';
import { Auth } from './plugins/auth';
import {
  EXTERNAL_FILE_ERRORS,
  EXTERNAL_FILE_ROOT,
  ExternalFile,
  type ExternalDirListing,
  type ExternalFileReadResult,
} from './plugins/external-file';
import { collectVault, toVaultFiles, vaultNameOf } from './vault-import';
import { HashCash } from './plugins/hashcash';
import { MobileBack } from './plugins/mobile-back';
import { NbStoreNativeDBApis } from './plugins/nbstore';
import { Preview } from './plugins/preview';
import './plugins/vault';
import {
  authRequestProvider,
  clearEndpointSession,
  getValidAccessToken,
} from './proxy';

const storeManagerClient = createStoreManagerClient();
setTelemetryTransport(storeManagerClient.telemetry);
window.addEventListener('beforeunload', () => {
  storeManagerClient.dispose();
});

const future = {
  v7_startTransition: true,
} as const;

const framework = new Framework();
configureCommonModules(framework);
configureBrowserWorkbenchModule(framework);
configureLocalStorageStateStorageImpls(framework);
configureBrowserWorkspaceFlavours(framework);
configureMobileModules(framework);
framework.impl(NbstoreProvider, {
  realtime: storeManagerClient.realtime,
  openStore(key, options) {
    const { store, dispose } = storeManagerClient.open(key, options);
    return {
      store,
      dispose: () => {
        dispose();
      },
    };
  },
});
const frameworkProvider = framework.provider();

registerNativePreviewHandlers({
  renderMermaidSvg: request => Preview.renderMermaidSvg(request),
  renderTypstSvg: request => Preview.renderTypstSvg(request),
});

framework.impl(PopupWindowProvider, {
  open: (url: string) => {
    InAppBrowser.open({
      url: url,
    }).catch(console.error);
  },
});

framework.impl(ClientSchemeProvider, {
  getClientScheme() {
    return 'cloud-notes';
  },
});

framework.impl(VirtualKeyboardProvider, {
  show: () => {
    Keyboard.show().catch(console.error);
  },
  hide: () => {
    // In some cases, the keyboard will show again. for example, it will show again
    // when this function is called in click event of button. It may be a bug of
    // android webview or capacitor.
    setTimeout(() => {
      Keyboard.hide().catch(console.error);
    });
  },
  onChange: callback => {
    let disposeRef = {
      dispose: () => {},
    };

    Promise.all([
      Keyboard.addListener('keyboardWillShow', info => {
        (async () => {
          const navBarHeight = (await CloudNotesTheme.getSystemNavBarHeight())
            .height;
          callback({
            // When an physical keyboard is connected, the virtual keyboard height is 0,
            // even though the `keyboardWillShow` event is still triggered.
            visible: info.keyboardHeight !== 0,
            height: info.keyboardHeight - navBarHeight,
            // SystemBars applies the IME inset to the WebView parent, so the
            // keyboard no longer overlaps the web content on Android.
            overlaysContent: false,
          });
        })().catch(console.error);
      }),
      Keyboard.addListener('keyboardWillHide', () => {
        callback({
          visible: false,
          height: 0,
        });
      }),
    ])
      .then(handlers => {
        disposeRef.dispose = () => {
          Promise.all(handlers.map(handler => handler.remove())).catch(
            console.error
          );
        };
      })
      .catch(console.error);

    return () => {
      disposeRef.dispose();
    };
  },
});

framework.impl(ValidatorProvider, {
  async validate(_challenge, resource) {
    const res = await HashCash.hash({ challenge: resource });
    return res.value;
  },
});

framework.impl(AIButtonProvider, {
  presentAIButton: () => {
    return AIButton.present();
  },
  dismissAIButton: () => {
    return AIButton.dismiss();
  },
});

framework.scope(ServerScope).override(AuthProvider, resolver => {
  const serverService = resolver.get(ServerService);
  const endpoint = serverService.server.baseUrl;
  return {
    async signInMagicLink(email, linkToken, clientNonce) {
      await Auth.signInMagicLink({
        endpoint,
        email,
        token: linkToken,
        clientNonce,
      });
    },
    async signInOauth(code, state, _provider, clientNonce) {
      await Auth.signInOauth({
        endpoint,
        code,
        state,
        clientNonce,
      });
      return {};
    },
    async signInPassword(credential) {
      await Auth.signInPassword({
        endpoint,
        ...credential,
      });
    },
    async signInOpenAppSignInCode(code) {
      await Auth.signInOpenApp({
        endpoint,
        code,
      });
    },
    async signOut() {
      try {
        await Auth.signOut({ endpoint });
      } finally {
        await clearEndpointSession(endpoint);
      }
    },
    async clearSession() {
      await clearEndpointSession(endpoint);
    },
  };
});

// ------ some apis for native ------
(window as any).getCurrentServerBaseUrl = () => {
  const globalContextService = frameworkProvider.get(GlobalContextService);
  const currentServerId = globalContextService.globalContext.serverId.get();
  const serversService = frameworkProvider.get(ServersService);
  const defaultServerService = frameworkProvider.get(DefaultServerService);
  const currentServer =
    (currentServerId ? serversService.server$(currentServerId).value : null) ??
    defaultServerService.server;
  return currentServer.baseUrl;
};
(window as any).getCurrentI18nLocale = () => {
  return I18n.language;
};
(window as any).getCurrentWorkspaceId = () => {
  const globalContextService = frameworkProvider.get(GlobalContextService);
  return globalContextService.globalContext.workspaceId.get();
};
(window as any).getCurrentDocId = () => {
  const globalContextService = frameworkProvider.get(GlobalContextService);
  return globalContextService.globalContext.docId.get();
};
(window as any).getCurrentDocContentInMarkdown = async () => {
  const globalContextService = frameworkProvider.get(GlobalContextService);
  const currentWorkspaceId =
    globalContextService.globalContext.workspaceId.get();
  const currentDocId = globalContextService.globalContext.docId.get();
  const workspacesService = frameworkProvider.get(WorkspacesService);
  const workspaceRef = currentWorkspaceId
    ? workspacesService.openByWorkspaceId(currentWorkspaceId)
    : null;
  if (!workspaceRef) {
    return;
  }
  const { workspace, dispose: disposeWorkspace } = workspaceRef;

  const docsService = workspace.scope.get(DocsService);
  const docRef = currentDocId ? docsService.open(currentDocId) : null;
  if (!docRef) {
    return;
  }
  const { doc, release: disposeDoc } = docRef;

  try {
    const blockSuiteDoc = doc.blockSuiteDoc;

    const transformer = blockSuiteDoc.getTransformer([
      docLinkBaseURLMiddleware(blockSuiteDoc.workspace.id),
      titleMiddleware(blockSuiteDoc.workspace.meta.docMetas),
    ]);
    const snapshot = transformer.docToSnapshot(blockSuiteDoc);

    const container = new Container();
    getStoreManager()
      .config.init()
      .value.get('store')
      .forEach(ext => {
        ext.setup(container);
      });
    const provider = container.provider();

    const adapter = new MarkdownAdapter(transformer, provider);
    if (!snapshot) {
      return;
    }

    const markdownResult = await adapter.fromDocSnapshot({
      snapshot,
      assets: transformer.assetsManager,
    });
    return markdownResult.file;
  } finally {
    disposeDoc();
    disposeWorkspace();
  }
};

// setup application lifecycle events, and emit application start event
window.addEventListener('focus', () => {
  frameworkProvider.get(LifecycleService).applicationFocus();
});
frameworkProvider.get(LifecycleService).applicationStart();
CapacitorApp.addListener('appStateChange', ({ isActive }) => {
  if (!isActive) return;
  const servers = frameworkProvider.get(ServersService).servers$.value;
  Promise.allSettled(
    servers.map(server => getValidAccessToken(server.baseUrl))
  ).catch(console.error);
}).catch(console.error);

const getErrorMessage = (error: unknown, fallback: string) => {
  if (typeof error === 'string' && error) {
    return error;
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return fallback;
};

const notifyAuthenticationError = (error: unknown, fallback: string) => {
  console.error(fallback, error);
  notify.error({
    title: I18n['com.cloud-notes.auth.toast.title.failed'](),
    message: getErrorMessage(error, fallback),
  });
};

CapacitorApp.addListener('appUrlOpen', ({ url }) => {
  // try to close browser if it's open
  InAppBrowser.close().catch(e => console.error('Failed to close browser', e));

  const urlObj = new URL(url);

  if (urlObj.hostname === 'authentication') {
    const method = urlObj.searchParams.get('method');
    const payload = JSON.parse(urlObj.searchParams.get('payload') ?? 'false');
    const serverBaseUrl = urlObj.searchParams.get('server');

    if (
      !method ||
      (method !== 'magic-link' && method !== 'oauth') ||
      !payload
    ) {
      notifyAuthenticationError(
        new Error('Invalid authentication url'),
        'Invalid authentication url'
      );
      return;
    }

    let authService = frameworkProvider
      .get(DefaultServerService)
      .server.scope.get(AuthService);

    if (serverBaseUrl) {
      const serversService = frameworkProvider.get(ServersService);
      const server = serversService.getServerByBaseUrl(serverBaseUrl);
      if (!server) {
        notifyAuthenticationError(
          new Error(
            `Authentication callback server not found: ${serverBaseUrl}`
          ),
          'Authentication callback server not found'
        );
        return;
      }
      authService = server.scope.get(AuthService);
    }

    if (method === 'oauth') {
      authService
        .signInOauth(payload.code, payload.state, payload.provider)
        .catch(error =>
          notifyAuthenticationError(error, 'Failed to sign in with OAuth')
        );
    } else if (method === 'magic-link') {
      authService
        .signInMagicLink(payload.email, payload.token)
        .catch(error =>
          notifyAuthenticationError(error, 'Failed to sign in with magic link')
        );
    }
  }
}).catch(e => {
  notifyAuthenticationError(e, 'Failed to handle authentication callback');
});

const ThemeProvider = () => {
  const { resolvedTheme } = useTheme();

  useEffect(() => {
    StatusBar.setStyle({
      style:
        resolvedTheme === 'dark'
          ? Style.Dark
          : resolvedTheme === 'light'
            ? Style.Light
            : Style.Default,
    }).catch(console.error);
    CloudNotesTheme.onThemeChanged({
      darkMode: resolvedTheme === 'dark',
    }).catch(console.error);
  }, [resolvedTheme]);
  return null;
};

const AndroidCapacitorApp = CapacitorApp as typeof CapacitorApp & {
  toggleBackButtonHandler(options: { enabled: boolean }): Promise<void>;
};

const AndroidBackAdapter = () => {
  const coordinator = useService(MobileBackCoordinator);
  const canHandle = useLiveData(coordinator.canHandle$);

  useEffect(() => {
    Promise.all([
      AndroidCapacitorApp.toggleBackButtonHandler({ enabled: !canHandle }),
      MobileBack.setEnabled({ enabled: canHandle }),
    ]).catch(console.error);
  }, [canHandle]);

  useEffect(() => {
    let disposed = false;
    let remove = () => {};
    MobileBack.addListener('back', event => {
      const handled = coordinator.handleInteractivePhase(event.phase);
      if (event.phase === 'commit' && !handled) {
        coordinator.request('system-back');
      }
    })
      .then(handle => {
        if (disposed) handle.remove().catch(console.error);
        else
          remove = () => {
            handle.remove().catch(console.error);
          };
      })
      .catch(console.error);
    return () => {
      disposed = true;
      remove();
      Promise.all([
        AndroidCapacitorApp.toggleBackButtonHandler({ enabled: true }),
        MobileBack.setEnabled({ enabled: false }),
      ]).catch(console.error);
    };
  }, [coordinator]);

  return null;
};


// #469 — the ONE feature over upstream: open a file from emulated storage.
// The native FAB dispatches 'cloud-notes:open-file'; the explorer below picks
// a file, and openExternalFile reads it through ExternalFilePlugin (every
// failure carries its own visible message) and import the markdown into the local workspace via Cloud Notes's own
// Obsidian importer, so the vault docs land in the app's document store.
//
// #513 — this used to be `useService(ImportService)` in the component body, and
// that single line is what painted the white page. ImportService is registered
// as `framework.scope(WorkspaceScope).service(ImportService, …)` (see
// core/src/modules/import/index.ts), but everything rendered by App() below
// lives on the ROOT provider — there is no workspace scope on the stack, so the
// resolver fell through to `throw new ComponentNotFoundError(ImportService)` on
// the FIRST render. Nothing above it is an error boundary, so React tore down
// the whole tree, the WebView painted nothing, and the FAB's listener was never
// registered — the inert button and the blank page were one fault.
//
// So the service is resolved WHERE IT LIVES and WHEN IT IS NEEDED: out of the
// open workspace's own scope, at event time, exactly as
// getCurrentDocContentInMarkdown above already does. Rendering this component
// can no longer resolve anything, so it can no longer throw.
//
// #547 — HTML is CONVERTED, not previewed. ExternalFilePlugin has always
// accepted .html, and this function then wrapped it as a text/markdown File for
// the Obsidian importer, so the doc showed the literal markup. An HTML file now
// goes through upstream's own HtmlAdapter (HtmlTransformer.importHTMLToDoc, the
// exact path the desktop import dialog's `html` entry uses) and lands as a
// real BlockSuite doc: editable, searchable, synced like every other doc. A
// separate preview surface was rejected: it would be a second viewer beside the
// editor, it would not persist anything, and it would run the file's own markup
// inside the WebView that carries the Capacitor bridge — the converter keeps
// only what maps to blocks, so no script in the file ever executes.
// Which extensions take this route is asserted against the native SUPPORTED set
// by test/test-html-import-route.sh.
const HTML_EXTENSIONS = new Set(['html', 'htm']);

const isHtmlFile = (name: string) =>
  HTML_EXTENSIONS.has(name.split('.').pop()?.toLowerCase() ?? '');

// Every native failure carries its own code + message (#469): show it, and for
// a permission problem offer the jump to Settings once — never a silent retry.
const reportExternalFileError = (err: unknown, title: string) => {
  const code = (err as { code?: string })?.code ?? 'UNKNOWN';
  const fallback = EXTERNAL_FILE_ERRORS[code] ?? 'Cannot open the file.';
  notify.error({
    title,
    message: err instanceof Error && err.message ? err.message : fallback,
  });
  if (
    code === 'PERMISSION_DENIED' &&
    window.confirm('Open Settings to grant storage access?')
  ) {
    ExternalFile.openSettings().catch(console.error);
  }
};

const openExternalFile = async (path: string) => {
  let file: ExternalFileReadResult;
  try {
    file = await ExternalFile.readFile({ path });
  } catch (err: unknown) {
    reportExternalFileError(err, 'Cannot open file');
    return;
  }
  await importExternalFile(file);
};

// #1642 a text file another app opened with this app (the manifest's VIEW/EDIT filters): the native side read it
// (ExternalFilePlugin.takePendingOpen) and it lands as a note exactly like a file picked in the explorer.
const openPendingFile = async () => {
  let pending: Awaited<ReturnType<typeof ExternalFile.takePendingOpen>>;
  try {
    pending = await ExternalFile.takePendingOpen();
  } catch (err: unknown) {
    console.error(err);
    return;
  }
  if (!pending.present) return;
  if (pending.error || pending.content === undefined) {
    notify.error({
      title: 'Cannot open file',
      message: pending.error ?? 'The file could not be read.',
    });
    return;
  }
  await importExternalFile({
    content: pending.content,
    name: pending.name ?? 'note.txt',
    size: pending.size ?? pending.content.length,
    path: '',
  });
};

const importExternalFile = async (file: ExternalFileReadResult) => {
  const workspaceId = frameworkProvider
    .get(GlobalContextService)
    .globalContext.workspaceId.get();
  const workspaceRef = workspaceId
    ? frameworkProvider.get(WorkspacesService).openByWorkspaceId(workspaceId)
    : null;
  if (!workspaceRef) {
    notify.error({
      title: 'Cannot open file',
      message: `Open a workspace first — there is nowhere to import ${file.name} into.`,
    });
    return;
  }

  const { workspace, dispose: disposeWorkspace } = workspaceRef;
  try {
    if (isHtmlFile(file.name)) {
      const docId = await HtmlTransformer.importHTMLToDoc({
        collection: workspace.docCollection,
        schema: getCloudNotesWorkspaceSchema(),
        extensions: getStoreManager().config.init().value.get('store'),
        html: file.content,
        fileName: file.name.replace(/\.[^.]+$/, ''),
      });
      if (!docId) throw new Error(`${file.name} produced no document.`);
    } else {
      await workspace.scope
        .get(ImportService)
        .importObsidianVault([
          new File([file.content], /\.(md|markdown)$/i.test(file.name) ? file.name : `${file.name}.md`, {
            type: 'text/markdown',
          }),
        ]);
    }
    notify.success({
      title: 'Opened',
      message: `${file.name} imported into the workspace.`,
    });
  } catch (err: unknown) {
    notify.error({
      title: 'Import failed',
      message: err instanceof Error ? err.message : String(err),
    });
  } finally {
    disposeWorkspace();
  }
};

// Phase 1 (cloud-notes-hybrid): "Import this folder as a vault". The folder is
// walked through the same plugin (vault-import.ts) and every note goes to the
// Obsidian importer in ONE call, so [[wikilinks]] between the notes resolve.
// Attachments come along as bytes; whatever is skipped is counted, never
// dropped silently. Resolved at event time, like openExternalFile (#513).
const importVaultFolder = async (root: string) => {
  let collected: Awaited<ReturnType<typeof collectVault>>;
  try {
    collected = await collectVault(ExternalFile, root);
  } catch (err: unknown) {
    reportExternalFileError(err, 'Cannot read folder');
    return;
  }
  if (collected.notes.length === 0) {
    notify.error({
      title: 'Nothing to import',
      message: 'No .md notes were found in this folder or below it.',
    });
    return;
  }

  const workspaceId = frameworkProvider
    .get(GlobalContextService)
    .globalContext.workspaceId.get();
  const workspaceRef = workspaceId
    ? frameworkProvider.get(WorkspacesService).openByWorkspaceId(workspaceId)
    : null;
  if (!workspaceRef) {
    notify.error({
      title: 'Cannot import',
      message: 'Open a workspace first — there is nowhere to import the vault into.',
    });
    return;
  }

  const { workspace, dispose: disposeWorkspace } = workspaceRef;
  try {
    await workspace.scope
      .get(ImportService)
      .importObsidianVault(toVaultFiles(collected.notes, vaultNameOf(root), collected.attachments));
    const skipped = collected.skipped.length;
    notify.success({
      title: 'Vault imported',
      message:
        `${collected.notes.length} notes and ${collected.attachments.length} attachments imported` +
        (skipped ? `, ${skipped} entries skipped` : '') +
        (collected.truncated ? '. A size limit stopped the walk early.' : '.'),
    });
  } catch (err: unknown) {
    notify.error({
      title: 'Vault import failed',
      message: err instanceof Error ? err.message : String(err),
    });
  } finally {
    disposeWorkspace();
  }
};

// #547 — the file explorer: the FAB's event opens this browser over the
// device's visible storage instead of asking for a typed path. It lists one
// folder at a time through ExternalFilePlugin.listDir (the native side offers
// only folders and the extensions readFile accepts) and hands the tapped file
// to openExternalFile. Plain elements, no upstream component: it renders on the
// ROOT provider, where a service lookup is exactly what #513 forbade.
const ExternalFileOpener = () => {
  const [listing, setListing] = useState<ExternalDirListing | null>(null);

  const browse = (path: string) => {
    ExternalFile.listDir({ path })
      .then(setListing)
      .catch((err: unknown) => {
        reportExternalFileError(err, 'Cannot open folder');
        setListing(null);
      });
  };

  useEffect(() => {
    const listener = () => browse(EXTERNAL_FILE_ROOT);
    window.addEventListener('cloud-notes:open-file', listener);
    // #1642 "Open with": native dispatches this when a text file arrives; one that arrived before the page loaded is taken now.
    const viewListener = () => {
      openPendingFile().catch(console.error);
    };
    window.addEventListener('cloud-notes:view-intent', viewListener);
    viewListener();
    return () => {
      window.removeEventListener('cloud-notes:open-file', listener);
      window.removeEventListener('cloud-notes:view-intent', viewListener);
    };
  }, []);

  if (!listing) return null;

  const parent = listing.parent;
  const row = {
    display: 'block',
    width: '100%',
    padding: '14px 16px',
    textAlign: 'left',
    border: 0,
    borderBottom: '1px solid rgba(128,128,128,0.25)',
    background: 'transparent',
    color: 'inherit',
    font: 'inherit',
  } as const;

  return (
    <div
      role="dialog"
      aria-label="Open file"
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 10000,
        display: 'flex',
        flexDirection: 'column',
        background: 'var(--cloud-notes-background-primary-color, #fff)',
        color: 'var(--cloud-notes-text-primary-color, #000)',
      }}
    >
      <div style={{ ...row, display: 'flex', gap: 12, fontWeight: 600 }}>
        <span style={{ flex: 1, overflowWrap: 'anywhere' }}>{listing.path}</span>
        <button
          type="button"
          onClick={() => {
            const root = listing.path;
            setListing(null);
            importVaultFolder(root).catch(console.error);
          }}
        >
          Import as vault
        </button>
        <button type="button" onClick={() => setListing(null)}>
          Close
        </button>
      </div>
      <div style={{ flex: 1, overflowY: 'auto' }}>
        {parent ? (
          <button type="button" style={row} onClick={() => browse(parent)}>
            ..
          </button>
        ) : null}
        {listing.entries.map(entry => (
          <button
            key={entry.name}
            type="button"
            style={row}
            onClick={() => {
              const path = listing.path + entry.name;
              if (entry.isDir) {
                browse(path);
              } else {
                setListing(null);
                openExternalFile(path).catch(console.error);
              }
            }}
          >
            {entry.isDir ? `${entry.name}/` : entry.name}
          </button>
        ))}
        {listing.entries.length === 0 ? (
          <div style={row}>No folders or supported files here.</div>
        ) : null}
      </div>
    </div>
  );
};

export function App() {
  return (
    <Suspense>
      <FrameworkRoot framework={frameworkProvider}>
        <I18nProvider>
          <MobileModalConfigProvider>
            <CloudNotesContext store={getCurrentStore()}>
              <ThemeProvider />
              <AndroidBackAdapter />
              <ExternalFileOpener />
              <RouterProvider
                fallbackElement={<AppFallback />}
                router={router}
                future={future}
              />
            </CloudNotesContext>
          </MobileModalConfigProvider>
        </I18nProvider>
      </FrameworkRoot>
    </Suspense>
  );
}

function createStoreManagerClient() {
  const worker = new Worker(getWorkerUrl('nbstore'));
  const { port1: nativeDBApiChannelServer, port2: nativeDBApiChannelClient } =
    new MessageChannel();
  AsyncCall<typeof NbStoreNativeDBApis>(NbStoreNativeDBApis, {
    channel: {
      on(listener) {
        const f = (e: MessageEvent<any>) => {
          listener(e.data);
        };
        nativeDBApiChannelServer.addEventListener('message', f);
        return () => {
          nativeDBApiChannelServer.removeEventListener('message', f);
        };
      },
      send(data) {
        nativeDBApiChannelServer.postMessage(data);
      },
    },
    log: false,
  });
  nativeDBApiChannelServer.start();
  worker.postMessage(
    {
      type: 'native-db-api-channel',
      port: nativeDBApiChannelClient,
    },
    [nativeDBApiChannelClient]
  );

  const { port1: authTokenChannelServer, port2: authTokenChannelClient } =
    new MessageChannel();
  serveAuthRequests(authTokenChannelServer, authRequestProvider);
  worker.postMessage(
    { type: 'auth-access-token-channel', port: authTokenChannelClient },
    [authTokenChannelClient]
  );
  return new StoreManagerClient(new OpClient(worker));
}
