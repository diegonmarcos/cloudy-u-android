import { Button, SafeArea, Skeleton } from '@cloud-notes/component';
import { useI18n } from '@cloud-notes/i18n';
import { cssVarV2 } from '@cloud-notes/theme/v2';

// #1717 the loading placeholder is the vault's own shape (round sidebar button, the mode pill, a few lines of a note, the floating
// bottom bar), not the old home page. The error panel below is unchanged.
const lineWidths = ['62%', '90%', '78%', '84%', '55%'];

export const AppFallback = ({
  onRetry,
  errorCause,
}: {
  onRetry?: () => void;
  // #522: what ACTUALLY failed. Without it this panel printed
  // error.INTERNAL_SERVER_ERROR — "An internal error occurred." — over every
  // possible cause, and over a server that does not exist in this build. The
  // owner reported the string twice; nobody could act on it either time,
  // because the string is the same whatever went wrong.
  errorCause?: string;
}) => {
  const t = useI18n();

  return (
    <SafeArea top bottom style={{ height: '100dvh', overflow: 'hidden' }}>
      {/* top: the round sidebar button and the Raw | Edit | View pill */}
      <div style={{ padding: '10px 14px', display: 'flex', alignItems: 'center', gap: 10 }}>
        <Skeleton animation="wave" style={{ width: 44, height: 44, borderRadius: 22 }} />
        <div style={{ flex: 1 }} />
        <Skeleton animation="wave" style={{ width: 220, height: 44, borderRadius: 22 }} />
      </div>
      {/* a note: a title and a few lines */}
      <div style={{ padding: '24px 16px', display: 'flex', flexDirection: 'column', gap: 16 }}>
        <Skeleton animation="wave" style={{ width: '48%', height: 28, borderRadius: 6 }} />
        {lineWidths.map((width, i) => (
          <Skeleton key={i} animation="wave" style={{ width, height: 14, borderRadius: 4 }} />
        ))}
      </div>
      {/* bottom: the floating bar */}
      <div style={{ position: 'fixed', left: 14, right: 14, bottom: 24, display: 'flex', justifyContent: 'center' }}>
        <Skeleton animation="wave" style={{ width: '100%', maxWidth: 420, height: 56, borderRadius: 28 }} />
      </div>
      {onRetry ? (
        <div
          role="alert"
          style={{
            position: 'fixed',
            inset: 0,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 16,
            padding: 24,
            background: cssVarV2('layer/background/mobile/primary'),
          }}
        >
          <div style={{ maxWidth: 320, textAlign: 'center' }}>
            {t['error.INTERNAL_SERVER_ERROR']()}
          </div>
          {errorCause ? (
            <div
              style={{
                maxWidth: 320,
                maxHeight: 180,
                overflow: 'auto',
                fontSize: 11,
                fontFamily: 'monospace',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
                textAlign: 'left',
                opacity: 0.7,
              }}
            >
              {errorCause}
            </div>
          ) : null}
          <Button
            variant="primary"
            style={{ minWidth: 104, height: 44 }}
            onClick={onRetry}
          >
            {t['Retry']()}
          </Button>
        </div>
      ) : null}
    </SafeArea>
  );
};
