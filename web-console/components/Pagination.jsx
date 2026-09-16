export default function Pagination({ total, page, limit, setPage, setLimit }) {
  const totalPages = Math.ceil(total / limit) || 1;
  return (
    <div className="pagination">
      <span>Showing {Math.min((page - 1) * limit + 1, total)} to {Math.min(page * limit, total)} of {total} entries</span>
      <div className="page-controls">
        <select value={limit} onChange={e => setLimit(Number(e.target.value))} style={{marginRight: 10, padding: 4}}>
          <option value={10}>10</option>
          <option value={20}>20</option>
          <option value={50}>50</option>
        </select>
        <button className="page-btn" disabled={page === 1} onClick={() => setPage(page - 1)}>Prev</button>
        <span>Page {page} of {totalPages}</span>
        <button className="page-btn" disabled={page === totalPages} onClick={() => setPage(page + 1)}>Next</button>
      </div>
    </div>
  );
}
