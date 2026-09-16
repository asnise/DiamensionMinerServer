export default function Pagination({ total, page, limit, setPage, setLimit }) {
  const totalPages = Math.ceil(total / limit) || 1;
  const from = total === 0 ? 0 : (page - 1) * limit + 1;
  const to = Math.min(page * limit, total);

  return (
    <div className="pagination">
      <span>
        Showing <strong>{from}</strong>–<strong>{to}</strong> of {total} entries
      </span>
      <div className="page-controls">
        <select value={limit} onChange={e => setLimit(Number(e.target.value))} title="Rows per page">
          {[10, 20, 30, 40, 50, 60, 70, 80, 90, 100].map(val => (
            <option key={val} value={val}>{val} / page</option>
          ))}
        </select>
        <button className="page-btn" disabled={page === 1} onClick={() => setPage(page - 1)}>Prev</button>
        <span className="page-indicator">Page {page} of {totalPages}</span>
        <button className="page-btn" disabled={page === totalPages} onClick={() => setPage(page + 1)}>Next</button>
      </div>
    </div>
  );
}