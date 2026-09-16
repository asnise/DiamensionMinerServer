export function formatDimension(val) {
  if (val === undefined || val === null) return undefined;
  if (typeof val === 'number') return `${val}px`;
  return val;
}

export function Skeleton({
  variant = 'rounded',
  width,
  height,
  animation = 'wave',
  className = '',
  style = {},
  ...props
}) {
  const variantClass = variant === 'circular'
    ? 'skeleton-circular'
    : variant === 'text'
    ? 'skeleton-text'
    : variant === 'rounded'
    ? 'skeleton-rounded'
    : '';

  const animClass = animation === 'pulse'
    ? 'skeleton-pulse'
    : animation === 'none'
    ? ''
    : '';

  const inlineStyle = {
    width: formatDimension(width),
    height: formatDimension(height),
    ...style,
  };

  return (
    <div
      className={`skeleton ${variantClass} ${animClass} ${className}`.trim()}
      style={inlineStyle}
      {...props}
    />
  );
}

export function TableSkeleton({
  rows = 5,
  columns = 3,
  hasAvatar = false,
  className = '',
}) {
  const rowList = Array.from({ length: Math.max(1, rows) });
  const colCount = typeof columns === 'number' ? columns : columns.length;
  const colConfigs = Array.isArray(columns)
    ? columns
    : Array.from({ length: columns }).map((_, idx) => {
        if (idx === 0) return { width: hasAvatar ? '140px' : '90px' };
        if (idx === colCount - 1) return { width: '180px' };
        return { width: `${Math.floor(70 / Math.max(1, colCount - 2))}%` };
      });

  return (
    <>
      {rowList.map((_, rIdx) => (
        <tr key={rIdx} className={className}>
          {colConfigs.map((col, cIdx) => (
            <td key={cIdx} style={{ textAlign: col.align || 'left' }}>
              {cIdx === 0 && hasAvatar ? (
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <Skeleton variant="circular" width={32} height={32} />
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                    <Skeleton width={col.width || 120} height={14} />
                    <Skeleton width={80} height={11} />
                  </div>
                </div>
              ) : (
                <Skeleton
                  width={col.width || (cIdx === 0 ? 80 : 120)}
                  height={col.height || 14}
                  style={col.align === 'right' ? { marginLeft: 'auto' } : {}}
                />
              )}
            </td>
          ))}
        </tr>
      ))}
    </>
  );
}

export function StatCardSkeleton({ count = 1, className = '', style = {} }) {
  const cards = Array.from({ length: Math.max(1, count) });
  return (
    <>
      {cards.map((_, i) => (
        <div key={i} className={`stat-card-skeleton ${className}`} style={style}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
            <Skeleton width={22} height={22} style={{ borderRadius: 6 }} />
            <Skeleton width={48} height={12} />
          </div>
          <Skeleton width={72} height={24} style={{ marginBottom: 8 }} />
          <Skeleton width={110} height={12} />
        </div>
      ))}
    </>
  );
}

export function CardSkeleton({ lines = 3, hasHeader = true, className = '', style = {} }) {
  return (
    <div className={`card-skeleton ${className}`} style={style}>
      {hasHeader && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18 }}>
          <Skeleton width={140} height={20} />
          <Skeleton width={80} height={32} style={{ borderRadius: 6 }} />
        </div>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {Array.from({ length: lines }).map((_, i) => (
          <Skeleton
            key={i}
            width={i === lines - 1 ? '60%' : '100%'}
            height={14}
          />
        ))}
      </div>
    </div>
  );
}

export function ListSkeleton({ count = 5, hasAvatar = true, className = '', style = {} }) {
  return (
    <div className={className} style={{ display: 'flex', flexDirection: 'column', gap: 12, ...style }}>
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 0' }}>
          {hasAvatar && <Skeleton variant="circular" width={36} height={36} />}
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6 }}>
            <Skeleton width="40%" height={14} />
            <Skeleton width="70%" height={12} />
          </div>
          <Skeleton width={50} height={20} style={{ borderRadius: 999 }} />
        </div>
      ))}
    </div>
  );
}

export function ProfileHeroSkeleton({ className = '', style = {} }) {
  return (
    <div className={`card profile-hero ${className}`} style={{ marginBottom: 24, ...style }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 20 }}>
        <Skeleton variant="circular" width={72} height={72} style={{ borderRadius: 16 }} />
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <Skeleton width={150} height={22} />
          <Skeleton width={230} height={12} />
          <Skeleton width={170} height={12} />
        </div>
      </div>
      <div style={{ display: 'flex', gap: 16 }}>
        <div className="stat-card" style={{ minWidth: 120 }}>
          <Skeleton width={18} height={18} style={{ borderRadius: 6, marginBottom: 8 }} />
          <Skeleton width={50} height={16} style={{ marginBottom: 6 }} />
          <Skeleton width={40} height={11} />
        </div>
        <div className="stat-card" style={{ minWidth: 120 }}>
          <Skeleton width={18} height={18} style={{ borderRadius: 6, marginBottom: 8 }} />
          <Skeleton width={50} height={16} style={{ marginBottom: 6 }} />
          <Skeleton width={40} height={11} />
        </div>
      </div>
    </div>
  );
}

Skeleton.Text = function SkeletonText(props) {
  return <Skeleton variant="text" {...props} />;
};

Skeleton.Circular = function SkeletonCircular(props) {
  return <Skeleton variant="circular" {...props} />;
};

Skeleton.Avatar = function SkeletonAvatar({ size = 32, ...props }) {
  return <Skeleton variant="circular" width={size} height={size} {...props} />;
};

Skeleton.Table = TableSkeleton;
Skeleton.Card = CardSkeleton;
Skeleton.StatCard = StatCardSkeleton;
Skeleton.List = ListSkeleton;
Skeleton.ProfileHero = ProfileHeroSkeleton;

export default Skeleton;