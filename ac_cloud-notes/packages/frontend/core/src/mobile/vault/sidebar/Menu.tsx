// A small popup menu used by the sidebar, the top bar and the bottom bar. A transparent shade
// behind it closes it; items are plain rows. Shared UI only: no vault state in here.
import type { CSSProperties, ReactNode } from 'react';

import { Icon } from '../icons';

export interface MenuItem {
  id: string;
  label: string;
  icon?: string;
  /** shows a check mark (the chosen option of a group) */
  checked?: boolean;
  disabled?: boolean;
  /** a divider is drawn before this item */
  divider?: boolean;
  /** a group title drawn before this item */
  title?: string;
  hint?: string;
}

export const Menu = ({ items, onPick, onClose, style, testId, header }: {
  items: MenuItem[];
  onPick: (id: string) => void;
  onClose: () => void;
  style?: CSSProperties;
  testId?: string;
  header?: ReactNode;
}) => (
  <>
    <div className="cnv-shade" onClick={e => { e.stopPropagation(); onClose(); }} />
    <div className="cnv-menu" role="menu" style={style} data-testid={testId} onClick={e => e.stopPropagation()}>
      {header}
      {items.map(it => (
        <div key={it.id}>
          {it.divider && <hr />}
          {it.title && <div className="cnv-menu-title">{it.title}</div>}
          <button type="button" role="menuitem" className="cnv-row" disabled={it.disabled} aria-checked={it.checked} aria-label={it.label} data-item={it.id}
            style={it.disabled ? { opacity: 0.45 } : undefined}
            onClick={() => { onClose(); onPick(it.id); }}>
            {it.icon ? <Icon name={it.icon} size={16} /> : <span style={{ width: 16 }} />}
            <span style={{ flex: 1 }}>{it.label}</span>
            {it.hint && <span className="cnv-dim">{it.hint}</span>}
            {it.checked && <Icon name="check" size={14} />}
          </button>
        </div>
      ))}
    </div>
  </>
);
