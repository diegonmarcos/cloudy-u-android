import {
  useBindWorkbenchToBrowserRouter,
  WorkbenchService,
} from '@cloud-notes/core/modules/workbench';
import { ViewRoot } from '@cloud-notes/core/modules/workbench/view/view-root';
import { WorkspaceService } from '@cloud-notes/core/modules/workspace';
import { useLiveData, useService } from '@toeverything/infra';
import {
  type PropsWithChildren,
  useEffect,
  useRef,
} from 'react';
import { type RouteObject, useLocation } from 'react-router-dom';

import {
  isMobileRootDestination,
  MobileBackCoordinator,
  type MobileDestinationKind,
} from '../../modules/back-coordinator';

declare global {
  interface WindowEventMap {
    'cloud-notes:memory-pressure': Event;
  }
}

const destinationKind = (_pathname: string): MobileDestinationKind => 'home';

export const MobileWorkbenchRoot = ({ routes }: { routes: RouteObject[] }) => {
  const workbench = useService(WorkbenchService).workbench;
  const workspaceId = useService(WorkspaceService).workspace.id;

  const views = useLiveData(workbench.views$);
  const view = views[0];
  const viewLocation = useLiveData(view.location$);
  const backCoordinator = useService(MobileBackCoordinator);
  const previousLocation = useRef({
    index: view.history.index,
    location: `${viewLocation.pathname}${viewLocation.search}${viewLocation.hash}`,
    kind: destinationKind(viewLocation.pathname),
  });

  const location = useLocation();
  const basename = location.pathname.match(/\/workspace\/[^/]+/g)?.[0] ?? '/';

  useBindWorkbenchToBrowserRouter(workbench, basename);

  useEffect(() => {
    workbench.updateBasename(basename);
  }, [basename, workbench]);

  useEffect(() => {
    const kind = destinationKind(viewLocation.pathname);
    const currentLocation = `${viewLocation.pathname}${viewLocation.search}${viewLocation.hash}`;
    const previous = previousLocation.current;
    const index = view.history.index;
    if (index > previous.index && !isMobileRootDestination(kind)) {
      backCoordinator.pushSource({
        workspaceId,
        location: previous.location,
        restoration:
          previous.kind === 'home'
            ? backCoordinator.snapshot('home')
            : undefined,
      });
    } else if (index < previous.index) {
      backCoordinator.discardSources(previous.index - index);
    }
    if (
      isMobileRootDestination(kind) &&
      previous.location !== currentLocation
    ) {
      backCoordinator.discardSources();
    }
    previousLocation.current = {
      index,
      location: currentLocation,
      kind,
    };
    const canGoBack = backCoordinator.hasSource(workspaceId);
    backCoordinator.setDestination({
      kind,
      back: canGoBack
        ? () => {
            const source = backCoordinator.popSource(workspaceId);
            if (!source) return false;
            view.replace(source.location);
            return true;
          }
        : undefined,
      up: () => view.replace('/vault'),
    });
  }, [backCoordinator, view, viewLocation, workspaceId]);

  return <ViewRoot routes={routes} view={view} />;
};
