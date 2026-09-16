"use client";

import { useState, useEffect } from 'react';
import { Plus, FileJson } from 'lucide-react';
import EditorModal from './modals/EditorModal';
import Pagination from './Pagination';
import { TableSkeleton, formatDimension } from './Skeleton';

function parseScrollOverflow(val, fallback) {
  if (val === true) return 'auto';
  if (val === false) return 'hidden';
  if (typeof val === 'string') return val;
  return fallback;
}

export default function DataTable({
  kind,
  data: propData,
  total: propTotal,
  columns,
  loading: propLoading,
  width = '100%',
  height,
  minWidth,
  maxWidth,
  minHeight,
  maxHeight,
  tableMinWidth,
  scrollX = true,
  scrollY,
  showScrollbar = true,
  stickyHeader,
  showToolbar = true,
  showPagination = true,
  className = '',
  style = {},
  onRowClick,
}) {
  const [internalData, setInternalData] = useState([]);
  const [internalTotal, setInternalTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [limit, setLimit] = useState(10);
  const [editingItem, setEditingItem] = useState(null);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const currentKey = `${kind}-${page}-${limit}`;
  const [prevKey, setPrevKey] = useState(currentKey);
  const [internalLoading, setInternalLoading] = useState(Boolean(kind));

  if (kind && prevKey !== currentKey) {
    setPrevKey(currentKey);
    setInternalLoading(true);
  }

  const isControlled = propData !== undefined;
  const data = isControlled ? propData : internalData;
  const total = isControlled ? (propTotal !== undefined ? propTotal : propData.length) : internalTotal;
  const isLoading = propLoading !== undefined ? propLoading : internalLoading;

  const applyData = (json) => {
    setInternalData(json.data || []);
    setInternalTotal(json.total || 0);
  };

  useEffect(() => {
    if (!kind) return;
    let cancelled = false;
    fetch(`/api/content/${kind}?page=${page}&limit=${limit}`)
      .then(res => res.json())
      .then(json => {
        if (!cancelled) {
          applyData(json);
          setInternalLoading(false);
        }
      })
      .catch(() => {
        if (!cancelled) setInternalLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [kind, page, limit]);

  const handleEdit = (item) => {
    if (onRowClick) {
      onRowClick(item);
      return;
    }
    setEditingItem({ id: item.definition_id, payload: item.payload });
    setIsModalOpen(true);
  };

  const handleCreate = () => {
    setEditingItem(null);
    setIsModalOpen(true);
  };

  const closeModal = (didSave) => {
    setIsModalOpen(false);
    if (didSave && kind) {
      setInternalLoading(true);
      fetch(`/api/content/${kind}?page=${page}&limit=${limit}`)
        .then(res => res.json())
        .then(json => {
          applyData(json);
          setInternalLoading(false);
        });
    }
  };

  const defaultColumns = [
    {
      key: 'definition_id',
      title: 'ID',
      style: { fontWeight: 550 },
      render: (item) => item.definition_id,
    },
    {
      key: 'version',
      title: 'Version',
      render: (item) => <span className="status-badge offline">v{item.version}</span>,
    },
    {
      key: 'payload',
      title: 'Summary',
      render: (item) => {
        const text = JSON.stringify(item.payload || {});
        return (
          <span style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace', fontSize: 12, color: 'var(--text-secondary)' }}>
            {text.substring(0, 100)}{text.length > 100 ? '...' : ''}
          </span>
        );
      },
    },
  ];

  const tableColumns = columns || defaultColumns;

  const overflowX = parseScrollOverflow(scrollX, 'auto');
  const overflowY = parseScrollOverflow(
    scrollY,
    height || maxHeight ? 'auto' : 'visible'
  );

  const isSticky = stickyHeader !== undefined
    ? Boolean(stickyHeader)
    : (overflowY === 'auto' || overflowY === 'scroll');

  const containerStyle = {
    width: formatDimension(width),
    minWidth: formatDimension(minWidth),
    maxWidth: formatDimension(maxWidth),
    height: formatDimension(height),
    minHeight: formatDimension(minHeight),
    maxHeight: formatDimension(maxHeight),
    ...style,
  };

  const scrollAreaStyle = {
    overflowX,
    overflowY,
  };

  const tableStyle = {
    minWidth: formatDimension(tableMinWidth),
  };

  return (
    <div style={{ width: formatDimension(width), minWidth: formatDimension(minWidth), maxWidth: formatDimension(maxWidth) }}>
      {showToolbar && kind && (
        <div className="toolbar">
          <span style={{ color: 'var(--text-secondary)', fontSize: 13.5 }}>
            {nf(total)} definition{total === 1 ? '' : 's'} in this registry
          </span>
          <button className="btn-primary" onClick={handleCreate}>
            <Plus size={16} strokeWidth={2.5} />
            Create New
          </button>
        </div>
      )}

      <div className={`data-table-container ${className}`.trim()} style={containerStyle}>
        <div
          className={`data-table-scroll-area ${showScrollbar ? 'custom-scrollbar' : 'no-scrollbar'}`}
          style={scrollAreaStyle}
        >
          <table className="data-table" style={tableStyle}>
            <thead>
              <tr>
                {tableColumns.map((col, idx) => (
                  <th
                    key={col.key || idx}
                    className={isSticky ? 'sticky-header' : ''}
                    style={{
                      width: formatDimension(col.width),
                      textAlign: col.align || 'left',
                      ...col.headerStyle,
                    }}
                  >
                    {col.title || col.label || col.name || col.key}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {isLoading && (
                <TableSkeleton
                  rows={Math.min(limit || 5, 5)}
                  columns={tableColumns.map(c => ({ width: c.width, align: c.align }))}
                />
              )}

              {!isLoading && data.length === 0 && (
                <tr>
                  <td colSpan={tableColumns.length}>
                    <div className="empty-state">
                      <div
                        style={{
                          width: 44,
                          height: 44,
                          margin: '0 auto 12px',
                          borderRadius: 12,
                          background: 'var(--accent-soft)',
                          color: 'var(--accent-strong)',
                          display: 'grid',
                          placeItems: 'center',
                        }}
                      >
                        <FileJson size={22} strokeWidth={1.8} />
                      </div>
                      <div className="empty-title">No definitions yet</div>
                      <div>Create your first {kind || 'entry'} to get started.</div>
                    </div>
                  </td>
                </tr>
              )}

              {!isLoading && data.map((item, rowIdx) => (
                <tr
                  key={item.definition_id || item.id || rowIdx}
                  onClick={() => handleEdit(item)}
                  style={{ cursor: (kind || onRowClick) ? 'pointer' : 'default' }}
                >
                  {tableColumns.map((col, cIdx) => (
                    <td
                      key={col.key || cIdx}
                      style={{
                        textAlign: col.align || 'left',
                        ...col.style,
                      }}
                    >
                      {col.render
                        ? col.render(item, rowIdx)
                        : item[col.key] !== undefined
                        ? String(item[col.key])
                        : null}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {showPagination && total > 0 && (
          <Pagination
            total={total}
            page={page}
            limit={limit}
            setPage={setPage}
            setLimit={setLimit}
          />
        )}
      </div>

      {isModalOpen && kind && (
        <EditorModal kind={kind} initialData={editingItem} onClose={closeModal} />
      )}
    </div>
  );
}

function nf(n) {
  return Number(n || 0).toLocaleString('en-US');
}