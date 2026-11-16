import { Component as IndexComponent } from '@cloud-notes/core/desktop/pages/index';

import { AppFallback } from '../components/app-fallback';

// Default route fallback for mobile

export const Component = () => {
  // TODO: replace with a mobile version
  return (
    <IndexComponent
      defaultIndexRoute={'vault'}
      fallback={<AppFallback />}
      createErrorFallback={(retry, cause) => (
        <AppFallback onRetry={retry} errorCause={cause} />
      )}
    />
  );
};
