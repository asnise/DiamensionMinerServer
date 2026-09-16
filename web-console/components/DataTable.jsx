"use client";

import { useState, useEffect } from 'react';
import { Plus } from 'lucide-react';
import EditorModal from './modals/EditorModal';
import Pagination from './Pagination';

export default function DataTable({ kind }) {
  const [data, setData] = useState([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [limit, setLimit] = useState(10);
  const [editingItem, setEditingItem] = useState(null);
  const [isModalOpen, setIsModalOpen] = useState(false);

  useEffect(() => {
    fetchData();
  }, [kind, page, limit]);

  const fetchData = async () => {
    const res = await fetch(`/api/content/${kind}?page=${page}&limit=${limit}`);
    const json = await res.json();
    setData(json.data || []);
    setTotal(json.total || 0);
  };

  const handleEdit = (item) => {
    setEditingItem({ id: item.definition_id, payload: item.payload });
    setIsModalOpen(true);
  };

  const handleCreate = () => {
    setEditingItem(null);
    setIsModalOpen(true);
  };

  const closeModal = (didSave) => {
    setIsModalOpen(false);
    if (didSave) fetchData();
  };

  return (
    <div>
      <div style={{ marginBottom: 15 }}>
        <button className="btn-primary" onClick={handleCreate}><Plus size={16}/> Create New</button>
      </div>
      <table className="data-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>Version</th>
            <th>Summary</th>
          </tr>
        </thead>
        <tbody>
          {data.map(item => (
            <tr key={item.definition_id} onClick={() => handleEdit(item)} style={{cursor: 'pointer'}}>
              <td>{item.definition_id}</td>
              <td>v{item.version}</td>
              <td style={{fontFamily: 'monospace', fontSize: 12, opacity: 0.7}}>
                {JSON.stringify(item.payload).substring(0, 100)}...
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <Pagination total={total} page={page} limit={limit} setPage={setPage} setLimit={setLimit} />
      {isModalOpen && <EditorModal kind={kind} initialData={editingItem} onClose={closeModal} />}
    </div>
  );
}
